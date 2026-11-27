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
import io.agentscope.core.agent.RunOutcome;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.agent.SubagentEventBus;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.SubagentExposedEvent;
import io.agentscope.core.formatter.DocumentReferenceRenderer;
import io.agentscope.core.journal.JournalException;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.ToolContextState.SpawnEntry;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.ToolSuspendException;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.SessionIdUtils;
import io.agentscope.harness.agent.gateway.SubagentGatewayBridge;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.RemoteAskPolicy;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.SubagentExecution;
import io.agentscope.harness.agent.subagent.protocol.RemoteConfirmDecision;
import io.agentscope.harness.agent.subagent.protocol.RemoteEventCodec;
import io.agentscope.harness.agent.subagent.protocol.RemotePendingConfirm;
import io.agentscope.harness.agent.subagent.protocol.RemoteStreamDetail;
import io.agentscope.harness.agent.subagent.task.AgentProtocolTransport;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.RemoteSubagentTransport;
import io.agentscope.harness.agent.subagent.task.RemoteSubmitContext;
import io.agentscope.harness.agent.subagent.task.RemoteTarget;
import io.agentscope.harness.agent.subagent.task.RemoteTaskStatus;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
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
 *   <li>{@code agent_list} — list retained, addressable subagents
 *   <li>{@code agent_release} — release an idle retained subagent and invalidate its handles
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
 * <p>{@code agent_spawn}, {@code agent_send}, and {@code agent_release} return
 * {@link Mono}{@code <ToolResultBlock>} or {@link ToolResultBlock} so that the
 * framework's reactive tool-invocation pipeline (see {@code ToolMethodInvoker}) can subscribe them
 * within the parent agent's streaming chain. When a {@link SubagentEventBus} is present in the
 * Reactor Context (injected by {@code AgentBase.createEventStream}), every child {@link
 * io.agentscope.core.agent.Event} is forwarded to the parent sink in real time, giving consumers
 * a flattened event stream across the full call hierarchy. When no bus is present (plain {@code
 * call()} mode), execution falls back to the non-streaming {@code invokeAgent} path with no
 * overhead.
 */
public class AgentSpawnTool implements AutoCloseable {

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

    /**
     * {@link RuntimeContext} string key for the immutable subagent registry selected by the
     * current parent-agent invocation. {@link
     * io.agentscope.harness.agent.middleware.SubagentsMiddleware} installs a namespace-scoped
     * manager here so concurrent callers never overwrite each other's declarations.
     */
    public static final String CTX_AGENT_MANAGER = "agentscope.subagent.agent_manager";

    /**
     * {@link RuntimeContext} string key holding a {@code Map<String, Object>} of caller-defined
     * attributes to send with every remote subagent submission of the current call, as
     * {@code context.attributes}:
     *
     * <pre>{@code
     * RuntimeContext ctx = RuntimeContext.builder()
     *     .sessionId("s-1")
     *     .put(AgentSpawnTool.CTX_REMOTE_CONTEXT_ATTRIBUTES, Map.of("tenant", "acme"))
     *     .build();
     * }</pre>
     *
     * <p>Merged over the subagent's static {@link
     * SubagentDeclaration#getRemoteContextAttributes()}, so a per-call value wins on conflict.
     * Values must be JSON-serializable.
     */
    public static final String CTX_REMOTE_CONTEXT_ATTRIBUTES =
            "agentscope.subagent.remote_context_attributes";

    /**
     * {@link RuntimeContext} string key that forces synchronous subagent execution for the current
     * call. Put a {@link Boolean} (or its string form) under this key to ignore LLM-requested
     * background mode ({@code timeout_seconds=0}) and to disable timeout promotion to a background
     * {@code task_id}.
     *
     * <p>When force-sync is on:
     *
     * <ul>
     *   <li>{@code timeout_seconds=0} is coerced to the default sync timeout (30s), unless {@link
     *       #CTX_FORCE_SYNC_TIMEOUT_SECONDS} supplies an absolute override
     *   <li>If the sync wait exceeds the timeout, the subagent is interrupted and the tool returns
     *       {@code status: timeout} — it is <em>not</em> promoted to an async background task
     * </ul>
     *
     * <pre>{@code
     * RuntimeContext ctx = RuntimeContext.builder()
     *     .sessionId("s-1")
     *     .put(AgentSpawnTool.CTX_FORCE_SYNC, true)
     *     .put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, 120)
     *     .build();
     * }</pre>
     *
     * <p>Applies to {@code agent_spawn} and {@code agent_send}. Multiple sync tool calls in one
     * turn still run in parallel by default (Toolkit {@code parallel=true}).
     */
    public static final String CTX_FORCE_SYNC = "agentscope.subagent.force_sync";

    /**
     * Optional {@link RuntimeContext} override for the sync wait (seconds) when {@link
     * #CTX_FORCE_SYNC} is enabled. Accepts an {@link Integer}/{@link Number} or its string form.
     *
     * <p>When present (and force-sync is on), this value fully replaces the LLM's {@code
     * timeout_seconds} for the call. Values {@code <= 0} fall back to the default sync timeout
     * (30s). Positive values are honored as-is — the deployer owns the sync-wait budget; the
     * 600s ceiling ({@code MAX_TIMEOUT_SECONDS}) applies only to LLM-requested timeouts.
     *
     * <p>Ignored when force-sync is off.
     *
     * <pre>{@code
     * RuntimeContext ctx = RuntimeContext.builder()
     *     .put(AgentSpawnTool.CTX_FORCE_SYNC, true)
     *     .put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, 120)
     *     .build();
     * }</pre>
     */
    public static final String CTX_FORCE_SYNC_TIMEOUT_SECONDS =
            "agentscope.subagent.force_sync_timeout_seconds";

    private static final String BG_RESULT_TEMPLATE =
            """
            status: accepted
            task_id: %s
            Use task_output(task_id='%s', block=false) to check status, \
            wait_async_results(task_ids=...) to wait for a chosen group, \
            wait_async_results(wait_all=true) to wait for all current background tasks, \
            task_cancel(task_id='%s') to cancel, or task_list() to see all tasks. \
            Do NOT call task_output immediately — the task has just started. \
            Do not redo the task's work yourself while it runs in the background. \
            Never mention task_id, agent_key, or session_id to the user — they are internal handles. \
            Do not read the task's output files to watch progress; wait for the result instead.\
            """;

    /** Short poll interval used while waiting for a remote sync task, to detect awaiting_confirm promptly. */
    private static final long REMOTE_CONFIRM_POLL_MS = 1_000L;

    private final DefaultAgentManager agentManager;
    private final TaskRepository taskRepository;
    private final int parentSpawnDepth;
    private volatile SubagentGatewayBridge gatewayBridge;
    private volatile RemoteSubagentTransport remoteTransport = new AgentProtocolTransport();

    private record SpawnedAgent(
            String key,
            String agentId,
            String sessionId,
            String label,
            Agent agent,
            int depth,
            boolean reusable) {}

    /** A live agent plus the lifecycle state needed to reject release while work is in flight. */
    private static final class ManagedAgent {

        private final SpawnedAgent spawned;
        private boolean releasing;
        private int inFlight;

        private ManagedAgent(SpawnedAgent spawned) {
            this.spawned = spawned;
        }

        private synchronized Lease tryAcquire() {
            return tryAcquire(null);
        }

        private synchronized Lease tryAcquire(Runnable onTerminal) {
            if (releasing) {
                return null;
            }
            inFlight++;
            return new Lease(this, onTerminal);
        }

        private synchronized boolean beginRelease() {
            if (releasing || inFlight != 0) {
                return false;
            }
            releasing = true;
            return true;
        }

        private synchronized boolean beginClose() {
            if (releasing) {
                return false;
            }
            releasing = true;
            return true;
        }

        private synchronized void releaseLease() {
            if (inFlight > 0) {
                inFlight--;
            }
        }

        private synchronized void markClosed() {
            releasing = true;
        }

        private synchronized boolean isReleasing() {
            return releasing;
        }

        private synchronized int inFlight() {
            return inFlight;
        }
    }

    /** Idempotent execution lease. */
    private static final class Lease implements AutoCloseable {

        private final ManagedAgent owner;
        private final Runnable onTerminal;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(ManagedAgent owner, Runnable onTerminal) {
            this.owner = owner;
            this.onTerminal = onTerminal;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                owner.releaseLease();
                if (onTerminal != null) {
                    onTerminal.run();
                }
            }
        }
    }

    private record RegistryScope(String userId, String parentSessionId) {

        private static RegistryScope from(RuntimeContext context) {
            return new RegistryScope(
                    context != null ? context.getUserId() : null,
                    context != null ? context.getSessionId() : null);
        }
    }

    private static final class ScopedRegistry {

        private final ConcurrentHashMap<String, ManagedAgent> agentsByKey =
                new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, String> labelToKey = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Object> keyLocks = new ConcurrentHashMap<>();
        private int users;
    }

    private final ConcurrentHashMap<RegistryScope, ScopedRegistry> registries =
            new ConcurrentHashMap<>();

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

    /** Test-only hook to inject a fake {@link RemoteSubagentTransport} for remote streaming/HITL tests. */
    void setRemoteTransport(RemoteSubagentTransport remoteTransport) {
        this.remoteTransport = Objects.requireNonNull(remoteTransport, "remoteTransport");
    }

    @Tool(
            name = "agent_spawn",
            stateInjected = true,
            timeoutManaged = true,
            description =
                    """
                    Spawn an isolated subagent. Returns agent_key (copy verbatim to agent_send), \
                    agent_id (type), and session_id (internal). Sync mode includes the reply; \
                    async or timeout promotion includes task_id for task_output / wait_async_results. \
                    task_id is NOT agent_key. Set reusable=true for follow-ups or to spawn without a task. \
                    Set reusable=false for a one-shot task that closes when it finishes. \
                    The child does not see your conversation; provide a self-contained task. \
                    A persistent local agent retains its history. Do not rephrase a denied request to bypass permissions.\
                    """)
    public Mono<ToolResultBlock> agentSpawn(
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
                            name = "reusable",
                            description =
                                    "Whether this subagent remains addressable by agent_send"
                                            + " after the initial task. false closes it when the"
                                            + " task reaches a terminal state.")
                    boolean reusable,
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

        if (parentState == null && runtimeContext != null) {
            parentState = runtimeContext.getAgentState();
        }
        if (!hostOwnsChildState(runtimeContext)
                && runtimeContext != null
                && runtimeContext.getSessionId() != null
                && !runtimeContext.getSessionId().isBlank()
                && parentState != null
                && !isParentStateOwner(parentState, runtimeContext)) {
            return Mono.just(ToolResultBlock.error("Unknown agent_key."));
        }
        log.debug(
                "agent_spawn called: agentId={}, reusable={}, timeoutSeconds={}, task={}",
                agentId,
                reusable,
                timeoutSeconds,
                task);
        int nextDepth = parentSpawnDepth + 1;
        if (nextDepth > MAX_SPAWN_DEPTH) {
            log.warn("agent_spawn depth exceeded: depth={}, max={}", nextDepth, MAX_SPAWN_DEPTH);
            return Mono.just(
                    ToolResultBlock.error(
                            "Maximum spawn depth exceeded (max=" + MAX_SPAWN_DEPTH + ")"));
        }
        String canonLabel = label != null && !label.isBlank() ? label.trim() : null;
        DefaultAgentManager manager = managerFor(runtimeContext);
        if (manager.isPrimaryOnly(agentId)) {
            return Mono.just(
                    ToolResultBlock.error(
                            "agent_id '"
                                    + agentId
                                    + "' is PRIMARY-only and cannot be spawned as a subagent."));
        }
        if (!manager.hasAgent(agentId)) {
            return Mono.just(ToolResultBlock.error("Unknown agent_id: " + agentId));
        }
        String currentUserId = runtimeContext != null ? runtimeContext.getUserId() : null;
        String parentSessionId = runtimeContext != null ? runtimeContext.getSessionId() : null;
        var declOpt = manager.getDeclaration(agentId);
        boolean persist = declOpt.map(SubagentDeclaration::isPersistSession).orElse(false);
        boolean effectiveExpose = resolveExposeToUser(exposeToUser, declOpt, runtimeContext);
        boolean hasTask = task != null && !task.isBlank();
        if (!reusable && (!hasTask || canonLabel != null || persist || effectiveExpose)) {
            return Mono.just(
                    ToolResultBlock.error(
                            "reusable=false requires a task and cannot be combined with label,"
                                    + " persistSession, or expose_to_user."));
        }
        ScopedRegistry registry = registryFor(runtimeContext);
        try {

            String key;
            String sessionId;
            if (persist) {
                String hash =
                        deterministicHash(currentUserId, parentSessionId, agentId, canonLabel);
                key = "agent:" + agentId + ":" + hash;
                SubagentExecution execution =
                        runtimeContext == null ? null : runtimeContext.get(SubagentExecution.class);
                String ownedChild =
                        execution == null ? null : execution.childSessionId(runtimeContext);
                sessionId = ownedChild == null ? "sub-" + hash : ownedChild;
                // Reuse existing agent if same deterministic key was already spawned.
                ManagedAgent existing = registry.agentsByKey.get(key);
                if (existing != null) {
                    return reuseManagedAgent(
                            existing,
                            runtimeContext,
                            parentState,
                            currentUserId,
                            task,
                            timeoutSeconds,
                            declOpt);
                }
            } else {
                key = "agent:" + agentId + ":" + UUID.randomUUID();
                SubagentExecution execution =
                        runtimeContext == null ? null : runtimeContext.get(SubagentExecution.class);
                String ownedChild =
                        execution == null ? null : execution.childSessionId(runtimeContext);
                sessionId = ownedChild == null ? "sub-" + UUID.randomUUID() : ownedChild;
            }

            String lowerLabel = canonLabel != null ? canonLabel.toLowerCase(Locale.ROOT) : null;
            if (lowerLabel != null) {
                synchronized (registry) {
                    String claimed = registry.labelToKey.putIfAbsent(lowerLabel, key);
                    if (claimed != null && !claimed.equals(key)) {
                        return Mono.just(
                                ToolResultBlock.error("Label already in use: " + canonLabel));
                    }
                }
            }

            Object initializationLock =
                    persist
                            ? registry.keyLocks.computeIfAbsent(key, ignored -> new Object())
                            : new Object();
            synchronized (initializationLock) {
                if (persist) {
                    ManagedAgent existing = registry.agentsByKey.get(key);
                    if (existing != null) {
                        return reuseManagedAgent(
                                existing,
                                runtimeContext,
                                parentState,
                                currentUserId,
                                task,
                                timeoutSeconds,
                                declOpt);
                    }
                }
                Optional<Agent> agentOpt = manager.createAgentIfPresent(agentId, runtimeContext);
                if (agentOpt.isEmpty()) {
                    if (lowerLabel != null) {
                        synchronized (registry) {
                            registry.labelToKey.remove(lowerLabel, key);
                        }
                    }
                    removeRegistryIfEmpty(runtimeContext, registry);
                    if (manager.isPrimaryOnly(agentId)) {
                        return Mono.just(
                                ToolResultBlock.error(
                                        "agent_id '"
                                                + agentId
                                                + "' is PRIMARY-only and cannot be spawned as a"
                                                + " subagent."));
                    }
                    log.warn("agent_spawn unknown agentId={}, known={}", agentId, manager);
                    return Mono.just(ToolResultBlock.error("Unknown agent_id: " + agentId));
                }
                log.debug("agent_spawn resolved: agentId={}", agentId);
                Agent agent = agentOpt.get();
                ManagedAgent managed =
                        new ManagedAgent(
                                new SpawnedAgent(
                                        key,
                                        agentId,
                                        sessionId,
                                        canonLabel,
                                        agent,
                                        nextDepth,
                                        reusable));

                // Fully initialize the private candidate before publishing it in the live registry.
                // The per-key lock also ensures a persistent identity constructs only one child
                // while
                // concurrent callers wait for the fully initialized winner.
                String subagentId = null;
                try {
                    if (!hostOwnsChildState(runtimeContext)) {
                        propagatePlanMode(parentState, currentUserId, sessionId, agent);
                        propagateParentDenyRules(
                                parentState, currentUserId, sessionId, agent, declOpt);
                    }

                    SubagentGatewayBridge exposureBridge = gatewayBridge;
                    if (effectiveExpose && exposureBridge != null) {
                        OutboundAddress replyTo =
                                runtimeContext != null
                                        ? runtimeContext.get(
                                                "outboundAddress", OutboundAddress.class)
                                        : null;
                        SubagentGatewayBridge.ExposeResult er =
                                exposureBridge.expose(
                                        agentId, sessionId, agent, replyTo, runtimeContext);
                        subagentId = er.subagentId();
                    }
                } catch (RuntimeException | Error e) {
                    if (lowerLabel != null) {
                        synchronized (registry) {
                            registry.labelToKey.remove(lowerLabel, key);
                        }
                    }
                    closeManagedAgent(managed);
                    removeRegistryIfEmpty(runtimeContext, registry);
                    return Mono.error(e);
                }

                ManagedAgent winner;
                synchronized (registry) {
                    winner = registry.agentsByKey.get(key);
                    if (winner == null) {
                        persistSpawnEntry(
                                parentState,
                                key,
                                agentId,
                                sessionId,
                                canonLabel,
                                nextDepth,
                                reusable);
                        registry.agentsByKey.put(key, managed);
                    }
                }
                if (winner != null) {
                    closeManagedAgent(managed);
                    return reuseManagedAgent(
                            winner,
                            runtimeContext,
                            parentState,
                            currentUserId,
                            task,
                            timeoutSeconds,
                            declOpt);
                }

                io.agentscope.core.journal.InvocationRecorder recorder =
                        io.agentscope.core.journal.InvocationRecorder.from(runtimeContext);
                if (recorder != null) {
                    recorder.recordNow(
                            "subagent/spawned",
                            Map.of(
                                    "childSessionId",
                                    sessionId,
                                    "childAgentId",
                                    agentId,
                                    "agentKey",
                                    key,
                                    "childLogAgentId",
                                    agent instanceof HarnessAgent child
                                            ? child.threadKey(
                                                            RuntimeContext.builder()
                                                                    .userId(currentUserId)
                                                                    .sessionId(sessionId)
                                                                    .build())
                                                    .agentId()
                                            : agentId));
                }

                String spawnInfo = formatSpawnInfo(key, agentId, sessionId, subagentId);
                if (!hasTask) {
                    return withSubagentExposedEvent(
                            Mono.just(ToolResultBlock.success(spawnInfo + "\nstatus: accepted")),
                            subagentId,
                            agentId,
                            sessionId,
                            canonLabel);
                }

                Mono<ToolResultBlock> execution =
                        execManagedTask(
                                managed,
                                runtimeContext,
                                parentState,
                                spawnInfo,
                                task,
                                timeoutSeconds,
                                declOpt);
                return withSubagentExposedEvent(
                        execution, subagentId, agentId, sessionId, canonLabel);
            }
        } finally {
            releaseRegistry(runtimeContext, registry);
        }
    }

    /** Runs a reusable subagent for Java callers using the original overload. */
    public Mono<ToolResultBlock> agentSpawn(
            RuntimeContext runtimeContext,
            AgentState parentState,
            String agentId,
            String task,
            String label,
            Integer timeoutSeconds,
            Boolean exposeToUser) {
        return agentSpawn(
                runtimeContext,
                parentState,
                agentId,
                task,
                true,
                label,
                timeoutSeconds,
                exposeToUser);
    }

    private Mono<ToolResultBlock> reuseManagedAgent(
            ManagedAgent managed,
            RuntimeContext runtimeContext,
            AgentState parentState,
            String currentUserId,
            String task,
            Integer timeoutSeconds,
            Optional<SubagentDeclaration> declOpt) {
        if (!isAccessible(managed, runtimeContext, parentState)) {
            return Mono.just(ToolResultBlock.error("Unknown agent_key."));
        }
        if (managed.isReleasing()) {
            return Mono.just(
                    ToolResultBlock.error("Agent is being released: " + managed.spawned.key()));
        }
        if (!managed.spawned.reusable()) {
            return Mono.just(
                    ToolResultBlock.error("Agent is not reusable: " + managed.spawned.key()));
        }
        SpawnedAgent spawned = managed.spawned;
        persistSpawnEntry(
                parentState,
                spawned.key(),
                spawned.agentId(),
                spawned.sessionId(),
                spawned.label(),
                spawned.depth(),
                spawned.reusable());
        String spawnInfo =
                formatSpawnInfo(spawned.key(), spawned.agentId(), spawned.sessionId(), null);
        if (task == null || task.isBlank()) {
            if (!propagateWithLease(managed, runtimeContext, parentState, currentUserId, declOpt)) {
                return Mono.just(
                        ToolResultBlock.error("Agent is being released: " + spawned.key()));
            }
            return Mono.just(ToolResultBlock.success(spawnInfo + "\nstatus: accepted (reused)"));
        }
        return execManagedTask(
                managed, runtimeContext, parentState, spawnInfo, task, timeoutSeconds, declOpt);
    }

    @Tool(
            name = "agent_send",
            stateInjected = true,
            timeoutManaged = true,
            description =
                    """
                    Send a follow-up to an existing subagent using agent_key or label. \
                    Returns the reply, or task_id for background work / timeout promotion. \
                    Do not rephrase a denied request to bypass permissions.\
                    """)
    public Mono<ToolResultBlock> agentSend(
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
            return Mono.just(ToolResultBlock.error("Provide either agent_key or label, not both."));
        }
        if (!hasKey && !hasLabel) {
            return Mono.just(ToolResultBlock.error("Either agent_key or label is required."));
        }
        if (message == null || message.isBlank()) {
            return Mono.just(ToolResultBlock.error("message is required"));
        }

        if (parentState == null && runtimeContext != null) {
            parentState = runtimeContext.getAgentState();
        }
        if (!canUseParentState(parentState, runtimeContext)) {
            return Mono.just(
                    ToolResultBlock.error(
                            hasKey ? "Unknown agent_key." : "Unknown label: " + label.trim()));
        }
        ScopedRegistry registry = registries.get(RegistryScope.from(runtimeContext));
        String key;
        if (hasKey) {
            key = agentKey.trim();
        } else {
            key =
                    registry != null
                            ? registry.labelToKey.get(label.trim().toLowerCase(Locale.ROOT))
                            : null;
            if (key == null) {
                key = tryResolveLabelFromState(parentState, label.trim());
            }
            if (key == null) {
                return Mono.just(ToolResultBlock.error("Unknown label: " + label.trim()));
            }
        }

        ManagedAgent resolved = registry != null ? registry.agentsByKey.get(key) : null;
        if (resolved == null) {
            var entry =
                    parentState == null
                            ? null
                            : parentState.getToolContext().getSpawnRegistry().get(key);
            if (entry != null
                    && key.equals(entry.key())
                    && !entry.reusable()
                    && !knownInAnotherScope(key, runtimeContext)) {
                return Mono.just(ToolResultBlock.error("Agent is not reusable: " + key));
            }
            resolved = tryRestoreFromState(parentState, key, runtimeContext);
        }
        if (resolved == null || !isAccessible(resolved, runtimeContext, parentState)) {
            return Mono.just(
                    ToolResultBlock.error(
                            hasKey ? "Unknown agent_key." : "Unknown label: " + label.trim()));
        }
        if (!resolved.spawned.reusable()) {
            return Mono.just(ToolResultBlock.error("Agent is not reusable: " + key));
        }
        final ManagedAgent managed = resolved;
        final SpawnedAgent spawned = managed.spawned;
        DefaultAgentManager manager = managerFor(runtimeContext);
        var declOpt = manager.getDeclaration(spawned.agentId());
        return execManagedTask(
                managed,
                runtimeContext,
                parentState,
                "agent_key: " + key,
                message,
                timeoutSeconds,
                declOpt);
    }

    @Tool(
            name = "agent_release",
            stateInjected = true,
            description =
                    "Release an idle subagent by agent_key or label. Running agents cannot be"
                            + " released.")
    public ToolResultBlock agentRelease(
            RuntimeContext runtimeContext,
            AgentState parentState,
            @ToolParam(name = "agent_key", required = false) String agentKey,
            @ToolParam(name = "label", required = false) String label) {
        boolean hasKey = agentKey != null && !agentKey.isBlank();
        boolean hasLabel = label != null && !label.isBlank();
        if (hasKey == hasLabel) {
            return ToolResultBlock.error("Provide exactly one of agent_key or label.");
        }

        if (parentState == null && runtimeContext != null) {
            parentState = runtimeContext.getAgentState();
        }
        if (!canUseParentState(parentState, runtimeContext)) {
            return ToolResultBlock.error(
                    hasKey ? "Unknown agent_key." : "Unknown label: " + label.trim());
        }
        ScopedRegistry registry = registries.get(RegistryScope.from(runtimeContext));
        String key =
                hasKey
                        ? agentKey.trim()
                        : registry != null
                                ? registry.labelToKey.get(label.trim().toLowerCase(Locale.ROOT))
                                : null;
        if (key == null && hasLabel) {
            key = tryResolveLabelFromState(parentState, label.trim());
        }
        if (key == null) {
            return ToolResultBlock.error("Unknown label: " + label.trim());
        }

        if (knownInAnotherScope(key, runtimeContext)) {
            return ToolResultBlock.error("Unknown agent_key.");
        }
        ManagedAgent managed;
        boolean releasedPersistedOnly = false;
        if (registry != null) {
            synchronized (registry) {
                managed = registry.agentsByKey.get(key);
                if (managed == null) {
                    releasedPersistedOnly = releasePersistedSpawnEntry(parentState, key);
                    if (releasedPersistedOnly) {
                        registry.labelToKey.values().removeIf(key::equals);
                    }
                } else {
                    if (!isAccessible(managed, runtimeContext, parentState)) {
                        return ToolResultBlock.error("Unknown agent_key.");
                    }
                    if (!managed.beginRelease()) {
                        return ToolResultBlock.error(
                                "Agent is busy and cannot be released: " + key);
                    }
                    registry.agentsByKey.remove(key, managed);
                    if (managed.spawned.label() != null) {
                        registry.labelToKey.remove(
                                managed.spawned.label().toLowerCase(Locale.ROOT), key);
                    }
                    removePersistedSpawnEntry(parentState, key);
                }
            }
        } else {
            managed = null;
            releasedPersistedOnly = releasePersistedSpawnEntry(parentState, key);
        }
        if (managed == null) {
            if (releasedPersistedOnly) {
                if (registry != null) {
                    removeRegistryIfEmpty(runtimeContext, registry);
                }
                return ToolResultBlock.success("status: released\nagent_key: " + key);
            }
            return ToolResultBlock.error("Unknown agent_key.");
        }
        closeManagedAgent(managed);
        removeRegistryIfEmpty(runtimeContext, registry);
        return ToolResultBlock.success("status: released\nagent_key: " + key);
    }

    @Tool(
            name = "agent_list",
            stateInjected = true,
            description =
                    "List retained subagents addressable in the current user and parent session.")
    public ToolResultBlock agentList(RuntimeContext runtimeContext, AgentState parentState) {
        if (parentState == null && runtimeContext != null) {
            parentState = runtimeContext.getAgentState();
        }
        if (!canUseParentState(parentState, runtimeContext)) {
            return formatAgentList(Map.of(), null);
        }
        ScopedRegistry registry = registries.get(RegistryScope.from(runtimeContext));
        Map<String, SpawnEntry> entries = new LinkedHashMap<>();
        if (isParentStateOwner(parentState, runtimeContext)) {
            for (var item : parentState.getToolContext().getSpawnRegistry().entrySet()) {
                String key = item.getKey();
                SpawnEntry entry = item.getValue();
                ManagedAgent live = registry == null ? null : registry.agentsByKey.get(key);
                if (key.equals(entry.key())
                        && (live == null
                                ? !knownInAnotherScope(key, runtimeContext)
                                : isAccessible(live, runtimeContext, parentState))) {
                    entries.put(key, entry);
                }
            }
        }
        if (registry != null) {
            for (ManagedAgent managed : registry.agentsByKey.values()) {
                SpawnedAgent a = managed.spawned;
                if (!managed.isReleasing() && isAccessible(managed, runtimeContext, parentState)) {
                    entries.put(a.key(), spawnEntry(a));
                }
            }
        }
        return formatAgentList(entries, registry);
    }

    public ToolResultBlock agentList(RuntimeContext runtimeContext) {
        return agentList(runtimeContext, null);
    }

    /** Compatibility entry point without call identity. */
    public ToolResultBlock agentList() {
        return agentList(RuntimeContext.empty());
    }

    private static ToolResultBlock formatAgentList(
            Map<String, SpawnEntry> entries, ScopedRegistry registry) {
        if (entries.isEmpty()) {
            return ToolResultBlock.success("No addressable subagents.");
        }
        StringBuilder sb =
                new StringBuilder("Addressable subagents (").append(entries.size()).append("):\n");
        for (SpawnEntry entry : entries.values()) {
            sb.append("- agent_key: ").append(entry.key()).append("\n");
            sb.append("  agent_id: ").append(entry.agentId()).append("\n");
            if (entry.label() != null) {
                sb.append("  label: ").append(entry.label()).append("\n");
            }
            sb.append("  spawn_depth: ").append(entry.depth()).append("\n");
            ManagedAgent live = registry != null ? registry.agentsByKey.get(entry.key()) : null;
            if (live != null) {
                sb.append("  state: ")
                        .append(live.isReleasing() ? "releasing" : "active")
                        .append("\n");
                sb.append("  in_flight: ").append(live.inFlight()).append("\n");
            }
        }
        return ToolResultBlock.success(sb.toString().trim());
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    private ScopedRegistry registryFor(RuntimeContext runtimeContext) {
        synchronized (registries) {
            ScopedRegistry registry =
                    registries.computeIfAbsent(
                            RegistryScope.from(runtimeContext), ignored -> new ScopedRegistry());
            registry.users++;
            return registry;
        }
    }

    private void releaseRegistry(RuntimeContext runtimeContext, ScopedRegistry registry) {
        synchronized (registries) {
            registry.users--;
            removeRegistryIfEmptyLocked(runtimeContext, registry);
        }
    }

    private void removeRegistryIfEmpty(RuntimeContext runtimeContext, ScopedRegistry registry) {
        synchronized (registries) {
            removeRegistryIfEmptyLocked(runtimeContext, registry);
        }
    }

    private void removeRegistryIfEmptyLocked(
            RuntimeContext runtimeContext, ScopedRegistry registry) {
        if (registry.users == 0
                && registry.agentsByKey.isEmpty()
                && registry.labelToKey.isEmpty()) {
            registries.remove(RegistryScope.from(runtimeContext), registry);
        }
    }

    private static boolean removePersistedSpawnEntry(AgentState parentState, String key) {
        if (parentState == null
                || !parentState.getToolContext().getSpawnRegistry().containsKey(key)) {
            return false;
        }
        parentState.getToolContext().removeSpawnEntry(key);
        return true;
    }

    private boolean releasePersistedSpawnEntry(AgentState parentState, String key) {
        SpawnEntry entry =
                parentState == null
                        ? null
                        : parentState.getToolContext().getSpawnRegistry().get(key);
        if (entry == null || !key.equals(entry.key())) {
            return false;
        }
        revokeGatewayExposure(entry.sessionId());
        parentState.getToolContext().removeSpawnEntry(key);
        return true;
    }

    private void closeManagedAgent(ManagedAgent managed) {
        try {
            revokeGatewayExposure(managed.spawned.sessionId());
            closeAgent(managed.spawned.agent());
        } finally {
            managed.markClosed();
        }
    }

    /** Closes a one-shot subagent after its execution lease reaches a terminal state. */
    private void releaseOneShot(
            ManagedAgent managed, RuntimeContext runtimeContext, AgentState parentState) {
        if (managed.spawned.reusable()) {
            return;
        }
        ScopedRegistry registry = registries.get(RegistryScope.from(runtimeContext));
        if (registry == null) {
            if (managed.beginRelease()) {
                removePersistedSpawnEntry(parentState, managed.spawned.key());
                closeManagedAgent(managed);
            }
            return;
        }

        synchronized (registry) {
            if (!managed.beginRelease()) {
                return;
            }
            registry.agentsByKey.remove(managed.spawned.key(), managed);
            if (managed.spawned.label() != null) {
                registry.labelToKey.remove(
                        managed.spawned.label().toLowerCase(Locale.ROOT), managed.spawned.key());
            }
            removePersistedSpawnEntry(parentState, managed.spawned.key());
        }
        closeManagedAgent(managed);
        removeRegistryIfEmpty(runtimeContext, registry);
    }

    private void revokeGatewayExposure(String sessionId) {
        SubagentGatewayBridge bridge = gatewayBridge;
        if (bridge == null) {
            return;
        }
        try {
            if (!bridge.revokeBySessionId(sessionId)) {
                log.debug(
                        "Gateway bridge does not support revoking exposed subagent session {}",
                        sessionId);
            }
        } catch (RuntimeException e) {
            log.warn("Failed to revoke exposed subagent session {}", sessionId, e);
        }
    }

    private static void closeAgent(Agent agent) {
        if (agent instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.warn("Failed to close released subagent {}", agent.getClass().getName(), e);
            }
        }
    }

    /**
     * Closes all live child instances owned by this tool. Persisted spawn metadata is deliberately
     * left intact so a later parent invocation can restore persistent sessions.
     */
    @Override
    public void close() {
        for (Map.Entry<RegistryScope, ScopedRegistry> scoped : registries.entrySet()) {
            ScopedRegistry registry = scoped.getValue();
            for (Map.Entry<String, ManagedAgent> entry : registry.agentsByKey.entrySet()) {
                ManagedAgent managed = entry.getValue();
                if (managed.beginClose() && registry.agentsByKey.remove(entry.getKey(), managed)) {
                    if (managed.spawned.label() != null) {
                        registry.labelToKey.remove(
                                managed.spawned.label().toLowerCase(Locale.ROOT), entry.getKey());
                    }
                    closeManagedAgent(managed);
                }
            }
            registries.remove(scoped.getKey(), registry);
        }
    }

    private DefaultAgentManager managerFor(RuntimeContext runtimeContext) {
        DefaultAgentManager scoped =
                runtimeContext != null
                        ? runtimeContext.get(CTX_AGENT_MANAGER, DefaultAgentManager.class)
                        : null;
        return scoped != null ? scoped : agentManager;
    }

    private static boolean canUseParentState(AgentState state, RuntimeContext context) {
        return hostOwnsChildState(context) || isParentStateOwner(state, context);
    }

    private static boolean isAccessible(
            ManagedAgent managed, RuntimeContext context, AgentState parentState) {
        if (hostOwnsChildState(context)) {
            return true;
        }
        SpawnedAgent child = managed.spawned;
        return isParentStateOwner(parentState, context)
                && spawnEntry(child)
                        .equals(parentState.getToolContext().getSpawnRegistry().get(child.key()));
    }

    private boolean knownInAnotherScope(String key, RuntimeContext context) {
        RegistryScope scope = RegistryScope.from(context);
        return registries.entrySet().stream()
                .anyMatch(
                        entry ->
                                !scope.equals(entry.getKey())
                                        && entry.getValue().agentsByKey.containsKey(key));
    }

    private static SpawnEntry spawnEntry(SpawnedAgent child) {
        return new SpawnEntry(
                child.key(),
                child.agentId(),
                child.sessionId(),
                child.label(),
                child.depth(),
                child.reusable());
    }

    /**
     * Never infer a caller's identity from supplied state. Anonymous users remain valid, but local
     * continuation requires an explicit context with a non-blank session matching that state.
     */
    private static boolean isParentStateOwner(AgentState parentState, RuntimeContext context) {
        return parentState != null
                && context != null
                && context.getSessionId() != null
                && !context.getSessionId().isBlank()
                && Objects.equals(parentState.getUserId(), context.getUserId())
                && Objects.equals(parentState.getSessionId(), context.getSessionId());
    }

    private static boolean propagateWithLease(
            ManagedAgent managed,
            RuntimeContext runtimeContext,
            AgentState parentState,
            String userId,
            Optional<SubagentDeclaration> declaration) {
        Lease lease = managed.tryAcquire();
        if (lease == null) {
            return false;
        }
        try {
            SpawnedAgent spawned = managed.spawned;
            if (!hostOwnsChildState(runtimeContext)) {
                propagatePlanMode(parentState, userId, spawned.sessionId(), spawned.agent());
                propagateParentDenyRules(
                        parentState, userId, spawned.sessionId(), spawned.agent(), declaration);
            }
            return true;
        } finally {
            lease.close();
        }
    }

    private static boolean hostOwnsChildState(RuntimeContext context) {
        var host = context == null ? null : context.get(SubagentExecution.class);
        return host != null && host.ownsChildState();
    }

    /**
     * Activates plan mode on a local child immediately before it can be invoked.
     *
     * <p>This must run for both newly-created and reused children. In particular, a persistent
     * child may have been created while its parent was in build mode and then reused after the
     * parent entered plan mode.
     */
    private static void propagatePlanMode(
            AgentState parentState, String userId, String sessionId, Agent child) {
        if (parentState != null
                && parentState.getPlanModeContext().isPlanActive()
                && child instanceof HarnessAgent harnessChild) {
            harnessChild.enterPlanMode(userId, sessionId);
        }
    }

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
                            DefaultAgentManager manager = managerFor(parentCtx);
                            // ── Path 1: streamEvents() — AgentEvent forwarding ──
                            Optional<AgentEventEmitter> emitterOpt =
                                    AgentEventEmitter.fromContext(ctxView);
                            if (emitterOpt.isPresent()) {
                                AgentEventEmitter parentEmitter = emitterOpt.get();
                                String sourcePath = buildSourcePath(spawned, parentCtx);
                                String replyId = UUID.randomUUID().toString().replace("-", "");
                                AgentEventEmitter taggedEmitter =
                                        event -> parentEmitter.emit(event.withSource(sourcePath));

                                parentEmitter.emit(
                                        new AgentStartEvent(
                                                        spawned.sessionId(),
                                                        replyId,
                                                        spawned.agentId())
                                                .withSource(sourcePath));

                                AtomicBoolean endEmitted = new AtomicBoolean();
                                Runnable emitEnd =
                                        () -> {
                                            if (endEmitted.compareAndSet(false, true)) {
                                                parentEmitter.emit(
                                                        new AgentEndEvent(replyId)
                                                                .withSource(sourcePath));
                                            }
                                        };

                                return manager.invokeAgent(
                                                agent, sessionId, userId, prompt, parentCtx)
                                        .contextWrite(
                                                c ->
                                                        c.put(
                                                                AgentEventEmitter
                                                                        .FORWARDING_CONTEXT_KEY,
                                                                taggedEmitter))
                                        // Emit before success or error reaches the parent, which
                                        // may
                                        // otherwise complete its event sink before doFinally runs.
                                        .doOnSuccess(ignored -> emitEnd.run())
                                        .doOnError(ignored -> emitEnd.run())
                                        // Preserve best-effort cancellation signaling without
                                        // emitting a
                                        // duplicate if cancellation races with normal termination.
                                        .doFinally(
                                                signal -> {
                                                    if (signal == SignalType.CANCEL) {
                                                        emitEnd.run();
                                                    }
                                                });
                            }

                            // ── Path 2: stream() (deprecated) — SubagentEventBus forwarding ──
                            if (ctxView.hasKey(SubagentEventBus.CONTEXT_KEY)) {
                                SubagentEventBus bus = ctxView.get(SubagentEventBus.CONTEXT_KEY);
                                EventSource childSource = buildChildSource(spawned, parentCtx);

                                return manager.invokeAgentStream(
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
                                                            "[execLocalSync] forwarding child event"
                                                                    + " to bus: type={} msgId={}"
                                                                    + " isLast={}",
                                                            e.getType(),
                                                            e.getMessage().getId(),
                                                            e.isLast());
                                                    bus.emit(e);
                                                })
                                        .filter(
                                                e ->
                                                        e.isLast()
                                                                && e.getType()
                                                                        == EventType.AGENT_RESULT)
                                        .last()
                                        .map(e -> e.getMessage())
                                        .switchIfEmpty(
                                                Mono.defer(
                                                        () ->
                                                                manager.invokeAgent(
                                                                        agent, sessionId, userId,
                                                                        prompt, parentCtx)));
                            }

                            // ── Path 3: non-streaming ──
                            return manager.invokeAgent(agent, sessionId, userId, prompt, parentCtx);
                        })
                .doOnSuccess(
                        reply -> {
                            var recorder =
                                    io.agentscope.core.journal.InvocationRecorder.from(parentCtx);
                            if (recorder != null
                                    && RunOutcome.fromResult(reply).status()
                                            != RunOutcome.Status.SUSPENDED) {
                                recorder.recordIfOpen(
                                        "subagent/completed",
                                        Map.of(
                                                "childSessionId",
                                                sessionId,
                                                "childAgentId",
                                                spawned.agentId(),
                                                "status",
                                                "completed"));
                            }
                        });
    }

    /**
     * Executes a local subagent with timeout promotion: if the agent doesn't finish within
     * {@code timeoutMs}, the in-flight execution is promoted to an async background task instead
     * of being cancelled and lost — unless {@code forceSync} is true, in which case the agent is
     * interrupted and {@code status: timeout} is returned with no background promotion.
     *
     * <p>The key mechanism is a {@link CompletableFuture} bridge that decouples execution from
     * observation. The Mono from {@link #execLocalSync} is subscribed with Reactor Context
     * propagation (so streaming events flow during the sync wait period), and its result feeds
     * the bridge. A race future derived from the bridge adds a timeout without cancelling the
     * original:
     *
     * <ul>
     *   <li>Agent finishes before timeout → normal result returned
     *   <li>Timeout fires first (default) → bridge (still running) is registered in {@link
     *       TaskRepository} as an {@link TaskRunSpec.AdoptedTaskRunSpec}, and a {@code task_id} is
     *       returned
     *   <li>Timeout fires first ({@code forceSync}) → agent interrupted, {@code status: timeout},
     *       no {@code task_id}
     *   <li>Agent errors → error message returned
     * </ul>
     */
    private Mono<ToolResultBlock> execWithTimeoutPromotion(
            ManagedAgent managed,
            String task,
            RuntimeContext runtimeContext,
            String header,
            long timeoutMs,
            boolean forceSync,
            Lease lease) {

        return Mono.deferContextual(
                parentCtx -> {
                    SpawnedAgent spawned = managed.spawned;
                    RuntimeContext interruptContext =
                            childInterruptContext(runtimeContext, spawned.sessionId());
                    CompletableFuture<Msg> bridge = new CompletableFuture<>();
                    bridge.whenComplete((ignored, error) -> lease.close());
                    RuntimeContext invocationContext =
                            RuntimeContext.builder(runtimeContext)
                                    .put(
                                            SubagentExecution.Invocation.class,
                                            new SubagentExecution.Invocation(
                                                    spawned.agentId(), task, header))
                                    .build();
                    return Mono.<ToolResultBlock>create(
                            sink -> {
                                Mono<Msg> inner =
                                        execLocalSync(
                                                        spawned.agent(),
                                                        spawned.sessionId(),
                                                        runtimeContext != null
                                                                ? runtimeContext.getUserId()
                                                                : null,
                                                        task,
                                                        spawned,
                                                        invocationContext)
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
                                                                        spawned.agent(),
                                                                        interruptContext);
                                                                lease.close();
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
                                                        spawned.agentId(),
                                                        sink,
                                                        forceSync,
                                                        innerSub);
                                            } else {
                                                sink.success(
                                                        ToolResultBlock.success(
                                                                header
                                                                        + "\n"
                                                                        + formatLocalSyncReply(
                                                                                msg)));
                                            }
                                        });

                                // Propagate parent cancellation to the inner fire-and-forget
                                // subscription. Without this, doFinally(CANCEL) above never
                                // fires because the inner is subscribed independently of the
                                // parent Mono.create sink.
                                sink.onCancel(innerSub);
                            });
                });
    }

    /**
     * Interrupts the sub-agent's reasoning loop when its parent tool-call subscription is
     * cancelled. Mirrors the fix in core {@code SubAgentTool.interruptAgent} (commit
     * {@code 029cc55e}, issue #1783) — see issue #2062 for the harness-side equivalent.
     *
     * <p>{@link HarnessAgent} wraps a {@link ReActAgent}, so cancellation must reach its delegate.
     * Other {@link Agent} implementations are no-ops here; their inner execution will still be
     * disposed by the caller's {@code sink.onCancel(innerSub::dispose)}, which is enough for
     * non-looping agents.
     */
    private void interruptAgent(Agent agent, RuntimeContext ctx) {
        ReActAgent target = null;
        if (agent instanceof ReActAgent reactAgent) {
            target = reactAgent;
        } else if (agent instanceof HarnessAgent harnessAgent) {
            target = harnessAgent.getDelegate();
        }
        if (target != null) {
            target.interrupt(ctx);
            log.warn(
                    "Sub-agent '{}' (id={}) was interrupted because its parent tool call"
                            + " subscription was cancelled.",
                    target.getName(),
                    target.getAgentId());
        }
    }

    /**
     * Builds the context used to interrupt a spawned child. The child executes under its own
     * session, so using the parent context would target the parent's interrupt-control state.
     */
    private static RuntimeContext childInterruptContext(
            RuntimeContext parent, String childSessionId) {
        String userId = parent != null ? parent.getUserId() : null;
        if (parent == null) {
            return RuntimeContext.builder().sessionId(childSessionId).userId(userId).build();
        }
        return RuntimeContext.builder(parent).sessionId(childSessionId).userId(userId).build();
    }

    /**
     * Handles errors from the race future in {@link #execWithTimeoutPromotion}. Separated to keep
     * the lambda readable — it distinguishes timeout (→ promote, or hard-fail under force-sync)
     * from real errors (→ report).
     */
    private void handleExecError(
            Throwable err,
            CompletableFuture<Msg> bridge,
            RuntimeContext runtimeContext,
            String header,
            long timeoutMs,
            String agentId,
            reactor.core.publisher.MonoSink<ToolResultBlock> sink,
            boolean forceSync,
            Disposable innerSub) {

        Throwable cause = err instanceof CompletionException ? err.getCause() : err;
        if (cause instanceof ToolSuspendException || cause instanceof JournalException) {
            sink.error(cause);
            return;
        }
        if (cause instanceof TimeoutException) {
            if (forceSync) {
                // Hard timeout: dispose triggers doFinally(CANCEL) → interruptAgent; no promote.
                if (innerSub != null && !innerSub.isDisposed()) {
                    innerSub.dispose();
                }
                log.info(
                        "agent_spawn sync timeout after {}ms under force_sync (not promoted):"
                                + " agentId={}",
                        timeoutMs,
                        agentId);
                sink.success(
                        ToolResultBlock.text(header + "\n" + formatForceSyncTimeout(timeoutMs))
                                .withState(ToolResultState.ERROR));
                return;
            }
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
            sink.success(
                    ToolResultBlock.success(
                            header + "\n" + formatTimeoutPromoted(taskId, timeoutMs)));
        } else {
            Throwable reportable = cause != null ? cause : err;
            String errStr =
                    reportable.getMessage() != null
                            ? reportable.getMessage()
                            : reportable.getClass().getSimpleName();
            log.warn("agent execution failed: agentId={}", agentId, reportable);
            sink.success(
                    ToolResultBlock.text(header + "\nstatus: error\nerror: " + errStr)
                            .withState(ToolResultState.ERROR));
        }
    }

    private void persistSpawnEntry(
            AgentState parentState,
            String key,
            String agentId,
            String sessionId,
            String label,
            int depth,
            boolean reusable) {
        if (parentState == null) {
            return;
        }
        parentState
                .getToolContext()
                .putSpawnEntry(
                        key, new SpawnEntry(key, agentId, sessionId, label, depth, reusable));
    }

    private String tryResolveLabelFromState(AgentState parentState, String label) {
        if (parentState == null) {
            return null;
        }
        String lowerLabel = label.toLowerCase(Locale.ROOT);
        for (SpawnEntry entry : parentState.getToolContext().getSpawnRegistry().values()) {
            if (entry.label() != null
                    && entry.label().toLowerCase(Locale.ROOT).equals(lowerLabel)) {
                return entry.key();
            }
        }
        return null;
    }

    private ManagedAgent tryRestoreFromState(
            AgentState parentState, String key, RuntimeContext runtimeContext) {
        if (!isParentStateOwner(parentState, runtimeContext)) {
            return null;
        }
        SpawnEntry entry = parentState.getToolContext().getSpawnRegistry().get(key);
        if (entry == null
                || !Objects.equals(key, entry.key())
                || !entry.reusable()
                || knownInAnotherScope(key, runtimeContext)) {
            return null;
        }
        Optional<Agent> agentOpt =
                managerFor(runtimeContext).createAgentIfPresent(entry.agentId(), runtimeContext);
        if (agentOpt.isEmpty()) {
            return null;
        }
        SpawnedAgent restored =
                new SpawnedAgent(
                        key,
                        entry.agentId(),
                        entry.sessionId(),
                        entry.label(),
                        agentOpt.get(),
                        entry.depth(),
                        entry.reusable());
        ManagedAgent candidate = new ManagedAgent(restored);
        ScopedRegistry registry = registryFor(runtimeContext);
        try {
            ManagedAgent winner;
            synchronized (registry) {
                SpawnEntry currentEntry = parentState.getToolContext().getSpawnRegistry().get(key);
                if (!entry.equals(currentEntry)) {
                    closeAgent(restored.agent());
                    return null;
                }
                winner = registry.agentsByKey.putIfAbsent(key, candidate);
                if (winner == null && entry.label() != null) {
                    String existing =
                            registry.labelToKey.putIfAbsent(
                                    entry.label().toLowerCase(Locale.ROOT), key);
                    if (existing != null && !existing.equals(key)) {
                        registry.agentsByKey.remove(key, candidate);
                        closeManagedAgent(candidate);
                        return null;
                    }
                }
            }
            if (winner != null) {
                closeAgent(restored.agent());
                return winner;
            }
            log.info(
                    "Restored subagent from persisted state: key={}, agentId={}",
                    key,
                    entry.agentId());
            return candidate;
        } finally {
            releaseRegistry(runtimeContext, registry);
        }
    }

    private static String formatLocalSyncReply(Msg msg) {
        String reply = textOf(msg);
        if (msg != null && msg.getGenerateReason() == GenerateReason.MAX_ITERATIONS) {
            return reply;
        }
        return "status: ok\nreply:\n" + reply;
    }

    /**
     * Renders a subagent reply as text for the task result.
     *
     * <p>The background task contract stores a string, so a delivered document cannot travel as
     * a block here. It is named in a reference instead of disappearing. Replies stopped by the
     * iteration limit retain their completion reason and are explicitly marked as partial.
     */
    private static String textOf(Msg msg) {
        String reply = DocumentReferenceRenderer.renderMessageText(msg);
        if (msg != null && msg.getGenerateReason() == GenerateReason.MAX_ITERATIONS) {
            return "status: max_iterations\ngenerate_reason: MAX_ITERATIONS\n"
                    + "note: The subagent reached its maximum iterations. The reply is a partial"
                    + " result; the task may be incomplete.\nreply:\n"
                    + reply;
        }
        return reply;
    }

    private static String formatTimeoutPromoted(String taskId, long timeoutMs) {
        return String.format(
                """
                status: timeout_promoted
                task_id: %s
                The task exceeded the %ds sync timeout but is still running in the background. \
                Use task_output(task_id='%s', block=false) to check status, \
                wait_async_results(task_ids=...) when this task is part of a required barrier, \
                or wait — completed tasks are pushed back to you automatically. \
                Do NOT retry the same task.\
                """,
                taskId, timeoutMs / 1000, taskId);
    }

    private static String formatForceSyncTimeout(long timeoutMs) {
        return String.format(
                """
                status: timeout
                The task exceeded the %ds sync timeout and was interrupted because force_sync                 is enabled. Background promotion is disabled for this call.\
                """,
                timeoutMs / 1000);
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
     * Builds a source path string for the {@link AgentEvent#withSource} tag. The child's own
     * session id is the final segment so consumers can distinguish multiple instances of the same
     * subagent type within one parent session.
     */
    private String buildSourcePath(SpawnedAgent spawned, RuntimeContext parentCtx) {
        String parentName =
                (parentCtx != null && parentCtx.getSessionId() != null)
                        ? parentCtx.getSessionId()
                        : "main";
        return parentName + "/" + spawned.agentId() + "/" + spawned.sessionId();
    }

    /**
     * Builds a {@code parentSession/agentId/taskId} source path for remote events forwarded into
     * the parent's stream. The task id is the remote invocation discriminator.
     */
    static String buildRemoteSourcePath(String parentSessionId, String agentId, String taskId) {
        String parent =
                parentSessionId != null && !parentSessionId.isBlank() ? parentSessionId : "main";
        String child = agentId != null && !agentId.isBlank() ? agentId : "remote";
        String invocation = taskId != null && !taskId.isBlank() ? taskId.trim() : "task";
        return parent + "/" + child + "/" + invocation;
    }

    /**
     * Tags a remote-forwarded {@link AgentEvent} with the parent-visible {@code source} path, the
     * harness {@link AgentEvent#METADATA_TASK_ID}, and {@link
     * AgentEvent#METADATA_PARENT_SESSION_ID} so concurrent calls to the same remote agent stay
     * correlatable to distinct {@code TaskRecord}s and to the parent session that spawned them.
     */
    static AgentEvent tagRemoteForwardedEvent(
            AgentEvent event, String sourcePath, String taskId, String parentSessionId) {
        if (event == null) {
            return null;
        }
        event.withSource(sourcePath);
        if (taskId != null && !taskId.isBlank()) {
            event.withMetadataEntry(AgentEvent.METADATA_TASK_ID, taskId);
        }
        if (parentSessionId != null && !parentSessionId.isBlank()) {
            event.withMetadataEntry(AgentEvent.METADATA_PARENT_SESSION_ID, parentSessionId.trim());
        }
        return event;
    }

    /**
     * Builds submission metadata for a remote task (streaming preference, parent identity, denied
     * permission rules, caller-defined attributes).
     */
    private RemoteSubmitContext buildRemoteSubmitContext(
            RuntimeContext runtimeContext, AgentState parentState, SubagentDeclaration decl) {
        String userId = runtimeContext != null ? runtimeContext.getUserId() : null;
        String parentSessionId = runtimeContext != null ? runtimeContext.getSessionId() : null;
        boolean stream = decl != null && decl.isRemoteStreaming();
        String detail =
                stream
                        ? decl.getRemoteStreamDetail().wireValue()
                        : RemoteStreamDetail.STATUS.wireValue();
        return RemoteSubmitContext.builder().userId(userId).parentSessionId(parentSessionId).stream(
                        stream)
                .detail(detail)
                .denyRules(collectParentDenyRules(parentState, Optional.ofNullable(decl)))
                .attributes(collectRemoteContextAttributes(runtimeContext, decl))
                .build();
    }

    /**
     * Merges the subagent's declared static attributes with the per-call ones from {@link
     * #CTX_REMOTE_CONTEXT_ATTRIBUTES}; per-call entries win on conflict.
     */
    static Map<String, Object> collectRemoteContextAttributes(
            RuntimeContext runtimeContext, SubagentDeclaration decl) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (decl != null && decl.getRemoteContextAttributes() != null) {
            merged.putAll(decl.getRemoteContextAttributes());
        }
        Object perCall =
                runtimeContext != null ? runtimeContext.get(CTX_REMOTE_CONTEXT_ATTRIBUTES) : null;
        if (perCall instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    merged.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
        }
        return merged;
    }

    /**
     * Flattens parent DENY rules into wire maps for {@link RemoteSubmitContext}. Returns an empty
     * list when inheritance is disabled or the parent has no DENY rules.
     */
    static List<Map<String, String>> collectParentDenyRules(
            AgentState parentState, Optional<SubagentDeclaration> declaration) {
        boolean inherit =
                declaration.map(SubagentDeclaration::isInheritParentPermissions).orElse(true);
        if (!inherit || parentState == null) {
            return List.of();
        }
        PermissionContextState parentPermissions = parentState.getPermissionContext();
        if (parentPermissions == null || parentPermissions.getDenyRules().isEmpty()) {
            return List.of();
        }
        List<Map<String, String>> out = new ArrayList<>();
        parentPermissions
                .getDenyRules()
                .forEach(
                        (toolName, rules) -> {
                            for (PermissionRule rule : rules) {
                                Map<String, String> m = new LinkedHashMap<>();
                                m.put("tool_name", rule.toolName());
                                if (rule.ruleContent() != null) {
                                    m.put("rule_content", rule.ruleContent());
                                }
                                m.put("behavior", rule.behavior().name());
                                m.put("source", rule.source());
                                out.add(m);
                            }
                        });
        return out;
    }

    /**
     * Reactive entry for remote sync execution. Captures {@link AgentEventEmitter} from Reactor
     * Context before blocking on the remote task.
     */
    private Mono<ToolResultBlock> runRemoteSyncReactive(
            RuntimeContext runtimeContext,
            AgentState parentState,
            String header,
            String agentId,
            String parentSessionId,
            SubagentDeclaration decl,
            String input,
            long timeoutMs,
            boolean forceSync,
            Lease lease) {
        return Mono.deferContextual(
                ctxView -> {
                    Optional<AgentEventEmitter> emitterOpt = AgentEventEmitter.fromContext(ctxView);
                    return Mono.fromCallable(
                                    () ->
                                            runRemoteSync(
                                                    runtimeContext,
                                                    parentState,
                                                    header,
                                                    agentId,
                                                    parentSessionId,
                                                    decl,
                                                    input,
                                                    timeoutMs,
                                                    emitterOpt.orElse(null),
                                                    forceSync,
                                                    lease))
                            .doOnError(ignored -> lease.close())
                            .doFinally(
                                    signal -> {
                                        if (signal == SignalType.CANCEL) {
                                            lease.close();
                                        }
                                    });
                });
    }

    /**
     * Submits a remote task through {@link TaskRepository} (for durable state) and blocks until
     * it completes or the timeout elapses.
     *
     * <p>When an {@link AgentEventEmitter} is present and {@link SubagentDeclaration#isRemoteStreaming()}
     * is true, remote events are forwarded into the parent stream. Without an emitter, or when
     * {@link RemoteAskPolicy#DENY} applies, pending remote confirmations are auto-denied via
     * {@link RemoteSubagentTransport#resume}.
     */
    private ToolResultBlock runRemoteSync(
            RuntimeContext runtimeContext,
            AgentState parentState,
            String header,
            String agentId,
            String parentSessionId,
            SubagentDeclaration decl,
            String input,
            long timeoutMs,
            AgentEventEmitter emitter,
            boolean forceSync,
            Lease lease) {
        String taskId = "task_" + UUID.randomUUID();
        RemoteSubmitContext submitContext =
                buildRemoteSubmitContext(runtimeContext, parentState, decl);
        TaskRunSpec spec =
                new TaskRunSpec.RemoteTaskRunSpec(
                        decl.getUrl(), decl.getHeaders(), agentId, input, submitContext);
        BackgroundTask bgTask;
        try {
            bgTask = taskRepository.putTask(runtimeContext, taskId, agentId, parentSessionId, spec);
            bgTask.whenComplete((ignored, error) -> lease.close());
        } catch (RuntimeException e) {
            lease.close();
            throw e;
        }

        RemoteTarget target = new RemoteTarget(decl.getUrl(), decl.getHeaders());
        RemoteSubagentTransport transport = this.remoteTransport;
        String sourcePath = buildRemoteSourcePath(parentSessionId, agentId, taskId);
        boolean wantStream = emitter != null && decl.isRemoteStreaming();
        AtomicBoolean autoDenied = new AtomicBoolean(false);
        AtomicBoolean resumedEpisode = new AtomicBoolean(false);

        Closeable streamHandle = () -> {};
        try {
            if (wantStream) {
                streamHandle =
                        transport.streamEvents(
                                target,
                                taskId,
                                0L,
                                remoteEvent ->
                                        RemoteEventCodec.toAgentEvent(remoteEvent)
                                                .ifPresent(
                                                        ae ->
                                                                emitter.emit(
                                                                        tagRemoteForwardedEvent(
                                                                                ae,
                                                                                sourcePath,
                                                                                taskId,
                                                                                parentSessionId))));
            }

            long deadlineMs = System.currentTimeMillis() + Math.max(timeoutMs, 0L);
            while (true) {
                long remaining = deadlineMs - System.currentTimeMillis();
                if (remaining <= 0) {
                    if (forceSync) {
                        taskRepository.cancelTask(runtimeContext, parentSessionId, taskId);
                        log.info(
                                "agent remote sync timeout after {}ms under force_sync"
                                        + " (cancelled): agentId={}, taskId={}",
                                timeoutMs,
                                agentId,
                                taskId);
                        return ToolResultBlock.text(
                                        header + "\n" + formatForceSyncTimeout(timeoutMs))
                                .withState(ToolResultState.ERROR);
                    }
                    return ToolResultBlock.success(
                            header + "\nstatus: timeout\ntask_id: " + taskId);
                }
                long slice = Math.min(REMOTE_CONFIRM_POLL_MS, remaining);
                boolean done = bgTask.waitForCompletion(slice);

                try {
                    RemoteTaskStatus st = transport.getStatus(target, taskId);
                    if (st.isAwaitingConfirm()) {
                        boolean shouldAutoDeny =
                                emitter == null
                                        || decl.getRemoteAskPolicy() == RemoteAskPolicy.DENY;
                        if (shouldAutoDeny && resumedEpisode.compareAndSet(false, true)) {
                            List<RemotePendingConfirm> pending =
                                    st.pendingConfirms() != null ? st.pendingConfirms() : List.of();
                            List<RemoteConfirmDecision> decisions = new ArrayList<>(pending.size());
                            for (RemotePendingConfirm p : pending) {
                                decisions.add(new RemoteConfirmDecision(p.getToolCallId(), false));
                            }
                            if (!decisions.isEmpty()) {
                                transport.resume(target, taskId, decisions);
                                autoDenied.set(true);
                            }
                        }
                    } else {
                        resumedEpisode.set(false);
                    }
                } catch (Exception e) {
                    log.debug(
                            "Remote status poll during sync wait failed for {}: {}",
                            taskId,
                            e.getMessage());
                }

                if (done) {
                    break;
                }
            }

            TaskStatus ts = bgTask.getTaskStatus();
            if (ts == TaskStatus.FAILED) {
                Exception err = bgTask.getError();
                String msg = err != null ? err.getMessage() : "remote task failed";
                return ToolResultBlock.text(header + "\nstatus: error\nerror: " + msg)
                        .withState(ToolResultState.ERROR);
            }
            if (ts == TaskStatus.CANCELLED) {
                return ToolResultBlock.text(header + "\nstatus: cancelled\ntask_id: " + taskId)
                        .withState(ToolResultState.INTERRUPTED);
            }
            String result = bgTask.getResult();
            StringBuilder sb = new StringBuilder(header).append("\nstatus: ok");
            if (autoDenied.get()) {
                sb.append("\nnote: remote tool confirmation(s) were auto-denied");
            }
            sb.append("\nreply:\n").append(result != null ? result : "");
            return ToolResultBlock.success(sb.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("agent remote sync interrupted: agentId={}", agentId);
            return ToolResultBlock.text(header + "\nstatus: error\nerror: interrupted")
                    .withState(ToolResultState.INTERRUPTED);
        } finally {
            try {
                streamHandle.close();
            } catch (IOException e) {
                log.debug("Closing remote event stream failed: {}", e.getMessage());
            }
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
     * Whether {@link #CTX_FORCE_SYNC} is enabled on the current call.
     */
    static boolean isForceSync(RuntimeContext ctx) {
        if (ctx == null) {
            return false;
        }
        return Boolean.TRUE.equals(asBoolean(ctx.get(CTX_FORCE_SYNC)));
    }

    /**
     * Resolves the effective sync wait for the current call.
     *
     * <p>Precedence under force-sync (highest first):
     *
     * <ol>
     *   <li>{@link #CTX_FORCE_SYNC_TIMEOUT_SECONDS} when present — absolute app override
     *   <li>LLM {@code timeout_seconds}, with {@code 0} coerced to the default sync timeout
     * </ol>
     *
     * <p>Without force-sync, behavior matches {@link #resolveTimeoutMs} (including async {@code
     * 0}).
     */
    static long resolveEffectiveTimeoutMs(Integer timeoutSeconds, RuntimeContext ctx) {
        boolean forceSync = isForceSync(ctx);
        if (forceSync) {
            Integer override = forceSyncTimeoutSeconds(ctx);
            if (override != null) {
                int seconds = override <= 0 ? DEFAULT_TIMEOUT_SECONDS : override;
                return (long) seconds * 1_000;
            }
        }
        long timeoutMs = resolveTimeoutMs(timeoutSeconds, DEFAULT_TIMEOUT_SECONDS);
        if (forceSync && timeoutMs == 0L) {
            return (long) DEFAULT_TIMEOUT_SECONDS * 1_000;
        }
        return timeoutMs;
    }

    /** Reads {@link #CTX_FORCE_SYNC_TIMEOUT_SECONDS}; {@code null} means "no override". */
    static Integer forceSyncTimeoutSeconds(RuntimeContext ctx) {
        if (ctx == null) {
            return null;
        }
        return asPositiveOrZeroInt(ctx.get(CTX_FORCE_SYNC_TIMEOUT_SECONDS));
    }

    /** Coerces a context value ({@link Number} or numeric string) to Integer; blank/invalid → null. */
    private static Integer asPositiveOrZeroInt(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * Wraps a {@code Mono<ToolResultBlock>} to emit a {@link SubagentExposedEvent} into the parent's event
     * stream when {@code subagentId} is non-null. When subagentId is null (no expose), returns the
     * original Mono unchanged.
     */
    private static Mono<ToolResultBlock> withSubagentExposedEvent(
            Mono<ToolResultBlock> source,
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

    private Mono<ToolResultBlock> execManagedTask(
            ManagedAgent managed,
            RuntimeContext runtimeContext,
            AgentState parentState,
            String header,
            String task,
            Integer timeoutSeconds,
            Optional<SubagentDeclaration> declOpt) {
        return Mono.defer(
                () -> {
                    Lease lease =
                            managed.tryAcquire(
                                    () -> releaseOneShot(managed, runtimeContext, parentState));
                    if (lease == null) {
                        return Mono.just(
                                ToolResultBlock.error(
                                        "Agent is being released: " + managed.spawned.key()));
                    }
                    try {
                        SpawnedAgent spawned = managed.spawned;
                        boolean forceSync = isForceSync(runtimeContext);
                        long timeoutMs = resolveEffectiveTimeoutMs(timeoutSeconds, runtimeContext);
                        String currentUserId =
                                runtimeContext != null ? runtimeContext.getUserId() : null;
                        String parentSessionId =
                                runtimeContext != null ? runtimeContext.getSessionId() : null;
                        DefaultAgentManager manager = managerFor(runtimeContext);
                        boolean remote = declOpt.map(SubagentDeclaration::isRemote).orElse(false);

                        if (!hostOwnsChildState(runtimeContext)) {
                            propagatePlanMode(
                                    parentState,
                                    currentUserId,
                                    spawned.sessionId(),
                                    spawned.agent());
                            propagateParentDenyRules(
                                    parentState,
                                    currentUserId,
                                    spawned.sessionId(),
                                    spawned.agent(),
                                    declOpt);
                        }

                        if (timeoutMs == 0) {
                            String taskId = "task_" + UUID.randomUUID();
                            final String capturedTask = task;
                            TaskRunSpec spec;
                            if (remote) {
                                SubagentDeclaration d = declOpt.get();
                                spec =
                                        new TaskRunSpec.RemoteTaskRunSpec(
                                                d.getUrl(),
                                                d.getHeaders(),
                                                spawned.agentId(),
                                                capturedTask,
                                                buildRemoteSubmitContext(
                                                        runtimeContext, parentState, d));
                            } else {
                                spec =
                                        new TaskRunSpec.LocalTaskRunSpec(
                                                () -> {
                                                    try {
                                                        Msg reply =
                                                                manager.invokeAgent(
                                                                                spawned.agent(),
                                                                                spawned.sessionId(),
                                                                                currentUserId,
                                                                                capturedTask,
                                                                                runtimeContext)
                                                                        .block();
                                                        return textOf(reply);
                                                    } finally {
                                                        lease.close();
                                                    }
                                                });
                            }
                            try {
                                BackgroundTask bgTask =
                                        taskRepository.putTask(
                                                runtimeContext,
                                                taskId,
                                                spawned.agentId(),
                                                parentSessionId,
                                                spec);
                                if (bgTask != null) {
                                    bgTask.whenComplete((ignored, error) -> lease.close());
                                } else if (remote) {
                                    lease.close();
                                }
                            } catch (RuntimeException e) {
                                lease.close();
                                throw e;
                            }
                            return Mono.just(
                                    ToolResultBlock.success(
                                            header
                                                    + "\n"
                                                    + String.format(
                                                            BG_RESULT_TEMPLATE,
                                                            taskId,
                                                            taskId,
                                                            taskId)));
                        }

                        if (remote) {
                            return runRemoteSyncReactive(
                                    runtimeContext,
                                    parentState,
                                    header,
                                    spawned.agentId(),
                                    parentSessionId,
                                    declOpt.get(),
                                    task.trim(),
                                    timeoutMs,
                                    forceSync,
                                    lease);
                        }

                        return execWithTimeoutPromotion(
                                managed,
                                task.trim(),
                                runtimeContext,
                                header,
                                timeoutMs,
                                forceSync,
                                lease);
                    } catch (RuntimeException e) {
                        lease.close();
                        return Mono.error(e);
                    }
                });
    }

    /**
     * Derives a deterministic 12-char hex hash from (userId, parentSessionId, agentId, label). Same inputs
     * always produce the same key, enabling subagent state recovery across parent calls.
     */
    static String deterministicHash(
            String userId, String parentSessionId, String agentId, String label) {
        return SessionIdUtils.deterministicHash(
                identityPart(userId),
                identityPart(parentSessionId),
                identityPart(agentId),
                identityPart(label));
    }

    private static String identityPart(String value) {
        return value == null ? "-1:" : value.length() + ":" + value;
    }

    private static void propagateParentDenyRules(
            AgentState parentState,
            String userId,
            String childSessionId,
            Agent childAgent,
            Optional<SubagentDeclaration> declaration) {
        boolean inherit =
                declaration.map(SubagentDeclaration::isInheritParentPermissions).orElse(true);
        if (!inherit || parentState == null) {
            return;
        }

        PermissionContextState parentPermissions = parentState.getPermissionContext();
        if (parentPermissions == null || parentPermissions.getDenyRules().isEmpty()) {
            return;
        }

        if (childAgent instanceof HarnessAgent harnessAgent) {
            mergeParentDenyRulesIntoSlot(
                    userId, childSessionId, harnessAgent.getDelegate(), parentPermissions);
        } else if (childAgent instanceof ReActAgent reactAgent) {
            mergeParentDenyRulesIntoSlot(userId, childSessionId, reactAgent, parentPermissions);
        }
    }

    private static void mergeParentDenyRulesIntoSlot(
            String userId,
            String childSessionId,
            ReActAgent child,
            PermissionContextState parentPermissions) {
        PermissionContextState childPermissions =
                child.getAgentState(userId, childSessionId).getPermissionContext();
        PermissionContextState merged = mergeParentDenyRules(childPermissions, parentPermissions);
        if (!merged.equals(childPermissions)) {
            child.replacePermissionContext(userId, childSessionId, merged);
        }
    }

    /**
     * Adds parent DENY rules without widening the child's configured permissions.
     *
     * <p>A trivial child uses the legacy lightweight permission path, where a tool-level
     * {@code PASSTHROUGH} is allowed. Adding the first DENY rule makes the context non-trivial and
     * activates the full engine; {@link PermissionMode#BYPASS} preserves that prior fallback while
     * explicit DENY rules still take precedence.
     */
    private static PermissionContextState mergeParentDenyRules(
            PermissionContextState child, PermissionContextState parent) {
        PermissionContextState.Builder merged =
                PermissionContextState.builder()
                        .mode(child.isTrivial() ? PermissionMode.BYPASS : child.getMode());

        child.getWorkingDirectories().forEach(merged::addWorkingDirectory);
        child.getAllowRules()
                .forEach(
                        (toolName, rules) ->
                                rules.forEach(rule -> merged.addAllowRule(toolName, rule)));

        Map<String, List<PermissionRule>> denyRules = new LinkedHashMap<>();
        child.getDenyRules()
                .forEach((toolName, rules) -> denyRules.put(toolName, new ArrayList<>(rules)));
        parent.getDenyRules()
                .forEach(
                        (toolName, rules) -> {
                            List<PermissionRule> targetRules =
                                    denyRules.computeIfAbsent(
                                            toolName, ignored -> new ArrayList<>());
                            for (PermissionRule rule : rules) {
                                if (!targetRules.contains(rule)) {
                                    targetRules.add(rule);
                                }
                            }
                        });
        denyRules.forEach(
                (toolName, rules) -> rules.forEach(rule -> merged.addDenyRule(toolName, rule)));

        child.getAskRules()
                .forEach(
                        (toolName, rules) ->
                                rules.forEach(rule -> merged.addAskRule(toolName, rule)));
        return merged.build();
    }
}
