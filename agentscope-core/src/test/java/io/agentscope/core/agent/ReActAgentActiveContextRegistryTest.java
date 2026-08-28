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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.agent.test.TestConstants;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.hook.RuntimeContextAware;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Verifies that {@link ReActAgent} tracks active per-call {@link RuntimeContext}s in a
 * runId-indexed registry, so concurrent calls on different sessions no longer overwrite each
 * other's context.
 */
@DisplayName("ReActAgent active-context registry")
@SuppressWarnings("deprecation")
class ReActAgentActiveContextRegistryTest {

    @Test
    @DisplayName("getRuntimeContext(runId) returns null after the call completes")
    void runtimeContextRemovedAfterCall() throws Exception {
        MockModel model = new MockModel("done");
        ReActAgent agent = buildAgent(model, null);

        RuntimeContext rc = RuntimeContext.builder().userId("u1").sessionId("s1").build();
        String runId = rc.getRunId();

        // Before the call, no active context for this runId.
        assertNull(agent.getRuntimeContext(runId));

        Msg result = agent.call(List.of(TestUtils.createUserMessage("user", "hi")), rc).block();

        assertNotNull(result);
        // After the call completes, the runId slot is removed.
        awaitRegistryDrained(agent, 5, TimeUnit.SECONDS);
        assertNull(agent.getRuntimeContext(runId));
    }

    @Test
    @DisplayName("getActiveRuntimeContexts() is empty when no call is in flight")
    void activeContextsEmptyWhenIdle() {
        ReActAgent agent = buildAgent(new MockModel("ok"), null);
        assertTrue(agent.getActiveRuntimeContexts().isEmpty());
        assertNull(agent.getRuntimeContext());
    }

    @Test
    @DisplayName("no-arg getRuntimeContext() returns the sole in-flight context")
    void noArgGetRuntimeContextReturnsSoleContext() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Hook blockingHook =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof PreReasoningEvent) {
                            entered.countDown();
                            return Mono.<T>fromRunnable(
                                            () -> {
                                                try {
                                                    release.await(10, TimeUnit.SECONDS);
                                                } catch (InterruptedException e) {
                                                    Thread.currentThread().interrupt();
                                                }
                                            })
                                    .then(Mono.just(event));
                        }
                        return Mono.just(event);
                    }
                };
        ReActAgent agent = buildAgent(new MockModel("done"), blockingHook);
        RuntimeContext rc = RuntimeContext.builder().userId("u").sessionId("s-sole").build();

        agent.call(List.of(TestUtils.createUserMessage("u", "hi")), rc)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
        assertTrue(entered.await(30, TimeUnit.SECONDS));
        assertEquals(rc, agent.getRuntimeContext());

        release.countDown();
        awaitRegistryDrained(agent, 5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("concurrent calls on different sessions do not overwrite each other")
    void concurrentCallsIsolated() throws Exception {
        CountDownLatch enteredReasoning = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        Hook blockingHook =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof PreReasoningEvent) {
                            enteredReasoning.countDown();
                            return Mono.<T>fromRunnable(
                                            () -> {
                                                try {
                                                    assertTrue(release.await(10, TimeUnit.SECONDS));
                                                } catch (InterruptedException e) {
                                                    Thread.currentThread().interrupt();
                                                }
                                            })
                                    .then(Mono.just(event));
                        }
                        return Mono.just(event);
                    }
                };

        MockModel model = new MockModel("done");
        ReActAgent agent = buildAgent(model, blockingHook);

        RuntimeContext rcAlice =
                RuntimeContext.builder().userId("alice").sessionId("alice-session").build();
        RuntimeContext rcBob =
                RuntimeContext.builder().userId("bob").sessionId("bob-session").build();
        String runIdAlice = rcAlice.getRunId();
        String runIdBob = rcBob.getRunId();

        // Fire both calls on separate threads so they run concurrently (different slots).
        Mono<Msg> callA =
                agent.call(List.of(TestUtils.createUserMessage("alice", "hi")), rcAlice)
                        .subscribeOn(Schedulers.boundedElastic());
        Mono<Msg> callB =
                agent.call(List.of(TestUtils.createUserMessage("bob", "hi")), rcBob)
                        .subscribeOn(Schedulers.boundedElastic());

        CountDownLatch doneA = new CountDownLatch(1);
        CountDownLatch doneB = new CountDownLatch(1);
        AtomicReference<Msg> resultA = new AtomicReference<>();
        AtomicReference<Msg> resultB = new AtomicReference<>();
        AtomicReference<Throwable> errorA = new AtomicReference<>();
        AtomicReference<Throwable> errorB = new AtomicReference<>();

        Disposable subA =
                callA.subscribe(
                        msg -> {
                            resultA.set(msg);
                            doneA.countDown();
                        },
                        e -> {
                            errorA.set(e);
                            doneA.countDown();
                        });
        Disposable subB =
                callB.subscribe(
                        msg -> {
                            resultB.set(msg);
                            doneB.countDown();
                        },
                        e -> {
                            errorB.set(e);
                            doneB.countDown();
                        });

        // Wait until both calls have entered the reasoning phase (RCs registered).
        assertTrue(enteredReasoning.await(30, TimeUnit.SECONDS));

        // Both calls in-flight: registry holds exactly the two active contexts.
        assertEquals(2, agent.getActiveRuntimeContexts().size());
        // The no-arg accessor is ambiguous with several in-flight calls.
        assertThrows(IllegalStateException.class, agent::getRuntimeContext);
        // Each runId resolves to the correct session — no cross-contamination.
        assertEquals("alice-session", agent.getRuntimeContext(runIdAlice).getSessionId());
        assertEquals("bob-session", agent.getRuntimeContext(runIdBob).getSessionId());

        // Release both calls and wait for completion.
        release.countDown();
        assertTrue(doneA.await(30, TimeUnit.SECONDS));
        assertTrue(doneB.await(30, TimeUnit.SECONDS));
        if (errorA.get() != null) {
            throw new AssertionError("callA failed", errorA.get());
        }
        if (errorB.get() != null) {
            throw new AssertionError("callB failed", errorB.get());
        }
        assertNotNull(resultA.get());
        assertNotNull(resultB.get());

        // Cleanup fires after the terminal signal propagates downstream; poll for the drain.
        awaitRegistryDrained(agent, 5, TimeUnit.SECONDS);
        assertNull(agent.getRuntimeContext(runIdAlice));
        assertNull(agent.getRuntimeContext(runIdBob));
        assertTrue(agent.getActiveRuntimeContexts().isEmpty());
    }

    @Test
    @DisplayName(
            "deprecated stream(..., RuntimeContext) keeps userId/sessionId (Reactor Context path)")
    @SuppressWarnings("deprecation")
    void legacyStreamOverloadKeepsSessionIsolation() throws Exception {
        MockModel model = new MockModel("done");
        ReActAgent agent = buildAgent(model, null);

        RuntimeContext rc =
                RuntimeContext.builder().userId("stream-user").sessionId("stream-session").build();

        // The deprecated overload carries the RC only via the Reactor Context.
        List<Event> events =
                agent.stream(
                                List.of(TestUtils.createUserMessage("user", "hi")),
                                StreamOptions.defaults(),
                                rc)
                        .collectList()
                        .block(java.time.Duration.ofSeconds(30));

        assertNotNull(events);
        assertFalse(events.isEmpty(), "legacy stream should emit at least the result event");

        // The conversation landed in the RC's session, not the agent default session.
        List<Msg> streamedSession =
                agent.getAgentState("stream-user", "stream-session").getContext();
        assertTrue(
                streamedSession.stream()
                        .anyMatch(m -> "hi".equals(TestUtils.extractTextContent(m))),
                "message must be persisted to the RC session 'stream-session'");

        List<Msg> defaultSession =
                agent.getAgentState(null, agent.getDefaultSessionId()).getContext();
        assertTrue(
                defaultSession.stream()
                        .noneMatch(m -> "hi".equals(TestUtils.extractTextContent(m))),
                "message must NOT leak into the default session");

        awaitRegistryDrained(agent, 5, TimeUnit.SECONDS);
        assertTrue(agent.getActiveRuntimeContexts().isEmpty());
    }

    @Test
    @DisplayName("normal, native structured, and fallback structured paths drop no errors")
    void noDroppedErrorsOnCallPaths() throws Exception {
        List<Throwable> dropped = new java.util.concurrent.CopyOnWriteArrayList<>();
        Hooks.onErrorDropped(dropped::add);
        try {
            // Normal path.
            ReActAgent plain = buildAgent(new MockModel("done"), null);
            RuntimeContext rcPlain =
                    RuntimeContext.builder().userId("u1").sessionId("s-normal").build();
            assertNotNull(
                    plain.call(List.of(TestUtils.createUserMessage("user", "hi")), rcPlain)
                            .block());
            awaitRegistryDrained(plain, 5, TimeUnit.SECONDS);

            // Fallback structured path (MockModel does not support native structured output).
            ReActAgent fallback = buildAgent(new MockModel("{\"value\":\"ok\"}"), null);
            RuntimeContext rcFallback =
                    RuntimeContext.builder().userId("u2").sessionId("s-fallback").build();
            assertNotNull(
                    fallback.call(
                                    List.of(TestUtils.createUserMessage("user", "hi")),
                                    SimplePojo.class,
                                    rcFallback)
                            .block());
            awaitRegistryDrained(fallback, 5, TimeUnit.SECONDS);

            // Native structured path (model claims native support and returns JSON text).
            ReActAgent nativeAgent = buildAgent(nativeJsonModel(), null);
            RuntimeContext rcNative =
                    RuntimeContext.builder().userId("u3").sessionId("s-native").build();
            assertNotNull(
                    nativeAgent
                            .call(
                                    List.of(TestUtils.createUserMessage("user", "hi")),
                                    SimplePojo.class,
                                    rcNative)
                            .block());
            awaitRegistryDrained(nativeAgent, 5, TimeUnit.SECONDS);
        } finally {
            Hooks.resetOnErrorDropped();
        }
        assertTrue(
                dropped.isEmpty(),
                "no dropped errors expected (got: "
                        + dropped.stream().map(Object::toString).toList()
                        + ")");
    }

    @Test
    @DisplayName("call failure still removes the active context")
    void errorPathRemovesActiveContext() throws Exception {
        MockModel model = new MockModel("unused");
        model.withError("boom");
        ReActAgent agent = buildAgent(model, null);

        RuntimeContext rc =
                RuntimeContext.builder().userId("err-user").sessionId("err-session").build();

        Throwable error = null;
        try {
            agent.call(List.of(TestUtils.createUserMessage("user", "hi")), rc).block();
        } catch (Throwable t) {
            error = t;
        }
        assertNotNull(error, "call must fail when the model errors");

        awaitRegistryDrained(agent, 5, TimeUnit.SECONDS);
        assertNull(agent.getRuntimeContext(rc.getRunId()));
    }

    @Test
    @DisplayName("cancelling the subscription still removes the active context")
    void cancelPathRemovesActiveContext() throws Exception {
        CountDownLatch enteredReasoning = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Hook blockingHook =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof PreReasoningEvent) {
                            enteredReasoning.countDown();
                            return Mono.<T>fromRunnable(
                                            () -> {
                                                try {
                                                    release.await(10, TimeUnit.SECONDS);
                                                } catch (InterruptedException e) {
                                                    Thread.currentThread().interrupt();
                                                }
                                            })
                                    .then(Mono.just(event));
                        }
                        return Mono.just(event);
                    }
                };

        ReActAgent agent = buildAgent(new MockModel("done"), blockingHook);
        RuntimeContext rc =
                RuntimeContext.builder().userId("cancel-user").sessionId("cancel-session").build();
        String runId = rc.getRunId();

        Disposable sub =
                agent.call(List.of(TestUtils.createUserMessage("user", "hi")), rc)
                        .subscribeOn(Schedulers.boundedElastic())
                        .subscribe();
        assertTrue(enteredReasoning.await(30, TimeUnit.SECONDS));
        assertEquals(1, agent.getActiveRuntimeContexts().size());

        sub.dispose();
        awaitRegistryDrained(agent, 5, TimeUnit.SECONDS);
        assertNull(agent.getRuntimeContext(runId));
        // Release the parked hook thread so it does not linger past the test.
        release.countDown();
    }

    @Test
    @DisplayName("RuntimeContextAware hook receives each bound context under concurrency")
    void runtimeContextAwareHookUnderConcurrency() throws Exception {
        List<Throwable> dropped = new java.util.concurrent.CopyOnWriteArrayList<>();
        Hooks.onErrorDropped(dropped::add);
        try {
            List<RuntimeContext> bound = new java.util.concurrent.CopyOnWriteArrayList<>();
            ConcurrencyAwareHook hook = new ConcurrencyAwareHook(bound);
            ReActAgent agent = buildAgent(new MockModel("done"), hook);

            RuntimeContext alice =
                    RuntimeContext.builder().userId("alice").sessionId("s-alice").build();
            RuntimeContext bob = RuntimeContext.builder().userId("bob").sessionId("s-bob").build();

            AtomicReference<Throwable> callError = new AtomicReference<>();
            agent.call(List.of(TestUtils.createUserMessage("alice", "hi")), alice)
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(m -> {}, callError::set);
            agent.call(List.of(TestUtils.createUserMessage("bob", "hi")), bob)
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(m -> {}, callError::set);

            assertTrue(
                    hook.entered.await(30, TimeUnit.SECONDS), "both calls should enter reasoning");

            List<String> boundRunIds = bound.stream().map(RuntimeContext::getRunId).toList();
            assertTrue(boundRunIds.contains(alice.getRunId()), "alice's context should be bound");
            assertTrue(boundRunIds.contains(bob.getRunId()), "bob's context should be bound");
            assertEquals(2, agent.getActiveRuntimeContexts().size());

            hook.release.countDown();
            hook.release.countDown();
            awaitRegistryDrained(agent, 5, TimeUnit.SECONDS);
            assertNull(callError.get(), "concurrent calls must complete without error");
        } finally {
            Hooks.resetOnErrorDropped();
        }
        assertTrue(dropped.isEmpty(), "no dropped errors expected during concurrent calls");
    }

    @Test
    @DisplayName(
            "sharing one RuntimeContext across concurrent calls fails fast, sequential reuse works")
    void duplicateRunIdAcrossConcurrentCallsFailsFast() throws Exception {
        CountDownLatch enteredReasoning = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Hook blockingHook =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof PreReasoningEvent) {
                            enteredReasoning.countDown();
                            return Mono.<T>fromRunnable(
                                            () -> {
                                                try {
                                                    release.await(10, TimeUnit.SECONDS);
                                                } catch (InterruptedException e) {
                                                    Thread.currentThread().interrupt();
                                                }
                                            })
                                    .then(Mono.just(event));
                        }
                        return Mono.just(event);
                    }
                };

        ReActAgent agent = buildAgent(new MockModel("done"), blockingHook);
        RuntimeContext shared =
                RuntimeContext.builder().userId("dup-user").sessionId("dup-session").build();

        CountDownLatch doneA = new CountDownLatch(1);
        AtomicReference<Msg> resultA = new AtomicReference<>();
        agent.call(List.of(TestUtils.createUserMessage("user", "first")), shared)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        msg -> {
                            resultA.set(msg);
                            doneA.countDown();
                        },
                        e -> doneA.countDown());
        assertTrue(enteredReasoning.await(30, TimeUnit.SECONDS));

        // A copy carrying the same runId must be rejected exactly like the shared instance.
        RuntimeContext copied = RuntimeContext.builder(shared).build();
        assertEquals(shared.getRunId(), copied.getRunId());

        Throwable[] error = new Throwable[1];
        try {
            agent.call(List.of(TestUtils.createUserMessage("user", "second")), shared)
                    .block(java.time.Duration.ofSeconds(30));
        } catch (Throwable t) {
            error[0] = t;
        }
        assertNotNull(error[0], "the duplicate concurrent call must be rejected");
        assertTrue(error[0] instanceof IllegalStateException, "got: " + error[0]);
        assertTrue(
                error[0].getMessage().contains("already active"),
                "message should explain runId collision: " + error[0].getMessage());

        // The rejected call must not have evicted the original registration.
        assertSame(shared, agent.getRuntimeContext(shared.getRunId()));

        // Finish the first call; the registry drains.
        release.countDown();
        assertTrue(doneA.await(30, TimeUnit.SECONDS));
        assertNotNull(resultA.get());
        awaitRegistryDrained(agent, 5, TimeUnit.SECONDS);

        // Sequential reuse of the same RuntimeContext after completion is fine.
        assertNotNull(
                agent.call(List.of(TestUtils.createUserMessage("user", "third")), shared)
                        .block(java.time.Duration.ofSeconds(30)));
        awaitRegistryDrained(agent, 5, TimeUnit.SECONDS);
    }

    /**
     * Polls until the agent's active-context registry becomes empty, or fails the timeout.
     * Needed because the {@code Mono.using} cleanup fires after the terminal signal propagates
     * downstream.
     */
    private static void awaitRegistryDrained(ReActAgent agent, long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        while (!agent.getActiveRuntimeContexts().isEmpty()) {
            if (System.nanoTime() > deadlineNanos) {
                throw new AssertionError(
                        "Active-context registry not drained within "
                                + timeout
                                + " "
                                + unit
                                + "; still contains: "
                                + agent.getActiveRuntimeContexts().size());
            }
            Thread.sleep(10);
        }
    }

    // ==================== Helpers ====================

    private static ReActAgent buildAgent(Model model, Hook hook) {
        ReActAgent.Builder b =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(model);
        if (hook != null) {
            b.hook(hook);
        }
        return b.build();
    }

    private static Model nativeJsonModel() {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.just(
                        ChatResponse.builder()
                                .id("native-json")
                                .content(
                                        List.of(
                                                io.agentscope.core.message.TextBlock.builder()
                                                        .text("{\"value\":\"ok\"}")
                                                        .build()))
                                .build());
            }

            @Override
            public String getModelName() {
                return "native-json-model";
            }

            @Override
            public boolean supportsNativeStructuredOutput() {
                return true;
            }
        };
    }

    /** Simple structured-output target for the native/fallback path tests. */
    public static class SimplePojo {
        private String value;

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

    /** Records each bound context and blocks both calls in reasoning until released. */
    private static final class ConcurrencyAwareHook implements Hook, RuntimeContextAware {
        private final List<RuntimeContext> bound;
        private final CountDownLatch entered = new CountDownLatch(2);
        private final CountDownLatch release = new CountDownLatch(2);

        ConcurrencyAwareHook(List<RuntimeContext> bound) {
            this.bound = bound;
        }

        @Override
        public void setRuntimeContext(RuntimeContext context) {
            if (context != null) {
                bound.add(context);
            }
        }

        @Override
        public <T extends HookEvent> Mono<T> onEvent(T event) {
            if (event instanceof PreReasoningEvent) {
                entered.countDown();
                return Mono.<T>fromRunnable(
                                () -> {
                                    try {
                                        release.await(10, TimeUnit.SECONDS);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                })
                        .then(Mono.just(event));
            }
            return Mono.just(event);
        }
    }
}
