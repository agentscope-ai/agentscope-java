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
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.protocol.RemoteConfirmDecision;
import io.agentscope.harness.agent.subagent.task.TaskRecord;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * Deterministic reproduction of the resume-vs-unwind race seen on CI: the paused run persists the
 * {@code awaiting_confirm} record before its CompletableFuture completes, so a client that reacts
 * to the record (or the REQUIRE_CONFIRM event) can call {@code resume} while that future is still
 * unwinding. Parking the executor thread right after the persist simulates the worst-case
 * preemption that CI runners hit. Resume must still be accepted in that window.
 */
class AgentProtocolTaskStorePauseRaceReproTest {

    @TempDir Path tempDir;

    @Test
    void resumeWhilePausedRunStillUnwinding() throws Exception {
        AtomicInteger streamCalls = new AtomicInteger();
        CountDownLatch saveLanded = new CountDownLatch(1);
        CountDownLatch releaseSave = new CountDownLatch(1);

        WorkspaceProtocolTaskRepository delegate = new WorkspaceProtocolTaskRepository(tempDir);
        ProtocolTaskRepository repo =
                new ProtocolTaskRepository() {
                    @Override
                    public void save(TaskRecord record) {
                        delegate.save(record);
                        if (record.isAwaitingConfirm()) {
                            // Record is durable and visible, but this run still owns its leases:
                            // hand control to the test before releaseRun can run.
                            saveLanded.countDown();
                            try {
                                releaseSave.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    }

                    @Override
                    public Optional<TaskRecord> find(String taskId) {
                        return delegate.find(taskId);
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
                            return Flux.just(
                                    new AgentStartEvent("sess", null, "worker"),
                                    new AgentResultEvent(completedMsg()),
                                    new AgentEndEvent(null));
                        });

        AgentProtocolProperties props = new AgentProtocolProperties();
        props.setHitlEnabled(true);
        props.setStreamingEnabled(true);
        AgentProtocolTaskStore store =
                new AgentProtocolTaskStore(
                        AgentFactory.fixed(agent), repo, new AgentProtocolTaskEventBus(), props);

        store.submit("race-1", "worker", "please run bash", Map.of());

        // saveLanded fires right after the awaiting-confirm write and before releaseRun. At this
        // point the record is visible but the paused run still holds both leases — exactly the
        // window a fast client hits. Resume from another thread so the main thread can let the
        // paused run finish unwinding; the resume must wait for that instead of failing.
        assertTrue(saveLanded.await(5, TimeUnit.SECONDS), "pause record was never written");
        assertEquals("awaiting_confirm", store.snapshot("race-1").get("status"));

        ExecutorService resumer = Executors.newSingleThreadExecutor();
        try {
            Future<?> resumed =
                    resumer.submit(
                            () ->
                                    store.resume(
                                            "race-1",
                                            List.of(
                                                    new RemoteConfirmDecision(
                                                            "tc-ask", false, "no"))));
            // Let releaseRun proceed; the resume above waits for the lease it frees.
            releaseSave.countDown();
            resumed.get(5, TimeUnit.SECONDS);
        } finally {
            // Idempotent: also unblocks the save if submit/get threw, and never leaks the thread.
            releaseSave.countDown();
            resumer.shutdownNow();
        }

        long deadline = System.currentTimeMillis() + 5_000;
        while (!"success".equals(store.snapshot("race-1").get("status"))) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("task never completed after resume");
            }
            Thread.sleep(20);
        }
        assertEquals("denied and done", store.snapshot("race-1").get("result"));
        assertEquals(2, streamCalls.get());
    }

    /**
     * The window this guards: the pause record has not landed yet and the run has not handed its
     * leases back either. A same-session submission arriving exactly here used to slip through and
     * start a new turn, leaving the paused one stranded behind it.
     */
    @Test
    void aSubmissionArrivingWhileThePauseIsWrittenIsRejected() throws Exception {
        CountDownLatch pauseWriteStarted = new CountDownLatch(1);
        CountDownLatch releasePauseWrite = new CountDownLatch(1);
        AtomicInteger streamCalls = new AtomicInteger();

        WorkspaceProtocolTaskRepository delegate = new WorkspaceProtocolTaskRepository(tempDir);
        ProtocolTaskRepository repo =
                new ProtocolTaskRepository() {
                    @Override
                    public void save(TaskRecord record) {
                        if (record.isAwaitingConfirm() && "t-1".equals(record.getTaskId())) {
                            pauseWriteStarted.countDown();
                            try {
                                // Park before the write lands: leases still held, record not yet
                                // paused.
                                releasePauseWrite.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        delegate.save(record);
                    }

                    @Override
                    public Optional<TaskRecord> find(String taskId) {
                        return delegate.find(taskId);
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
                            return Flux.just(
                                    new AgentStartEvent("sess", null, "worker"),
                                    new AgentResultEvent(completedMsg()),
                                    new AgentEndEvent(null));
                        });

        AgentProtocolProperties props = new AgentProtocolProperties();
        props.setHitlEnabled(true);
        props.setStreamingEnabled(true);
        AgentProtocolTaskStore store =
                new AgentProtocolTaskStore(
                        AgentFactory.fixed(agent), repo, new AgentProtocolTaskEventBus(), props);

        store.submit("t-1", "worker", "please run bash", Map.of("session_id", "conv-1"));
        assertTrue(
                pauseWriteStarted.await(5, TimeUnit.SECONDS), "t-1 never reached the pause write");

        // The session lease is still held, so the new turn is refused rather than queued.
        assertThrows(
                SessionBusyException.class,
                () -> store.submit("t-2", "worker", "carry on", Map.of("session_id", "conv-1")));

        releasePauseWrite.countDown();
        long deadline = System.currentTimeMillis() + 5_000;
        while (!"awaiting_confirm".equals(store.snapshot("t-1").get("status"))
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        // And once the pause is visible, the next attempt is refused for the other reason.
        assertThrows(
                SessionAwaitingConfirmException.class,
                () -> store.submit("t-3", "worker", "carry on", Map.of("session_id", "conv-1")));
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
