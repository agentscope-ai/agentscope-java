/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.tool.mcp.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class TaskTest {

    private static final String TASK_ID = "test-task-123";
    private static final TaskStatus STATUS = TaskStatus.WORKING;
    private static final String STATUS_MESSAGE = "Processing request";
    private static final Instant CREATED_AT = Instant.parse("2025-12-17T10:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2025-12-17T10:05:00Z");
    private static final Long TTL = 60000L;
    private static final Long POLL_INTERVAL = 5000L;

    @Test
    void testBuilder_AllFields() {
        Task task =
                Task.builder()
                        .taskId(TASK_ID)
                        .status(STATUS)
                        .statusMessage(STATUS_MESSAGE)
                        .createdAt(CREATED_AT)
                        .lastUpdatedAt(UPDATED_AT)
                        .ttl(TTL)
                        .pollInterval(POLL_INTERVAL)
                        .build();

        assertEquals(TASK_ID, task.getTaskId());
        assertEquals(STATUS, task.getStatus());
        assertEquals(STATUS_MESSAGE, task.getStatusMessage());
        assertEquals(CREATED_AT, task.getCreatedAt());
        assertEquals(UPDATED_AT, task.getLastUpdatedAt());
        assertEquals(TTL, task.getTtl());
        assertEquals(POLL_INTERVAL, task.getPollInterval());
    }

    @Test
    void testBuilder_MinimalFields() {
        Task task =
                Task.builder()
                        .taskId(TASK_ID)
                        .status(STATUS)
                        .createdAt(CREATED_AT)
                        .lastUpdatedAt(UPDATED_AT)
                        .build();

        assertEquals(TASK_ID, task.getTaskId());
        assertEquals(STATUS, task.getStatus());
        assertNull(task.getStatusMessage());
        assertEquals(CREATED_AT, task.getCreatedAt());
        assertEquals(UPDATED_AT, task.getLastUpdatedAt());
        assertNull(task.getTtl());
        assertNull(task.getPollInterval());
    }

    @Test
    void testConstructor_NullTaskId() {
        assertThrows(
                NullPointerException.class,
                () ->
                        new Task(
                                null,
                                STATUS,
                                STATUS_MESSAGE,
                                CREATED_AT,
                                UPDATED_AT,
                                TTL,
                                POLL_INTERVAL));
    }

    @Test
    void testConstructor_NullStatus() {
        assertThrows(
                NullPointerException.class,
                () ->
                        new Task(
                                TASK_ID,
                                null,
                                STATUS_MESSAGE,
                                CREATED_AT,
                                UPDATED_AT,
                                TTL,
                                POLL_INTERVAL));
    }

    @Test
    void testConstructor_NullCreatedAt() {
        assertThrows(
                NullPointerException.class,
                () ->
                        new Task(
                                TASK_ID,
                                STATUS,
                                STATUS_MESSAGE,
                                null,
                                UPDATED_AT,
                                TTL,
                                POLL_INTERVAL));
    }

    @Test
    void testConstructor_NullLastUpdatedAt() {
        assertThrows(
                NullPointerException.class,
                () ->
                        new Task(
                                TASK_ID,
                                STATUS,
                                STATUS_MESSAGE,
                                CREATED_AT,
                                null,
                                TTL,
                                POLL_INTERVAL));
    }

    @Test
    void testIsTerminal_CompletedTask() {
        Task task =
                Task.builder()
                        .taskId(TASK_ID)
                        .status(TaskStatus.COMPLETED)
                        .createdAt(CREATED_AT)
                        .lastUpdatedAt(UPDATED_AT)
                        .build();

        assertTrue(task.isTerminal());
    }

    @Test
    void testIsTerminal_FailedTask() {
        Task task =
                Task.builder()
                        .taskId(TASK_ID)
                        .status(TaskStatus.FAILED)
                        .createdAt(CREATED_AT)
                        .lastUpdatedAt(UPDATED_AT)
                        .build();

        assertTrue(task.isTerminal());
    }

    @Test
    void testIsTerminal_CancelledTask() {
        Task task =
                Task.builder()
                        .taskId(TASK_ID)
                        .status(TaskStatus.CANCELLED)
                        .createdAt(CREATED_AT)
                        .lastUpdatedAt(UPDATED_AT)
                        .build();

        assertTrue(task.isTerminal());
    }

    @Test
    void testIsTerminal_WorkingTask() {
        Task task =
                Task.builder()
                        .taskId(TASK_ID)
                        .status(TaskStatus.WORKING)
                        .createdAt(CREATED_AT)
                        .lastUpdatedAt(UPDATED_AT)
                        .build();

        assertFalse(task.isTerminal());
    }

    @Test
    void testEquals_SameTasks() {
        Task task1 =
                Task.builder()
                        .taskId(TASK_ID)
                        .status(STATUS)
                        .statusMessage(STATUS_MESSAGE)
                        .createdAt(CREATED_AT)
                        .lastUpdatedAt(UPDATED_AT)
                        .ttl(TTL)
                        .pollInterval(POLL_INTERVAL)
                        .build();

        Task task2 =
                Task.builder()
                        .taskId(TASK_ID)
                        .status(STATUS)
                        .statusMessage(STATUS_MESSAGE)
                        .createdAt(CREATED_AT)
                        .lastUpdatedAt(UPDATED_AT)
                        .ttl(TTL)
                        .pollInterval(POLL_INTERVAL)
                        .build();

        assertEquals(task1, task2);
        assertEquals(task1.hashCode(), task2.hashCode());
    }

    @Test
    void testEquals_DifferentTaskId() {
        Task task1 =
                Task.builder()
                        .taskId("task-1")
                        .status(STATUS)
                        .createdAt(CREATED_AT)
                        .lastUpdatedAt(UPDATED_AT)
                        .build();

        Task task2 =
                Task.builder()
                        .taskId("task-2")
                        .status(STATUS)
                        .createdAt(CREATED_AT)
                        .lastUpdatedAt(UPDATED_AT)
                        .build();

        assertNotEquals(task1, task2);
    }

    @Test
    void testEquals_DifferentStatus() {
        Task task1 =
                Task.builder()
                        .taskId(TASK_ID)
                        .status(TaskStatus.WORKING)
                        .createdAt(CREATED_AT)
                        .lastUpdatedAt(UPDATED_AT)
                        .build();

        Task task2 =
                Task.builder()
                        .taskId(TASK_ID)
                        .status(TaskStatus.COMPLETED)
                        .createdAt(CREATED_AT)
                        .lastUpdatedAt(UPDATED_AT)
                        .build();

        assertNotEquals(task1, task2);
    }

    @Test
    void testToString_ContainsAllFields() {
        Task task =
                Task.builder()
                        .taskId(TASK_ID)
                        .status(STATUS)
                        .statusMessage(STATUS_MESSAGE)
                        .createdAt(CREATED_AT)
                        .lastUpdatedAt(UPDATED_AT)
                        .ttl(TTL)
                        .pollInterval(POLL_INTERVAL)
                        .build();

        String toString = task.toString();
        assertTrue(toString.contains(TASK_ID));
        assertTrue(toString.contains(STATUS.toString()));
        assertTrue(toString.contains(STATUS_MESSAGE));
    }
}
