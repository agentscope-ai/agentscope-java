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
package io.agentcore.managed.server.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.agentcore.managed.server.runtime.ManagedCatalog;
import io.agentcore.managed.server.web.managed.MemoryDocumentStore;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ControlPlaneMemoryClientTest {
    private record Request(
            String method, String path, String token, String owner, String env, String body) {}

    private final ObjectMapper mapper = new ObjectMapper();
    private final ManagedCatalog catalog = mock(ManagedCatalog.class);
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger status = new AtomicInteger(200);
    private HttpServer server;
    private MemoryDocumentStore documents;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    try (exchange) {
                        var headers = exchange.getRequestHeaders();
                        String path = exchange.getRequestURI().getPath();
                        requests.add(
                                new Request(
                                        exchange.getRequestMethod(),
                                        path,
                                        headers.getFirst("X-Managed-Agents-Internal-Token"),
                                        headers.getFirst("X-Managed-Agents-Internal-User"),
                                        headers.getFirst("X-Managed-Agents-Internal-Environment"),
                                        new String(
                                                exchange.getRequestBody().readAllBytes(),
                                                StandardCharsets.UTF_8)));
                        if (exchange.getRequestMethod().equals("DELETE") && status.get() == 200) {
                            exchange.sendResponseHeaders(204, -1);
                            return;
                        }
                        String document =
                                "{\"id\":\"doc\",\"storeId\":\"notes\",\"path\":\"nested/note.md\",\"content\":\"live"
                                    + " content\",\"headVersion\":2,\"createdAt\":1,\"updatedAt\":2}";
                        byte[] body =
                                (path.endsWith("/memories") ? "[" + document + "]" : document)
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(status.get(), body.length);
                        exchange.getResponseBody().write(body);
                    }
                });
        server.start();
        var client =
                new ControlPlaneClient(
                        "http://127.0.0.1:" + server.getAddress().getPort(), "local-test-token");
        client.setManagedCatalog(catalog);
        when(catalog.resolve("native-session"))
                .thenReturn(configuration("native-session", "namespace-owner", List.of("notes")));
        documents = client.memoryDocuments("native-session", "notes");
    }

    private SessionResolveResult configuration(String session, String owner, List<String> stores) {
        return mapper.convertValue(
                Map.of(
                        "session",
                                Map.of(
                                        "id",
                                        session,
                                        "ownerId",
                                        owner,
                                        "environmentId",
                                        "environment",
                                        "memoryStoreIds",
                                        stores),
                        "memoryMounts",
                                stores.stream()
                                        .map(id -> Map.of("storeId", id, "name", id))
                                        .toList()),
                SessionResolveResult.class);
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void nativeMountUsesLiveResourceApiAndRetainsOwnerEnvironmentAndExpectedVersion()
            throws Exception {
        assertEquals("live content", documents.list().get(0).content());
        assertEquals(2, documents.get("nested/note.md").headVersion());
        documents.put("nested/note.md", "updated", 2);
        documents.delete("nested/note.md");
        assertEquals(
                List.of("GET", "GET", "PUT", "DELETE"),
                requests.stream().map(Request::method).toList());
        for (var request : requests) {
            assertTrue(request.path().startsWith("/api/internal/memory-stores/notes/memories"));
            assertEquals("local-test-token", request.token());
            assertEquals("namespace-owner", request.owner());
            assertEquals("environment", request.env());
        }
        assertEquals(
                "/api/internal/memory-stores/notes/memories/nested/note.md",
                requests.get(1).path());
        assertEquals(2, mapper.readTree(requests.get(2).body()).path("expectedVersion").asInt());
        assertEquals("updated", mapper.readTree(requests.get(2).body()).path("content").asText());
    }

    @Test
    void unboundMountAndWrongSessionOrOwnerAreRejectedBeforeNetworkAccess() {
        when(catalog.resolve("native-session"))
                .thenReturn(configuration("native-session", "namespace-owner", List.of()));
        assertEquals(
                HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class, documents::list).getStatusCode());
        assertThrows(ResponseStatusException.class, () -> documents.get("note.md"));
        assertThrows(ResponseStatusException.class, () -> documents.put("note.md", "text", 0));
        assertThrows(ResponseStatusException.class, () -> documents.delete("note.md"));
        when(catalog.resolve("native-session"))
                .thenReturn(
                        configuration("different-session", "namespace-owner", List.of("notes")));
        assertThrows(ResponseStatusException.class, documents::list);
        when(catalog.resolve("native-session"))
                .thenReturn(configuration("native-session", "", List.of("notes")));
        assertThrows(ResponseStatusException.class, documents::list);
        assertTrue(requests.isEmpty());
    }

    @Test
    void resourceRevocationAndVersionConflictArePreserved() {
        status.set(404);
        assertEquals(
                HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> documents.get("note.md"))
                        .getStatusCode());
        status.set(409);
        assertEquals(
                HttpStatus.CONFLICT,
                assertThrows(
                                ResponseStatusException.class,
                                () -> documents.put("note.md", "text", 0))
                        .getStatusCode());
        status.set(403);
        assertEquals(
                HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class, () -> documents.delete("note.md"))
                        .getStatusCode());
    }
}
