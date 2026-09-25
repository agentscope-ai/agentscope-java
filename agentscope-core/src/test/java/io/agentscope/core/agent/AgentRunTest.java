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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.MiddlewareBase;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

class AgentRunTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void cancelledBeforeSubscriptionDoesNotInvokeSourceAndCannotBeRestarted() {
        AtomicInteger subscriptions = new AtomicInteger();
        AgentRun<String> run =
                AgentRun.create(
                        "a",
                        () -> {
                            subscriptions.incrementAndGet();
                            return Mono.just("unexpected");
                        });
        assertEquals(AgentRun.Status.CREATED, run.status());
        assertTrue(run.cancel());
        assertFalse(run.cancel());
        StepVerifier.create(run.stream()).expectError(CancellationException.class).verify(TIMEOUT);
        StepVerifier.create(run.stream()).expectError(IllegalStateException.class).verify(TIMEOUT);
        assertEquals(0, subscriptions.get());
        assertEquals(AgentRun.Status.CANCELLED, run.termination().block(TIMEOUT));
    }

    @Test
    void downstreamCancellationAndFailureAreTerminal() {
        AgentRun<String> cancelled = AgentRun.create("a", Flux::never);
        var subscription = cancelled.stream().subscribe();
        subscription.dispose();
        assertEquals(AgentRun.Status.CANCELLED, cancelled.termination().block(TIMEOUT));
        AgentRun<String> failed =
                AgentRun.create(
                        "a",
                        () -> {
                            throw new IllegalArgumentException("bad");
                        });
        StepVerifier.create(failed.stream())
                .expectError(IllegalArgumentException.class)
                .verify(TIMEOUT);
        assertEquals(AgentRun.Status.FAILED, failed.status());
        assertFalse(failed.cancel());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellingQueuedBDoesNotInterruptAOrAdmitCEarly(boolean cooperative) {
        Gates gates = new Gates();
        try (ReActAgent agent = agent(gates)) {
            AgentRun<Msg> a = agent.prepareCall(input(), context("a", "same"));
            AgentRun<Msg> b = agent.prepareCall(input(), context("b", "same"));
            AgentRun<Msg> c = agent.prepareCall(input(), context("c", "same"));
            var ar =
                    a.stream()
                            .single()
                            .toFuture()
                            .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS);
            var br =
                    b.stream()
                            .single()
                            .toFuture()
                            .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS);
            var cr =
                    c.stream()
                            .single()
                            .toFuture()
                            .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS);
            try {
                assertEquals(AgentRun.Status.RUNNING, a.status());
                assertEquals(AgentRun.Status.QUEUED, b.status());
                assertTrue(cooperative ? b.interrupt() : b.cancel());
                assertTrue(br.isCompletedExceptionally());
                assertEquals(AgentRun.Status.RUNNING, a.status());
                assertEquals(AgentRun.Status.QUEUED, c.status());
                assertFalse(gates.entered.containsKey("b"));
                assertFalse(gates.entered.containsKey("c"));
                gates.release("a");
                assertEquals(AgentRun.Status.COMPLETED, a.status());
                assertNotNull(ar.join());
                assertEquals(AgentRun.Status.RUNNING, c.status());
                gates.release("c");
                assertNotNull(cr.join());
                assertEquals(AgentRun.Status.COMPLETED, c.status());
                assertFalse(a.cancel());
            } finally {
                a.cancel();
                b.cancel();
                c.cancel();
            }
        }
    }

    @Test
    void cancellingActiveRunAllowsNextAndCannotCancelReplacement() {
        Gates gates = new Gates();
        try (ReActAgent agent = agent(gates)) {
            AgentRun<Msg> a = agent.prepareCall(input(), context("a", "same"));
            AgentRun<Msg> b = agent.prepareCall(input(), context("b", "same"));
            var ar =
                    a.stream()
                            .single()
                            .toFuture()
                            .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS);
            var br =
                    b.stream()
                            .single()
                            .toFuture()
                            .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS);
            try {
                assertTrue(a.cancel());
                assertTrue(ar.isCompletedExceptionally());
                assertEquals(AgentRun.Status.RUNNING, b.status());
                assertFalse(a.interrupt());
                gates.release("b");
                assertNotNull(br.join());
                assertEquals(AgentRun.Status.COMPLETED, b.status());
            } finally {
                a.cancel();
                b.cancel();
            }
        }
    }

    @Test
    void cooperativeInterruptIsOwnedByOneRunAndNextRunStartsClean() {
        Gates gates = new Gates();
        try (ReActAgent agent = agent(gates)) {
            AgentRun<Msg> a = agent.prepareCall(input(), context("a", "same"));
            AgentRun<Msg> b = agent.prepareCall(input(), context("b", "same"));
            var ar =
                    a.stream()
                            .single()
                            .toFuture()
                            .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS);
            var br =
                    b.stream()
                            .single()
                            .toFuture()
                            .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS);
            try {
                assertTrue(a.interrupt(new UserMessage("stop")));
                gates.release("a");
                assertEquals(GenerateReason.INTERRUPTED, ar.join().getGenerateReason());
                gates.release("b");
                assertFalse(br.join().getGenerateReason() == GenerateReason.INTERRUPTED);
            } finally {
                a.cancel();
                b.cancel();
            }
        }
    }

    @Test
    void sessionInterruptTargetsRunningCallAndOtherSessionIsIndependent() {
        Gates gates = new Gates();
        try (ReActAgent agent = agent(gates)) {
            AgentRun<Msg> a = agent.prepareCall(input(), context("a", "one"));
            AgentRun<Msg> b = agent.prepareCall(input(), context("b", "two"));
            var ar =
                    a.stream()
                            .single()
                            .toFuture()
                            .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS);
            var br =
                    b.stream()
                            .single()
                            .toFuture()
                            .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS);
            try {
                agent.interrupt("u", "one");
                gates.release("a");
                gates.release("b");
                assertEquals(GenerateReason.INTERRUPTED, ar.join().getGenerateReason());
                assertFalse(br.join().getGenerateReason() == GenerateReason.INTERRUPTED);
            } finally {
                a.cancel();
                b.cancel();
            }
        }
    }

    @Test
    void retryWithinOneInvocationKeepsTheHandleAndSessionRouting() {
        AtomicInteger attempts = new AtomicInteger();
        Sinks.One<String> resumed = Sinks.one();
        MiddlewareBase retry =
                new MiddlewareBase() {
                    @Override
                    public Flux<io.agentscope.core.event.AgentEvent> onAgent(
                            Agent agent,
                            RuntimeContext ctx,
                            io.agentscope.core.middleware.AgentInput input,
                            java.util.function.Function<
                                            io.agentscope.core.middleware.AgentInput,
                                            Flux<io.agentscope.core.event.AgentEvent>>
                                    next) {
                        return next.apply(input).retry(1);
                    }

                    @Override
                    public Mono<String> onSystemPrompt(
                            Agent agent, RuntimeContext ctx, String prompt) {
                        return attempts.incrementAndGet() == 1
                                ? Mono.error(new IllegalStateException("retry"))
                                : resumed.asMono();
                    }
                };
        try (ReActAgent agent =
                ReActAgent.builder()
                        .name("retry")
                        .model(new MockModel("done"))
                        .middlewares(List.of(retry))
                        .build()) {
            AgentRun<Msg> run = agent.prepareCall(input(), context("a", "same"));
            var reply =
                    run.stream()
                            .single()
                            .toFuture()
                            .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS);
            try {
                assertEquals(2, attempts.get());
                assertEquals(AgentRun.Status.RUNNING, run.status());
                agent.interrupt("u", "same");
                resumed.tryEmitValue("system");
                assertEquals(GenerateReason.INTERRUPTED, reply.join().getGenerateReason());
                assertEquals(AgentRun.Status.COMPLETED, run.status());
            } finally {
                run.cancel();
            }
        }
    }

    @Test
    void nestedCallDoesNotInheritTheParentsCooperativeSignal() {
        Sinks.One<String> childGate = Sinks.one();
        AtomicReference<Msg> childReply = new AtomicReference<>();
        MiddlewareBase nested =
                new MiddlewareBase() {
                    @Override
                    public Mono<String> onSystemPrompt(
                            Agent agent, RuntimeContext ctx, String prompt) {
                        if (ctx.get(Label.class).value().equals("parent")) {
                            return ((ReActAgent) agent)
                                    .call(input(), context("child", "child-session"))
                                    .doOnNext(childReply::set)
                                    .thenReturn("system");
                        }
                        return childGate.asMono();
                    }
                };
        try (ReActAgent agent =
                ReActAgent.builder()
                        .name("nested")
                        .model(new MockModel("done"))
                        .middlewares(List.of(nested))
                        .build()) {
            AgentRun<Msg> run = agent.prepareCall(input(), context("parent", "parent-session"));
            var reply =
                    run.stream()
                            .single()
                            .toFuture()
                            .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS);
            try {
                assertTrue(run.interrupt());
                childGate.tryEmitValue("system");
                assertEquals(GenerateReason.INTERRUPTED, reply.join().getGenerateReason());
                assertNotNull(childReply.get());
                assertFalse(childReply.get().getGenerateReason() == GenerateReason.INTERRUPTED);
            } finally {
                run.cancel();
            }
        }
    }

    private record Label(String value) {}

    private static RuntimeContext context(String label, String session) {
        return RuntimeContext.builder()
                .userId("u")
                .sessionId(session)
                .put(Label.class, new Label(label))
                .build();
    }

    private static List<Msg> input() {
        return List.of(new UserMessage("hello"));
    }

    private static ReActAgent agent(Gates gates) {
        return ReActAgent.builder()
                .name("runs")
                .model(new MockModel("done"))
                .middlewares(List.of(gates))
                .build();
    }

    private static final class Gates implements MiddlewareBase {
        private final ConcurrentHashMap<String, Sinks.One<String>> entered =
                new ConcurrentHashMap<>();

        @Override
        public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String prompt) {
            Sinks.One<String> gate = Sinks.one();
            entered.put(ctx.get(Label.class).value(), gate);
            return gate.asMono();
        }

        void release(String label) {
            assertEquals(Sinks.EmitResult.OK, entered.get(label).tryEmitValue("system"));
        }
    }
}
