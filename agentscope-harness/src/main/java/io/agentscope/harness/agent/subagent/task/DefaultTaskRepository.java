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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * In-memory {@link TaskRepository} backed by a cached daemon thread pool.
 *
 * <p>AgentStateStore IDs are ignored — all tasks share a single flat map. Only supports
 * {@link TaskRunSpec.LocalTaskRunSpec}; remote-protocol tasks throw {@code UnsupportedOperationException}.
 *
 * <p><b>Deprecated for production use.</b> {@link HarnessAgent} always constructs a workspace
 * (defaulting to {@code ${cwd}/.agentscope/workspace}) and now resolves the default task
 * repository to {@link WorkspaceTaskRepository}, which is a behavioral superset (handles both
 * local and remote specs, persists state across restarts, and supports cross-node visibility
 * via shared filesystems). This class is retained for test scaffolding and for callers that
 * use the subagent middlewares standalone (without a {@code WorkspaceManager}). New code should
 * use {@link WorkspaceTaskRepository} directly.
 *
 * @deprecated Use {@link WorkspaceTaskRepository}. Slated for removal in a future major release.
 */
@Deprecated(since = "2.1.0")
public class DefaultTaskRepository implements TaskRepository {

    private final Map<String, BackgroundTask> tasks = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final boolean ownsExecutor;

    public DefaultTaskRepository() {
        this(
                Executors.newCachedThreadPool(
                        r -> {
                            Thread t = new Thread(r);
                            t.setDaemon(true);
                            t.setName("subagent-task-" + t.getId());
                            return t;
                        }),
                true);
    }

    public DefaultTaskRepository(ExecutorService executor) {
        this(executor, false);
    }

    private DefaultTaskRepository(ExecutorService executor, boolean ownsExecutor) {
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
    }

    @Override
    public BackgroundTask getTask(RuntimeContext rc, String sessionId, String taskId) {
        return tasks.get(taskId);
    }

    @Override
    public BackgroundTask putTask(
            RuntimeContext rc,
            String taskId,
            String subAgentId,
            String sessionId,
            TaskRunSpec spec) {
        if (!(spec instanceof TaskRunSpec.LocalTaskRunSpec local)) {
            throw new UnsupportedOperationException(
                    "DefaultTaskRepository only supports LocalTaskRunSpec; use"
                            + " WorkspaceTaskRepository for remote tasks.");
        }
        CompletableFuture<String> future =
                CompletableFuture.supplyAsync(local.execution(), executor);
        BackgroundTask task = new BackgroundTask(taskId, subAgentId, future);
        tasks.put(taskId, task);
        return task;
    }

    @Override
    public void removeTask(RuntimeContext rc, String sessionId, String taskId) {
        tasks.remove(taskId);
    }

    @Override
    public void clear() {
        tasks.clear();
    }

    @Override
    public Collection<BackgroundTask> listTasks(
            RuntimeContext rc, String sessionId, TaskStatus filter) {
        if (filter == null) {
            return List.copyOf(tasks.values());
        }
        List<BackgroundTask> result = new ArrayList<>();
        for (BackgroundTask task : tasks.values()) {
            if (task.getTaskStatus() == filter) {
                result.add(task);
            }
        }
        return result;
    }

    @Override
    public boolean cancelTask(RuntimeContext rc, String sessionId, String taskId) {
        BackgroundTask task = tasks.get(taskId);
        if (task == null) {
            return false;
        }
        task.cancel(true);
        return true;
    }

    public void clearCompletedTasks() {
        tasks.entrySet().removeIf(e -> e.getValue().isCompleted());
    }

    /** Shuts down the thread pool if this repository owns it. */
    public void shutdown() {
        if (ownsExecutor && executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
