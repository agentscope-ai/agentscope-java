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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.MiddlewareChain;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ToolSchema;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** Exercises the documented ordering against real onion chains and reactive retries. */
class ToolCircuitBreakerMiddlewareOrderTest {

    private static final String TOOL = "lookup";
    private static final String HEALTHY = "healthy";
    private static final String REPLY_ID = "reply";
    private static final String CALL_ID = "call";
    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final InMemoryToolCircuitBreakerStore store = new InMemoryToolCircuitBreakerStore();

    @Test
    void schemaTransformationRunsBeforeCircuitFiltering() {
        ToolCircuitBreaker breaker = breaker(1);
        breaker.recordFailure(TOOL);
        ToolCircuitBreakerMiddleware circuit =
                new ToolCircuitBreakerMiddleware(breaker, "lookup", 1);
        MiddlewareBase schemaAdder =
                new MiddlewareBase() {
                    @Override
                    public int order() {
                        return 2;
                    }

                    @Override
                    public Flux<AgentEvent> onReasoning(
                            Agent agent,
                            RuntimeContext ctx,
                            ReasoningInput input,
                            Function<ReasoningInput, Flux<AgentEvent>> next) {
                        List<ToolSchema> schemas = new ArrayList<>(input.tools());
                        schemas.add(schema(TOOL));
                        return next.apply(
                                new ReasoningInput(input.messages(), schemas, input.options()));
                    }
                };
        AtomicReference<List<String>> offered = new AtomicReference<>();

        MiddlewareChain.build(
                        ordered(circuit, schemaAdder),
                        null,
                        RuntimeContext.empty(),
                        MiddlewareBase::onReasoning,
                        input -> {
                            offered.set(input.tools().stream().map(ToolSchema::getName).toList());
                            return Flux.empty();
                        })
                .apply(new ReasoningInput(List.of(), List.of(schema(HEALTHY)), null))
                .blockLast();

        assertEquals(List.of(HEALTHY), offered.get());
        assertEquals(ToolCircuitState.OPEN, breaker.state(TOOL));
    }

    @Test
    void reasoningRetryOutsideBreakerReleasesAndReacquiresProbe() {
        ToolCircuitBreaker breaker = breaker(1);
        breaker.recordFailure(TOOL);
        clock.advance(Duration.ofSeconds(60));
        RuntimeContext context = RuntimeContext.empty();
        ToolCircuitBreakerMiddleware circuit =
                new ToolCircuitBreakerMiddleware(breaker, "lookup", 1);
        MiddlewareBase retry =
                new MiddlewareBase() {
                    @Override
                    public int order() {
                        return 2;
                    }

                    @Override
                    public Flux<AgentEvent> onReasoning(
                            Agent agent,
                            RuntimeContext ctx,
                            ReasoningInput input,
                            Function<ReasoningInput, Flux<AgentEvent>> next) {
                        return Flux.defer(() -> next.apply(input)).retry(1);
                    }
                };
        AtomicInteger attempts = new AtomicInteger();
        List<String> tokens = new ArrayList<>();

        MiddlewareChain.build(
                        ordered(circuit, retry),
                        null,
                        context,
                        MiddlewareBase::onReasoning,
                        input -> {
                            assertEquals(
                                    List.of(TOOL),
                                    input.tools().stream().map(ToolSchema::getName).toList());
                            String token = store.snapshot(TOOL).probeToken();
                            assertTrue(store.snapshot(TOOL).hasActiveProbe(clock.millis()));
                            tokens.add(token);
                            ToolCallStartEvent selection =
                                    new ToolCallStartEvent(REPLY_ID, CALL_ID, TOOL);
                            return attempts.incrementAndGet() == 1
                                    ? Flux.concat(
                                            Flux.just(selection),
                                            Flux.error(new IllegalStateException("retry")))
                                    : Flux.just(selection);
                        })
                .apply(new ReasoningInput(List.of(), List.of(schema(TOOL)), null))
                .collectList()
                .block();

        assertEquals(2, attempts.get());
        assertNotEquals(tokens.get(0), tokens.get(1));
        assertEquals(tokens.get(1), store.snapshot(TOOL).probeToken());
        circuit.onActing(
                        null,
                        context,
                        new ActingInput(List.of()),
                        input ->
                                Flux.just(
                                        new ToolResultEndEvent(
                                                REPLY_ID, CALL_ID, TOOL, ToolResultState.SUCCESS)))
                .blockLast();
        assertEquals(ToolCircuitState.CLOSED, breaker.state(TOOL));
    }

    @Test
    void actingRetryAndCacheInsideBreakerExposeOneLogicalOutcome() {
        ToolCircuitBreaker breaker = breaker(3);
        ToolCircuitBreakerMiddleware circuit =
                new ToolCircuitBreakerMiddleware(breaker, "lookup", 1);
        AtomicReference<List<AgentEvent>> cached = new AtomicReference<>();
        MiddlewareBase retryAndCache =
                new MiddlewareBase() {
                    @Override
                    public int order() {
                        return 0;
                    }

                    @Override
                    public Flux<AgentEvent> onActing(
                            Agent agent,
                            RuntimeContext ctx,
                            ActingInput input,
                            Function<ActingInput, Flux<AgentEvent>> next) {
                        return Flux.defer(
                                () -> {
                                    if (cached.get() != null) {
                                        return Flux.fromIterable(cached.get());
                                    }
                                    return Flux.defer(() -> next.apply(input))
                                            .collectList()
                                            .retry(1)
                                            .doOnNext(cached::set)
                                            .flatMapMany(Flux::fromIterable);
                                });
                    }
                };
        AtomicInteger executions = new AtomicInteger();
        Function<ActingInput, Flux<AgentEvent>> chain =
                MiddlewareChain.build(
                        ordered(retryAndCache, circuit),
                        null,
                        RuntimeContext.empty(),
                        MiddlewareBase::onActing,
                        input -> {
                            ToolResultEndEvent failure =
                                    new ToolResultEndEvent(
                                            REPLY_ID, CALL_ID, TOOL, ToolResultState.ERROR);
                            return executions.incrementAndGet() == 1
                                    ? Flux.concat(
                                            Flux.just(failure),
                                            Flux.error(new IllegalStateException("retry")))
                                    : Flux.just(failure);
                        });

        chain.apply(new ActingInput(List.of())).blockLast();
        assertEquals(2, executions.get());
        assertEquals(1L, store.snapshot(TOOL).failureCount());
        assertFalse(breaker.isWithheld(TOOL));

        chain.apply(new ActingInput(List.of())).blockLast();
        assertEquals(2, executions.get(), "cache hit bypasses execution");
        assertEquals(2L, store.snapshot(TOOL).failureCount(), "cache hit remains observable");
        assertFalse(breaker.isWithheld(TOOL));
    }

    private ToolCircuitBreaker breaker(int threshold) {
        return new ToolCircuitBreaker(
                ToolCircuitBreakerConfig.builder()
                        .monitorTools(TOOL)
                        .failureThreshold(threshold)
                        .initialCooldown(Duration.ofSeconds(60))
                        .build(),
                store,
                clock);
    }

    private ToolSchema schema(String name) {
        return ToolSchema.builder().name(name).description(name).build();
    }

    private List<MiddlewareBase> ordered(MiddlewareBase... middlewares) {
        List<MiddlewareBase> ordered = new ArrayList<>(List.of(middlewares));
        ordered.sort(Comparator.comparingInt(MiddlewareBase::order).reversed());
        return ordered;
    }
}
