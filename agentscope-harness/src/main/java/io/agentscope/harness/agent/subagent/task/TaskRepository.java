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

import io.agentscope.core.agent.RuntimeContext;
import java.util.Collection;
import java.util.List;

/**
 * Repository for managing background subagent tasks, scoped by session.
 *
 * <p>All operations are scoped to a {@code sessionId} so that tasks from different parent sessions
 * are isolated from one another. Implementations may ignore {@code sessionId} (in-memory stores)
 * or use it to partition durable storage (workspace-backed stores).
 *
 * <p>Every method accepts a {@link RuntimeContext}: implementations that persist task state through
 * a per-user namespaced filesystem must propagate {@code rc} so that writes from concurrent users
 * land in their respective namespaces.
 */
public interface TaskRepository {

    /**
     * Retrieve a background task by session and task ID, or {@code null} if not found.
     *
     * @param rc the current call's runtime context; may be {@link RuntimeContext#empty()}
     * @param sessionId the parent session scope
     * @param taskId unique task identifier
     */
    BackgroundTask getTask(RuntimeContext rc, String sessionId, String taskId);

    /**
     * Submit a new background task according to {@link TaskRunSpec}.
     *
     * <p>Implementations should capture {@code rc} so that the background execution thread can
     * persist task state under the originating user's namespace.
     *
     * @param rc the current call's runtime context; may be {@link RuntimeContext#empty()}
     * @param taskId unique identifier for the task
     * @param subAgentId the subagent type executing this task
     * @param sessionId the parent session scope
     * @param spec local supplier execution or remote HTTP task protocol
     * @return the created background task
     */
    BackgroundTask putTask(
            RuntimeContext rc,
            String taskId,
            String subAgentId,
            String sessionId,
            TaskRunSpec spec);

    /**
     * Remove a task from the repository.
     *
     * @param rc the current call's runtime context; may be {@link RuntimeContext#empty()}
     * @param sessionId the parent session scope
     * @param taskId unique task identifier
     */
    void removeTask(RuntimeContext rc, String sessionId, String taskId);

    /** Clear all tasks across all sessions. */
    void clear();

    /**
     * List all tracked tasks for the given session, optionally filtered by status.
     *
     * @param rc the current call's runtime context; may be {@link RuntimeContext#empty()}
     * @param sessionId the parent session scope
     * @param filter if non-null, only return tasks with this status; null returns all tasks
     */
    Collection<BackgroundTask> listTasks(RuntimeContext rc, String sessionId, TaskStatus filter);

    /**
     * Cancel a running task by session and task ID.
     *
     * @param rc the current call's runtime context; may be {@link RuntimeContext#empty()}
     * @return true if the task was found and cancellation was attempted
     */
    boolean cancelTask(RuntimeContext rc, String sessionId, String taskId);

    // ------------------------------------------------------------------------
    // Phase B-3 — push delivery API.
    //
    // Implementations that persist {@link TaskRecord} should override the three methods below to
    // surface "terminal-but-not-yet-delivered" tasks to the parent agent's reasoning loop. The
    // default no-op implementations preserve pull-only semantics for in-memory repositories
    // where the parent process and any task callers share the same JVM and can rely on direct
    // future completion instead.
    // ------------------------------------------------------------------------

    /**
     * Returns terminal-state tasks for the given session whose completions have not yet been
     * pushed back to the parent agent.
     *
     * <p>Implementations should order the result deterministically (typically by completion time
     * ascending) so the parent agent sees deliveries in the order they actually happened.
     *
     * <p>Default returns an empty list — in-memory repositories do not currently support push.
     */
    default List<TaskDelivery> findPendingDeliveries(RuntimeContext rc, String sessionId) {
        return List.of();
    }

    /**
     * Stamps the given task as delivered so it does not get pushed again. Idempotent: calling
     * twice has no effect after the first call.
     *
     * <p>Default is a no-op.
     */
    default void markDelivered(RuntimeContext rc, String sessionId, String taskId) {
        // no-op
    }

    /**
     * Whether the given task has already been delivered to the parent agent. Used by
     * {@link io.agentscope.harness.agent.middleware.SubagentsMiddleware#buildTaskSummary} to omit
     * already-delivered terminal tasks from the SYSTEM-prompt summary.
     *
     * <p>Default returns {@code false}.
     */
    default boolean isDelivered(RuntimeContext rc, String sessionId, String taskId) {
        return false;
    }
}
