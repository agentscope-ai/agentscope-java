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
package io.agentscope.harness.agent.tool;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.EventSource;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.agent.SubagentEventBus;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.SubagentExposedEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.SessionIdUtils;
import io.agentscope.harness.agent.gateway.SubagentGatewayBridge;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

/**
 * Simple subagent tool for agent-internal use. Much lighter than {@code SessionsTool}:
 *
 * <ul>
 *   <li>{@code agent_spawn} — spawn a subagent, run task, return result (sync or async)
 *   <li>{@code agent_send} — send follow-up message to a previously spawned subagent
 *   <li>{@code agent_list} — list active subagents
 * </ul>
 *
 * <p>No sessions, no lanes, no run registry, no announce dispatch. Just "create agent, invoke,
 * return result". Uses {@link DefaultAgentManager} for agent creation and invocation only.
 *
 * <p>Async tasks ({@code timeout_seconds=0}) are submitted to the {@link TaskRepository} scoped
 * by the current session ID from {@link RuntimeContext}. This makes task state visible in
 * workspace storage for cross-node retrieval and recovery after compaction.
 *
 * <h2>Streaming</h2>
 *
 * <p>{@code agent_spawn} and {@code agent_send} return {@link Mono}{@code <String>} so that the
 * framework's reactive tool-invocation pipeline (see {@code ToolMethodInvoker}) can subscribe them
 * within the parent agent's streaming chain. When a {@link SubagentEventBus} is present in the
 * Reactor Context (injected by {@code AgentBase.createEventStream}), every child {@link
 * io.agentscope.core.agent.Event} is forwarded to the parent sink in real time, giving consumers
 * a flattened event stream across the full call hierarchy. When no bus is present (plain {@code
 * call()} mode), execution falls back to the non-streaming {@code invokeAgent} path with no
 * overhead.
 */
public class AgentSpawnTool {

    private static final Logger log = LoggerFactory.getLogger(AgentSpawnTool.class);

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 600;
    private static final int MAX_SPAWN_DEPTH = 3;

    /**
     * {@link RuntimeContext} string key for a per-call override of subagent user-exposure. Put a
     * {@link Boolean} (or its string form) under this key to control exposure for every
     * {@code agent_spawn} in the current call, independent of what the LLM requests.
     *
     * <p>Example:
     * <pre>{@code
     * RuntimeContext ctx = RuntimeContext.builder()
     *     .userId("user-1")
     *     .put(AgentSpawnTool.CTX_EXPOSE_TO_USER, true)
     *     .build();
     * }</pre>
     *
     * <p>Resolution precedence (highest first): this context value → the spawned subagent's
     * {@link SubagentDeclaration#getExposeToUser()} policy → the LLM's {@code expose_to_user}
     * argument → {@code false}.
     */
    public static final String CTX_EXPOSE_TO_USER = "agentscope.subagent.expose_to_user";

    private static final String BG_RESULT_TEMPLATE =
            """
            status: accepted
            task_id: %s
            Use task_output(task_id='%s', block=false) to check status, \
            task_cancel(task_id='%s') to cancel, or task_list() to see all tasks. \
            Do NOT call task_output immediately — the task has just started.\
            """;

    private final DefaultAgentManager agentManager;
    private final TaskRepository taskRepository;
    private final int parentSpawnDepth;
    private volatile SubagentGatewayBridge gatewayBridge;

    private record SpawnedAgent(
            String key, String agentId, String sessionId, String label, Agent agent, int depth) {}

    private final ConcurrentHashMap<String, SpawnedAgent> agentsByKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> labelToKey = new ConcurrentHashMap<>();

    /**
     * Creates an {@code AgentSpawnTool} that derives the active user-id from each tool call's
     * {@link RuntimeContext}, rather than a shared supplier — this prevents identity races when a
     * single agent instance serves concurrent callers.
     *
     * @param agentManager factory and invoker for subagents
     * @param taskRepository background task store
     * @param parentSpawnDepth current spawn-depth of the parent (0 for top-level main agent)
     */
    public AgentSpawnTool(
            DefaultAgentManager agentManager, TaskRepository taskRepository, int parentSpawnDepth) {
        this(agentManager, taskRepository, parentSpawnDepth, null);
    }

    /**
     * Creates an {@code AgentSpawnTool} with an optional gateway bridge for exposing subagents
     * as user-addressable threads.
     *
     * @param agentManager factory and invoker for subagents
     * @param taskRepository background task store
     * @param parentSpawnDepth current spawn-depth of the parent (0 for top-level main agent)
     * @param gatewayBridge optional bridge for thread exposure; null for standalone mode
     */
    public AgentSpawnTool(
            DefaultAgentManager agentManager,
            TaskRepository taskRepository,
            int parentSpawnDepth,
            SubagentGatewayBridge gatewayBridge) {
        this.agentManager = Objects.requireNonNull(agentManager, "agentManager");
        this.taskRepository = taskRepository;
        this.parentSpawnDepth = parentSpawnDepth;
        this.gatewayBridge = gatewayBridge;
    }

    /**
     * Wires (or re-wires) the gateway bridge used to expose subagents as user-addressable threads.
     *
     * <p>Mutating the bridge on the live instance — rather than constructing a replacement — is
     * essential: the toolkit binds {@code agent_spawn} to this exact object at orchestration time,
     * so a replacement tool would never receive calls. The bridge is typically supplied lazily,
     * after the agent is built, when its internal gateway is created (see
     * {@code HarnessAgent#ensureGateway}).
     *
     * @param gatewayBridge the bridge implementation, or {@code null} to disable exposure
     */
    public void setGatewayBridge(SubagentGatewayBridge gatewayBridge) {
        this.gatewayBridge = gatewayBridge;
    }

    @Tool(
            name = "agent_spawn",
            stateInjected = true,
            description =
                    """
                    Spawn an isolated subagent for delegated or background work. \
                    Every response starts with three lines: agent_key (pass this verbatim to \
                    agent_send as agent_key), agent_id (the subagent type name), and session_id \
                    (internal; do not use as agent_key). Sync mode returns the reply below that; \
                    async (timeout_seconds=0) adds task_id for task_output — task_id is NOT agent_key.\
                    """)
    public Mono<String> agentSpawn(
            RuntimeContext runtimeContext,
            AgentState parentState,
            @ToolParam(name = "agent_id", description = "Subagent identifier to instantiate")
                    String agentId,
            @ToolParam(
                            name = "task",
                            description = "Task or prompt to send to the spawned agent",
                            required = false)
                    String task,
            @ToolParam(
                            name = "label",
                            description =
                                    "Optional human-readable label for referencing via agent_send",
                            required = false)
                    String label,
            @ToolParam(
                            name = "timeout_seconds",
                            description =
                                    """
                                    Max seconds to wait for the task result. 0=fire-and-forget, \
                                    returns task_id. Default: 30. Max: 600.\
                                    """,
                            required = false)
                    Integer timeoutSeconds,
            @ToolParam(
                            name = "expose_to_user",
                            description =
                                    """
                                    When true, the spawned subagent becomes directly addressable \
                                    by the user via a thread_id handle. Returns thread_id in the \
                                    response. Requires a gateway bridge to be configured.\
                                    """,
                            required = false)
                    Boolean exposeToUser) {

        log.debug(
                "agent_spawn called: agentId={}, timeoutSeconds={}, task={}",
                agentId,
                timeoutSeconds,
                task);
        int nextDepth = parentSpawnDepth + 1;
        if (nextDepth > MAX_SPAWN_DEPTH) {
            log.warn("agent_spawn depth exceeded: depth={}, max={}", nextDepth, MAX_SPAWN_DEPTH);
            return Mono.just("Error: Maximum spawn depth exceeded (max=" + MAX_SPAWN_DEPTH + ")");
        }
        String canonLabel = label != null && !label.isBlank() ? label.trim() : null;

        Optional<Agent> agentOpt = agentManager.createAgentIfPresent(agentId, runtimeContext);
        if (agentOpt.isEmpty()) {
            if (agentManager.isPrimaryOnly(agentId)) {
                return Mono.just(
                        "Error: agent_id '"
                                + agentId
                                + "' is PRIMARY-only and cannot be spawned as a subagent.");
            }
            log.warn("agent_spawn unknown agentId={}, known={}", agentId, agentManager);
            return Mono.just("Error: Unknown agent_id: " + agentId);
        }
        log.debug("agent_spawn resolved: agentId={}", agentId);
        Agent agent = agentOpt.get();
        String currentUserId = runtimeContext != null ? runtimeContext.getUserId() : null;
        String parentSessionId = runtimeContext != null ? runtimeContext.getSessionId() : null;
        var declOpt = agentManager.getDeclaration(agentId);
        boolean persist = declOpt.map(SubagentDeclaration::isPersistSession).orElse(false);

        String key;
        String sessionId;
        if (persist) {
            String hash = deterministicHash(parentSessionId, agentId, canonLabel);
            key = "agent:" + agentId + ":" + hash;
            sessionId = "sub-" + hash;
            // Reuse existing agent if same deterministic key was already spawned.
            SpawnedAgent existing = agentsByKey.get(key);
            if (existing != null) {
                String spawnInfo = formatSpawnInfo(key, agentId, sessionId, null);
                boolean hasTask = task != null && !task.isBlank();
                if (!hasTask) {
                    return Mono.just(spawnInfo + "\nstatus: accepted (reused)");
                }
                return execSpawnTask(
                        existing, runtimeContext, spawnInfo, task, timeoutSeconds, declOpt);
            }
        } else {
            key = "agent:" + agentId + ":" + UUID.randomUUID();
            sessionId = "sub-" + UUID.randomUUID();
        }

        // Label uniqueness check — skipped above for persist=true reuse path (already returned).
        if (canonLabel != null && labelToKey.containsKey(canonLabel.toLowerCase())) {
            return Mono.just("Error: Label already in use: " + canonLabel);
        }

        SpawnedAgent spawned =
                new SpawnedAgent(key, agentId, sessionId, canonLabel, agent, nextDepth);
        agentsByKey.put(key, spawned);
        if (canonLabel != null) {
            labelToKey.put(canonLabel.toLowerCase(), key);
        }
        persistSpawnEntry(parentState, key, agentId, sessionId, canonLabel, nextDepth);

        // Propagate plan mode: if parent is in plan mode, force child into read-only mode too.
        if (parentState != null
                && parentState.getPlanModeContext().isPlanActive()
                && agent instanceof HarnessAgent ha) {
            ha.enterPlanMode(currentUserId, sessionId);
        }

        // Propagate DENY permission rules from parent to child (security boundary inheritance).
        boolean inherit = declOpt.map(SubagentDeclaration::isInheritParentPermissions).orElse(true);
        if (inherit && parentState != null && agent instanceof ReActAgent ra) {
            propagateDenyRules(parentState, ra);
        }

        // Expose subagent to user via gateway bridge if requested. The effective decision combines
        // (in priority order) a per-call RuntimeContext override, the declaration policy, and the
        // LLM-supplied argument — so application code can force or forbid exposure regardless of
        // what the model decides.
        boolean effectiveExpose = resolveExposeToUser(exposeToUser, declOpt, runtimeContext);
        String subagentId = null;
        if (effectiveExpose && gatewayBridge != null) {
            OutboundAddress replyTo =
                    runtimeContext != null
                            ? runtimeContext.get("outboundAddress", OutboundAddress.class)
                            : null;
            SubagentGatewayBridge.ExposeResult er =
                    gatewayBridge.expose(agentId, sessionId, agent, replyTo);
            subagentId = er.subagentId();
        }

        String spawnInfo = formatSpawnInfo(key, agentId, sessionId, subagentId);
        boolean hasTask = task != null && !task.isBlank();

        if (!hasTask) {
            return withSubagentExposedEvent(
                    Mono.just(spawnInfo + "\nstatus: accepted"),
                    subagentId,
                    agentId,
                    sessionId,
                    canonLabel);
        }

        long timeoutMs = resolveTimeoutMs(timeoutSeconds, DEFAULT_TIMEOUT_SECONDS);
        boolean remote = declOpt.map(SubagentDeclaration::isRemote).orElse(false);

        if (timeoutMs == 0) {
            String taskId = "task_" + UUID.randomUUID();
            final String capturedTask = task;
            TaskRunSpec spec;
            if (remote) {
                SubagentDeclaration d = declOpt.get();
                spec =
                        new TaskRunSpec.RemoteTaskRunSpec(
                                d.getUrl(), d.getHeaders(), agentId, capturedTask);
            } else {
                spec =
                        new TaskRunSpec.LocalTaskRunSpec(
                                () -> {
                                    try {
                                        Msg reply =
                                                agentManager
                                                        .invokeAgent(
                                                                agent,
                                                                sessionId,
                                                                currentUserId,
                                                                capturedTask,
                                                                runtimeContext)
                                                        .block();
                                        return reply != null ? reply.getTextContent() : "";
                                    } catch (RuntimeException e) {
                                        return "Error: "
                                                + (e.getMessage() != null
                                                        ? e.getMessage()
                                                        : e.getClass().getSimpleName());
                                    }
                                });
            }
            taskRepository.putTask(runtimeContext, taskId, agentId, parentSessionId, spec);
            return withSubagentExposedEvent(
                    Mono.just(
                            spawnInfo
                                    + "\n"
                                    + String.format(BG_RESULT_TEMPLATE, taskId, taskId, taskId)),
                    subagentId,
                    agentId,
                    sessionId,
                    canonLabel);
        }

        if (remote) {
            final String finalTask = task;
            return withSubagentExposedEvent(
                    Mono.fromCallable(
                            () ->
                                    runRemoteSync(
                                            runtimeContext,
                                            spawnInfo,
                                            agentId,
                                            parentSessionId,
                                            declOpt.get(),
                                            finalTask.trim(),
                                            timeoutMs)),
                    subagentId,
                    agentId,
                    sessionId,
                    canonLabel);
        }

        // Sync-local execution with timeout promotion: if the agent doesn't finish within the
        // timeout, its in-flight execution is promoted to an async task instead of being lost.
        final String finalTask = task.trim();
        final String finalSpawnInfo = spawnInfo;
        final String finalSubagentId = subagentId;
        final String finalLabel = canonLabel;
        return withSubagentExposedEvent(
                execWithTimeoutPromotion(
                        agent,
                        sessionId,
                        currentUserId,
                        finalTask,
                        spawned,
                        runtimeContext,
                        finalSpawnInfo,
                        timeoutMs,
                        agentId),
                finalSubagentId,
                agentId,
                sessionId,
                finalLabel);
    }

    @Tool(
            name = "agent_send",
            stateInjected = true,
            description =
                    """
                    Send a message to an existing subagent. Use the exact string from the \
                    agent_key line of agent_spawn output (starts with agent:), or the label \
                    you set at spawn. Do not pass agent_id, session_id, or task_id here. \
                    timeout_seconds=0 returns task_id for task_output.\
                    """)
    public Mono<String> agentSend(
            RuntimeContext runtimeContext,
            AgentState parentState,
            @ToolParam(
                            name = "agent_key",
                            description =
                                    "Exact value from agent_spawn's first line after 'agent_key: '"
                                        + " (format agent:<type>:<uuid>). Not agent_id, session_id,"
                                        + " or task_id. Mutually exclusive with label.",
                            required = false)
                    String agentKey,
            @ToolParam(
                            name = "label",
                            description =
                                    "Agent label assigned at spawn time. Mutually exclusive with"
                                            + " agent_key.",
                            required = false)
                    String label,
            @ToolParam(name = "message", description = "Message to send to the subagent")
                    String message,
            @ToolParam(
                            name = "timeout_seconds",
                            description =
                                    """
                                    Max seconds to wait for a reply. 0=fire-and-forget, returns \
                                    task_id. Default: 30. Max: 600.\
                                    """,
                            required = false)
                    Integer timeoutSeconds) {

        boolean hasKey = agentKey != null && !agentKey.isBlank();
        boolean hasLabel = label != null && !label.isBlank();
        if (hasKey && hasLabel) {
            return Mono.just("Error: Provide either agent_key or label, not both.");
        }
        if (!hasKey && !hasLabel) {
            return Mono.just("Error: Either agent_key or label is required.");
        }
        if (message == null || message.isBlank()) {
            return Mono.just("Error: message is required");
        }

        String key;
        if (hasKey) {
            key = agentKey.trim();
        } else {
            key = labelToKey.get(label.trim().toLowerCase());
            if (key == null) {
                key = tryResolveLabelFromState(parentState, label.trim());
            }
            if (key == null) {
                return Mono.just("Error: Unknown label: " + label.trim());
            }
        }

        SpawnedAgent resolved = agentsByKey.get(key);
        if (resolved == null) {
            resolved = tryRestoreFromState(parentState, key, runtimeContext);
        }
        if (resolved == null) {
            return Mono.just("Error: Unknown agent_key: " + key);
        }
        final SpawnedAgent spawned = resolved;

        long timeoutMs = resolveTimeoutMs(timeoutSeconds, DEFAULT_TIMEOUT_SECONDS);
        String currentUserId = runtimeContext != null ? runtimeContext.getUserId() : null;
        String parentSessionId = runtimeContext != null ? runtimeContext.getSessionId() : null;
        var declOpt = agentManager.getDeclaration(spawned.agentId());
        boolean remote = declOpt.map(SubagentDeclaration::isRemote).orElse(false);

        if (timeoutMs == 0) {
            String taskId = "task_" + UUID.randomUUID();
            final String capturedMessage = message;
            TaskRunSpec spec;
            if (remote) {
                SubagentDeclaration d = declOpt.get();
                spec =
                        new TaskRunSpec.RemoteTaskRunSpec(
                                d.getUrl(), d.getHeaders(), spawned.agentId(), capturedMessage);
            } else {
                spec =
                        new TaskRunSpec.LocalTaskRunSpec(
                                () -> {
                                    try {
                                        Msg reply =
                                                agentManager
                                                        .invokeAgent(
                                                                spawned.agent(),
                                                                spawned.sessionId(),
                                                                currentUserId,
                                                                capturedMessage,
                                                                runtimeContext)
                                                        .block();
                                        return reply != null ? reply.getTextContent() : "";
                                    } catch (RuntimeException e) {
                                        return "Error: "
                                                + (e.getMessage() != null
                                                        ? e.getMessage()
                                                        : e.getClass().getSimpleName());
                                    }
                                });
            }
            taskRepository.putTask(
                    runtimeContext, taskId, spawned.agentId(), parentSessionId, spec);
            return Mono.just(String.format(BG_RESULT_TEMPLATE, taskId, taskId, taskId));
        }

        if (remote) {
            final String finalMessage = message;
            final String finalKey = key;
            return Mono.fromCallable(
                    () ->
                            runRemoteSync(
                                    runtimeContext,
                                    "agent_key: " + finalKey,
                                    spawned.agentId(),
                                    parentSessionId,
                                    declOpt.get(),
                                    finalMessage.trim(),
                                    timeoutMs));
        }

        final String finalKey = key;
        return execWithTimeoutPromotion(
                spawned.agent(),
                spawned.sessionId(),
                currentUserId,
                message.trim(),
                spawned,
                runtimeContext,
                "agent_key: " + finalKey,
                timeoutMs,
                spawned.agentId());
    }

    @Tool(name = "agent_list", description = "List active subagents spawned by this agent.")
    public String agentList() {
        if (agentsByKey.isEmpty()) {
            return "No active subagents.";
        }

        StringBuilder sb =
                new StringBuilder("Active subagents (").append(agentsByKey.size()).append("):\n");
        for (SpawnedAgent a : agentsByKey.values()) {
            sb.append("- agent_key: ").append(a.key()).append("\n");
            sb.append("  agent_id: ").append(a.agentId()).append("\n");
            if (a.label() != null) {
                sb.append("  label: ").append(a.label()).append("\n");
            }
            sb.append("  spawn_depth: ").append(a.depth()).append("\n");
        }
        return sb.toString().trim();
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    /**
     * Returns a {@link Mono} that invokes the local subagent.
     *
     * <p>Three paths, checked in order:
     *
     * <ol>
     *   <li><b>{@code streamEvents()} path</b> — an {@link AgentEventEmitter} is present in the
     *       Reactor Context (set by {@code ReActAgent.streamEvents}). Child events are forwarded
     *       into the parent's {@code Flux<AgentEvent>} via a source-tagging wrapper emitter.
     *   <li><b>{@code stream()} path</b> (deprecated) — a {@link SubagentEventBus} is present.
     *       Child events are forwarded via the bus with {@link EventSource} metadata.
     *   <li><b>Non-streaming path</b> — plain {@code call()}, no event forwarding.
     * </ol>
     *
     * <p><b>Context propagation note:</b> this method returns a {@code Mono} whose
     * {@code deferContextual} is subscribed by {@code ToolMethodInvoker}'s {@code flatMap}, which
     * correctly inherits the Reactor Context from the parent streaming chain. Do NOT call
     * {@code .block()} on this Mono directly inside a tool method that returns {@link String},
     * because {@code block()} creates an isolated subscription that loses the Context.
     */
    private Mono<Msg> execLocalSync(
            Agent agent,
            String sessionId,
            String userId,
            String prompt,
            SpawnedAgent spawned,
            RuntimeContext parentCtx) {
        return Mono.deferContextual(
                ctxView -> {
                    // ── Path 1: streamEvents() — AgentEvent forwarding ──
                    Optional<AgentEventEmitter> emitterOpt = AgentEventEmitter.fromContext(ctxView);
                    if (emitterOpt.isPresent()) {
                        AgentEventEmitter parentEmitter = emitterOpt.get();
                        String sourcePath = buildSourcePath(spawned, parentCtx);
                        AgentEventEmitter taggedEmitter =
                                event -> parentEmitter.emit(event.withSource(sourcePath));

                        parentEmitter.emit(
                                new AgentStartEvent(spawned.sessionId(), null, spawned.agentId())
                                        .withSource(sourcePath));

                        return agentManager
                                .invokeAgent(agent, sessionId, userId, prompt, parentCtx)
                                .contextWrite(
                                        c ->
                                                c.put(
                                                        AgentEventEmitter.FORWARDING_CONTEXT_KEY,
                                                        taggedEmitter))
                                .doOnTerminate(
                                        () ->
                                                parentEmitter.emit(
                                                        new AgentEndEvent(null)
                                                                .withSource(sourcePath)));
                    }

                    // ── Path 2: stream() (deprecated) — SubagentEventBus forwarding ──
                    if (ctxView.hasKey(SubagentEventBus.CONTEXT_KEY)) {
                        SubagentEventBus bus = ctxView.get(SubagentEventBus.CONTEXT_KEY);
                        EventSource childSource = buildChildSource(spawned, parentCtx);

                        return agentManager
                                .invokeAgentStream(
                                        agent,
                                        sessionId,
                                        userId,
                                        prompt,
                                        childSource,
                                        StreamOptions.defaults(),
                                        parentCtx)
                                .doOnNext(
                                        e -> {
                                            log.debug(
                                                    "[execLocalSync] forwarding child event to"
                                                            + " bus: type={} msgId={} isLast={}",
                                                    e.getType(),
                                                    e.getMessage().getId(),
                                                    e.isLast());
                                            bus.emit(e);
                                        })
                                .filter(e -> e.isLast() && e.getType() == EventType.AGENT_RESULT)
                                .last()
                                .map(e -> e.getMessage())
                                .switchIfEmpty(
                                        Mono.defer(
                                                () ->
                                                        agentManager.invokeAgent(
                                                                agent, sessionId, userId, prompt,
                                                                parentCtx)));
                    }

                    // ── Path 3: non-streaming ──
                    return agentManager.invokeAgent(agent, sessionId, userId, prompt, parentCtx);
                });
    }

    /**
     * Executes a local subagent with timeout promotion: if the agent doesn't finish within
     * {@code timeoutMs}, the in-flight execution is promoted to an async background task instead
     * of being cancelled and lost.
     *
     * <p>The key mechanism is a {@link CompletableFuture} bridge that decouples execution from
     * observation. The Mono from {@link #execLocalSync} is subscribed with Reactor Context
     * propagation (so streaming events flow during the sync wait period), and its result feeds
     * the bridge. A race future derived from the bridge adds a timeout without cancelling the
     * original:
     *
     * <ul>
     *   <li>Agent finishes before timeout → normal result returned
     *   <li>Timeout fires first → bridge (still running) is registered in {@link TaskRepository}
     *       as an {@link TaskRunSpec.AdoptedTaskRunSpec}, and a {@code task_id} is returned
     *   <li>Agent errors → error message returned
     * </ul>
     */
    private Mono<String> execWithTimeoutPromotion(
            Agent agent,
            String sessionId,
            String userId,
            String task,
            SpawnedAgent spawned,
            RuntimeContext runtimeContext,
            String header,
            long timeoutMs,
            String agentId) {

        return Mono.deferContextual(
                parentCtx ->
                        Mono.<String>create(
                                sink -> {
                                    CompletableFuture<Msg> bridge = new CompletableFuture<>();

                                    Mono<Msg> inner =
                                            execLocalSync(
                                                            agent,
                                                            sessionId,
                                                            userId,
                                                            task,
                                                            spawned,
                                                            runtimeContext)
                                                    .contextWrite(
                                                            c ->
                                                                    reactor.util.context.Context.of(
                                                                            parentCtx))
                                                    .doFinally(
                                                            signal -> {
                                                                // Parent subscription was
                                                                // cancelled (outer Mono.timeout,
                                                                // user stop, or upstream Reactor
                                                                // cancel). The fire-and-forget
                                                                // subscribe below detaches the
                                                                // inner execution from the parent
                                                                // lifecycle, so without this
                                                                // doFinally the sub-agent would
                                                                // keep running as an orphan and
                                                                // the eventual sink.success(...)
                                                                // would be a no-op on the
                                                                // already-cancelled sink.
                                                                if (signal == SignalType.CANCEL) {
                                                                    interruptAgent(
                                                                            agent, runtimeContext);
                                                                }
                                                            });

                                    Disposable innerSub =
                                            inner.subscribe(
                                                    bridge::complete,
                                                    bridge::completeExceptionally,
                                                    () -> {
                                                        if (!bridge.isDone()) {
                                                            bridge.complete(null);
                                                        }
                                                    });

                                    // Race against timeout without cancelling the bridge.
                                    // thenApply(m -> m) creates a dependent future so orTimeout
                                    // completes the race copy, not the original bridge.
                                    CompletableFuture<Msg> race = bridge.thenApply(m -> m);
                                    race.orTimeout(timeoutMs, TimeUnit.MILLISECONDS);

                                    race.whenComplete(
                                            (msg, err) -> {
                                                if (err != null) {
                                                    handleExecError(
                                                            err,
                                                            bridge,
                                                            runtimeContext,
                                                            header,
                                                            timeoutMs,
                                                            agentId,
                                                            sink);
                                                } else {
                                                    sink.success(
                                                            header
                                                                    + "\nstatus: ok\nreply:\n"
                                                                    + textOf(msg));
                                                }
                                            });

                                    // Propagate parent cancellation to the inner fire-and-forget
                                    // subscription. Without this, doFinally(CANCEL) above never
                                    // fires because the inner is subscribed independently of the
                                    // parent Mono.create sink.
                                    sink.onCancel(innerSub);
                                }));
    }

    /**
     * Interrupts the sub-agent's reasoning loop when its parent tool-call subscription is
     * cancelled. Mirrors the fix in core {@code SubAgentTool.interruptAgent} (commit
     * {@code 029cc55e}, issue #1783) — see issue #2062 for the harness-side equivalent.
     *
     * <p>Only {@link ReActAgent} exposes {@code interrupt(RuntimeContext)}. Other {@link Agent}
     * implementations are no-ops here; their inner execution will still be disposed by the caller's
     * {@code sink.onCancel(innerSub::dispose)}, which is enough for non-looping agents.
     */
    private void interruptAgent(Agent agent, RuntimeContext ctx) {
        if (agent instanceof ReActAgent ra) {
            ra.interrupt(ctx);
            log.warn(
                    "Sub-agent '{}' (id={}) was interrupted because its parent tool call"
                            + " subscription was cancelled.",
                    ra.getName(),
                    ra.getAgentId());
        }
    }

    /**
     * Handles errors from the race future in {@link #execWithTimeoutPromotion}. Separated to keep
     * the lambda readable — it distinguishes timeout (→ promote) from real errors (→ report).
     */
    private void handleExecError(
            Throwable err,
            CompletableFuture<Msg> bridge,
            RuntimeContext runtimeContext,
            String header,
            long timeoutMs,
            String agentId,
            reactor.core.publisher.MonoSink<String> sink) {

        Throwable cause = err instanceof CompletionException ? err.getCause() : err;
        if (cause instanceof TimeoutException) {
            String taskId = "task_" + UUID.randomUUID();
            String parentSessionId = runtimeContext != null ? runtimeContext.getSessionId() : null;
            CompletableFuture<String> textFuture = bridge.thenApply(AgentSpawnTool::textOf);
            taskRepository.putTask(
                    runtimeContext,
                    taskId,
                    agentId,
                    parentSessionId,
                    new TaskRunSpec.AdoptedTaskRunSpec(textFuture));
            log.info(
                    "agent_spawn sync timeout after {}ms, promoted to async: agentId={}, taskId={}",
                    timeoutMs,
                    agentId,
                    taskId);
            sink.success(header + "\n" + formatTimeoutPromoted(taskId, timeoutMs));
        } else {
            Throwable reportable = cause != null ? cause : err;
            String errStr =
                    reportable.getMessage() != null
                            ? reportable.getMessage()
                            : reportable.getClass().getSimpleName();
            log.warn("agent execution failed: agentId={}", agentId, reportable);
            sink.success(header + "\nstatus: error\nerror: " + errStr);
        }
    }

    private void persistSpawnEntry(
            AgentState parentState,
            String key,
            String agentId,
            String sessionId,
            String label,
            int depth) {
        if (parentState == null) {
            return;
        }
        parentState
                .getToolContext()
                .putSpawnEntry(
                        key,
                        new io.agentscope.core.state.ToolContextState.SpawnEntry(
                                key, agentId, sessionId, label, depth));
    }

    private String tryResolveLabelFromState(AgentState parentState, String label) {
        if (parentState == null) {
            return null;
        }
        String lowerLabel = label.toLowerCase();
        for (io.agentscope.core.state.ToolContextState.SpawnEntry entry :
                parentState.getToolContext().getSpawnRegistry().values()) {
            if (entry.label() != null && entry.label().toLowerCase().equals(lowerLabel)) {
                return entry.key();
            }
        }
        return null;
    }

    private SpawnedAgent tryRestoreFromState(
            AgentState parentState, String key, RuntimeContext runtimeContext) {
        if (parentState == null) {
            return null;
        }
        io.agentscope.core.state.ToolContextState.SpawnEntry entry =
                parentState.getToolContext().getSpawnRegistry().get(key);
        if (entry == null) {
            return null;
        }
        Optional<Agent> agentOpt =
                agentManager.createAgentIfPresent(entry.agentId(), runtimeContext);
        if (agentOpt.isEmpty()) {
            log.warn(
                    "Failed to restore subagent from state: agentId={} not found in registry",
                    entry.agentId());
            return null;
        }
        SpawnedAgent restored =
                new SpawnedAgent(
                        key,
                        entry.agentId(),
                        entry.sessionId(),
                        entry.label(),
                        agentOpt.get(),
                        entry.depth());
        agentsByKey.put(key, restored);
        if (entry.label() != null) {
            labelToKey.put(entry.label().toLowerCase(), key);
        }
        log.info(
                "Restored subagent from persisted state: key={}, agentId={}", key, entry.agentId());
        return restored;
    }

    private static String textOf(Msg msg) {
        return msg != null ? msg.getTextContent() : "";
    }

    private static String formatTimeoutPromoted(String taskId, long timeoutMs) {
        return String.format(
                """
                status: timeout_promoted
                task_id: %s
                The task exceeded the %ds sync timeout but is still running in the background. \
                Use task_output(task_id='%s', block=false) to check status, \
                or wait — completed tasks are pushed back to you automatically. \
                Do NOT retry the same task.\
                """,
                taskId, timeoutMs / 1000, taskId);
    }

    /**
     * Builds an {@link EventSource} for a freshly spawned or known subagent. The path is
     * constructed from the parent session ID (or {@code "main"} as fallback) plus the child's
     * {@code agentId}, separated by {@code "/"}.
     */
    private EventSource buildChildSource(SpawnedAgent spawned, RuntimeContext parentCtx) {
        String parentName =
                (parentCtx != null && parentCtx.getSessionId() != null)
                        ? parentCtx.getSessionId()
                        : "main";
        String path = parentName + "/" + spawned.agentId();
        return EventSource.builder()
                .agentKey(spawned.key())
                .agentId(spawned.agentId())
                .sessionId(spawned.sessionId())
                .depth(spawned.depth())
                .path(path)
                .build();
    }

    /**
     * Builds a source path string for the {@link AgentEvent#withSource} tag. Uses the same
     * parent-session / child-agent-id convention as {@link #buildChildSource}.
     */
    private String buildSourcePath(SpawnedAgent spawned, RuntimeContext parentCtx) {
        String parentName =
                (parentCtx != null && parentCtx.getSessionId() != null)
                        ? parentCtx.getSessionId()
                        : "main";
        return parentName + "/" + spawned.agentId();
    }

    /**
     * Submits a remote task through {@link TaskRepository} (for durable state) and blocks until
     * it completes or the timeout elapses.
     *
     * <p>Using the repository ensures the task is visible to {@code task_list} and survives
     * conversation compaction, just like async remote tasks do.
     */
    private String runRemoteSync(
            RuntimeContext runtimeContext,
            String header,
            String agentId,
            String parentSessionId,
            SubagentDeclaration decl,
            String input,
            long timeoutMs) {
        String taskId = "task_" + UUID.randomUUID();
        TaskRunSpec spec =
                new TaskRunSpec.RemoteTaskRunSpec(decl.getUrl(), decl.getHeaders(), agentId, input);
        BackgroundTask bgTask =
                taskRepository.putTask(runtimeContext, taskId, agentId, parentSessionId, spec);
        try {
            boolean done = bgTask.waitForCompletion(timeoutMs);
            if (!done) {
                return header + "\nstatus: timeout\ntask_id: " + taskId;
            }
            TaskStatus ts = bgTask.getTaskStatus();
            if (ts == TaskStatus.FAILED) {
                Exception err = bgTask.getError();
                String msg = err != null ? err.getMessage() : "remote task failed";
                return header + "\nstatus: error\nerror: " + msg;
            }
            if (ts == TaskStatus.CANCELLED) {
                return header + "\nstatus: cancelled\ntask_id: " + taskId;
            }
            String result = bgTask.getResult();
            return header + "\nstatus: ok\nreply:\n" + (result != null ? result : "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("agent remote sync interrupted: agentId={}", agentId);
            return header + "\nstatus: error\nerror: interrupted";
        }
    }

    /**
     * Resolves the effective user-exposure decision for a spawn.
     *
     * <p>Precedence (highest first):
     *
     * <ol>
     *   <li>A per-call {@link RuntimeContext} value under {@link #CTX_EXPOSE_TO_USER} — lets the
     *       embedding application force/forbid exposure for the whole call.
     *   <li>The spawned subagent's {@link SubagentDeclaration#getExposeToUser()} policy — a static
     *       per-type default; {@code null} means "no opinion".
     *   <li>The LLM-supplied {@code expose_to_user} tool argument.
     *   <li>{@code false} when none of the above expresses an opinion.
     * </ol>
     */
    private static boolean resolveExposeToUser(
            Boolean llmParam, Optional<SubagentDeclaration> declOpt, RuntimeContext ctx) {
        if (ctx != null) {
            Boolean override = asBoolean(ctx.get(CTX_EXPOSE_TO_USER));
            if (override != null) {
                return override;
            }
        }
        Boolean declPolicy = declOpt.map(SubagentDeclaration::getExposeToUser).orElse(null);
        if (declPolicy != null) {
            return declPolicy;
        }
        return Boolean.TRUE.equals(llmParam);
    }

    /** Coerces a context value (Boolean or its string form) to a tri-state Boolean. */
    private static Boolean asBoolean(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s && !s.isBlank()) {
            return Boolean.parseBoolean(s.trim());
        }
        return null;
    }

    private static long resolveTimeoutMs(Integer timeoutSeconds, int defaultSeconds) {
        if (timeoutSeconds == null) {
            return (long) defaultSeconds * 1_000;
        }
        if (timeoutSeconds <= 0) {
            return 0L;
        }
        return (long) Math.min(timeoutSeconds, MAX_TIMEOUT_SECONDS) * 1_000;
    }

    /**
     * Wraps a {@code Mono<String>} to emit a {@link SubagentExposedEvent} into the parent's event
     * stream when {@code subagentId} is non-null. When subagentId is null (no expose), returns the
     * original Mono unchanged.
     */
    private static Mono<String> withSubagentExposedEvent(
            Mono<String> source,
            String subagentId,
            String agentId,
            String sessionId,
            String label) {
        if (subagentId == null) {
            return source;
        }
        return Mono.deferContextual(
                ctx -> {
                    AgentEventEmitter.fromContext(ctx)
                            .ifPresent(
                                    emitter ->
                                            emitter.emit(
                                                    new SubagentExposedEvent(
                                                            subagentId,
                                                            agentId,
                                                            sessionId,
                                                            label)));
                    return source;
                });
    }

    private static String formatSpawnInfo(
            String key, String agentId, String sessionId, String subagentId) {
        StringBuilder sb = new StringBuilder();
        sb.append("agent_key: ").append(key).append("\n");
        sb.append("agent_id: ").append(agentId).append("\n");
        sb.append("session_id: ").append(sessionId);
        if (subagentId != null) {
            sb.append("\nsubagent_id: ").append(subagentId);
            sb.append("\nstatus: exposed (user can send messages directly via subagent_id)");
        }
        return sb.toString();
    }

    /**
     * Executes a task against a previously spawned (or reused) subagent. Factored out of
     * {@code agentSpawn} to handle the deterministic-key reuse path without duplicating the
     * sync/async/remote dispatch logic.
     */
    private Mono<String> execSpawnTask(
            SpawnedAgent spawned,
            RuntimeContext runtimeContext,
            String spawnInfo,
            String task,
            Integer timeoutSeconds,
            Optional<SubagentDeclaration> declOpt) {
        long timeoutMs = resolveTimeoutMs(timeoutSeconds, DEFAULT_TIMEOUT_SECONDS);
        String currentUserId = runtimeContext != null ? runtimeContext.getUserId() : null;
        String parentSessionId = runtimeContext != null ? runtimeContext.getSessionId() : null;
        boolean remote = declOpt.map(SubagentDeclaration::isRemote).orElse(false);

        if (timeoutMs == 0) {
            String taskId = "task_" + UUID.randomUUID();
            final String capturedTask = task;
            TaskRunSpec spec;
            if (remote) {
                SubagentDeclaration d = declOpt.get();
                spec =
                        new TaskRunSpec.RemoteTaskRunSpec(
                                d.getUrl(), d.getHeaders(), spawned.agentId(), capturedTask);
            } else {
                spec =
                        new TaskRunSpec.LocalTaskRunSpec(
                                () -> {
                                    try {
                                        Msg reply =
                                                agentManager
                                                        .invokeAgent(
                                                                spawned.agent(),
                                                                spawned.sessionId(),
                                                                currentUserId,
                                                                capturedTask,
                                                                runtimeContext)
                                                        .block();
                                        return reply != null ? reply.getTextContent() : "";
                                    } catch (RuntimeException e) {
                                        return "Error: "
                                                + (e.getMessage() != null
                                                        ? e.getMessage()
                                                        : e.getClass().getSimpleName());
                                    }
                                });
            }
            taskRepository.putTask(
                    runtimeContext, taskId, spawned.agentId(), parentSessionId, spec);
            return Mono.just(
                    spawnInfo + "\n" + String.format(BG_RESULT_TEMPLATE, taskId, taskId, taskId));
        }

        if (remote) {
            final String finalTask = task;
            return Mono.fromCallable(
                    () ->
                            runRemoteSync(
                                    runtimeContext,
                                    spawnInfo,
                                    spawned.agentId(),
                                    parentSessionId,
                                    declOpt.get(),
                                    finalTask.trim(),
                                    timeoutMs));
        }

        final String finalTask = task.trim();
        final String finalSpawnInfo = spawnInfo;
        return execWithTimeoutPromotion(
                spawned.agent(),
                spawned.sessionId(),
                currentUserId,
                finalTask,
                spawned,
                runtimeContext,
                finalSpawnInfo,
                timeoutMs,
                spawned.agentId());
    }

    /**
     * Derives a deterministic 12-char hex hash from (parentSessionId, agentId, label). Same inputs
     * always produce the same key, enabling subagent state recovery across parent calls.
     */
    static String deterministicHash(String parentSessionId, String agentId, String label) {
        String parent = parentSessionId != null ? parentSessionId : "anon";
        return label != null
                ? SessionIdUtils.deterministicHash(parent, agentId, label)
                : SessionIdUtils.deterministicHash(parent, agentId);
    }

    /**
     * Copies all DENY rules from the parent's permission context into the child's permission
     * engine. This enforces the security boundary: anything the parent is explicitly denied, the
     * child is also denied.
     */
    private static void propagateDenyRules(AgentState parentState, ReActAgent child) {
        PermissionContextState parentPerms = parentState.getPermissionContext();
        if (parentPerms == null || parentPerms.getDenyRules().isEmpty()) {
            return;
        }
        var childEngine = child.getPermissionEngine();
        if (childEngine == null) {
            return;
        }
        for (Map.Entry<String, List<PermissionRule>> entry :
                parentPerms.getDenyRules().entrySet()) {
            for (PermissionRule rule : entry.getValue()) {
                childEngine.addRule(rule);
            }
        }
    }
}
