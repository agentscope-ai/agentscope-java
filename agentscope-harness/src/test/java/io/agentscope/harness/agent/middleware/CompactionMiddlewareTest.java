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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/** Verifies that compaction fallback does not swallow interrupts or rerun downstream reasoning. */
class CompactionMiddlewareTest {

    /** An ordinary compaction failure skips compaction and invokes original reasoning once. */
    @Test
    void ordinaryCompactionFailureFallsBackToOriginalInputOnce() {
        AtomicInteger nextCalls = new AtomicInteger();
        CompactionMiddleware middleware =
                new CompactionMiddleware(
                        null,
                        new SynchronousFailingSummaryModel(
                                new IllegalStateException("summary provider unavailable")),
                        fixedConfig());

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

    /** A normal summary failure is still best-effort and continues with a failed-summary message. */
    @Test
    void ordinarySummaryFailureContinuesWithCompactedInput() {
        AtomicInteger nextCalls = new AtomicInteger();
        CompactionMiddleware middleware =
                new CompactionMiddleware(
                        null,
                        new FailingSummaryModel(
                                new IllegalStateException("summary provider unavailable")),
                        fixedConfig());

        StepVerifier.create(
                        middleware.onReasoning(
                                agent(),
                                context("user", "session"),
                                input(),
                                next -> {
                                    nextCalls.incrementAndGet();
                                    assertEquals(2, next.messages().size());
                                    assertTrue(
                                            next.messages()
                                                    .get(0)
                                                    .getTextContent()
                                                    .contains("Summarization failed"));
                                    return Flux.empty();
                                }))
                .verifyComplete();

        assertEquals(1, nextCalls.get());
    }

    /** An interrupt during real compaction summarization must propagate without entering reasoning. */
    @Test
    void interruptedSummaryCompactionPropagatesWithoutCallingNext() {
        AtomicInteger nextCalls = new AtomicInteger();
        CompactionMiddleware middleware =
                new CompactionMiddleware(
                        null,
                        new FailingSummaryModel(
                                new InterruptedException("interrupted while compacting")),
                        fixedConfig());

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

    /** An async interrupt from real compaction summarization must not degrade to reasoning. */
    @Test
    void asynchronousInterruptedSummaryCompactionPropagatesWithoutCallingNext() {
        AtomicInteger nextCalls = new AtomicInteger();
        CompactionMiddleware middleware =
                new CompactionMiddleware(
                        null,
                        new AsyncFailingSummaryModel(
                                new InterruptedException("interrupted while compacting")),
                        fixedConfig());

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

    /** A user interrupt wrapped by Reactor or application exceptions must also propagate. */
    @Test
    void wrappedInterruptedCompactionPropagatesWithoutCallingNext() {
        AtomicInteger nextCalls = new AtomicInteger();
        CompactionMiddleware middleware =
                new CompactionMiddleware(
                        null,
                        new FailingSummaryModel(
                                new IllegalStateException(
                                        "compaction wrapper",
                                        new InterruptedException("interrupted while compacting"))),
                        fixedConfig());

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

    /** A cyclic exception cause chain must terminate and retain ordinary compaction fallback. */
    @Test
    void cyclicCompactionFailureCauseFallsBackWithoutLooping() {
        AtomicInteger nextCalls = new AtomicInteger();
        CompactionMiddleware middleware =
                new CompactionMiddleware(
                        null, new FailingSummaryModel(new CyclicCauseException()), fixedConfig());

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

    /** An interrupt from downstream reasoning must propagate without a fallback retry. */
    @Test
    void interruptedDownstreamPropagatesWithoutRetryingNext() {
        AtomicInteger nextCalls = new AtomicInteger();
        CompactionMiddleware middleware =
                new CompactionMiddleware(null, new SuccessfulSummaryModel(), fixedConfig());

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
                new CompactionMiddleware(
                        null, new PerSessionSummaryModel(compactedSessions), fixedConfig());

        Flux<AgentEvent> interrupted =
                middleware.onReasoning(
                        agent(),
                        context("user-a", "session-a"),
                        input("session-a previous", "session-a latest"),
                        next -> {
                            interruptedNextCalls.incrementAndGet();
                            return Flux.empty();
                        });
        Flux<AgentEvent> active =
                middleware.onReasoning(
                        agent(),
                        context("user-b", "session-b"),
                        input("session-b previous", "session-b latest"),
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
                new CompactionMiddleware(null, model, noCompactionConfig());
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
                new CompactionMiddleware(null, model, noCompactionConfig());
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
        assertFalse(
                activeReply.get(5, TimeUnit.SECONDS).getGenerateReason()
                        == GenerateReason.INTERRUPTED);
        assertEquals(2, model.callCount.get());
    }

    /** Creates stable configuration that enters the compaction branch. */
    private CompactionConfig fixedConfig() {
        return CompactionConfig.builder()
                .triggerTokens(1)
                .keepTokens(1)
                .flushBeforeCompact(false)
                .offloadBeforeCompact(false)
                .prune(null)
                .build();
    }

    /** Creates stable configuration that bypasses compaction for model-stream interrupt tests. */
    private CompactionConfig noCompactionConfig() {
        return CompactionConfig.builder()
                .triggerMessages(0)
                .triggerTokens(Integer.MAX_VALUE)
                .keepTokens(0)
                .build();
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
        return input("previous context", "latest request");
    }

    /** Creates input with enough conversation messages for compaction to cut a prefix. */
    private ReasoningInput input(String first, String second) {
        return new ReasoningInput(
                List.of(userMessage(first), userMessage(second)), List.of(), null);
    }

    /** Creates a minimal user message shared by single-session and concurrent-session tests. */
    private Msg userMessage(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }

    /** Throws while creating the summary publisher, exercising the outer compaction fallback. */
    private static final class SynchronousFailingSummaryModel extends ChatModelBase {
        private final RuntimeException error;

        private SynchronousFailingSummaryModel(RuntimeException error) {
            this.error = error;
        }

        @Override
        public String getModelName() {
            return "synchronous-failing-summary";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            throw error;
        }
    }

    /** Provides a summary model that emits one successful summary chunk. */
    private static final class SuccessfulSummaryModel extends ChatModelBase {

        @Override
        public String getModelName() {
            return "successful-summary";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("summary").build()))
                            .build());
        }
    }

    /** Provides a real summary-model failure for compaction boundary tests. */
    private static class FailingSummaryModel extends ChatModelBase {
        private final Throwable error;

        private FailingSummaryModel(Throwable error) {
            this.error = error;
        }

        @Override
        public String getModelName() {
            return "failing-summary";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.error(error);
        }
    }

    /** Provides an asynchronous summary-model failure for in-flight interrupt coverage. */
    private static final class AsyncFailingSummaryModel extends FailingSummaryModel {

        private AsyncFailingSummaryModel(Throwable error) {
            super(error);
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return super.doStream(messages, tools, options)
                    .delaySubscription(Duration.ofMillis(20));
        }
    }

    /** Fails one session's summary while allowing another to complete. */
    private static final class PerSessionSummaryModel extends ChatModelBase {
        private final Set<String> compactedSessions;

        private PerSessionSummaryModel(Set<String> compactedSessions) {
            this.compactedSessions = compactedSessions;
        }

        @Override
        public String getModelName() {
            return "per-session-summary";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            String rendered = messages.get(0).getTextContent();
            if (rendered.contains("session-a")) {
                compactedSessions.add("session-a");
                return Flux.error(new InterruptedException("interrupted session-a"));
            }
            compactedSessions.add("session-b");
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("summary").build()))
                            .build());
        }
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

    /** Supplies a malformed cause chain to verify cycle-safe interruption detection. */
    private static final class CyclicCauseException extends RuntimeException {

        @Override
        public synchronized Throwable getCause() {
            return this;
        }
    }
}
