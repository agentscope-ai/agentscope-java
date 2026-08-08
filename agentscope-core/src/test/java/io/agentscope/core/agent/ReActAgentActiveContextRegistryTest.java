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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.agent.test.TestConstants;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Verifies that {@link ReActAgent} tracks active per-call {@link RuntimeContext}s in a
 * runId-indexed registry, so concurrent calls on different sessions no longer overwrite each
 * other's context.
 */
@DisplayName("ReActAgent active-context registry")
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

        // After both complete, registry is empty. The doFinally cleanup fires after the
        // terminal signal propagates downstream, so poll briefly for the registry to drain.
        awaitRegistryDrained(agent, 5, TimeUnit.SECONDS);
        assertNull(agent.getRuntimeContext(runIdAlice));
        assertNull(agent.getRuntimeContext(runIdBob));
        assertTrue(agent.getActiveRuntimeContexts().isEmpty());
    }

    /**
     * Polls until the agent's active-context registry becomes empty, or fails the timeout.
     * Needed because {@code doFinally} fires after the terminal signal propagates downstream.
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

    private static ReActAgent buildAgent(MockModel model, Hook hook) {
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
}
