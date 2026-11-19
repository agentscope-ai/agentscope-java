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
package io.agentscope.extensions.agentprotocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.protocol.RemoteConfirmDecision;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Two {@link AgentProtocolTaskStore} instances sharing one repository, run registry, context store
 * and event bus — the shape of a multi-instance deployment. Each test exercises a request that lands
 * on the instance that is <em>not</em> running the task.
 */
class AgentProtocolTaskStoreClusterTest {

    @TempDir Path tempDir;

    private HarnessAgent agent;
    private AgentProtocolTaskStore nodeA;
    private AgentProtocolTaskStore nodeB;

    @BeforeEach
    void setUp() {
        agent = mock(HarnessAgent.class);

        AgentProtocolProperties props = new AgentProtocolProperties();
        props.setHitlEnabled(true);
        props.setStreamingEnabled(true);

        ProtocolTaskRepository repository = new WorkspaceProtocolTaskRepository(tempDir);
        ProtocolTaskRunRegistry runRegistry = BaseStoreProtocolTaskRunRegistry.inMemory();
        ProtocolTaskContextStore contextStore = BaseStoreProtocolTaskContextStore.inMemory();
        AgentProtocolEventBus eventBus = new AgentProtocolTaskEventBus();

        nodeA = newStore(agent, props, repository, runRegistry, contextStore, eventBus);
        nodeB = newStore(agent, props, repository, runRegistry, contextStore, eventBus);
    }

    @AfterEach
    void tearDown() {
        nodeA.close();
        nodeB.close();
    }

    private static AgentProtocolTaskStore newStore(
            HarnessAgent agent,
            AgentProtocolProperties props,
            ProtocolTaskRepository repository,
            ProtocolTaskRunRegistry runRegistry,
            ProtocolTaskContextStore contextStore,
            AgentProtocolEventBus eventBus) {
        return new AgentProtocolTaskStore(
                AgentFactory.fixed(agent),
                repository,
                eventBus,
                props,
                List.of(),
                runRegistry,
                contextStore);
    }

    @Test
    void duplicateSubmitOnAnotherNodeDoesNotStartSecondRun() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class)))
                .thenAnswer(
                        inv -> {
                            runs.incrementAndGet();
                            return Flux.concat(
                                    Flux.just((AgentEvent) startEvent()),
                                    Mono.fromCallable(
                                                    () -> {
                                                        hold.await(10, TimeUnit.SECONDS);
                                                        return (AgentEvent)
                                                                new AgentResultEvent(
                                                                        completedMsg());
                                                    })
                                            .flux(),
                                    Flux.just((AgentEvent) new AgentEndEvent(null)));
                        });

        nodeA.submit("dup-1", "worker", "hi", Map.of());
        // RUNNING is written before the executor reaches streamEvents, so wait for the first run
        // to actually start before counting duplicates; a fixed sleep races on slow machines.
        awaitRuns(runs, 1);

        // The duplicate is refused synchronously (no task lease left), so once submit returns no
        // further run can start.
        nodeB.submit("dup-1", "worker", "hi", Map.of());
        assertEquals(1, runs.get(), "the duplicate must not start a second run");
        hold.countDown();
        awaitStatus(nodeB, "dup-1", "success", 5_000);
    }

    @Test
    void resumeOnAnotherNodeKeepsSubmissionContext() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class)))
                .thenAnswer(
                        inv -> {
                            if (runs.incrementAndGet() == 1) {
                                return Flux.just(
                                        (AgentEvent) startEvent(),
                                        new AgentResultEvent(askingMsg()),
                                        new AgentEndEvent(null));
                            }
                            return Flux.just(
                                    (AgentEvent) startEvent(),
                                    new AgentResultEvent(completedMsg()),
                                    new AgentEndEvent(null));
                        });

        nodeA.submit("hitl-1", "worker", "run bash", Map.of("user_id", "u-7", "detail", "full"));
        awaitStatus(nodeA, "hitl-1", "awaiting_confirm", 5_000);

        // The pause must not pin the task to the instance that ran it.
        assertTrue(nodeB.hasSubmitContext("hitl-1"));

        nodeB.resume("hitl-1", List.of(new RemoteConfirmDecision("tc-ask", false, "no")));
        awaitStatus(nodeA, "hitl-1", "success", 5_000);

        assertEquals("denied and done", nodeA.snapshot("hitl-1").get("result"));

        ArgumentCaptor<Msg> messages = ArgumentCaptor.forClass(Msg.class);
        ArgumentCaptor<RuntimeContext> contexts = ArgumentCaptor.forClass(RuntimeContext.class);
        verify(agent, times(2)).streamEvents(messages.capture(), contexts.capture());

        // Submission identity survives the instance hop, instead of degrading to defaults.
        assertEquals("u-7", contexts.getAllValues().get(1).getUserId());
        Object raw = messages.getAllValues().get(1).getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
        List<?> results = assertInstanceOf(List.class, raw);
        ConfirmResult result = assertInstanceOf(ConfirmResult.class, results.get(0));
        assertFalse(result.isConfirmed());
    }

    @Test
    void concurrentResumeOnTwoNodesStartsOneRun() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class)))
                .thenAnswer(
                        inv -> {
                            if (runs.incrementAndGet() == 1) {
                                return Flux.just(
                                        (AgentEvent) startEvent(),
                                        new AgentResultEvent(askingMsg()),
                                        new AgentEndEvent(null));
                            }
                            return Flux.just(
                                    (AgentEvent) startEvent(),
                                    new AgentResultEvent(completedMsg()),
                                    new AgentEndEvent(null));
                        });

        nodeA.submit("race-1", "worker", "run bash", Map.of());
        awaitStatus(nodeA, "race-1", "awaiting_confirm", 5_000);

        ExecutorService callers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger rejected = new AtomicInteger();
        try {
            Future<?> first = callers.submit(() -> resume(nodeA, "race-1", start, rejected));
            Future<?> second = callers.submit(() -> resume(nodeB, "race-1", start, rejected));
            start.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            callers.shutdownNow();
        }

        assertEquals(1, rejected.get(), "exactly one resume must be rejected");
        awaitStatus(nodeA, "race-1", "success", 5_000);
        assertEquals(2, runs.get(), "one submit run plus one resumed run");
    }

    @Test
    void cancelOnAnotherNodeStopsTheRunAndKeepsCancelledStatus() throws Exception {
        int totalTicks = 150;
        AtomicInteger emitted = new AtomicInteger();
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.concat(
                                Flux.just((AgentEvent) startEvent()),
                                Flux.interval(Duration.ofMillis(20))
                                        .take(totalTicks)
                                        .map(i -> (AgentEvent) new AgentResultEvent(partialMsg()))
                                        .doOnNext(e -> emitted.incrementAndGet())));

        nodeA.submit("cancel-1", "worker", "go", Map.of());
        awaitStatus(nodeA, "cancel-1", "running", 5_000);

        nodeB.cancel("cancel-1");

        assertEquals("cancelled", nodeB.snapshot("cancel-1").get("status"));

        // Wait for the stream to stop emitting rather than for a fixed duration, so a slow runner
        // cannot turn this into a flake: an uncancelled run emits every tick until it completes.
        int emittedWhenStopped = awaitStableCount(emitted);
        assertTrue(
                emittedWhenStopped < totalTicks,
                "the run was not stopped early: " + emittedWhenStopped + " of " + totalTicks);
        // A cancelled task must not be rewritten as failed by the run that unwinds afterwards.
        assertEquals("cancelled", nodeA.snapshot("cancel-1").get("status"));
    }

    @Test
    void waitForOnAnotherNodeSeesTheResultWrittenByTheOwner() throws Exception {
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.just(
                                (AgentEvent) startEvent(),
                                new AgentResultEvent(completedMsg()),
                                new AgentEndEvent(null)));

        nodeA.submit("wait-1", "worker", "hi", Map.of());

        Map<String, Object> out = nodeB.waitFor("wait-1", 5_000);

        assertEquals("success", out.get("status"));
        assertEquals("denied and done", out.get("result"));
    }

    private static void resume(
            AgentProtocolTaskStore store,
            String taskId,
            CountDownLatch start,
            AtomicInteger rejected) {
        try {
            start.await(5, TimeUnit.SECONDS);
            store.resume(taskId, List.of(new RemoteConfirmDecision("tc-ask", false, "no")));
        } catch (IllegalStateException e) {
            rejected.incrementAndGet();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static AgentEvent startEvent() {
        return new AgentStartEvent("sess", null, "worker");
    }

    private static Msg askingMsg() {
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(
                        ToolUseBlock.builder()
                                .id("tc-ask")
                                .name("bash")
                                .input(Map.of("cmd", "rm -rf /"))
                                .state(ToolCallState.ASKING)
                                .build())
                .generateReason(GenerateReason.PERMISSION_ASKING)
                .build();
    }

    private static Msg completedMsg() {
        return Msg.builder().role(MsgRole.ASSISTANT).textContent("denied and done").build();
    }

    private static Msg partialMsg() {
        return Msg.builder().role(MsgRole.ASSISTANT).textContent("partial").build();
    }

    private static void awaitRuns(AtomicInteger runs, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (runs.get() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(expected, runs.get(), "the first run never reached streamEvents");
    }

    private static void awaitStatus(
            AgentProtocolTaskStore store, String taskId, String status, long timeoutMs)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!status.equals(store.snapshot(taskId).get("status"))) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError(
                        "task "
                                + taskId
                                + " never reached "
                                + status
                                + ": "
                                + store.snapshot(taskId));
            }
            Thread.sleep(25);
        }
    }

    /** Returns the counter value once it has been unchanged for 300 ms, or the value at timeout. */
    private static int awaitStableCount(AtomicInteger counter) throws Exception {
        long deadline = System.currentTimeMillis() + 8_000;
        int last = counter.get();
        long quietSince = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            int current = counter.get();
            if (current != last) {
                last = current;
                quietSince = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - quietSince >= 300) {
                return current;
            }
        }
        return counter.get();
    }
}
