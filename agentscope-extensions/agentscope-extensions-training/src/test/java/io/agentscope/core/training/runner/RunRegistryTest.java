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
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.core.training.util.TrainingTestConstants;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RunRegistry Unit Tests")
class RunRegistryTest {

    @BeforeEach
    void setUp() {
        RunRegistry.clearAll();
    }

    @AfterEach
    void tearDown() {
        RunRegistry.clearAll();
    }

    @Test
    @DisplayName("Should allocate run ID starting from 0")
    void shouldAllocateRunIdStartingFromZero() {
        // Act
        String runId = RunRegistry.allocateRunId(TrainingTestConstants.TEST_TASK_ID);

        // Assert
        assertEquals("0", runId);
    }

    @Test
    @DisplayName("Should allocate incrementing run IDs for same task")
    void shouldAllocateIncrementingRunIdsForSameTask() {
        // Act
        String runId1 = RunRegistry.allocateRunId(TrainingTestConstants.TEST_TASK_ID);
        String runId2 = RunRegistry.allocateRunId(TrainingTestConstants.TEST_TASK_ID);
        String runId3 = RunRegistry.allocateRunId(TrainingTestConstants.TEST_TASK_ID);

        // Assert
        assertEquals("0", runId1);
        assertEquals("1", runId2);
        assertEquals("2", runId3);
    }

    @Test
    @DisplayName("Should allocate separate run IDs for different tasks")
    void shouldAllocateSeparateRunIdsForDifferentTasks() {
        // Act
        String runId1 = RunRegistry.allocateRunId("task-1");
        String runId2 = RunRegistry.allocateRunId("task-2");
        String runId3 = RunRegistry.allocateRunId("task-1");

        // Assert
        assertEquals("0", runId1);
        assertEquals("0", runId2);
        assertEquals("1", runId3);
    }

    @Test
    @DisplayName("Should throw exception when taskId is null")
    void shouldThrowExceptionWhenTaskIdIsNull() {
        assertThrows(IllegalArgumentException.class, () -> RunRegistry.allocateRunId(null));
    }

    @Test
    @DisplayName("Should get current run count")
    void shouldGetCurrentRunCount() {
        // Arrange
        RunRegistry.allocateRunId(TrainingTestConstants.TEST_TASK_ID);
        RunRegistry.allocateRunId(TrainingTestConstants.TEST_TASK_ID);
        RunRegistry.allocateRunId(TrainingTestConstants.TEST_TASK_ID);

        // Act
        int count = RunRegistry.getCurrentRunCount(TrainingTestConstants.TEST_TASK_ID);

        // Assert
        assertEquals(3, count);
    }

    @Test
    @DisplayName("Should return 0 for non-existent task")
    void shouldReturnZeroForNonExistentTask() {
        // Act
        int count = RunRegistry.getCurrentRunCount("non-existent-task");

        // Assert
        assertEquals(0, count);
    }

    @Test
    @DisplayName("Should return 0 for null taskId in getCurrentRunCount")
    void shouldReturnZeroForNullTaskIdInGetCurrentRunCount() {
        // Act
        int count = RunRegistry.getCurrentRunCount(null);

        // Assert
        assertEquals(0, count);
    }

    @Test
    @DisplayName("Should cleanup task")
    void shouldCleanupTask() {
        // Arrange
        RunRegistry.allocateRunId(TrainingTestConstants.TEST_TASK_ID);
        RunRegistry.allocateRunId(TrainingTestConstants.TEST_TASK_ID);

        // Act
        RunRegistry.cleanup(TrainingTestConstants.TEST_TASK_ID);

        // Assert
        assertEquals(0, RunRegistry.getCurrentRunCount(TrainingTestConstants.TEST_TASK_ID));
    }

    @Test
    @DisplayName("Should handle cleanup for null taskId")
    void shouldHandleCleanupForNullTaskId() {
        // Act - should not throw
        RunRegistry.cleanup(null);

        // Assert
        assertEquals(0, RunRegistry.size());
    }

    @Test
    @DisplayName("Should clear all tasks")
    void shouldClearAllTasks() {
        // Arrange
        RunRegistry.allocateRunId("task-1");
        RunRegistry.allocateRunId("task-2");
        RunRegistry.allocateRunId("task-3");

        // Act
        RunRegistry.clearAll();

        // Assert
        assertEquals(0, RunRegistry.size());
    }

    @Test
    @DisplayName("Should return correct size")
    void shouldReturnCorrectSize() {
        // Arrange
        RunRegistry.allocateRunId("task-1");
        RunRegistry.allocateRunId("task-2");
        RunRegistry.allocateRunId("task-3");

        // Act
        int size = RunRegistry.size();

        // Assert
        assertEquals(3, size);
    }

    @Test
    @DisplayName("Should allocate run IDs thread-safely")
    void shouldAllocateRunIdsThreadSafely() throws InterruptedException {
        // Arrange
        int threadCount = 10;
        int allocationsPerThread = 100;
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        Set<String> allRunIds = java.util.Collections.synchronizedSet(new HashSet<>());

        // Act
        for (int i = 0; i < threadCount; i++) {
            executor.submit(
                    () -> {
                        for (int j = 0; j < allocationsPerThread; j++) {
                            String runId =
                                    RunRegistry.allocateRunId(TrainingTestConstants.TEST_TASK_ID);
                            allRunIds.add(runId);
                        }
                        latch.countDown();
                    });
        }
        latch.await();
        executor.shutdown();

        // Assert - all run IDs should be unique
        assertEquals(threadCount * allocationsPerThread, allRunIds.size());
        assertEquals(
                threadCount * allocationsPerThread,
                RunRegistry.getCurrentRunCount(TrainingTestConstants.TEST_TASK_ID));
    }
}
