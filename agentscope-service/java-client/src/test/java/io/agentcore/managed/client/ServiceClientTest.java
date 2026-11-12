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
package io.agentcore.managed.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ServiceClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private String base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(
                Executors.newCachedThreadPool(
                        runnable -> {
                            Thread thread = new Thread(runnable, "java-client-test");
                            thread.setDaemon(true);
                            return thread;
                        }));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void checkpointOperationsKeepPublicRoutesAndRejectForeignReceipts() throws Exception {
        var path = new AtomicReference<String>();
        var body = new AtomicReference<String>();
        var key = new AtomicReference<String>();
        server.createContext(
                "/",
                exchange -> {
                    assertEquals(
                            "original-key", exchange.getRequestHeaders().getFirst("X-API-Key"));
                    assertNull(
                            exchange.getRequestHeaders()
                                    .getFirst("X-Managed-Agents-Internal-Token"));
                    path.set(exchange.getRequestURI().getRawPath());
                    body.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    key.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                    respond(
                            exchange,
                            200,
                            "{\"source_session_id\":\"session\",\"operation_id\":\"operation\"}");
                });
        var client = ServiceClient.builder(base).apiKey("original-key").build();
        client.restoreCheckpoint("session", "checkpoint_saved", "restore-key");
        assertEquals("/api/v1/agent-sessions/session/checkpoints/restore", path.get());
        assertEquals("restore-key", key.get());
        assertEquals(1, mapper.readTree(body.get()).size());
        client.fork("session", "target", "checkpoint_saved", "fork-key");
        assertEquals("fork-key", key.get());
        assertEquals(2, mapper.readTree(body.get()).size());
        assertEquals("target", mapper.readTree(body.get()).path("target_session_id").asText());
        client.checkpointOperation("session", "operation");
        assertEquals("/api/v1/agent-sessions/session/checkpoint-operations/operation", path.get());
        assertThrows(IOException.class, () -> client.checkpointOperation("other", "operation"));
        assertThrows(IOException.class, () -> client.checkpointOperation("session", "other"));
        client.cancelCheckpointOperation("session", "operation");
        assertEquals(
                "/api/v1/agent-sessions/session/checkpoint-operations/operation/cancel",
                path.get());
    }

    @Test
    void budgetWritesCarryTheOriginalIdentityAndConditionalRevision() throws Exception {
        server.createContext(
                "/api/v1/agent-sessions/session/budget",
                exchange -> {
                    assertEquals(
                            "original-key", exchange.getRequestHeaders().getFirst("X-API-Key"));
                    assertNull(
                            exchange.getRequestHeaders()
                                    .getFirst("X-Managed-Agents-Internal-Token"));
                    if ("PUT".equals(exchange.getRequestMethod())) {
                        assertEquals("2", exchange.getRequestHeaders().getFirst("If-Match"));
                        assertEquals(
                                20,
                                mapper.readTree(exchange.getRequestBody())
                                        .path("max_model_calls")
                                        .asInt());
                    }
                    respond(exchange, 200, "{\"revision\":3,\"limits\":{\"max_model_calls\":20}}");
                });
        var client = ServiceClient.builder(base).apiKey("original-key").build();
        assertEquals(3, client.budget("session").path("revision").asInt());
        assertEquals(
                3,
                client.setBudget(
                                "session", mapper.createObjectNode().put("max_model_calls", 20), 2L)
                        .path("revision")
                        .asInt());
    }

    @Test
    void carriesOriginalIdentityEscapesPathsAndChecksCommandTurn() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server.createContext(
                "/",
                exchange -> {
                    assertEquals(
                            "original-user",
                            exchange.getRequestHeaders().getFirst("Authorization").substring(7));
                    assertNull(exchange.getRequestHeaders().getFirst("X-API-Key"));
                    assertNull(
                            exchange.getRequestHeaders()
                                    .getFirst("X-Managed-Agents-Internal-Token"));
                    assertEquals(
                            "tenant",
                            exchange.getRequestHeaders().getFirst("X-Managed-Agents-Tenant"));
                    assertEquals(
                            "ns",
                            exchange.getRequestHeaders().getFirst("X-Managed-Agents-Namespace"));
                    path.set(exchange.getRequestURI().getRawPath());
                    body.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    if (path.get().endsWith("/turns"))
                        assertEquals(
                                "turn-key",
                                exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                    respond(exchange, 200, "{\"turn_id\":\"another-turn\"}");
                });
        var client =
                ServiceClient.builder(base)
                        .apiToken("original-user")
                        .tenant("tenant")
                        .namespace("ns")
                        .build();
        client.submitMessage("s/with space", "hello", "turn-key");
        assertEquals("/api/v1/agent-sessions/s%2Fwith%20space/turns", path.get());
        assertEquals("hello", mapper.readTree(body.get()).path("message").asText());
        assertThrows(IOException.class, () -> client.commandStatus("session", "turn", "command"));
        assertThrows(
                IllegalArgumentException.class, () -> client.submitMessage("session", "hello", ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> ServiceClient.builder(base).apiKey("key").apiToken("token").build());
        assertThrows(IllegalArgumentException.class, () -> client.session(".."));
    }

    @Test
    void publicExportChecksWindowMetadataAndRejectsTruncatedRecords() throws Exception {
        var query = new AtomicReference<String>();
        var responseBody =
                new AtomicReference<>(
                        "{\"schema_version\":1,\"session_id\":\"session\",\"id\":\"event\",\"cursor\":\"cursor-1\",\"type\":\"turn.completed\",\"data\":{}}\n");
        server.createContext(
                "/",
                exchange -> {
                    assertEquals("app-key", exchange.getRequestHeaders().getFirst("X-API-Key"));
                    assertNull(
                            exchange.getRequestHeaders()
                                    .getFirst("X-Managed-Agents-Internal-Token"));
                    query.set(exchange.getRequestURI().getRawQuery());
                    exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
                    exchange.getResponseHeaders().set("X-Export-Through", "watermark");
                    exchange.getResponseHeaders().set("X-Next-Cursor", "cursor-1");
                    exchange.getResponseHeaders().set("X-Has-More", "true");
                    exchange.getResponseHeaders().set("X-Export-Count", "1");
                    respond(exchange, 200, responseBody.get());
                });
        var client = ServiceClient.builder(base).apiKey("app-key").build();
        try (var exported = client.exportEvents("session", "turn", "", "watermark", 1)) {
            assertEquals("watermark", exported.throughCursor());
            assertEquals("cursor-1", exported.nextCursor());
            assertTrue(exported.hasMore());
            assertEquals("event", exported.nextEvent().path("id").asText());
            assertNull(exported.nextEvent());
        }
        assertTrue(query.get().contains("through=watermark"));
        responseBody.set(responseBody.get().trim());
        try (var exported = client.exportEvents("session", null, null, null, 1)) {
            assertThrows(IOException.class, exported::nextEvent);
        }
        responseBody.set("");
        try (var exported = client.exportEvents("session", null, null, null, 1)) {
            assertThrows(IOException.class, exported::nextEvent);
        }
        assertThrows(
                IOException.class, () -> client.exportEvents("session", null, "", "foreign", 1));
    }

    @Test
    void validatesCapabilitiesAndPreservesSnapshotPagesAndWebhookDefaults() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        AtomicInteger capabilityCalls = new AtomicInteger();
        server.createContext(
                "/",
                exchange -> {
                    assertEquals("app-key", exchange.getRequestHeaders().getFirst("X-API-Key"));
                    body.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    query.set(exchange.getRequestURI().getRawQuery());
                    String path = exchange.getRequestURI().getPath();
                    String response = "{}";
                    if (path.endsWith("/capabilities"))
                        response =
                                capabilityCalls.incrementAndGet() == 1
                                        ? "{\"capabilities\":{\"files\":true,\"file_input\":true,\"artifacts\":true}}"
                                        : "{}";
                    if (path.endsWith("/snapshot"))
                        response = "{\"snapshot_id\":\"snap\",\"as_of\":\"19\",\"resources\":{}}";
                    if (path.contains("/resources/"))
                        response =
                                "{\"snapshot_id\":\"snap\",\"as_of\":\"19\",\"data\":[],\"has_more\":false}";
                    respond(exchange, 200, response);
                });
        var client = ServiceClient.builder(base).apiKey("app-key").build();
        assertTrue(client.capabilities("s").path("capabilities").path("file_input").asBoolean());
        assertThrows(IOException.class, () -> client.capabilities("s"));
        assertEquals("19", client.snapshot("s", "t", 10).path("as_of").asText());
        assertEquals(
                "snap",
                client.resources("s", "t", "steps", "next page", "snap", 10)
                        .path("snapshot_id")
                        .asText());
        assertTrue(query.get().contains("after=next%20page"));
        assertTrue(query.get().contains("snapshot_id=snap"));
        client.webhook("s", "https://receiver.example/events", null, "hook-key");
        assertFalse(mapper.readTree(body.get()).has("event_types"));
        client.webhook(
                "s", "https://receiver.example/events", List.of("turn.completed"), "hook-key-2");
        assertEquals(
                "turn.completed", mapper.readTree(body.get()).path("event_types").get(0).asText());
        assertThrows(
                IllegalArgumentException.class,
                () -> client.resources("s", "t", "steps", "", "", 1000));
    }

    @Test
    void replaysMultilineSseAndEnforcesDeadlineAfterHeaders() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        server.createContext(
                "/",
                exchange -> {
                    boolean waiting = exchange.getRequestURI().getPath().contains("waiting");
                    exchange.getResponseHeaders()
                            .set("Content-Type", "text/event-stream; charset=utf-8");
                    exchange.sendResponseHeaders(200, 0);
                    try (var output = exchange.getResponseBody()) {
                        if (waiting) {
                            output.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                            output.flush();
                            try {
                                release.await(3, TimeUnit.SECONDS);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            }
                        } else {
                            assertEquals(
                                    "18", exchange.getRequestHeaders().getFirst("Last-Event-ID"));
                            output.write(
                                    (": comment\r\n"
                                         + "id: 19\r\n"
                                         + "event: turn.completed\r\n"
                                         + "data:"
                                         + " {\"schema_version\":1,\"id\":\"event\",\"cursor\":\"19\",\r\n"
                                         + "data:"
                                         + " \"type\":\"turn.completed\",\"data\":{\"tokens\":9007199254740993}}\r\n\r\n")
                                            .getBytes(StandardCharsets.UTF_8));
                        }
                    }
                });
        var client = ServiceClient.builder(base).timeout(Duration.ofSeconds(2)).build();
        try (var stream = client.stream("s", "t", "18")) {
            var event = stream.nextEvent();
            assertEquals(9007199254740993L, event.path("data").path("tokens").asLong());
            assertEquals("19", event.path("cursor").asText());
            assertNull(stream.nextEvent());
        }
        long before = System.nanoTime();
        var shortClient = ServiceClient.builder(base).timeout(Duration.ofMillis(300)).build();
        try (var stream = shortClient.stream("waiting", "", "")) {
            assertThrows(HttpTimeoutException.class, stream::nextEvent);
        } finally {
            release.countDown();
        }
        assertTrue(Duration.ofNanos(System.nanoTime() - before).toMillis() < 1800);
    }

    @Test
    void rejectsRedirectsAndOversizedBodiesAndTimesOutIncompleteJson() throws Exception {
        AtomicInteger forwarded = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        server.createContext(
                "/forwarded",
                exchange -> {
                    forwarded.incrementAndGet();
                    respond(exchange, 200, "{}");
                });
        server.createContext(
                "/",
                exchange -> {
                    String path = exchange.getRequestURI().getPath();
                    if (path.contains("redirect")) {
                        exchange.getResponseHeaders().set("Location", base + "/forwarded");
                        respond(exchange, 302, "redirect");
                    } else if (path.contains("large")) {
                        exchange.sendResponseHeaders(200, 0);
                        try (var output = exchange.getResponseBody()) {
                            byte[] chunk = new byte[64 << 10];
                            for (int i = 0; i < 257; i++) output.write(chunk);
                        } catch (IOException cancelled) {
                            /* Client cancels the oversized response. */
                        }
                    } else if (path.contains("waiting")) {
                        exchange.sendResponseHeaders(200, 0);
                        try (var output = exchange.getResponseBody()) {
                            output.write('{');
                            output.flush();
                            try {
                                release.await(3, TimeUnit.SECONDS);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    } else respond(exchange, 409, "{\"code\":\"idempotency_conflict\"}");
                });
        var client =
                ServiceClient.builder(base)
                        .apiKey("app-key")
                        .timeout(Duration.ofSeconds(2))
                        .build();
        var error = assertThrows(ServiceAPIException.class, () -> client.session("conflict"));
        assertEquals(409, error.status());
        assertEquals("idempotency_conflict", error.detail().path("code").asText());
        assertEquals(
                302,
                assertThrows(ServiceAPIException.class, () -> client.session("redirect")).status());
        assertEquals(0, forwarded.get());
        assertTrue(
                assertThrows(IOException.class, () -> client.session("large"))
                        .getMessage()
                        .contains("limit"));
        var shortClient = ServiceClient.builder(base).timeout(Duration.ofMillis(300)).build();
        try {
            assertThrows(HttpTimeoutException.class, () -> shortClient.session("waiting"));
        } finally {
            release.countDown();
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        client.uploadFile(
                                "s",
                                "file",
                                new byte[(16 << 20) + 1],
                                "application/octet-stream",
                                "key"));
    }

    private static void respond(HttpExchange exchange, int status, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        if (!exchange.getResponseHeaders().containsKey("Content-Type"))
            exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
