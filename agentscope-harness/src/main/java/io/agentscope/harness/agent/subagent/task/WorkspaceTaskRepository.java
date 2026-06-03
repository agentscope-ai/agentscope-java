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
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
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
 *   <li>Remote {@link TaskRunSpec.RemoteTaskRunSpec} tasks use {@link AgentProtocolTaskClient} and
 *       persist {@link TaskRecord#getRemoteBaseUrl()} for cross-node resume.
 * </ul>
 */
public class WorkspaceTaskRepository implements TaskRepository {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceTaskRepository.class);

    private static final String TRANSPORT_AGENT_PROTOCOL = "agent-protocol";

    /**
     * How often (in seconds) the heartbeat refreshes {@code lastUpdatedAt} for live local tasks.
     * Must be well below {@link #ORPHAN_TIMEOUT_MINUTES} to avoid false orphan detection.
     */
    static final int HEARTBEAT_INTERVAL_SECONDS = 30;

    /**
     * How long (in minutes) a non-terminal local task may remain un-heartbeated before the sweeper
     * marks it {@link TaskStatus#FAILED}. Should be several multiples of
     * {@link #HEARTBEAT_INTERVAL_SECONDS} to tolerate transient delays.
     */
    static final int ORPHAN_TIMEOUT_MINUTES = 10;

    /** How often (in minutes) the orphan sweeper scans all workspace task records. */
    static final int SWEEP_INTERVAL_MINUTES = 5;

    private final WorkspaceManager workspaceManager;
    private final String parentAgentId;
    private final AgentProtocolTaskClient protocolClient;

    /**
     * In-memory local task handles. Keyed by {@code "<sessionId>:<taskId>"} to provide session
     * isolation when multiple parent sessions are active in the same JVM process.
     */
    private final Map<String, BackgroundTask> localTasks = new ConcurrentHashMap<>();

    /**
     * Maps {@code localKey} → {@code sessionId} so the heartbeat can iterate running tasks without
     * needing to parse the composite key string.
     */
    private final Map<String, String> localTaskSessionIds = new ConcurrentHashMap<>();

    /**
     * Maps {@code localKey} → {@link RuntimeContext} captured at {@code putTask} time so the
     * background future and the heartbeat thread can persist task state under the originating
     * user's namespace.
     */
    private final Map<String, RuntimeContext> localTaskContexts = new ConcurrentHashMap<>();

    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private final ScheduledExecutorService maintenanceScheduler;

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
                true,
                true);
    }

    public WorkspaceTaskRepository(
            WorkspaceManager workspaceManager, String parentAgentId, ExecutorService executor) {
        this(workspaceManager, parentAgentId, executor, false, true);
    }

    /**
     * Test-only factory without background heartbeat or orphan sweeper threads.
     *
     * <p>Unit tests invoke {@link #heartbeat()} and {@link #sweepOrphanedTasks} directly; leaving
     * the maintenance scheduler enabled causes flaky races on slow CI hosts (notably Windows).
     */
    static WorkspaceTaskRepository forTests(
            WorkspaceManager workspaceManager, String parentAgentId) {
        ExecutorService testExecutor =
                Executors.newCachedThreadPool(
                        r -> {
                            Thread t = new Thread(r, "ws-task-test");
                            t.setDaemon(true);
                            return t;
                        });
        // Test helper creates its own executor, so repository must own and shut it down.
        return new WorkspaceTaskRepository(
                workspaceManager, parentAgentId, testExecutor, true, false);
    }

    static WorkspaceTaskRepository forTests(
            WorkspaceManager workspaceManager, String parentAgentId, ExecutorService executor) {
        return new WorkspaceTaskRepository(workspaceManager, parentAgentId, executor, false, false);
    }

    private WorkspaceTaskRepository(
            WorkspaceManager workspaceManager,
            String parentAgentId,
            ExecutorService executor,
            boolean ownsExecutor,
            boolean enableMaintenance) {
        this.workspaceManager = workspaceManager;
        this.parentAgentId = parentAgentId != null ? parentAgentId : "HarnessAgent";
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
        this.protocolClient = new AgentProtocolTaskClient();
        if (enableMaintenance) {
            ScheduledExecutorService scheduler =
                    Executors.newSingleThreadScheduledExecutor(
                            r -> {
                                Thread t = new Thread(r);
                                t.setDaemon(true);
                                t.setName("ws-task-maint-" + t.getId());
                                return t;
                            });
            scheduler.scheduleAtFixedRate(
                    this::heartbeat,
                    HEARTBEAT_INTERVAL_SECONDS,
                    HEARTBEAT_INTERVAL_SECONDS,
                    TimeUnit.SECONDS);
            // Jitter the first sweep in [0, SWEEP_INTERVAL_MINUTES) to spread load when multiple
            // nodes start simultaneously.
            long sweepJitterSeconds =
                    ThreadLocalRandom.current().nextLong(SWEEP_INTERVAL_MINUTES * 60L);
            scheduler.scheduleAtFixedRate(
                    this::sweepOrphanedTasksDefault,
                    sweepJitterSeconds,
                    SWEEP_INTERVAL_MINUTES * 60L,
                    TimeUnit.SECONDS);
            this.maintenanceScheduler = scheduler;
        } else {
            this.maintenanceScheduler = null;
        }
    }

    @Override
    public BackgroundTask putTask(
            RuntimeContext rc,
            String taskId,
            String subAgentId,
            String sessionId,
            TaskRunSpec spec) {
        RuntimeContext capturedRc = rc != null ? rc : RuntimeContext.empty();
        TaskRecord record = new TaskRecord(taskId, subAgentId, parentAgentId, sessionId, null);
        record.setStatus(TaskStatus.PENDING);
        if (spec instanceof TaskRunSpec.RemoteTaskRunSpec remote) {
            record.setTransportType(TRANSPORT_AGENT_PROTOCOL);
            record.setRemoteBaseUrl(remote.baseUrl());
            record.setRemoteHeaders(
                    remote.headers() == null
                            ? null
                            : Collections.unmodifiableMap(remote.headers()));
        }
        persistRecord(capturedRc, sessionId, record);

        String localKey = localKey(sessionId, taskId);
        CompletableFuture<String> future;

        if (spec instanceof TaskRunSpec.LocalTaskRunSpec local) {
            future =
                    CompletableFuture.supplyAsync(
                            () ->
                                    runLocalSupplier(
                                            capturedRc, sessionId, taskId, local.execution()),
                            executor);
        } else if (spec instanceof TaskRunSpec.RemoteTaskRunSpec remote) {
            future =
                    CompletableFuture.supplyAsync(
                            () ->
                                    runRemoteTask(
                                            capturedRc,
                                            sessionId,
                                            taskId,
                                            subAgentId,
                                            remote,
                                            true),
                            executor);
        } else {
            throw new IllegalArgumentException("Unsupported TaskRunSpec: " + spec.getClass());
        }

        BackgroundTask bgTask = new BackgroundTask(taskId, subAgentId, future);
        localTasks.put(localKey, bgTask);
        localTaskSessionIds.put(localKey, sessionId != null ? sessionId : "");
        localTaskContexts.put(localKey, capturedRc);
        return bgTask;
    }

    private String runLocalSupplier(
            RuntimeContext rc, String sessionId, String taskId, Supplier<String> taskExecution) {
        Optional<TaskRecord> latest =
                workspaceManager.readTaskRecord(rc, parentAgentId, sessionId, taskId);
        if (latest.isPresent() && latest.get().isCancelRequested()) {
            markCancelled(rc, sessionId, taskId);
            return null;
        }

        updateStatus(rc, sessionId, taskId, TaskStatus.RUNNING, null, null);
        try {
            String result = taskExecution.get();
            Optional<TaskRecord> afterRun =
                    workspaceManager.readTaskRecord(rc, parentAgentId, sessionId, taskId);
            if (afterRun.isPresent() && afterRun.get().isCancelRequested()) {
                markCancelled(rc, sessionId, taskId);
                return null;
            }
            updateStatus(rc, sessionId, taskId, TaskStatus.COMPLETED, result, null);
            return result;
        } catch (Exception e) {
            String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            updateStatus(rc, sessionId, taskId, TaskStatus.FAILED, null, errMsg);
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }
    }

    private String runRemoteTask(
            RuntimeContext rc,
            String sessionId,
            String taskId,
            String subAgentId,
            TaskRunSpec.RemoteTaskRunSpec remote,
            boolean submitRemote) {
        try {
            Optional<TaskRecord> latest =
                    workspaceManager.readTaskRecord(rc, parentAgentId, sessionId, taskId);
            if (latest.isPresent() && latest.get().isCancelRequested()) {
                markCancelled(rc, sessionId, taskId);
                return null;
            }
            if (submitRemote) {
                protocolClient.submitTask(
                        remote.baseUrl(), remote.headers(), taskId, subAgentId, remote.input());
                updateStatus(rc, sessionId, taskId, TaskStatus.RUNNING, null, null);
            }
            return pollRemoteUntilDone(rc, sessionId, taskId, remote.baseUrl(), remote.headers());
        } catch (Exception e) {
            String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            updateStatus(rc, sessionId, taskId, TaskStatus.FAILED, null, errMsg);
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }
    }

    private String pollRemoteUntilDone(
            RuntimeContext rc,
            String sessionId,
            String taskId,
            String baseUrl,
            Map<String, String> headers)
            throws Exception {
        int attempt = 0;
        while (!Thread.currentThread().isInterrupted()) {
            Optional<TaskRecord> wr =
                    workspaceManager.readTaskRecord(rc, parentAgentId, sessionId, taskId);
            if (wr.isPresent() && wr.get().isCancelRequested()) {
                try {
                    protocolClient.cancelTask(baseUrl, headers, taskId);
                } catch (Exception ex) {
                    log.debug("Remote cancel after local cancel flag: {}", ex.getMessage());
                }
                markCancelled(rc, sessionId, taskId);
                return null;
            }
            RemoteTaskStatus st = protocolClient.getStatus(baseUrl, headers, taskId);
            String s = st.status() == null ? "" : st.status().toLowerCase();
            switch (s) {
                case "success" -> {
                    String result = protocolClient.waitForResult(baseUrl, headers, taskId, 120);
                    updateStatus(rc, sessionId, taskId, TaskStatus.COMPLETED, result, null);
                    return result;
                }
                case "error", "failed" -> {
                    String err = st.error() != null ? st.error() : "remote task error";
                    updateStatus(rc, sessionId, taskId, TaskStatus.FAILED, null, err);
                    throw new RuntimeException(err);
                }
                case "cancelled", "canceled" -> {
                    markCancelled(rc, sessionId, taskId);
                    return null;
                }
                default -> {
                    // pending, running, empty: keep polling
                }
            }
            long sleepMs = Math.min(5_000L, 200L * (1L << Math.min(attempt++, 4)));
            Thread.sleep(sleepMs);
        }
        Thread.currentThread().interrupt();
        markCancelled(rc, sessionId, taskId);
        return null;
    }

    @Override
    public BackgroundTask getTask(RuntimeContext rc, String sessionId, String taskId) {
        BackgroundTask local = localTasks.get(localKey(sessionId, taskId));
        if (local != null) {
            return local;
        }
        // Fall back to workspace record — construct a synthetic completed/failed BackgroundTask
        Optional<TaskRecord> record =
                workspaceManager.readTaskRecord(
                        rc != null ? rc : RuntimeContext.empty(), parentAgentId, sessionId, taskId);
        return record.map(r -> syntheticTask(rc != null ? rc : RuntimeContext.empty(), r))
                .orElse(null);
    }

    @Override
    public Collection<BackgroundTask> listTasks(
            RuntimeContext rc, String sessionId, TaskStatus filter) {
        RuntimeContext effRc = rc != null ? rc : RuntimeContext.empty();
        Collection<TaskRecord> records =
                workspaceManager.listTaskRecords(effRc, parentAgentId, sessionId);

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
                effective = syntheticTask(effRc, wsRecord);
            }
            if (filter == null || effective.getTaskStatus() == filter) {
                result.add(effective);
            }
        }
        return result;
    }

    @Override
    public boolean cancelTask(RuntimeContext rc, String sessionId, String taskId) {
        RuntimeContext effRc = rc != null ? rc : RuntimeContext.empty();
        boolean found = false;

        BackgroundTask local = localTasks.get(localKey(sessionId, taskId));
        if (local != null) {
            local.cancel(true);
            found = true;
        }

        // Always write cancelRequested flag to workspace for cross-node coordination
        Optional<TaskRecord> existing =
                workspaceManager.readTaskRecord(effRc, parentAgentId, sessionId, taskId);
        if (existing.isPresent()) {
            TaskRecord snapshot = existing.get();
            boolean agentProtocol =
                    snapshot.isAgentProtocolTransport() && snapshot.getRemoteBaseUrl() != null;

            TaskRecord record = snapshot;
            record.setCancelRequested(true);
            if (!record.getStatus().isTerminal()) {
                record.setStatus(TaskStatus.CANCELLED);
            }
            persistRecord(effRc, sessionId, record);

            if (agentProtocol) {
                try {
                    protocolClient.cancelTask(
                            snapshot.getRemoteBaseUrl(), snapshot.getRemoteHeaders(), taskId);
                } catch (Exception e) {
                    log.warn("Remote cancel failed for task {}: {}", taskId, e.getMessage());
                }
            }
            return true;
        }

        return found;
    }

    // ---- Phase B-3 push delivery -----------------------------------------------------------

    @Override
    public List<TaskDelivery> findPendingDeliveries(RuntimeContext rc, String sessionId) {
        RuntimeContext effRc = rc != null ? rc : RuntimeContext.empty();
        Collection<TaskRecord> records =
                workspaceManager.listTaskRecords(effRc, parentAgentId, sessionId);
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        List<TaskRecord> ordered = new ArrayList<>(records);
        ordered.sort(
                Comparator.comparing(
                        TaskRecord::getLastUpdatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())));
        List<TaskDelivery> out = new ArrayList<>();
        for (TaskRecord r : ordered) {
            if (r.getStatus() == null || !r.getStatus().isTerminal()) continue;
            if (r.isDelivered()) continue;
            out.add(
                    new TaskDelivery(
                            r.getTaskId(),
                            r.getSubAgentId(),
                            r.getStatus(),
                            r.getResult(),
                            r.getErrorMessage(),
                            r.getLastUpdatedAt()));
        }
        return out;
    }

    /**
     * Stamps {@code deliveredAt} on the persisted record. Idempotent — the first non-null write
     * wins; subsequent calls bail out without touching workspace storage. Uses an independent
     * read-modify-write path rather than going through {@link #updateStatus} so the heartbeat /
     * orphan-sweeper cannot accidentally clobber the field via their RUNNING/FAILED writes
     * (those paths reconstruct the record around status-only fields).
     */
    @Override
    public void markDelivered(RuntimeContext rc, String sessionId, String taskId) {
        RuntimeContext effRc = rc != null ? rc : RuntimeContext.empty();
        Optional<TaskRecord> existing =
                workspaceManager.readTaskRecord(effRc, parentAgentId, sessionId, taskId);
        if (existing.isEmpty()) return;
        TaskRecord r = existing.get();
        if (r.getDeliveredAt() != null) return; // already delivered
        r.setDeliveredAt(Instant.now());
        persistRecord(effRc, sessionId, r);
    }

    @Override
    public boolean isDelivered(RuntimeContext rc, String sessionId, String taskId) {
        RuntimeContext effRc = rc != null ? rc : RuntimeContext.empty();
        return workspaceManager
                .readTaskRecord(effRc, parentAgentId, sessionId, taskId)
                .map(TaskRecord::isDelivered)
                .orElse(false);
    }

    @Override
    public void removeTask(RuntimeContext rc, String sessionId, String taskId) {
        String key = localKey(sessionId, taskId);
        localTasks.remove(key);
        localTaskSessionIds.remove(key);
        localTaskContexts.remove(key);
    }

    @Override
    public void clear() {
        localTasks.clear();
        localTaskSessionIds.clear();
        localTaskContexts.clear();
    }

    /** Shuts down the maintenance scheduler and (if owned) the task executor. */
    public void shutdown() {
        if (maintenanceScheduler != null) {
            maintenanceScheduler.shutdown();
            try {
                if (!maintenanceScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    maintenanceScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                maintenanceScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
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

    /**
     * Refreshes {@code lastUpdatedAt} in the workspace for every live local task that is still
     * running. Called on a fixed schedule so the orphan sweeper can distinguish a genuinely
     * running task from one whose originating node has disappeared.
     *
     * <p>Package-private for direct invocation in unit tests.
     */
    void heartbeat() {
        localTasks.forEach(
                (key, task) -> {
                    if (!task.isCompleted()) {
                        String sid = localTaskSessionIds.get(key);
                        if (sid == null) {
                            return;
                        }
                        RuntimeContext rc = localTaskContexts.get(key);
                        if (rc == null) {
                            rc = RuntimeContext.empty();
                        }
                        try {
                            updateStatus(rc, sid, task.getTaskId(), TaskStatus.RUNNING, null, null);
                        } catch (Exception e) {
                            log.debug(
                                    "Heartbeat update failed for task {}: {}",
                                    task.getTaskId(),
                                    e.getMessage());
                        }
                    }
                });
    }

    /** Package-private entry point for unit tests that need to invoke the default sweep path. */
    void sweepOrphanedTasksDefault_forTest() {
        sweepOrphanedTasksDefault();
    }

    private void sweepOrphanedTasksDefault() {
        // Maintenance scheduler runs without per-user context: tasks under user-isolated
        // namespaces are reachable via the captured per-task RC; this sweep operates on the
        // shared sweep marker (under empty RC) only.
        RuntimeContext rc = RuntimeContext.empty();

        // Best-effort distributed throttle: if another node already completed a sweep
        // within the last SWEEP_INTERVAL_MINUTES, skip this cycle entirely.
        // No locking — two nodes may occasionally both sweep, which is safe (idempotent).
        Duration sweepInterval = Duration.ofSeconds(SWEEP_INTERVAL_MINUTES * 60L);
        Optional<Instant> lastSweep = workspaceManager.readSweepMarker(rc, parentAgentId);
        if (lastSweep.isPresent() && lastSweep.get().isAfter(Instant.now().minus(sweepInterval))) {
            log.debug(
                    "Skipping orphan sweep for {} — another node swept at {}",
                    parentAgentId,
                    lastSweep.get());
            return;
        }

        // recentWindow = 2× orphan timeout + 1 sweep cycle.
        // The 2× factor ensures we never miss a task right at the orphan threshold
        // (the file could have been written exactly orphanTimeout ago).
        // The +1 cycle buffer handles clock skew and sweep scheduling jitter.
        Duration orphanTimeout = Duration.ofMinutes(ORPHAN_TIMEOUT_MINUTES);
        Duration recentWindow = orphanTimeout.multipliedBy(2).plus(sweepInterval);
        sweepOrphanedTasks(orphanTimeout, recentWindow);

        // Record completion so other nodes can skip their next scheduled cycle.
        workspaceManager.writeSweepMarker(rc, parentAgentId);
    }

    /**
     * Scans all persisted task records for this agent and marks non-terminal, non-remote tasks as
     * {@link TaskStatus#FAILED} when their {@code lastUpdatedAt} is older than
     * {@code orphanTimeout}.
     *
     * <p>A task is considered <em>orphaned</em> when the node that originated it has failed before
     * writing a terminal status and the {@link #heartbeat()} has therefore stopped. Remote
     * ({@code agent-protocol}) tasks are excluded because their liveness is governed by the remote
     * endpoint, not by this node's heartbeat.
     *
     * <p>Safe to run concurrently on multiple nodes: the workspace write is idempotent and
     * last-writer-wins is acceptable here (the task is already orphaned regardless of which node
     * notices first).
     *
     * <p>Package-private for direct invocation in unit tests.
     *
     * @param orphanTimeout how long a non-terminal local task may remain un-heartbeated before it
     *     is considered orphaned
     * @param recentWindow only session files modified within this window are scanned; files older
     *     than this are assumed to contain only terminal tasks (see
     *     {@link WorkspaceManager#listAllTaskRecords})
     */
    void sweepOrphanedTasks(Duration orphanTimeout, Duration recentWindow) {
        // Sweep runs without per-user RC. Tasks persisted under user-scoped namespaces are
        // visible to the sweep only via the captured per-task RC of any still-local entry; this
        // empty-RC path covers AGENT/GLOBAL-scoped persistence and the per-task local maps.
        RuntimeContext sweepRc = RuntimeContext.empty();
        try {
            Collection<TaskRecord> all =
                    workspaceManager.listAllTaskRecords(sweepRc, parentAgentId, recentWindow);
            Instant threshold = Instant.now().minus(orphanTimeout);
            for (TaskRecord record : all) {
                if (record.getStatus() == null || record.getStatus().isTerminal()) {
                    continue;
                }
                if (record.isAgentProtocolTransport()) {
                    // Remote tasks handle their own liveness via polling
                    continue;
                }
                Instant lastUpdated = record.getLastUpdatedAt();
                if (lastUpdated == null || !lastUpdated.isBefore(threshold)) {
                    continue;
                }
                String sid = record.getParentSessionId();
                String tid = record.getTaskId();
                // Skip if this node still has an active local future — heartbeat is live.
                String key = localKey(sid, tid);
                BackgroundTask local = localTasks.get(key);
                if (local != null && !local.isCompleted()) {
                    continue;
                }
                RuntimeContext updateRc = localTaskContexts.getOrDefault(key, sweepRc);
                long staleSecs = Duration.between(lastUpdated, Instant.now()).getSeconds();
                String orphanMsg =
                        String.format(
                                "executor lost: no heartbeat for %d seconds (last updated: %s)",
                                staleSecs, lastUpdated);
                log.warn(
                        "Orphaned task {} (session {}) last updated {}s ago, marking FAILED",
                        tid,
                        sid,
                        staleSecs);
                updateStatus(updateRc, sid, tid, TaskStatus.FAILED, null, orphanMsg);
            }
        } catch (Exception e) {
            log.warn("Orphan sweeper encountered an error: {}", e.getMessage());
        }
    }

    // ---- private helpers ----

    private static String localKey(String sessionId, String taskId) {
        String s = sessionId != null ? sessionId : "_";
        return s + ":" + taskId;
    }

    private void persistRecord(RuntimeContext rc, String sessionId, TaskRecord record) {
        try {
            workspaceManager.writeTaskRecord(rc, parentAgentId, sessionId, record);
        } catch (Exception e) {
            log.warn(
                    "Failed to persist task record {} for session {}: {}",
                    record.getTaskId(),
                    sessionId,
                    e.getMessage());
        }
    }

    private void updateStatus(
            RuntimeContext rc,
            String sessionId,
            String taskId,
            TaskStatus status,
            String result,
            String error) {
        Optional<TaskRecord> existing =
                workspaceManager.readTaskRecord(rc, parentAgentId, sessionId, taskId);
        // Guard 1: terminal states are immutable — never overwrite COMPLETED, FAILED, or CANCELLED
        // with any other status. This prevents a late COMPLETED/FAILED write from a still-running
        // thread from clobbering a CANCELLED status set concurrently by cancelTask().
        if (existing.isPresent()
                && existing.get().getStatus() != null
                && existing.get().getStatus().isTerminal()) {
            return;
        }
        // Guard 2: if cancellation has been requested but the workspace record has not yet reached
        // a terminal state (e.g. heartbeat races cancelTask()), refuse to persist non-terminal
        // updates. This stops the heartbeat from writing RUNNING back over a record whose
        // cancelRequested flag was just set by cancelTask().
        if (!status.isTerminal() && existing.isPresent() && existing.get().isCancelRequested()) {
            return;
        }
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
        persistRecord(rc, sessionId, record);
    }

    private void markCancelled(RuntimeContext rc, String sessionId, String taskId) {
        updateStatus(rc, sessionId, taskId, TaskStatus.CANCELLED, null, null);
    }

    /**
     * Creates a synthetic {@link BackgroundTask} from a persisted {@link TaskRecord}. The future
     * is already-completed (or failed/cancelled) to reflect the stored terminal status.
     */
    private BackgroundTask syntheticTask(RuntimeContext rc, TaskRecord record) {
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
                if (record.isAgentProtocolTransport()
                        && record.getRemoteBaseUrl() != null
                        && !record.getStatus().isTerminal()) {
                    // Cache in localTasks so repeated calls to getTask/listTasks don't spawn
                    // multiple concurrent pollers for the same remote task.
                    String sid =
                            record.getParentSessionId() != null ? record.getParentSessionId() : "";
                    String lk = localKey(sid, record.getTaskId());
                    RuntimeContext capturedRc = rc != null ? rc : RuntimeContext.empty();
                    BackgroundTask cached =
                            localTasks.computeIfAbsent(
                                    lk,
                                    k -> {
                                        CompletableFuture<String> f =
                                                CompletableFuture.supplyAsync(
                                                        () ->
                                                                runRemoteTask(
                                                                        capturedRc,
                                                                        sid,
                                                                        record.getTaskId(),
                                                                        record.getSubAgentId(),
                                                                        new TaskRunSpec
                                                                                .RemoteTaskRunSpec(
                                                                                record
                                                                                        .getRemoteBaseUrl(),
                                                                                record
                                                                                                        .getRemoteHeaders()
                                                                                                != null
                                                                                        ? record
                                                                                                .getRemoteHeaders()
                                                                                        : Map.of(),
                                                                                record
                                                                                        .getSubAgentId(),
                                                                                ""),
                                                                        false),
                                                        executor);
                                        return new BackgroundTask(
                                                record.getTaskId(), record.getSubAgentId(), f);
                                    });
                    localTaskSessionIds.putIfAbsent(lk, sid);
                    localTaskContexts.putIfAbsent(lk, capturedRc);
                    return cached;
                } else {
                    // PENDING or RUNNING but no local future — cross-node local task.
                    future = new CompletableFuture<>();
                }
            }
        }
        return new BackgroundTask(record.getTaskId(), record.getSubAgentId(), future);
    }
}
