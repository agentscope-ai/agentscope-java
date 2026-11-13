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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.middleware.CircuitBreakerMiddleware;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ToolSchema;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

class CircuitBreakerToolTest {
    private final AgentTool delegate = mock(AgentTool.class);
    private final MutableClock clock = new MutableClock();
    private final ToolCallParam param = ToolCallParam.builder().input(Map.of()).build();

    private CircuitBreakerTool tool(int threshold) {
        when(delegate.getName()).thenReturn("weather");
        return CircuitBreakerTool.builder(delegate)
                .failureThreshold(threshold)
                .initialCooldown(Duration.ofSeconds(1))
                .maxCooldown(Duration.ofSeconds(4))
                .clock(clock)
                .build();
    }

    @Test
    void successBreaksFailureStreakAndOpenCallsDoNotReachDelegate() {
        CircuitBreakerTool tool = tool(2);
        when(delegate.callAsync(param))
                .thenReturn(
                        Mono.just(ToolResultBlock.error("offline")),
                        Mono.just(ToolResultBlock.text("ok")),
                        Mono.just(ToolResultBlock.error("offline")));
        for (int i = 0; i < 3; i++) tool.callAsync(param).block();
        assertTrue(tool.isAvailable());
        tool.callAsync(param).block();
        assertFalse(tool.isAvailable());
        assertEquals(ToolResultState.ERROR, tool.callAsync(param).block().getState());
        verify(delegate, times(4)).callAsync(param);
    }

    @Test
    void cooldownBacksOffCapsAndResetsAfterRecovery() {
        CircuitBreakerTool tool = tool(1);
        when(delegate.callAsync(param)).thenReturn(Mono.just(ToolResultBlock.error("offline")));
        tool.callAsync(param).block();
        for (int seconds : new int[] {1, 2, 4, 4}) {
            clock.advance(seconds * 1000L - 1);
            assertFalse(tool.isAvailable());
            clock.advance(1);
            assertTrue(tool.isAvailable());
            tool.callAsync(param).block();
        }
        clock.advance(4000);
        when(delegate.callAsync(param)).thenReturn(Mono.just(ToolResultBlock.text("ok")));
        tool.callAsync(param).block();
        assertTrue(tool.isAvailable());
        when(delegate.callAsync(param)).thenReturn(Mono.just(ToolResultBlock.error("offline")));
        tool.callAsync(param).block();
        clock.advance(1000);
        assertTrue(tool.isAvailable());
    }

    @Test
    void onlyOneConcurrentProbeReachesDelegate() throws Exception {
        CircuitBreakerTool tool = tool(1);
        when(delegate.callAsync(param)).thenReturn(Mono.just(ToolResultBlock.error("offline")));
        tool.callAsync(param).block();
        clock.advance(1000);
        Sinks.One<ToolResultBlock> pending = Sinks.one();
        AtomicInteger admitted = new AtomicInteger();
        when(delegate.callAsync(param))
                .thenAnswer(
                        invocation -> {
                            admitted.incrementAndGet();
                            return pending.asMono();
                        });
        var pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var futures =
                    java.util.stream.IntStream.range(0, 8)
                            .mapToObj(
                                    i ->
                                            pool.submit(
                                                    () -> {
                                                        start.await();
                                                        return tool.callAsync(param).subscribe();
                                                    }))
                            .toList();
            start.countDown();
            var subscriptions = new java.util.ArrayList<reactor.core.Disposable>();
            for (var future : futures)
                subscriptions.add(future.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, admitted.get());
            assertFalse(tool.isAvailable());
            pending.tryEmitValue(ToolResultBlock.text("recovered"));
            assertTrue(tool.isAvailable());
            subscriptions.forEach(reactor.core.Disposable::dispose);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void staleSuccessCannotCloseANewerCircuit() {
        CircuitBreakerTool tool = tool(1);
        Sinks.One<ToolResultBlock> old = Sinks.one();
        when(delegate.callAsync(param))
                .thenReturn(old.asMono(), Mono.just(ToolResultBlock.error("offline")));
        tool.callAsync(param).subscribe();
        tool.callAsync(param).block();
        old.tryEmitValue(ToolResultBlock.text("late success"));
        assertFalse(tool.isAvailable());
    }

    @Test
    void cancellationEmptyAndNonTerminalResultsReleaseProbe() {
        CircuitBreakerTool tool = tool(1);
        when(delegate.callAsync(param)).thenReturn(Mono.just(ToolResultBlock.error("offline")));
        tool.callAsync(param).block();
        clock.advance(1000);
        when(delegate.callAsync(param)).thenReturn(Mono.never());
        var subscription = tool.callAsync(param).subscribe();
        assertFalse(tool.isAvailable());
        subscription.dispose();
        assertTrue(tool.isAvailable());
        when(delegate.callAsync(param)).thenReturn(Mono.empty());
        tool.callAsync(param).block();
        assertTrue(tool.isAvailable());
        for (ToolResultState state : List.of(ToolResultState.DENIED, ToolResultState.INTERRUPTED)) {
            when(delegate.callAsync(param))
                    .thenReturn(Mono.just(ToolResultBlock.text("ignored").withState(state)));
            tool.callAsync(param).block();
            assertTrue(tool.isAvailable());
        }
    }

    @Test
    void bothSynchronousAndReactiveExceptionsCount() {
        CircuitBreakerTool tool = tool(2);
        when(delegate.callAsync(param))
                .thenThrow(new IllegalStateException("sync"))
                .thenReturn(Mono.error(new IllegalStateException("async")));
        assertThrows(IllegalStateException.class, () -> tool.callAsync(param).block());
        assertTrue(tool.isAvailable());
        assertThrows(IllegalStateException.class, () -> tool.callAsync(param).block());
        assertFalse(tool.isAvailable());
    }

    @Test
    void customPredicateClassifiesBusinessFailure() {
        CircuitBreakerTool tool =
                CircuitBreakerTool.builder(delegate)
                        .failureThreshold(1)
                        .failurePredicate(result -> true)
                        .build();
        when(delegate.callAsync(param))
                .thenReturn(Mono.just(ToolResultBlock.text("business failure")));
        tool.callAsync(param).block();
        assertFalse(tool.isAvailable());
    }

    @Test
    void schemasAndPermissionAttributesArePreserved() {
        CircuitBreakerTool tool = tool(1);
        when(delegate.getDescription()).thenReturn("lookup");
        when(delegate.getParameters()).thenReturn(Map.of("type", "object"));
        when(delegate.getOutputSchema()).thenReturn(Map.of("type", "string"));
        when(delegate.getStrict()).thenReturn(true);
        when(delegate.isReadOnly()).thenReturn(true);
        assertEquals("weather", tool.getName());
        assertEquals("lookup", tool.getDescription());
        assertEquals(delegate.getParameters(), tool.getParameters());
        assertEquals(delegate.getOutputSchema(), tool.getOutputSchema());
        assertTrue(tool.getStrict());
        assertTrue(tool.isReadOnly());
    }

    @Test
    void filteringIsLazyAndNeverMutatesToolkitOrOriginalRequest() {
        CircuitBreakerTool tool = tool(1);
        Toolkit toolkit = new Toolkit();
        when(delegate.getDescription()).thenReturn("lookup");
        when(delegate.getParameters()).thenReturn(Map.of());
        toolkit.registerAgentTool(tool);
        var schemas =
                List.of(
                        ToolSchema.builder().name("weather").description("lookup").build(),
                        ToolSchema.builder().name("other").description("other").build());
        var input = new ReasoningInput(List.of(), schemas, null);
        var middleware = new CircuitBreakerMiddleware(List.of(tool));
        AtomicReference<ReasoningInput> seen = new AtomicReference<>();
        var stream =
                middleware.onReasoning(
                        null,
                        null,
                        input,
                        value -> {
                            seen.set(value);
                            return Flux.empty();
                        });
        when(delegate.callAsync(param)).thenReturn(Mono.just(ToolResultBlock.error("offline")));
        tool.callAsync(param).block();
        stream.blockLast();
        assertEquals(
                List.of("other"), seen.get().tools().stream().map(ToolSchema::getName).toList());
        assertSame(input.messages(), seen.get().messages());
        assertEquals(2, schemas.size());
        assertSame(tool, toolkit.getTool("weather"));
        clock.advance(1000);
        stream.blockLast();
        assertEquals(schemas, seen.get().tools());
        // A model turn that does not call the tool never claims a probe.
        assertTrue(tool.isAvailable());
    }

    @Test
    void validatesConfigurationAndDuplicateNames() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CircuitBreakerTool.builder(delegate).failureThreshold(0).build());
        assertThrows(
                IllegalArgumentException.class,
                () -> CircuitBreakerTool.builder(delegate).initialCooldown(Duration.ZERO).build());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CircuitBreakerTool.builder(delegate)
                                .maxCooldown(Duration.ofSeconds(1))
                                .build());
        assertThrows(
                IllegalArgumentException.class,
                () -> CircuitBreakerTool.builder(delegate).backoffMultiplier(Double.NaN).build());
        CircuitBreakerTool tool = tool(1);
        assertThrows(
                IllegalArgumentException.class,
                () -> new CircuitBreakerMiddleware(List.of(tool, tool)));
    }

    @Test
    void suspendedCallsAreNotDependencyFailures() {
        CircuitBreakerTool tool = tool(1);
        when(delegate.callAsync(param)).thenThrow(new ToolSuspendException());
        assertThrows(ToolSuspendException.class, () -> tool.callAsync(param).block());
        assertTrue(tool.isAvailable());
        var use =
                io.agentscope.core.message.ToolUseBlock.builder()
                        .id("call")
                        .name("weather")
                        .input(Map.of())
                        .build();
        doReturn(Mono.just(ToolResultBlock.suspended(use))).when(delegate).callAsync(param);
        tool.callAsync(param).block();
        assertTrue(tool.isAvailable());
    }

    @Test
    void retryingAnOpenDecoratorDoesNotRetryTheDependency() {
        CircuitBreakerTool tool = tool(1);
        when(delegate.callAsync(param))
                .thenReturn(Mono.error(new IllegalStateException("offline")));
        ToolResultBlock result = tool.callAsync(param).retry(3).block();
        assertEquals(ToolResultState.ERROR, result.getState());
        verify(delegate, times(1)).callAsync(param);
    }

    @Test
    void staleFailureDoesNotIncreaseCooldownAfterRecovery() {
        CircuitBreakerTool tool = tool(1);
        Sinks.One<ToolResultBlock> old = Sinks.one();
        when(delegate.callAsync(param))
                .thenReturn(old.asMono(), Mono.just(ToolResultBlock.error("offline")));
        tool.callAsync(param).subscribe();
        tool.callAsync(param).block();
        clock.advance(1000);
        when(delegate.callAsync(param)).thenReturn(Mono.just(ToolResultBlock.text("ok")));
        tool.callAsync(param).block();
        old.tryEmitValue(ToolResultBlock.error("late failure"));
        assertTrue(tool.isAvailable());
    }

    private static class MutableClock extends Clock {
        private Instant now = Instant.EPOCH;

        void advance(long millis) {
            now = now.plusMillis(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
