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

import reactor.core.publisher.Mono;

/**
 * Interface for managing MCP tasks.
 *
 * <p>
 * This interface provides methods for interacting with tasks in the MCP
 * protocol,
 * including retrieving task status, getting results, listing tasks, and
 * cancelling tasks.
 *
 * <p>
 * All methods return Mono types for reactive programming support, allowing for
 * asynchronous and non-blocking operations.
 *
 * @see Task
 * @see TaskStatus
 * @see <a href=
 *      "https://modelcontextprotocol.io/specification/draft/basic/utilities/tasks">MCP
 *      Tasks Specification</a>
 */
public interface TaskManager {

    /**
     * Gets the current status and metadata of a task.
     *
     * <p>
     * This method queries the server for the current state of a task.
     * It can be called repeatedly to poll for task completion.
     *
     * @param taskId the unique identifier of the task
     * @return a Mono emitting the task information
     */
    Mono<Task> getTask(String taskId);

    /**
     * Retrieves the result of a completed task.
     *
     * <p>
     * This method should be called when a task reaches a terminal state
     * (completed, failed, or cancelled) or when the task status is input_required.
     *
     * <p>
     * The result type depends on the original request that created the task:
     * <ul>
     * <li>For tool calls: returns CallToolResult</li>
     * <li>For sampling requests: returns appropriate sampling result</li>
     * </ul>
     *
     * @param taskId     the unique identifier of the task
     * @param resultType the expected result type class
     * @param <T>        the type of the result
     * @return a Mono emitting the task result
     */
    <T> Mono<T> getTaskResult(String taskId, Class<T> resultType);

    /**
     * Lists all tasks known to the server.
     *
     * <p>
     * This method supports pagination through the cursor parameter.
     * If the result contains a nextCursor, it can be used to retrieve
     * the next page of results.
     *
     * @param cursor optional cursor for pagination (null for first page)
     * @return a Mono emitting the list of tasks with pagination info
     */
    Mono<ListTasksResult> listTasks(String cursor);

    /**
     * Cancels a running task.
     *
     * <p>
     * This method requests cancellation of a task. The server will attempt
     * to stop the task execution and transition it to the CANCELLED state.
     *
     * <p>
     * Note that cancellation may not be immediate, and some tasks may not
     * be cancellable depending on their current state.
     *
     * @param taskId the unique identifier of the task to cancel
     * @return a Mono emitting the updated task information after cancellation
     */
    Mono<Task> cancelTask(String taskId);

    /**
     * Registers a listener for task status notifications.
     *
     * <p>
     * When registered, the listener will be notified whenever a task's
     * status changes. This allows for event-driven task monitoring instead
     * of polling.
     *
     * @param listener the task status listener
     */
    void registerTaskStatusListener(TaskStatusListener listener);

    /**
     * Unregisters a previously registered task status listener.
     *
     * @param listener the task status listener to remove
     */
    void unregisterTaskStatusListener(TaskStatusListener listener);

    /**
     * Listener interface for task status change notifications.
     */
    @FunctionalInterface
    interface TaskStatusListener {
        /**
         * Called when a task's status changes.
         *
         * @param task the updated task information
         */
        void onTaskStatusChanged(Task task);
    }
}
