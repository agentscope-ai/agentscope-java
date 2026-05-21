/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.agentprotocol;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.task.TaskRecord;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * In-memory execution handles with workspace-backed {@link TaskRecord} persistence.
 *
 * <p>Each submitted task obtains a {@link HarnessAgent} instance via the {@code agentSupplier}.
 * For concurrent task execution, supply a factory that returns a new instance per call (e.g. a
 * Spring prototype-scoped bean). When a singleton bean is supplied, concurrent tasks will fail
 * at the agent level unless the agent is configured with {@code checkRunning(false)}.
 */
public final class AgentProtocolTaskStore {

    private static final Logger log = LoggerFactory.getLogger(AgentProtocolTaskStore.class);

    private final Supplier<HarnessAgent> agentSupplier;
    private final WorkspaceManager workspaceManager;
    private final ExecutorService executor =
            Executors.newCachedThreadPool(
                    r -> {
                        Thread t = new Thread(r);
                        t.setDaemon(true);
                        t.setName("agent-protocol-task-" + t.getId());
                        return t;
                    });

    private final Map<String, CompletableFuture<String>> futures = new ConcurrentHashMap<>();

    /**
     * Creates the store with an agent factory. Each task invocation calls {@code agentSupplier}
     * once; for true concurrent execution supply a prototype-scoped factory.
     */
    public AgentProtocolTaskStore(
            Supplier<HarnessAgent> agentSupplier, WorkspaceManager workspaceManager) {
        this.agentSupplier = Objects.requireNonNull(agentSupplier, "agentSupplier");
        this.workspaceManager = Objects.requireNonNull(workspaceManager, "workspaceManager");
    }

    /** Convenience constructor for the common single-instance case. */
    public AgentProtocolTaskStore(HarnessAgent harnessAgent, WorkspaceManager workspaceManager) {
        this(() -> harnessAgent, workspaceManager);
    }

    /**
     * Starts a background task. Idempotent while running: if a non-terminal task with the same id
     * already exists, returns without starting a duplicate run.
     *
     * @throws IllegalStateException if the task id already completed (HTTP 409)
     */
    public void submit(String taskId, String agentId, String input) {
        Optional<TaskRecord> existing =
                workspaceManager.readTaskRecord(
                        AgentProtocolConstants.PROTOCOL_AGENT_ID,
                        AgentProtocolConstants.PROTOCOL_SESSION_ID,
                        taskId);
        if (existing.isPresent() && existing.get().getStatus().isTerminal()) {
            throw new IllegalStateException("task_id already finished: " + taskId);
        }
        CompletableFuture<String> prior = futures.get(taskId);
        if (prior != null && !prior.isDone()) {
            return;
        }

        TaskRecord pending =
                new TaskRecord(
                        taskId,
                        agentId != null ? agentId : "default",
                        AgentProtocolConstants.PROTOCOL_AGENT_ID,
                        AgentProtocolConstants.PROTOCOL_SESSION_ID,
                        null);
        pending.setStatus(TaskStatus.PENDING);
        persist(pending);

        CompletableFuture<String> f =
                CompletableFuture.supplyAsync(() -> runAgent(taskId, agentId, input), executor);
        futures.put(taskId, f);
        f.whenComplete((r, ex) -> futures.remove(taskId));
    }

    private String runAgent(String taskId, String agentId, String input) {
        try {
            update(taskId, TaskStatus.RUNNING, null, null, agentId);
            RuntimeContext ctx = RuntimeContext.builder().sessionId(taskId).build();
            Msg msg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .textContent(input != null ? input : "")
                            .build();
            HarnessAgent agent = agentSupplier.get();
            Mono<Msg> mono = agent.call(msg, ctx);
            Msg reply = mono.block(Duration.ofHours(2));
            String text = reply != null ? reply.getTextContent() : "";
            update(taskId, TaskStatus.COMPLETED, text, null, agentId);
            return text;
        } catch (Exception e) {
            String err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Protocol task {} failed", taskId, e);
            update(taskId, TaskStatus.FAILED, null, err, agentId);
            throw new RuntimeException(e);
        }
    }

    private void persist(TaskRecord r) {
        workspaceManager.writeTaskRecord(
                AgentProtocolConstants.PROTOCOL_AGENT_ID,
                AgentProtocolConstants.PROTOCOL_SESSION_ID,
                r);
    }

    private void update(
            String taskId, TaskStatus status, String result, String error, String agentId) {
        Optional<TaskRecord> prev =
                workspaceManager.readTaskRecord(
                        AgentProtocolConstants.PROTOCOL_AGENT_ID,
                        AgentProtocolConstants.PROTOCOL_SESSION_ID,
                        taskId);
        TaskRecord r =
                prev.orElseGet(
                        () ->
                                new TaskRecord(
                                        taskId,
                                        agentId != null ? agentId : "default",
                                        AgentProtocolConstants.PROTOCOL_AGENT_ID,
                                        AgentProtocolConstants.PROTOCOL_SESSION_ID,
                                        null));
        r.setSubAgentId(agentId != null ? agentId : r.getSubAgentId());
        r.setStatus(status);
        if (result != null) {
            r.setResult(result);
        }
        if (error != null) {
            r.setErrorMessage(error);
        }
        persist(r);
    }

    public Map<String, Object> snapshot(String taskId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("task_id", taskId);
        Optional<TaskRecord> rec =
                workspaceManager.readTaskRecord(
                        AgentProtocolConstants.PROTOCOL_AGENT_ID,
                        AgentProtocolConstants.PROTOCOL_SESSION_ID,
                        taskId);
        if (rec.isEmpty()) {
            m.put("status", "error");
            m.put("error", "task not found");
            return m;
        }
        TaskRecord r = rec.get();
        m.put("status", mapStatus(r.getStatus()));
        if (r.getErrorMessage() != null) {
            m.put("error", r.getErrorMessage());
        }
        if (r.getStatus() == TaskStatus.COMPLETED && r.getResult() != null) {
            m.put("result", r.getResult());
        }
        CompletableFuture<String> f = futures.get(taskId);
        if (f != null
                && !f.isDone()
                && (r.getStatus() == TaskStatus.RUNNING || r.getStatus() == TaskStatus.PENDING)) {
            m.put("status", "running");
        }
        return m;
    }

    /**
     * Blocks until the task reaches a terminal state or the timeout elapses.
     *
     * @param taskId task to wait for
     * @param timeoutMs maximum wait time in milliseconds; 0 means no explicit deadline (uses the
     *     in-memory future's natural completion if available, then falls back to a single workspace
     *     read)
     */
    public Map<String, Object> waitFor(String taskId, long timeoutMs) throws Exception {
        long deadline = timeoutMs > 0 ? System.currentTimeMillis() + timeoutMs : Long.MAX_VALUE;

        CompletableFuture<String> f = futures.get(taskId);
        if (f != null) {
            try {
                if (timeoutMs > 0) {
                    f.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                } else {
                    f.get();
                }
            } catch (java.util.concurrent.TimeoutException e) {
                Map<String, Object> tout = new LinkedHashMap<>();
                tout.put("status", "error");
                tout.put("error", "wait timeout after " + timeoutMs + " ms");
                return tout;
            } catch (Exception ignored) {
                // terminal state will be read from workspace below
            }
        }

        int attempt = 0;
        while (System.currentTimeMillis() < deadline) {
            Optional<TaskRecord> rec =
                    workspaceManager.readTaskRecord(
                            AgentProtocolConstants.PROTOCOL_AGENT_ID,
                            AgentProtocolConstants.PROTOCOL_SESSION_ID,
                            taskId);
            if (rec.isEmpty()) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("status", "error");
                err.put("error", "task not found");
                return err;
            }
            TaskRecord r = rec.get();
            if (r.getStatus().isTerminal()) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("status", mapStatus(r.getStatus()));
                if (r.getStatus() == TaskStatus.COMPLETED) {
                    out.put("result", r.getResult() != null ? r.getResult() : "");
                } else if (r.getStatus() == TaskStatus.FAILED) {
                    out.put("error", r.getErrorMessage());
                }
                return out;
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                break;
            }
            long sleepMs =
                    Math.min(Math.min(5_000L, remaining), 200L * (1L << Math.min(attempt++, 4)));
            Thread.sleep(sleepMs);
        }

        Map<String, Object> tout = new LinkedHashMap<>();
        tout.put("status", "error");
        tout.put("error", "wait timeout after " + timeoutMs + " ms");
        return tout;
    }

    public void cancel(String taskId) {
        CompletableFuture<String> f = futures.get(taskId);
        if (f != null) {
            f.cancel(true);
        }
        Optional<TaskRecord> rec =
                workspaceManager.readTaskRecord(
                        AgentProtocolConstants.PROTOCOL_AGENT_ID,
                        AgentProtocolConstants.PROTOCOL_SESSION_ID,
                        taskId);
        if (rec.isPresent()) {
            TaskRecord r = rec.get();
            r.setCancelRequested(true);
            if (!r.getStatus().isTerminal()) {
                r.setStatus(TaskStatus.CANCELLED);
            }
            persist(r);
        }
    }

    private static String mapStatus(TaskStatus s) {
        return switch (s) {
            case PENDING -> "pending";
            case RUNNING -> "running";
            case COMPLETED -> "success";
            case FAILED -> "error";
            case CANCELLED -> "cancelled";
        };
    }
}
