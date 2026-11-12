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
package io.agentscope.builder.web.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class ManagedSessionChannelBridgeTest {
    @Test
    void registersRuntimeSessionBeforeDispatchAndPollsOnlyNewEvents() throws Exception {
        verifyDispatch(false);
    }

    @Test
    void failedRegistrationDoesNotDispatchToDataPlane() throws Exception {
        verifyDispatch(true);
    }

    private void verifyDispatch(boolean failRegistration) throws Exception {
        var json = new ObjectMapper();
        var calls = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    String route = exchange.getRequestMethod() + " " + exchange.getRequestURI();
                    calls.add(route);
                    String response;
                    int status = 200;
                    if (route.equals("POST /api/internal/managed-sessions/find-or-create")) {
                        var body = json.readTree(exchange.getRequestBody());
                        assertEquals(
                                "verified-channel:demo:owner:peer",
                                body.path("externalKey").asText());
                        assertEquals("owner", body.path("ownerId").asText());
                        assertEquals("agent", body.path("agentId").asText());
                        assertEquals(
                                "owner",
                                exchange.getRequestHeaders().getFirst("X-Builder-Internal-User"));
                        response = "{\"id\":\"sess-existing\"}";
                        if (failRegistration) status = 503;
                    } else if (route.equals("POST /api/sessions/sess-existing/events")) {
                        assertEquals(
                                "hello",
                                json.readTree(exchange.getRequestBody())
                                        .path("events")
                                        .get(0)
                                        .path("payload")
                                        .path("text")
                                        .asText());
                        response = "[{\"seq\":2,\"type\":\"user.message\",\"createdAt\":1}]";
                    } else if (route.equals("GET /api/sessions/sess-existing/events?after=2")) {
                        response =
                                "[{\"seq\":3,\"type\":\"agent.message\",\"createdAt\":1,\"payload\":{\"text\":\"answer\"}},{\"seq\":4,\"type\":\"session.status_idle\",\"createdAt\":1}]";
                    } else {
                        response = "{}";
                        status = 404;
                    }
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        try {
            var client = WebClient.create("http://127.0.0.1:" + server.getAddress().getPort());
            var bridge = new ManagedSessionChannelBridge(client, client, 5000, 10);
            if (failRegistration) {
                assertThrows(
                        RuntimeException.class,
                        () ->
                                bridge.dispatchAndAwaitReply(
                                                "owner",
                                                "agent",
                                                "verified-channel:demo:owner:peer",
                                                "hello")
                                        .block(Duration.ofSeconds(10)));
                assertEquals(List.of("POST /api/internal/managed-sessions/find-or-create"), calls);
            } else {
                assertEquals(
                        "answer",
                        bridge.dispatchAndAwaitReply(
                                        "owner",
                                        "agent",
                                        "verified-channel:demo:owner:peer",
                                        "hello")
                                .block(Duration.ofSeconds(10)));
                assertEquals(
                        List.of(
                                "POST /api/internal/managed-sessions/find-or-create",
                                "POST /api/sessions/sess-existing/events",
                                "GET /api/sessions/sess-existing/events?after=2"),
                        calls);
            }
        } finally {
            server.stop(0);
        }
    }
}
