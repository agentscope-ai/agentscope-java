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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ExecutionConfig;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ToolExecutionCallbackTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @ParameterizedTest
    @ValueSource(strings = {"progress", "result"})
    void callbackFailurePreservesProgressIsolationAndDoesNotRetryTool(String phase) {
        Toolkit toolkit = new Toolkit();
        AtomicInteger calls = new AtomicInteger();
        toolkit.registerTool(
                tool(
                        p -> {
                            calls.incrementAndGet();
                            p.getEmitter().emit(ToolResultBlock.text("working"));
                            return Mono.just(ToolResultBlock.text("done"));
                        }));
        ToolExecutionCallback callback =
                new ToolExecutionCallback() {
                    void fail(String point) {
                        if (phase.equals(point)) throw new IllegalStateException("notification");
                    }

                    public void onProgress(ToolUseBlock call, ToolResultBlock chunk) {
                        fail("progress");
                    }

                    public void onResult(ToolUseBlock call, ToolResultBlock result) {
                        fail("result");
                    }
                };
        ExecutionConfig config =
                ExecutionConfig.builder()
                        .maxAttempts(3)
                        .initialBackoff(Duration.ofMillis(1))
                        .build();
        if (phase.equals("result")) {
            StepVerifier.create(run(toolkit, callback, config))
                    .expectErrorMatches(
                            error ->
                                    error instanceof IllegalStateException
                                            && "notification".equals(error.getMessage()))
                    .verify(TIMEOUT);
        } else {
            StepVerifier.create(run(toolkit, callback, config))
                    .assertNext(
                            results ->
                                    assertEquals(
                                            "done",
                                            ((io.agentscope.core.message.TextBlock)
                                                            results.get(0).getOutput().get(0))
                                                    .getText()))
                    .verifyComplete();
        }
        assertEquals(1, calls.get());
    }

    @Test
    void notifiesResultOnceAfterRetry() {
        AtomicInteger attempts = new AtomicInteger();
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(
                tool(
                        p ->
                                attempts.incrementAndGet() == 1
                                        ? Mono.never()
                                        : Mono.just(ToolResultBlock.text("retried"))));
        List<String> events = new CopyOnWriteArrayList<>();
        ExecutionConfig config =
                ExecutionConfig.builder()
                        .timeout(Duration.ofMillis(100))
                        .maxAttempts(2)
                        .initialBackoff(Duration.ofMillis(1))
                        .build();
        List<ToolResultBlock> results = run(toolkit, recording(events), config).block(TIMEOUT);
        assertEquals(2, attempts.get());
        assertEquals("a", results.get(0).getId());
        assertEquals("retried", ((TextBlock) results.get(0).getOutput().get(0)).getText());
        assertEquals(List.of("start", "result"), events);
    }

    @Test
    void notifiesResultWhenToolFails() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(
                tool(p -> Mono.error(new IllegalArgumentException("business failure"))));
        List<String> events = new CopyOnWriteArrayList<>();
        List<ToolResultBlock> results = run(toolkit, recording(events), null).block(TIMEOUT);
        assertEquals("a", results.get(0).getId());
        assertTrue(results.get(0).getOutput().toString().contains("business failure"));
        assertEquals(List.of("start", "result"), events);
    }

    @Test
    void cancellingStopsSubscriptionWithoutFinalResult() throws Exception {
        Toolkit toolkit = new Toolkit();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        toolkit.registerTool(
                tool(
                        p -> {
                            return Mono.<ToolResultBlock>never()
                                    .doOnSubscribe(s -> started.countDown())
                                    .doOnCancel(cancelled::countDown);
                        }));
        List<String> events = new CopyOnWriteArrayList<>();
        var subscription = run(toolkit, recording(events), null).subscribe();
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS));
            subscription.dispose();
            assertTrue(cancelled.await(3, TimeUnit.SECONDS));
            assertEquals(
                    List.of("start"),
                    events,
                    "start fires at subscription; no final result after cancellation");
        } finally {
            subscription.dispose();
        }
    }

    @Test
    void callbacksPreserveLegacyProgressAndSubscriptionLifecycle() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(
                tool(
                        p -> {
                            p.getEmitter().emit(ToolResultBlock.text("progress"));
                            return Mono.just(ToolResultBlock.text("result"));
                        }));
        AtomicInteger internal = new AtomicInteger();
        AtomicInteger user = new AtomicInteger();
        toolkit.setChunkCallback((call, chunk) -> user.incrementAndGet());
        toolkit.callTools(
                        List.of(call()),
                        null,
                        null,
                        null,
                        (call, chunk) -> internal.incrementAndGet())
                .block(TIMEOUT);
        assertEquals(1, internal.get());
        assertEquals(1, user.get());
        List<String> events = new CopyOnWriteArrayList<>();
        Mono<List<ToolResultBlock>> execution = run(toolkit, recording(events), null);
        assertTrue(events.isEmpty());
        assertEquals(1, user.get());
        execution.block(TIMEOUT);
        execution.block(TIMEOUT);
        assertEquals(List.of("start", "progress", "result", "start", "progress", "result"), events);
        assertEquals(3, user.get());
    }

    private static ToolExecutionCallback recording(List<String> events) {
        return new ToolExecutionCallback() {
            public void onStart(ToolUseBlock call) {
                events.add("start");
            }

            public void onProgress(ToolUseBlock call, ToolResultBlock result) {
                events.add("progress");
            }

            public void onResult(ToolUseBlock call, ToolResultBlock result) {
                events.add("result");
            }
        };
    }

    private static ToolUseBlock call() {
        return ToolUseBlock.builder().id("a").name("work").input(Map.of()).content("{}").build();
    }

    private static Mono<List<ToolResultBlock>> run(
            Toolkit toolkit, ToolExecutionCallback callback, ExecutionConfig config) {
        return toolkit.callToolsWithCallback(
                List.of(call()), config, null, null, ToolRequestConfig.NONE, callback);
    }

    private static AgentTool tool(Function<ToolCallParam, Mono<ToolResultBlock>> body) {
        return new AgentTool() {
            public String getName() {
                return "work";
            }

            public String getDescription() {
                return "work";
            }

            public Map<String, Object> getParameters() {
                return Map.of("type", "object", "properties", Map.of());
            }

            public Mono<ToolResultBlock> callAsync(ToolCallParam p) {
                return body.apply(p);
            }
        };
    }
}
