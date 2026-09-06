/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.control;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.builder.web.managed.SessionEventDto;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ControlPlaneClientExecutionScopeTest {

    @Test
    void aTurnKeepsItsAttemptFenceAcrossLaterSessionResolves() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger resolves = new AtomicInteger();
        List<Map<String, Object>> events = new ArrayList<>();
        List<Map<String, Object>> runtimePatches = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(
                "/api/internal/sessions/session-a/resolve",
                exchange -> {
                    int call = resolves.incrementAndGet();
                    String attempt = call == 1 ? "attempt-old" : "attempt-new";
                    writeJson(exchange, resolveBody(attempt, call), mapper);
                });
        server.createContext(
                "/api/internal/runtime-sessions/session-a/events",
                exchange -> {
                    synchronized (events) {
                        events.add(
                                mapper.readValue(
                                        exchange.getRequestBody(), new TypeReference<>() {}));
                    }
                    exchange.sendResponseHeaders(204, -1);
                    exchange.close();
                });
        server.createContext(
                "/api/internal/sessions/session-a/runtime",
                exchange -> {
                    synchronized (runtimePatches) {
                        runtimePatches.add(
                                mapper.readValue(
                                        exchange.getRequestBody(), new TypeReference<>() {}));
                    }
                    exchange.sendResponseHeaders(204, -1);
                    exchange.close();
                });
        server.start();
        try {
            ControlPlaneClient client =
                    new ControlPlaneClient(
                            "http://localhost:" + server.getAddress().getPort(),
                            "internal-token",
                            mapper);
            SessionEventDto event =
                    new SessionEventDto(
                            "event-a", "session-a", 1, "session.error", Map.of(), null, 1);

            ControlPlaneClient.ManagedExecutionScope oldScope =
                    client.beginManagedExecution("session-a");
            assertThat(client.managedExecutionScope("session-a"))
                    .extracting(
                            ControlPlaneClient.ManagedExecutionScope::tenant,
                            ControlPlaneClient.ManagedExecutionScope::agentTaskId)
                    .containsExactly("tenant-a", "task-a");
            client.resolveSession("session-a");
            client.appendSessionEvent(event);
            client.endManagedExecution("session-a", oldScope);
            ControlPlaneClient.ManagedExecutionScope newScope =
                    client.beginManagedExecution("session-a");
            client.endManagedExecution("session-a", oldScope);
            assertThat(client.managedExecutionScope("session-a")).isEqualTo(newScope);
            client.appendSessionEvent(event);
            client.patchSessionRuntime("session-a", "idle", null, "owner-a", oldScope);

            assertThat(events).hasSize(2);
            assertThat(events.get(0))
                    .containsEntry("attemptId", "attempt-old")
                    .containsEntry("dispatchGeneration", 1);
            assertThat(events.get(1))
                    .containsEntry("attemptId", "attempt-new")
                    .containsEntry("dispatchGeneration", 3);
            assertThat(runtimePatches)
                    .singleElement()
                    .satisfies(
                            body ->
                                    assertThat(body)
                                            .containsEntry("status", "idle")
                                            .containsEntry("agentTaskId", "task-a")
                                            .containsEntry("attemptId", "attempt-old")
                                            .containsEntry("dispatchGeneration", 1)
                                            .containsEntry("turnId", "turn-1"));
        } finally {
            server.stop(0);
        }
    }

    private static Map<String, Object> resolveBody(String attemptId, int generation) {
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("id", "session-a");
        session.put("ownerId", "owner-a");
        session.put("agentId", "agent-a");
        session.put("agentVersion", 1);
        session.put("memoryStoreIds", List.of());
        session.put("vaultIds", List.of());
        session.put("resources", List.of());
        session.put("status", "running");
        session.put("stopReason", Map.of());
        session.put("createdAt", 1);
        session.put("updatedAt", 1);
        return Map.of(
                "session",
                session,
                "executionContext",
                Map.of(
                        "taskContext",
                        Map.of("task", Map.of("id", "task-a", "tenant", "tenant-a")),
                        "attemptId",
                        attemptId,
                        "dispatchGeneration",
                        generation,
                        "turnId",
                        "turn-" + generation));
    }

    private static void writeJson(HttpExchange exchange, Object value, ObjectMapper mapper)
            throws IOException {
        byte[] bytes = mapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
