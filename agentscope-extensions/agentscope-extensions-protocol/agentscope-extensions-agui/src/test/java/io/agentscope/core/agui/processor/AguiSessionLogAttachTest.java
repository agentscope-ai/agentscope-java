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
package io.agentscope.core.agui.processor;

import static io.agentscope.core.agui.processor.AguiSessionLogTestSupport.THREAD_ID;
import static io.agentscope.core.agui.processor.AguiSessionLogTestSupport.TURN_ID;
import static io.agentscope.core.agui.processor.AguiSessionLogTestSupport.append;
import static io.agentscope.core.agui.processor.AguiSessionLogTestSupport.assistantToolCall;
import static io.agentscope.core.agui.processor.AguiSessionLogTestSupport.message;
import static io.agentscope.core.agui.processor.AguiSessionLogTestSupport.modelChunk;
import static io.agentscope.core.agui.processor.AguiSessionLogTestSupport.textBlock;
import static io.agentscope.core.agui.processor.AguiSessionLogTestSupport.toolResult;
import static io.agentscope.core.agui.processor.AguiSessionLogTestSupport.toolResultBlock;
import static io.agentscope.core.agui.processor.AguiSessionLogTestSupport.toolUse;
import static io.agentscope.core.agui.processor.AguiSessionLogTestSupport.toolUseBlock;
import static io.agentscope.core.agui.processor.AguiSessionLogTestSupport.user;
import static io.agentscope.core.agui.processor.AguiSessionLogTestSupport.writer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agui.AguiInterrupts;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.AguiFramedEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.session.SessionLog;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

/** Attach stream tests for the SessionLog-backed AG-UI recovery path. */
class AguiSessionLogAttachTest {

    private static final AguiAdapterConfig CONFIG = AguiAdapterConfig.defaultConfig();
    private static final Duration POLL = Duration.ofMillis(10);

    @Test
    void attachFinishedRunDrainsNothingAndFinishes() {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        try {
            append(log, writer, "run/start", Map.of("executionRunId", "exec-1"));
            append(log, writer, "message/user", message(user("u1", "hello")));
            append(log, writer, "run/end", Map.of("status", "completed"));
            long cursor = log.head().seq();
            log.release(writer);
            writer = null;

            List<AguiFramedEvent> frames =
                    AguiSessionLogAttach.attach(input(), log, cursor, "exec-1", CONFIG, POLL)
                            .collectList()
                            .block(Duration.ofSeconds(5));

            // The cursor already covers run/start, but a connect stream must still open with
            // RUN_STARTED before the terminal frame.
            assertEquals(2, frames.size());
            assertTrue(frames.get(0).event() instanceof AguiEvent.RunStarted);
            assertEquals("exec-1", ((AguiEvent.RunStarted) frames.get(0).event()).runId());
            assertTrue(frames.get(1).event() instanceof AguiEvent.RunFinished);
            assertFalse(
                    frames.stream()
                            .anyMatch(
                                    frame ->
                                            frame.event() instanceof AguiEvent.TextMessageContent));
        } finally {
            if (writer != null) {
                log.release(writer);
            }
        }
    }

    @Test
    void attachEmitsRunStartedFirstWhenCursorPassedRunStart() {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        long after;
        try {
            append(log, writer, "run/start", Map.of("executionRunId", "exec-1"));
            after = append(log, writer, "message/user", message(user("u1", "hello")));
            append(log, writer, "run/end", Map.of("status", "completed"));
        } finally {
            log.release(writer);
        }

        List<AguiFramedEvent> frames =
                AguiSessionLogAttach.attach(input(), log, after, "exec-1", CONFIG, POLL)
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertNotNull(frames);
        assertEquals(2, frames.size());
        assertTrue(
                frames.get(0).event() instanceof AguiEvent.RunStarted,
                "the official AG-UI client requires RUN_STARTED as the first connect event");
        assertEquals(
                -1L,
                frames.get(0).seq(),
                "the synthetic RUN_STARTED must not advance the replay cursor");
        assertEquals("exec-1", ((AguiEvent.RunStarted) frames.get(0).event()).runId());
        assertTrue(frames.get(1).event() instanceof AguiEvent.RunFinished);
    }

    @Test
    void attachReportsFailedTerminalStatusWhenCursorCoversRunEnd() {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        long cursor;
        try {
            append(log, writer, "run/start", Map.of("executionRunId", "exec-1"));
            append(log, writer, "run/end", Map.of("status", "failed", "reason", "boom"));
            cursor = log.head().seq();
        } finally {
            log.release(writer);
        }

        List<AguiFramedEvent> frames =
                AguiSessionLogAttach.attach(input(), log, cursor, "exec-1", CONFIG, POLL)
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertNotNull(frames);
        AguiEvent.RunError error = (AguiEvent.RunError) frames.get(frames.size() - 1).event();
        assertEquals("boom", error.message());
    }

    @Test
    void attachFinishesWhenAnotherRunFollowsTheTargetRun() {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        long after;
        try {
            append(log, writer, "run/start", Map.of("executionRunId", "exec-1"));
            append(log, writer, "run/end", Map.of("status", "completed"));
            after = log.head().seq();
            append(log, writer, "run/start", "exec-2", Map.of());
            append(log, writer, "run/end", "exec-2", Map.of("status", "completed"));
        } finally {
            log.release(writer);
        }

        List<AguiFramedEvent> frames =
                AguiSessionLogAttach.attach(input(), log, after, "exec-1", CONFIG, POLL)
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertNotNull(frames);
        AguiFramedEvent last = frames.get(frames.size() - 1);
        assertTrue(
                last.event() instanceof AguiEvent.RunFinished,
                "a target run that already ended must finish, not report a missing terminal event");
    }

    @Test
    void attachReplaysTextFromCursorUntilRunEnd() throws Exception {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        try {
            append(log, writer, "run/start", Map.of("executionRunId", "exec-1"));
            append(log, writer, "message/user", message(user("u1", "hello")));
            long cursor = log.head().seq();

            Thread producer =
                    new Thread(
                            () -> {
                                try {
                                    Thread.sleep(50);
                                    append(
                                            log,
                                            writer,
                                            "model/chunk",
                                            modelChunk("call-1", "chat-1", textBlock("Hel")));
                                    append(
                                            log,
                                            writer,
                                            "model/chunk",
                                            modelChunk("call-1", "chat-1", textBlock("lo")));
                                    append(
                                            log,
                                            writer,
                                            "model/end",
                                            Map.of("modelCallId", "call-1", "status", "completed"));
                                    append(log, writer, "run/end", Map.of("status", "completed"));
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                }
                            });
            producer.start();

            List<AguiFramedEvent> frames =
                    AguiSessionLogAttach.attach(input(), log, cursor, "exec-1", CONFIG, POLL)
                            .collectList()
                            .block(Duration.ofSeconds(5));
            producer.join(1000);

            assertEquals(
                    1,
                    frames.stream()
                            .filter(frame -> frame.event() instanceof AguiEvent.TextMessageStart)
                            .count());
            assertEquals(
                    "Hello",
                    frames.stream()
                            .filter(frame -> frame.event() instanceof AguiEvent.TextMessageContent)
                            .map(frame -> ((AguiEvent.TextMessageContent) frame.event()).delta())
                            .reduce("", String::concat));
            assertTrue(
                    frames.stream()
                            .anyMatch(frame -> frame.event() instanceof AguiEvent.TextMessageEnd));
            assertTrue(
                    frames.stream()
                            .anyMatch(frame -> frame.event() instanceof AguiEvent.RunFinished));
        } finally {
            log.release(writer);
        }
    }

    @Test
    void attachReplaysToolArgumentsAndResult() throws Exception {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        try {
            append(log, writer, "run/start", Map.of("executionRunId", "exec-1"));
            long cursor = log.head().seq();

            Thread producer =
                    new Thread(
                            () -> {
                                try {
                                    Thread.sleep(50);
                                    append(
                                            log,
                                            writer,
                                            "model/chunk",
                                            modelChunk(
                                                    "call-1",
                                                    "chat-1",
                                                    toolUseBlock(
                                                            "tool-1",
                                                            "search",
                                                            Map.of("q", "x"),
                                                            "{\"q\":\"x\"}")));
                                    append(
                                            log,
                                            writer,
                                            "model/end",
                                            Map.of("modelCallId", "call-1", "status", "completed"));
                                    Map<String, Object> result = new LinkedHashMap<>();
                                    result.put("type", "tool_result");
                                    result.put("id", "tool-1");
                                    result.put("name", "search");
                                    result.put("output", List.of(textBlock("ok")));
                                    result.put("state", "success");
                                    append(
                                            log,
                                            writer,
                                            "action/end",
                                            Map.of("toolCallId", "tool-1", "result", result));
                                    append(log, writer, "run/end", Map.of("status", "completed"));
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                }
                            });
            producer.start();

            List<AguiFramedEvent> frames =
                    AguiSessionLogAttach.attach(input(), log, cursor, "exec-1", CONFIG, POLL)
                            .collectList()
                            .block(Duration.ofSeconds(5));
            producer.join(1000);

            assertTrue(
                    frames.stream()
                            .anyMatch(frame -> frame.event() instanceof AguiEvent.ToolCallStart));
            assertTrue(
                    frames.stream()
                            .anyMatch(frame -> frame.event() instanceof AguiEvent.ToolCallArgs));
            assertTrue(
                    frames.stream()
                            .anyMatch(frame -> frame.event() instanceof AguiEvent.ToolCallEnd));
            assertTrue(
                    frames.stream()
                            .anyMatch(
                                    frame ->
                                            frame.event() instanceof AguiEvent.ToolCallResult result
                                                    && "ok".equals(result.content())));
        } finally {
            log.release(writer);
        }
    }

    @Test
    void attachDoesNotRestartToolCallAlreadyCoveredByHistory() throws Exception {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        try {
            append(log, writer, "run/start", Map.of("executionRunId", "exec-1"));
            append(
                    log,
                    writer,
                    "message/assistant",
                    message(
                            assistantToolCall(
                                    "a1",
                                    "calling",
                                    toolUse("tool-1", "search", "{\"q\":\"x\"}"))));
            long cursor = log.head().seq();

            Thread producer =
                    new Thread(
                            () -> {
                                try {
                                    Thread.sleep(50);
                                    append(
                                            log,
                                            writer,
                                            "tool/result",
                                            message(
                                                    toolResult(
                                                            "r1",
                                                            toolResultBlock(
                                                                    "tool-1", "search", "ok"))));
                                    append(log, writer, "run/end", Map.of("status", "completed"));
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                }
                            });
            producer.start();

            List<AguiFramedEvent> frames =
                    AguiSessionLogAttach.attach(input(), log, cursor, "exec-1", CONFIG, POLL)
                            .collectList()
                            .block(Duration.ofSeconds(5));
            producer.join(1000);

            assertFalse(
                    frames.stream()
                            .anyMatch(frame -> frame.event() instanceof AguiEvent.ToolCallStart),
                    "the tool call is already part of history");
            assertTrue(
                    frames.stream()
                            .anyMatch(
                                    frame ->
                                            frame.event() instanceof AguiEvent.ToolCallResult result
                                                    && "tool-1".equals(result.toolCallId())));
        } finally {
            log.release(writer);
        }
    }

    @Test
    void attachMapsFailedRunEndToRunError() {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        try {
            append(log, writer, "run/start", Map.of("executionRunId", "exec-1"));
            long cursor = log.head().seq();
            append(log, writer, "run/end", Map.of("status", "failed", "reason", "MAX_ITERATIONS"));

            List<AguiFramedEvent> frames =
                    AguiSessionLogAttach.attach(input(), log, cursor, "exec-1", CONFIG, POLL)
                            .collectList()
                            .block(Duration.ofSeconds(5));

            assertTrue(
                    frames.stream().anyMatch(frame -> frame.event() instanceof AguiEvent.RunError));
            assertFalse(
                    frames.stream()
                            .anyMatch(frame -> frame.event() instanceof AguiEvent.RunFinished));
        } finally {
            log.release(writer);
        }
    }

    @Test
    void attachReportsMissingRunOnEmptyLog() {
        SessionLog log = AguiSessionLogTestSupport.newLog();

        List<AguiFramedEvent> frames =
                AguiSessionLogAttach.attach(input(), log, 0, "exec-1", CONFIG, POLL)
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertNotNull(frames);
        AguiEvent.RunError error = (AguiEvent.RunError) frames.get(frames.size() - 1).event();
        assertEquals("INVALID_INPUT_ERROR", error.code());
    }

    @Test
    void attachRejectsCursorAheadOfHead() {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        List<AguiFramedEvent> frames =
                AguiSessionLogAttach.attach(input(), log, 10, "exec-1", CONFIG, POLL)
                        .collectList()
                        .block(Duration.ofSeconds(5));

        AguiEvent.RunError error = (AguiEvent.RunError) frames.get(frames.size() - 1).event();
        assertEquals("INVALID_INPUT_ERROR", error.code());
    }

    @Test
    void attachWaitsForCursorUntilHeadCatchesUp() throws Exception {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        append(log, writer, "model/chunk", modelChunk("call-1", "chat-1", textBlock("hi")));

        Thread appender =
                new Thread(
                        () -> {
                            try {
                                Thread.sleep(200);
                                append(log, writer, "run/end", Map.of("status", "completed"));
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            } finally {
                                log.release(writer);
                            }
                        });
        appender.start();
        try {
            List<AguiFramedEvent> frames =
                    AguiSessionLogAttach.attach(input(), log, 2, "exec-1", CONFIG, POLL)
                            .collectList()
                            .block(Duration.ofSeconds(5));

            assertTrue(
                    frames.stream()
                            .anyMatch(frame -> frame.event() instanceof AguiEvent.RunFinished),
                    "attach must wait for the cursor's event to be committed, then finish");
        } finally {
            appender.join(Duration.ofSeconds(5).toMillis());
        }
    }

    @Test
    void attachTimesOutWhenCursorStaysUncommitted() {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        try {
            append(log, writer, "model/chunk", modelChunk("call-1", "chat-1", textBlock("hi")));
            AguiAdapterConfig shortWait =
                    AguiAdapterConfig.builder().runTimeout(Duration.ofMillis(300)).build();

            List<AguiFramedEvent> frames =
                    AguiSessionLogAttach.attach(input(), log, 5, "exec-1", shortWait, POLL)
                            .collectList()
                            .block(Duration.ofSeconds(5));

            AguiEvent.RunError error = (AguiEvent.RunError) frames.get(frames.size() - 1).event();
            assertEquals("TIMEOUT_ERROR", error.code());
        } finally {
            log.release(writer);
        }
    }

    @Test
    void attachUntilTimesOutOnUncommittedBoundary() {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        try {
            long seq =
                    append(
                            log,
                            writer,
                            "model/chunk",
                            modelChunk("call-1", "chat-1", textBlock("hi")));
            AguiAdapterConfig shortWait =
                    AguiAdapterConfig.builder().runTimeout(Duration.ofMillis(200)).build();

            List<AguiFramedEvent> frames =
                    AguiSessionLogAttach.attachUntil(input(), log, 0, seq + 5, "exec-1", shortWait)
                            .collectList()
                            .block(Duration.ofSeconds(5));

            AguiFramedEvent last = frames.get(frames.size() - 1);
            assertEquals("TIMEOUT_ERROR", ((AguiEvent.RunError) last.event()).code());
            assertEquals(seq, last.seq(), "the failure frame reports the last emitted sequence");
        } finally {
            log.release(writer);
        }
    }

    @Test
    void attachUntilRejectsUncommittedBoundary() {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        long seq;
        try {
            seq =
                    append(
                            log,
                            writer,
                            "model/chunk",
                            modelChunk("call-1", "chat-1", textBlock("hi")));
        } finally {
            log.release(writer);
        }

        List<AguiFramedEvent> frames =
                AguiSessionLogAttach.attachUntil(input(), log, 0, seq + 5, "exec-1", CONFIG)
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertNotNull(frames);
        AguiEvent.RunError error = (AguiEvent.RunError) frames.get(frames.size() - 1).event();
        assertEquals("INVALID_INPUT_ERROR", error.code());
    }

    @Test
    void attachUntilRejectsInvertedRange() {
        SessionLog log = AguiSessionLogTestSupport.newLog();

        List<AguiFramedEvent> frames =
                AguiSessionLogAttach.attachUntil(input(), log, 5, 3, "exec-1", CONFIG)
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertNotNull(frames);
        AguiEvent.RunError error = (AguiEvent.RunError) frames.get(frames.size() - 1).event();
        assertEquals("INVALID_INPUT_ERROR", error.code());
    }

    @Test
    void attachKeepsCursorWhenEventProjectionFails() {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        try {
            append(log, writer, "model/chunk", modelChunk("call-1", "chat-1", textBlock("hi")));
            append(log, writer, "model/chunk", modelChunk("call-1", "chat-1", Map.of("text", "x")));
        } finally {
            log.release(writer);
        }

        List<AguiFramedEvent> frames =
                AguiSessionLogAttach.attach(input(), log, 0, "exec-1", CONFIG, POLL)
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertNotNull(frames);
        AguiFramedEvent last = frames.get(frames.size() - 1);
        assertTrue(last.event() instanceof AguiEvent.RunError);
        assertEquals("SESSION_LOG_ERROR", ((AguiEvent.RunError) last.event()).code());
        assertEquals(0L, last.seq(), "the cursor must not advance past an unprojected event");
    }

    @Test
    void attachDoesNotProbeTheStoreOnTheSubscribingThread() throws Exception {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        long cursor;
        try {
            append(log, writer, "run/start", Map.of("executionRunId", "exec-1"));
            append(log, writer, "run/end", Map.of("status", "completed"));
            cursor = log.head().seq();
        } finally {
            log.release(writer);
        }
        AtomicInteger headCalls = new AtomicInteger();
        SessionLog slow =
                (SessionLog)
                        Proxy.newProxyInstance(
                                SessionLog.class.getClassLoader(),
                                new Class<?>[] {SessionLog.class},
                                (proxy, method, args) -> {
                                    if ("head".equals(method.getName())
                                            && headCalls.getAndIncrement() == 0) {
                                        Thread.sleep(300);
                                    }
                                    try {
                                        return method.invoke(log, args);
                                    } catch (InvocationTargetException error) {
                                        throw error.getCause();
                                    }
                                });

        List<AguiFramedEvent> frames = new CopyOnWriteArrayList<>();
        long start = System.nanoTime();
        Disposable subscription =
                AguiSessionLogAttach.attach(input(), slow, cursor, "exec-1", CONFIG, POLL)
                        .subscribe(frames::add);
        try {
            long subscribeMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertTrue(
                    subscribeMillis < 250,
                    "subscribing must not run the blocking probe inline: " + subscribeMillis);
            long deadline = System.currentTimeMillis() + 5_000;
            while (frames.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertFalse(frames.isEmpty(), "the attach must still start after the probe");
        } finally {
            subscription.dispose();
        }
    }

    @Test
    void attachFailsWhenCommittedEventsCannotBeRead() throws Exception {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        try {
            append(log, writer, "model/chunk", modelChunk("call-1", "chat-1", textBlock("hi")));
        } finally {
            log.release(writer);
        }
        SessionLog unreadable =
                (SessionLog)
                        Proxy.newProxyInstance(
                                SessionLog.class.getClassLoader(),
                                new Class<?>[] {SessionLog.class},
                                (proxy, method, args) -> {
                                    if ("readAfter".equals(method.getName())
                                            || "scan".equals(method.getName())) {
                                        throw new IllegalStateException("unreadable session log");
                                    }
                                    try {
                                        return method.invoke(log, args);
                                    } catch (InvocationTargetException error) {
                                        throw error.getCause();
                                    }
                                });

        List<AguiFramedEvent> frames =
                AguiSessionLogAttach.attach(input(), unreadable, 0, "exec-1", CONFIG, POLL)
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertNotNull(frames);
        AguiFramedEvent last = frames.get(frames.size() - 1);
        assertTrue(last.event() instanceof AguiEvent.RunError);
        assertEquals("SESSION_LOG_ERROR", ((AguiEvent.RunError) last.event()).code());
    }

    @Test
    void attachUntilReplaysACommittedRangeWithOneScan() {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        long through;
        try {
            append(log, writer, "run/start", Map.of("executionRunId", "exec-1"));
            for (int i = 0; i < 300; i++) {
                append(log, writer, "message/user", message(user("u" + i, "message " + i)));
            }
            through = log.head().seq();
        } finally {
            log.release(writer);
        }
        AtomicInteger scanCalls = new AtomicInteger();
        SessionLog counted =
                (SessionLog)
                        Proxy.newProxyInstance(
                                SessionLog.class.getClassLoader(),
                                new Class<?>[] {SessionLog.class},
                                (proxy, method, args) -> {
                                    if ("scan".equals(method.getName())) {
                                        scanCalls.incrementAndGet();
                                    }
                                    try {
                                        return method.invoke(log, args);
                                    } catch (InvocationTargetException error) {
                                        throw error.getCause();
                                    }
                                });

        List<AguiFramedEvent> frames =
                AguiSessionLogAttach.attachUntil(input(), counted, 0, through, "exec-1", CONFIG)
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertNotNull(frames);
        assertEquals(1, scanCalls.get(), "a committed range must be replayed in one scan pass");
        assertFalse(
                frames.stream().anyMatch(frame -> frame.event() instanceof AguiEvent.RunError),
                "one-pass replay must finish without an error frame");
    }

    @Test
    void attachUntilWaitsForUncommittedBoundary() throws Exception {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        long first =
                append(log, writer, "model/chunk", modelChunk("call-1", "chat-1", textBlock("hi")));
        long through = first + 1;

        Thread appender =
                new Thread(
                        () -> {
                            try {
                                Thread.sleep(200);
                                append(
                                        log,
                                        writer,
                                        "model/chunk",
                                        modelChunk("call-1", "chat-1", textBlock("lo")));
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            } finally {
                                log.release(writer);
                            }
                        });
        appender.start();

        try {
            List<AguiFramedEvent> frames =
                    AguiSessionLogAttach.attachUntil(input(), log, 0, through, "exec-1", CONFIG)
                            .collectList()
                            .block(Duration.ofSeconds(5));

            assertFalse(frames.isEmpty());
            assertFalse(
                    frames.get(frames.size() - 1).hasCursor(),
                    "a streamed chunk must not advance the replay cursor");
        } finally {
            appender.join(Duration.ofSeconds(5).toMillis());
        }
    }

    @Test
    void attachWithRunIdIgnoresOtherRuns() throws Exception {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        try {
            append(log, writer, "run/start", "exec-1", Map.of("executionRunId", "exec-1"));
            append(log, writer, "run/start", "exec-2", Map.of("executionRunId", "exec-2"));
            long cursor = log.head().seq();

            Thread producer =
                    new Thread(
                            () -> {
                                try {
                                    Thread.sleep(50);
                                    append(
                                            log,
                                            writer,
                                            "model/chunk",
                                            "exec-2",
                                            modelChunk("call-2", "chat-2", textBlock("other")));
                                    append(
                                            log,
                                            writer,
                                            "model/chunk",
                                            "exec-1",
                                            modelChunk("call-1", "chat-1", textBlock("mine")));
                                    append(
                                            log,
                                            writer,
                                            "run/end",
                                            "exec-2",
                                            Map.of("status", "completed"));
                                    append(
                                            log,
                                            writer,
                                            "run/end",
                                            "exec-1",
                                            Map.of("status", "completed"));
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                }
                            });
            producer.start();

            List<AguiFramedEvent> frames =
                    AguiSessionLogAttach.attach(input(), log, cursor, "exec-1", CONFIG, POLL)
                            .collectList()
                            .block(Duration.ofSeconds(5));
            producer.join(1000);

            String text =
                    frames.stream()
                            .filter(frame -> frame.event() instanceof AguiEvent.TextMessageContent)
                            .map(frame -> ((AguiEvent.TextMessageContent) frame.event()).delta())
                            .reduce("", String::concat);
            assertEquals("mine", text);
            assertTrue(
                    frames.stream()
                            .anyMatch(frame -> frame.event() instanceof AguiEvent.RunFinished));
        } finally {
            log.release(writer);
        }
    }

    @Test
    void attachUntilReplaysUpToBoundaryWithoutRunFinished() {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        long secondChunk;
        try {
            append(log, writer, "run/start", Map.of("executionRunId", "exec-1"));
            append(log, writer, "message/user", message(user("u1", "hello")));
            append(log, writer, "model/chunk", modelChunk("call-1", "chat-1", textBlock("Hel")));
            secondChunk =
                    append(
                            log,
                            writer,
                            "model/chunk",
                            modelChunk("call-1", "chat-1", textBlock("lo")));
            append(
                    log,
                    writer,
                    "model/end",
                    Map.of("modelCallId", "call-1", "status", "completed"));
            append(log, writer, "run/end", Map.of("status", "completed"));
        } finally {
            log.release(writer);
        }

        List<AguiFramedEvent> frames =
                AguiSessionLogAttach.attachUntil(input(), log, 0, secondChunk, "exec-1", CONFIG)
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertEquals(4, frames.size());
        assertTrue(
                frames.stream().anyMatch(frame -> frame.event() instanceof AguiEvent.RunStarted));
        assertTrue(
                frames.stream().noneMatch(frame -> frame.event() instanceof AguiEvent.RunFinished));
        List<String> deltas =
                frames.stream()
                        .filter(frame -> frame.event() instanceof AguiEvent.TextMessageContent)
                        .map(frame -> ((AguiEvent.TextMessageContent) frame.event()).delta())
                        .toList();
        assertEquals(List.of("Hel", "lo"), deltas);
        assertEquals(
                -1L,
                frames.get(frames.size() - 1).seq(),
                "a streamed chunk must not advance the replay cursor");
    }

    @Test
    void attachUntilEmptyRangeCompletesWithoutFrames() {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        long cursor;
        try {
            append(log, writer, "run/start", Map.of("executionRunId", "exec-1"));
            append(log, writer, "run/end", Map.of("status", "completed"));
            cursor = log.head().seq();
        } finally {
            log.release(writer);
        }

        List<AguiFramedEvent> frames =
                AguiSessionLogAttach.attachUntil(input(), log, cursor, cursor, "exec-1", CONFIG)
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertTrue(frames.isEmpty());
    }

    @Test
    void attachUntilFiltersByExecutionRunId() {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        long last;
        try {
            append(
                    log,
                    writer,
                    "model/chunk",
                    "exec-1",
                    modelChunk("call-1", "chat-1", textBlock("mine")));
            last =
                    append(
                            log,
                            writer,
                            "model/chunk",
                            "exec-2",
                            modelChunk("call-2", "chat-2", textBlock("other")));
        } finally {
            log.release(writer);
        }

        List<AguiFramedEvent> frames =
                AguiSessionLogAttach.attachUntil(input(), log, 0, last, "exec-1", CONFIG)
                        .collectList()
                        .block(Duration.ofSeconds(5));

        List<String> deltas =
                frames.stream()
                        .filter(frame -> frame.event() instanceof AguiEvent.TextMessageContent)
                        .map(frame -> ((AguiEvent.TextMessageContent) frame.event()).delta())
                        .toList();
        assertEquals(List.of("mine"), deltas);
    }

    @Test
    void attachUntilCarriesCursorOnlyOnCompletedEvents() {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        long endSeq;
        try {
            append(log, writer, "model/chunk", modelChunk("call-1", "chat-1", textBlock("hi")));
            endSeq = append(log, writer, "model/end", Map.of("modelCallId", "call-1"));
        } finally {
            log.release(writer);
        }

        List<AguiFramedEvent> frames =
                AguiSessionLogAttach.attachUntil(input(), log, 0, endSeq, "exec-1", CONFIG)
                        .collectList()
                        .block(Duration.ofSeconds(5));

        List<AguiFramedEvent> cursored =
                frames.stream().filter(AguiFramedEvent::hasCursor).toList();
        assertEquals(1, cursored.size(), "only the completed event advances the cursor");
        assertEquals(endSeq, cursored.get(0).seq());
    }

    @Test
    void attachDeliversATerminalFrameBroadcastBeforeTheTailWasTaken() {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        try {
            append(log, writer, "run/start", Map.of("executionRunId", "exec-1"));
            append(log, writer, "model/chunk", modelChunk("call-1", "chat-1", textBlock("hi")));
            append(log, writer, "model/end", Map.of("modelCallId", "call-1"));
        } finally {
            log.release(writer);
        }
        AguiRunBroadcaster broadcaster = new AguiRunBroadcaster();
        broadcaster.open(THREAD_ID, "exec-1");
        // What the adapter produces today: a chunk event carries its seq, a model/end event does
        // not, so the broadcast watermark stops at the chunk.
        broadcaster.publish(
                THREAD_ID,
                "exec-1",
                new AguiFramedEvent(
                        2, 2, new AguiEvent.TextMessageContent(THREAD_ID, "run-1", "m-1", "hi")));
        broadcaster.publish(
                THREAD_ID,
                "exec-1",
                new AguiFramedEvent(
                        -1, -1, new AguiEvent.TextMessageEnd(THREAD_ID, "run-1", "m-1")));
        AguiRunBroadcaster.LiveRun live = broadcaster.live(THREAD_ID);
        broadcaster.close(THREAD_ID, "exec-1");

        List<AguiFramedEvent> frames =
                AguiSessionLogAttach.attach(input(), log, 1, live, "exec-1", CONFIG)
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertNotNull(frames);
        assertTrue(
                frames.stream()
                        .anyMatch(frame -> frame.event() instanceof AguiEvent.TextMessageEnd),
                "the terminal frame must be delivered even when it was broadcast with no source"
                        + " seq");
    }

    @Test
    void attachResumesFromLiveTailWithoutDuplicatingTheReplayBoundary() {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        long firstChunk;
        long secondChunk;
        try {
            append(log, writer, "run/start", Map.of("executionRunId", "exec-1"));
            firstChunk =
                    append(
                            log,
                            writer,
                            "model/chunk",
                            modelChunk("call-1", "chat-1", textBlock("a")));
            secondChunk =
                    append(
                            log,
                            writer,
                            "model/chunk",
                            modelChunk("call-1", "chat-1", textBlock("b")));
        } finally {
            log.release(writer);
        }
        AguiRunBroadcaster broadcaster = new AguiRunBroadcaster();
        broadcaster.open(THREAD_ID, "exec-1");
        broadcaster.publish(
                THREAD_ID,
                "exec-1",
                List.of(
                        new AguiFramedEvent(
                                -1,
                                firstChunk,
                                new AguiEvent.TextMessageStart(
                                        THREAD_ID, "run-1", "m-1", "assistant")),
                        new AguiFramedEvent(
                                firstChunk,
                                firstChunk,
                                new AguiEvent.TextMessageContent(THREAD_ID, "run-1", "m-1", "a"))));
        AguiRunBroadcaster.LiveRun live = broadcaster.live(THREAD_ID);
        broadcaster.publish(
                THREAD_ID,
                "exec-1",
                new AguiFramedEvent(
                        secondChunk,
                        secondChunk,
                        new AguiEvent.TextMessageContent(THREAD_ID, "run-1", "m-1", "b")));
        broadcaster.close(THREAD_ID, "exec-1");

        List<AguiFramedEvent> frames =
                AguiSessionLogAttach.attach(input(), log, firstChunk - 1, live, "exec-1", CONFIG)
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertNotNull(frames);
        List<String> deltas =
                frames.stream()
                        .filter(frame -> frame.event() instanceof AguiEvent.TextMessageContent)
                        .map(frame -> ((AguiEvent.TextMessageContent) frame.event()).delta())
                        .toList();
        assertEquals(List.of("a", "b"), deltas);
        assertEquals(
                1,
                frames.stream()
                        .filter(frame -> frame.event() instanceof AguiEvent.TextMessageStart)
                        .count(),
                "the replay boundary must not be announced twice");
    }

    @Test
    void attachReconnectsFromACursorInsideAStreamedMessage() {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        long firstChunk;
        try {
            append(log, writer, "run/start", Map.of("executionRunId", "exec-1"));
            firstChunk =
                    append(
                            log,
                            writer,
                            "model/chunk",
                            modelChunk("call-1", "chat-1", textBlock("a")));
        } finally {
            log.release(writer);
        }
        AguiRunBroadcaster broadcaster = new AguiRunBroadcaster();
        broadcaster.open(THREAD_ID, "exec-1");
        AguiRunBroadcaster.LiveRun live = broadcaster.live(THREAD_ID);
        // The second chunk is produced after the tail was taken; the client already holds the
        // first one, so the cursor is a chunk sequence, not an event boundary.
        broadcaster.publish(
                THREAD_ID,
                "exec-1",
                new AguiFramedEvent(
                        -1,
                        firstChunk + 1,
                        new AguiEvent.TextMessageContent(THREAD_ID, "run-1", "m-1", "b")));
        broadcaster.close(THREAD_ID, "exec-1");

        List<AguiFramedEvent> frames =
                AguiSessionLogAttach.attach(input(), log, firstChunk, live, "exec-1", CONFIG)
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertNotNull(frames);
        List<String> deltas =
                frames.stream()
                        .filter(frame -> frame.event() instanceof AguiEvent.TextMessageContent)
                        .map(frame -> ((AguiEvent.TextMessageContent) frame.event()).delta())
                        .toList();
        assertEquals(
                List.of("b"),
                deltas,
                "a cursor on a chunk must not drop the message tail or replay the chunk");
    }

    @Test
    void attachServesRecentFramesWithoutWaitingForTheLog() {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        AguiRunBroadcaster broadcaster = new AguiRunBroadcaster();
        broadcaster.open(THREAD_ID, "exec-1");
        broadcaster.publish(
                THREAD_ID,
                "exec-1",
                List.of(
                        new AguiFramedEvent(
                                -1,
                                5,
                                new AguiEvent.TextMessageStart(
                                        THREAD_ID, "run-1", "m-1", "assistant")),
                        new AguiFramedEvent(
                                5,
                                5,
                                new AguiEvent.TextMessageContent(THREAD_ID, "run-1", "m-1", "a"))));
        AguiRunBroadcaster.LiveRun live = broadcaster.live(THREAD_ID);
        broadcaster.close(THREAD_ID, "exec-1");

        // The log is empty: only the recent buffer can serve this cursor.
        List<AguiFramedEvent> frames =
                AguiSessionLogAttach.attach(input(), log, 4, live, "exec-1", CONFIG)
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertNotNull(frames);
        assertFalse(
                frames.stream().anyMatch(frame -> frame.event() instanceof AguiEvent.RunError),
                "the recent buffer must bridge the cursor without a log error");
        List<String> deltas =
                frames.stream()
                        .filter(frame -> frame.event() instanceof AguiEvent.TextMessageContent)
                        .map(frame -> ((AguiEvent.TextMessageContent) frame.event()).delta())
                        .toList();
        assertEquals(List.of("a"), deltas);
    }

    @Test
    void attachReplaysTheLogBeforeTheRecentBuffer() {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        try {
            append(log, writer, "run/start", Map.of("executionRunId", "exec-1"));
            append(log, writer, "model/chunk", modelChunk("call-1", "chat-1", textBlock("a")));
        } finally {
            log.release(writer);
        }
        AguiRunBroadcaster broadcaster = new AguiRunBroadcaster();
        broadcaster.open(THREAD_ID, "exec-1");
        broadcaster.publish(
                THREAD_ID,
                "exec-1",
                new AguiFramedEvent(
                        -1, 3, new AguiEvent.TextMessageContent(THREAD_ID, "run-1", "m-1", "b")));
        AguiRunBroadcaster.LiveRun live = broadcaster.live(THREAD_ID);
        broadcaster.close(THREAD_ID, "exec-1");

        List<AguiFramedEvent> frames =
                AguiSessionLogAttach.attach(input(), log, 1, live, "exec-1", CONFIG)
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertNotNull(frames);
        List<String> deltas =
                frames.stream()
                        .filter(frame -> frame.event() instanceof AguiEvent.TextMessageContent)
                        .map(frame -> ((AguiEvent.TextMessageContent) frame.event()).delta())
                        .toList();
        assertEquals(List.of("a", "b"), deltas);
    }

    @Test
    void attachFallsBackWhenLiveFramesCarryNoSequence() {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        try {
            append(log, writer, "run/start", Map.of("executionRunId", "exec-1"));
            append(log, writer, "model/chunk", modelChunk("call-1", "chat-1", textBlock("logged")));
            append(log, writer, "run/end", Map.of("status", "completed"));
        } finally {
            log.release(writer);
        }
        AguiFramedEvent liveFrame =
                new AguiFramedEvent(
                        -1,
                        -1,
                        new AguiEvent.TextMessageContent(THREAD_ID, "run-1", "m-1", "live"));
        AguiRunBroadcaster.LiveRun live =
                new AguiRunBroadcaster.LiveRun(
                        "exec-1", -1, -1, List.of(liveFrame), Flux.just(liveFrame));

        List<AguiFramedEvent> frames =
                AguiSessionLogAttach.attach(input(), log, 0, live, "exec-1", CONFIG)
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertNotNull(frames);
        List<String> deltas =
                frames.stream()
                        .filter(frame -> frame.event() instanceof AguiEvent.TextMessageContent)
                        .map(frame -> ((AguiEvent.TextMessageContent) frame.event()).delta())
                        .toList();
        assertEquals(
                List.of("logged"),
                deltas,
                "an all-uncursored live tail must not duplicate the replayed log");
    }

    @Test
    void attachFallsBackToTheLogWhenTheLiveTailFails() {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        try {
            append(log, writer, "run/start", Map.of("executionRunId", "exec-1"));
            append(log, writer, "model/chunk", modelChunk("call-1", "chat-1", textBlock("a")));
            append(log, writer, "run/end", Map.of("status", "completed"));
        } finally {
            log.release(writer);
        }
        AguiRunBroadcaster.LiveRun live =
                new AguiRunBroadcaster.LiveRun(
                        "exec-1",
                        0,
                        -1,
                        List.of(),
                        Flux.error(new IllegalStateException("tail overflow")));

        List<AguiFramedEvent> frames =
                AguiSessionLogAttach.attach(input(), log, 0, live, "exec-1", CONFIG)
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertNotNull(frames);
        assertFalse(
                frames.stream().anyMatch(frame -> frame.event() instanceof AguiEvent.RunError),
                "a tail failure must degrade to log replay, not abort the stream");
        assertTrue(
                frames.stream().anyMatch(frame -> frame.event() instanceof AguiEvent.RunFinished));
    }

    @Test
    void attachReplayAndLiveTailUseTheSameTurnScopedTextMessageId() {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        long firstChunk;
        try {
            append(log, writer, "run/start", Map.of("executionRunId", "exec-1"));
            firstChunk =
                    append(
                            log,
                            writer,
                            "model/chunk",
                            modelChunk("call-1", "chat-1", textBlock("a")));
        } finally {
            log.release(writer);
        }
        AguiRunBroadcaster broadcaster = new AguiRunBroadcaster();
        broadcaster.open(THREAD_ID, "exec-1");
        broadcaster.publish(
                THREAD_ID,
                "exec-1",
                List.of(
                        new AguiFramedEvent(
                                -1,
                                firstChunk,
                                new AguiEvent.TextMessageStart(
                                        THREAD_ID, "run-1", TURN_ID, "assistant")),
                        new AguiFramedEvent(
                                firstChunk,
                                firstChunk,
                                new AguiEvent.TextMessageContent(
                                        THREAD_ID, "run-1", TURN_ID, "a"))));
        AguiRunBroadcaster.LiveRun live = broadcaster.live(THREAD_ID);
        broadcaster.publish(
                THREAD_ID,
                "exec-1",
                new AguiFramedEvent(
                        firstChunk + 1,
                        firstChunk + 1,
                        new AguiEvent.TextMessageContent(THREAD_ID, "run-1", TURN_ID, "b")));
        broadcaster.close(THREAD_ID, "exec-1");

        List<AguiFramedEvent> frames =
                AguiSessionLogAttach.attach(input(), log, firstChunk - 1, live, "exec-1", CONFIG)
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertNotNull(frames);
        List<String> startIds =
                frames.stream()
                        .filter(frame -> frame.event() instanceof AguiEvent.TextMessageStart)
                        .map(frame -> ((AguiEvent.TextMessageStart) frame.event()).messageId())
                        .toList();
        List<String> contentIds =
                frames.stream()
                        .filter(frame -> frame.event() instanceof AguiEvent.TextMessageContent)
                        .map(frame -> ((AguiEvent.TextMessageContent) frame.event()).messageId())
                        .distinct()
                        .toList();
        assertEquals(List.of(TURN_ID), startIds);
        assertEquals(List.of(TURN_ID), contentIds);
    }

    @Test
    void attachDoesNotDuplicateTheAssistantMessageAcrossTheHandoff() {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        long firstChunk;
        try {
            append(log, writer, "run/start", Map.of("executionRunId", "exec-1"));
            firstChunk =
                    append(
                            log,
                            writer,
                            "model/chunk",
                            modelChunk("call-1", "chat-1", textBlock("a")));
        } finally {
            log.release(writer);
        }
        AguiRunBroadcaster broadcaster = new AguiRunBroadcaster();
        broadcaster.open(THREAD_ID, "exec-1");
        broadcaster.publish(
                THREAD_ID,
                "exec-1",
                List.of(
                        new AguiFramedEvent(
                                -1,
                                firstChunk,
                                new AguiEvent.TextMessageStart(
                                        THREAD_ID, "run-1", TURN_ID, "assistant")),
                        new AguiFramedEvent(
                                firstChunk,
                                firstChunk,
                                new AguiEvent.TextMessageContent(
                                        THREAD_ID, "run-1", TURN_ID, "a"))));
        AguiRunBroadcaster.LiveRun live = broadcaster.live(THREAD_ID);
        broadcaster.publish(
                THREAD_ID,
                "exec-1",
                new AguiFramedEvent(
                        firstChunk + 1,
                        firstChunk + 1,
                        new AguiEvent.TextMessageContent(THREAD_ID, "run-1", TURN_ID, "b")));
        broadcaster.close(THREAD_ID, "exec-1");

        List<AguiFramedEvent> frames =
                AguiSessionLogAttach.attach(input(), log, firstChunk - 1, live, "exec-1", CONFIG)
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertNotNull(frames);
        assertEquals(
                1,
                frames.stream()
                        .filter(frame -> frame.event() instanceof AguiEvent.TextMessageStart)
                        .count(),
                "the assistant message must be announced once across the handoff");
        List<String> deltas =
                frames.stream()
                        .filter(frame -> frame.event() instanceof AguiEvent.TextMessageContent)
                        .map(frame -> ((AguiEvent.TextMessageContent) frame.event()).delta())
                        .toList();
        assertEquals(List.of("a", "b"), deltas, "the replay boundary must not repeat text");
    }

    @Test
    void attachReplayAndLiveInterruptsShareTheTurnScopedId() {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        try {
            append(log, writer, "run/start", Map.of("executionRunId", "exec-1"));
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("requestId", "tool-1");
            request.put("kind", "confirmation");
            request.put(
                    "call", toolUseBlock("tool-1", "search", Map.of("q", "x"), "{\"q\":\"x\"}"));
            append(log, writer, "interaction/requested", request);
            append(log, writer, "run/end", Map.of("status", "completed"));
        } finally {
            log.release(writer);
        }

        List<AguiFramedEvent> frames =
                AguiSessionLogAttach.attach(input(), log, 0, "exec-1", CONFIG, POLL)
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertNotNull(frames);
        AguiEvent.RunFinished finished =
                (AguiEvent.RunFinished) frames.get(frames.size() - 1).event();
        AguiEvent.RunFinishedInterruptOutcome outcome =
                (AguiEvent.RunFinishedInterruptOutcome) finished.outcome();
        String replayId = outcome.interrupts().get(0).id();
        String liveId =
                AguiInterrupts.permissionConfirm(
                                TURN_ID, "reply-1", toolUse("tool-1", "search", "{\"q\":\"x\"}"))
                        .id();
        assertEquals(TURN_ID + ":tool-1", replayId);
        assertEquals(replayId, liveId);
    }

    private static RunAgentInput input() {
        return RunAgentInput.builder().threadId(THREAD_ID).runId("run-1").build();
    }
}
