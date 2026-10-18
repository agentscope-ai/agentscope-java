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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.task.TaskRecord;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Sinks;

class AgentProtocolTaskStoreConcurrentSubmitTest {
    @TempDir Path tempDir;

    @Test
    @Timeout(15)
    void concurrentSubmissionsOfSameTaskStartOnlyOneRun() throws Exception {
        assertConcurrentSubmissions(true);
    }

    @Test
    @Timeout(15)
    void submittingAnotherTaskDoesNotWaitForFirstTaskRegistration() throws Exception {
        assertConcurrentSubmissions(false);
    }

    private void assertConcurrentSubmissions(boolean sameTask) throws Exception {
        CountDownLatch firstPending = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger pendingWrites = new AtomicInteger();
        AtomicInteger runs = new AtomicInteger();
        WorkspaceProtocolTaskRepository delegate = new WorkspaceProtocolTaskRepository(tempDir);
        ProtocolTaskRepository repository =
                new ProtocolTaskRepository() {
                    @Override
                    public void save(TaskRecord record) {
                        delegate.save(record);
                        if (record.getStatus() == TaskStatus.PENDING
                                && pendingWrites.incrementAndGet() == 1) {
                            firstPending.countDown();
                            try {
                                if (!releaseFirst.await(10, TimeUnit.SECONDS)) {
                                    throw new AssertionError("pending save not released");
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError(e);
                            }
                        }
                    }

                    @Override
                    public Optional<TaskRecord> find(String taskId) {
                        return delegate.find(taskId);
                    }
                };
        Sinks.One<AgentEvent> result = Sinks.one();
        HarnessAgent agent = mock(HarnessAgent.class);
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class)))
                .thenAnswer(
                        invocation -> {
                            runs.incrementAndGet();
                            return result.asMono().flux();
                        });
        AgentProtocolTaskStore store =
                new AgentProtocolTaskStore(AgentFactory.fixed(agent), repository);
        CompletableFuture<Void> firstDone = new CompletableFuture<>();
        CompletableFuture<Void> secondDone = new CompletableFuture<>();
        String secondTaskId = sameTask ? "same-task" : "other-task";
        Thread first = submitThread(store, "same-task", firstDone);
        Thread second = submitThread(store, secondTaskId, secondDone);
        first.start();
        try {
            assertTrue(firstPending.await(5, TimeUnit.SECONDS));
            second.start();
            // On the buggy code the second submit returns while the first is parked in save.
            // With per-task serialization it blocks at the task monitor until save is released.
            if (sameTask) {
                await(() -> secondDone.isDone() || blockedBy(second, first));
            } else {
                secondDone.get(5, TimeUnit.SECONDS);
            }
            releaseFirst.countDown();
            firstDone.get(5, TimeUnit.SECONDS);
            secondDone.get(5, TimeUnit.SECONDS);
            await(() -> runs.get() == pendingWrites.get());
            int expectedRuns = sameTask ? 1 : 2;
            assertEquals(expectedRuns, pendingWrites.get(), "one registration per logical task");
            assertEquals(expectedRuns, runs.get(), "one execution per logical task");
        } finally {
            releaseFirst.countDown();
            result.tryEmitValue(
                    new AgentResultEvent(
                            Msg.builder().role(MsgRole.ASSISTANT).textContent("done").build()));
            first.join(5000);
            if (second.getState() != Thread.State.NEW) {
                second.join(5000);
            }
        }
        assertEquals("success", store.waitFor("same-task", 5000).get("status"));
        assertEquals("success", store.waitFor(secondTaskId, 5000).get("status"));
        assertThrows(
                IllegalStateException.class,
                () -> store.submit("same-task", "worker", "retry after completion"));
    }

    private static boolean blockedBy(Thread thread, Thread owner) {
        ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(thread.getId());
        return info != null
                && info.getThreadState() == Thread.State.BLOCKED
                && info.getLockOwnerId() == owner.getId();
    }

    private static Thread submitThread(
            AgentProtocolTaskStore store, String taskId, CompletableFuture<Void> done) {
        return new Thread(
                () -> {
                    try {
                        store.submit(taskId, "worker", "request");
                        done.complete(null);
                    } catch (Throwable error) {
                        done.completeExceptionally(error);
                    }
                });
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertTrue(condition.getAsBoolean(), "condition did not become true");
    }
}
