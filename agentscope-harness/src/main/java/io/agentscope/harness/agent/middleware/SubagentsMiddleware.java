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
package io.agentscope.harness.agent.middleware;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.context.ContextManifest;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.subagent.AgentSpecLoader;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.SubagentFactory;
import io.agentscope.harness.agent.subagent.SubagentSpecGenerator;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskDelivery;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import io.agentscope.harness.agent.tool.AgentGenerateTool;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import io.agentscope.harness.agent.tool.TaskTool;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Middleware that provides the managed subagent mechanism.
 *
 * <p>In <strong>default mode</strong> (standalone harness setup), this middleware creates an
 * {@link AgentSpawnTool} backed by a {@link DefaultAgentManager}. In <strong>session mode</strong>
 * (orchestrated via {@code AgentBootstrap}), an external tool (typically {@code SessionsTool})
 * is injected, replacing the default {@link AgentSpawnTool}.
 *
 * <p>Responsibilities:
 *
 * <ol>
 *   <li>Exposes the subagent tool and {@link TaskTool} as agent tools (callers query via
 *       {@link #getTools()} and register them on the toolkit at orchestration time).
 *   <li>On every {@link #onAgent}, reloads subagent declarations from the workspace filesystem
 *       (namespace-scoped) to support per-user subagent isolation.
 *   <li>Prepends the stable subagent usage guidance to the leading SYSTEM message of every
 *       {@link ReasoningInput}, and appends the volatile state (available agent catalog, async
 *       task summary) as a synthetic tail message. Because the framework rebuilds the SYSTEM
 *       message from a frozen base each iteration, this is safe — content never accumulates
 *       across iterations, and keeping the volatile parts out of the head keeps the system
 *       bytes stable across steps (prefix-cache friendly).
 * </ol>
 */
public class SubagentsMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(SubagentsMiddleware.class);

    private static final DateTimeFormatter ISO_SHORT =
            DateTimeFormatter.ofPattern("HH:mm'Z'").withZone(ZoneOffset.UTC);

    private static final int MAX_TASK_SUMMARY_ENTRIES = 10;

    /**
     * Cap on individual task results aggregated into a single {@code <system-reminder>} delivery
     * message. Anything beyond this gets a "... and N more — call task_list()" footer; the LLM
     * can still fetch each one explicitly.
     */
    static final int MAX_DELIVERIES_PER_REMINDER = 10;

    private static final String TASK_HINT_SOURCE = "subagent_task";
    private static final String TASK_HINT_HEADER = "<system-reminder>Background subagent task '";
    private static final String TASK_HINT_AGENT = "' (agent=";
    private static final String METADATA_TASK_HINT_ID = "subagent_task_hint_id";

    // @formatter:off
    private static final String SUBAGENT_SECTION_TEMPLATE =
            """

            ## Subagents

            Delegate self-contained work when isolation or parallelism is useful. Each subagent
            starts a fresh conversation: include the goal, needed paths/context, constraints,
            and expected deliverable. It does not see your conversation. Do not re-spawn or
            re-send a denied request to bypass permissions.

            Use `%s` to spawn, `%s` for follow-ups, and `%s` to list agents. Keep the full
            agent_key for follow-ups; it is distinct from agent_id, session_id, and task_id.
            Each invocation produces one result. A live local agent retains its history; remote backends may start a new conversation. Later resumption
            depends on the declaration (persistSession) and backend, so do not assume persistence.

            With timeout_seconds=0, work runs in the background. A sync timeout returns
            status: timeout_promoted and task_id: the task is already running. Do NOT retry it.
            Results may arrive as reminders in a later reasoning step. Continue independent work;
            use task_output(block=false) when the actual result is missing or truncated.

            When dependent work must wait, use wait_async_results(task_ids=...) for a known group,
            or wait_async_results(wait_all=true) for the snapshot of current non-terminal tasks.
            Both return results. With neither option, legacy inbox-any mode wakes on ANY inbox
            message; it does NOT wait for every task. task_output(block=true) waits for one task.
            task_list shows tracked tasks; task_cancel cancels running work.

            Reconcile results before declaring success. COMPLETED describes execution, not goal
            acceptance; status: max_iterations / partial means unfinished work remains.

            %s
            """;
    // @formatter:on

    private final List<SubagentEntry> baseEntries;
    private volatile Object subagentTool;
    private volatile AgentSpawnTool ownedAgentSpawnTool;
    private final TaskTool taskTool;
    private final TaskRepository taskRepository;
    private final boolean isSessionMode;

    private final AbstractFilesystem filesystem;
    private final Path mainWorkspace;
    private final Function<SubagentDeclaration, SubagentFactory> factoryBuilder;
    private final DefaultAgentManager agentManager;
    private final WorkspaceManager workspaceManager;

    private record SubagentSnapshot(
            List<SubagentEntry> entries, DefaultAgentManager agentManager) {}

    /**
     * Optional {@link AgentGenerateTool} for LLM-driven subagent spec generation. Lazy because
     * the spec generator needs a {@link io.agentscope.core.model.Model} instance that callers
     * supply via {@link #enableAgentGenerateTool}; null when not enabled (OOTB default).
     */
    private volatile AgentGenerateTool agentGenerateTool;

    /**
     * Default mode: creates {@link AgentSpawnTool} + {@link DefaultAgentManager} internally.
     */
    public SubagentsMiddleware(
            List<SubagentEntry> entries,
            TaskRepository taskRepository,
            WorkspaceManager workspaceManager,
            AbstractFilesystem filesystem,
            Path mainWorkspace,
            Function<SubagentDeclaration, SubagentFactory> factoryBuilder) {
        this.baseEntries = List.copyOf(entries);
        this.isSessionMode = false;
        DefaultAgentManager dam = new DefaultAgentManager(entries, workspaceManager);
        this.agentManager = dam;
        this.workspaceManager = workspaceManager;
        java.util.Objects.requireNonNull(taskRepository, "taskRepository");
        this.taskRepository = taskRepository;
        AgentSpawnTool spawnTool = new AgentSpawnTool(dam, taskRepository, 0);
        this.subagentTool = spawnTool;
        this.ownedAgentSpawnTool = spawnTool;
        this.taskTool = new TaskTool(taskRepository);
        this.filesystem = filesystem;
        this.mainWorkspace = mainWorkspace;
        this.factoryBuilder = factoryBuilder;
    }

    /**
     * Default mode without dynamic reload support.
     */
    public SubagentsMiddleware(
            List<SubagentEntry> entries,
            TaskRepository taskRepository,
            WorkspaceManager workspaceManager) {
        this(entries, taskRepository, workspaceManager, null, null, null);
    }

    /**
     * AgentStateStore mode: uses the externally provided tool (typically {@code SessionsTool}).
     */
    public SubagentsMiddleware(
            List<SubagentEntry> entries,
            Object externalSubagentTool,
            TaskRepository taskRepository) {
        this.baseEntries = List.copyOf(entries);
        this.isSessionMode = true;
        this.agentManager = null;
        this.workspaceManager = null;
        this.subagentTool = externalSubagentTool;
        this.ownedAgentSpawnTool = null;
        java.util.Objects.requireNonNull(taskRepository, "taskRepository");
        this.taskRepository = taskRepository;
        this.taskTool = new TaskTool(taskRepository);
        this.filesystem = null;
        this.mainWorkspace = null;
        this.factoryBuilder = null;
    }

    /** Narrow declaration: subclasses overriding more hooks must extend this set. */
    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_AGENT, ExtensionPoint.ON_REASONING);
    }

    /**
     * Enables the {@code agent_generate} tool, which lets the LLM author new subagent specs from
     * a description. Off by default — the generator needs a {@link io.agentscope.core.model.Model}
     * and writes to the workspace, so callers opt in explicitly.
     *
     * <p>Only effective in default (non-session) mode, where this middleware owns a
     * {@link DefaultAgentManager}. In session mode the manager is external and this call is a
     * no-op.
     *
     * @param generator a {@link SubagentSpecGenerator} backed by the model that should author specs
     * @return this middleware for chaining
     */
    public SubagentsMiddleware enableAgentGenerateTool(SubagentSpecGenerator generator) {
        if (isSessionMode || agentManager == null) {
            log.debug("enableAgentGenerateTool ignored in session mode (no internal manager)");
            return this;
        }
        this.agentGenerateTool = new AgentGenerateTool(generator, agentManager, filesystem);
        return this;
    }

    /** Returns the {@link TaskRepository} used by this middleware. */
    public TaskRepository getTaskRepository() {
        return taskRepository;
    }

    /**
     * Wires a {@link MessageBus} so that background task completions signal the session inbox and
     * enqueue a wakeup. This enables the {@link
     * io.agentscope.harness.agent.gateway.WakeupDispatcher} to re-trigger idle sessions when
     * subagent work finishes.
     *
     * <p>Registers a {@link TaskRepository.TaskCompletionCallback} on the underlying repository.
     * Safe to call multiple times — each call replaces the previous callback.
     *
     * @param messageBus the application message bus
     * @param agentId the parent agent id (for wakeup routing)
     * @return this middleware for chaining
     */
    public SubagentsMiddleware wireMessageBus(MessageBus messageBus, String agentId) {
        wireMessageBus(taskRepository, messageBus, agentId);
        return this;
    }

    /** Shares the completion callback between static and dynamic subagent routing. */
    public static void wireMessageBus(TaskRepository repository, MessageBus bus, String agentId) {
        if (bus == null) return;
        repository.setCompletionCallback(
                (rc, taskId, subAgentId, sessionId, result) -> {
                    String userId = rc != null ? rc.getUserId() : null;
                    Map<String, Object> payload =
                            Map.of(
                                    "type",
                                    "hint",
                                    "id",
                                    UUID.randomUUID().toString().replace("-", ""),
                                    "source",
                                    TASK_HINT_SOURCE,
                                    "taskId",
                                    taskId != null ? taskId : "",
                                    "hint",
                                    TASK_HINT_HEADER
                                            + taskId
                                            + TASK_HINT_AGENT
                                            + subAgentId
                                            + ") has completed.\n\nResult:\n\n"
                                            + (result != null ? result : "(no output)")
                                            + "</system-reminder>");
                    bus.inboxPush(sessionId, payload)
                            .onErrorResume(
                                    error -> {
                                        log.warn(
                                                "Failed to signal task {} in the inbox",
                                                taskId,
                                                error);
                                        return Mono.empty();
                                    })
                            .then(
                                    bus.enqueueWakeup(
                                            userId != null ? userId : "",
                                            sessionId,
                                            agentId != null ? agentId : ""))
                            .subscribe(
                                    unused -> {},
                                    err ->
                                            log.warn(
                                                    "Failed to enqueue wakeup after task {}"
                                                            + " completion: {}",
                                                    taskId,
                                                    err.getMessage()));
                });
    }

    /**
     * Wires a gateway bridge into the internal {@link AgentSpawnTool}, enabling spawned subagents
     * to be exposed as user-addressable threads. Only effective in default (non-session) mode.
     *
     * @param bridge the bridge implementation (typically obtained from
     *     {@link io.agentscope.harness.agent.gateway.GatewayBootstrap#gatewayBridge()})
     * @return this middleware for chaining
     */
    public SubagentsMiddleware setGatewayBridge(
            io.agentscope.harness.agent.gateway.SubagentGatewayBridge bridge) {
        if (isSessionMode || agentManager == null) {
            log.debug("setGatewayBridge ignored in session mode (no internal manager)");
            return this;
        }
        // Mutate the bridge on the live tool instead of replacing it: the toolkit binds
        // agent_spawn to the AgentSpawnTool instance returned by getTools() at orchestration
        // time, so a fresh instance here would never be invoked and exposure would silently
        // never fire.
        if (this.subagentTool instanceof AgentSpawnTool ast) {
            ast.setGatewayBridge(bridge);
        } else {
            AgentSpawnTool spawnTool = new AgentSpawnTool(agentManager, taskRepository, 0, bridge);
            this.subagentTool = spawnTool;
            this.ownedAgentSpawnTool = spawnTool;
        }
        return this;
    }

    /** Closes the internally-created spawn tool without touching an injected external tool. */
    public synchronized void closeOwnedAgentSpawnTool() {
        AgentSpawnTool spawnTool = ownedAgentSpawnTool;
        if (spawnTool != null) {
            ownedAgentSpawnTool = null;
            spawnTool.close();
        }
    }

    /**
     * Returns the internal {@link DefaultAgentManager} that can re-materialize subagents, or
     * {@code null} in session mode (external tool). Used to wire a gateway materializer for
     * cross-node exposed-subagent recovery.
     */
    public DefaultAgentManager getAgentManager() {
        return agentManager;
    }

    /**
     * Returns the tool instances this middleware contributes to the agent toolkit. The caller
     * is responsible for registering them on the toolkit at orchestration time.
     *
     * <p>When {@link #enableAgentGenerateTool} has been called, the returned list additionally
     * contains an {@link AgentGenerateTool}.
     */
    public List<Object> getTools() {
        if (baseEntries.isEmpty()) {
            return List.of();
        }
        AgentGenerateTool gen = this.agentGenerateTool;
        if (gen != null) {
            return List.of(subagentTool, taskTool, gen);
        }
        return List.of(subagentTool, taskTool);
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        if (ctx != null) {
            installSnapshot(ctx, loadSubagentSnapshot(ctx));
        }
        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        RuntimeContext rc = ctx != null ? ctx : RuntimeContext.empty();
        List<SubagentEntry> currentEntries = snapshotFor(rc).entries();
        return reasonWithSubagents(
                agent, rc, input, next, taskRepository, currentEntries, isSessionMode, true);
    }

    static Flux<AgentEvent> reasonWithSubagents(
            Agent agent,
            RuntimeContext rc,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next,
            TaskRepository repository,
            List<SubagentEntry> entries,
            boolean sessionMode,
            boolean collectDeliveries) {
        String sessionId = rc.getSessionId();
        List<TaskDelivery> pending =
                collectDeliveries && agent instanceof ReActAgent
                        ? repository.findPendingDeliveries(rc, sessionId)
                        : List.of();
        List<Msg> rebuilt =
                new ArrayList<>(
                        prependToSystemMessage(
                                input.messages(), renderSubagentSection(entries, sessionMode)));
        Msg delivery = selectDeliveryReminder(rebuilt, pending, repository, rc);
        if (delivery != null) rebuilt.add(delivery);
        Msg stateMsg = buildSubagentStateMsg(entries, buildTaskSummary(repository, rc));
        if (stateMsg != null) rebuilt.add(stateMsg);

        Flux<AgentEvent> downstream =
                next.apply(new ReasoningInput(rebuilt, input.tools(), input.options()));
        if (delivery == null || !(agent instanceof ReActAgent react)) return downstream;

        // A request projection survives compaction. Move it into regular history only after
        // the completed model call included it, then persist history before acknowledging it.
        AtomicReference<String> submittedCall = new AtomicReference<>();
        AtomicBoolean received = new AtomicBoolean();
        Mono<AgentEvent> acknowledge =
                Mono.<AgentEvent>fromRunnable(
                                () -> {
                                    RuntimeContext.resolveAgentState(rc, react)
                                            .contextMutable()
                                            .add(delivery);
                                    react.saveAgentState(rc);
                                    int shown =
                                            Math.min(pending.size(), MAX_DELIVERIES_PER_REMINDER);
                                    for (TaskDelivery task : pending.subList(0, shown)) {
                                        try {
                                            repository.markDelivered(rc, sessionId, task.taskId());
                                        } catch (RuntimeException error) {
                                            log.warn(
                                                    "Failed to mark task {} as delivered",
                                                    task.taskId(),
                                                    error);
                                        }
                                    }
                                })
                        .subscribeOn(Schedulers.boundedElastic());
        return downstream
                .doOnNext(
                        event -> {
                            if (event instanceof ModelCallStartEvent start) {
                                var metadata = start.getMetadata();
                                Object manifest =
                                        metadata == null ? null : metadata.get("contextManifest");
                                boolean included =
                                        !(manifest instanceof ContextManifest compiled)
                                                || compiled.items().stream()
                                                        .map(ContextManifest.Item::sourceId)
                                                        .anyMatch(delivery.getId()::equals);
                                submittedCall.set(included ? start.getReplyId() : null);
                            } else if (event instanceof ModelCallEndEvent end
                                    && end.getReplyId().equals(submittedCall.get())) {
                                received.set(true);
                            }
                        })
                .concatWith(Mono.defer(() -> received.get() ? acknowledge : Mono.empty()));
    }

    /** Passes the compatible inbox payload to the next middleware without entering history. */
    static Msg projectTaskHint(Map<String, Object> payload, HintBlock hint) {
        if (!TASK_HINT_SOURCE.equals(hint.getSource())) return null;
        String text = hint.getHint();
        String taskId = payload.get("taskId") instanceof String id ? id : "";
        if (taskId.isBlank() && text.startsWith(TASK_HINT_HEADER)) {
            int end = text.indexOf(TASK_HINT_AGENT, TASK_HINT_HEADER.length());
            if (end > TASK_HINT_HEADER.length()) {
                taskId = text.substring(TASK_HINT_HEADER.length(), end);
            }
        }
        if (taskId.isBlank()) return null;
        return Msg.builder()
                .role(MsgRole.USER)
                .name("system")
                .textContent(text)
                .metadata(Map.of(Msg.METADATA_SYNTHETIC, true, METADATA_TASK_HINT_ID, taskId))
                .build();
    }

    private static Msg selectDeliveryReminder(
            List<Msg> messages,
            List<TaskDelivery> pending,
            TaskRepository repository,
            RuntimeContext rc) {
        Map<String, String> hints = new LinkedHashMap<>();
        messages.removeIf(
                msg -> {
                    if (msg.getMetadata() == null
                            || !(msg.getMetadata().get(METADATA_TASK_HINT_ID)
                                    instanceof String taskId)) {
                        return false;
                    }
                    hints.putIfAbsent(taskId, msg.getTextContent());
                    return true;
                });
        pending.forEach(task -> hints.remove(task.taskId()));
        hints.keySet().removeIf(taskId -> repository.isDelivered(rc, rc.getSessionId(), taskId));
        if (hints.isEmpty()) return pending.isEmpty() ? null : buildDeliveryReminder(pending);
        StringBuilder text = new StringBuilder();
        if (!pending.isEmpty()) text.append(renderTaskResults(pending));
        for (String hint : hints.values()) {
            if (!text.isEmpty()) text.append("\n\n");
            text.append(hint);
        }
        return deliveryReminder(text.toString());
    }

    /**
     * Builds the single aggregated {@code <system-reminder>} message that surfaces newly-terminal
     * background tasks to the LLM. Caps at {@link #MAX_DELIVERIES_PER_REMINDER} entries; the
     * remainder is mentioned in a tail line so the LLM knows to call {@code task_list()} if it
     * wants the full set.
     *
     * <p>Role / metadata mirror {@code TaskReminderMiddleware} so downstream UI and persistence
     * layers can recognise this as system-originated synthetic content rather than user input.
     */
    static Msg buildDeliveryReminder(List<TaskDelivery> deliveries) {
        return deliveryReminder(renderTaskResults(deliveries));
    }

    private static String renderTaskResults(List<TaskDelivery> deliveries) {
        int total = deliveries.size();
        int shown = Math.min(total, MAX_DELIVERIES_PER_REMINDER);
        StringBuilder sb = new StringBuilder();
        sb.append("<system-reminder>\n");
        sb.append(total).append(" background subagent task");
        sb.append(total == 1 ? " has" : "s have");
        sb.append(" completed since your last turn. ");
        sb.append("These results are now part of your conversation history.\n\n");
        for (int i = 0; i < shown; i++) {
            TaskDelivery d = deliveries.get(i);
            String state = stateLiteral(d.status());
            sb.append("<task id=\"").append(d.taskId()).append("\" state=\"").append(state);
            sb.append("\"");
            if (d.agentId() != null) {
                sb.append(" agent=\"").append(d.agentId()).append("\"");
            }
            sb.append(">\n");
            switch (d.status()) {
                case COMPLETED -> {
                    sb.append("<task_result>\n");
                    sb.append(d.result() != null ? d.result() : "");
                    sb.append("\n</task_result>\n");
                }
                case FAILED -> {
                    sb.append("<task_error>\n");
                    sb.append(d.errorMessage() != null ? d.errorMessage() : "(no error message)");
                    sb.append("\n</task_error>\n");
                }
                case CANCELLED -> sb.append("Task was cancelled before producing a result.\n");
                default ->
                        sb.append("Task ended in non-terminal state ")
                                .append(d.status())
                                .append('\n');
            }
            sb.append("</task>\n");
        }
        if (total > shown) {
            sb.append("\n... and ")
                    .append(total - shown)
                    .append(" more — call task_list() to inspect.\n");
        }
        return sb.append("</system-reminder>").toString();
    }

    private static Msg deliveryReminder(String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .name("system")
                .textContent(text)
                .metadata(
                        Map.of(
                                Msg.METADATA_SYNTHETIC,
                                true,
                                Msg.METADATA_REMINDER_KIND,
                                "task_delivery"))
                .build();
    }

    private static String stateLiteral(TaskStatus status) {
        return switch (status) {
            case COMPLETED -> "completed";
            case FAILED -> "error";
            case CANCELLED -> "cancelled";
            default -> status.name().toLowerCase();
        };
    }

    /**
     * Appends the given extra content to the leading SYSTEM message of {@code messages}.
     * If no SYSTEM message is present, a new one is inserted at index 0.
     */
    static List<Msg> prependToSystemMessage(List<Msg> messages, String extra) {
        if (extra == null || extra.isEmpty()) {
            return messages != null ? messages : List.of();
        }
        List<Msg> out = new ArrayList<>(messages != null ? messages.size() : 1);
        if (messages == null || messages.isEmpty() || messages.get(0).getRole() != MsgRole.SYSTEM) {
            out.add(
                    Msg.builder()
                            .name("system")
                            .role(MsgRole.SYSTEM)
                            .content(TextBlock.builder().text(extra).build())
                            .build());
            if (messages != null) {
                out.addAll(messages);
            }
            return out;
        }
        Msg sys = messages.get(0);
        String existing = sys.getTextContent() != null ? sys.getTextContent() : "";
        String merged = existing.isEmpty() ? extra : existing + "\n" + extra;
        Msg newSys =
                Msg.builder()
                        .id(sys.getId())
                        .name(sys.getName())
                        .role(MsgRole.SYSTEM)
                        .content(TextBlock.builder().text(merged).build())
                        .metadata(sys.getMetadata())
                        .timestamp(sys.getTimestamp())
                        .build();
        out.add(newSys);
        out.addAll(messages.subList(1, messages.size()));
        return out;
    }

    private SubagentSnapshot snapshotFor(RuntimeContext runtimeContext) {
        SubagentSnapshot existing = runtimeContext.get(SubagentSnapshot.class);
        if (existing != null) {
            return existing;
        }
        SubagentSnapshot snapshot = loadSubagentSnapshot(runtimeContext);
        installSnapshot(runtimeContext, snapshot);
        return snapshot;
    }

    private void installSnapshot(RuntimeContext runtimeContext, SubagentSnapshot snapshot) {
        runtimeContext.put(SubagentSnapshot.class, snapshot);
        runtimeContext.put(AgentSpawnTool.CTX_AGENT_MANAGER, snapshot.agentManager());
    }

    private SubagentSnapshot loadSubagentSnapshot(RuntimeContext runtimeContext) {
        if (filesystem == null || factoryBuilder == null || isSessionMode) {
            return new SubagentSnapshot(baseEntries, agentManager);
        }
        try {
            List<SubagentDeclaration> decls =
                    AgentSpecLoader.loadFromFilesystem(filesystem, runtimeContext, mainWorkspace);

            List<SubagentEntry> newEntries = new ArrayList<>(baseEntries);
            for (SubagentDeclaration decl : decls) {
                boolean alreadyExists =
                        baseEntries.stream().anyMatch(e -> e.name().equals(decl.getName()));
                if (!alreadyExists) {
                    newEntries.add(
                            new SubagentEntry(
                                    decl.getName(),
                                    decl.getDescription(),
                                    factoryBuilder.apply(decl),
                                    decl));
                }
            }
            List<SubagentEntry> snapshotEntries = List.copyOf(newEntries);
            return new SubagentSnapshot(
                    snapshotEntries, new DefaultAgentManager(snapshotEntries, workspaceManager));
        } catch (Exception e) {
            log.warn("Failed to reload subagent entries from filesystem: {}", e.getMessage());
            return new SubagentSnapshot(baseEntries, agentManager);
        }
    }

    /**
     * Renders the {@code ## Subagents} system-prompt section for the supplied entries. Shared
     * with {@link DynamicSubagentsMiddleware}. Contains only per-configuration stable guidance —
     * the volatile agent catalog and async task summary travel in the tail state message (see
     * {@link #buildSubagentStateMsg}) so the system head stays byte-stable across steps.
     */
    public static String renderSubagentSection(List<SubagentEntry> entries, boolean isSessionMode) {
        String spawnName = isSessionMode ? "sessions_spawn" : "agent_spawn";
        String sendName = isSessionMode ? "sessions_send" : "agent_send";
        String listName = isSessionMode ? "sessions_list" : "agent_list";
        String lifecycle =
                isSessionMode
                        ? ""
                        : "Use reusable=false for one-shot tasks, or reusable=true for follow-ups."
                                + " Release an idle retained subagent with `agent_release` to"
                                + " invalidate its agent_key and public handles.";
        return String.format(SUBAGENT_SECTION_TEMPLATE, spawnName, sendName, listName, lifecycle);
    }

    /**
     * Renders the visible agent catalog lines, or an empty string when no entry is visible.
     *
     * <p>Filters out entries whose declaration sets {@code hidden=true} or {@code mode=PRIMARY}:
     * the LLM should not see internal-use subagents (hidden) and cannot spawn primary-only
     * declarations (the {@link io.agentscope.harness.agent.subagent.DefaultAgentManager} rejects
     * such spawns anyway, so advertising them would be a footgun). Programmatic registrations
     * without a declaration ({@code entry.declaration() == null}) are always shown.
     */
    private static String renderSubagentCatalog(List<SubagentEntry> entries) {
        return entries.stream()
                .filter(SubagentsMiddleware::isVisibleToLlm)
                .map(e -> String.format("- `%s`: %s", e.name(), e.description()))
                .collect(Collectors.joining("\n"));
    }

    /**
     * Builds the synthetic tail message carrying the volatile subagent state: the available agent
     * catalog and the async task summary. Returns {@code null} when both parts are empty.
     *
     * <p>Role and metadata mirror {@link #buildDeliveryReminder}: the harness layout places this
     * message after the conversation (same zone as {@code runtime_materials} and {@code
     * plan_state}), and compaction keeps this request-only projection out of the durable summary.
     */
    static Msg buildSubagentStateMsg(List<SubagentEntry> entries, String taskSummary) {
        String catalog = renderSubagentCatalog(entries);
        boolean hasTasks = taskSummary != null && !taskSummary.isBlank();
        if (catalog.isEmpty() && !hasTasks) {
            return null;
        }
        StringBuilder sb = new StringBuilder("<RUNTIME_STATE>\n");
        if (!catalog.isEmpty()) {
            sb.append("### Available agent ids\n").append(catalog).append('\n');
        }
        if (hasTasks) {
            sb.append(taskSummary.strip()).append('\n');
        }
        sb.append("</RUNTIME_STATE>");
        return Msg.builder()
                .role(MsgRole.USER)
                .name("system")
                .content(TextBlock.builder().text(sb.toString()).build())
                .metadata(
                        Map.of(
                                Msg.METADATA_SYNTHETIC,
                                true,
                                Msg.METADATA_REMINDER_KIND,
                                "subagent_state"))
                .build();
    }

    /**
     * Whether an entry should appear in the LLM-facing list of available subagents.
     * Programmatic entries with no declaration default to visible.
     */
    private static boolean isVisibleToLlm(SubagentEntry e) {
        SubagentDeclaration decl = e.declaration();
        if (decl == null) return true;
        if (decl.isHidden()) return false;
        return decl.getMode() != SubagentDeclaration.Mode.PRIMARY;
    }

    /**
     * Builds a concise summary of undelivered tasks for the current session, or {@code null} if
     * none. Shared with {@link DynamicSubagentsMiddleware}.
     *
     * <p>Phase B-3: already-delivered tasks are filtered out — they live in the conversation
     * history as {@code <system-reminder>} blocks, so re-mentioning them here would (a) waste
     * tokens, (b) push genuinely still-running tasks past the {@link #MAX_TASK_SUMMARY_ENTRIES}
     * cap, and (c) invite the LLM to second-guess the push by re-fetching results.
     */
    public static String buildTaskSummary(TaskRepository repo, RuntimeContext ctx) {
        if (repo == null) {
            return null;
        }
        String sessionId = ctx != null ? ctx.getSessionId() : null;
        Collection<BackgroundTask> tasks = repo.listTasks(ctx, sessionId, null);
        if (tasks.isEmpty()) {
            return null;
        }

        List<BackgroundTask> visible = new ArrayList<>();
        for (BackgroundTask task : tasks) {
            if (repo.isDelivered(ctx, sessionId, task.getTaskId())) continue;
            visible.add(task);
        }
        if (visible.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder("\n### Async tasks (current session)\n");
        int count = 0;
        for (BackgroundTask task : visible) {
            if (count >= MAX_TASK_SUMMARY_ENTRIES) {
                sb.append("- ... (")
                        .append(visible.size() - MAX_TASK_SUMMARY_ENTRIES)
                        .append(" more — use task_list() to see all)\n");
                break;
            }
            sb.append("- task_id: ").append(task.getTaskId());
            if (task.getAgentId() != null) {
                sb.append("  agent: ").append(task.getAgentId());
            }
            sb.append("  status: ").append(task.getTaskStatus().name().toLowerCase());
            sb.append("  started: ").append(ISO_SHORT.format(task.getCreatedAt()));
            sb.append('\n');
            count++;
        }
        sb.append(
                "(Status only, not task results. For a terminal task, use task_output if its"
                        + " actual output is missing or truncated in your context. A terminal"
                        + " status alone does not establish acceptance.)\n");
        return sb.toString();
    }
}
