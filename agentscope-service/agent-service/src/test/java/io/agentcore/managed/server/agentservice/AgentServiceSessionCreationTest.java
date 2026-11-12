/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.agentservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionRepository;
import io.agentcore.managed.server.agentservice.session.AgentServiceSessionAccess;
import io.agentcore.managed.server.agentservice.session.AgentServiceSessionCreation;
import io.agentcore.managed.server.agentservice.session.AgentServiceSessionProvisioning;
import io.agentcore.managed.server.runtime.managed.ManagedStateException;
import io.agentcore.managed.server.runtime.protocol.RuntimeSessionAdmission;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class AgentServiceSessionCreationTest {
    @Test
    void connectedCreationRetainsTheAdmittedBackendAndCaller() throws Exception {
        for (String kind : List.of("managed", "hosted-runtime", "workflow", "team")) {
            var admission = create(kind, kind);
            assertEquals(kind, admission.bootstrap().runtimeKind());
            assertEquals("owner", admission.bootstrap().context().actor().get("user"));
            assertEquals("session", admission.bootstrap().context().sessionId());
            assertEquals("a".repeat(64), admission.policy().get("scope_id"));
        }
    }

    @Test
    void creationRejectsBackendMismatchAndUnconnectedBackends() {
        assertEquals(
                HttpStatus.BAD_GATEWAY,
                assertThrows(
                                ResponseStatusException.class,
                                () -> create("hosted-runtime", "managed"))
                        .getStatusCode());
        assertEquals(
                HttpStatus.NOT_IMPLEMENTED,
                assertThrows(ResponseStatusException.class, () -> create("external", "external"))
                        .getStatusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"pi", "pi-durable", "codex"})
    void rejectsUnsupportedControlPlaneDefinitionsAndOverridesBeforeProvisioning(String kernel) {
        for (String field : List.of("contextPolicy", "jev")) {
            assertEquals(
                    "unsupported_capability",
                    assertThrows(
                                    ManagedStateException.class,
                                    () ->
                                            create(
                                                    "managed",
                                                    "managed",
                                                    kernel,
                                                    Map.of(field, Map.of("enabled", true)),
                                                    "{}"))
                            .code());
            assertEquals(
                    "unsupported_capability",
                    assertThrows(
                                    ManagedStateException.class,
                                    () ->
                                            create(
                                                    "managed",
                                                    "managed",
                                                    kernel,
                                                    Map.of("name", "basic"),
                                                    "{\"" + field + "\":{\"enabled\":true}}"))
                            .code());
        }
        assertEquals(
                "unsupported_capability",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        create(
                                                "managed",
                                                "managed",
                                                kernel,
                                                Map.of(
                                                        "tools",
                                                        List.of(
                                                                Map.of(
                                                                        "type",
                                                                        "agent_toolset",
                                                                        "configs",
                                                                        List.of(
                                                                                Map.of(
                                                                                        "name",
                                                                                        "web_search",
                                                                                        "enabled",
                                                                                        true))))),
                                                "{}"))
                        .code());
    }

    private RuntimeSessionAdmission create(String admitted, String projected) throws Exception {
        return create(
                admitted, projected, "agentscope-java", Map.of("name", "creation-fixture"), "{}");
    }

    private RuntimeSessionAdmission create(
            String admitted,
            String projected,
            String kernel,
            Map<String, Object> snapshot,
            String overrides)
            throws Exception {
        var mapper = new ObjectMapper();
        var called = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/internal/",
                exchange -> {
                    assertEquals(
                            "service-identity",
                            exchange.getRequestHeaders()
                                    .getFirst("X-Managed-Agents-Internal-Token"));
                    Map<String, Object> response;
                    if (exchange.getRequestMethod().equals("POST")) {
                        assertEquals(
                                "Bearer real-caller",
                                exchange.getRequestHeaders().getFirst("Authorization"));
                        assertEquals(
                                "creation-key",
                                exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                        exchange.getRequestBody().readAllBytes();
                        response =
                                Map.of(
                                        "id",
                                        "session",
                                        "tenant",
                                        "tenant",
                                        "runtimeKind",
                                        admitted,
                                        "admissionActor",
                                        Map.of("user", "owner", "key_hash", "", "internal", false));
                    } else {
                        assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
                        called.set("projection");
                        response =
                                Map.of(
                                        "runtimeKind",
                                        projected,
                                        "managed",
                                        projected.equals("managed"),
                                        "resolved",
                                        Map.of(
                                                "session",
                                                Map.of(
                                                        "id",
                                                        "session",
                                                        "ownerId",
                                                        "owner",
                                                        "agentOverridesJson",
                                                        overrides),
                                                "agentSnapshot",
                                                snapshot),
                                        "policy",
                                        Map.of(
                                                "scope_id",
                                                "a".repeat(64),
                                                "tenant",
                                                "tenant",
                                                "namespace",
                                                "space",
                                                "valid_until",
                                                System.currentTimeMillis() + 60000),
                                        "view",
                                        Map.of("id", "session", "runtimeKind", projected));
                    }
                    var raw = mapper.writeValueAsBytes(response);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, raw.length);
                    exchange.getResponseBody().write(raw);
                    exchange.close();
                });
        server.start();
        try {
            var provisioning = mock(AgentServiceSessionProvisioning.class);
            when(provisioning.readyView("session")).thenReturn(Map.of("id", "session"));
            var creation =
                    new AgentServiceSessionCreation(
                            mock(AgentServiceSessionRepository.class),
                            provisioning,
                            mock(AgentServiceSessionAccess.class),
                            mapper,
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            "service-identity");
            var headers = new HttpHeaders();
            headers.setBearerAuth("real-caller");
            headers.set("Idempotency-Key", "creation-key");
            ReflectionTestUtils.setField(creation, "harnessType", kernel);
            try {
                assertEquals(
                        Map.of("id", "session"),
                        creation.create(Map.of("target", "agent"), headers));
            } catch (ManagedStateException rejected) {
                verifyNoInteractions(provisioning);
                throw rejected;
            }
            assertEquals("projection", called.get());
            var captor = ArgumentCaptor.forClass(RuntimeSessionAdmission.class);
            verify(provisioning).provision(captor.capture());
            return captor.getValue();
        } finally {
            server.stop(0);
        }
    }
}
