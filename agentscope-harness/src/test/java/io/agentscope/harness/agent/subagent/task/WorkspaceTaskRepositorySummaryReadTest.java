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
package io.agentscope.harness.agent.subagent.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.middleware.SubagentsMiddleware;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Regression test for #4459: {@code buildTaskSummary} used to read the whole session task file
 * once for {@code listTasks} and again for every {@code isDelivered} check, so the number of reads
 * grew with the task count (N + 1).
 *
 * <p>The summary must instead be rendered from a single consistent snapshot, keeping the number
 * of whole-file reads constant regardless of how many tasks the session holds.
 */
class WorkspaceTaskRepositorySummaryReadTest {

    @TempDir Path tempDir;

    private static final String AGENT_ID = "parent-agent";
    private static final String SESSION_ID = "session-A";

    private CountingLocalFilesystem filesystem;
    private WorkspaceManager workspaceManager;
    private WorkspaceTaskRepository repository;

    /** Counts reads of the session task file while delegating to a real local backend. */
    private static final class CountingLocalFilesystem extends LocalFilesystem {

        private final AtomicInteger taskFileReads = new AtomicInteger();

        private CountingLocalFilesystem(Path root) {
            super(root);
        }

        @Override
        public ReadResult read(
                RuntimeContext runtimeContext, String filePath, int offset, int limit) {
            if (filePath != null
                    && filePath.endsWith("agents/" + AGENT_ID + "/tasks/" + SESSION_ID + ".json")) {
                taskFileReads.incrementAndGet();
            }
            return super.read(runtimeContext, filePath, offset, limit);
        }
    }

    @BeforeEach
    void setup() {
        filesystem = new CountingLocalFilesystem(tempDir);
        workspaceManager = new WorkspaceManager(tempDir, filesystem);
        repository = WorkspaceTaskRepository.forTests(workspaceManager, AGENT_ID);
    }

    @AfterEach
    void tearDown() {
        if (repository != null) {
            repository.shutdown();
        }
        if (workspaceManager != null) {
            workspaceManager.close();
        }
    }

    private void writeRecord(String taskId, TaskStatus status) {
        TaskRecord record = new TaskRecord(taskId, "sub-agent", AGENT_ID, SESSION_ID, null);
        record.setStatus(status);
        workspaceManager.writeTaskRecord(RuntimeContext.empty(), AGENT_ID, SESSION_ID, record);
    }

    private RuntimeContext rc() {
        return RuntimeContext.builder().sessionId(SESSION_ID).build();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 10})
    @DisplayName("buildTaskSummary reads the session task file once regardless of task count")
    void readsTaskFileOncePerSummary(int taskCount) {
        for (int i = 0; i < taskCount; i++) {
            writeRecord("task-" + i, TaskStatus.COMPLETED);
        }
        filesystem.taskFileReads.set(0);

        String summary = SubagentsMiddleware.buildTaskSummary(repository, rc());

        assertEquals(1, filesystem.taskFileReads.get());
        if (taskCount == 0) {
            assertNull(summary);
        } else {
            // The summary still lists the tasks, not just skips extra reads.
            assertNotNull(summary);
            assertTrue(summary.contains("task-0"));
        }
    }

    @Test
    @DisplayName("summary still filters delivered tasks from the same snapshot")
    void filtersDeliveredTasks() {
        writeRecord("task-0", TaskStatus.COMPLETED);
        writeRecord("task-1", TaskStatus.COMPLETED);
        repository.markDelivered(RuntimeContext.empty(), SESSION_ID, "task-0");

        String summary = SubagentsMiddleware.buildTaskSummary(repository, rc());

        assertNotNull(summary);
        assertFalse(summary.contains("task-0"));
        assertTrue(summary.contains("task-1"));
    }

    @Test
    @DisplayName("next summary observes newly delivered state instead of a cached snapshot")
    void nextSummaryReadsLatestDeliveryState() {
        writeRecord("task-0", TaskStatus.COMPLETED);
        String first = SubagentsMiddleware.buildTaskSummary(repository, rc());
        assertNotNull(first);
        assertTrue(first.contains("task-0"));

        repository.markDelivered(RuntimeContext.empty(), SESSION_ID, "task-0");

        String second = SubagentsMiddleware.buildTaskSummary(repository, rc());
        assertNull(second);
    }
}
