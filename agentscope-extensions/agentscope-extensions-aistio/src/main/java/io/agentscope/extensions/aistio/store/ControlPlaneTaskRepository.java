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
package io.agentscope.extensions.aistio.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.extensions.aistio.transport.ControlPlaneHttpClient;
import io.agentscope.harness.agent.subagent.task.AgentProtocolTaskClient;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.RemoteTaskStatus;
import io.agentscope.harness.agent.subagent.task.TaskDelivery;
import io.agentscope.harness.agent.subagent.task.TaskRecord;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Control-plane hosted {@link TaskRepository} over {@code /api/v1/dp/tasks/*}.
 *
 * <p>Maintains in-memory {@link BackgroundTask} handles for tasks running on the current node while
 * using the control plane as the authoritative persistence layer. Heartbeats live local tasks every
 * {@value #HEARTBEAT_INTERVAL_SECONDS} seconds via {@code POST /tasks/heartbeat}; there is no
 * client-side orphan sweeper.
 */
public final class ControlPlaneTaskRepository implements TaskRepository {

    private static final Logger log = LoggerFactory.getLogger(ControlPlaneTaskRepository.class);
    private static final ObjectMapper MAPPER = ControlPlaneHttpClient.mapper();
    private static final TypeReference<Map<String, String>> HEADERS_TYPE = new TypeReference<>() {};

    private static final String TRANSPORT_AGENT_PROTOCOL = "agent-protocol";

    /** Heartbeat interval for live local tasks (seconds). */
    static final int HEARTBEAT_INTERVAL_SECONDS = 30;

    private final ControlPlaneHttpClient http;
    private final String agentName;
    private final String namespace;
    private final String parentAgentId;
    private final AgentProtocolTaskClient protocolClient;

    private final Map<String, BackgroundTask> localTasks = new ConcurrentHashMap<>();
    private final Map<String, String> localTaskSessionIds = new ConcurrentHashMap<>();
    private final Map<String, RuntimeContext> localTaskContexts = new ConcurrentHashMap<>();

    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private final ScheduledExecutorService maintenanceScheduler;

    private volatile TaskCompletionCallback completionCallback;

    ControlPlaneTaskRepository(
            ControlPlaneHttpClient http, String agentName, String namespace, String parentAgentId) {
        this(http, agentName, namespace, parentAgentId, true);
    }

    static ControlPlaneTaskRepository forTests(
            ControlPlaneHttpClient http, String agentName, String namespace, String parentAgentId) {
        ExecutorService testExecutor =
                Executors.newCachedThreadPool(
                        r -> {
                            Thread t = new Thread(r, "cp-task-test");
                            t.setDaemon(true);
                            return t;
                        });
        return new ControlPlaneTaskRepository(
                http, agentName, namespace, parentAgentId, testExecutor, true, false);
    }

    private ControlPlaneTaskRepository(
            ControlPlaneHttpClient http,
            String agentName,
            String namespace,
            String parentAgentId,
            boolean enableMaintenance) {
        this(
                http,
                agentName,
                namespace,
                parentAgentId,
                Executors.newCachedThreadPool(
                        r -> {
                            Thread t = new Thread(r);
                            t.setDaemon(true);
                            t.setName("cp-task-" + t.getId());
                            return t;
                        }),
                true,
                enableMaintenance);
    }

    private ControlPlaneTaskRepository(
            ControlPlaneHttpClient http,
            String agentName,
            String namespace,
            String parentAgentId,
            ExecutorService executor,
            boolean ownsExecutor,
            boolean enableMaintenance) {
        this.http = Objects.requireNonNull(http, "http");
        this.agentName = Objects.requireNonNull(agentName, "agentName");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.parentAgentId =
                parentAgentId != null && !parentAgentId.isBlank() ? parentAgentId : agentName;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
        this.protocolClient = new AgentProtocolTaskClient();
        if (enableMaintenance) {
            ScheduledExecutorService scheduler =
                    Executors.newSingleThreadScheduledExecutor(
                            r -> {
                                Thread t = new Thread(r);
                                t.setDaemon(true);
                                t.setName("cp-task-maint-" + t.getId());
                                return t;
                            });
            scheduler.scheduleAtFixedRate(
                    this::heartbeat,
                    HEARTBEAT_INTERVAL_SECONDS,
                    HEARTBEAT_INTERVAL_SECONDS,
                    TimeUnit.SECONDS);
            this.maintenanceScheduler = scheduler;
        } else {
            this.maintenanceScheduler = null;
        }
    }

    @Override
    public void setCompletionCallback(TaskCompletionCallback callback) {
        this.completionCallback = callback;
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

        if (spec instanceof TaskRunSpec.AdoptedTaskRunSpec adopted) {
            future = adopted.future();
            updateStatus(capturedRc, sessionId, taskId, TaskStatus.RUNNING, null, null);
            final String sid = sessionId;
            future.whenComplete(
                    (result, err) -> {
                        if (err != null) {
                            Throwable cause =
                                    err instanceof java.util.concurrent.CompletionException
                                            ? err.getCause()
                                            : err;
                            String errMsg =
                                    cause != null && cause.getMessage() != null
                                            ? cause.getMessage()
                                            : (cause != null
                                                    ? cause.getClass().getSimpleName()
                                                    : err.getClass().getSimpleName());
                            updateStatus(capturedRc, sid, taskId, TaskStatus.FAILED, null, errMsg);
                            fireCompletionCallback(capturedRc, taskId, subAgentId, sid, null);
                        } else {
                            updateStatus(
                                    capturedRc, sid, taskId, TaskStatus.COMPLETED, result, null);
                            fireCompletionCallback(capturedRc, taskId, subAgentId, sid, result);
                        }
                    });
        } else if (spec instanceof TaskRunSpec.LocalTaskRunSpec local) {
            future =
                    CompletableFuture.supplyAsync(
                            () ->
                                    runLocalSupplier(
                                            capturedRc,
                                            sessionId,
                                            taskId,
                                            subAgentId,
                                            local.execution()),
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

    @Override
    public BackgroundTask getTask(RuntimeContext rc, String sessionId, String taskId) {
        BackgroundTask local = localTasks.get(localKey(sessionId, taskId));
        if (local != null) {
            return local;
        }
        RuntimeContext effRc = rc != null ? rc : RuntimeContext.empty();
        return readRecord(effRc, sessionId, taskId).map(r -> syntheticTask(effRc, r)).orElse(null);
    }

    @Override
    public Collection<BackgroundTask> listTasks(
            RuntimeContext rc, String sessionId, TaskStatus filter) {
        RuntimeContext effRc = rc != null ? rc : RuntimeContext.empty();
        List<TaskRecord> records = listRecords(effRc, sessionId, filter);

        List<BackgroundTask> result = new ArrayList<>();
        for (TaskRecord stored : records) {
            String key = localKey(sessionId, stored.getTaskId());
            BackgroundTask local = localTasks.get(key);
            BackgroundTask effective;
            if (local != null && !stored.getStatus().isTerminal()) {
                effective = local;
            } else {
                effective = syntheticTask(effRc, stored);
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

        Optional<TaskRecord> existing = readRecord(effRc, sessionId, taskId);
        if (existing.isPresent()) {
            try {
                Map<String, Object> body = tenantBody();
                body.put("parentSessionId", sessionId);
                ControlPlaneHttpClient.Response resp =
                        http.send("POST", "/api/v1/dp/tasks/" + encPath(taskId) + "/cancel", body);
                if (resp.status() == 404) {
                    return found;
                }
                requireOk(resp, "cancel");
            } catch (IOException | InterruptedException e) {
                rethrow(e);
            }

            TaskRecord snapshot = existing.get();
            if (snapshot.isAgentProtocolTransport() && snapshot.getRemoteBaseUrl() != null) {
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

    @Override
    public List<TaskDelivery> findPendingDeliveries(RuntimeContext rc, String sessionId) {
        RuntimeContext effRc = rc != null ? rc : RuntimeContext.empty();
        try {
            String path =
                    "/api/v1/dp/tasks/pending-deliveries?"
                            + tenantQuery()
                            + "&parentSessionId="
                            + enc(sessionId);
            ControlPlaneHttpClient.Response resp = http.send("GET", path, null);
            requireOk(resp, "pending-deliveries");
            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode tasks = root.path("tasks");
            List<TaskRecord> ordered = new ArrayList<>();
            if (tasks.isArray()) {
                for (JsonNode n : tasks) {
                    ordered.add(parseTask(n));
                }
            }
            ordered.sort(
                    Comparator.comparing(
                            TaskRecord::getLastUpdatedAt,
                            Comparator.nullsLast(Comparator.naturalOrder())));
            List<TaskDelivery> out = new ArrayList<>();
            for (TaskRecord r : ordered) {
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
        } catch (IOException | InterruptedException e) {
            rethrow(e);
            return List.of();
        }
    }

    @Override
    public void markDelivered(RuntimeContext rc, String sessionId, String taskId) {
        RuntimeContext effRc = rc != null ? rc : RuntimeContext.empty();
        if (readRecord(effRc, sessionId, taskId).isEmpty()) {
            return;
        }
        try {
            Map<String, Object> body = tenantBody();
            body.put("parentSessionId", sessionId);
            ControlPlaneHttpClient.Response resp =
                    http.send("POST", "/api/v1/dp/tasks/" + encPath(taskId) + "/delivered", body);
            if (resp.status() == 404) {
                return;
            }
            requireOk(resp, "delivered");
        } catch (IOException | InterruptedException e) {
            rethrow(e);
        }
    }

    @Override
    public boolean isDelivered(RuntimeContext rc, String sessionId, String taskId) {
        RuntimeContext effRc = rc != null ? rc : RuntimeContext.empty();
        return readRecord(effRc, sessionId, taskId).map(TaskRecord::isDelivered).orElse(false);
    }

    @Override
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

    /** Package-private for unit tests. */
    void heartbeat() {
        List<Map<String, String>> refs = new ArrayList<>();
        localTasks.forEach(
                (key, task) -> {
                    if (!task.isCompleted()) {
                        String sid = localTaskSessionIds.get(key);
                        if (sid == null) {
                            return;
                        }
                        Map<String, String> ref = new LinkedHashMap<>();
                        ref.put("parentSessionId", sid);
                        ref.put("taskId", task.getTaskId());
                        refs.add(ref);
                    }
                });
        if (refs.isEmpty()) {
            return;
        }
        try {
            Map<String, Object> body = tenantBody();
            body.put("tasks", refs);
            ControlPlaneHttpClient.Response resp =
                    http.send("POST", "/api/v1/dp/tasks/heartbeat", body);
            if (resp.status() == 204 || (resp.status() >= 200 && resp.status() < 300)) {
                return;
            }
            throw new RuntimeException(
                    "control-plane task heartbeat failed: HTTP "
                            + resp.status()
                            + " "
                            + resp.body());
        } catch (IOException | InterruptedException e) {
            log.debug("Task heartbeat failed: {}", e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private String runLocalSupplier(
            RuntimeContext rc,
            String sessionId,
            String taskId,
            String subAgentId,
            Supplier<String> taskExecution) {
        Optional<TaskRecord> latest = readRecord(rc, sessionId, taskId);
        if (latest.isPresent() && latest.get().isCancelRequested()) {
            markCancelled(rc, sessionId, taskId);
            return null;
        }

        updateStatus(rc, sessionId, taskId, TaskStatus.RUNNING, null, null);
        try {
            String result = taskExecution.get();
            Optional<TaskRecord> afterRun = readRecord(rc, sessionId, taskId);
            if (afterRun.isPresent() && afterRun.get().isCancelRequested()) {
                markCancelled(rc, sessionId, taskId);
                return null;
            }
            updateStatus(rc, sessionId, taskId, TaskStatus.COMPLETED, result, null);
            fireCompletionCallback(rc, taskId, subAgentId, sessionId, result);
            return result;
        } catch (Exception e) {
            String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            updateStatus(rc, sessionId, taskId, TaskStatus.FAILED, null, errMsg);
            fireCompletionCallback(rc, taskId, subAgentId, sessionId, null);
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
            Optional<TaskRecord> latest = readRecord(rc, sessionId, taskId);
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
            Optional<TaskRecord> wr = readRecord(rc, sessionId, taskId);
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

    private Optional<TaskRecord> readRecord(RuntimeContext rc, String sessionId, String taskId) {
        try {
            String path =
                    "/api/v1/dp/tasks/"
                            + encPath(taskId)
                            + "?"
                            + tenantQuery()
                            + "&parentSessionId="
                            + enc(sessionId);
            ControlPlaneHttpClient.Response resp = http.send("GET", path, null);
            if (resp.status() == 404) {
                return Optional.empty();
            }
            requireOk(resp, "get");
            return Optional.of(parseTask(MAPPER.readTree(resp.body())));
        } catch (IOException | InterruptedException e) {
            rethrow(e);
            return Optional.empty();
        }
    }

    private List<TaskRecord> listRecords(RuntimeContext rc, String sessionId, TaskStatus filter) {
        try {
            StringBuilder path =
                    new StringBuilder("/api/v1/dp/tasks?")
                            .append(tenantQuery())
                            .append("&parentSessionId=")
                            .append(enc(sessionId));
            if (filter != null) {
                path.append("&status=").append(enc(filter.name()));
            }
            ControlPlaneHttpClient.Response resp = http.send("GET", path.toString(), null);
            requireOk(resp, "list");
            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode tasks = root.path("tasks");
            List<TaskRecord> out = new ArrayList<>();
            if (tasks.isArray()) {
                for (JsonNode n : tasks) {
                    out.add(parseTask(n));
                }
            }
            return out;
        } catch (IOException | InterruptedException e) {
            rethrow(e);
            return List.of();
        }
    }

    private void persistRecord(RuntimeContext rc, String sessionId, TaskRecord record) {
        try {
            Map<String, Object> body = toUpsertBody(rc, sessionId, record);
            ControlPlaneHttpClient.Response resp =
                    http.send("PUT", "/api/v1/dp/tasks/" + encPath(record.getTaskId()), body);
            requireOk(resp, "put");
        } catch (IOException | InterruptedException e) {
            log.warn(
                    "Failed to persist task record {} for session {}: {}",
                    record.getTaskId(),
                    sessionId,
                    e.getMessage());
            rethrow(e);
        }
    }

    private void updateStatus(
            RuntimeContext rc,
            String sessionId,
            String taskId,
            TaskStatus status,
            String result,
            String error) {
        Optional<TaskRecord> existing = readRecord(rc, sessionId, taskId);
        if (existing.isPresent()
                && existing.get().getStatus() != null
                && existing.get().getStatus().isTerminal()) {
            return;
        }
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
                    future = new CompletableFuture<>();
                }
            }
        }
        return new BackgroundTask(record.getTaskId(), record.getSubAgentId(), future);
    }

    private Map<String, Object> toUpsertBody(
            RuntimeContext rc, String sessionId, TaskRecord record) {
        Map<String, Object> body = tenantBody();
        body.put("parentSessionId", sessionId);
        body.put("subAgentId", record.getSubAgentId());
        body.put("subSessionId", record.getSubSessionId());
        body.put("status", record.getStatus() != null ? record.getStatus().name() : null);
        body.put("result", record.getResult());
        body.put("errorMessage", record.getErrorMessage());
        body.put("cancelRequested", record.isCancelRequested());
        body.put("transportType", record.getTransportType());
        body.put("remoteBaseUrl", record.getRemoteBaseUrl());
        if (record.getRemoteHeaders() != null) {
            body.put("remoteHeaders", record.getRemoteHeaders());
        }
        if (rc != null && rc.getUserId() != null && !rc.getUserId().isBlank()) {
            body.put("userId", rc.getUserId());
        }
        if (record.getLastCheckedAt() != null) {
            body.put("lastCheckedAt", record.getLastCheckedAt().toString());
        }
        return body;
    }

    private TaskRecord parseTask(JsonNode n) {
        TaskRecord r = new TaskRecord();
        r.setTaskId(textOrNull(n, "taskId"));
        r.setSubAgentId(textOrNull(n, "subAgentId"));
        r.setParentAgentId(textOrNull(n, "parentAgentId"));
        r.setParentSessionId(textOrNull(n, "parentSessionId"));
        r.setSubSessionId(textOrNull(n, "subSessionId"));
        String status = textOrNull(n, "status");
        if (status != null && !status.isBlank()) {
            r.setStatus(TaskStatus.valueOf(status));
        }
        r.setResult(textOrNull(n, "result"));
        r.setErrorMessage(textOrNull(n, "errorMessage"));
        r.setCancelRequested(n.path("cancelRequested").asBoolean(false));
        r.setTransportType(textOrNull(n, "transportType"));
        r.setRemoteBaseUrl(textOrNull(n, "remoteBaseUrl"));
        JsonNode headersNode = n.get("remoteHeaders");
        if (headersNode != null && !headersNode.isNull()) {
            r.setRemoteHeaders(MAPPER.convertValue(headersNode, HEADERS_TYPE));
        }
        r.setCreatedAt(parseInstant(n, "createdAt"));
        r.setLastCheckedAt(parseInstant(n, "lastCheckedAt"));
        r.setLastUpdatedAt(parseInstant(n, "lastUpdatedAt"));
        r.setDeliveredAt(parseInstant(n, "deliveredAt"));
        return r;
    }

    private static String textOrNull(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText();
        return s.isBlank() ? null : s;
    }

    private static Instant parseInstant(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull() || v.asText().isBlank()) {
            return null;
        }
        return Instant.parse(v.asText());
    }

    private Map<String, Object> tenantBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agentName", agentName);
        body.put("namespace", namespace);
        return body;
    }

    private String tenantQuery() {
        return "agentName=" + enc(agentName) + "&namespace=" + enc(namespace);
    }

    private static String localKey(String sessionId, String taskId) {
        String s = sessionId != null ? sessionId : "_";
        return s + ":" + taskId;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String encPath(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void requireOk(ControlPlaneHttpClient.Response resp, String op) {
        if (resp.status() >= 200 && resp.status() < 300) {
            return;
        }
        throw new RuntimeException(
                "control-plane task " + op + " failed: HTTP " + resp.status() + " " + resp.body());
    }

    private static void rethrow(Exception e) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        throw new RuntimeException("control-plane task request failed: " + e.getMessage(), e);
    }

    private void fireCompletionCallback(
            RuntimeContext rc, String taskId, String subAgentId, String sessionId, String result) {
        TaskCompletionCallback cb = this.completionCallback;
        if (cb == null) {
            return;
        }
        try {
            cb.onCompleted(rc, taskId, subAgentId, sessionId, result);
        } catch (Exception e) {
            log.warn("TaskCompletionCallback failed for task {}: {}", taskId, e.getMessage(), e);
        }
    }
}
