/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Default implementation of TaskManager for MCP clients.
 *
 * <p>
 * This implementation provides task management capabilities by delegating
 * to the underlying MCP client's task-related methods. It maintains a cache
 * of task status listeners and handles task status notifications.
 *
 * <p>
 * Note: This is a basic implementation that assumes the MCP SDK provides
 * the necessary task-related methods. If the SDK doesn't support tasks
 * natively,
 * this implementation will need to be extended to handle task operations
 * through
 * custom protocol messages.
 */
public class DefaultTaskManager implements TaskManager {

    private static final Logger logger = LoggerFactory.getLogger(DefaultTaskManager.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final String clientName;
    private final TaskOperations taskOperations;
    private final List<TaskStatusListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, Task> taskCache = new ConcurrentHashMap<>();

    /**
     * Constructs a new DefaultTaskManager.
     *
     * @param clientName     the name of the MCP client
     * @param taskOperations the task operations provider
     */
    public DefaultTaskManager(String clientName, TaskOperations taskOperations) {
        this.clientName = clientName;
        this.taskOperations = taskOperations;
    }

    @Override
    public Mono<Task> getTask(String taskId) {
        logger.debug("Getting task '{}' from client '{}'", taskId, clientName);

        return taskOperations
                .getTask(taskId)
                .doOnNext(
                        task -> {
                            taskCache.put(taskId, task);
                            notifyListeners(task);
                        })
                .doOnError(
                        e -> logger.error("Failed to get task '{}': {}", taskId, e.getMessage()));
    }

    @Override
    public <T> Mono<T> getTaskResult(String taskId, Class<T> resultType) {
        logger.debug("Getting result for task '{}' from client '{}'", taskId, clientName);

        return taskOperations
                .getTaskResult(taskId)
                .map(
                        result -> {
                            try {
                                // Convert the result to the expected type
                                if (resultType.isInstance(result)) {
                                    return resultType.cast(result);
                                }
                                // Try JSON conversion if direct cast fails
                                JsonNode jsonNode = objectMapper.valueToTree(result);
                                return objectMapper.treeToValue(jsonNode, resultType);
                            } catch (Exception e) {
                                throw new RuntimeException(
                                        "Failed to convert task result to " + resultType.getName(),
                                        e);
                            }
                        })
                .doOnError(
                        e ->
                                logger.error(
                                        "Failed to get result for task '{}': {}",
                                        taskId,
                                        e.getMessage()));
    }

    @Override
    public Mono<ListTasksResult> listTasks(String cursor) {
        logger.debug("Listing tasks from client '{}' with cursor '{}'", clientName, cursor);

        return taskOperations
                .listTasks(cursor)
                .doOnNext(
                        result -> {
                            // Cache all tasks
                            result.getTasks()
                                    .forEach(task -> taskCache.put(task.getTaskId(), task));
                        })
                .doOnError(e -> logger.error("Failed to list tasks: {}", e.getMessage()));
    }

    @Override
    public Mono<Task> cancelTask(String taskId) {
        logger.debug("Cancelling task '{}' on client '{}'", taskId, clientName);

        return taskOperations
                .cancelTask(taskId)
                .doOnNext(
                        task -> {
                            taskCache.put(taskId, task);
                            notifyListeners(task);
                        })
                .doOnError(
                        e ->
                                logger.error(
                                        "Failed to cancel task '{}': {}", taskId, e.getMessage()));
    }

    @Override
    public void registerTaskStatusListener(TaskStatusListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
            logger.debug("Registered task status listener for client '{}'", clientName);
        }
    }

    @Override
    public void unregisterTaskStatusListener(TaskStatusListener listener) {
        if (listener != null && listeners.remove(listener)) {
            logger.debug("Unregistered task status listener for client '{}'", clientName);
        }
    }

    /**
     * Handles incoming task status notifications.
     *
     * <p>
     * This method should be called by the MCP client when it receives
     * a notifications/tasks/status message from the server.
     *
     * @param task the updated task information
     */
    public void handleTaskStatusNotification(Task task) {
        logger.debug(
                "Received task status notification for task '{}': {}",
                task.getTaskId(),
                task.getStatus());
        taskCache.put(task.getTaskId(), task);
        notifyListeners(task);
    }

    /**
     * Notifies all registered listeners of a task status change.
     *
     * @param task the updated task
     */
    private void notifyListeners(Task task) {
        for (TaskStatusListener listener : listeners) {
            try {
                listener.onTaskStatusChanged(task);
            } catch (Exception e) {
                logger.error("Error notifying task status listener", e);
            }
        }
    }

    /**
     * Gets a cached task by ID.
     *
     * @param taskId the task ID
     * @return the cached task, or null if not found
     */
    public Task getCachedTask(String taskId) {
        return taskCache.get(taskId);
    }

    /**
     * Gets all cached tasks.
     *
     * @return a list of all cached tasks
     */
    public List<Task> getAllCachedTasks() {
        return new ArrayList<>(taskCache.values());
    }

    /**
     * Clears the task cache.
     */
    public void clearCache() {
        taskCache.clear();
        logger.debug("Cleared task cache for client '{}'", clientName);
    }

    /**
     * Interface for task operations that must be provided by the MCP client.
     */
    public interface TaskOperations {
        /**
         * Gets a task by ID.
         *
         * @param taskId the task ID
         * @return a Mono emitting the task
         */
        Mono<Task> getTask(String taskId);

        /**
         * Gets the result of a task.
         *
         * @param taskId the task ID
         * @return a Mono emitting the task result
         */
        Mono<Object> getTaskResult(String taskId);

        /**
         * Lists tasks with optional pagination.
         *
         * @param cursor optional cursor for pagination
         * @return a Mono emitting the list result
         */
        Mono<ListTasksResult> listTasks(String cursor);

        /**
         * Cancels a task.
         *
         * @param taskId the task ID
         * @return a Mono emitting the updated task
         */
        Mono<Task> cancelTask(String taskId);
    }

    /**
     * Creates a Task from a raw JSON response.
     *
     * <p>
     * This utility method helps convert MCP protocol responses into Task objects.
     *
     * @param taskId        the task ID
     * @param status        the status string
     * @param statusMessage optional status message
     * @param createdAt     creation timestamp string (ISO 8601)
     * @param lastUpdatedAt last update timestamp string (ISO 8601)
     * @param ttl           time-to-live in milliseconds
     * @param pollInterval  poll interval in milliseconds
     * @return a new Task instance
     */
    public static Task createTask(
            String taskId,
            String status,
            String statusMessage,
            String createdAt,
            String lastUpdatedAt,
            Long ttl,
            Long pollInterval) {
        return Task.builder()
                .taskId(taskId)
                .status(TaskStatus.fromValue(status))
                .statusMessage(statusMessage)
                .createdAt(Instant.parse(createdAt))
                .lastUpdatedAt(Instant.parse(lastUpdatedAt))
                .ttl(ttl)
                .pollInterval(pollInterval)
                .build();
    }
}
