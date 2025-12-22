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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class DefaultTaskManagerTest {

    private DefaultTaskManager taskManager;
    private DefaultTaskManager.TaskOperations mockOperations;

    private Task createTask(String taskId, TaskStatus status) {
        return Task.builder()
                .taskId(taskId)
                .status(status)
                .statusMessage("Test message")
                .createdAt(Instant.now())
                .lastUpdatedAt(Instant.now())
                .ttl(60000L)
                .pollInterval(5000L)
                .build();
    }

    @BeforeEach
    void setUp() {
        mockOperations = mock(DefaultTaskManager.TaskOperations.class);
        taskManager = new DefaultTaskManager("test-client", mockOperations);
    }

    @Test
    void testGetTask_Success() {
        Task expectedTask = createTask("task-123", TaskStatus.WORKING);
        when(mockOperations.getTask("task-123")).thenReturn(Mono.just(expectedTask));

        StepVerifier.create(taskManager.getTask("task-123"))
                .expectNext(expectedTask)
                .verifyComplete();

        verify(mockOperations).getTask("task-123");
    }

    @Test
    void testGetTask_CachesResult() {
        Task task = createTask("task-123", TaskStatus.WORKING);
        when(mockOperations.getTask("task-123")).thenReturn(Mono.just(task));

        // First call
        taskManager.getTask("task-123").block();

        // Verify task is cached
        assertEquals(task, taskManager.getCachedTask("task-123"));
    }

    @Test
    void testGetTask_Error() {
        when(mockOperations.getTask("task-123"))
                .thenReturn(Mono.error(new RuntimeException("Task not found")));

        StepVerifier.create(taskManager.getTask("task-123"))
                .expectErrorMessage("Task not found")
                .verify();
    }

    @Test
    void testGetTaskResult_Success() {
        Map<String, Object> resultData = new HashMap<>();
        resultData.put("status", "success");
        resultData.put("data", "test data");

        when(mockOperations.getTaskResult("task-123")).thenReturn(Mono.just(resultData));

        StepVerifier.create(taskManager.getTaskResult("task-123", Map.class))
                .expectNext(resultData)
                .verifyComplete();
    }

    @Test
    void testGetTaskResult_TypeConversion() {
        // Test JSON conversion
        Map<String, Object> resultData = new HashMap<>();
        resultData.put("message", "Hello");

        when(mockOperations.getTaskResult("task-123")).thenReturn(Mono.just(resultData));

        StepVerifier.create(taskManager.getTaskResult("task-123", Map.class))
                .assertNext(
                        result -> {
                            assertNotNull(result);
                            assertEquals("Hello", result.get("message"));
                        })
                .verifyComplete();
    }

    @Test
    void testGetTaskResult_ConversionError() {
        when(mockOperations.getTaskResult("task-123")).thenReturn(Mono.just("invalid-object"));

        StepVerifier.create(taskManager.getTaskResult("task-123", Integer.class))
                .expectError(RuntimeException.class)
                .verify();
    }

    @Test
    void testListTasks_Success() {
        List<Task> tasks =
                Arrays.asList(
                        createTask("task-1", TaskStatus.WORKING),
                        createTask("task-2", TaskStatus.COMPLETED));
        ListTasksResult result = new ListTasksResult(tasks, "next-cursor");

        when(mockOperations.listTasks(null)).thenReturn(Mono.just(result));

        StepVerifier.create(taskManager.listTasks(null))
                .assertNext(
                        r -> {
                            assertEquals(2, r.getTasks().size());
                            assertEquals("next-cursor", r.getNextCursor());
                        })
                .verifyComplete();
    }

    @Test
    void testListTasks_CachesAllTasks() {
        List<Task> tasks =
                Arrays.asList(
                        createTask("task-1", TaskStatus.WORKING),
                        createTask("task-2", TaskStatus.COMPLETED));
        ListTasksResult result = new ListTasksResult(tasks, null);

        when(mockOperations.listTasks(null)).thenReturn(Mono.just(result));

        taskManager.listTasks(null).block();

        // Verify both tasks are cached
        assertNotNull(taskManager.getCachedTask("task-1"));
        assertNotNull(taskManager.getCachedTask("task-2"));
    }

    @Test
    void testListTasks_WithCursor() {
        List<Task> tasks = Arrays.asList(createTask("task-3", TaskStatus.WORKING));
        ListTasksResult result = new ListTasksResult(tasks, null);

        when(mockOperations.listTasks("cursor-123")).thenReturn(Mono.just(result));

        StepVerifier.create(taskManager.listTasks("cursor-123"))
                .assertNext(r -> assertEquals(1, r.getTasks().size()))
                .verifyComplete();

        verify(mockOperations).listTasks("cursor-123");
    }

    @Test
    void testCancelTask_Success() {
        Task cancelledTask = createTask("task-123", TaskStatus.CANCELLED);
        when(mockOperations.cancelTask("task-123")).thenReturn(Mono.just(cancelledTask));

        StepVerifier.create(taskManager.cancelTask("task-123"))
                .assertNext(task -> assertEquals(TaskStatus.CANCELLED, task.getStatus()))
                .verifyComplete();
    }

    @Test
    void testCancelTask_UpdatesCache() {
        Task cancelledTask = createTask("task-123", TaskStatus.CANCELLED);
        when(mockOperations.cancelTask("task-123")).thenReturn(Mono.just(cancelledTask));

        taskManager.cancelTask("task-123").block();

        Task cachedTask = taskManager.getCachedTask("task-123");
        assertNotNull(cachedTask);
        assertEquals(TaskStatus.CANCELLED, cachedTask.getStatus());
    }

    @Test
    void testRegisterTaskStatusListener() {
        AtomicInteger callCount = new AtomicInteger(0);
        TaskManager.TaskStatusListener listener = task -> callCount.incrementAndGet();

        taskManager.registerTaskStatusListener(listener);

        // Trigger a notification
        Task task = createTask("task-123", TaskStatus.COMPLETED);
        taskManager.handleTaskStatusNotification(task);

        assertEquals(1, callCount.get());
    }

    @Test
    void testRegisterTaskStatusListener_MultipleListeners() {
        AtomicInteger callCount1 = new AtomicInteger(0);
        AtomicInteger callCount2 = new AtomicInteger(0);

        TaskManager.TaskStatusListener listener1 = task -> callCount1.incrementAndGet();
        TaskManager.TaskStatusListener listener2 = task -> callCount2.incrementAndGet();

        taskManager.registerTaskStatusListener(listener1);
        taskManager.registerTaskStatusListener(listener2);

        Task task = createTask("task-123", TaskStatus.COMPLETED);
        taskManager.handleTaskStatusNotification(task);

        assertEquals(1, callCount1.get());
        assertEquals(1, callCount2.get());
    }

    @Test
    void testRegisterTaskStatusListener_NullListener() {
        // Should not throw exception
        taskManager.registerTaskStatusListener(null);

        Task task = createTask("task-123", TaskStatus.COMPLETED);
        taskManager.handleTaskStatusNotification(task);
    }

    @Test
    void testRegisterTaskStatusListener_DuplicateListener() {
        AtomicInteger callCount = new AtomicInteger(0);
        TaskManager.TaskStatusListener listener = task -> callCount.incrementAndGet();

        taskManager.registerTaskStatusListener(listener);
        taskManager.registerTaskStatusListener(listener); // Register twice

        Task task = createTask("task-123", TaskStatus.COMPLETED);
        taskManager.handleTaskStatusNotification(task);

        // Should only be called once (no duplicates)
        assertEquals(1, callCount.get());
    }

    @Test
    void testUnregisterTaskStatusListener() {
        AtomicInteger callCount = new AtomicInteger(0);
        TaskManager.TaskStatusListener listener = task -> callCount.incrementAndGet();

        taskManager.registerTaskStatusListener(listener);
        taskManager.unregisterTaskStatusListener(listener);

        Task task = createTask("task-123", TaskStatus.COMPLETED);
        taskManager.handleTaskStatusNotification(task);

        assertEquals(0, callCount.get());
    }

    @Test
    void testUnregisterTaskStatusListener_NotRegistered() {
        TaskManager.TaskStatusListener listener = task -> {};

        // Should not throw exception
        taskManager.unregisterTaskStatusListener(listener);
    }

    @Test
    void testHandleTaskStatusNotification_UpdatesCache() {
        Task task = createTask("task-123", TaskStatus.COMPLETED);

        taskManager.handleTaskStatusNotification(task);

        assertEquals(task, taskManager.getCachedTask("task-123"));
    }

    @Test
    void testHandleTaskStatusNotification_NotifiesListeners() {
        AtomicInteger callCount = new AtomicInteger(0);
        TaskManager.TaskStatusListener listener = task -> callCount.incrementAndGet();

        taskManager.registerTaskStatusListener(listener);

        Task task1 = createTask("task-1", TaskStatus.WORKING);
        Task task2 = createTask("task-2", TaskStatus.COMPLETED);

        taskManager.handleTaskStatusNotification(task1);
        taskManager.handleTaskStatusNotification(task2);

        assertEquals(2, callCount.get());
    }

    @Test
    void testHandleTaskStatusNotification_ListenerException() {
        TaskManager.TaskStatusListener faultyListener =
                task -> {
                    throw new RuntimeException("Listener error");
                };

        AtomicInteger callCount = new AtomicInteger(0);
        TaskManager.TaskStatusListener goodListener = task -> callCount.incrementAndGet();

        taskManager.registerTaskStatusListener(faultyListener);
        taskManager.registerTaskStatusListener(goodListener);

        Task task = createTask("task-123", TaskStatus.COMPLETED);

        // Should not throw exception, and good listener should still be called
        taskManager.handleTaskStatusNotification(task);

        assertEquals(1, callCount.get());
    }

    @Test
    void testGetCachedTask_NotFound() {
        assertNull(taskManager.getCachedTask("non-existent"));
    }

    @Test
    void testGetAllCachedTasks() {
        Task task1 = createTask("task-1", TaskStatus.WORKING);
        Task task2 = createTask("task-2", TaskStatus.COMPLETED);

        taskManager.handleTaskStatusNotification(task1);
        taskManager.handleTaskStatusNotification(task2);

        List<Task> allTasks = taskManager.getAllCachedTasks();

        assertEquals(2, allTasks.size());
        assertTrue(allTasks.contains(task1));
        assertTrue(allTasks.contains(task2));
    }

    @Test
    void testClearCache() {
        Task task = createTask("task-123", TaskStatus.WORKING);
        taskManager.handleTaskStatusNotification(task);

        assertNotNull(taskManager.getCachedTask("task-123"));

        taskManager.clearCache();

        assertNull(taskManager.getCachedTask("task-123"));
        assertTrue(taskManager.getAllCachedTasks().isEmpty());
    }

    @Test
    void testCreateTask_UtilityMethod() {
        Task task =
                DefaultTaskManager.createTask(
                        "task-123",
                        "working",
                        "Processing",
                        "2025-12-17T10:00:00Z",
                        "2025-12-17T10:05:00Z",
                        60000L,
                        5000L);

        assertEquals("task-123", task.getTaskId());
        assertEquals(TaskStatus.WORKING, task.getStatus());
        assertEquals("Processing", task.getStatusMessage());
        assertEquals(60000L, task.getTtl());
        assertEquals(5000L, task.getPollInterval());
    }

    @Test
    void testCreateTask_InvalidStatus() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        DefaultTaskManager.createTask(
                                "task-123",
                                "invalid-status",
                                null,
                                "2025-12-17T10:00:00Z",
                                "2025-12-17T10:05:00Z",
                                null,
                                null));
    }

    @Test
    void testCreateTask_InvalidTimestamp() {
        assertThrows(
                Exception.class,
                () ->
                        DefaultTaskManager.createTask(
                                "task-123",
                                "working",
                                null,
                                "invalid-timestamp",
                                "2025-12-17T10:05:00Z",
                                null,
                                null));
    }
}
