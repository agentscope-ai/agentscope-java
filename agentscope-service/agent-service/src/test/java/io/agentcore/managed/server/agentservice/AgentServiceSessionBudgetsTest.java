/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.agentservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.agentservice.api.AgentServiceSessionBudgetController;
import io.agentcore.managed.server.agentservice.config.SessionApiErrors;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionRepository;
import io.agentcore.managed.server.agentservice.session.AgentServiceSessionAccess;
import io.agentcore.managed.server.agentservice.session.AgentServiceSessionBudgets;
import io.agentcore.managed.server.agentservice.session.ManagedSessionTurnsApi;
import io.agentcore.managed.server.runtime.managed.ManagedAdmissionStore;
import io.agentcore.managed.server.runtime.managed.ManagedBudgetStore;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import io.agentcore.managed.server.runtime.managed.ManagedStateException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;

class AgentServiceSessionBudgetsTest {
    @Test
    void budgetOptInCoexistsWithTurnInputsWithoutEnablingQueueOrExtraEvents() {
        var headers = new HttpHeaders();
        headers.set("Managed-Agents-Extensions", "session-budgets-v1");
        assertFalse(ManagedSessionTurnsApi.extensions(headers));
        assertFalse(ManagedSessionTurnsApi.queueExtension(headers));
        headers.set("Managed-Agents-Extensions", "turn-inputs-v1,session-budgets-v1");
        assertTrue(ManagedSessionTurnsApi.extensions(headers));
        assertFalse(ManagedSessionTurnsApi.queueExtension(headers));
    }

    @Test
    void budgetRevisionAndReadsUseTheSharedLedgerWithoutHarnessRuntimeRPC() {
        var mapper = new ObjectMapper();
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:budget-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        ManagedAdmissionStore.initialize(source);
        var scope = new ManagedScope("t", "n", "session");
        new ManagedAdmissionStore(source, mapper).createSession(scope, "{}");
        var row = new AgentServiceSessionEntity();
        row.id = scope.sessionId();
        row.tenant = scope.tenant();
        row.namespace = scope.namespace();
        row.runtimeKind = "managed";
        row.state = "created";
        row.lifecycle = "active";
        var sessions = mock(AgentServiceSessionRepository.class);
        when(sessions.findById(row.id)).thenReturn(Optional.of(row));
        var budgets =
                new AgentServiceSessionBudgets(
                        sessions,
                        mock(AgentServiceSessionAccess.class),
                        new ManagedBudgetStore(source, mapper, (model, usage) -> null));
        var headers = new HttpHeaders();
        var first = budgets.configure(row.id, Map.of("max_model_calls", 20), "\"0\"", headers);
        assertEquals(1L, first.get("revision"));
        assertEquals(
                first, budgets.configure(row.id, Map.of("max_model_calls", 20), null, headers));
        assertEquals(
                "budget_revision_conflict",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        budgets.configure(
                                                row.id,
                                                Map.of("max_model_calls", 30),
                                                "0",
                                                headers))
                        .code());
        for (String invalid : new String[] {"-1", "W/\"1\"", "1\"", "\\\"1\\\""}) {
            assertEquals(
                    HttpStatus.BAD_REQUEST,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            budgets.configure(
                                                    row.id,
                                                    Map.of("max_model_calls", 30),
                                                    invalid,
                                                    headers))
                            .getStatusCode());
        }
        assertEquals(first, budgets.get(row.id, headers).block());
        var client =
                WebTestClient.bindToController(new AgentServiceSessionBudgetController(budgets))
                        .webFilter(new SessionApiErrors(mapper))
                        .build();
        client.get().uri("/v1/sessions/session/budget").exchange().expectStatus().isBadRequest();
        client.put()
                .uri("/v1/sessions/session/budget")
                .bodyValue(Map.of("max_model_calls", 30))
                .exchange()
                .expectStatus()
                .isBadRequest();
        client.get()
                .uri("/v1/sessions/session/budget")
                .header("Managed-Agents-Extensions", "turn-inputs-v1,session-budgets-v1")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.limits.max_model_calls")
                .isEqualTo(20);
        client.put()
                .uri("/v1/sessions/session/budget")
                .header("Managed-Agents-Extensions", "turn-inputs-v1,session-budgets-v1")
                .header("If-Match", "1")
                .bodyValue(Map.of("max_model_calls", 30))
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectBody()
                .jsonPath("$.revision")
                .isEqualTo(2);
        assertThrows(
                IllegalArgumentException.class,
                () -> budgets.configure(row.id, Map.of("max_model_calls", 0), null, headers));
        row.rootSessionId = "root";
        assertEquals(
                HttpStatus.CONFLICT,
                assertThrows(
                                ResponseStatusException.class,
                                () -> budgets.configure(row.id, Map.of(), null, headers))
                        .getStatusCode());
    }
}
