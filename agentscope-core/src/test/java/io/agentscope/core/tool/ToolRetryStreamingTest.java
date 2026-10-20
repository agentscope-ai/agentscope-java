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
package io.agentscope.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.session.InMemorySessionLogStore;
import io.agentscope.core.session.SessionKey;
import io.agentscope.core.session.SessionRecorder;
import io.agentscope.core.state.AgentState;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

@Tag("unit")
class ToolRetryStreamingTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void explicitPredicateCanRetryDeterministicFailures() {
        Toolkit toolkit = new Toolkit();
        AtomicInteger calls = new AtomicInteger();
        register(
                toolkit,
                param ->
                        calls.incrementAndGet() == 1
                                ? Mono.error(new IllegalStateException("Explicitly retryable"))
                                : Mono.just(ToolResultBlock.text("done")));
        ExecutionConfig config =
                ExecutionConfig.builder()
                        .maxAttempts(2)
                        .initialBackoff(Duration.ofMillis(1))
                        .retryOn(error -> true)
                        .build();

        assertEquals("done", text(call(toolkit, config).block(TIMEOUT).get(0)));
        assertEquals(2, calls.get());
    }

    @Test
    void singleAttemptStreamsBeforeTheToolCompletes() throws Exception {
        Toolkit toolkit = new Toolkit();
        CountDownLatch chunkReceived = new CountDownLatch(1);
        Sinks.One<ToolResultBlock> completion = Sinks.one();
        toolkit.setChunkCallback((use, chunk) -> chunkReceived.countDown());
        register(
                toolkit,
                param -> {
                    param.getEmitter().emit(ToolResultBlock.text("progress"));
                    return completion.asMono();
                });

        CompletableFuture<List<ToolResultBlock>> result =
                call(toolkit, ExecutionConfig.builder().maxAttempts(1).build()).toFuture();
        try {
            assertTrue(chunkReceived.await(3, TimeUnit.SECONDS));
            assertFalse(result.isDone());
            completion.tryEmitValue(ToolResultBlock.text("done"));
            assertEquals("done", text(result.get(3, TimeUnit.SECONDS).get(0)));
        } finally {
            result.cancel(true);
        }
    }

    @Test
    void slowChunkCallbackCannotTimeoutAndReplayASuccessfulAttempt() throws Exception {
        Toolkit toolkit = new Toolkit();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch chunkReceived = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        CountDownLatch retried = new CountDownLatch(1);
        toolkit.setChunkCallback(
                (use, chunk) -> {
                    chunkReceived.countDown();
                    try {
                        assertTrue(releaseCallback.await(4, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                });
        register(
                toolkit,
                param -> {
                    if (calls.incrementAndGet() > 1) retried.countDown();
                    param.getEmitter().emit(ToolResultBlock.text("progress"));
                    return Mono.just(ToolResultBlock.text("done"));
                });

        CompletableFuture<List<ToolResultBlock>> result =
                call(toolkit, retryConfig(Duration.ofSeconds(1))).toFuture();
        try {
            assertTrue(chunkReceived.await(3, TimeUnit.SECONDS));
            assertFalse(retried.await(1200, TimeUnit.MILLISECONDS));
            releaseCallback.countDown();
            assertEquals("done", text(result.get(3, TimeUnit.SECONDS).get(0)));
            assertEquals(1, calls.get());
        } finally {
            releaseCallback.countDown();
            result.cancel(true);
        }
    }

    @Test
    void timedOutAttemptsDiscardPartialAndLateChunks() {
        Toolkit toolkit = new Toolkit();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<ToolEmitter> firstEmitter = new AtomicReference<>();
        List<String> delivered = new CopyOnWriteArrayList<>();
        toolkit.setChunkCallback((use, chunk) -> delivered.add(text(chunk)));
        register(
                toolkit,
                param -> {
                    if (calls.incrementAndGet() == 1) {
                        firstEmitter.set(param.getEmitter());
                        param.getEmitter().emit(ToolResultBlock.text("discarded"));
                        return Mono.never();
                    }
                    firstEmitter.get().emit(ToolResultBlock.text("late"));
                    param.getEmitter().emit(ToolResultBlock.text("kept"));
                    return Mono.just(ToolResultBlock.text("done"));
                });

        var log =
                new InMemorySessionLogStore()
                        .open(new SessionKey("u", "agent", "s"), RuntimeContext.empty());
        SessionRecorder recorder = new SessionRecorder(log, "turn");
        RuntimeContext context =
                RuntimeContext.builder()
                        .userId("u")
                        .sessionId("s")
                        .agentState(AgentState.builder().build())
                        .put(SessionRecorder.CONTEXT_KEY, recorder)
                        .build();
        try {
            ToolResultBlock result =
                    call(toolkit, retryConfig(Duration.ofMillis(100)), context)
                            .block(TIMEOUT)
                            .get(0);
            firstEmitter.get().emit(ToolResultBlock.text("after completion"));
            recorder.flush().block(TIMEOUT);
            assertEquals(2, calls.get());
            assertEquals("done", text(result));
            assertEquals(List.of("kept"), delivered);
        } finally {
            recorder.close();
        }
        var chunks =
                log.readAfter(0, 1000).stream()
                        .filter(event -> event.type().equals("tool/chunk"))
                        .toList();
        assertEquals(4, chunks.size());
        Object firstAction = chunks.get(0).data().get("actionId");
        assertEquals(firstAction, chunks.get(1).data().get("actionId"));
        assertEquals(firstAction, chunks.get(3).data().get("actionId"));
        assertNotEquals(firstAction, chunks.get(2).data().get("actionId"));
    }

    @Test
    void concurrentChunksAreDeliveredExactlyOnce() {
        Toolkit toolkit = new Toolkit();
        List<String> delivered = new CopyOnWriteArrayList<>();
        toolkit.setChunkCallback((use, chunk) -> delivered.add(text(chunk)));
        register(
                toolkit,
                param -> {
                    CompletableFuture<?>[] emissions =
                            IntStream.range(0, 100)
                                    .mapToObj(
                                            i ->
                                                    CompletableFuture.runAsync(
                                                            () ->
                                                                    param.getEmitter()
                                                                            .emit(
                                                                                    ToolResultBlock
                                                                                            .text(
                                                                                                    "chunk-"
                                                                                                            + i))))
                                    .toArray(CompletableFuture<?>[]::new);
                    return Mono.fromFuture(CompletableFuture.allOf(emissions))
                            .thenReturn(ToolResultBlock.text("done"));
                });

        ToolResultBlock result = call(toolkit, retryConfig(TIMEOUT)).block(TIMEOUT).get(0);
        assertEquals(ToolResultState.RUNNING, result.getState());
        assertEquals(100, delivered.size());
        assertEquals(100, new HashSet<>(delivered).size());
    }

    private static ExecutionConfig retryConfig(Duration timeout) {
        return ExecutionConfig.builder()
                .maxAttempts(2)
                .timeout(timeout)
                .initialBackoff(Duration.ofMillis(1))
                .maxBackoff(Duration.ofMillis(10))
                .build();
    }

    private static Mono<List<ToolResultBlock>> call(Toolkit toolkit, ExecutionConfig config) {
        return call(toolkit, config, null);
    }

    private static Mono<List<ToolResultBlock>> call(
            Toolkit toolkit, ExecutionConfig config, RuntimeContext context) {
        ToolUseBlock call =
                ToolUseBlock.builder()
                        .id("stream-call")
                        .name("stream")
                        .input(Map.of())
                        .content("{}")
                        .build();
        return toolkit.callTools(List.of(call), config, null, context);
    }

    private static String text(ToolResultBlock result) {
        return ((TextBlock) result.getOutput().get(0)).getText();
    }

    private static void register(
            Toolkit toolkit, Function<ToolCallParam, Mono<ToolResultBlock>> invocation) {
        toolkit.registerTool(
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "stream";
                    }

                    @Override
                    public String getDescription() {
                        return "Streaming tool";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        return Map.of("type", "object", "properties", Map.of());
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        return invocation.apply(param);
                    }
                });
    }
}
