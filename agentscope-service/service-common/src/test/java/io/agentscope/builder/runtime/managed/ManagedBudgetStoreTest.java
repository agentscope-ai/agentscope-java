/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.web.managed.SessionUsagePricer;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.util.JsonUtils;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Accounting transactions and limits; transport/guard ownership has separate contract tests. */
class ManagedBudgetStoreTest {
    @TempDir private Path managedCheckpointRoot;

    private final ManagedScope scope = new ManagedScope("t", "n", "s");
    private ManagedAdmissionStore admission;
    private ManagedBudgetStore budgets;
    private ManagedJournalStore journal;
    private String turn;

    @BeforeEach
    void setup() {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:budget-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        ManagedAdmissionStore.initialize(source);
        var mapper = new ObjectMapper();
        admission = new ManagedAdmissionStore(source, mapper);
        admission.createSession(scope, "{}");
        budgets =
                new ManagedBudgetStore(
                        source,
                        mapper,
                        (model, usage) ->
                                new SessionUsagePricer.Quote(
                                        new BigDecimal(
                                                String.valueOf(
                                                        usage.getOrDefault("inputTokens", 0))),
                                        "USD"));
        journal =
                new ManagedJournalStore(
                        source,
                        mapper,
                        budgets,
                        new ManagedCheckpointObjects(mapper, managedCheckpointRoot));
        turn = active(scope, "first");
    }

    private String active(ManagedScope session, String key) {
        String id = admission.acceptTurn(session, "u", key, "{}").id();
        admission.reservePublication(session, id);
        admission.confirmPublication(session, id, "broker");
        admission.activate(session, id, 1, "brain");
        return id;
    }

    private ManagedTurnExecution execution(ManagedScope session, String id, long epoch) {
        var execution = mock(ManagedTurnExecution.class);
        when(execution.scope()).thenReturn(session);
        when(execution.turnId()).thenReturn(id);
        when(execution.epoch()).thenReturn(epoch);
        when(execution.admission()).thenReturn(admission);
        return execution;
    }

    @Test
    void modelReservationIsIdempotentAndBudgetPreventsAnotherCall() {
        budgets.configure(scope, Map.of("max_model_calls", 1), 0L);
        var execution = execution(scope, turn, 1);
        budgets.reserve(execution, "call", "model");
        budgets.reserve(execution, "call", "model");
        assertEquals(1L, number("model_calls"));
        assertEquals(
                "budget_exceeded",
                assertThrows(
                                ManagedStateException.class,
                                () -> budgets.reserve(execution, "next", "model"))
                        .code());
        assertEquals(
                "model_identity_conflict",
                assertThrows(
                                ManagedStateException.class,
                                () -> budgets.reserve(execution, "call", "different-model"))
                        .code());
    }

    @Test
    void nativeEndAndAccountingCommitAtomicallyAndStaleReplayCannotBillTwice() {
        budgets.reserve(execution(scope, turn, 1), "call", "model");
        var end =
                event(
                        scope,
                        turn,
                        1,
                        "model/end",
                        Map.of(
                                "modelCallId",
                                "call",
                                "status",
                                "completed",
                                "usage",
                                Map.of("inputTokens", 12, "outputTokens", 8)));
        var bad = event(scope, turn, 2, "state/checkpoint", Map.of("stateJson", 42));
        assertThrows(
                ManagedStateException.class,
                () ->
                        journal.commit(
                                journal.objects()
                                        .prepare(batch(scope, turn, "bad", List.of(end, bad)))));
        assertEquals(1L, number("in_flight_calls"));
        assertEquals(0L, journal.head(scope).sequence());
        var good =
                event(scope, turn, 2, "state/checkpoint", Map.of("stateJson", "{\"context\":[]}"));
        var commit = batch(scope, turn, "good", List.of(end, good));
        journal.commit(journal.objects().prepare(commit));
        assertEquals(
                "native_sequence_conflict",
                assertThrows(
                                ManagedStateException.class,
                                () -> journal.commit(journal.objects().prepare(commit)))
                        .code());
        assertEquals(2, journal.head(scope).sequence());
        assertEquals(20L, number("total_tokens"));
        assertEquals(0L, number("in_flight_calls"));
        var usage = (Map<?, ?>) budgets.get(scope).get("usage");
        assertEquals(new BigDecimal("12"), new BigDecimal(usage.get("estimated_cost").toString()));
    }

    @Test
    void childModelsUseTheRootBudget() {
        budgets.configure(scope, Map.of("max_model_calls", 1), 0L);
        var child = new ManagedScope("t", "n", "child");
        admission.createSession(child, "{\"root_session_id\":\"s\"}");
        String childTurn = active(child, "child");
        budgets.reserve(execution(child, childTurn, 1), "child-call", "model");
        assertEquals(1L, number("model_calls"));
        assertThrows(
                ManagedStateException.class,
                () -> budgets.reserve(execution(scope, turn, 1), "parent-call", "model"));
        assertEquals(budgets.get(scope), budgets.get(child));
    }

    @Test
    void lostEpochWithUnreportedUsageCannotBypassTokenBudget() {
        budgets.configure(scope, Map.of("max_total_tokens", 100), 0L);
        budgets.reserve(execution(scope, turn, 1), "old", "model");
        admission.activate(scope, turn, 1, "new-brain");
        assertEquals(
                "usage_unavailable",
                assertThrows(
                                ManagedStateException.class,
                                () -> budgets.reserve(execution(scope, turn, 2), "new", "model"))
                        .getMessage());
        assertEquals(0L, number("in_flight_calls"));
        assertEquals(1L, number("unreported_calls"));
    }

    @Test
    void committedModelUsageAlsoEnforcesTheApplicationTokenBudget() {
        admission.applicationPolicy(scope, Map.of("version", 1, "tokenBudget", 10));
        budgets.reserve(execution(scope, turn, 1), "first", "model");
        journal.commit(
                journal.objects()
                        .prepare(
                                batch(
                                        scope,
                                        turn,
                                        "usage",
                                        List.of(
                                                event(
                                                        scope,
                                                        turn,
                                                        1,
                                                        "model/end",
                                                        Map.of(
                                                                "modelCallId",
                                                                "first",
                                                                "status",
                                                                "completed",
                                                                "usage",
                                                                Map.of(
                                                                        "inputTokens",
                                                                        10,
                                                                        "outputTokens",
                                                                        0)))))));
        assertEquals(
                "application_token_budget",
                assertThrows(
                                ManagedStateException.class,
                                () -> budgets.reserve(execution(scope, turn, 1), "second", "model"))
                        .getMessage());
    }

    private long number(String key) {
        var usage = (Map<?, ?>) budgets.get(scope).get("usage");
        return usage.get(key) instanceof Number n ? n.longValue() : 0;
    }

    private static SessionEvent event(
            ManagedScope scope, String turn, long seq, String type, Object payload) {
        return new SessionEvent(
                1,
                UUID.randomUUID().toString(),
                seq,
                System.currentTimeMillis(),
                type,
                "run",
                turn,
                true,
                JsonUtils.getJsonCodec().toJson(payload));
    }

    private static ManagedRuntimeBatch batch(
            ManagedScope scope, String turn, String id, List<SessionEvent> events) {
        return new ManagedRuntimeBatch(
                scope, turn, 1, "brain", "writer", id, 0, events, List.of(), List.of(), null);
    }
}
