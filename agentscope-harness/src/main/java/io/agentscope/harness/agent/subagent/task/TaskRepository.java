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
 * Repository for managing background subagent tasks. Supports async execution with retrieval by
 * task ID, listing, and cancellation.
 */
public interface TaskRepository {

    /** Retrieve a background task by its ID, or null if not found. */
    BackgroundTask getTask(String taskId);

    /**
     * Submit a new background task; the supplier runs asynchronously.
     *
     * @param taskId unique identifier for the task
     * @param agentId the subagent type that is executing this task
     * @param taskExecution the work to execute asynchronously
     * @return the created background task
     */
    BackgroundTask putTask(String taskId, String agentId, Supplier<String> taskExecution);

    void removeTask(String taskId);

    void clear();

    /**
     * List all tracked tasks, optionally filtered by status.
     *
     * @param filter if non-null, only return tasks with this status; null returns all tasks
     */
    Collection<BackgroundTask> listTasks(TaskStatus filter);

    /**
     * Cancel a running task by its ID.
     *
     * @return true if the task was found and cancellation was attempted
     */
    boolean cancelTask(String taskId);
}
