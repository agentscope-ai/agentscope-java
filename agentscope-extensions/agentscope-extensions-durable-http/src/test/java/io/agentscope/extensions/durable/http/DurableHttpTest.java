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

package io.agentscope.extensions.durable.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableProtocol;
import io.agentscope.durable.DurableProtocol.Endpoint;
import io.agentscope.durable.DurableRuntime;
import io.agentscope.durable.DurableTypes.Commit;
import io.agentscope.durable.DurableTypes.DelegationTarget;
import io.agentscope.durable.DurableTypes.EventInput;
import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.ExecutionResult;
import io.agentscope.durable.DurableTypes.ExecutionState;
import io.agentscope.durable.DurableTypes.LiveEvent;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.SessionSpec;
import io.agentscope.durable.DurableTypes.ThreadBinding;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.DurableTypes.ThreadSpec;
import io.agentscope.durable.DurableTypes.ToolCall;
import io.agentscope.durable.DurableTypes.TurnStatus;
import io.agentscope.durable.Executor;
import io.agentscope.durable.SessionBackend;
import io.agentscope.durable.SessionEventClient;
import io.agentscope.durable.TransactionalSessionBackend;
import io.agentscope.durable.Wakeups;
import io.agentscope.extensions.durable.jdbc.JdbcRecordStore;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DurableHttpTest {
    @TempDir Path directory;
    HttpServer server;
    ExecutorService threads;
    SessionBackend backend;
    DurableRuntime apiRuntime;
    HttpSessionBackend remote;
    URI base;
    SessionKey key = new SessionKey("tenant", "session");

    @Test
    void delegationCannotHideAnotherSessionInsideAnAuthorizedEnvelope() throws Exception {
        var foreign = new ThreadKey(new SessionKey("other", "private"), "main");
        var request =
                new ExecutionRequest(
                        "execution",
                        foreign,
                        "turn",
                        new ToolCall("call", "children", "child", DurableJson.value("input")));
        for (String op : List.of("delegate", "delegation", "cancelDelegation")) {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("thread", new ThreadKey(key, "main"));
            payload.put("request", request);
            if (op.equals("delegate"))
                payload.put(
                        "target",
                        new DelegationTarget(
                                new ThreadSpec(
                                        new ThreadKey(foreign.session(), "child"),
                                        new ThreadBinding("child", "v1", "v1")),
                                DurableJson.value("input")));
            if (op.equals("cancelDelegation")) payload.put("childTurnId", null);
            var body = DurableProtocol.request(Endpoint.BACKEND, op, payload);
            var response =
                    HttpClient.newHttpClient()
                            .send(
                                    HttpRequest.newBuilder(base.resolve("/backend"))
                                            .header("Content-Type", "application/json")
                                            .POST(
                                                    HttpRequest.BodyPublishers.ofString(
                                                            body.toString()))
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString());
            assertEquals(400, response.statusCode());
            assertTrue(response.body().contains("Delegation request scope differs"));
        }
    }

    @BeforeEach
    void setup() throws Exception {
        backend =
                new TransactionalSessionBackend(
                                JdbcRecordStore.sqlite(directory.resolve("http.db")))
                        .withWakeups(Wakeups.local());
        apiRuntime = DurableRuntime.builder().backend(backend).build();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        threads = Executors.newCachedThreadPool();
        server.setExecutor(threads);
        SessionBackendHandler.Authorizer authorize =
                (exchange, op, session) -> {
                    if (session != null && !"tenant".equals(session.namespace()))
                        throw new SecurityException();
                };
        server.createContext("/backend", new SessionBackendHandler(backend, authorize));
        server.createContext(
                "/events",
                new SessionEventHandler(
                        apiRuntime,
                        (e, o, n, s) -> authorize.authorize(e, o, s),
                        Duration.ofMillis(150),
                        Duration.ofMillis(10)));
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        remote = new HttpSessionBackend(base.resolve("/backend"));
        remote.create(
                new SessionSpec(
                        key, new ThreadBinding("fixture", "v1", "v1"), DurableJson.value(null)));
    }

    @AfterEach
    void close() throws InterruptedException {
        if (apiRuntime != null) apiRuntime.close();
        if (server != null) server.stop(0);
        boolean terminated = true;
        if (threads != null) {
            threads.shutdownNow();
            terminated = threads.awaitTermination(5, TimeUnit.SECONDS);
        }
        if (backend != null) backend.close();
        assertTrue(terminated, "HTTP test executor did not terminate");
    }

    @Test
    void oversizedCheckpointUsesScopedBinaryObjectsWithoutRaisingControlFrameLimit() {
        byte[] bytes = new byte[17 * 1024 * 1024];
        Arrays.fill(bytes, (byte) 'x');
        var reference = remote.putObject(key, bytes);
        assertArrayEquals(bytes, remote.readObject(key, reference));
        assertThrows(
                DurableException.class,
                () -> remote.readObject(new SessionKey("other", "session"), reference));
        remote.submit(new ThreadKey(key, "main"), "large", DurableJson.value("input"));
        var lease =
                remote.claim(new ThreadKey(key, "main"), "brain", Duration.ofMinutes(2))
                        .orElseThrow();
        var checkpoint = DurableJson.value(new String(bytes, StandardCharsets.UTF_8));
        var command =
                new Commit(
                        "large-commit",
                        0,
                        checkpoint,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        TurnStatus.RUNNING,
                        null,
                        null,
                        null);
        var receipt = remote.commit(lease, command);
        assertEquals(receipt, remote.commit(lease, command));
        assertEquals(checkpoint, remote.frame(lease).checkpoint());
        assertEquals(16 * 1024 * 1024, DurableHttp.MAX_REQUEST_BYTES);
    }

    @Test
    void invalidPayloadIsRejectedWithoutAdmittingARun() throws Exception {
        var request =
                HttpRequest.newBuilder(base.resolve("/backend"))
                        .header("Content-Type", "application/json")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        """
                                        {"version":"durable.v1","operation":"submit","payload":{
                                          "session":{"namespace":"tenant","sessionId":"session"},"input":"hello"}}
                                        """))
                        .build();
        var response =
                HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode());
        assertEquals("INVALID", DurableJson.parse(response.body()).path("code").asText());
        assertTrue(backend.snapshot(key).turns().isEmpty());
    }

    @Test
    void malformedSuccessReplyRemainsAnUnconfirmedOutcome() {
        server.createContext(
                "/broken",
                exchange -> {
                    try (exchange) {
                        byte[] bytes =
                                "{\"version\":\"durable.v1\"}".getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    }
                });
        var broken = new HttpSessionBackend(base.resolve("/broken"));
        var error =
                assertThrows(
                        DurableException.class,
                        () ->
                                broken.submit(
                                        new ThreadKey(key, "main"),
                                        "lost",
                                        DurableJson.value("hello")));
        assertEquals(DurableException.Code.UNAVAILABLE, error.code());
    }

    @Test
    void remoteBackendUsesOriginalTransactionsAndReceipts() {
        var receipt =
                remote.submit(new ThreadKey(key, "main"), "request", DurableJson.value("input"));
        assertEquals(
                receipt,
                remote.submit(new ThreadKey(key, "main"), "request", DurableJson.value("input")));
        var lease =
                remote.claim(new ThreadKey(key, "main"), "owner", Duration.ofSeconds(3))
                        .orElseThrow();
        var frame = remote.frame(lease);
        var commit =
                new Commit(
                        "commit",
                        frame.turn().revision(),
                        DurableJson.value(Map.of("native", 1)),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(
                                new EventInput(
                                        "event",
                                        "message.completed",
                                        DurableJson.value(
                                                Map.of(
                                                        "messageId",
                                                        "reply",
                                                        "attempt",
                                                        1,
                                                        "revision",
                                                        0,
                                                        "text",
                                                        "answer")))),
                        TurnStatus.SUCCEEDED,
                        null,
                        DurableJson.value("answer"),
                        null);
        var result = remote.commit(lease, commit);
        assertEquals(
                result,
                remote.commit(lease, commit)); // Retry after terminal commit/lost acknowledgement.
        assertEquals(
                result,
                remote.receipt(new ThreadKey(key, "main"), receipt.turnId(), "commit")
                        .orElseThrow());
        assertEquals(
                TurnStatus.SUCCEEDED,
                remote.turn(new ThreadKey(key, "main"), receipt.turnId()).status());
        assertEquals(1, remote.sessions(null, 10).size());
        assertEquals(
                DurableException.Code.CONFLICT,
                assertThrows(
                                DurableException.class,
                                () ->
                                        remote.submit(
                                                new ThreadKey(key, "main"),
                                                "request",
                                                DurableJson.value("changed")))
                        .code());
        assertThrows(
                DurableException.class,
                () -> remote.snapshot(new SessionKey("another", "session")));
    }

    @Test
    void sseFiltersRunScopeAndAdvancesPastUnrelatedEvents() throws Exception {
        var thread = new ThreadKey(key, "main");
        var first = remote.submit(thread, "first", DurableJson.value("first"));
        var lease = remote.claim(thread, "brain", Duration.ofSeconds(5)).orElseThrow();
        var run = remote.prepareRun(lease, "call");
        var second = remote.submit(thread, "second", DurableJson.value("second"));
        remote.cancel(thread, second.turnId(), "cancel-second");
        String runCursor =
                remote.events(key, null, 100).events().stream()
                        .filter(e -> run.runId().equals(e.runId()))
                        .findFirst()
                        .orElseThrow()
                        .cursor();
        String cursor = remote.snapshot(key).cursor();
        String scope =
                "/events?namespace=tenant&sessionId=session&threadId=main&turnId="
                        + first.turnId()
                        + "&runId="
                        + run.runId();
        var http = HttpClient.newHttpClient();
        var response =
                http.send(
                        HttpRequest.newBuilder(base.resolve(scope)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("run.prepared"));
        assertFalse(response.body().contains("turn.queued"));
        assertFalse(response.body().contains(second.turnId()));
        assertTrue(response.body().contains("id: " + cursor + "\nevent: cursor\ndata: {}"));
        var reconnect =
                http.send(
                        HttpRequest.newBuilder(base.resolve(scope + "&cursor=invalid"))
                                .header("Last-Event-ID", cursor)
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(200, reconnect.statusCode());
        assertFalse(reconnect.body().contains("event: durable"));
        assertEquals(TurnStatus.RUNNING, remote.turn(thread, first.turnId()).status());
        for (String invalid :
                List.of(
                        "&runId=" + run.runId(),
                        "&turnId=" + first.turnId(),
                        "&threadId=main&threadId=main",
                        "&threadId=main&unexpected=value")) {
            var rejected =
                    http.send(
                            HttpRequest.newBuilder(
                                            base.resolve(
                                                    "/events?namespace=tenant&sessionId=session"
                                                            + invalid))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(400, rejected.statusCode());
        }
        var wrongParent =
                http.send(
                        HttpRequest.newBuilder(
                                        base.resolve(
                                                scope.replace(first.turnId(), second.turnId())))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(404, wrongParent.statusCode());
        remote.pruneEvents(key, cursor);
        var expired =
                http.send(
                        HttpRequest.newBuilder(base.resolve(scope))
                                .header("Last-Event-ID", runCursor)
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(410, expired.statusCode());
    }

    @Test
    void sseReplayAndReconnectUseCommittedCursorAndDisconnectDoesNotCancel() throws Exception {
        var turn = remote.submit(new ThreadKey(key, "main"), "request", DurableJson.value("input"));
        var http = HttpClient.newHttpClient();
        URI events = base.resolve("/events?namespace=tenant&sessionId=session");
        var first =
                http.send(
                        HttpRequest.newBuilder(events).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(200, first.statusCode());
        assertTrue(first.body().contains("turn.queued"));
        String cursor = remote.snapshot(key).cursor();
        remote.cancel(new ThreadKey(key, "main"), turn.turnId(), "cancel");
        var second =
                http.send(
                        HttpRequest.newBuilder(events)
                                .header("Last-Event-ID", cursor)
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertFalse(second.body().contains("turn.queued"));
        assertTrue(second.body().contains("turn.cancel"));
        var another =
                remote.submit(
                        new ThreadKey(key, "main"), "second", DurableJson.value("still running"));
        var stream =
                http.send(
                        HttpRequest.newBuilder(events).GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
        stream.body().close();
        assertFalse(
                remote.turn(new ThreadKey(key, "main"), another.turnId()).cancellationRequested());
    }

    @Test
    void expiredCursorResynchronizesSnapshotBeforeAcceptingNewEvents() {
        remote.submit(new ThreadKey(key, "main"), "one", DurableJson.value("input"));
        String old = remote.snapshot(key).cursor();
        remote.submit(new ThreadKey(key, "main"), "two", DurableJson.value("input"));
        var snapshot = remote.snapshot(key);
        remote.pruneEvents(key, snapshot.cursor());
        var snapshots = new AtomicInteger();
        var events = new ArrayList<String>();
        var client =
                new SessionEventClient(
                        remote,
                        key,
                        old,
                        s -> {
                            assertEquals(snapshot.cursor(), s.cursor());
                            snapshots.incrementAndGet();
                        },
                        e -> events.add(e.eventId()));
        assertEquals(0, client.poll());
        assertEquals(1, snapshots.get());
        remote.submit(new ThreadKey(key, "main"), "three", DurableJson.value("input"));
        assertEquals(1, client.poll());
        assertEquals(0, client.poll());
        assertEquals(1, events.size());
    }

    @Test
    void httpAdmissionWakesSseWithoutWaitingForPollingTimeout() throws Exception {
        server.createContext(
                "/live",
                new SessionEventHandler(
                        apiRuntime,
                        (e, o, n, s) -> {},
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(30)));
        var response =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(
                                                base.resolve(
                                                        "/live?namespace=tenant&sessionId=session"))
                                        .header("Last-Event-ID", remote.snapshot(key).cursor())
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofInputStream());
        try (var lines =
                new BufferedReader(
                        new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            assertEquals(": heartbeat", lines.readLine());
            assertEquals("", lines.readLine());
            var next =
                    threads.submit(
                            () -> {
                                String line;
                                while ((line = lines.readLine()) != null)
                                    if (line.startsWith("data: ")) return line;
                                return "";
                            });
            var turn =
                    remote.submit(new ThreadKey(key, "main"), "live", DurableJson.value("input"));
            String event = next.get(3, TimeUnit.SECONDS);
            assertTrue(event.contains("turn.queued"));
            assertTrue(event.contains(turn.turnId()));
        }
    }

    @Test
    void expiredSseCursorIsRejectedBeforeStartingStream() throws Exception {
        remote.submit(new ThreadKey(key, "main"), "first", DurableJson.value("input"));
        String old = remote.snapshot(key).cursor();
        remote.submit(new ThreadKey(key, "main"), "second", DurableJson.value("input"));
        remote.pruneEvents(key, remote.snapshot(key).cursor());
        var response =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(
                                                base.resolve(
                                                        "/events?namespace=tenant&sessionId=session"))
                                        .header("Last-Event-ID", old)
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
        assertEquals(410, response.statusCode());
        assertEquals("CURSOR_EXPIRED", DurableJson.parse(response.body()).path("code").asText());
    }

    @Test
    void failedSseFlushLeavesEventUnacknowledgedForReplay() {
        String start = remote.snapshot(key).cursor();
        remote.submit(new ThreadKey(key, "main"), "request", DurableJson.value("input"));
        var flushes = new AtomicInteger();
        var output =
                new ByteArrayOutputStream() {
                    @Override
                    public void flush() throws IOException {
                        if (flushes.incrementAndGet() == 1)
                            throw new IOException("Client disconnected");
                    }
                };
        try (var reader =
                new SessionEventClient(backend, key, start, s -> {}, new SseEventWriter(output))) {
            assertThrows(UncheckedIOException.class, reader::poll);
            assertEquals(start, reader.cursor());
            assertEquals(1, reader.poll());
            assertEquals(backend.snapshot(key).cursor(), reader.cursor());
            assertEquals(
                    2,
                    output.toString(StandardCharsets.UTF_8).split("event: durable", -1).length - 1);
        }
    }

    @Test
    void liveSseFrameNeverAdvancesTheDurableCursor() {
        var output = new ByteArrayOutputStream();
        var writer = new SseEventWriter(output);
        writer.live(
                new LiveEvent(
                        "1",
                        "live-1",
                        key,
                        "main",
                        "turn",
                        "run",
                        "step",
                        1,
                        "message.delta",
                        DurableJson.value(Map.of("messageId", "message", "text", "hello"))));

        String frame = output.toString(StandardCharsets.UTF_8);
        assertTrue(frame.contains("event: live"));
        assertTrue(frame.contains("message.delta"));
        assertFalse(frame.contains("id: "));
    }

    @Test
    void runtimeManagedObservationWorksOverRemoteBackendWithoutLocalNotifications()
            throws Exception {
        var snapshotReady = new CountDownLatch(1);
        var delivered = new CountDownLatch(2);
        var eventTypes = new ArrayList<String>();
        try (var runtime =
                        DurableRuntime.builder()
                                .backend(remote)
                                .pollInterval(Duration.ofMillis(10))
                                .build();
                var subscription =
                        runtime.getSession(key)
                                .observe(
                                        snapshot -> snapshotReady.countDown(),
                                        event -> {
                                            eventTypes.add(event.type());
                                            delivered.countDown();
                                        })) {
            assertTrue(snapshotReady.await(3, TimeUnit.SECONDS));
            var turn = runtime.getSession(key).submit("observed", "hello");
            assertTrue(delivered.await(3, TimeUnit.SECONDS));
            assertEquals(List.of("turn.queued", "message.completed"), eventTypes);
            subscription.close();
            assertFalse(
                    runtime.getSession(key)
                            .getTurn(turn.turnId())
                            .status()
                            .cancellationRequested());
        }
    }

    @Test
    void executorTransportPreservesProviderCapabilitiesAndUnknownOutcomes() {
        var starts = new AtomicInteger();
        Executor provider =
                new Executor() {
                    public Capabilities capabilities() {
                        return new Capabilities(false, true, false);
                    }

                    public ExecutionResult startExecution(ExecutionRequest request) {
                        starts.incrementAndGet();
                        return ExecutionResult.unknown("Lost result");
                    }

                    public ExecutionResult query(ExecutionRequest request, String handle) {
                        return ExecutionResult.missing();
                    }

                    public ExecutionResult cancel(ExecutionRequest request, String handle) {
                        return ExecutionResult.unknown("Unsupported");
                    }
                };
        server.createContext("/executor", new ExecutorHandler(provider, (exchange, op, key) -> {}));
        var remoteExecutor = new HttpExecutor(base.resolve("/executor"));
        var request =
                new ExecutionRequest(
                        "execution",
                        new ThreadKey(key, "main"),
                        "turn",
                        new ToolCall("call", "provider", "job", DurableJson.value(Map.of())));
        assertFalse(remoteExecutor.capabilities().idempotentStart());
        assertEquals(ExecutionState.UNKNOWN, remoteExecutor.startExecution(request).state());
        assertEquals(ExecutionState.NOT_FOUND, remoteExecutor.query(request, null).state());
        assertEquals(1, starts.get());
    }
}
