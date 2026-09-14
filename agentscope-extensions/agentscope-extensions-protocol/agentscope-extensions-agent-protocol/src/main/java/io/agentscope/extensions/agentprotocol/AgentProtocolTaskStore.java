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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.protocol.RemoteAgentEvent;
import io.agentscope.harness.agent.subagent.protocol.RemoteConfirmDecision;
import io.agentscope.harness.agent.subagent.protocol.RemoteEventCodec;
import io.agentscope.harness.agent.subagent.protocol.RemoteEventType;
import io.agentscope.harness.agent.subagent.protocol.RemotePendingConfirm;
import io.agentscope.harness.agent.subagent.task.TaskRecord;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * In-memory execution handles with control-plane {@link TaskRecord} persistence.
 *
 * <p>Each run resolves its {@link HarnessAgent} through the {@link AgentFactory}, which receives
 * the submission context and can therefore route per task. For concurrent task execution, return a
 * new instance per call (e.g. a Spring prototype-scoped bean); a shared instance requires the agent
 * to be configured with {@code checkRunning(false)}.
 *
 * <p>Task metadata is stored via {@link ProtocolTaskRepository} (a dedicated control-plane path),
 * not via any execution agent's workspace. Caller-supplied {@code context.attributes} reach the
 * run through its {@link RuntimeContext}; see {@link RuntimeContextCustomizer} for controlling how.
 */
public final class AgentProtocolTaskStore {

    private static final Logger log = LoggerFactory.getLogger(AgentProtocolTaskStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Sentinel returned when the agent paused for HITL confirmation. */
    static final String AWAITING_CONFIRM_SENTINEL = "__awaiting_confirm__";

    private final AgentFactory agentFactory;
    private final ProtocolTaskRepository taskRepository;
    private final AgentProtocolEventBus eventBus;
    private final AgentProtocolProperties properties;
    private final List<RuntimeContextCustomizer> runtimeContextCustomizers;
    private final ExecutorService executor =
            Executors.newCachedThreadPool(
                    r -> {
                        Thread t = new Thread(r);
                        t.setDaemon(true);
                        t.setName("agent-protocol-task-" + t.getId());
                        return t;
                    });

    private final Map<String, CompletableFuture<String>> futures = new ConcurrentHashMap<>();
    private final Map<String, SubmitContext> submitContexts = new ConcurrentHashMap<>();

    public AgentProtocolTaskStore(
            AgentFactory agentFactory,
            ProtocolTaskRepository taskRepository,
            AgentProtocolEventBus eventBus,
            AgentProtocolProperties properties) {
        this(agentFactory, taskRepository, eventBus, properties, List.of());
    }

    public AgentProtocolTaskStore(
            AgentFactory agentFactory,
            ProtocolTaskRepository taskRepository,
            AgentProtocolEventBus eventBus,
            AgentProtocolProperties properties,
            List<RuntimeContextCustomizer> runtimeContextCustomizers) {
        this.agentFactory = Objects.requireNonNull(agentFactory, "agentFactory");
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository");
        this.eventBus = eventBus != null ? eventBus : new AgentProtocolTaskEventBus();
        this.properties = properties != null ? properties : new AgentProtocolProperties();
        this.runtimeContextCustomizers =
                runtimeContextCustomizers == null
                        ? List.of()
                        : List.copyOf(runtimeContextCustomizers);
    }

    public AgentProtocolTaskStore(
            AgentFactory agentFactory, ProtocolTaskRepository taskRepository) {
        this(
                agentFactory,
                taskRepository,
                new AgentProtocolTaskEventBus(),
                new AgentProtocolProperties());
    }

    public AgentProtocolTaskStore(
            HarnessAgent harnessAgent, ProtocolTaskRepository taskRepository) {
        this(AgentFactory.fixed(harnessAgent), taskRepository);
    }

    public AgentProtocolEventBus eventBus() {
        return eventBus;
    }

    /**
     * Starts a background task. Idempotent while running: if a non-terminal task with the same id
     * already exists, returns without starting a duplicate run.
     *
     * @throws IllegalStateException if the task id already completed (HTTP 409)
     */
    public void submit(String taskId, String agentId, String input) {
        submit(taskId, agentId, input, null);
    }

    public void submit(String taskId, String agentId, String input, Map<String, Object> context) {
        Optional<TaskRecord> existing = taskRepository.find(taskId);
        if (existing.isPresent() && existing.get().getStatus().isTerminal()) {
            throw new IllegalStateException("task_id already finished: " + taskId);
        }
        CompletableFuture<String> prior = futures.get(taskId);
        if (prior != null && !prior.isDone()) {
            return;
        }

        SubmitContext ctx = SubmitContext.from(context);
        submitContexts.put(taskId, ctx);

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
                CompletableFuture.supplyAsync(
                        () -> runAgent(taskId, agentId, input, ctx, null), executor);
        futures.put(taskId, f);
        f.whenComplete((r, ex) -> futures.remove(taskId));
    }

    /**
     * Resumes a task paused for HITL confirmation with the given decisions.
     *
     * @throws IllegalStateException if the task is not awaiting confirmation
     */
    public void resume(String taskId, List<RemoteConfirmDecision> decisions) {
        if (!properties.isHitlEnabled()) {
            throw new IllegalStateException("HITL is disabled on this server");
        }
        Optional<TaskRecord> existing = taskRepository.find(taskId);
        if (existing.isEmpty() || !existing.get().isAwaitingConfirm()) {
            throw new IllegalStateException("task is not awaiting confirmation: " + taskId);
        }
        TaskRecord rec = existing.get();
        CompletableFuture<String> prior = futures.get(taskId);
        if (prior != null && !prior.isDone()) {
            throw new IllegalStateException("task is already running: " + taskId);
        }

        List<RemotePendingConfirm> pending =
                rec.getPendingConfirms() != null ? rec.getPendingConfirms() : List.of();
        List<ConfirmResult> confirmResults = toConfirmResults(pending, decisions);

        rec.setAwaitingConfirm(false);
        rec.setPendingConfirms(null);
        rec.setStatus(TaskStatus.RUNNING);
        persist(rec);

        SubmitContext ctx = submitContexts.getOrDefault(taskId, SubmitContext.empty());
        String agentId = rec.getSubAgentId();
        String dummyInput = ""; // resume uses confirm metadata, not a new user prompt
        CompletableFuture<String> f =
                CompletableFuture.supplyAsync(
                        () -> runAgent(taskId, agentId, dummyInput, ctx, confirmResults), executor);
        futures.put(taskId, f);
        f.whenComplete((r, ex) -> futures.remove(taskId));
    }

    private String runAgent(
            String taskId,
            String agentId,
            String input,
            SubmitContext submitCtx,
            List<ConfirmResult> confirmResults) {
        try {
            updateRunning(taskId, agentId);
            AgentRequest request =
                    new AgentRequest(
                            taskId,
                            agentId,
                            input,
                            submitCtx.userId(),
                            submitCtx.parentSessionId(),
                            confirmResults != null,
                            submitCtx.raw());
            RuntimeContext ctx = buildRuntimeContext(request);

            Msg.Builder msgBuilder =
                    Msg.builder().role(MsgRole.USER).textContent(input != null ? input : "");
            if (confirmResults != null && !confirmResults.isEmpty()) {
                Map<String, Object> meta = new HashMap<>();
                meta.put(Msg.METADATA_CONFIRM_RESULTS, confirmResults);
                msgBuilder.metadata(meta);
            }
            Msg msg = msgBuilder.build();

            HarnessAgent agent = agentFactory.create(request);
            if (agent == null) {
                throw new IllegalStateException(
                        "AgentFactory returned no agent for agent_id=" + agentId);
            }
            AtomicReference<Msg> resultRef = new AtomicReference<>();
            String detail = submitCtx.detail();

            Flux<AgentEvent> events = agent.streamEvents(msg, ctx);
            events.doOnNext(
                            event -> {
                                if (event instanceof AgentResultEvent resultEvent) {
                                    resultRef.set(resultEvent.getResult());
                                }
                                if (!properties.isStreamingEnabled()) {
                                    return;
                                }
                                RemoteEventCodec.fromAgentEvent(event)
                                        .filter(dto -> RemoteEventCodec.matchesDetail(dto, detail))
                                        .ifPresent(
                                                dto -> {
                                                    dto.setAgentId(agentId);
                                                    eventBus.publish(taskId, dto);
                                                });
                            })
                    .blockLast(Duration.ofHours(2));

            Msg reply = resultRef.get();
            if (reply != null
                    && reply.getGenerateReason() == GenerateReason.PERMISSION_ASKING
                    && properties.isHitlEnabled()) {
                List<RemotePendingConfirm> pending = extractPendingConfirms(reply);
                markAwaitingConfirm(taskId, agentId, pending);
                RemoteAgentEvent require = new RemoteAgentEvent();
                require.setType(RemoteEventType.REQUIRE_CONFIRM);
                require.setAgentId(agentId);
                require.setPendingConfirms(pending);
                require.setStatus("awaiting_confirm");
                eventBus.publish(taskId, require);
                return AWAITING_CONFIRM_SENTINEL;
            }

            String text = reply != null ? reply.getTextContent() : "";
            clearSubmitContext(taskId);
            update(taskId, TaskStatus.COMPLETED, text, null, agentId, false, null);
            publishStatus(taskId, agentId, "success", null);
            eventBus.complete(taskId);
            return text;
        } catch (Exception e) {
            String err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Protocol task {} failed", taskId, e);
            clearSubmitContext(taskId);
            update(taskId, TaskStatus.FAILED, null, err, agentId, false, null);
            RemoteAgentEvent error = new RemoteAgentEvent();
            error.setType(RemoteEventType.RUN_ERROR);
            error.setAgentId(agentId);
            error.setError(err);
            error.setStatus("error");
            eventBus.publish(taskId, error);
            eventBus.complete(taskId);
            throw new RuntimeException(e);
        }
    }

    /**
     * Builds the agent's runtime context for one run: session/user identity, the caller's {@code
     * context.attributes} under {@link AgentProtocolConstants#RUNTIME_CONTEXT_ATTRIBUTES_KEY}, and
     * whatever the registered {@link RuntimeContextCustomizer}s add on top.
     *
     * <p>Attributes stay behind a single namespaced key by default so that no caller-supplied name
     * can shadow a key the framework reads (e.g. {@code agentId}); promoting individual attributes
     * to their own keys is an explicit opt-in through a customizer.
     */
    private RuntimeContext buildRuntimeContext(AgentRequest request) {
        RuntimeContext.Builder builder = RuntimeContext.builder().sessionId(request.taskId());
        if (request.userId() != null) {
            builder.userId(request.userId());
        }
        Map<String, Object> attributes = request.attributes();
        if (!attributes.isEmpty()) {
            builder.put(AgentProtocolConstants.RUNTIME_CONTEXT_ATTRIBUTES_KEY, attributes);
        }
        for (RuntimeContextCustomizer customizer : runtimeContextCustomizers) {
            customizer.customize(request, builder);
        }
        return builder.build();
    }

    private void publishStatus(String taskId, String agentId, String status, String error) {
        RemoteAgentEvent evt = new RemoteAgentEvent();
        evt.setType(RemoteEventType.STATUS);
        evt.setAgentId(agentId);
        evt.setStatus(status);
        evt.setError(error);
        eventBus.publish(taskId, evt);
    }

    private void markAwaitingConfirm(
            String taskId, String agentId, List<RemotePendingConfirm> pending) {
        Optional<TaskRecord> prev = taskRepository.find(taskId);
        TaskRecord r =
                prev.orElseGet(
                        () ->
                                new TaskRecord(
                                        taskId,
                                        agentId != null ? agentId : "default",
                                        AgentProtocolConstants.PROTOCOL_AGENT_ID,
                                        AgentProtocolConstants.PROTOCOL_SESSION_ID,
                                        null));
        r.setStatus(TaskStatus.RUNNING);
        r.setAwaitingConfirm(true);
        r.setPendingConfirms(pending);
        r.touch();
        persist(r);
    }

    private static List<RemotePendingConfirm> extractPendingConfirms(Msg reply) {
        List<RemotePendingConfirm> pending = new ArrayList<>();
        for (ToolUseBlock block : reply.getContentBlocks(ToolUseBlock.class)) {
            if (block.getState() == ToolCallState.ASKING) {
                pending.add(
                        new RemotePendingConfirm(
                                block.getId(), block.getName(), toJsonQuiet(block.getInput())));
            }
        }
        if (pending.isEmpty()) {
            // Fall back to all tool uses if state wasn't stamped
            for (ToolUseBlock block : reply.getContentBlocks(ToolUseBlock.class)) {
                pending.add(
                        new RemotePendingConfirm(
                                block.getId(), block.getName(), toJsonQuiet(block.getInput())));
            }
        }
        return pending;
    }

    private static List<ConfirmResult> toConfirmResults(
            List<RemotePendingConfirm> pending, List<RemoteConfirmDecision> decisions) {
        Map<String, Boolean> byId = new HashMap<>();
        if (decisions != null) {
            for (RemoteConfirmDecision d : decisions) {
                if (d.getToolCallId() != null) {
                    byId.put(d.getToolCallId(), d.isApproved());
                }
            }
        }
        List<ConfirmResult> results = new ArrayList<>();
        for (RemotePendingConfirm p : pending) {
            boolean approved = byId.getOrDefault(p.getToolCallId(), false);
            Map<String, Object> input = parseInputQuiet(p.getToolInputJson());
            ToolUseBlock toolCall =
                    ToolUseBlock.builder()
                            .id(p.getToolCallId())
                            .name(p.getToolName())
                            .input(input)
                            .build();
            results.add(new ConfirmResult(approved, toolCall));
        }
        return results;
    }

    private void persist(TaskRecord r) {
        taskRepository.save(r);
    }

    private void updateRunning(String taskId, String agentId) {
        update(taskId, TaskStatus.RUNNING, null, null, agentId, false, null);
    }

    private void update(
            String taskId,
            TaskStatus status,
            String result,
            String error,
            String agentId,
            boolean awaitingConfirm,
            List<RemotePendingConfirm> pendingConfirms) {
        Optional<TaskRecord> prev = taskRepository.find(taskId);
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
        r.setAwaitingConfirm(awaitingConfirm);
        r.setPendingConfirms(pendingConfirms);
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
        Optional<TaskRecord> rec = taskRepository.find(taskId);
        if (rec.isEmpty()) {
            m.put("status", "error");
            m.put("error", "task not found");
            return m;
        }
        TaskRecord r = rec.get();
        m.put("status", mapStatus(r));
        if (r.getErrorMessage() != null) {
            m.put("error", r.getErrorMessage());
        }
        if (r.getStatus() == TaskStatus.COMPLETED && r.getResult() != null) {
            m.put("result", r.getResult());
        }
        if (r.isAwaitingConfirm() && r.getPendingConfirms() != null) {
            m.put("pending_confirms", r.getPendingConfirms());
        }
        CompletableFuture<String> f = futures.get(taskId);
        if (f != null
                && !f.isDone()
                && !r.isAwaitingConfirm()
                && (r.getStatus() == TaskStatus.RUNNING || r.getStatus() == TaskStatus.PENDING)) {
            m.put("status", "running");
        }
        return m;
    }

    /**
     * Blocks until the task reaches a terminal state or the timeout elapses. Does not complete while
     * awaiting confirmation — returns {@code awaiting_confirm} so the client can resume.
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
            Optional<TaskRecord> rec = taskRepository.find(taskId);
            if (rec.isEmpty()) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("status", "error");
                err.put("error", "task not found");
                return err;
            }
            TaskRecord r = rec.get();
            if (r.isAwaitingConfirm()) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("status", "awaiting_confirm");
                if (r.getPendingConfirms() != null) {
                    out.put("pending_confirms", r.getPendingConfirms());
                }
                return out;
            }
            if (r.getStatus().isTerminal()) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("status", mapStatus(r));
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
        Optional<TaskRecord> rec = taskRepository.find(taskId);
        if (rec.isPresent()) {
            TaskRecord r = rec.get();
            r.setCancelRequested(true);
            r.setAwaitingConfirm(false);
            if (!r.getStatus().isTerminal()) {
                r.setStatus(TaskStatus.CANCELLED);
            }
            persist(r);
        }
        eventBus.complete(taskId);
        clearSubmitContext(taskId);
    }

    /**
     * Drops in-memory submit metadata for {@code taskId}. Kept during {@code awaiting_confirm} so
     * {@link #resume} can reuse detail/userId, and cleared only on terminal completion/cancel.
     */
    private void clearSubmitContext(String taskId) {
        submitContexts.remove(taskId);
    }

    /** Visible for tests — whether a submit context is still retained for {@code taskId}. */
    boolean hasSubmitContext(String taskId) {
        return submitContexts.containsKey(taskId);
    }

    private static String mapStatus(TaskRecord r) {
        if (r.isAwaitingConfirm()) {
            return "awaiting_confirm";
        }
        return switch (r.getStatus()) {
            case PENDING -> "pending";
            case RUNNING -> "running";
            case COMPLETED -> "success";
            case FAILED -> "error";
            case CANCELLED -> "cancelled";
        };
    }

    private static String toJsonQuiet(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseInputQuiet(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = JSON.readValue(json, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> out = new HashMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (e.getKey() != null) {
                        out.put(String.valueOf(e.getKey()), e.getValue());
                    }
                }
                return out;
            }
            return Map.of("raw", parsed);
        } catch (JsonProcessingException e) {
            return Map.of("raw", json);
        }
    }

    /**
     * Parsed protocol fields plus the {@code raw} submission map, which is handed to the {@link
     * AgentFactory} so custom context keys survive routing on both submit and resume.
     */
    private record SubmitContext(
            String userId, String parentSessionId, String detail, Map<String, Object> raw) {
        static SubmitContext empty() {
            return new SubmitContext(null, null, "status", Map.of());
        }

        static SubmitContext from(Map<String, Object> raw) {
            if (raw == null || raw.isEmpty()) {
                return empty();
            }
            String userId = stringVal(raw.get("user_id"));
            String parentSessionId = stringVal(raw.get("parent_session_id"));
            String detail = stringVal(raw.get("detail"));
            if (detail == null || detail.isBlank()) {
                detail = "status";
            }
            return new SubmitContext(userId, parentSessionId, detail, raw);
        }

        private static String stringVal(Object v) {
            return v == null ? null : String.valueOf(v);
        }
    }
}
