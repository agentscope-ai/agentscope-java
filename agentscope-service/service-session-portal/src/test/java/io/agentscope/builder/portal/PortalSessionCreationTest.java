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
package io.agentscope.builder.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.builder.portal.persistence.PortalSessionRepository;
import io.agentscope.builder.portal.session.PortalSessionAccess;
import io.agentscope.builder.portal.session.PortalSessionCreation;
import io.agentscope.builder.portal.session.PortalSessionProvisioning;
import io.agentscope.builder.runtime.protocol.RuntimeSessionAdmission;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class PortalSessionCreationTest {
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

    private RuntimeSessionAdmission create(String admitted, String projected) throws Exception {
        var mapper = new ObjectMapper();
        var called = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/internal/",
                exchange -> {
                    assertEquals(
                            "service-identity",
                            exchange.getRequestHeaders().getFirst("X-Builder-Internal-Token"));
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
                                                Map.of("id", "session", "ownerId", "owner")),
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
            var provisioning = mock(PortalSessionProvisioning.class);
            when(provisioning.readyView("session")).thenReturn(Map.of("id", "session"));
            var creation =
                    new PortalSessionCreation(
                            mock(PortalSessionRepository.class),
                            provisioning,
                            mock(PortalSessionAccess.class),
                            mapper,
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            "service-identity");
            var headers = new HttpHeaders();
            headers.setBearerAuth("real-caller");
            headers.set("Idempotency-Key", "creation-key");
            assertEquals(
                    Map.of("id", "session"), creation.create(Map.of("target", "agent"), headers));
            assertEquals("projection", called.get());
            var captor = ArgumentCaptor.forClass(RuntimeSessionAdmission.class);
            verify(provisioning).provision(captor.capture());
            return captor.getValue();
        } finally {
            server.stop(0);
        }
    }
}
