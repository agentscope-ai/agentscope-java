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

import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Workspace-backed {@link TaskRepository} that uses {@link WorkspaceManager} as the authoritative
 * truth source for task state, while maintaining in-memory {@link BackgroundTask} handles as a
 * local performance overlay for tasks running on the current node.
 *
 * <p>Storage layout: {@code agents/<parentAgentId>/tasks/<sessionId>.json} — a JSON map of
 * {@code taskId → TaskRecord}, consistent with how sessions are stored. In distributed deployments
 * using {@code RemoteFilesystemSpec}, this path is automatically routed to shared storage, making
 * task state visible to any node.
 *
 * <p>The in-memory {@code localTasks} map is keyed by {@code "<sessionId>:<taskId>"} to preserve
 * session isolation when multiple sessions coexist in the same process.
 *
 * <p>Distributed semantics:
 *
 * <ul>
 *   <li>Task execution is sticky to the originating node (the node that called {@link #putTask}).
 *   <li>Any node can read task status via {@link #getTask} or {@link #listTasks} by falling back
 *       to workspace records when no local future exists.
 *   <li>{@code block=true} on a non-originating node degrades gracefully to reading the latest
 *       persisted terminal state without hanging.
 *   <li>Cancellation sets a {@link TaskRecord#isCancelRequested()} flag in workspace storage;
 *       the originating node checks this flag before invoking the subagent for best-effort cancel.
 * </ul>
 */
public class WorkspaceTaskRepository implements TaskRepository {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceTaskRepository.class);

    private final WorkspaceManager workspaceManager;
    private final String parentAgentId;

    /**
     * In-memory local task handles. Keyed by {@code "<sessionId>:<taskId>"} to provide session
     * isolation when multiple parent sessions are active in the same JVM process.
     */
    private final Map<String, BackgroundTask> localTasks = new ConcurrentHashMap<>();

    private final ExecutorService executor;
    private final boolean ownsExecutor;

    public WorkspaceTaskRepository(WorkspaceManager workspaceManager, String parentAgentId) {
        this(
                workspaceManager,
                parentAgentId,
                Executors.newCachedThreadPool(
                        r -> {
                            Thread t = new Thread(r);
                            t.setDaemon(true);
                            t.setName("ws-task-" + t.getId());
                            return t;
                        }),
                true);
    }

    public WorkspaceTaskRepository(
            WorkspaceManager workspaceManager, String parentAgentId, ExecutorService executor) {
        this(workspaceManager, parentAgentId, executor, false);
    }

    private WorkspaceTaskRepository(
            WorkspaceManager workspaceManager,
            String parentAgentId,
            ExecutorService executor,
            boolean ownsExecutor) {
        this.workspaceManager = workspaceManager;
        this.parentAgentId = parentAgentId != null ? parentAgentId : "HarnessAgent";
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
    }

    @Override
    public BackgroundTask putTask(
            String taskId, String subAgentId, String sessionId, Supplier<String> taskExecution) {
        // Write PENDING record first so cancelTask can find the task in workspace immediately
        TaskRecord record = new TaskRecord(taskId, subAgentId, parentAgentId, sessionId, null);
        record.setStatus(TaskStatus.PENDING);
        persistRecord(sessionId, record);

        String localKey = localKey(sessionId, taskId);
        CompletableFuture<String> future =
                CompletableFuture.supplyAsync(
                        () -> {
                            // Check cancel-before-start (cross-node cancellation coordination)
                            Optional<TaskRecord> latest =
                                    workspaceManager.readTaskRecord(
                                            parentAgentId, sessionId, taskId);
                            if (latest.isPresent() && latest.get().isCancelRequested()) {
                                markCancelled(sessionId, taskId);
                                return null;
                            }

                            updateStatus(sessionId, taskId, TaskStatus.RUNNING, null, null);
                            try {
                                String result = taskExecution.get();
                                updateStatus(sessionId, taskId, TaskStatus.COMPLETED, result, null);
                                return result;
                            } catch (Exception e) {
                                String errMsg =
                                        e.getMessage() != null
                                                ? e.getMessage()
                                                : e.getClass().getSimpleName();
                                updateStatus(sessionId, taskId, TaskStatus.FAILED, null, errMsg);
                                throw e instanceof RuntimeException re
                                        ? re
                                        : new RuntimeException(e);
                            }
                        },
                        executor);

        BackgroundTask bgTask = new BackgroundTask(taskId, subAgentId, future);
        localTasks.put(localKey, bgTask);
        return bgTask;
    }

    @Override
    public BackgroundTask getTask(String sessionId, String taskId) {
        BackgroundTask local = localTasks.get(localKey(sessionId, taskId));
        if (local != null) {
            return local;
        }
        // Fall back to workspace record — construct a synthetic completed/failed BackgroundTask
        Optional<TaskRecord> record =
                workspaceManager.readTaskRecord(parentAgentId, sessionId, taskId);
        return record.map(this::syntheticTask).orElse(null);
    }

    @Override
    public Collection<BackgroundTask> listTasks(String sessionId, TaskStatus filter) {
        Collection<TaskRecord> records = workspaceManager.listTaskRecords(parentAgentId, sessionId);

        List<BackgroundTask> result = new ArrayList<>();
        for (TaskRecord wsRecord : records) {
            String key = localKey(sessionId, wsRecord.getTaskId());
            BackgroundTask local = localTasks.get(key);
            // Use local handle if available (live status); otherwise fall back to workspace record.
            // Never override a terminal workspace status with a local RUNNING state from an old
            // handle.
            BackgroundTask effective;
            if (local != null && !wsRecord.getStatus().isTerminal()) {
                effective = local;
            } else {
                effective = syntheticTask(wsRecord);
            }
            if (filter == null || effective.getTaskStatus() == filter) {
                result.add(effective);
            }
        }
        return result;
    }

    @Override
    public boolean cancelTask(String sessionId, String taskId) {
        boolean found = false;

        BackgroundTask local = localTasks.get(localKey(sessionId, taskId));
        if (local != null) {
            local.cancel(true);
            found = true;
        }

        // Always write cancelRequested flag to workspace for cross-node coordination
        Optional<TaskRecord> existing =
                workspaceManager.readTaskRecord(parentAgentId, sessionId, taskId);
        if (existing.isPresent()) {
            TaskRecord record = existing.get();
            record.setCancelRequested(true);
            if (!record.getStatus().isTerminal()) {
                record.setStatus(TaskStatus.CANCELLED);
            }
            persistRecord(sessionId, record);
            return true;
        }

        return found;
    }

    @Override
    public void removeTask(String sessionId, String taskId) {
        localTasks.remove(localKey(sessionId, taskId));
    }

    @Override
    public void clear() {
        localTasks.clear();
    }

    /** Shuts down the executor if owned by this repository. */
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

    // ---- private helpers ----

    private static String localKey(String sessionId, String taskId) {
        String s = sessionId != null ? sessionId : "_";
        return s + ":" + taskId;
    }

    private void persistRecord(String sessionId, TaskRecord record) {
        try {
            workspaceManager.writeTaskRecord(parentAgentId, sessionId, record);
        } catch (Exception e) {
            log.warn(
                    "Failed to persist task record {} for session {}: {}",
                    record.getTaskId(),
                    sessionId,
                    e.getMessage());
        }
    }

    private void updateStatus(
            String sessionId, String taskId, TaskStatus status, String result, String error) {
        Optional<TaskRecord> existing =
                workspaceManager.readTaskRecord(parentAgentId, sessionId, taskId);
        TaskRecord record =
                existing.orElseGet(
                        () -> {
                            TaskRecord r = new TaskRecord();
                            r.setTaskId(taskId);
                            r.setParentAgentId(parentAgentId);
                            r.setParentSessionId(sessionId);
                            return r;
                        });
        record.setStatus(status);
        if (result != null) {
            record.setResult(result);
        }
        if (error != null) {
            record.setErrorMessage(error);
        }
        persistRecord(sessionId, record);
    }

    private void markCancelled(String sessionId, String taskId) {
        updateStatus(sessionId, taskId, TaskStatus.CANCELLED, null, null);
    }

    /**
     * Creates a synthetic {@link BackgroundTask} from a persisted {@link TaskRecord}. The future
     * is already-completed (or failed/cancelled) to reflect the stored terminal status.
     */
    private BackgroundTask syntheticTask(TaskRecord record) {
        CompletableFuture<String> future;
        switch (record.getStatus()) {
            case COMPLETED -> future = CompletableFuture.completedFuture(record.getResult());
            case FAILED -> {
                future = new CompletableFuture<>();
                future.completeExceptionally(
                        new RuntimeException(
                                record.getErrorMessage() != null
                                        ? record.getErrorMessage()
                                        : "Task failed"));
            }
            case CANCELLED -> {
                future = new CompletableFuture<>();
                future.cancel(false);
            }
            default -> {
                // PENDING or RUNNING but no local future — represents a cross-node task.
                // Return an incomplete future so callers see "still running".
                future = new CompletableFuture<>();
            }
        }
        return new BackgroundTask(record.getTaskId(), record.getSubAgentId(), future);
    }
}
