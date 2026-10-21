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
package io.agentscope.builder.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.agentscope.builder.portal.persistence.PortalSessionEntity;
import io.agentscope.builder.portal.persistence.PortalSessionRepository;
import io.agentscope.builder.portal.session.PortalSessionAccess;
import io.agentscope.builder.portal.session.PortalSessionTrace;
import io.agentscope.builder.web.auth.JwtService;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class PortalSessionTraceTest {
    private final PortalSessionRepository sessions = mock(PortalSessionRepository.class);
    private final PortalSessionAccess access = mock(PortalSessionAccess.class);
    private final JwtService jwt = new JwtService("trace-fixture-secret-at-least-32-characters");

    private HttpHeaders caller(String role) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(jwt.generate("reader", "reader", List.of(role)));
        return headers;
    }

    @Test
    void disabledOrdinaryAndApplicationCallersCannotReadNativeHistory() {
        var off =
                new PortalSessionTrace(
                        sessions, access, jwt, false, false, "http://127.0.0.1:1", "token");
        assertEquals(
                HttpStatus.NOT_FOUND,
                assertThrows(
                                ResponseStatusException.class,
                                () -> off.get("session", 0, null, 2, caller("admin")).block())
                        .getStatusCode());
        var on =
                new PortalSessionTrace(
                        sessions, access, jwt, true, false, "http://127.0.0.1:1", "token");
        assertEquals(
                HttpStatus.FORBIDDEN,
                assertThrows(
                                ResponseStatusException.class,
                                () -> on.get("session", 0, null, 2, caller("user")).block())
                        .getStatusCode());
        var key = caller("admin");
        key.set("X-API-Key", "application-key");
        assertEquals(
                HttpStatus.FORBIDDEN,
                assertThrows(
                                ResponseStatusException.class,
                                () -> on.get("session", 0, null, 2, key).block())
                        .getStatusCode());
    }

    @Test
    void childReadsUseTheRootOwnerAndRevokedTraceScopeCannotBeRestoredByAdminJwt()
            throws Exception {
        var row = new PortalSessionEntity();
        row.id = "child";
        row.rootSessionId = "root";
        row.ownerId = "root-owner";
        row.runtimeKind = "managed";
        when(sessions.findById("child")).thenReturn(Optional.of(row));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    assertEquals(
                            "/api/internal/runtime/managed/sessions/root/trace",
                            exchange.getRequestURI().getPath());
                    assertEquals(
                            "root-owner",
                            exchange.getRequestHeaders().getFirst("X-Builder-Internal-User"));
                    assertEquals(
                            "token",
                            exchange.getRequestHeaders().getFirst("X-Builder-Internal-Token"));
                    assertEquals(
                            "after=2&limit=3&through=8&child=child",
                            exchange.getRequestURI().getRawQuery());
                    byte[] body =
                            "{\"data\":[],\"as_of_seq\":8,\"next_seq\":2,\"has_more\":true}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        try {
            var reader =
                    new PortalSessionTrace(
                            sessions,
                            access,
                            jwt,
                            true,
                            false,
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            "token");
            var view = reader.get("child", 2, 8L, 3, caller("admin")).block(Duration.ofSeconds(5));
            assertEquals(8, view.get("as_of_seq"));
            doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND))
                    .when(access)
                    .authorize(eq("root"), any(), eq("trace"));
            assertEquals(
                    HttpStatus.NOT_FOUND,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () -> reader.get("child", 2, 8L, 3, caller("admin")).block())
                            .getStatusCode());
        } finally {
            server.stop(0);
        }
    }
}
