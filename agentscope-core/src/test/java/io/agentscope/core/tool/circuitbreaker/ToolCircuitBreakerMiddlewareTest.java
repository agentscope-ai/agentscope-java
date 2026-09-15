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
package io.agentscope.core.tool.circuitbreaker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * End-to-end behaviour of {@link ToolCircuitBreakerMiddleware}: outcomes observed on the acting
 * stream must drive what the reasoning phase advertises to the model.
 */
class ToolCircuitBreakerMiddlewareTest {

    private static final String REPLY_ID = "reply-1";
    private static final String WEATHER = "query_weather";
    private static final String DATABASE = "query_database";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));

    // ==================== Reasoning: withholding tripped tools ====================

    @Test
    void trippedToolIsRemovedFromTheToolsOfferedToTheModel() {
        ToolCircuitBreakerMiddleware middleware = middleware(1);

        failTool(middleware, WEATHER);

        List<String> offered = offeredToolNames(middleware, WEATHER, DATABASE);
        assertEquals(List.of(DATABASE), offered);
    }

    @Test
    void healthyToolsArePassedThroughUntouched() {
        ToolCircuitBreakerMiddleware middleware = middleware(1);

        List<String> offered = offeredToolNames(middleware, WEATHER, DATABASE);
        assertEquals(List.of(WEATHER, DATABASE), offered);
    }

    @Test
    void unfilteredTurnForwardsTheOriginalInputWithoutCopying() {
        ToolCircuitBreakerMiddleware middleware = middleware(1);
        ReasoningInput input =
                new ReasoningInput(
                        List.of(userMsg("plan a trip")), List.of(schema(DATABASE)), null);
        AtomicReference<ReasoningInput> seen = new AtomicReference<>();

        middleware
                .onReasoning(
                        null,
                        null,
                        input,
                        received -> {
                            seen.set(received);
                            return Flux.empty();
                        })
                .collectList()
                .block();

        assertSame(input, seen.get());
    }

    @Test
    void filteringPreservesMessagesAndOptions() {
        ToolCircuitBreakerMiddleware middleware = middleware(1);
        failTool(middleware, WEATHER);

        Msg message = userMsg("what is the weather");
        GenerateOptions options = GenerateOptions.builder().build();
        ReasoningInput input =
                new ReasoningInput(
                        List.of(message), List.of(schema(WEATHER), schema(DATABASE)), options);
        AtomicReference<ReasoningInput> seen = new AtomicReference<>();

        middleware
                .onReasoning(
                        null,
                        null,
                        input,
                        received -> {
                            seen.set(received);
                            return Flux.empty();
                        })
                .collectList()
                .block();

        assertEquals(List.of(message), seen.get().messages());
        assertSame(options, seen.get().options());
        assertEquals(1, seen.get().tools().size());
        assertEquals(DATABASE, seen.get().tools().get(0).getName());
    }

    @Test
    void toolIsOfferedAgainOnceTheCooldownElapses() {
        ToolCircuitBreakerMiddleware middleware = middleware(1);
        failTool(middleware, WEATHER);
        assertEquals(List.of(DATABASE), offeredToolNames(middleware, WEATHER, DATABASE));

        clock.advance(Duration.ofSeconds(60));

        assertEquals(List.of(WEATHER, DATABASE), offeredToolNames(middleware, WEATHER, DATABASE));
    }

    @Test
    void onlyOneConcurrentReasoningTurnReceivesTheHalfOpenProbe() throws Exception {
        ToolCircuitBreakerMiddleware middleware = middleware(1);
        failTool(middleware, WEATHER);
        clock.advance(Duration.ofSeconds(60));

        int turns = 8;
        ExecutorService pool = Executors.newFixedThreadPool(turns);
        CountDownLatch ready = new CountDownLatch(turns);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch observed = new CountDownLatch(turns);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger offers = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < turns; i++) {
                futures.add(
                        pool.submit(
                                () -> {
                                    ready.countDown();
                                    await(start);
                                    middleware
                                            .onReasoning(
                                                    null,
                                                    RuntimeContext.empty(),
                                                    new ReasoningInput(
                                                            List.of(),
                                                            List.of(schema(WEATHER)),
                                                            null),
                                                    received -> {
                                                        if (!received.tools().isEmpty()) {
                                                            offers.incrementAndGet();
                                                        }
                                                        observed.countDown();
                                                        await(release);
                                                        return Flux.empty();
                                                    })
                                            .collectList()
                                            .block();
                                }));
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(observed.await(5, TimeUnit.SECONDS));
            release.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }

            assertEquals(1, offers.get());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void unsupervisedToolIsNeverWithheldHoweverOftenItFails() {
        ToolCircuitBreakerMiddleware middleware = middleware(1);

        for (int i = 0; i < 5; i++) {
            failTool(middleware, DATABASE);
        }

        assertEquals(List.of(WEATHER, DATABASE), offeredToolNames(middleware, WEATHER, DATABASE));
    }

    // ==================== Acting: counting outcomes ====================

    @Test
    void errorResultsTripTheCircuitOnceTheThresholdIsReached() {
        ToolCircuitBreakerMiddleware middleware = middleware(3);

        failTool(middleware, WEATHER);
        failTool(middleware, WEATHER);
        assertFalse(middleware.getBreaker().isWithheld(WEATHER));

        failTool(middleware, WEATHER);
        assertTrue(middleware.getBreaker().isWithheld(WEATHER));
    }

    @Test
    void deniedResultsDoNotCountAsDependencyFailures() {
        ToolCircuitBreakerMiddleware middleware = middleware(2);

        emit(middleware, new ToolResultEndEvent(REPLY_ID, "c1", WEATHER, ToolResultState.DENIED));
        emit(middleware, new ToolResultEndEvent(REPLY_ID, "c2", WEATHER, ToolResultState.DENIED));
        emit(middleware, new ToolResultEndEvent(REPLY_ID, "c3", WEATHER, ToolResultState.DENIED));

        assertEquals(ToolCircuitState.CLOSED, middleware.getBreaker().state(WEATHER));
    }

    @Test
    void interruptedAndRunningResultsDoNotCountAsDependencyFailures() {
        ToolCircuitBreakerMiddleware middleware = middleware(2);

        emit(
                middleware,
                new ToolResultEndEvent(REPLY_ID, "c1", WEATHER, ToolResultState.INTERRUPTED));
        emit(middleware, new ToolResultEndEvent(REPLY_ID, "c2", WEATHER, ToolResultState.RUNNING));
        emit(
                middleware,
                new ToolResultEndEvent(REPLY_ID, "c3", WEATHER, ToolResultState.INTERRUPTED));

        assertEquals(ToolCircuitState.CLOSED, middleware.getBreaker().state(WEATHER));
    }

    @Test
    void successResetsTheFailureStreak() {
        ToolCircuitBreakerMiddleware middleware = middleware(3);

        failTool(middleware, WEATHER);
        failTool(middleware, WEATHER);
        succeedTool(middleware, WEATHER);
        failTool(middleware, WEATHER);
        failTool(middleware, WEATHER);

        assertEquals(ToolCircuitState.CLOSED, middleware.getBreaker().state(WEATHER));
    }

    @Test
    void actingStreamIsForwardedUnchanged() {
        ToolCircuitBreakerMiddleware middleware = middleware(1);
        ToolResultEndEvent event =
                new ToolResultEndEvent(REPLY_ID, "c1", WEATHER, ToolResultState.ERROR);

        List<AgentEvent> forwarded =
                middleware
                        .onActing(
                                null, null, new ActingInput(List.of()), ignored -> Flux.just(event))
                        .collectList()
                        .block();

        assertEquals(1, forwarded.size());
        assertSame(event, forwarded.get(0));
    }

    @Test
    void probeFailureAfterCooldownExtendsTheWithholdingPeriod() {
        ToolCircuitBreakerMiddleware middleware = middleware(1);
        failTool(middleware, WEATHER);
        clock.advance(Duration.ofSeconds(60));

        // The half-open probe fails, so the second generation applies: 120s, not 60s.
        failTool(middleware, WEATHER);

        clock.advance(Duration.ofSeconds(60));
        assertEquals(List.of(DATABASE), offeredToolNames(middleware, WEATHER, DATABASE));

        clock.advance(Duration.ofSeconds(60));
        assertEquals(List.of(WEATHER, DATABASE), offeredToolNames(middleware, WEATHER, DATABASE));
    }

    @Test
    void aTurnThatNeverCallsTheToolHandsTheProbeBack() {
        ToolCircuitBreakerMiddleware middleware = middleware(1);
        failTool(middleware, WEATHER);
        clock.advance(Duration.ofSeconds(60));

        // The model is offered the tool but selects nothing, so the reasoning stream ends without a
        // ToolCallStartEvent.
        assertEquals(List.of(WEATHER), offeredToolNames(middleware, WEATHER));

        // Without the release the claim would still be live and this turn would be withheld.
        assertEquals(List.of(WEATHER), offeredToolNames(middleware, WEATHER));
    }

    @Test
    void aSelectedToolKeepsItsProbeAndItsResultClosesTheCircuit() {
        ToolCircuitBreakerMiddleware middleware = middleware(1);
        failTool(middleware, WEATHER);
        clock.advance(Duration.ofSeconds(60));
        RuntimeContext ctx = RuntimeContext.empty();

        // Reasoning advertises the tool and the model selects it.
        ToolCallStartEvent selection = new ToolCallStartEvent(REPLY_ID, "call-1", WEATHER);
        middleware
                .onReasoning(
                        null,
                        ctx,
                        new ReasoningInput(List.of(), List.of(schema(WEATHER)), null),
                        received -> Flux.just(selection))
                .collectList()
                .block();

        // The probe is bound to that tool-call id, so the matching result completes it.
        middleware
                .onActing(
                        null,
                        ctx,
                        new ActingInput(List.of()),
                        ignored ->
                                Flux.just(
                                        new ToolResultEndEvent(
                                                REPLY_ID,
                                                "call-1",
                                                WEATHER,
                                                ToolResultState.SUCCESS)))
                .collectList()
                .block();

        assertEquals(ToolCircuitState.CLOSED, middleware.getBreaker().state(WEATHER));
    }

    @Test
    void aDeniedProbeIsHandedBackInsteadOfCountingAsAFailure() {
        ToolCircuitBreakerMiddleware middleware = middleware(1);
        failTool(middleware, WEATHER);
        clock.advance(Duration.ofSeconds(60));
        RuntimeContext ctx = RuntimeContext.empty();
        middleware
                .onReasoning(
                        null,
                        ctx,
                        new ReasoningInput(List.of(), List.of(schema(WEATHER)), null),
                        received -> Flux.just(new ToolCallStartEvent(REPLY_ID, "call-1", WEATHER)))
                .collectList()
                .block();

        middleware
                .onActing(
                        null,
                        ctx,
                        new ActingInput(List.of()),
                        ignored ->
                                Flux.just(
                                        new ToolResultEndEvent(
                                                REPLY_ID,
                                                "call-1",
                                                WEATHER,
                                                ToolResultState.DENIED)))
                .collectList()
                .block();

        // The dependency was never tested: still half-open, and probing is possible again.
        assertEquals(ToolCircuitState.HALF_OPEN, middleware.getBreaker().state(WEATHER));
        assertEquals(List.of(WEATHER), offeredToolNames(middleware, WEATHER));
    }

    // ==================== Helpers ====================

    private ToolCircuitBreakerMiddleware middleware(int threshold) {
        ToolCircuitBreakerConfig config =
                ToolCircuitBreakerConfig.builder()
                        .monitorTools(WEATHER)
                        .failureThreshold(threshold)
                        .initialCooldown(Duration.ofSeconds(60))
                        .backoffMultiplier(2.0)
                        .maxCooldown(Duration.ofSeconds(600))
                        .build();
        return new ToolCircuitBreakerMiddleware(
                new ToolCircuitBreaker(config, new InMemoryToolCircuitBreakerStore(), clock));
    }

    private void failTool(ToolCircuitBreakerMiddleware middleware, String toolName) {
        emit(middleware, new ToolResultEndEvent(REPLY_ID, "call", toolName, ToolResultState.ERROR));
    }

    private void succeedTool(ToolCircuitBreakerMiddleware middleware, String toolName) {
        emit(
                middleware,
                new ToolResultEndEvent(REPLY_ID, "call", toolName, ToolResultState.SUCCESS));
    }

    private void emit(ToolCircuitBreakerMiddleware middleware, AgentEvent event) {
        middleware
                .onActing(null, null, new ActingInput(List.of()), ignored -> Flux.just(event))
                .collectList()
                .block();
    }

    private List<String> offeredToolNames(
            ToolCircuitBreakerMiddleware middleware, String... toolNames) {
        List<ToolSchema> schemas = new ArrayList<>();
        for (String toolName : toolNames) {
            schemas.add(schema(toolName));
        }
        AtomicReference<ReasoningInput> seen = new AtomicReference<>();
        middleware
                .onReasoning(
                        null,
                        null,
                        new ReasoningInput(List.of(), schemas, null),
                        received -> {
                            seen.set(received);
                            return Flux.empty();
                        })
                .collectList()
                .block();
        return seen.get().tools().stream().map(ToolSchema::getName).toList();
    }

    private static ToolSchema schema(String name) {
        return ToolSchema.builder().name(name).description(name).build();
    }

    private static Msg userMsg(String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent test phase");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating concurrent test", e);
        }
    }
}
