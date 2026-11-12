/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class ManagedSessionClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private ServiceClient transport;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        transport =
                ServiceClient.builder("http://127.0.0.1:" + server.getAddress().getPort())
                        .apiKey("original-key")
                        .tenant("tenant")
                        .namespace("namespace")
                        .build();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void terminalTurnWithoutOutputCompletesWithAnEmptyResult() throws Exception {
        server.createContext(
                "/",
                exchange ->
                        respond(
                                exchange,
                                exchange.getRequestURI().getPath().endsWith("/output")
                                        ? "{\"data\":[],\"output_available\":false,\"has_more\":false,\"next_cursor\":null}"
                                        : "{\"status\":\"failed\"}"));
        assertEquals(
                List.of(),
                transport.managedSessions().finalOutput("s", "t", Duration.ofSeconds(1)));
    }

    @Test
    void outputRetriesReadOnlyAndFiltersAndCursorValidationRemainBounded() throws Exception {
        var paths = new ArrayList<String>();
        var unavailable = new AtomicBoolean(true);
        var cycle = new AtomicBoolean(false);
        server.createContext(
                "/",
                exchange -> {
                    assertEquals("GET", exchange.getRequestMethod());
                    String path = exchange.getRequestURI().toString();
                    paths.add(path);
                    if (path.contains("/output")) {
                        if (unavailable.getAndSet(false)) {
                            exchange.sendResponseHeaders(503, 2);
                            exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
                            exchange.close();
                            return;
                        }
                        boolean more = cycle.get() || !path.contains("after=");
                        respond(
                                exchange,
                                "{\"data\":[{\"id\":\"answer\"}],\"output_available\":true,\"has_more\":"
                                        + more
                                        + ",\"next_cursor\":"
                                        + (more ? "\"cursor\"" : "null")
                                        + "}");
                    } else respond(exchange, "{\"status\":\"completed\"}");
                });
        var client = transport.managedSessions();
        client.turns("s", null, 100, "asc", "completed", "event");
        assertTrue(paths.get(paths.size() - 1).contains("input_event_id=event"));
        client.inputs("s", "t", null, 100, "applied", "input");
        assertTrue(paths.get(paths.size() - 1).contains("event_id=input"));
        assertEquals(2, client.finalOutput("s", "t", Duration.ofSeconds(2)).size());
        assertEquals(paths.get(3), paths.get(4));
        cycle.set(true);
        assertThrows(IOException.class, () -> client.finalOutput("s", "t", Duration.ofSeconds(2)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        client.submit(
                                "s",
                                mapper.createObjectNode()
                                        .put("message", "text")
                                        .put("durable", false),
                                "key"));
        assertThrows(
                IllegalArgumentException.class, () -> client.finalOutput("s", "t", Duration.ZERO));
    }

    @Test
    void finalOutputBoundsAnInFlightRead() {
        server.createContext(
                "/",
                exchange -> {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                    exchange.close();
                });
        long start = System.nanoTime();
        assertThrows(
                IOException.class,
                () -> transport.managedSessions().finalOutput("s", "t", Duration.ofMillis(50)));
        assertTrue(System.nanoTime() - start < Duration.ofSeconds(1).toNanos());
    }

    @Test
    void nativeWebhooksKeepIdentityKeysFiltersAndBoundedReceiptPages() throws Exception {
        var path = new AtomicReference<String>();
        var method = new AtomicReference<String>();
        var body = new AtomicReference<String>();
        var key = new AtomicReference<String>();
        server.createContext(
                "/",
                exchange -> {
                    originalIdentity(exchange);
                    path.set(exchange.getRequestURI().toString());
                    method.set(exchange.getRequestMethod());
                    body.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    key.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                    respond(exchange, "{}");
                });
        var client = transport.managedSessions();
        client.webhook(
                "session/one",
                "https://receiver.example/events",
                List.of("tool.operation.completed"),
                "hook-key");
        assertEquals("/v1/sessions/session%2Fone/webhooks", path.get());
        assertEquals("hook-key", key.get());
        assertEquals(
                "tool.operation.completed",
                mapper.readTree(body.get()).path("event_types").get(0).asText());
        client.webhooks("session", "a".repeat(64), 25);
        assertTrue(path.get().startsWith("/v1/sessions/session/webhooks?"));
        assertTrue(path.get().contains("limit=25"));
        client.webhookDeliveries("session", "hook/one", null, 100);
        assertEquals("/v1/sessions/session/webhooks/hook%2Fone/deliveries?limit=100", path.get());
        client.retryWebhook("session", "hook");
        assertEquals("POST", method.get());
        assertEquals("/v1/sessions/session/webhooks/hook/retry", path.get());
        client.deleteWebhook("session", "hook");
        assertEquals("DELETE", method.get());
        assertThrows(IllegalArgumentException.class, () -> client.webhooks("session", null, 101));
        assertThrows(
                IllegalArgumentException.class, () -> client.webhook("session", "url", null, ""));
    }

    @Test
    void checkpointWritesKeepSourceIdentityAndNeverResubmitTheTurn() throws Exception {
        var path = new AtomicReference<String>();
        var body = new AtomicReference<String>();
        var key = new AtomicReference<String>();
        server.createContext(
                "/",
                exchange -> {
                    originalIdentity(exchange);
                    path.set(exchange.getRequestURI().getRawPath());
                    body.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    key.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                    respond(
                            exchange,
                            "{\"source_session_id\":\"session\",\"status\":\"validating\"}");
                });
        var client = transport.managedSessions();
        client.restoreCheckpoint("session/one", "checkpoint", "restore-key");
        assertEquals("/v1/sessions/session%2Fone/checkpoints/restore", path.get());
        assertEquals("restore-key", key.get());
        assertEquals("checkpoint", mapper.readTree(body.get()).path("checkpoint_id").asText());
        client.forkCheckpoint("session", "target", "checkpoint", "fork-key");
        assertEquals("/v1/sessions/session/fork", path.get());
        assertEquals("target", mapper.readTree(body.get()).path("target_session_id").asText());
        assertEquals("fork-key", key.get());
        assertEquals(
                "validating",
                client.checkpointOperation("session", "op/one").path("status").asText());
        assertEquals("/v1/sessions/session/checkpoint-operations/op%2Fone", path.get());
        assertThrows(IOException.class, () -> client.checkpointOperation("other", "op"));
        assertThrows(
                IllegalArgumentException.class,
                () -> client.forkCheckpoint("same", "same", "checkpoint", "key"));
        assertThrows(
                IllegalArgumentException.class,
                () -> client.restoreCheckpoint("session", "checkpoint", ""));
        client.cancelCheckpointOperation("session", "op");
        assertEquals("/v1/sessions/session/checkpoint-operations/op/cancel", path.get());
    }

    @Test
    void nativeAdmissionInputsAndLifecycleKeepTheirOwnPathsAndExplicitRetryKeys() throws Exception {
        var path = new AtomicReference<String>();
        var body = new AtomicReference<String>();
        var key = new AtomicReference<String>();
        server.createContext(
                "/",
                exchange -> {
                    originalIdentity(exchange);
                    path.set(exchange.getRequestURI().getRawPath());
                    body.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    key.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                    respond(
                            exchange,
                            "{\"data\":[{\"id\":\"event\",\"managed_agents\":{\"turn_id\":\"turn\",\"intent\":\"new_turn\",\"receipt\":{\"id\":\"turn\",\"status\":\"accepted\"}}}]}");
                });
        var client = transport.managedSessions();
        assertEquals(
                "turn",
                client.submitMessage("session/one", "hello", "turn-key").path("id").asText());
        assertEquals("/v1/sessions/session%2Fone/events", path.get());
        assertEquals("turn-key", key.get());
        assertEquals(
                "hello",
                mapper.readTree(body.get())
                        .path("events")
                        .get(0)
                        .path("content")
                        .get(0)
                        .path("text")
                        .asText());
        client.input(
                "session",
                "turn",
                "steer",
                mapper.createObjectNode().put("message", "redirect"),
                null,
                "steer-key");
        assertEquals("/v1/sessions/session/events", path.get());
        assertEquals(
                "steer",
                mapper.readTree(body.get())
                        .path("events")
                        .get(0)
                        .path("managed_agents")
                        .path("intent")
                        .asText());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        client.input(
                                "session",
                                "turn",
                                "reply",
                                mapper.createObjectNode(),
                                "action",
                                "reply-key"));
    }

    @Test
    void fileBytesAndPinnedArtifactPagesReuseTheBoundedOriginalTransport() throws Exception {
        byte[] bytes = new byte[] {0, 1, -1, 13, 10};
        var path = new AtomicReference<String>();
        var query = new AtomicReference<String>();
        server.createContext(
                "/",
                exchange -> {
                    originalIdentity(exchange);
                    path.set(exchange.getRequestURI().getRawPath());
                    query.set(exchange.getRequestURI().getRawQuery());
                    if ("POST".equals(exchange.getRequestMethod())) {
                        assertEquals(
                                "file-key",
                                exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                        assertEquals(
                                "%E6%96%87%E4%BB%B6%20name.bin",
                                exchange.getRequestHeaders().getFirst("X-File-Name"));
                        assertArrayEquals(bytes, exchange.getRequestBody().readAllBytes());
                    }
                    if (path.get().endsWith("/content")) {
                        exchange.sendResponseHeaders(200, bytes.length);
                        try (var output = exchange.getResponseBody()) {
                            output.write(bytes);
                        }
                    } else respond(exchange, "{\"file_id\":\"file\"}");
                });
        var client = transport.managedSessions();
        assertEquals(
                "file",
                client.uploadFile(
                                "session",
                                "文件 name.bin",
                                bytes,
                                "application/octet-stream",
                                "file-key")
                        .path("file_id")
                        .asText());
        assertArrayEquals(bytes, client.fileContent("session", "file"));
        assertEquals("/v1/sessions/session/files/file/content", path.get());
        client.resources("session", "snapshot", "artifacts", "opaque+/=", 256);
        assertEquals("/v1/sessions/session/resources/artifacts", path.get());
        assertTrue(query.get().contains("snapshot_id=snapshot"));
        assertTrue(query.get().contains("after=opaque%2B%2F%3D"));
        client.artifacts("session", "turn", "", "snapshot", 10);
        assertEquals("/v1/sessions/session/artifacts", path.get());
        assertTrue(query.get().contains("turn=turn"));
    }

    @Test
    void streamReplaysTheSessionCursorAndRejectsForeignEvents() throws Exception {
        var owner = new AtomicReference<>("session");
        server.createContext(
                "/",
                exchange -> {
                    originalIdentity(exchange);
                    assertNull(exchange.getRequestHeaders().getFirst("Last-Event-ID"));
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    respond(
                            exchange,
                            ": keepalive\n\n"
                                + "data:"
                                + " {\"id\":\"event\",\"type\":\"managed_agents.turn.completed\",\"processed_at\":\"2026-10-19T00:00:00Z\",\"managed_agents\":{\"session_id\":\""
                                    + owner.get()
                                    + "\"}}\n\n");
                });
        var client = transport.managedSessions();
        assertThrows(IllegalArgumentException.class, () -> client.stream("session", "old-cursor"));
        try (var stream = client.stream("session", null)) {
            assertEquals("event", stream.nextEvent().path("id").asText());
            assertNull(stream.nextEvent());
        }
        owner.set("other-session");
        try (var stream = client.stream("session", null)) {
            assertThrows(IOException.class, stream::nextEvent);
        }
    }

    private static void originalIdentity(HttpExchange exchange) {
        assertEquals("original-key", exchange.getRequestHeaders().getFirst("X-API-Key"));
        assertEquals("tenant", exchange.getRequestHeaders().getFirst("X-Managed-Agents-Tenant"));
        assertEquals(
                "namespace", exchange.getRequestHeaders().getFirst("X-Managed-Agents-Namespace"));
        assertNull(exchange.getRequestHeaders().getFirst("X-Managed-Agents-Internal-Token"));
    }

    private static void respond(HttpExchange exchange, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
