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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.protocol.RemoteAgentEvent;
import io.agentscope.harness.agent.subagent.protocol.RemoteConfirmDecision;
import io.agentscope.harness.agent.subagent.protocol.RemoteEventType;
import io.agentscope.harness.agent.subagent.task.TaskRecord;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/** Deterministic coverage of resume while the paused executor is still unwinding. */
@Timeout(20)
class AgentProtocolTaskStorePauseRaceReproTest {

    @TempDir Path tempDir;

    @Test
    void resumedRunCompletesBeforePausedExecutorReleaseWithoutReopeningChannel() throws Exception {
        PauseFixture fixture = new PauseFixture();
        try {
            CompletableFuture<String> resumed = fixture.resume();
            fixture.completeReplacement();
            assertEquals("denied and done", resumed.get(5, TimeUnit.SECONDS));
            assertTrue(fixture.streamCompleted.await(5, TimeUnit.SECONDS));
            assertTrue(channels(fixture.bus).isEmpty(), "terminal run removes its channel");

            fixture.releaseExecutor.countDown();
            assertEquals(
                    AgentProtocolTaskStore.AWAITING_CONFIRM_SENTINEL,
                    fixture.pausedFuture.get(5, TimeUnit.SECONDS));
            assertTrue(fixture.pausedCleanup.await(5, TimeUnit.SECONDS));

            fixture.assertNoStalePause();
            assertTrue(
                    channels(fixture.bus).isEmpty(), "old pause must not recreate a live channel");
            assertEquals("success", fixture.store.snapshot("race-1").get("status"));
            assertEquals("denied and done", fixture.store.snapshot("race-1").get("result"));
            assertEquals(2, fixture.streamCalls.get());
        } finally {
            fixture.release();
        }
    }

    @Test
    void replacementFutureSurvivesOldPausedFutureCleanup() throws Exception {
        PauseFixture fixture = new PauseFixture();
        try {
            CompletableFuture<String> resumed = fixture.resume();
            assertTrue(fixture.replacementStarted.await(5, TimeUnit.SECONDS));
            fixture.releaseExecutor.countDown();
            fixture.pausedFuture.get(5, TimeUnit.SECONDS);
            // Observe the actual conditional remove, not merely the old future's result:
            // CompletableFuture.get() can return before completion callbacks have finished.
            assertTrue(fixture.pausedCleanup.await(5, TimeUnit.SECONDS));

            assertSame(resumed, futures(fixture.store).get("race-1"));
            assertFalse(resumed.isDone(), "replacement is still awaiting its model result");
            fixture.assertNoStalePause();
            fixture.completeReplacement();
            assertEquals("denied and done", resumed.get(5, TimeUnit.SECONDS));
            assertEquals("success", fixture.store.waitFor("race-1", 5000).get("status"));
        } finally {
            fixture.release();
        }
    }

    @Test
    void concurrentResumesAdmitExactlyOneReplacement() throws Exception {
        PauseFixture fixture = new PauseFixture();
        CompletableFuture<Boolean> firstDone = new CompletableFuture<>();
        CompletableFuture<Boolean> secondDone = new CompletableFuture<>();
        Thread first = resumeThread(fixture.store, firstDone);
        Thread second = resumeThread(fixture.store, secondDone);
        fixture.parkedResumeCaller = first;
        first.start();
        try {
            assertTrue(fixture.firstResumeRead.await(5, TimeUnit.SECONDS));
            second.start();
            // Without the admission lock, the second resume returns while the first is
            // holding its stale awaiting-confirm record. With the lock it must block.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!secondDone.isDone()
                    && !blockedBy(second, first)
                    && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertTrue(secondDone.isDone() || blockedBy(second, first));
            fixture.releaseResumeRead.countDown();
            int admitted =
                    (firstDone.get(5, TimeUnit.SECONDS) ? 1 : 0)
                            + (secondDone.get(5, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, admitted);
            assertTrue(fixture.replacementStarted.await(5, TimeUnit.SECONDS));
            assertEquals(2, fixture.streamCalls.get());
            CompletableFuture<String> resumed = futures(fixture.store).get("race-1");
            fixture.releaseExecutor.countDown();
            assertTrue(fixture.pausedCleanup.await(5, TimeUnit.SECONDS));
            assertSame(resumed, futures(fixture.store).get("race-1"));
            fixture.completeReplacement();
            assertEquals("denied and done", resumed.get(5, TimeUnit.SECONDS));
            fixture.assertNoStalePause();
        } finally {
            fixture.release();
            first.join(5000);
            if (second.getState() != Thread.State.NEW) {
                second.join(5000);
            }
        }
    }

    private static boolean blockedBy(Thread thread, Thread owner) {
        ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(thread.getId());
        return info != null
                && info.getThreadState() == Thread.State.BLOCKED
                && info.getLockOwnerId() == owner.getId();
    }

    private static Thread resumeThread(
            AgentProtocolTaskStore store, CompletableFuture<Boolean> done) {
        return new Thread(
                () -> {
                    try {
                        store.resume(
                                "race-1",
                                List.of(new RemoteConfirmDecision("tc-ask", false, "no")));
                        done.complete(true);
                    } catch (IllegalStateException expected) {
                        if (expected.getMessage().contains("not awaiting confirmation")) {
                            done.complete(false);
                        } else {
                            done.completeExceptionally(expected);
                        }
                    } catch (Throwable error) {
                        done.completeExceptionally(error);
                    }
                });
    }

    private final class PauseFixture {
        final CountDownLatch releaseExecutor = new CountDownLatch(1);
        final CountDownLatch pauseSaved = new CountDownLatch(1);
        final CountDownLatch firstResumeRead = new CountDownLatch(1);
        final CountDownLatch releaseResumeRead = new CountDownLatch(1);
        final CountDownLatch pausedCleanup = new CountDownLatch(1);
        final CountDownLatch replacementStarted = new CountDownLatch(1);
        final CountDownLatch streamCompleted = new CountDownLatch(1);
        final AtomicInteger streamCalls = new AtomicInteger();
        final Sinks.One<AgentEvent> replacementResult = Sinks.one();
        final AgentProtocolTaskEventBus bus = spy(new AgentProtocolTaskEventBus());
        final List<RemoteAgentEvent> events = new CopyOnWriteArrayList<>();
        final AgentProtocolTaskStore store;
        CompletableFuture<String> pausedFuture;
        Thread parkedResumeCaller;

        PauseFixture() throws Exception {
            WorkspaceProtocolTaskRepository delegate = new WorkspaceProtocolTaskRepository(tempDir);
            ProtocolTaskRepository repo =
                    new ProtocolTaskRepository() {
                        @Override
                        public void save(TaskRecord record) {
                            delegate.save(record);
                            if (record.isAwaitingConfirm()) {
                                // The record is durable and visible before publication. Resume
                                // must not wait for this executor's remaining pause work.
                                pauseSaved.countDown();
                                awaitRelease(releaseExecutor);
                            }
                        }

                        @Override
                        public Optional<TaskRecord> find(String taskId) {
                            Optional<TaskRecord> record = delegate.find(taskId);
                            if (Thread.currentThread() == parkedResumeCaller) {
                                firstResumeRead.countDown();
                                awaitRelease(releaseResumeRead);
                            }
                            return record;
                        }
                    };
            HarnessAgent agent = mock(HarnessAgent.class);
            when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class)))
                    .thenAnswer(
                            inv -> {
                                if (streamCalls.incrementAndGet() == 1) {
                                    return Flux.just(
                                            new AgentStartEvent("sess", null, "worker"),
                                            new AgentResultEvent(askingMsg()),
                                            new AgentEndEvent(null));
                                }
                                replacementStarted.countDown();
                                return replacementResult.asMono().flux();
                            });
            AgentProtocolProperties props = new AgentProtocolProperties();
            props.setHitlEnabled(true);
            props.setStreamingEnabled(true);
            store = new AgentProtocolTaskStore(AgentFactory.fixed(agent), repo, bus, props);

            // Instrument cleanup only, leaving execution and the real event bus unchanged.
            Map<String, CompletableFuture<String>> handles = spy(futures(store));
            Field field = AgentProtocolTaskStore.class.getDeclaredField("futures");
            field.setAccessible(true);
            field.set(store, handles);
            doAnswer(
                            inv -> {
                                Object removed = inv.callRealMethod();
                                if (inv.getArgument(1) == pausedFuture) {
                                    pausedCleanup.countDown();
                                }
                                return removed;
                            })
                    .when(handles)
                    .remove(any(), any());
            bus.subscribe("race-1", 0)
                    .subscribe(events::add, error -> {}, streamCompleted::countDown);
            store.submit("race-1", "worker", "please run bash", Map.of());
            pausedFuture = handles.get("race-1");
            assertTrue(pauseSaved.await(5, TimeUnit.SECONDS));
            assertEquals("awaiting_confirm", store.snapshot("race-1").get("status"));
        }

        CompletableFuture<String> resume() throws Exception {
            store.resume("race-1", List.of(new RemoteConfirmDecision("tc-ask", false, "no")));
            return futures(store).get("race-1");
        }

        void completeReplacement() {
            replacementResult.tryEmitValue(new AgentResultEvent(completedMsg()));
        }

        void assertNoStalePause() {
            // Also inspect publication: a stale event on a recreated channel cannot be seen
            // by subscribers whose original channel already completed.
            verify(bus, never())
                    .publish(
                            eq("race-1"),
                            argThat(e -> e.getType() == RemoteEventType.REQUIRE_CONFIRM));
            assertTrue(
                    events.stream().noneMatch(e -> e.getType() == RemoteEventType.REQUIRE_CONFIRM),
                    "superseded executor must not publish REQUIRE_CONFIRM");
        }

        void release() {
            releaseResumeRead.countDown();
            releaseExecutor.countDown();
            completeReplacement();
        }
    }

    private static void awaitRelease(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "executor was not released");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, CompletableFuture<String>> futures(AgentProtocolTaskStore store)
            throws Exception {
        Field field = AgentProtocolTaskStore.class.getDeclaredField("futures");
        field.setAccessible(true);
        return (Map<String, CompletableFuture<String>>) field.get(store);
    }

    private static Map<?, ?> channels(AgentProtocolTaskEventBus bus) throws Exception {
        Field field = AgentProtocolTaskEventBus.class.getDeclaredField("channels");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(bus);
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
}
