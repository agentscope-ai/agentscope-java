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
package io.agentscope.harness.agent.subagent.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.subagent.protocol.RemoteAgentEvent;
import io.agentscope.harness.agent.subagent.protocol.RemoteConfirmDecision;
import io.agentscope.harness.agent.subagent.protocol.RemotePendingConfirm;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.io.Closeable;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Remote polling + HITL awaiting_confirm toggle for {@link WorkspaceTaskRepository}, using a fake
 * {@link RemoteSubagentTransport} injected via {@link WorkspaceTaskRepository#setTransport}.
 */
class WorkspaceTaskRepositoryRemoteStreamingTest {

    @TempDir Path tempDir;

    private WorkspaceManager workspaceManager;
    private WorkspaceTaskRepository repo;

    @BeforeEach
    void setUp() {
        workspaceManager = new WorkspaceManager(tempDir);
        repo = WorkspaceTaskRepository.forTests(workspaceManager, "test-agent");
    }

    @AfterEach
    void tearDown() {
        if (repo != null) {
            repo.shutdown();
        }
    }

    @Test
    void remoteTask_togglesAwaitingConfirm_thenFiresCompletionOnSuccess() throws Exception {
        AtomicInteger statusCalls = new AtomicInteger();
        List<Boolean> awaitingSnapshots = new CopyOnWriteArrayList<>();
        FakeTransport transport = new FakeTransport(statusCalls, 2);
        repo.setTransport(transport);

        AtomicReference<String> completedResult = new AtomicReference<>();
        repo.setCompletionCallback(
                (rc, taskId, subAgentId, sessionId, result) -> completedResult.set(result));

        String session = "sess-remote";
        String taskId = "task-remote-hitl";

        repo.putTask(
                RuntimeContext.empty(),
                taskId,
                "remote-worker",
                session,
                new TaskRunSpec.RemoteTaskRunSpec(
                        "http://remote.test",
                        Map.of(),
                        "remote-worker",
                        "do work",
                        RemoteSubmitContext.empty()));

        awaitCondition(
                () -> {
                    Optional<TaskRecord> r =
                            workspaceManager.readTaskRecord(
                                    RuntimeContext.empty(), "test-agent", session, taskId);
                    if (r.isPresent() && r.get().isAwaitingConfirm()) {
                        awaitingSnapshots.add(true);
                    }
                    return r.isPresent() && r.get().getStatus().isTerminal();
                });

        Optional<TaskRecord> finalRecord =
                workspaceManager.readTaskRecord(
                        RuntimeContext.empty(), "test-agent", session, taskId);
        assertTrue(finalRecord.isPresent());
        assertEquals(TaskStatus.COMPLETED, finalRecord.get().getStatus());
        assertEquals("remote-ok", finalRecord.get().getResult());
        assertFalse(finalRecord.get().isAwaitingConfirm());
        assertTrue(
                !awaitingSnapshots.isEmpty() || statusCalls.get() >= 2,
                "expected at least one awaiting_confirm poll before success");
        assertEquals("remote-ok", completedResult.get());
        assertTrue(transport.submitCalled);
    }

    @Test
    void awaitingConfirm_doesNotRewriteWorkspaceEveryPoll() throws Exception {
        CountingWorkspaceManager countingWm = new CountingWorkspaceManager(tempDir);
        repo.shutdown();
        workspaceManager = countingWm;
        repo = WorkspaceTaskRepository.forTests(workspaceManager, "test-agent");

        AtomicInteger statusCalls = new AtomicInteger();
        // Stay in awaiting_confirm for several polls so a naive rewrite-every-poll would write
        // often.
        FakeTransport transport = new FakeTransport(statusCalls, 5);
        repo.setTransport(transport);

        String session = "sess-no-rewrite";
        String taskId = "task-no-rewrite";
        repo.putTask(
                RuntimeContext.empty(),
                taskId,
                "remote-worker",
                session,
                new TaskRunSpec.RemoteTaskRunSpec(
                        "http://remote.test",
                        Map.of(),
                        "remote-worker",
                        "do work",
                        RemoteSubmitContext.empty()));

        awaitCondition(
                () -> {
                    Optional<TaskRecord> r =
                            workspaceManager.readTaskRecord(
                                    RuntimeContext.empty(), "test-agent", session, taskId);
                    return r.isPresent() && r.get().getStatus().isTerminal();
                });

        assertTrue(statusCalls.get() >= 5, "expected multiple awaiting_confirm polls");
        // PENDING persist + RUNNING + enter awaiting_confirm + leave awaiting + COMPLETED.
        // Without the noop, five awaiting polls would add four extra writes (9 total).
        assertEquals(
                5,
                countingWm.writeCount.get(),
                "awaiting_confirm polls with unchanged pending must not re-persist");
    }

    private static void awaitCondition(Condition condition) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.get()) {
            if (System.currentTimeMillis() >= deadline) {
                throw new AssertionError("Condition not met within 10 seconds");
            }
            Thread.sleep(50);
        }
    }

    @FunctionalInterface
    private interface Condition {
        boolean get() throws Exception;
    }

    private static final class CountingWorkspaceManager extends WorkspaceManager {
        private final AtomicInteger writeCount = new AtomicInteger();

        CountingWorkspaceManager(Path workspace) {
            super(workspace);
        }

        @Override
        public void writeTaskRecord(
                RuntimeContext rc, String agentId, String sessionId, TaskRecord record) {
            writeCount.incrementAndGet();
            super.writeTaskRecord(rc, agentId, sessionId, record);
        }
    }

    /** Returns awaiting_confirm for {@code awaitingPolls} status calls, then success. */
    private static final class FakeTransport implements RemoteSubagentTransport {
        private final AtomicInteger statusCalls;
        private final int awaitingPolls;
        volatile boolean submitCalled;

        FakeTransport(AtomicInteger statusCalls, int awaitingPolls) {
            this.statusCalls = statusCalls;
            this.awaitingPolls = awaitingPolls;
        }

        @Override
        public String transportType() {
            return "agent-protocol";
        }

        @Override
        public void submit(
                RemoteTarget target,
                String taskId,
                String agentId,
                String input,
                RemoteSubmitContext context) {
            submitCalled = true;
        }

        @Override
        public RemoteTaskStatus getStatus(RemoteTarget target, String taskId) {
            int n = statusCalls.incrementAndGet();
            if (n <= awaitingPolls) {
                return new RemoteTaskStatus(
                        "awaiting_confirm",
                        null,
                        List.of(new RemotePendingConfirm("tc1", "bash", "{\"cmd\":\"ls\"}")));
            }
            return new RemoteTaskStatus("success", null);
        }

        @Override
        public String waitForResult(RemoteTarget target, String taskId, long timeoutSeconds) {
            return "remote-ok";
        }

        @Override
        public void cancel(RemoteTarget target, String taskId) {}

        @Override
        public void resume(
                RemoteTarget target, String taskId, List<RemoteConfirmDecision> decisions) {}

        @Override
        public Closeable streamEvents(
                RemoteTarget target,
                String taskId,
                long fromSeq,
                Consumer<RemoteAgentEvent> consumer) {
            return () -> {};
        }
    }
}
