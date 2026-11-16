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
package io.agentscope.durable.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableRuntime;
import io.agentscope.durable.DurableTypes.LiveEvent;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.DurableTypes.TurnStatus;
import io.agentscope.durable.LiveEvents;
import io.agentscope.durable.TransactionalSessionBackend;
import io.agentscope.extensions.durable.jdbc.JdbcRecordStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class QueuedModelRecoveryTest {
    @Test
    void queuedRequestDecodesWithCoreCodec() {
        var tool =
                io.agentscope.core.model.ToolSchema.builder()
                        .name("lookup")
                        .description("Find order")
                        .parameters(Map.of("type", "object"))
                        .strict(true)
                        .build();
        var options =
                io.agentscope.core.model.GenerateOptions.builder()
                        .temperature(0.3)
                        .maxTokens(123)
                        .build();
        var codec = io.agentscope.core.util.JsonUtils.getJsonCodec();
        var decodedTool =
                codec.fromJson(codec.toJson(tool), io.agentscope.core.model.ToolSchema.class);
        var decodedOptions =
                codec.fromJson(
                        codec.toJson(options), io.agentscope.core.model.GenerateOptions.class);
        assertEquals(tool.getParameters(), decodedTool.getParameters());
        assertEquals(tool.getStrict(), decodedTool.getStrict());
        assertEquals(options.getTemperature(), decodedOptions.getTemperature());
        assertEquals(options.getMaxTokens(), decodedOptions.getMaxTokens());
    }

    private static final SessionKey KEY = new SessionKey("test", "queue");
    private static final ThreadKey THREAD = new ThreadKey(KEY, "main");
    @TempDir Path directory;

    private static DurableRuntime runtime(
            Path db, QueuedModelRequest.Publisher publisher, LiveEvents live, Duration timeout) {
        return runtime(db, publisher, live, timeout, new Toolkit());
    }

    private static DurableRuntime runtime(
            Path db,
            QueuedModelRequest.Publisher publisher,
            LiveEvents live,
            Duration timeout,
            Toolkit toolkit) {
        var adapter =
                AgentScopeAdapter.forReAct(
                                binding ->
                                        ReActAgent.builder()
                                                .name("fixture")
                                                .toolkit(toolkit)
                                                .model(
                                                        new AgentScopeAdapterTest.ScriptedModel(
                                                                false)),
                                Map.of())
                        .withQueuedModel(publisher, timeout);
        return DurableRuntime.builder()
                .backend(new TransactionalSessionBackend(JdbcRecordStore.sqlite(db)))
                .harness("agent", "v1", adapter)
                .liveEvents(live)
                .leaseDuration(Duration.ofSeconds(6))
                .build();
    }

    private static void unexpectedDispatch(QueuedModelRequest request) {
        throw new AssertionError("Redispatched " + request.callId());
    }

    private static ChatResponse text(String value) {
        return ChatResponse.builder()
                .id("remote")
                .content(List.of(TextBlock.builder().text(value).build()))
                .build();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    @Timeout(20)
    void missingFrameFailsAtOriginalDeadline(int missing) throws Exception {
        var db = directory.resolve("turn.db");
        var sent = new AtomicReference<QueuedModelRequest>();
        long deadline;
        try (var first = runtime(db, sent::set, LiveEvents.none(), Duration.ofMillis(800))) {
            var turn = first.createSession(KEY, "agent").submit("request", "hello");
            first.tick(THREAD);
            deadline = turn.status().waiting().deadline();
            assertTrue(deadline > 0);
        }
        try (var next =
                runtime(
                        db,
                        QueuedModelRecoveryTest::unexpectedDispatch,
                        LiveEvents.none(),
                        Duration.ofHours(1))) {
            var request = sent.get();
            var turn = next.getSession(KEY).getTurn(request.turnId());
            for (int seq = 0; seq <= 2; seq++) {
                if (seq == missing) continue;
                var signal =
                        QueuedModelRequest.reply(request.callId(), seq, text("part"), seq == 2);
                turn.signal(signal.id(), signal.key(), signal.value());
            }
            if (missing == 0) Thread.sleep(Math.max(0, deadline - System.currentTimeMillis() + 30));
            next.tick(THREAD);
            assertEquals(TurnStatus.FAILED, turn.status().status());
            assertTrue(turn.status().error().contains(request.callId()), turn.status()::toString);
            assertTrue(turn.status().error().contains("sequence " + missing));
        }
    }

    public static void main(String[] args) throws Exception {
        if ("effect".equals(args[1])) {
            try (var runtime =
                    effectRuntime(
                            Path.of(args[0]), QueuedModelRecoveryTest::unexpectedDispatch, true)) {
                runtime.tick(THREAD);
            }
            return;
        }
        var live = LiveEvents.local();
        try (var sub =
                        live.subscribe(
                                event -> {
                                    if (event.type().equals("message.delta")) {
                                        System.out.println(event.data());
                                        System.out.flush();
                                        Runtime.getRuntime().halt(17);
                                    }
                                });
                var runtime =
                        runtime(
                                Path.of(args[0]),
                                QueuedModelRecoveryTest::unexpectedDispatch,
                                live,
                                Duration.ofMinutes(1))) {
            runtime.tick(THREAD);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "provider-error", "bad-frame", "partial-error"})
    void configuredFallbackSurvivesHostReplacement(String primaryResult) {
        var db = directory.resolve("fallback.db");
        var requests = new java.util.ArrayList<QueuedModelRequest>();
        var switches = new java.util.ArrayList<Throwable>();
        var fallback = org.mockito.Mockito.mock(Model.class);
        org.mockito.Mockito.when(fallback.getModelName()).thenReturn("secondary");
        var adapter =
                AgentScopeAdapter.forReAct(
                                b ->
                                        ReActAgent.builder()
                                                .name("agent")
                                                .model(
                                                        new AgentScopeAdapterTest.ScriptedModel(
                                                                false))
                                                .fallbackModel(fallback)
                                                .failoverListener((m, e) -> switches.add(e)),
                                Map.of())
                        .withQueuedModel(requests::add);
        String id = null;
        for (int step = 0; step < 3; step++) {
            try (var rt =
                    DurableRuntime.builder()
                            .backend(new TransactionalSessionBackend(JdbcRecordStore.sqlite(db)))
                            .harness("agent", "v1", adapter)
                            .build()) {
                if (step == 0)
                    id = rt.createSession(KEY, "agent").submit("request", "test").turnId();
                var turn = rt.getSession(KEY).getTurn(id);
                if (step > 0) {
                    var call = requests.get(step - 1).callId();
                    var signal =
                            step == 1 && primaryResult.equals("provider-error")
                                    ? QueuedModelRequest.failure(call, 0, "provider unavailable")
                                    : QueuedModelRequest.reply(
                                            call,
                                            0,
                                            text("ok"),
                                            !primaryResult.equals("partial-error"));
                    turn.signal(
                            signal.id(),
                            signal.key(),
                            primaryResult.equals("bad-frame")
                                    ? DurableJson.value(Map.of())
                                    : signal.value());
                    if (primaryResult.equals("partial-error")) {
                        var error = QueuedModelRequest.failure(call, 1, "stream interrupted");
                        turn.signal(error.id(), error.key(), error.value());
                    }
                }
                rt.tick(THREAD);
                if (step == 0) {
                    assertEquals(TurnStatus.WAITING, turn.status().status());
                    assertEquals(1, requests.size());
                    assertTrue(switches.isEmpty());
                } else if (step == 1 && primaryResult.equals("provider-error")) {
                    assertEquals(
                            TurnStatus.WAITING, turn.status().status(), turn.status()::toString);
                    assertEquals("secondary", requests.get(1).model());
                    assertEquals(1, switches.size());
                } else {
                    assertEquals(
                            primaryResult.equals("bad-frame")
                                            || primaryResult.equals("partial-error")
                                    ? TurnStatus.FAILED
                                    : TurnStatus.SUCCEEDED,
                            turn.status().status(),
                            turn.status()::toString);
                    assertEquals(step, requests.size());
                    assertEquals(step - 1, switches.size());
                    return;
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"last\":false}",
                "{\"last\":true,\"response\":42}",
                "{\"last\":false,\"response\":{}}",
                "{\"last\":false,\"response\":{\"garbled\":true}}",
                "{\"last\":false,\"response\":{\"content\":42}}",
                "{\"error\":null}"
            })
    void malformedFrameBeforeValidTerminalFailsTurn(String payload) {
        var sent = new AtomicReference<QueuedModelRequest>();
        try (var rt =
                runtime(
                        directory.resolve("bad.db"),
                        sent::set,
                        LiveEvents.none(),
                        Duration.ofMinutes(1))) {
            var turn = rt.createSession(KEY, "agent").submit("request", "test");
            rt.tick(THREAD);
            String call = sent.get().callId();
            String key = QueuedModelRequest.key(call, 0);
            turn.signal(key, key, DurableJson.parse(payload));
            var end = QueuedModelRequest.reply(call, 1, text("must not complete"), true);
            turn.signal(end.id(), end.key(), end.value());
            rt.tick(THREAD);
            assertEquals(TurnStatus.FAILED, turn.status().status());
            assertTrue(turn.status().error().contains(key));
        }
    }

    @Test
    @Timeout(45)
    void killAfterPublishedDeltaReplaysAsNewRevisionOfSameAttempt() throws Exception {
        var db = directory.resolve("crash.db");
        var sent = new AtomicReference<QueuedModelRequest>();
        try (var first = runtime(db, sent::set, LiveEvents.none(), Duration.ofMinutes(1))) {
            var turn = first.createSession(KEY, "agent").submit("request", "hello");
            first.tick(THREAD);
            var signal = QueuedModelRequest.reply(sent.get().callId(), 0, text("hello"), false);
            turn.signal(signal.id(), signal.key(), signal.value());
        }
        runCrashingChild(db, "delta", 17);
        verifyReplayedDelta(db, sent.get());
    }

    private void runCrashingChild(Path db, String mode, int exitCode) throws Exception {
        var output = directory.resolve(mode + ".log");
        var child =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                System.getProperty(
                                        "surefire.test.class.path",
                                        System.getProperty("java.class.path")),
                                QueuedModelRecoveryTest.class.getName(),
                                db.toString(),
                                mode)
                        .redirectErrorStream(true)
                        .redirectOutput(output.toFile())
                        .start();
        try {
            assertTrue(child.waitFor(20, TimeUnit.SECONDS));
            assertEquals(exitCode, child.exitValue(), Files.readString(output));
        } finally {
            if (child.isAlive()) child.destroyForcibly();
        }
    }

    private void verifyReplayedDelta(Path db, QueuedModelRequest request) throws Exception {
        var events = new java.util.concurrent.CopyOnWriteArrayList<LiveEvent>();
        var live = LiveEvents.local();
        try (var sub =
                        live.subscribe(
                                event -> {
                                    if (event.type().equals("message.delta")) events.add(event);
                                });
                var replacement =
                        runtime(
                                db,
                                QueuedModelRecoveryTest::unexpectedDispatch,
                                live,
                                Duration.ofMinutes(1))) {
            var turn = replacement.getSession(KEY).getTurn(request.turnId());
            var end = QueuedModelRequest.reply(request.callId(), 1, text(" world"), true);
            turn.signal(end.id(), end.key(), end.value());
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!turn.status().status().terminal() && System.nanoTime() < until) {
                replacement.tick(THREAD);
                Thread.sleep(25);
            }
            assertEquals(TurnStatus.SUCCEEDED, turn.status().status());
            assertEquals(
                    "hello world",
                    turn.status().output().path("content").get(0).path("text").asText());
            assertEquals(
                    List.of("hello", " world"),
                    events.stream().map(e -> e.data().path("text").asText()).toList());
            for (var event : events) {
                assertEquals(1, event.data().required("attempt").asInt());
                assertEquals(1, event.data().required("revision").asInt());
            }
        }
    }

    public static class EffectTool {
        private final Path receipt;
        private final boolean crash;

        EffectTool(Path receipt, boolean crash) {
            this.receipt = receipt;
            this.crash = crash;
        }

        @Tool(name = "effect", description = "Append an external receipt")
        public String effect() throws Exception {
            Files.writeString(
                    receipt, "effect-1\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (crash) Runtime.getRuntime().halt(23);
            return "done";
        }
    }

    private static DurableRuntime effectRuntime(
            Path db, QueuedModelRequest.Publisher publisher, boolean crash) {
        var toolkit = new Toolkit();
        toolkit.registerTool(new EffectTool(db.resolveSibling("receipts"), crash));
        return runtime(db, publisher, LiveEvents.none(), Duration.ofMinutes(1), toolkit);
    }

    @Test
    @Timeout(45)
    void crashAfterExternalEffectRequiresReconciliationWithoutRepeatingTool() throws Exception {
        Path db = directory.resolve("effect.db");
        var sent = new AtomicReference<QueuedModelRequest>();
        try (var first = effectRuntime(db, sent::set, false)) {
            var turn = first.createSession(KEY, "agent").submit("request", "record receipt");
            first.tick(THREAD);
            var call = ToolUseBlock.builder().id("effect-1").name("effect").input(Map.of()).build();
            var result =
                    QueuedModelRequest.reply(
                            sent.get().callId(),
                            0,
                            ChatResponse.builder().content(List.of(call)).build(),
                            true);
            turn.signal(result.id(), result.key(), result.value());
        }
        runCrashingChild(db, "effect", 23);
        assertEquals(List.of("effect-1"), Files.readAllLines(directory.resolve("receipts")));
        try (var next = effectRuntime(db, QueuedModelRecoveryTest::unexpectedDispatch, false)) {
            var turn = next.getSession(KEY).getTurn(sent.get().turnId());
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (turn.status().recovery() == null && System.nanoTime() < until) {
                next.tick(THREAD);
                Thread.sleep(25);
            }
            assertEquals(TurnStatus.WAITING, turn.status().status());
            assertTrue(turn.status().error().contains("effect-1"));
            assertTrue(turn.status().error().contains("reconciliation"));
            assertEquals(false, turn.status().recovery().retryAllowed());
            assertEquals(List.of("effect-1"), Files.readAllLines(directory.resolve("receipts")));
        }
    }
}
