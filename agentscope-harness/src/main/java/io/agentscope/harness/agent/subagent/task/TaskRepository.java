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

import java.util.Collection;
import java.util.function.Supplier;

/**
 * Repository for managing background subagent tasks, scoped by session.
 *
 * <p>All operations are scoped to a {@code sessionId} so that tasks from different parent sessions
 * are isolated from one another. Implementations may ignore {@code sessionId} (in-memory stores)
 * or use it to partition durable storage (workspace-backed stores).
 */
public interface TaskRepository {

    /**
     * Retrieve a background task by session and task ID, or {@code null} if not found.
     *
     * @param sessionId the parent session scope
     * @param taskId unique task identifier
     */
    BackgroundTask getTask(String sessionId, String taskId);

    /**
     * Submit a new background task; the supplier runs asynchronously.
     *
     * @param taskId unique identifier for the task
     * @param subAgentId the subagent type executing this task
     * @param sessionId the parent session scope
     * @param taskExecution the work to execute asynchronously
     * @return the created background task
     */
    BackgroundTask putTask(
            String taskId, String subAgentId, String sessionId, Supplier<String> taskExecution);

    /**
     * Remove a task from the repository.
     *
     * @param sessionId the parent session scope
     * @param taskId unique task identifier
     */
    void removeTask(String sessionId, String taskId);

    /** Clear all tasks across all sessions. */
    void clear();

    /**
     * List all tracked tasks for the given session, optionally filtered by status.
     *
     * @param sessionId the parent session scope
     * @param filter if non-null, only return tasks with this status; null returns all tasks
     */
    Collection<BackgroundTask> listTasks(String sessionId, TaskStatus filter);

    /**
     * Cancel a running task by session and task ID.
     *
     * @return true if the task was found and cancellation was attempted
     */
    boolean cancelTask(String sessionId, String taskId);
}
