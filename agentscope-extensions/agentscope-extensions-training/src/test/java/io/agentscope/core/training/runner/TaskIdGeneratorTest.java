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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TaskIdGenerator Unit Tests")
class TaskIdGeneratorTest {

    @BeforeEach
    void setUp() {
        // Clean up any state before each test
    }

    @AfterEach
    void tearDown() {
        // Clean up after each test
    }

    @Test
    @DisplayName("Should generate task ID with correct prefix")
    void shouldGenerateTaskIdWithCorrectPrefix() {
        // Act
        String taskId = TaskIdGenerator.generate();

        // Assert
        assertNotNull(taskId);
        assertTrue(taskId.startsWith("task-"));
    }

    @Test
    @DisplayName("Should generate unique task IDs")
    void shouldGenerateUniqueTaskIds() {
        // Act
        String taskId1 = TaskIdGenerator.generate();
        String taskId2 = TaskIdGenerator.generate();
        String taskId3 = TaskIdGenerator.generate();

        // Assert
        assertNotEquals(taskId1, taskId2);
        assertNotEquals(taskId2, taskId3);
        assertNotEquals(taskId1, taskId3);
    }

    @Test
    @DisplayName("Should generate task ID with timestamp")
    void shouldGenerateTaskIdWithTimestamp() {
        // Act
        String taskId = TaskIdGenerator.generateWithTimestamp();

        // Assert
        assertNotNull(taskId);
        assertTrue(taskId.startsWith("task-"));
        // Should contain a timestamp (digits) after "task-"
        String[] parts = taskId.split("-");
        assertEquals(3, parts.length);
        assertTrue(parts[1].matches("\\d+"), "Second part should be timestamp digits");
    }

    @Test
    @DisplayName("Should generate unique task IDs with timestamp")
    void shouldGenerateUniqueTaskIdsWithTimestamp() throws InterruptedException {
        // Act
        String taskId1 = TaskIdGenerator.generateWithTimestamp();
        Thread.sleep(10); // Small delay to ensure different timestamps
        String taskId2 = TaskIdGenerator.generateWithTimestamp();

        // Assert
        assertNotEquals(taskId1, taskId2);
    }
}
