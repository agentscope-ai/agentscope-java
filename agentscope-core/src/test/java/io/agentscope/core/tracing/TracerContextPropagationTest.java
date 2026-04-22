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
package io.agentscope.core.tracing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/**
 * Tests the propagation of Reactor's {@link Context} through reactive chains
 * and its integration
 * with the tracing infrastructure. This class verifies that context values are
 * correctly propagated
 * and accessible via a {@link ThreadLocal} when using a custom {@link Tracer}
 * implementation.
 * <p>
 * The {@code TestTracer} implementation demonstrates how a value from the
 * Reactor context
 * (specifically, the value associated with "test-key") can be transferred to a
 * {@code ThreadLocal}
 * for the duration of a reactive operation, simulating context propagation for
 * tracing purposes.
 * These tests ensure that the tracing context is properly managed and restored
 * across different
 * reactive operators such as {@code map} and {@code flatMap}.
 */
public class TracerContextPropagationTest {

    static final ThreadLocal<String> THREAD_LOCAL = new ThreadLocal<>();

    /**
     * Test implementation of the {@link Tracer} interface that propagates Reactor
     * context values
     * to a {@link ThreadLocal} variable for verification purposes in tests.
     * <p>
     * This allows test code to assert that context propagation works as expected by
     * checking
     * the value stored in the {@code THREAD_LOCAL} after Reactor operations.
     */
    static class TestTracer implements Tracer {
        @Override
        public <TResp> TResp runWithContext(ContextView reactorCtx, Supplier<TResp> inner) {
            String val = reactorCtx.getOrDefault("test-key", null);
            String old = THREAD_LOCAL.get();
            if (val != null) {
                THREAD_LOCAL.set(val);
            }
            try {
                return inner.get();
            } finally {
                if (old != null) {
                    THREAD_LOCAL.set(old);
                } else {
                    THREAD_LOCAL.remove();
                }
            }
        }
    }

    @BeforeAll
    public static void enableHook() {
        TracerRegistry.enableTracingHook();
    }

    @AfterAll
    public static void disableHook() {
        TracerRegistry.disableTracingHook();
    }

    @BeforeEach
    public void setup() {
        TracerRegistry.register(new TestTracer());
        THREAD_LOCAL.remove();
    }

    @Test
    public void testContextPropagationInMono() {
        AtomicReference<String> captured = new AtomicReference<>();

        Mono.just("hello")
                .map(
                        s -> {
                            captured.set(THREAD_LOCAL.get());
                            return s;
                        })
                .contextWrite(Context.of("test-key", "propagated-value"))
                .block();

        assertEquals("propagated-value", captured.get());
    }

    @Test
    public void testContextPropagationInFlatMap() {
        AtomicReference<String> captured = new AtomicReference<>();

        Mono.just("hello")
                .flatMap(
                        s ->
                                Mono.just(s)
                                        .map(
                                                v -> {
                                                    captured.set(THREAD_LOCAL.get());
                                                    return v;
                                                }))
                .contextWrite(Context.of("test-key", "propagated-value"))
                .block();

        assertEquals("propagated-value", captured.get());
    }
}
