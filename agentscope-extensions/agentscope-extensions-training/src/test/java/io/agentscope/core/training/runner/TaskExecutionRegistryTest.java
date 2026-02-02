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

package io.agentscope.core.training.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.training.util.TrainingTestConstants;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TaskExecutionRegistry Unit Tests")
class TaskExecutionRegistryTest {

    @BeforeEach
    void setUp() {
        TaskExecutionRegistry.clearAll();
    }

    @AfterEach
    void tearDown() {
        TaskExecutionRegistry.clearAll();
    }

    @Test
    @DisplayName("Should register execution context")
    void shouldRegisterExecutionContext() {
        // Arrange
        RunExecutionContext context =
                RunExecutionContext.create(
                        TrainingTestConstants.TEST_TASK_ID, TrainingTestConstants.TEST_RUN_ID);

        // Act
        TaskExecutionRegistry.register(context);

        // Assert
        assertEquals(1, TaskExecutionRegistry.getTaskCount());
        assertEquals(1, TaskExecutionRegistry.getTotalRunCount());
    }

    @Test
    @DisplayName("Should ignore null context")
    void shouldIgnoreNullContext() {
        // Act
        TaskExecutionRegistry.register(null);

        // Assert
        assertEquals(0, TaskExecutionRegistry.getTaskCount());
    }

    @Test
    @DisplayName("Should get runs by task")
    void shouldGetRunsByTask() {
        // Arrange
        RunExecutionContext ctx0 =
                RunExecutionContext.create(TrainingTestConstants.TEST_TASK_ID, "0");
        RunExecutionContext ctx1 =
                RunExecutionContext.create(TrainingTestConstants.TEST_TASK_ID, "1");
        RunExecutionContext ctx2 =
                RunExecutionContext.create(TrainingTestConstants.TEST_TASK_ID, "2");

        TaskExecutionRegistry.register(ctx2);
        TaskExecutionRegistry.register(ctx0);
        TaskExecutionRegistry.register(ctx1);

        // Act
        List<RunExecutionContext> runs =
                TaskExecutionRegistry.getRunsByTask(TrainingTestConstants.TEST_TASK_ID);

        // Assert
        assertEquals(3, runs.size());
        // Should be sorted by runId
        assertEquals("0", runs.get(0).getRunId());
        assertEquals("1", runs.get(1).getRunId());
        assertEquals("2", runs.get(2).getRunId());
    }

    @Test
    @DisplayName("Should return empty list for non-existent task")
    void shouldReturnEmptyListForNonExistentTask() {
        // Act
        List<RunExecutionContext> runs = TaskExecutionRegistry.getRunsByTask("non-existent");

        // Assert
        assertTrue(runs.isEmpty());
    }

    @Test
    @DisplayName("Should return empty list for null taskId")
    void shouldReturnEmptyListForNullTaskId() {
        // Act
        List<RunExecutionContext> runs = TaskExecutionRegistry.getRunsByTask(null);

        // Assert
        assertTrue(runs.isEmpty());
    }

    @Test
    @DisplayName("Should get specific run")
    void shouldGetSpecificRun() {
        // Arrange
        RunExecutionContext ctx =
                RunExecutionContext.create(
                        TrainingTestConstants.TEST_TASK_ID, TrainingTestConstants.TEST_RUN_ID);
        ctx.addMsgId(TrainingTestConstants.TEST_MSG_ID_1);
        TaskExecutionRegistry.register(ctx);

        // Act
        RunExecutionContext result =
                TaskExecutionRegistry.getRun(
                        TrainingTestConstants.TEST_TASK_ID, TrainingTestConstants.TEST_RUN_ID);

        // Assert
        assertNotNull(result);
        assertEquals(TrainingTestConstants.TEST_TASK_ID, result.getTaskId());
        assertEquals(TrainingTestConstants.TEST_RUN_ID, result.getRunId());
    }

    @Test
    @DisplayName("Should return null for non-existent run")
    void shouldReturnNullForNonExistentRun() {
        // Act
        RunExecutionContext result = TaskExecutionRegistry.getRun("task", "999");

        // Assert
        assertNull(result);
    }

    @Test
    @DisplayName("Should return null when taskId or runId is null")
    void shouldReturnNullWhenTaskIdOrRunIdIsNull() {
        // Assert
        assertNull(TaskExecutionRegistry.getRun(null, "0"));
        assertNull(TaskExecutionRegistry.getRun("task", null));
    }

    @Test
    @DisplayName("Should get all task IDs")
    void shouldGetAllTaskIds() {
        // Arrange
        TaskExecutionRegistry.register(RunExecutionContext.create("task-1", "0"));
        TaskExecutionRegistry.register(RunExecutionContext.create("task-2", "0"));
        TaskExecutionRegistry.register(RunExecutionContext.create("task-3", "0"));

        // Act
        Set<String> taskIds = TaskExecutionRegistry.getAllTaskIds();

        // Assert
        assertEquals(3, taskIds.size());
        assertTrue(taskIds.contains("task-1"));
        assertTrue(taskIds.contains("task-2"));
        assertTrue(taskIds.contains("task-3"));
    }

    @Test
    @DisplayName("Should get run count for task")
    void shouldGetRunCountForTask() {
        // Arrange
        TaskExecutionRegistry.register(
                RunExecutionContext.create(TrainingTestConstants.TEST_TASK_ID, "0"));
        TaskExecutionRegistry.register(
                RunExecutionContext.create(TrainingTestConstants.TEST_TASK_ID, "1"));
        TaskExecutionRegistry.register(
                RunExecutionContext.create(TrainingTestConstants.TEST_TASK_ID, "2"));

        // Act
        int count = TaskExecutionRegistry.getRunCount(TrainingTestConstants.TEST_TASK_ID);

        // Assert
        assertEquals(3, count);
    }

    @Test
    @DisplayName("Should return 0 for non-existent task run count")
    void shouldReturnZeroForNonExistentTaskRunCount() {
        // Act
        int count = TaskExecutionRegistry.getRunCount("non-existent");

        // Assert
        assertEquals(0, count);
    }

    @Test
    @DisplayName("Should return 0 for null taskId run count")
    void shouldReturnZeroForNullTaskIdRunCount() {
        // Act
        int count = TaskExecutionRegistry.getRunCount(null);

        // Assert
        assertEquals(0, count);
    }

    @Test
    @DisplayName("Should get total run count")
    void shouldGetTotalRunCount() {
        // Arrange
        TaskExecutionRegistry.register(RunExecutionContext.create("task-1", "0"));
        TaskExecutionRegistry.register(RunExecutionContext.create("task-1", "1"));
        TaskExecutionRegistry.register(RunExecutionContext.create("task-2", "0"));

        // Act
        int total = TaskExecutionRegistry.getTotalRunCount();

        // Assert
        assertEquals(3, total);
    }

    @Test
    @DisplayName("Should cleanup task")
    void shouldCleanupTask() {
        // Arrange
        TaskExecutionRegistry.register(
                RunExecutionContext.create(TrainingTestConstants.TEST_TASK_ID, "0"));
        TaskExecutionRegistry.register(
                RunExecutionContext.create(TrainingTestConstants.TEST_TASK_ID, "1"));
        TaskExecutionRegistry.register(RunExecutionContext.create("other-task", "0"));

        // Act
        int cleaned = TaskExecutionRegistry.cleanup(TrainingTestConstants.TEST_TASK_ID);

        // Assert
        assertEquals(2, cleaned);
        assertEquals(1, TaskExecutionRegistry.getTaskCount());
        assertEquals(0, TaskExecutionRegistry.getRunCount(TrainingTestConstants.TEST_TASK_ID));
    }

    @Test
    @DisplayName("Should return 0 when cleanup null taskId")
    void shouldReturnZeroWhenCleanupNullTaskId() {
        // Act
        int cleaned = TaskExecutionRegistry.cleanup(null);

        // Assert
        assertEquals(0, cleaned);
    }

    @Test
    @DisplayName("Should clear all data")
    void shouldClearAllData() {
        // Arrange
        TaskExecutionRegistry.register(RunExecutionContext.create("task-1", "0"));
        TaskExecutionRegistry.register(RunExecutionContext.create("task-2", "0"));

        // Act
        TaskExecutionRegistry.clearAll();

        // Assert
        assertEquals(0, TaskExecutionRegistry.getTaskCount());
        assertEquals(0, TaskExecutionRegistry.getTotalRunCount());
    }

    @Test
    @DisplayName("Should get stats")
    void shouldGetStats() {
        // Arrange
        TaskExecutionRegistry.register(RunExecutionContext.create("task-1", "0"));
        TaskExecutionRegistry.register(RunExecutionContext.create("task-1", "1"));
        TaskExecutionRegistry.register(RunExecutionContext.create("task-2", "0"));
        TaskExecutionRegistry.register(RunExecutionContext.create("task-2", "1"));
        TaskExecutionRegistry.register(RunExecutionContext.create("task-2", "2"));

        // Act
        TaskExecutionRegistry.RegistryStats stats = TaskExecutionRegistry.getStats();

        // Assert
        assertEquals(2, stats.getTaskCount());
        assertEquals(5, stats.getTotalRunCount());
        assertEquals(2.5, stats.getAverageRunsPerTask(), 0.001);
    }

    @Test
    @DisplayName("Should return 0 average when no tasks")
    void shouldReturnZeroAverageWhenNoTasks() {
        // Act
        TaskExecutionRegistry.RegistryStats stats = TaskExecutionRegistry.getStats();

        // Assert
        assertEquals(0, stats.getTaskCount());
        assertEquals(0, stats.getTotalRunCount());
        assertEquals(0.0, stats.getAverageRunsPerTask(), 0.001);
    }

    @Test
    @DisplayName("Should produce readable stats toString")
    void shouldProduceReadableStatsToString() {
        // Arrange
        TaskExecutionRegistry.register(RunExecutionContext.create("task-1", "0"));
        TaskExecutionRegistry.register(RunExecutionContext.create("task-1", "1"));

        // Act
        String str = TaskExecutionRegistry.getStats().toString();

        // Assert
        assertTrue(str.contains("tasks=1"));
        assertTrue(str.contains("runs=2"));
    }
}
