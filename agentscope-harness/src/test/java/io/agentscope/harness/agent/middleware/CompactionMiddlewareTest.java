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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/** Verifies that compaction fallback does not swallow interrupts or rerun downstream reasoning. */
class CompactionMiddlewareTest {

    /** An ordinary compaction failure skips compaction and invokes original reasoning once. */
    @Test
    void ordinaryCompactionFailureFallsBackToOriginalInputOnce() {
        AtomicInteger nextCalls = new AtomicInteger();
        CompactionMiddleware middleware =
                middleware(
                        (ctx, messages, config, agentId, sessionId) ->
                                Mono.error(
                                        new IllegalStateException("summary provider unavailable")));

        StepVerifier.create(
                        middleware.onReasoning(
                                agent(),
                                context("user", "session"),
                                input(),
                                next -> {
                                    nextCalls.incrementAndGet();
                                    return Flux.empty();
                                }))
                .verifyComplete();

        assertEquals(1, nextCalls.get());
    }

    /** An interrupt during compaction must propagate without entering downstream reasoning. */
    @Test
    void interruptedCompactionPropagatesWithoutCallingNext() {
        AtomicInteger nextCalls = new AtomicInteger();
        CompactionMiddleware middleware =
                middleware(
                        (ctx, messages, config, agentId, sessionId) ->
                                Mono.error(
                                        new InterruptedException("interrupted while compacting")));

        StepVerifier.create(
                        middleware.onReasoning(
                                agent(),
                                context("user", "session"),
                                input(),
                                next -> {
                                    nextCalls.incrementAndGet();
                                    return Flux.empty();
                                }))
                .expectError(InterruptedException.class)
                .verify();

        assertEquals(0, nextCalls.get());
    }

    /** An interrupt received after asynchronous compaction starts must not degrade to reasoning. */
    @Test
    void interruptedInFlightCompactionPropagatesWithoutCallingNext() throws Exception {
        AtomicInteger nextCalls = new AtomicInteger();
        CountDownLatch compactionStarted = new CountDownLatch(1);
        Sinks.One<Optional<List<Msg>>> compactionResult = Sinks.one();
        CompactionMiddleware middleware =
                middleware(
                        (ctx, messages, config, agentId, sessionId) ->
                                compactionResult
                                        .asMono()
                                        .doOnSubscribe(ignored -> compactionStarted.countDown()));

        var result =
                middleware
                        .onReasoning(
                                agent(),
                                context("user", "session"),
                                input(),
                                next -> {
                                    nextCalls.incrementAndGet();
                                    return Flux.empty();
                                })
                        .subscribeOn(Schedulers.parallel())
                        .collectList()
                        .toFuture();
        assertTrue(compactionStarted.await(5, TimeUnit.SECONDS), "compaction should start");
        compactionResult.tryEmitError(new InterruptedException("interrupted while compacting"));

        ExecutionException error =
                assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
        assertTrue(error.getCause() instanceof InterruptedException);
        assertEquals(0, nextCalls.get());
    }

    /** A user interrupt wrapped by Reactor or application exceptions must also propagate. */
    @Test
    void wrappedInterruptedCompactionPropagatesWithoutCallingNext() {
        AtomicInteger nextCalls = new AtomicInteger();
        CompactionMiddleware middleware =
                middleware(
                        (ctx, messages, config, agentId, sessionId) ->
                                Mono.error(
                                        new IllegalStateException(
                                                "compaction wrapper",
                                                new InterruptedException(
                                                        "interrupted while compacting"))));

        StepVerifier.create(
                        middleware.onReasoning(
                                agent(),
                                context("user", "session"),
                                input(),
                                next -> {
                                    nextCalls.incrementAndGet();
                                    return Flux.empty();
                                }))
                .expectErrorMatches(
                        error ->
                                error instanceof IllegalStateException
                                        && error.getCause() instanceof InterruptedException)
                .verify();

        assertEquals(0, nextCalls.get());
    }

    /** An interrupt from downstream reasoning must propagate without a fallback retry. */
    @Test
    void interruptedDownstreamPropagatesWithoutRetryingNext() {
        AtomicInteger nextCalls = new AtomicInteger();
        CompactionMiddleware middleware =
                middleware(
                        (ctx, messages, config, agentId, sessionId) -> Mono.just(Optional.empty()));

        StepVerifier.create(
                        middleware.onReasoning(
                                agent(),
                                context("user", "session"),
                                input(),
                                next -> {
                                    nextCalls.incrementAndGet();
                                    return Flux.error(
                                            new InterruptedException(
                                                    "interrupted while reasoning"));
                                }))
                .expectError(InterruptedException.class)
                .verify();

        assertEquals(1, nextCalls.get());
    }

    /** An interrupted concurrent session must not prevent another session from entering reasoning. */
    @Test
    void concurrentSessionInterruptDoesNotAffectOtherSession() {
        AtomicInteger interruptedNextCalls = new AtomicInteger();
        AtomicInteger activeNextCalls = new AtomicInteger();
        Set<String> compactedSessions = ConcurrentHashMap.newKeySet();
        CompactionMiddleware middleware =
                middleware(
                        (ctx, messages, config, agentId, sessionId) ->
                                Mono.defer(
                                        () -> {
                                            compactedSessions.add(ctx.getSessionId());
                                            return "session-a".equals(ctx.getSessionId())
                                                    ? Mono.error(
                                                            new InterruptedException(
                                                                    "interrupted session-a"))
                                                    : Mono.just(Optional.empty());
                                        }));

        Flux<AgentEvent> interrupted =
                middleware.onReasoning(
                        agent(),
                        context("user-a", "session-a"),
                        input(),
                        next -> {
                            interruptedNextCalls.incrementAndGet();
                            return Flux.empty();
                        });
        Flux<AgentEvent> active =
                middleware.onReasoning(
                        agent(),
                        context("user-b", "session-b"),
                        input(),
                        next -> {
                            activeNextCalls.incrementAndGet();
                            return Flux.empty();
                        });

        Flux.mergeDelayError(
                        2,
                        interrupted.materialize().subscribeOn(Schedulers.parallel()),
                        active.materialize().subscribeOn(Schedulers.parallel()))
                .collectList()
                .block();

        assertEquals(Set.of("session-a", "session-b"), compactedSessions);
        assertEquals(0, interruptedNextCalls.get());
        assertEquals(1, activeNextCalls.get());
        assertTrue(compactedSessions.contains("session-b"));
    }

    /** An interrupt in a regular model stream must make one request and end as INTERRUPTED. */
    @Test
    void modelStreamInterruptProducesOneCallAndInterruptedTerminalReason() throws Exception {
        CountDownLatch subscribed = new CountDownLatch(1);
        CountingDelayedFirstChunkModel model = new CountingDelayedFirstChunkModel(subscribed);
        CompactionMiddleware middleware =
                new CompactionMiddleware(
                        null,
                        model,
                        fixedConfig(),
                        (ctx, messages, config, agentId, sessionId) -> Mono.just(Optional.empty()));
        ReActAgent agent =
                ReActAgent.builder()
                        .name("compaction-test-agent")
                        .sysPrompt("test")
                        .model(model)
                        .middleware(middleware)
                        .build();
        RuntimeContext context = context("user", "session");

        var reply =
                agent.call(
                                List.of(
                                        Msg.builder()
                                                .role(MsgRole.USER)
                                                .textContent("test")
                                                .build()),
                                context)
                        .subscribeOn(Schedulers.parallel())
                        .toFuture();
        assertTrue(subscribed.await(5, TimeUnit.SECONDS), "model stream should start");
        agent.interrupt(context);

        assertEquals(
                GenerateReason.INTERRUPTED, reply.get(5, TimeUnit.SECONDS).getGenerateReason());
        assertEquals(1, model.callCount.get());
    }

    /** Interrupting one concurrent user session must not cancel the other session's model stream. */
    @Test
    void interruptingOneSessionDoesNotCancelConcurrentSession() throws Exception {
        CountDownLatch subscribed = new CountDownLatch(2);
        CountingDelayedFirstChunkModel model = new CountingDelayedFirstChunkModel(subscribed);
        CompactionMiddleware middleware =
                new CompactionMiddleware(
                        null,
                        model,
                        fixedConfig(),
                        (ctx, messages, config, agentId, sessionId) -> Mono.just(Optional.empty()));
        ReActAgent agent =
                ReActAgent.builder()
                        .name("compaction-test-agent")
                        .sysPrompt("test")
                        .model(model)
                        .middleware(middleware)
                        .build();
        RuntimeContext interruptedContext = context("user-a", "session-a");
        RuntimeContext activeContext = context("user-b", "session-b");

        var interruptedReply =
                agent.call(List.of(userMessage("interrupt me")), interruptedContext)
                        .subscribeOn(Schedulers.parallel())
                        .toFuture();
        var activeReply =
                agent.call(List.of(userMessage("finish normally")), activeContext)
                        .subscribeOn(Schedulers.parallel())
                        .toFuture();
        assertTrue(subscribed.await(5, TimeUnit.SECONDS), "both model streams should start");
        agent.interrupt(interruptedContext);

        assertEquals(
                GenerateReason.INTERRUPTED,
                interruptedReply.get(5, TimeUnit.SECONDS).getGenerateReason());
        assertTrue(
                activeReply.get(5, TimeUnit.SECONDS).getGenerateReason()
                        != GenerateReason.INTERRUPTED);
        assertEquals(2, model.callCount.get());
    }

    /** Creates middleware with an explicit compaction executor, avoiding real model or filesystem dependencies. */
    private CompactionMiddleware middleware(CompactionMiddleware.CompactionExecutor executor) {
        return new CompactionMiddleware(null, mock(Model.class), fixedConfig(), executor);
    }

    /** Creates stable configuration that enters the compaction branch. */
    private CompactionConfig fixedConfig() {
        return CompactionConfig.builder().triggerTokens(1).keepTokens(1).build();
    }

    /** Creates the ReActAgent test double required by the middleware. */
    private ReActAgent agent() {
        ReActAgent agent = mock(ReActAgent.class);
        when(agent.getName()).thenReturn("compaction-test-agent");
        return agent;
    }

    /** Creates a runtime context isolated by user and session. */
    private RuntimeContext context(String userId, String sessionId) {
        return RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
    }

    /** Creates the minimal input containing one user message. */
    private ReasoningInput input() {
        return new ReasoningInput(List.of(userMessage("test")), List.of(), null);
    }

    /** Creates a minimal user message shared by single-session and concurrent-session tests. */
    private Msg userMessage(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }

    /** Provides a model interruptible before its first chunk to verify real ReAct stream request counts. */
    private static final class CountingDelayedFirstChunkModel extends ChatModelBase {
        private final CountDownLatch subscribed;
        private final AtomicInteger callCount = new AtomicInteger();

        private CountingDelayedFirstChunkModel(CountDownLatch subscribed) {
            this.subscribed = subscribed;
        }

        @Override
        public String getModelName() {
            return "counting-delayed-first-chunk";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.defer(
                    () -> {
                        callCount.incrementAndGet();
                        subscribed.countDown();
                        return Flux.just(
                                        ChatResponse.builder()
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("reply")
                                                                        .build()))
                                                .build())
                                .delaySubscription(Duration.ofMillis(200));
                    });
        }
    }
}
