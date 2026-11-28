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
package io.agentscope.durable.claude;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableRuntime;
import io.agentscope.durable.DurableSession;
import io.agentscope.durable.DurableTypes.Commit;
import io.agentscope.durable.DurableTypes.EventInput;
import io.agentscope.durable.DurableTypes.LiveEvent;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.TurnStatus;
import io.agentscope.durable.DurableTypes.WaitCondition;
import io.agentscope.durable.LiveEvents;
import io.agentscope.durable.SessionBackend;
import io.agentscope.extensions.durable.jdbc.LocalBackend;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClaudeSessionEventHandlerTest {
    private static final String EVENTS = "/v1/sessions/test/events";
    private static final String MESSAGE =
            """
            {"type":"user.message","content":[{"type":"text","text":"hello"}]}
            """;
    private static final String INTERRUPT = "{\"type\":\"user.interrupt\"}";
    private final SessionKey key = new SessionKey("tenant", "test");
    private final LiveEvents live = LiveEvents.local();

    @TempDir Path directory;
    private SessionBackend backend;
    private DurableRuntime runtime;
    private DurableSession session;
    private HttpServer server;
    private ExecutorService workers;
    private HttpClient client;
    private URI base;

    @BeforeEach
    void open() throws Exception {
        backend = LocalBackend.open(directory);
        runtime =
                DurableRuntime.builder()
                        .backend(backend)
                        .liveEvents(live)
                        .definition("agent", "v1", "1")
                        .build();
        session = runtime.createSession(key, "agent");
        runtime.createSession(new SessionKey("other", key.sessionId()), "agent");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newCachedThreadPool();
        server.setExecutor(workers);
        server.createContext(
                "/v1/sessions/",
                new ClaudeSessionEventHandler(
                        runtime,
                        backend.localStorage().orElseThrow().records(),
                        (exchange, operation, sessionId) -> {
                            String credential = exchange.getRequestHeaders().getFirst("x-api-key");
                            if ("allowed".equals(credential))
                                return new SessionKey("tenant", sessionId);
                            if ("other".equals(credential))
                                return new SessionKey("other", sessionId);
                            throw new SecurityException();
                        },
                        Duration.ofSeconds(2),
                        Duration.ofMillis(5)));
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @AfterEach
    void close() throws InterruptedException {
        if (server != null) server.stop(0);
        boolean terminated = true;
        try {
            if (workers != null) {
                workers.shutdownNow();
                terminated = workers.awaitTermination(5, TimeUnit.SECONDS);
            }
        } finally {
            if (runtime != null) runtime.close();
            if (backend != null) backend.close();
        }
        assertTrue(terminated, "HTTP test executor did not terminate");
    }

    @Test
    void sdkShapedBatchUsesNativeIdempotencyAndValidatesEveryItemBeforeAdmission()
            throws Exception {
        var request = "{\"events\":[" + INTERRUPT + "," + MESSAGE + "]}";
        var first = send("batch", request);
        assertEquals(200, first.statusCode(), first.body());
        assertEquals(first.body(), send("batch", request).body());
        var data = DurableJson.parse(first.body()).required("data");
        assertEquals("user.interrupt", data.get(0).required("type").asText());
        assertEquals("user.message", data.get(1).required("type").asText());
        assertEquals(
                session.listTurns(null, 10).items().get(0).inputMessageId(),
                data.get(1).required("id").asText());
        assertTrue(data.get(1).required("processed_at").isNull());
        assertEquals(409, send("batch", "{\"events\":[" + MESSAGE + "]}").statusCode());
        assertEquals(
                400,
                send("invalid", "{\"events\":[" + MESSAGE + ",{\"type\":\"unsupported\"}]}")
                        .statusCode());
        assertEquals(1, session.listTurns(null, 10).items().size());

        var requestHistory = request(EVENTS + "?types%5B%5D=user.interrupt&limit=1").GET().build();
        var history = client.send(requestHistory, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, history.statusCode(), history.body());
        assertEquals(data.get(0), DurableJson.parse(history.body()).required("data").get(0));
        var denied =
                client.send(
                        HttpRequest.newBuilder(base.resolve(EVENTS))
                                .header("anthropic-beta", ClaudeSessionEventHandler.BETA)
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(403, denied.statusCode());
        assertEquals(
                "permission_error", DurableJson.parse(denied.body()).at("/error/type").asText());
    }

    @Test
    void historyAcceptsSdkOrderAndTimeFiltersWithStablePages() throws Exception {
        var service = new ClaudeSessionEvents(session);
        var event = DurableJson.parse(INTERRUPT);
        var first = service.sendInterrupt("older", event);
        var second = service.sendInterrupt("newer", event);
        String route =
                EVENTS
                        + "?order=desc&types%5B%5D=user.interrupt"
                        + "&created_at%5Blte%5D="
                        + second.required("processed_at").asText();
        var head = getJson(route + "&limit=1");
        assertEquals(DurableJson.value(List.of(second)), head.required("data"));
        String page = head.required("next_page").asText();
        service.sendInterrupt("after-boundary", event);
        var tail = getJson(route + "&page=" + page);
        assertEquals(DurableJson.value(List.of(first)), tail.required("data"));
        assertTrue(tail.required("next_page").isNull());
        var changed =
                client.send(
                        request(EVENTS + "?order=asc&page=" + page).build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(400, changed.statusCode(), changed.body());
        var noMatches = getJson(EVENTS + "?created_at%5Blt%5D=2000-01-01T00:00:00Z");
        assertTrue(noMatches.required("data").isEmpty());
    }

    @Test
    void aFreshStreamCanTailAfterTheEntireHistoryWasPruned() throws Exception {
        var service = new ClaudeSessionEvents(session);
        var event = DurableJson.parse(INTERRUPT);
        service.sendInterrupt("pruned", event);
        backend.pruneEvents(key, session.snapshot().cursor());
        var open =
                client.send(
                        request(EVENTS + "/stream").build(),
                        HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, open.statusCode());
        try (var lines =
                new BufferedReader(new InputStreamReader(open.body(), StandardCharsets.UTF_8))) {
            var after = service.sendInterrupt("after-prune", event);
            assertEquals(after, frame(lines).data());
        }
    }

    private JsonNode getJson(String path) throws Exception {
        var result = client.send(request(path).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode(), result.body());
        return DurableJson.parse(result.body());
    }

    @Test
    void reportsBlockingApprovalWithTheSameStatusInTheStreamAndHistory() throws Exception {
        var opened =
                client.send(
                        request(EVENTS + "/stream").build(),
                        HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, opened.statusCode());
        try (var lines =
                new BufferedReader(new InputStreamReader(opened.body(), StandardCharsets.UTF_8))) {
            var turn = session.submit("approval", "request");
            var lease =
                    backend.claim(turn.threadKey(), "brain", Duration.ofSeconds(10)).orElseThrow();
            assertEquals("session.status_running", frame(lines).data().required("type").asText());
            var view =
                    DurableJson.value(
                            Map.of(
                                    "id",
                                    "approval-id",
                                    "requestId",
                                    "tool-native",
                                    "turnId",
                                    turn.turnId(),
                                    "key",
                                    "interaction:tool-native",
                                    "kind",
                                    "confirmation",
                                    "status",
                                    "pending",
                                    "automatic",
                                    false,
                                    "call",
                                    Map.of("name", "job", "arguments", Map.of())));
            backend.commit(
                    lease,
                    new Commit(
                            "wait",
                            backend.frame(lease).turn().revision(),
                            null,
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(new EventInput("approval", "interaction.requested", view)),
                            TurnStatus.WAITING,
                            new WaitCondition("interaction:tool-native", 0),
                            null,
                            null));
            var tool = frame(lines).data();
            var idle = frame(lines).data();
            assertEquals("agent.tool_use", tool.required("type").asText());
            assertEquals("session.status_idle", idle.required("type").asText());
            assertEquals("requires_action", idle.at("/stop_reason/type").asText());
            assertEquals(tool.required("id"), idle.at("/stop_reason/event_ids/0"));
            assertTrue(idle.required("stop_details").isNull());

            var history =
                    client.send(
                            request(EVENTS + "?types%5B%5D=session.status_idle").build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(200, history.statusCode(), history.body());
            assertEquals(
                    DurableJson.value(List.of(idle)),
                    DurableJson.parse(history.body()).required("data"));
        }
    }

    @Test
    void sseResumesAfterTheLastCommittedFrameAndPreviewsRemainConnectionScoped() throws Exception {
        var service = new ClaudeSessionEvents(session);
        var event = DurableJson.parse(INTERRUPT);
        var historical = service.sendInterrupt("historical", event);
        var opened =
                client.send(
                        request(EVENTS + "/stream?event_deltas%5B%5D=agent.message").GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, opened.statusCode());
        assertTrue(
                opened.headers()
                        .firstValue("Content-Type")
                        .orElseThrow()
                        .startsWith("text/event-stream"));
        String resume;
        try (var lines =
                new BufferedReader(new InputStreamReader(opened.body(), StandardCharsets.UTF_8))) {
            assertEquals(": connected", lines.readLine());
            preview(new SessionKey("other", key.sessionId()), "main", "foreign");
            preview(key, "child", "child");
            preview(key, "main", "visible");
            var start = frame(lines);
            assertEquals("event_start", start.data().required("type").asText());
            assertEquals("visible", start.data().at("/event/id").asText());
            assertNull(start.cursor());
            var delta = frame(lines);
            assertEquals("event_delta", delta.data().required("type").asText());
            assertEquals("visible", delta.data().at("/delta/content/text").asText());
            assertNull(delta.cursor());
            var current = service.sendInterrupt("streamed", event);
            var committed = frame(lines);
            assertEquals(current, committed.data());
            assertFalse(historical.equals(committed.data()));
            assertNotNull(committed.cursor());
            resume = committed.cursor();
        }

        var next = service.sendInterrupt("after-disconnect", event);
        var resumed =
                client.send(
                        request(EVENTS + "/stream").header("Last-Event-ID", resume).build(),
                        HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, resumed.statusCode());
        try (var lines =
                new BufferedReader(new InputStreamReader(resumed.body(), StandardCharsets.UTF_8))) {
            assertEquals(next, frame(lines).data());
        }
        var crossScope =
                client.send(
                        HttpRequest.newBuilder(base.resolve(EVENTS + "/stream"))
                                .header("anthropic-beta", ClaudeSessionEventHandler.BETA)
                                .header("x-api-key", "other")
                                .header("Last-Event-ID", resume)
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(400, crossScope.statusCode(), crossScope.body());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(5))
                .header("anthropic-beta", ClaudeSessionEventHandler.BETA)
                .header("x-api-key", "allowed");
    }

    private HttpResponse<String> send(String key, String body) throws Exception {
        return client.send(
                request(EVENTS)
                        .header("Idempotency-Key", key)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void preview(SessionKey scope, String thread, String id) {
        live.publish(
                new LiveEvent(
                        "1",
                        id,
                        scope,
                        thread,
                        "turn",
                        "run",
                        "step",
                        1,
                        "message.delta",
                        DurableJson.value(Map.of("messageId", id, "blockId", "text", "text", id))));
    }

    private record Frame(String cursor, JsonNode data) {}

    private static Frame frame(BufferedReader lines) throws Exception {
        String cursor = null;
        for (String line; (line = lines.readLine()) != null; ) {
            if (line.startsWith("id: ")) cursor = line.substring(4);
            if (line.startsWith("data: "))
                return new Frame(cursor, DurableJson.parse(line.substring(6)));
        }
        throw new AssertionError("Expected an SSE data frame");
    }
}
