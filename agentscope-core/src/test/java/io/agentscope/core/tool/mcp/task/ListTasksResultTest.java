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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class ListTasksResultTest {

    private Task createTask(String taskId, TaskStatus status) {
        return Task.builder()
                .taskId(taskId)
                .status(status)
                .createdAt(Instant.now())
                .lastUpdatedAt(Instant.now())
                .build();
    }

    @Test
    void testConstructor_WithTasksAndCursor() {
        List<Task> tasks =
                Arrays.asList(
                        createTask("task-1", TaskStatus.WORKING),
                        createTask("task-2", TaskStatus.COMPLETED));
        String cursor = "next-page-cursor";

        ListTasksResult result = new ListTasksResult(tasks, cursor);

        assertEquals(tasks, result.getTasks());
        assertEquals(cursor, result.getNextCursor());
    }

    @Test
    void testConstructor_WithTasksNoCursor() {
        List<Task> tasks = Arrays.asList(createTask("task-1", TaskStatus.WORKING));

        ListTasksResult result = new ListTasksResult(tasks, null);

        assertEquals(tasks, result.getTasks());
        assertNull(result.getNextCursor());
    }

    @Test
    void testConstructor_EmptyTasks() {
        List<Task> tasks = Collections.emptyList();

        ListTasksResult result = new ListTasksResult(tasks, null);

        assertTrue(result.getTasks().isEmpty());
        assertNull(result.getNextCursor());
    }

    @Test
    void testConstructor_NullTasks() {
        assertThrows(NullPointerException.class, () -> new ListTasksResult(null, "cursor"));
    }

    @Test
    void testHasMore_WithCursor() {
        List<Task> tasks = Arrays.asList(createTask("task-1", TaskStatus.WORKING));
        ListTasksResult result = new ListTasksResult(tasks, "next-cursor");

        assertTrue(result.hasMore());
    }

    @Test
    void testHasMore_NullCursor() {
        List<Task> tasks = Arrays.asList(createTask("task-1", TaskStatus.WORKING));
        ListTasksResult result = new ListTasksResult(tasks, null);

        assertFalse(result.hasMore());
    }

    @Test
    void testHasMore_EmptyCursor() {
        List<Task> tasks = Arrays.asList(createTask("task-1", TaskStatus.WORKING));
        ListTasksResult result = new ListTasksResult(tasks, "");

        assertFalse(result.hasMore());
    }

    @Test
    void testEquals_SameResults() {
        List<Task> tasks = Arrays.asList(createTask("task-1", TaskStatus.WORKING));
        ListTasksResult result1 = new ListTasksResult(tasks, "cursor");
        ListTasksResult result2 = new ListTasksResult(tasks, "cursor");

        assertEquals(result1, result2);
        assertEquals(result1.hashCode(), result2.hashCode());
    }

    @Test
    void testEquals_DifferentCursor() {
        List<Task> tasks = Arrays.asList(createTask("task-1", TaskStatus.WORKING));
        ListTasksResult result1 = new ListTasksResult(tasks, "cursor1");
        ListTasksResult result2 = new ListTasksResult(tasks, "cursor2");

        assertNotEquals(result1, result2);
    }

    @Test
    void testEquals_DifferentTasks() {
        ListTasksResult result1 =
                new ListTasksResult(
                        Arrays.asList(createTask("task-1", TaskStatus.WORKING)), "cursor");
        ListTasksResult result2 =
                new ListTasksResult(
                        Arrays.asList(createTask("task-2", TaskStatus.COMPLETED)), "cursor");

        assertNotEquals(result1, result2);
    }

    @Test
    void testToString_ContainsTasksAndCursor() {
        List<Task> tasks = Arrays.asList(createTask("task-1", TaskStatus.WORKING));
        String cursor = "next-cursor";
        ListTasksResult result = new ListTasksResult(tasks, cursor);

        String toString = result.toString();
        assertTrue(toString.contains("tasks"));
        assertTrue(toString.contains(cursor));
    }

    @Test
    void testGetTasks_ReturnsUnmodifiableList() {
        List<Task> tasks = Arrays.asList(createTask("task-1", TaskStatus.WORKING));
        ListTasksResult result = new ListTasksResult(tasks, null);

        List<Task> returnedTasks = result.getTasks();

        // Verify the list contains the same elements
        assertEquals(tasks, returnedTasks);

        // Verify the list is unmodifiable
        assertThrows(
                UnsupportedOperationException.class,
                () -> {
                    returnedTasks.add(createTask("task-2", TaskStatus.COMPLETED));
                });
    }
}
