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
package io.agentscope.core;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.agent.ContextCompressor;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.agent.accumulator.ReasoningContext;
import io.agentscope.core.agent.config.ContextConfig;
import io.agentscope.core.agent.config.ModelConfig;
import io.agentscope.core.agent.config.ReactConfig;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.event.UserConfirmResultEvent;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.interruption.InterruptSource;
import io.agentscope.core.legacy.agent.StructuredOutputCapableAgent;
import io.agentscope.core.legacy.hook.Hook;
import io.agentscope.core.legacy.hook.PendingToolRecoveryHook;
import io.agentscope.core.legacy.hook.PostActingEvent;
import io.agentscope.core.legacy.memory.Memory;
import io.agentscope.core.legacy.memory.StateBackedMemory;
import io.agentscope.core.legacy.skill.repository.AgentSkillRepository;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.Middleware;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.MiddlewareChain;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.StructuredOutputReminder;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContext;
import io.agentscope.core.permission.PermissionEngine;
import io.agentscope.core.session.Session;
import io.agentscope.core.shutdown.AgentShuttingDownException;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import io.agentscope.core.shutdown.PartialReasoningPolicy;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.LegacyStateLoader;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.tool.ToolResultMessageBuilder;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.ExceptionUtils;
import io.agentscope.core.util.MessageUtils;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.BakedContextFilesystem;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.hook.AgentTraceHook;
import io.agentscope.harness.agent.hook.CompactionHook;
import io.agentscope.harness.agent.hook.DynamicSkillHook;
import io.agentscope.harness.agent.hook.DynamicSubagentsHook;
import io.agentscope.harness.agent.hook.MemoryFlushHook;
import io.agentscope.harness.agent.hook.MemoryMaintenanceHook;
import io.agentscope.harness.agent.hook.SandboxLifecycleHook;
import io.agentscope.harness.agent.hook.SubagentsHook;
import io.agentscope.harness.agent.hook.ToolResultEvictionHook;
import io.agentscope.harness.agent.hook.WorkspaceContextHook;
import io.agentscope.harness.agent.memory.MemoryConsolidator;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxDistributedOptions;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import io.agentscope.harness.agent.sandbox.SandboxStateStore;
import io.agentscope.harness.agent.sandbox.SessionSandboxStateStore;
import io.agentscope.harness.agent.session.WorkspaceSession;
import io.agentscope.harness.agent.store.NamespaceFactory;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.tool.FilesystemTool;
import io.agentscope.harness.agent.tool.MemoryGetTool;
import io.agentscope.harness.agent.tool.MemorySearchTool;
import io.agentscope.harness.agent.tool.SessionSearchTool;
import io.agentscope.harness.agent.tool.ShellExecuteTool;
import io.agentscope.harness.agent.tools.McpServerRegistrar;
import io.agentscope.harness.agent.tools.ToolFilter;
import io.agentscope.harness.agent.tools.ToolsConfig;
import io.agentscope.harness.agent.tools.ToolsConfigLoader;
import io.agentscope.harness.agent.workspace.WorkspaceIndex;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * ReAct (Reasoning and Acting) Agent implementation.
 *
 * <p>ReAct is an agent design pattern that combines reasoning (thinking and planning) with acting
 * (tool execution) in an iterative loop. The agent alternates between these two phases until it
 * either completes the task or reaches the maximum iteration limit.
 *
 * <p><b>Key Features:</b>
 * <ul>
 *   <li><b>Reactive Streaming:</b> Uses Project Reactor for non-blocking execution
 *   <li><b>Hook System:</b> Extensible hooks for monitoring and intercepting agent execution
 *   <li><b>HITL Support:</b> Human-in-the-loop via stopAgent() in PostReasoningEvent/PostActingEvent
 *   <li><b>Structured Output:</b> StructuredOutputCapableAgent provides type-safe output generation
 * </ul>
 *
 * <p><b>Usage Example:</b>
 * <pre>{@code
 * // Create a model
 * DashScopeChatModel model = DashScopeChatModel.builder()
 *     .apiKey(System.getenv("DASHSCOPE_API_KEY"))
 *     .modelName("qwen-plus")
 *     .build();
 *
 * // Create a toolkit with tools
 * Toolkit toolkit = new Toolkit();
 * toolkit.registerObject(new MyToolClass());
 *
 * // Build the agent
 * ReActAgent agent = ReActAgent.builder()
 *     .name("Assistant")
 *     .sysPrompt("You are a helpful assistant.")
 *     .model(model)
 *     .toolkit(toolkit)
 *     .memory(new InMemoryMemory())
 *     .maxIters(10)
 *     .build();
 *
 * // Use the agent
 * Msg response = agent.call(Msg.builder()
 *     .name("user")
 *     .role(MsgRole.USER)
 *     .content(TextBlock.builder().text("What's the weather?").build())
 *     .build()).block();
 * }</pre>
 *
 * @see StructuredOutputCapableAgent
 */
@SuppressWarnings("deprecation")
public class ReActAgent extends StructuredOutputCapableAgent implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ReActAgent.class);
    private static final GracefulShutdownManager shutdownManager =
            GracefulShutdownManager.getInstance();

    /**
     * Reactor {@link reactor.util.context.Context} key under which callers register a
     * {@link Sinks.Many} that this agent will read to receive {@link ConfirmResult} responses to
     * {@link RequireUserConfirmEvent}s. The sink must accept at least one {@code ConfirmResult}
     * for every pending tool call before the agent will proceed.
     *
     * <p>When no sink is registered and at least one tool call requires confirmation, the agent
     * auto-denies all pending tool calls.
     */
    public static final String CONFIRM_SINK_KEY = "io.agentscope.core.ReActAgent.confirmSink";

    // ==================== Core Dependencies ====================

    /**
     * Read-only adapter exposing {@link #state}'s context as a legacy {@link Memory} so legacy
     * hooks (which call {@link #getMemory()}) keep working. The core flow does not use this; it
     * mutates {@link AgentState#contextMutable()} directly.
     *
     * @deprecated since 2.0.0. Conversation context is now held on
     *     {@link io.agentscope.core.state.AgentState#getContext()}.
     */
    @Deprecated(since = "2.0.0")
    private final Memory memory;

    private final String sysPrompt;
    private final Model model;
    private final int maxIters;
    private final ExecutionConfig modelExecutionConfig;
    private final ExecutionConfig toolExecutionConfig;
    private final GenerateOptions generateOptions;

    private final ToolExecutionContext toolExecutionContext;

    private final List<Middleware> middlewares;
    private RuntimeContext pendingRuntimeContext;

    // ==================== Persistence ====================

    private final Session session;
    private final SessionKey sessionKey;

    // ==================== 2.0 Core Fields ====================

    private final AgentState state;
    private final ModelConfig modelConfig;
    private final ContextConfig contextConfig;
    private final ReactConfig reactConfig;
    private final PermissionEngine permissionEngine;
    private final ContextCompressor compressor;

    /**
     * Per-call system message, propagated across PreCallEvent → PreReasoningEvent /
     * PreSummaryEvent. It is safe to use an {@link java.util.concurrent.atomic.AtomicReference}
     * here because {@code AgentBase.acquireExecution()} guarantees that only one {@code call()}
     * runs concurrently per agent instance, so this reference is effectively owned by a single
     * logical execution at any time.
     */
    private final java.util.concurrent.atomic.AtomicReference<Msg> currentSystemMsg =
            new java.util.concurrent.atomic.AtomicReference<>();

    private final AtomicReference<FluxSink<AgentEvent>> activeEventSink = new AtomicReference<>();

    @SuppressWarnings("deprecation")
    private final LegacyHookDispatcher hookDispatcher;

    // ==================== Harness fields (set by Builder.build() during orchestration)
    // ====================

    /**
     * Workspace manager bound to this agent's filesystem when the harness orchestration path runs
     * (i.e. when any of {@code workspace(...)}, {@code filesystem(...)}, {@code abstractFilesystem(...)},
     * {@code compaction(...)} or related setters were used). {@code null} for plain
     * {@code ReActAgent.builder()} usage.
     */
    private WorkspaceManager workspaceManager;

    private CompactionHook compactionHook;
    private SandboxContext defaultSandboxContext;
    private List<AgentSkillRepository> skillRepositories = List.of();

    /**
     * SQLite-backed workspace index allocated during {@link Builder#build()} when the agent is
     * configured with a {@link RemoteFilesystemSpec}. Owned by this agent; released by
     * {@link #close()}. {@code null} when no index was created.
     */
    private WorkspaceIndex ownedWorkspaceIndex;

    /** Factory for ctx-bound {@link WorkspaceManager} views — see {@link #workspaceFor(String, String)}. */
    private BiFunction<String, String, WorkspaceManager> workspaceFactory;

    /** Factory for per-userId {@link Session} views; {@code null} if not applicable. */
    private Function<String, Session> sessionFactory;

    // ==================== Constructor ====================

    private ReActAgent(Builder builder, Toolkit agentToolkit) {
        super(
                builder.name,
                builder.description,
                builder.checkRunning,
                new ArrayList<>(builder.hooks),
                agentToolkit,
                builder.structuredOutputReminder);

        this.sysPrompt = builder.sysPrompt;
        this.model = builder.model;
        this.maxIters = builder.maxIters;
        this.modelExecutionConfig = builder.modelExecutionConfig;
        this.toolExecutionConfig = builder.toolExecutionConfig;
        this.generateOptions = builder.generateOptions;
        this.toolExecutionContext = builder.toolExecutionContext;
        this.middlewares = List.copyOf(builder.middlewares);

        this.session = builder.session;
        this.sessionKey =
                builder.sessionKey != null
                        ? builder.sessionKey
                        : SimpleSessionKey.of(builder.name != null ? builder.name : "ReActAgent");
        this.state = loadOrCreateAgentState(this.session, this.sessionKey, builder, getAgentId());

        // Restore toolkit activeGroups from persisted state
        if (agentToolkit != null && !this.state.getToolContext().getActivatedGroups().isEmpty()) {
            agentToolkit.setActiveGroups(this.state.getToolContext().getActivatedGroups());
        }
        this.memory = new StateBackedMemory(this.state);
        this.modelConfig =
                builder.modelConfig != null ? builder.modelConfig : ModelConfig.defaults();
        this.contextConfig =
                builder.contextConfig != null ? builder.contextConfig : ContextConfig.defaults();
        this.reactConfig =
                builder.reactConfig != null
                        ? builder.reactConfig
                        : new ReactConfig(builder.maxIters, false);
        this.permissionEngine = new PermissionEngine(this.state.getPermissionContext());
        this.compressor =
                builder.model != null
                        ? new ContextCompressor(this.contextConfig, builder.model)
                        : null;
        this.hookDispatcher = new LegacyHookDispatcher(this);

        // Wire automatic state save on shutdown / interrupt.
        if (this.session != null) {
            shutdownManager.bindStateSaver(
                    this, agentState -> session.save(sessionKey, "agent_state", agentState));
        }
    }

    /**
     * Initial agent-state load. Tries (in order): the configured Session for an {@code agent_state}
     * entry, the v1 legacy session keys ({@code memory_messages} + {@code toolkit_activeGroups})
     * via {@link LegacyStateLoader}, and finally a fresh state if neither yields anything.
     */
    private static AgentState loadOrCreateAgentState(
            Session session, SessionKey sessionKey, Builder builder, String agentId) {
        AgentState fresh = freshState(builder, agentId);
        if (session == null) {
            return fresh;
        }
        try {
            return session.get(sessionKey, "agent_state", AgentState.class)
                    .orElseGet(
                            () -> {
                                AgentState legacy =
                                        LegacyStateLoader.loadFromLegacySession(
                                                session, sessionKey);
                                if (legacy != null
                                        && (!legacy.getContext().isEmpty()
                                                || !legacy.getToolContext()
                                                        .getActivatedGroups()
                                                        .isEmpty())) {
                                    return legacy;
                                }
                                return fresh;
                            });
        } catch (Exception e) {
            log.warn("Failed to load AgentState from session {}: {}", sessionKey, e.getMessage());
            return fresh;
        }
    }

    private static AgentState freshState(Builder builder, String agentId) {
        AgentState.Builder asb = AgentState.builder().sessionId(agentId);
        if (builder.permissionContext != null) {
            asb.permissionContext(builder.permissionContext);
        }
        return asb.build();
    }

    /**
     * Persist the current {@link AgentState} via the configured {@link Session}, or {@code
     * Mono.empty()} when no Session was provided. Synchronises toolkit activeGroups into the state
     * before writing.
     */
    private Mono<Void> saveStateToSession() {
        if (session == null) {
            return Mono.empty();
        }
        syncToolkitToState();
        return Mono.fromRunnable(() -> session.save(sessionKey, "agent_state", state));
    }

    // ==================== RuntimeContext ====================

    @Override
    protected void beforeAgentExecution(List<Msg> msgs) {
        RuntimeContext ctx = this.pendingRuntimeContext;
        this.pendingRuntimeContext = null;
        if (ctx == null) {
            ctx = RuntimeContext.empty();
        }
        bindRuntimeContextToHooks(ctx);
        // Reset per-call system message; will be initialised by consumeSystemMsgAfterPreCall
        currentSystemMsg.set(null);
    }

    @Override
    protected Msg seedSystemMsg() {
        if (sysPrompt == null || sysPrompt.trim().isEmpty()) {
            return null;
        }
        String prompt = applySystemPromptMiddlewares(sysPrompt);
        return Msg.builder()
                .name("system")
                .role(MsgRole.SYSTEM)
                .content(TextBlock.builder().text(prompt).build())
                .build();
    }

    private String applySystemPromptMiddlewares(String prompt) {
        if (middlewares.isEmpty()) {
            return prompt;
        }
        Mono<String> result = Mono.just(prompt);
        for (Middleware mw : middlewares) {
            result = result.flatMap(p -> mw.onSystemPrompt(this, p));
        }
        return result.block();
    }

    @Override
    protected void consumeSystemMsgAfterPreCall(Msg systemMsg) {
        currentSystemMsg.set(systemMsg);
    }

    @Override
    protected void afterAgentExecution() {
        unbindRuntimeContextFromHooks();
    }

    private ToolExecutionContext buildMergedToolContext() {
        RuntimeContext run = getRuntimeContext();
        if (run == null) {
            return toolExecutionContext != null
                    ? toolExecutionContext
                    : ToolExecutionContext.empty();
        }
        return ToolExecutionContext.merge(run.asToolExecutionContext(), toolExecutionContext);
    }

    /**
     * Calls the agent with a per-call {@link RuntimeContext} (metadata for hooks and tools, not
     * persisted).
     *
     * <p>When the agent was built via the harness orchestration path (a workspace, filesystem, or
     * compaction config was supplied), this overload also (a) fills missing {@link Session}/
     * {@link SessionKey} defaults, (b) loads any persisted state, and (c) wraps the call with a
     * context-overflow recovery flow that triggers an emergency compaction via the registered
     * {@link CompactionHook}. Plain {@code ReActAgent.builder()} usage skips all of that and just
     * binds the runtime context.
     */
    public Mono<Msg> call(List<Msg> msgs, RuntimeContext context) {
        if (workspaceManager == null && compactionHook == null) {
            // Not orchestrated: original lightweight behavior.
            this.pendingRuntimeContext = context;
            return call(msgs);
        }
        final RuntimeContext effective =
                ensureSessionDefaults(context != null ? context : RuntimeContext.empty());
        this.pendingRuntimeContext = effective;
        Mono<Msg> result = call(msgs);
        if (compactionHook != null) {
            final List<Msg> capturedMsgs = msgs;
            result =
                    result.onErrorResume(
                            e -> {
                                if (isContextOverflowError(e)) {
                                    return recoverFromOverflow(capturedMsgs, effective);
                                }
                                return Mono.error(e);
                            });
        }
        return result;
    }

    public Mono<Msg> call(List<Msg> msgs, Class<?> structuredOutputClass, RuntimeContext context) {
        this.pendingRuntimeContext = context;
        return call(msgs, structuredOutputClass);
    }

    public Mono<Msg> call(List<Msg> msgs, JsonNode outputSchema, RuntimeContext context) {
        this.pendingRuntimeContext = context;
        return call(msgs, outputSchema);
    }

    public Flux<Event> stream(List<Msg> msgs, StreamOptions options, RuntimeContext context) {
        this.pendingRuntimeContext = context;
        return stream(msgs, options);
    }

    public Flux<Event> stream(
            List<Msg> msgs,
            StreamOptions options,
            Class<?> structuredModel,
            RuntimeContext context) {
        this.pendingRuntimeContext = context;
        return stream(msgs, options, structuredModel);
    }

    public Flux<Event> stream(
            List<Msg> msgs, StreamOptions options, JsonNode schema, RuntimeContext context) {
        this.pendingRuntimeContext = context;
        return stream(msgs, options, schema);
    }

    /**
     * Stream fine-grained {@link AgentEvent}s from the full agent lifecycle.
     *
     * <p>This method goes through the same lifecycle as {@code call()} (acquire execution,
     * hooks, pre/post call notification) but exposes the internal event stream. The lifecycle
     * is driven by {@code call()} internally; events are captured via the shared
     * {@code activeEventSink}.
     *
     * @param msgs input messages
     * @return event stream covering the full agent invocation lifecycle
     */
    public Flux<AgentEvent> streamEvents(List<Msg> msgs) {
        String replyId = UUID.randomUUID().toString().replace("-", "");
        Function<AgentInput, Flux<AgentEvent>> core =
                input ->
                        Flux.<AgentEvent>create(
                                        sink -> {
                                            activeEventSink.set(sink);
                                            sink.next(
                                                    new AgentStartEvent(null, replyId, getName()));
                                            reactor.util.context.Context subscriberCtx =
                                                    reactor.util.context.Context.of(
                                                            sink.contextView());
                                            call(input.msgs())
                                                    .doFinally(
                                                            signal -> {
                                                                sink.next(
                                                                        new AgentEndEvent(replyId));
                                                                activeEventSink.set(null);
                                                                sink.complete();
                                                            })
                                                    .contextWrite(subscriberCtx)
                                                    .subscribe(finalMsg -> {}, sink::error);
                                        },
                                        FluxSink.OverflowStrategy.BUFFER)
                                .doOnError(e -> activeEventSink.set(null));
        return MiddlewareChain.build(middlewares, this, MiddlewareBase::onAgent, core)
                .apply(new AgentInput(msgs == null ? List.of() : msgs));
    }

    /**
     * Stream fine-grained {@link AgentEvent}s for a single input message.
     *
     * @param msg input message
     * @return event stream covering the full agent invocation lifecycle
     */
    public Flux<AgentEvent> streamEvents(Msg msg) {
        return streamEvents(List.of(msg));
    }

    // ==================== Protected API ====================

    @Override
    protected Mono<Msg> doCall(List<Msg> msgs) {
        return doCallInner(msgs).flatMap(result -> saveStateToSession().thenReturn(result));
    }

    private Mono<Msg> doCallInner(List<Msg> msgs) {
        Set<String> pendingIds = getPendingToolUseIds();

        // No pending tools -> normal processing
        if (pendingIds.isEmpty()) {
            addToContext(msgs);
            return coreAgent();
        }

        // Has pending tools but no input -> resume (execute pending tools directly)
        if (msgs == null || msgs.isEmpty()) {
            return resumeAgent();
        }

        // Has pending tools + input -> check if user provided tool results
        List<ToolResultBlock> providedResults =
                msgs.stream()
                        .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                        .toList();

        if (!providedResults.isEmpty()) {
            // User provided tool results -> validate and add
            validateAndAddToolResults(msgs, pendingIds);
            return hasPendingToolUse() ? resumeAgent() : coreAgent();
        }

        // If PendingToolRecoveryHook is enabled, pending state should have been
        // patched during PreCallEvent. If we still reach here, the hook was disabled
        // and the user did not provide tool results — this is an unrecoverable state.
        throw new IllegalStateException(
                "Pending tool calls exist without results. "
                        + "Enable PendingToolRecoveryHook or provide tool results. "
                        + "Pending IDs: "
                        + pendingIds);
    }

    /**
     * Execute the full agent invocation as a {@link Flux} of fine-grained {@link AgentEvent}s.
     *
     * <p>This method wraps the existing {@code doCall()} logic and captures all events emitted
     * by the internal stream methods ({@code reasoningStream}, {@code actingStream},
     * {@code summaryStream}). The stream is bookended by {@link AgentStartEvent} and
     * {@link AgentEndEvent}.
     *
     * @param msgs the input messages
     * @return event stream covering the full agent invocation lifecycle
     */
    Flux<AgentEvent> agentImpl(List<Msg> msgs) {
        String replyId = UUID.randomUUID().toString().replace("-", "");

        Function<AgentInput, Flux<AgentEvent>> core =
                input ->
                        Flux.<AgentEvent>create(
                                        sink -> {
                                            activeEventSink.set(sink);
                                            sink.next(
                                                    new AgentStartEvent(null, replyId, getName()));

                                            doCall(input.msgs())
                                                    .doFinally(
                                                            signal -> {
                                                                sink.next(
                                                                        new AgentEndEvent(replyId));
                                                                activeEventSink.set(null);
                                                                sink.complete();
                                                            })
                                                    .subscribe(finalMsg -> {}, sink::error);
                                        },
                                        FluxSink.OverflowStrategy.BUFFER)
                                .doOnError(e -> activeEventSink.set(null));

        return MiddlewareChain.build(middlewares, this, MiddlewareBase::onAgent, core)
                .apply(new AgentInput(msgs));
    }

    private void publishEvent(AgentEvent event) {
        FluxSink<AgentEvent> sink = activeEventSink.get();
        if (sink != null) {
            sink.next(event);
        }
    }

    /**
     * Build a {@link ToolResultBlock} representing a tool execution error.
     *
     * @param toolId the id of the tool call that failed
     * @param errorMessage the human-readable error description
     * @return a {@link ToolResultBlock} containing the formatted error message
     */
    private static ToolResultBlock buildErrorToolResult(String toolId, String errorMessage) {
        return ToolResultBlock.builder()
                .id(toolId)
                .output(List.of(TextBlock.builder().text("[ERROR] " + errorMessage).build()))
                .build();
    }

    /**
     * Find the last assistant message in context.
     *
     * @return The last assistant message, or null if not found
     */
    private Msg findLastAssistantMsg() {
        List<Msg> contextMsgs = state.contextMutable();
        for (int i = contextMsgs.size() - 1; i >= 0; i--) {
            Msg msg = contextMsgs.get(i);
            if (msg.getRole() == MsgRole.ASSISTANT) {
                return msg;
            }
        }
        return null;
    }

    /**
     * Check if there are pending tool calls without corresponding results.
     *
     * @return true if there are pending tool calls
     */
    private boolean hasPendingToolUse() {
        return !getPendingToolUseIds().isEmpty();
    }

    /**
     * Get the set of pending tool use IDs from the last assistant message.
     *
     * @return Set of tool use IDs that have no corresponding results in memory
     */
    private Set<String> getPendingToolUseIds() {
        Msg lastAssistant = findLastAssistantMsg();
        if (lastAssistant == null || !lastAssistant.hasContentBlocks(ToolUseBlock.class)) {
            return Set.of();
        }

        Set<String> existingResultIds =
                state.contextMutable().stream()
                        .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                        .map(ToolResultBlock::getId)
                        .collect(Collectors.toSet());

        return lastAssistant.getContentBlocks(ToolUseBlock.class).stream()
                .map(ToolUseBlock::getId)
                .filter(id -> !existingResultIds.contains(id))
                .collect(Collectors.toSet());
    }

    /**
     * Validate input messages when there are pending tool calls, then add to context.
     *
     * <p>Validation rules:
     * <ul>
     *   <li>Empty input: no-op (will proceed to acting)</li>
     *   <li>No tool results: throw error</li>
     *   <li>Has tool results: validate IDs match pending, no duplicates</li>
     *   <li>Partial results + text content: throw error (text only allowed when all tools
     *       completed)</li>
     * </ul>
     *
     * @param msgs The input messages to validate
     * @param pendingIds The set of pending tool use IDs
     * @throws IllegalStateException if validation fails
     */
    private void validateAndAddToolResults(List<Msg> msgs, Set<String> pendingIds) {
        if (msgs == null || msgs.isEmpty()) {
            return;
        }

        List<ToolResultBlock> results =
                msgs.stream()
                        .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                        .toList();

        if (results.isEmpty()) {
            throw new IllegalStateException(
                    "Cannot add messages without tool results when pending tool calls exist. "
                            + "Pending IDs: "
                            + pendingIds);
        }

        // Check for duplicate IDs
        Set<String> providedIds = new HashSet<>();
        for (ToolResultBlock r : results) {
            if (!providedIds.add(r.getId())) {
                throw new IllegalStateException("Duplicate tool result ID: " + r.getId());
            }
        }

        // Check all provided IDs match pending IDs
        Set<String> invalidIds =
                providedIds.stream()
                        .filter(id -> !pendingIds.contains(id))
                        .collect(Collectors.toSet());
        if (!invalidIds.isEmpty()) {
            throw new IllegalStateException(
                    "Invalid tool result IDs: " + invalidIds + ". Expected: " + pendingIds);
        }

        // Check for non-ToolResultBlock content
        boolean hasTextContent =
                msgs.stream()
                        .flatMap(m -> m.getContent().stream())
                        .anyMatch(block -> !(block instanceof ToolResultBlock));

        // If only partial results provided, text content is not allowed
        boolean isPartialResults = !providedIds.containsAll(pendingIds);
        if (isPartialResults && hasTextContent) {
            throw new IllegalStateException(
                    "Cannot include text content when providing partial tool results. "
                            + "Provided: "
                            + providedIds
                            + ", Pending: "
                            + pendingIds);
        }

        state.contextMutable().addAll(msgs);
    }

    /**
     * Add messages to the agent state context if not null.
     *
     * @param msgs The messages to add
     */
    private void addToContext(List<Msg> msgs) {
        if (msgs != null) {
            state.contextMutable().addAll(msgs);
        }
    }

    // ==================== Core ReAct Loop ====================

    /**
     * Entry point for a fresh agent invocation: kicks off the ReAct loop at iteration 0.
     */
    private Mono<Msg> coreAgent() {
        return executeIteration(0);
    }

    /**
     * Resume entry point when pending tool calls remain from a previous turn:
     * jumps directly into the acting phase without another reasoning step.
     */
    private Mono<Msg> resumeAgent() {
        return acting(0);
    }

    private Mono<Msg> executeIteration(int iter) {
        return reasoning(iter, false);
    }

    /**
     * Execute the reasoning phase.
     *
     * <p>This method streams from the model, accumulates chunks, notifies hooks, and
     * decides whether to continue to acting or return early (HITL stop, gotoReasoning, or finished).
     *
     * @param iter Current iteration number
     * @param ignoreMaxIters If true, skip maxIters check (for gotoReasoning)
     * @return Mono containing the final result message
     */
    private Mono<Msg> reasoning(int iter, boolean ignoreMaxIters) {
        // Check maxIters unless ignoreMaxIters is set
        if (!ignoreMaxIters && iter >= maxIters) {
            return summarizing();
        }

        ReasoningContext context = new ReasoningContext(getName());

        return checkInterruptedAsync()
                .then(compressor != null ? compressor.maybeCompress(state) : Mono.empty())
                .then(
                        hookDispatcher.firePreReasoning(
                                state.contextMutable(),
                                currentSystemMsg.get(),
                                model.getModelName()))
                .flatMap(
                        event -> {
                            GenerateOptions options =
                                    event.getEffectiveGenerateOptions() != null
                                            ? event.getEffectiveGenerateOptions()
                                            : buildGenerateOptions();
                            List<Msg> modelInput =
                                    prependSystemMsg(
                                            event.getInputMessages(), event.getSystemMessage());
                            List<ToolSchema> tools = toolkit.getToolSchemas();
                            Function<ReasoningInput, Flux<AgentEvent>> reasoningCore =
                                    ri ->
                                            reasoningStream(
                                                    context,
                                                    ri.messages(),
                                                    ri.tools(),
                                                    ri.options());
                            Flux<AgentEvent> stream =
                                    MiddlewareChain.build(
                                                    middlewares,
                                                    ReActAgent.this,
                                                    MiddlewareBase::onReasoning,
                                                    reasoningCore)
                                            .apply(new ReasoningInput(modelInput, tools, options));
                            return stream.then(
                                    Mono.defer(
                                            () -> Mono.justOrEmpty(context.buildFinalMessage())));
                        })
                .onErrorResume(
                        InterruptedException.class,
                        error -> {
                            Msg msg = context.buildFinalMessage();
                            if (msg != null) {
                                boolean discard =
                                        getInterruptSource() == InterruptSource.SYSTEM
                                                && shutdownManager
                                                                .getConfig()
                                                                .partialReasoningPolicy()
                                                        == PartialReasoningPolicy.DISCARD;
                                if (!discard) {
                                    state.contextMutable().add(msg);
                                }
                            }
                            return Mono.error(error);
                        })
                .flatMap(msg -> hookDispatcher.firePostReasoning(msg, model.getModelName()))
                .flatMap(
                        event -> {
                            Msg msg = event.getReasoningMessage();
                            if (msg != null) {
                                state.contextMutable().add(msg);
                            }

                            // HITL stop
                            if (event.isStopRequested()) {
                                return Mono.just(
                                        msg.withGenerateReason(
                                                GenerateReason.REASONING_STOP_REQUESTED));
                            }

                            // gotoReasoning requested (e.g., by StructuredOutputHook)
                            if (event.isGotoReasoningRequested()) {
                                List<Msg> gotoMsgs = event.getGotoReasoningMsgs();
                                if (gotoMsgs != null) {
                                    state.contextMutable().addAll(gotoMsgs);
                                }
                                return reasoning(iter + 1, true);
                            }

                            // Check finish conditions
                            if (isFinished(msg)) {
                                return Mono.just(msg);
                            }

                            // Continue to acting
                            return checkInterruptedAsync().then(acting(iter));
                        })
                .switchIfEmpty(
                        Mono.defer(
                                () -> {
                                    // No message was produced
                                    return Mono.justOrEmpty((Msg) null);
                                }));
    }

    /**
     * Stream fine-grained {@link AgentEvent}s from a model call during reasoning.
     *
     * <p>Emits: {@link ModelCallStartEvent} → block start/delta/end events → {@link
     * ModelCallEndEvent}. The provided {@link ReasoningContext} is used to accumulate chunks
     * (for building the final {@link Msg}) and to notify legacy {@link Hook}s.
     *
     * @param context   reasoning context for chunk accumulation
     * @param messages  the messages to send to the model
     * @param tools     the tool schemas available
     * @param options   generation options
     * @return event stream from a single model call
     */
    Flux<AgentEvent> reasoningStream(
            ReasoningContext context,
            List<Msg> messages,
            List<ToolSchema> tools,
            GenerateOptions options) {

        Function<ModelCallInput, Flux<AgentEvent>> modelCallCore =
                mci -> modelCallStream(context, mci, true);

        return MiddlewareChain.build(middlewares, this, MiddlewareBase::onModelCall, modelCallCore)
                .apply(new ModelCallInput(messages, tools, options, model))
                .doOnNext(this::publishEvent);
    }

    private Flux<AgentEvent> modelCallStream(
            ReasoningContext context, ModelCallInput mci, boolean withToolEvents) {

        String replyId = UUID.randomUUID().toString().replace("-", "");
        AtomicBoolean textStarted = new AtomicBoolean(false);
        AtomicBoolean thinkingStarted = new AtomicBoolean(false);
        Set<String> startedToolCalls = ConcurrentHashMap.newKeySet();

        Flux<AgentEvent> modelEvents =
                mci.model().stream(mci.messages(), mci.tools(), mci.options())
                        .concatMap(chunk -> checkInterruptedAsync().thenReturn(chunk))
                        .concatMap(
                                chunk -> {
                                    List<Msg> chunkMsgs = context.processChunk(chunk);
                                    for (Msg msg : chunkMsgs) {
                                        hookDispatcher
                                                .fireReasoningChunk(
                                                        msg, context, mci.model().getModelName())
                                                .subscribe();
                                    }

                                    List<AgentEvent> events = new ArrayList<>();
                                    for (ContentBlock block : chunk.getContent()) {
                                        emitBlockEvents(
                                                block,
                                                replyId,
                                                context,
                                                textStarted,
                                                thinkingStarted,
                                                withToolEvents
                                                        ? startedToolCalls
                                                        : ConcurrentHashMap.newKeySet(),
                                                events);
                                    }
                                    return Flux.fromIterable(events);
                                });

        Flux<AgentEvent> endEvents =
                Flux.defer(
                        () -> {
                            List<AgentEvent> events = new ArrayList<>();
                            if (textStarted.get()) {
                                events.add(new TextBlockEndEvent(replyId, "text"));
                            }
                            if (thinkingStarted.get()) {
                                events.add(new ThinkingBlockEndEvent(replyId, "thinking"));
                            }
                            for (String toolId : startedToolCalls) {
                                events.add(new ToolCallEndEvent(replyId, toolId));
                            }
                            events.add(new ModelCallEndEvent(replyId, context.getChatUsage()));
                            return Flux.fromIterable(events);
                        });

        return Flux.concat(Flux.just(new ModelCallStartEvent(replyId)), modelEvents, endEvents);
    }

    private void emitBlockEvents(
            ContentBlock block,
            String replyId,
            ReasoningContext context,
            AtomicBoolean textStarted,
            AtomicBoolean thinkingStarted,
            Set<String> startedToolCalls,
            List<AgentEvent> events) {

        if (block instanceof TextBlock tb) {
            if (textStarted.compareAndSet(false, true)) {
                events.add(new TextBlockStartEvent(replyId, "text"));
            }
            if (tb.getText() != null && !tb.getText().isEmpty()) {
                events.add(new TextBlockDeltaEvent(replyId, "text", tb.getText()));
            }
        } else if (block instanceof ThinkingBlock tb) {
            if (thinkingStarted.compareAndSet(false, true)) {
                events.add(new ThinkingBlockStartEvent(replyId, "thinking"));
            }
            if (tb.getThinking() != null && !tb.getThinking().isEmpty()) {
                events.add(new ThinkingBlockDeltaEvent(replyId, "thinking", tb.getThinking()));
            }
        } else if (block instanceof ToolUseBlock tub) {
            String toolId = resolveToolCallId(tub, context);
            if (toolId != null && startedToolCalls.add(toolId)) {
                String toolName = tub.getName();
                if (toolName != null && !toolName.startsWith("__")) {
                    events.add(new ToolCallStartEvent(replyId, toolId, toolName));
                }
            }
            if (tub.getContent() != null && !tub.getContent().isEmpty()) {
                events.add(
                        new ToolCallDeltaEvent(
                                replyId, toolId != null ? toolId : "", tub.getContent()));
            }
        }
    }

    private String resolveToolCallId(ToolUseBlock tub, ReasoningContext context) {
        if (tub.getId() != null && !tub.getId().isEmpty()) {
            return tub.getId();
        }
        ToolUseBlock accumulated = context.getAccumulatedToolCall(null);
        return accumulated != null ? accumulated.getId() : null;
    }

    /**
     * Execute the acting phase.
     *
     * <p>This method executes only pending tools (those without results in context),
     * notifies hooks for successful tool results, and decides whether to continue iteration
     * or return (HITL stop, suspended tools, or structured output).
     *
     * <p>For tools that throw {@link io.agentscope.core.tool.ToolSuspendException}:
     * <ul>
     *   <li>The exception is caught by Toolkit and converted to a pending ToolResultBlock</li>
     *   <li>Successful results are stored in context, pending results are not</li>
     *   <li>Returns Msg with {@link GenerateReason#TOOL_SUSPENDED} containing suspended ToolUseBlocks</li>
     * </ul>
     *
     * @param iter Current iteration number
     * @return Mono containing the final result message
     */
    private Mono<Msg> acting(int iter) {
        List<ToolUseBlock> pendingToolCalls = extractPendingToolCalls();

        if (pendingToolCalls.isEmpty()) {
            return executeIteration(iter + 1);
        }

        String replyId = UUID.randomUUID().toString().replace("-", "");
        AtomicReference<List<Map.Entry<ToolUseBlock, ToolResultBlock>>> resultHolder =
                new AtomicReference<>();

        return hookDispatcher
                .firePreActing(pendingToolCalls, toolkit)
                .flatMap(
                        toolCalls -> {
                            Function<ActingInput, Flux<AgentEvent>> actingCore =
                                    ai -> actingStream(ai.toolCalls(), replyId, resultHolder);
                            Flux<AgentEvent> stream =
                                    MiddlewareChain.build(
                                                    middlewares,
                                                    this,
                                                    MiddlewareBase::onActing,
                                                    actingCore)
                                            .apply(new ActingInput(toolCalls));
                            return stream.then(Mono.defer(() -> Mono.just(resultHolder.get())));
                        })
                .flatMap(
                        results -> {
                            List<Map.Entry<ToolUseBlock, ToolResultBlock>> successPairs =
                                    results.stream()
                                            .filter(e -> !e.getValue().isSuspended())
                                            .toList();
                            List<Map.Entry<ToolUseBlock, ToolResultBlock>> pendingPairs =
                                    results.stream()
                                            .filter(e -> e.getValue().isSuspended())
                                            .toList();

                            if (successPairs.isEmpty()) {
                                if (!pendingPairs.isEmpty()) {
                                    return Mono.just(buildSuspendedMsg(pendingPairs));
                                }
                                return executeIteration(iter + 1);
                            }

                            return Flux.fromIterable(successPairs)
                                    .concatMap(this::notifyPostActingHook)
                                    .last()
                                    .flatMap(
                                            event -> {
                                                if (event.isStopRequested()) {
                                                    return Mono.just(
                                                            event.getToolResultMsg()
                                                                    .withGenerateReason(
                                                                            GenerateReason
                                                                                    .ACTING_STOP_REQUESTED));
                                                }

                                                if (!pendingPairs.isEmpty()) {
                                                    return Mono.just(
                                                            buildSuspendedMsg(pendingPairs));
                                                }

                                                return executeIteration(iter + 1);
                                            });
                        });
    }

    /**
     * Stream fine-grained {@link AgentEvent}s from tool execution during the acting phase.
     *
     * <p>Emits: {@link ToolResultStartEvent} → delta events → {@link ToolResultEndEvent}
     * for each tool call. The provided {@code resultHolder} is populated with the execution
     * results so the caller can process them afterward.
     *
     * @param toolCalls    the tool calls to execute
     * @param replyId      the reply identifier for event correlation
     * @param resultHolder populated with tool execution results on completion
     * @return event stream from tool execution
     */
    Flux<AgentEvent> actingStream(
            List<ToolUseBlock> toolCalls,
            String replyId,
            AtomicReference<List<Map.Entry<ToolUseBlock, ToolResultBlock>>> resultHolder) {

        return evaluatePermissions(toolCalls)
                .flatMapMany(
                        gate -> {
                            List<ToolUseBlock> pending = gate.pendingAsk();
                            Set<String> autoDenied = gate.autoDeniedIds();
                            if (pending.isEmpty()) {
                                return runToolBatch(toolCalls, autoDenied, replyId, resultHolder);
                            }
                            return Flux.<AgentEvent>just(
                                            new RequireUserConfirmEvent(replyId, pending))
                                    .concatWith(
                                            awaitConfirmation(pending)
                                                    .flatMapMany(
                                                            confirmResults -> {
                                                                Set<String> deniedIds =
                                                                        new HashSet<>(autoDenied);
                                                                deniedIds.addAll(
                                                                        collectDeniedIds(
                                                                                confirmResults));
                                                                return Flux.<AgentEvent>just(
                                                                                new UserConfirmResultEvent(
                                                                                        replyId,
                                                                                        confirmResults))
                                                                        .concatWith(
                                                                                runToolBatch(
                                                                                        toolCalls,
                                                                                        deniedIds,
                                                                                        replyId,
                                                                                        resultHolder));
                                                            }));
                        })
                .doOnNext(this::publishEvent);
    }

    /**
     * Execute the given tool calls, synthesising DENIED results for any tool whose id is in
     * {@code deniedIds} (skipping toolkit invocation for those) and running the rest through
     * {@link #executeToolCalls(List)}. The combined results are written to {@code resultHolder}
     * and emitted as a stream of fine-grained {@link AgentEvent}s.
     */
    private Flux<AgentEvent> runToolBatch(
            List<ToolUseBlock> toolCalls,
            Set<String> deniedIds,
            String replyId,
            AtomicReference<List<Map.Entry<ToolUseBlock, ToolResultBlock>>> resultHolder) {

        List<Map.Entry<ToolUseBlock, ToolResultBlock>> deniedEntries = new ArrayList<>();
        List<ToolUseBlock> approved = new ArrayList<>();
        for (ToolUseBlock tc : toolCalls) {
            if (deniedIds.contains(tc.getId())) {
                ToolResultBlock denied =
                        ToolResultBlock.text("Permission denied by user")
                                .withIdAndName(tc.getId(), tc.getName())
                                .withState(ToolResultState.DENIED);
                deniedEntries.add(Map.entry(tc, denied));
            } else {
                approved.add(tc);
            }
        }

        Flux<AgentEvent> deniedEvents =
                Flux.fromIterable(deniedEntries)
                        .concatMap(
                                entry -> {
                                    ToolUseBlock use = entry.getKey();
                                    return Flux.<AgentEvent>just(
                                            new ToolResultStartEvent(
                                                    replyId, use.getId(), use.getName()),
                                            new ToolResultTextDeltaEvent(
                                                    replyId,
                                                    use.getId(),
                                                    "Permission denied by user"),
                                            new ToolResultEndEvent(
                                                    replyId, use.getId(), ToolResultState.DENIED));
                                });

        if (approved.isEmpty()) {
            resultHolder.set(deniedEntries);
            return deniedEvents;
        }

        // Capture the parent Reactor Context (set by AgentBase.createEventStream, which puts the
        // SubagentEventBus there) so we can forward it into the inner executeToolCalls subscribe.
        // Without this, the bare .subscribe() below detaches from the upstream chain and tools
        // like AgentSpawnTool see an empty ContextView, breaking child-event forwarding.
        Flux<AgentEvent> approvedEvents =
                Flux.<AgentEvent>deferContextual(
                        parentCtx ->
                                Flux.<AgentEvent>create(
                                        sink -> {
                                            for (ToolUseBlock tool : approved) {
                                                sink.next(
                                                        new ToolResultStartEvent(
                                                                replyId,
                                                                tool.getId(),
                                                                tool.getName()));
                                            }

                                            toolkit.setInternalChunkCallback(
                                                    (toolUse, chunk) -> {
                                                        if (chunk.getOutput() != null) {
                                                            for (ContentBlock block :
                                                                    chunk.getOutput()) {
                                                                if (block instanceof TextBlock tb) {
                                                                    sink.next(
                                                                            new ToolResultTextDeltaEvent(
                                                                                    replyId,
                                                                                    toolUse.getId(),
                                                                                    tb.getText()));
                                                                } else {
                                                                    sink.next(
                                                                            new ToolResultDataDeltaEvent(
                                                                                    replyId,
                                                                                    toolUse.getId(),
                                                                                    block));
                                                                }
                                                            }
                                                        }
                                                        hookDispatcher
                                                                .fireActingChunk(
                                                                        toolUse, chunk, toolkit)
                                                                .subscribe();
                                                    });

                                            executeToolCalls(approved)
                                                    .contextWrite(ctx -> ctx.putAll(parentCtx))
                                                    .subscribe(
                                                            results -> {
                                                                List<
                                                                                Map.Entry<
                                                                                        ToolUseBlock,
                                                                                        ToolResultBlock>>
                                                                        merged =
                                                                                new ArrayList<>(
                                                                                        deniedEntries);
                                                                merged.addAll(results);
                                                                resultHolder.set(merged);
                                                                for (Map.Entry<
                                                                                ToolUseBlock,
                                                                                ToolResultBlock>
                                                                        entry : results) {
                                                                    ToolResultState state =
                                                                            determineToolResultState(
                                                                                    entry
                                                                                            .getValue());
                                                                    sink.next(
                                                                            new ToolResultEndEvent(
                                                                                    replyId,
                                                                                    entry.getKey()
                                                                                            .getId(),
                                                                                    state));
                                                                }
                                                                sink.complete();
                                                            },
                                                            sink::error);
                                        }));

        return deniedEvents.concatWith(approvedEvents);
    }

    /**
     * Outcome of running every {@link ToolBase} call through the {@link PermissionEngine}.
     *
     * @param pendingAsk tool calls that require user confirmation before execution.
     * @param autoDeniedIds ids of tool calls whose decision was {@code DENY}; the agent loop
     *     synthesises denied results for them without invoking the tool.
     */
    private record PermissionGate(List<ToolUseBlock> pendingAsk, Set<String> autoDeniedIds) {}

    /**
     * Run every tool call through the permission gate.
     *
     * <p>When the agent's {@link io.agentscope.core.permission.PermissionContext} is trivial
     * (default mode, no rules, no working directories — i.e. the user has not opted into the
     * permission system) we fall back to the lightweight pre-2.0 path: the tool's own
     * {@link ToolBase#checkPermissions} ASK gates a confirmation, anything else is approved.
     *
     * <p>Otherwise we engage the full {@link PermissionEngine} pipeline so deny/ask/allow rules
     * and EXPLORE/ACCEPT_EDITS/BYPASS/DONT_ASK modes are honoured before execution. Legacy
     * {@link AgentTool}s that do not extend {@link ToolBase} always pass through approved.
     */
    private Mono<PermissionGate> evaluatePermissions(List<ToolUseBlock> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return Mono.just(new PermissionGate(List.of(), Set.of()));
        }
        boolean useEngine = !state.getPermissionContext().isTrivial();
        return Flux.fromIterable(toolCalls)
                .concatMap(use -> evaluateOne(use, useEngine))
                .collectList()
                .map(
                        verdicts -> {
                            List<ToolUseBlock> pending = new ArrayList<>();
                            Set<String> denied = new HashSet<>();
                            for (PermissionVerdict v : verdicts) {
                                switch (v.behavior()) {
                                    case DENY -> denied.add(v.use().getId());
                                    case ASK -> pending.add(v.use());
                                    case ALLOW, PASSTHROUGH -> {
                                        // auto-approved; falls through to execution
                                    }
                                }
                            }
                            return new PermissionGate(pending, denied);
                        });
    }

    private Mono<PermissionVerdict> evaluateOne(ToolUseBlock use, boolean useEngine) {
        AgentTool tool = toolkit.getTool(use.getName());
        if (!(tool instanceof ToolBase tb)) {
            return Mono.just(new PermissionVerdict(use, PermissionBehavior.ALLOW));
        }
        Map<String, Object> input = use.getInput() == null ? Map.of() : use.getInput();
        if (useEngine) {
            return permissionEngine
                    .checkPermission(tb, input)
                    .map(
                            decision ->
                                    new PermissionVerdict(
                                            use,
                                            decision == null
                                                    ? PermissionBehavior.ASK
                                                    : decision.getBehavior()));
        }
        return tb.checkPermissions(input, state.getPermissionContext())
                .map(
                        decision -> {
                            if (decision == null) {
                                return new PermissionVerdict(use, PermissionBehavior.ALLOW);
                            }
                            // In the legacy lightweight path only an explicit ASK from the tool
                            // gates execution; PASSTHROUGH and ALLOW both run, DENY is honoured.
                            return switch (decision.getBehavior()) {
                                case ASK -> new PermissionVerdict(use, PermissionBehavior.ASK);
                                case DENY -> new PermissionVerdict(use, PermissionBehavior.DENY);
                                default -> new PermissionVerdict(use, PermissionBehavior.ALLOW);
                            };
                        });
    }

    private record PermissionVerdict(ToolUseBlock use, PermissionBehavior behavior) {}

    /**
     * Read a {@link Sinks.Many} of {@link ConfirmResult} from the subscriber's Reactor context
     * under {@link #CONFIRM_SINK_KEY}, take one response per pending tool call and return the
     * list. When the sink is absent every pending call is auto-denied.
     */
    private Mono<List<ConfirmResult>> awaitConfirmation(List<ToolUseBlock> pending) {
        return Mono.deferContextual(
                ctx -> {
                    if (!ctx.hasKey(CONFIRM_SINK_KEY)) {
                        List<ConfirmResult> denied = new ArrayList<>(pending.size());
                        for (ToolUseBlock t : pending) {
                            denied.add(new ConfirmResult(false, t));
                        }
                        return Mono.just(denied);
                    }
                    Object raw = ctx.get(CONFIRM_SINK_KEY);
                    if (!(raw instanceof Sinks.Many<?> rawSink)) {
                        return Mono.error(
                                new IllegalStateException(
                                        "Reactor context value for "
                                                + CONFIRM_SINK_KEY
                                                + " is not a Sinks.Many<ConfirmResult>"));
                    }
                    @SuppressWarnings("unchecked")
                    Sinks.Many<ConfirmResult> sink = (Sinks.Many<ConfirmResult>) rawSink;
                    return sink.asFlux().take(pending.size()).collectList();
                });
    }

    private static Set<String> collectDeniedIds(List<ConfirmResult> results) {
        Set<String> denied = new HashSet<>();
        if (results == null) {
            return denied;
        }
        for (ConfirmResult r : results) {
            if (r != null && !r.isConfirmed() && r.getToolCall() != null) {
                denied.add(r.getToolCall().getId());
            }
        }
        return denied;
    }

    private ToolResultState determineToolResultState(ToolResultBlock result) {
        if (result.isSuspended()) {
            return ToolResultState.RUNNING;
        }
        if (result.getState() != null && result.getState() != ToolResultState.RUNNING) {
            return result.getState();
        }
        if (result.getOutput() != null
                && result.getOutput().stream()
                        .anyMatch(
                                b ->
                                        b instanceof TextBlock tb
                                                && tb.getText() != null
                                                && tb.getText().startsWith("[ERROR]"))) {
            return ToolResultState.ERROR;
        }
        return ToolResultState.SUCCESS;
    }

    /**
     * Build a message containing suspended tool calls for user execution.
     *
     * <p>The message contains both the ToolUseBlocks and corresponding pending ToolResultBlocks
     * for the suspended tools.
     *
     * @param pendingPairs List of (ToolUseBlock, pending ToolResultBlock) pairs
     * @return Msg with GenerateReason.TOOL_SUSPENDED
     */
    private Msg buildSuspendedMsg(List<Map.Entry<ToolUseBlock, ToolResultBlock>> pendingPairs) {
        List<ContentBlock> content = new ArrayList<>();
        for (Map.Entry<ToolUseBlock, ToolResultBlock> pair : pendingPairs) {
            content.add(pair.getKey());
            content.add(pair.getValue());
        }
        return Msg.builder()
                .name(getName())
                .role(MsgRole.ASSISTANT)
                .content(content)
                .generateReason(GenerateReason.TOOL_SUSPENDED)
                .build();
    }

    /**
     * Execute tool calls and return paired results.
     *
     * <p>If tool execution fails (timeout, error, etc.), this method generates error tool results
     * for all pending tool calls instead of propagating the error. This ensures the agent can
     * continue processing and the model receives proper error feedback.
     *
     * @param toolCalls The list of tool calls (potentially modified by PreActingEvent hooks)
     * @return Mono containing list of (ToolUseBlock, ToolResultBlock) pairs
     */
    private Mono<List<Map.Entry<ToolUseBlock, ToolResultBlock>>> executeToolCalls(
            List<ToolUseBlock> toolCalls) {
        return toolkit.callTools(toolCalls, toolExecutionConfig, this, buildMergedToolContext())
                .map(
                        results ->
                                IntStream.range(0, toolCalls.size())
                                        .mapToObj(i -> Map.entry(toolCalls.get(i), results.get(i)))
                                        .toList())
                .onErrorResume(
                        Exception.class,
                        error -> {
                            // Preserve interruption signal for agent stop policy
                            if (error instanceof InterruptedException) {
                                return Mono.error(error);
                            }
                            // Generate error tool results for all pending tool calls.
                            // Only catch Exception subclasses; critical JVM errors
                            // (e.g. OutOfMemoryError) are left to propagate.
                            String errorMsg = ExceptionUtils.getErrorMessage(error);
                            log.error(
                                    "Tool execution failed, generating error results for {} tool"
                                            + " calls",
                                    toolCalls.size(),
                                    error);
                            List<Map.Entry<ToolUseBlock, ToolResultBlock>> errorResults =
                                    toolCalls.stream()
                                            .map(
                                                    toolCall -> {
                                                        ToolResultBlock errorResult =
                                                                buildErrorToolResult(
                                                                        toolCall.getId(),
                                                                        "Tool execution failed: "
                                                                                + errorMsg);
                                                        return Map.entry(toolCall, errorResult);
                                                    })
                                            .toList();
                            return Mono.just(errorResults);
                        });
    }

    /**
     * Fire PostActingEvent for a single tool result, build message and add to context.
     */
    private Mono<PostActingEvent> notifyPostActingHook(
            Map.Entry<ToolUseBlock, ToolResultBlock> entry) {
        ToolUseBlock toolUse = entry.getKey();
        ToolResultBlock result = entry.getValue();

        Msg toolMsg = ToolResultMessageBuilder.buildToolResultMsg(result, toolUse, getName());

        return hookDispatcher
                .firePostActing(toolUse, result, toolkit, toolMsg)
                .doOnNext(
                        e -> {
                            Msg resultMsg = e.getToolResultMsg();
                            state.contextMutable().add(resultMsg);
                        });
    }

    /**
     * Generate summary when max iterations reached.
     */
    protected Mono<Msg> summarizing() {
        log.debug("Maximum iterations reached. Generating summary...");

        // Handle pending tool calls that were not completed before max iterations
        if (hasPendingToolUse()) {
            List<ToolUseBlock> pendingTools = extractPendingToolCalls();
            log.warn(
                    "Max iterations reached with {} pending tool calls. Adding error results.",
                    pendingTools.size());

            for (ToolUseBlock toolUse : pendingTools) {
                ToolResultBlock errorResult =
                        buildErrorToolResult(
                                toolUse.getId(),
                                "Tool execution cancelled because maximum iterations limit ("
                                        + maxIters
                                        + ") was reached");

                Msg errorResultMsg =
                        ToolResultMessageBuilder.buildToolResultMsg(
                                errorResult, toolUse, getName());
                state.contextMutable().add(errorResultMsg);
            }
        }

        List<Msg> messageList = prepareSummaryMessages();
        GenerateOptions generateOptions = buildGenerateOptions();
        ReasoningContext context = new ReasoningContext(getName());
        publishEvent(new ExceedMaxItersEvent("", maxIters, maxIters));

        return hookDispatcher
                .firePreSummary(
                        messageList,
                        generateOptions,
                        model.getModelName(),
                        maxIters,
                        currentSystemMsg.get())
                .flatMap(
                        preSummaryEvent -> {
                            List<Msg> effectiveMessages =
                                    prependSystemMsg(
                                            preSummaryEvent.getInputMessages(),
                                            preSummaryEvent.getSystemMessage());
                            GenerateOptions effectiveOptions =
                                    preSummaryEvent.getEffectiveGenerateOptions();

                            return summaryStream(context, effectiveMessages, effectiveOptions)
                                    .then(
                                            Mono.defer(
                                                    () ->
                                                            Mono.justOrEmpty(
                                                                    context.buildFinalMessage())))
                                    .flatMap(
                                            msg ->
                                                    hookDispatcher
                                                            .firePostSummary(
                                                                    msg,
                                                                    effectiveOptions,
                                                                    model.getModelName())
                                                            .map(
                                                                    postEvent -> {
                                                                        Msg finalMsg =
                                                                                postEvent
                                                                                        .getSummaryMessage()
                                                                                        .withGenerateReason(
                                                                                                GenerateReason
                                                                                                        .MAX_ITERATIONS);
                                                                        state.contextMutable()
                                                                                .add(finalMsg);
                                                                        return finalMsg;
                                                                    }));
                        })
                .onErrorResume(this::handleSummaryError);
    }

    /**
     * Stream fine-grained {@link AgentEvent}s from a model call during summarization.
     *
     * <p>Structurally identical to {@link #reasoningStream} but notifies summary-specific
     * hooks ({@link SummaryChunkEvent}) and does not pass tool schemas to the model.
     *
     * @param context   reasoning context for chunk accumulation
     * @param messages  the messages to send to the model
     * @param options   generation options
     * @return event stream from the summary model call
     */
    Flux<AgentEvent> summaryStream(
            ReasoningContext context, List<Msg> messages, GenerateOptions options) {

        Function<ModelCallInput, Flux<AgentEvent>> summaryModelCallCore =
                mci -> summaryModelCallStream(context, mci, options);

        return MiddlewareChain.build(
                        middlewares, this, MiddlewareBase::onModelCall, summaryModelCallCore)
                .apply(new ModelCallInput(messages, null, options, model))
                .doOnNext(this::publishEvent);
    }

    private Flux<AgentEvent> summaryModelCallStream(
            ReasoningContext context, ModelCallInput mci, GenerateOptions hookOptions) {

        String replyId = UUID.randomUUID().toString().replace("-", "");
        AtomicBoolean textStarted = new AtomicBoolean(false);
        AtomicBoolean thinkingStarted = new AtomicBoolean(false);

        Flux<AgentEvent> modelEvents =
                mci.model().stream(mci.messages(), mci.tools(), mci.options())
                        .concatMap(chunk -> checkInterruptedAsync().thenReturn(chunk))
                        .concatMap(
                                chunk -> {
                                    List<Msg> chunkMsgs = context.processChunk(chunk);
                                    for (Msg msg : chunkMsgs) {
                                        hookDispatcher
                                                .fireSummaryChunk(
                                                        msg,
                                                        context,
                                                        hookOptions,
                                                        model.getModelName())
                                                .subscribe();
                                    }

                                    List<AgentEvent> events = new ArrayList<>();
                                    for (ContentBlock block : chunk.getContent()) {
                                        if (block instanceof TextBlock tb) {
                                            if (textStarted.compareAndSet(false, true)) {
                                                events.add(
                                                        new TextBlockStartEvent(replyId, "text"));
                                            }
                                            if (tb.getText() != null && !tb.getText().isEmpty()) {
                                                events.add(
                                                        new TextBlockDeltaEvent(
                                                                replyId, "text", tb.getText()));
                                            }
                                        } else if (block instanceof ThinkingBlock tb) {
                                            if (thinkingStarted.compareAndSet(false, true)) {
                                                events.add(
                                                        new ThinkingBlockStartEvent(
                                                                replyId, "thinking"));
                                            }
                                            if (tb.getThinking() != null
                                                    && !tb.getThinking().isEmpty()) {
                                                events.add(
                                                        new ThinkingBlockDeltaEvent(
                                                                replyId,
                                                                "thinking",
                                                                tb.getThinking()));
                                            }
                                        }
                                    }
                                    return Flux.fromIterable(events);
                                });

        Flux<AgentEvent> endEvents =
                Flux.defer(
                        () -> {
                            List<AgentEvent> events = new ArrayList<>();
                            if (textStarted.get()) {
                                events.add(new TextBlockEndEvent(replyId, "text"));
                            }
                            if (thinkingStarted.get()) {
                                events.add(new ThinkingBlockEndEvent(replyId, "thinking"));
                            }
                            events.add(new ModelCallEndEvent(replyId, context.getChatUsage()));
                            return Flux.fromIterable(events);
                        });

        return Flux.concat(Flux.just(new ModelCallStartEvent(replyId)), modelEvents, endEvents);
    }

    private List<Msg> prepareSummaryMessages() {
        List<Msg> messageList = new ArrayList<>(state.contextMutable());
        messageList.add(
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(
                                TextBlock.builder()
                                        .text(
                                                "You have failed to generate response within the"
                                                    + " maximum iterations. Now respond directly by"
                                                    + " summarizing the current situation.")
                                        .build())
                        .build());
        return messageList;
    }

    private Mono<Msg> handleSummaryError(Throwable error) {
        if (error instanceof InterruptedException) {
            return Mono.error(error);
        }
        log.error("Error generating summary", error);
        Msg errorMsg =
                Msg.builder()
                        .name(getName())
                        .role(MsgRole.ASSISTANT)
                        .content(
                                TextBlock.builder()
                                        .text(
                                                String.format(
                                                        "Maximum iterations (%d) reached."
                                                                + " Error generating summary: %s",
                                                        maxIters, error.getMessage()))
                                        .build())
                        .build();
        state.contextMutable().add(errorMsg);
        return Mono.just(errorMsg);
    }

    // ==================== Helper Methods ====================

    /**
     * Prepends the system message to {@code msgs} if non-null.
     *
     * <p>Called immediately before each {@code model.stream()} invocation to build the final
     * LLM input without contaminating the context message list.
     */
    private static List<Msg> prependSystemMsg(List<Msg> msgs, Msg systemMsg) {
        if (systemMsg == null) {
            return msgs != null ? msgs : List.of();
        }
        List<Msg> result = new ArrayList<>();
        result.add(systemMsg);
        if (msgs != null) {
            result.addAll(msgs);
        }
        return result;
    }

    /**
     * Check if the ReAct loop should terminate.
     *
     * <p>Note: Structured output retry is now handled by StructuredOutputHook via gotoReasoning().
     *
     * @param msg The reasoning message
     * @return true if should finish, false if should continue to acting
     */
    private boolean isFinished(Msg msg) {
        if (msg == null) {
            return true;
        }

        List<ToolUseBlock> toolCalls = msg.getContentBlocks(ToolUseBlock.class);

        // No tool calls - finished
        // If there are tool calls (even non-existent ones), continue to acting phase
        // where ToolExecutor will return "Tool not found" error for the model to see
        return toolCalls.isEmpty();
    }

    /**
     * Extract tool calls from the most recent assistant message.
     */
    private List<ToolUseBlock> extractRecentToolCalls() {
        return MessageUtils.extractRecentToolCalls(state.contextMutable(), getName());
    }

    /**
     * Extract only pending tool calls (those without results in context) from the most recent
     * assistant message.
     *
     * <p>This method filters out tool calls that already have corresponding results in context,
     * preventing duplicate execution when resuming from HITL or partial tool result scenarios.
     *
     * @return List of tool use blocks that don't have results yet, or empty list if all tools
     *     have been executed
     */
    private List<ToolUseBlock> extractPendingToolCalls() {
        List<ToolUseBlock> allToolCalls = extractRecentToolCalls();
        if (allToolCalls.isEmpty()) {
            return List.of();
        }

        Set<String> pendingIds = getPendingToolUseIds();
        return allToolCalls.stream()
                .filter(toolUse -> pendingIds.contains(toolUse.getId()))
                .toList();
    }

    @Override
    protected GenerateOptions buildGenerateOptions() {
        // Start with user-configured generateOptions if available
        GenerateOptions baseOptions = generateOptions;

        // If modelExecutionConfig is set, merge it into the options
        if (modelExecutionConfig != null) {
            GenerateOptions execConfigOptions =
                    GenerateOptions.builder().executionConfig(modelExecutionConfig).build();
            baseOptions = GenerateOptions.mergeOptions(execConfigOptions, baseOptions);
        }

        return baseOptions != null ? baseOptions : GenerateOptions.builder().build();
    }

    @Override
    protected Mono<Msg> handleInterrupt(InterruptContext context, Msg... originalArgs) {
        if (context.getSource() == InterruptSource.SYSTEM) {
            shutdownManager.saveOnInterruptObserved(this);
            return Mono.error(new AgentShuttingDownException());
        }

        String recoveryText = "I noticed that you have interrupted me. What can I do for you?";

        Msg recoveryMsg =
                Msg.builder()
                        .name(getName())
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text(recoveryText).build())
                        .build();

        state.contextMutable().add(recoveryMsg);
        return Mono.just(recoveryMsg);
    }

    @Override
    protected Mono<Void> doObserve(Msg msg) {
        if (msg != null) {
            state.contextMutable().add(msg);
        }
        return Mono.empty();
    }

    // ==================== Getters ====================

    /**
     * Read-only adapter exposing the agent's conversation context as a legacy {@link Memory}.
     * Retained so legacy hooks (e.g. {@code PendingToolRecoveryHook}, {@code StructuredOutputHook})
     * keep compiling against the 1.0.x API; the live state lives on {@link AgentState}.
     *
     * @deprecated since 2.0.0. Use {@link #getAgentState()} instead.
     */
    @Deprecated(since = "2.0.0")
    @SuppressWarnings("deprecation")
    public Memory getMemory() {
        return memory;
    }

    public String getSysPrompt() {
        return sysPrompt;
    }

    public Model getModel() {
        return model;
    }

    public int getMaxIters() {
        return maxIters;
    }

    /**
     * Gets the configured generation options for this agent.
     *
     * @return The generation options, or null if not configured
     */
    public GenerateOptions getGenerateOptions() {
        return generateOptions;
    }

    /** Returns the live conversational state (context + summary + permissions). */
    public AgentState getState() {
        syncToolkitToState();
        return state;
    }

    @Override
    public AgentState getAgentState() {
        syncToolkitToState();
        return state;
    }

    /** Returns the {@link Session} configured for state persistence, or {@code null}. */
    public Session getSession() {
        return session;
    }

    /** Returns the {@link SessionKey} used when persisting state. */
    public SessionKey getSessionKey() {
        return sessionKey;
    }

    private void syncToolkitToState() {
        if (toolkit != null) {
            state.getToolContext().setActivatedGroups(toolkit.getActiveGroups());
        }
    }

    /** Returns the model-call configuration (retries, timeouts). */
    public ModelConfig getModelConfig() {
        return modelConfig;
    }

    /** Returns the context-management configuration (compression thresholds, prompts). */
    public ContextConfig getContextConfig() {
        return contextConfig;
    }

    /** Returns the reasoning-loop configuration (maxIters, stopOnReject). */
    public ReactConfig getReactConfig() {
        return reactConfig;
    }

    /** Returns the permission engine that gates tool execution. */
    public PermissionEngine getPermissionEngine() {
        return permissionEngine;
    }

    /** Returns the immutable list of registered middlewares. */
    public List<Middleware> getMiddlewares() {
        return middlewares;
    }

    /** Returns the system prompt (alias for {@link #getSysPrompt()}). */
    public String getSystemPrompt() {
        return sysPrompt;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ==================== Harness orchestration methods ====================

    /**
     * Releases resources owned by this agent — currently the SQLite-backed
     * {@link WorkspaceIndex} created when {@code RemoteFilesystemSpec} is configured.
     *
     * <p>After close, the agent and any {@link WorkspaceManager} views produced from it must not
     * be used.
     */
    @Override
    public void close() {
        if (ownedWorkspaceIndex != null) {
            ownedWorkspaceIndex.close();
        }
    }

    /**
     * Package-private second-phase setter used by {@link Builder#build()} to populate the harness
     * orchestration fields after construction. Keeps the public constructor signature stable.
     * Should be called at most once during build; subsequent calls are silently ignored.
     */
    void injectHarnessRuntime(
            WorkspaceManager workspaceManager,
            BiFunction<String, String, WorkspaceManager> workspaceFactory,
            Function<String, Session> sessionFactory,
            WorkspaceIndex ownedWorkspaceIndex,
            SandboxContext defaultSandboxContext,
            CompactionHook compactionHook,
            List<AgentSkillRepository> skillRepositories) {
        this.workspaceManager = workspaceManager;
        this.workspaceFactory = workspaceFactory;
        this.sessionFactory = sessionFactory;
        this.ownedWorkspaceIndex = ownedWorkspaceIndex;
        this.defaultSandboxContext = defaultSandboxContext;
        this.compactionHook = compactionHook;
        this.skillRepositories =
                skillRepositories != null ? List.copyOf(skillRepositories) : List.of();
    }

    /**
     * Returns the workspace manager produced by the harness orchestration path, or {@code null}
     * for plain {@code ReActAgent.builder()} usage.
     */
    public WorkspaceManager getWorkspaceManager() {
        return workspaceManager;
    }

    /**
     * Returns a {@link WorkspaceManager} view whose filesystem and namespace are bound to the
     * given {@code (userId, sessionId)} for the duration of the returned view's IO. Unlike
     * {@link #getWorkspaceManager()}, this does not mutate any shared state on this agent — so it
     * is safe to call concurrently from per-request controllers without racing with active chats.
     *
     * <p>When the agent was not built via the harness orchestration path, this returns the
     * (possibly null) base workspace manager unchanged.
     */
    public WorkspaceManager workspaceFor(String userId, String sessionId) {
        if (workspaceFactory == null) {
            return workspaceManager;
        }
        return workspaceFactory.apply(userId, sessionId);
    }

    /** Returns the {@link CompactionHook} instance if compaction was configured, or {@code null}. */
    public CompactionHook getCompactionHook() {
        return compactionHook;
    }

    /**
     * Returns the ordered list of {@link AgentSkillRepository} instances bound to this agent (low
     * to high priority). Empty when no orchestration ran.
     */
    public List<AgentSkillRepository> getSkillRepositories() {
        return skillRepositories;
    }

    /**
     * Fills in default Session and SessionKey when the caller didn't provide them, and injects the
     * default sandbox context.
     */
    private RuntimeContext ensureSessionDefaults(RuntimeContext ctx) {
        Session ctxSession = ctx.getSession();
        if (ctxSession == null) {
            String uid = ctx.getUserId();
            if (sessionFactory != null && uid != null && !uid.isBlank()) {
                Session perCall = sessionFactory.apply(uid);
                ctxSession = perCall != null ? perCall : this.session;
            } else {
                ctxSession = this.session;
            }
        }
        SessionKey ctxSessionKey = ctx.getSessionKey();
        if (ctxSessionKey == null) {
            String id = ctx.getSessionId();
            if (id != null && !id.isBlank()) {
                ctxSessionKey = SimpleSessionKey.of(id);
            } else {
                ctxSessionKey = SimpleSessionKey.of(getName());
            }
        }
        SandboxContext sandboxCtx =
                ctx.get(SandboxContext.class) != null
                        ? ctx.get(SandboxContext.class)
                        : defaultSandboxContext;

        if (ctxSession == ctx.getSession()
                && ctxSessionKey == ctx.getSessionKey()
                && sandboxCtx == ctx.get(SandboxContext.class)) {
            return ctx;
        }
        return RuntimeContext.builder()
                .sessionId(ctx.getSessionId())
                .userId(ctx.getUserId())
                .session(ctxSession)
                .sessionKey(ctxSessionKey)
                .putAll(ctx.getExtra())
                .put(SandboxContext.class, sandboxCtx)
                .build();
    }

    private Mono<Msg> recoverFromOverflow(List<Msg> msgs, RuntimeContext effective) {
        if (compactionHook != null) {
            log.warn(
                    "Context overflow detected, triggering emergency compaction via"
                            + " CompactionHook");
            return forceCompactAndRetry(msgs, effective);
        }
        return Mono.error(
                new RuntimeException(
                        "Context overflow: no compaction configured, unable to recover"));
    }

    private Mono<Msg> forceCompactAndRetry(List<Msg> msgs, RuntimeContext effective) {
        List<Msg> allMsgs = state.contextMutable();
        if (allMsgs.isEmpty()) {
            return Mono.error(
                    new RuntimeException("Context overflow: context is empty, cannot compact"));
        }
        String agentId = getName();
        String sessionId =
                effective != null && effective.getSessionId() != null
                        ? effective.getSessionId()
                        : "default";

        CompactionConfig forceConfig = CompactionConfig.builder().triggerMessages(1).build();
        MemoryFlushManager fm = new MemoryFlushManager(workspaceManager, getModel());
        ConversationCompactor compactor = new ConversationCompactor(getModel(), fm);

        return compactor
                .compactIfNeeded(
                        effective != null ? effective : RuntimeContext.empty(),
                        allMsgs,
                        forceConfig,
                        agentId,
                        sessionId)
                .flatMap(
                        opt -> {
                            if (opt.isPresent()) {
                                state.contextMutable().clear();
                                state.contextMutable().addAll(opt.get());
                                // Bind context and call the bare path (no overflow re-wrap).
                                this.pendingRuntimeContext =
                                        effective != null ? effective : RuntimeContext.empty();
                                return call(msgs);
                            }
                            return Mono.error(
                                    new RuntimeException(
                                            "Context overflow: emergency compaction yielded no"
                                                    + " result"));
                        });
    }

    private static boolean isContextOverflowError(Throwable e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("context_length_exceeded")
                || lower.contains("context length")
                || lower.contains("maximum context")
                || lower.contains("token limit")
                || lower.contains("too many tokens")
                || lower.contains("exceeds the model's maximum")
                || lower.contains("reduce the length");
    }

    // ==================== Builder ====================

    @SuppressWarnings("deprecation")
    public static class Builder {
        String name;
        String description;
        String sysPrompt;
        boolean checkRunning = true;
        Model model;
        Toolkit toolkit = new Toolkit();

        int maxIters = 10;
        ExecutionConfig modelExecutionConfig;
        ExecutionConfig toolExecutionConfig;
        GenerateOptions generateOptions;
        final Set<Hook> hooks = new LinkedHashSet<>();
        private final List<Middleware> middlewares = new ArrayList<>();
        private boolean enableMetaTool = false;
        private StructuredOutputReminder structuredOutputReminder =
                StructuredOutputReminder.TOOL_CHOICE;

        private ToolExecutionContext toolExecutionContext;
        private boolean enablePendingToolRecovery = false;

        // 2.0 core fields
        private ModelConfig modelConfig;
        private ContextConfig contextConfig;
        private ReactConfig reactConfig;
        private PermissionContext permissionContext;
        private Session session;
        private SessionKey sessionKey;

        // ==================== Harness orchestration fields ====================

        /**
         * Flips to {@code true} when any harness-specific setter is invoked. When {@code true},
         * {@link #build()} runs the harness orchestration path (workspace, hooks, tools.json, etc.)
         * before constructing the agent. Plain {@link ReActAgent.Builder} usage leaves this
         * {@code false} so the original behavior is preserved.
         */
        boolean harnessOrchestrationEnabled = false;

        String agentId;
        final List<AgentSkillRepository> skillRepositories = new ArrayList<>();
        Path projectGlobalSkillsDir;

        Path workspace;
        String environmentMemory;
        AbstractFilesystem abstractFilesystem;
        SandboxDistributedOptions sandboxDistributedOptions;

        boolean leafSubagent = false;
        boolean agentTracingLogEnabled = true;
        CompactionConfig compactionConfig = null;
        ToolResultEvictionConfig toolResultEvictionConfig = null;

        final List<SubagentDeclaration> subagentDeclarations = new ArrayList<>();
        final List<ReActAgentBuilderSupport.SubagentFactoryEntry> customSubagentFactories =
                new ArrayList<>();
        TaskRepository taskRepository;
        Object externalSubagentTool;
        Function<String, Model> modelResolver;
        final List<String> additionalContextFiles = new ArrayList<>();
        int maxContextTokens = 8000;
        boolean useLegacyXmlWorkspaceContext = false;

        boolean disableFilesystemTools = false;
        boolean disableShellTool = false;
        boolean disableMemoryTools = false;
        boolean disableMemoryHooks = false;
        boolean disableSessionPersistence = false;
        boolean disableWorkspaceContext = false;
        boolean disableSubagents = false;
        boolean disableDynamicSkills = false;
        boolean disableDynamicSubagents = false;
        boolean disableToolsConfig = false;

        ToolsConfig toolsConfigOverride;

        SandboxFilesystemSpec sandboxFilesystemSpec;
        RemoteFilesystemSpec remoteFilesystemSpec;
        LocalFilesystemSpec localFilesystemSpec;

        private Builder() {}

        /**
         * Sets the name for this agent.
         *
         * @param name The agent name, must not be null
         * @return This builder instance for method chaining
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder checkRunning(boolean checkRunning) {
            this.checkRunning = checkRunning;
            return this;
        }

        /**
         * Sets the system prompt for this agent.
         *
         * @param sysPrompt The system prompt, can be null or empty
         * @return This builder instance for method chaining
         */
        public Builder sysPrompt(String sysPrompt) {
            this.sysPrompt = sysPrompt;
            return this;
        }

        /**
         * Sets the language model for this agent.
         *
         * @param model The language model to use for reasoning, must not be null
         * @return This builder instance for method chaining
         */
        public Builder model(Model model) {
            this.model = model;
            return this;
        }

        /**
         * Configures the model from a string id resolved via {@link ModelRegistry}: a named
         * registration ({@link ModelRegistry#register(String, Model)}) or a built-in pattern such
         * as {@code openai:gpt-5.5}, {@code dashscope:qwen-max}, {@code anthropic:claude-sonnet-4-5},
         * {@code gemini:gemini-2.0-flash}, or {@code ollama:llama3}. API keys for auto-created models
         * come from standard environment variables ({@code OPENAI_API_KEY}, {@code DASHSCOPE_API_KEY},
         * etc.).
         *
         * @param modelId registry id or {@code provider:model} string
         * @return this builder
         * @throws IllegalArgumentException if the id cannot be resolved
         */
        public Builder model(String modelId) {
            this.model = ModelRegistry.resolve(modelId);
            return this;
        }

        /**
         * Sets the toolkit containing available tools for this agent.
         *
         * @param toolkit The toolkit with available tools, must not be null
         * @return This builder instance for method chaining
         */
        public Builder toolkit(Toolkit toolkit) {
            this.toolkit = toolkit;
            return this;
        }

        /**
         * Sets the maximum number of reasoning-acting iterations.
         *
         * @param maxIters Maximum iterations, must be positive
         * @return This builder instance for method chaining
         */
        public Builder maxIters(int maxIters) {
            this.maxIters = maxIters;
            return this;
        }

        /**
         * Adds a hook for monitoring and intercepting agent execution events.
         *
         * <p>Hooks can observe or modify events during reasoning, acting, and other phases.
         * Multiple hooks can be added and will be executed in priority order (lower priority
         * values execute first).
         *
         * @param hook The hook to add, must not be null
         * @return This builder instance for method chaining
         * @see Hook
         * @see Hook#tools()
         */
        public Builder hook(Hook hook) {
            this.hooks.add(hook);
            return this;
        }

        /**
         * Adds multiple hooks for monitoring and intercepting agent execution events.
         *
         * <p>Hooks can observe or modify events during reasoning, acting, and other phases.
         * All hooks will be executed in priority order (lower priority values execute first).
         *
         * @param hooks The list of hooks to add, must not be null
         * @return This builder instance for method chaining
         * @see Hook
         * @see Hook#tools()
         */
        public Builder hooks(List<Hook> hooks) {
            this.hooks.addAll(hooks);
            return this;
        }

        /**
         * Adds a middleware for intercepting agent execution.
         *
         * @param middleware the middleware to add
         * @return this builder instance for method chaining
         */
        public Builder middleware(Middleware middleware) {
            this.middlewares.add(middleware);
            return this;
        }

        /**
         * Adds multiple middlewares for intercepting agent execution.
         *
         * @param middlewares the list of middlewares to add
         * @return this builder instance for method chaining
         */
        public Builder middlewares(List<Middleware> middlewares) {
            this.middlewares.addAll(middlewares);
            return this;
        }

        /**
         * Enables or disables the meta-tool functionality.
         *
         * <p>When enabled, the toolkit will automatically register a meta-tool that provides
         * information about available tools to the agent. This can help the agent understand
         * what tools are available without relying solely on the system prompt.
         *
         * @param enableMetaTool true to enable meta-tool, false to disable
         * @return This builder instance for method chaining
         */
        public Builder enableMetaTool(boolean enableMetaTool) {
            this.enableMetaTool = enableMetaTool;
            return this;
        }

        /**
         * Enables or disables automatic recovery from orphaned pending tool calls.
         *
         * <p>When enabled , a {@link PendingToolRecoveryHook} is automatically
         * registered to detect and patch orphaned pending tool calls with synthetic error
         * results before agent processing begins. This prevents {@link IllegalStateException}
         * when tool execution fails, times out, or is interrupted.
         *
         * <p>Disable this if you prefer to handle pending tool calls manually, for example
         * through HITL (Human-in-the-loop) mechanisms or custom error handling strategies.
         *
         * @param enable true to enable auto-recovery, false to disable
         * @return This builder instance for method chaining
         * @see PendingToolRecoveryHook
         */
        public Builder enablePendingToolRecovery(boolean enable) {
            this.enablePendingToolRecovery = enable;
            return this;
        }

        /**
         * Sets the execution configuration for model API calls.
         *
         * <p>This configuration controls timeout, retry behavior, and backoff strategy for
         * model requests during the reasoning phase. If not set, the agent will use the
         * model's default execution configuration.
         *
         * @param modelExecutionConfig The execution configuration for model calls, can be null
         * @return This builder instance for method chaining
         * @see ExecutionConfig
         */
        public Builder modelExecutionConfig(ExecutionConfig modelExecutionConfig) {
            this.modelExecutionConfig = modelExecutionConfig;
            return this;
        }

        /**
         * Sets the execution configuration for tool executions.
         *
         * <p>This configuration controls timeout, retry behavior, and backoff strategy for
         * tool calls during the acting phase. If not set, the toolkit will use its default
         * execution configuration.
         *
         * @param toolExecutionConfig The execution configuration for tool calls, can be null
         * @return This builder instance for method chaining
         * @see ExecutionConfig
         */
        public Builder toolExecutionConfig(ExecutionConfig toolExecutionConfig) {
            this.toolExecutionConfig = toolExecutionConfig;
            return this;
        }

        /**
         * Sets the generation options for model API calls.
         *
         * <p>This configuration controls LLM generation parameters such as temperature, topP,
         * maxTokens, frequencyPenalty, presencePenalty, etc. These options are passed to the
         * model during the reasoning phase.
         *
         * <p><b>Example usage:</b>
         * <pre>{@code
         * ReActAgent agent = ReActAgent.builder()
         *     .name("assistant")
         *     .model(model)
         *     .generateOptions(GenerateOptions.builder()
         *         .temperature(0.7)
         *         .topP(0.9)
         *         .maxTokens(1000)
         *         .build())
         *     .build();
         * }</pre>
         *
         * <p><b>Note:</b> If both generateOptions and modelExecutionConfig are set,
         * the modelExecutionConfig's executionConfig will be merged into the generateOptions,
         * with modelExecutionConfig taking precedence for execution settings.
         *
         * @param generateOptions The generation options for model calls, can be null
         * @return This builder instance for method chaining
         * @see GenerateOptions
         */
        public Builder generateOptions(GenerateOptions generateOptions) {
            this.generateOptions = generateOptions;
            return this;
        }

        /**
         * Sets the structured output enforcement mode.
         *
         * @param reminder The structured output reminder mode, must not be null
         * @return This builder instance for method chaining
         */
        public Builder structuredOutputReminder(StructuredOutputReminder reminder) {
            this.structuredOutputReminder = reminder;
            return this;
        }

        /**
         * Sets the tool execution context for this agent.
         *
         * <p>This context will be passed to all tools invoked by this agent and can include
         * user identity, session information, permissions, and other metadata. The context
         * from this agent level will override toolkit-level context but can be overridden by
         * call-level context.
         *
         * @param toolExecutionContext The tool execution context
         * @return This builder instance for method chaining
         */
        public Builder toolExecutionContext(ToolExecutionContext toolExecutionContext) {
            this.toolExecutionContext = toolExecutionContext;
            return this;
        }

        /**
         * Sets the {@link Session} backing automatic AgentState load (at construction) and save
         * (after every successful {@code call()} and on graceful shutdown). When {@code null}, the
         * agent runs purely in-memory and persistence is a no-op.
         */
        public Builder session(Session session) {
            this.session = session;
            return this;
        }

        /**
         * Sets the {@link SessionKey} used when reading / writing the {@code agent_state} entry.
         * Defaults to {@code SimpleSessionKey.of(name)} when unset.
         */
        public Builder sessionKey(SessionKey sessionKey) {
            this.sessionKey = sessionKey;
            return this;
        }

        /**
         * Sets the {@link ModelConfig} controlling reasoning retry / backoff. When unset,
         * {@link ModelConfig#defaults()} is used.
         */
        public Builder modelConfig(ModelConfig modelConfig) {
            this.modelConfig = modelConfig;
            return this;
        }

        /**
         * Sets the {@link ContextConfig} controlling context compression thresholds. When unset,
         * {@link ContextConfig#defaults()} is used.
         */
        public Builder contextConfig(ContextConfig contextConfig) {
            this.contextConfig = contextConfig;
            return this;
        }

        /**
         * Sets the {@link ReactConfig} controlling the reasoning-acting loop. When unset, a config
         * is derived from {@link #maxIters(int)}.
         */
        public Builder reactConfig(ReactConfig reactConfig) {
            this.reactConfig = reactConfig;
            return this;
        }

        /**
         * Sets the {@link PermissionContext} consulted by the {@link PermissionEngine} during tool
         * execution. When unset, an empty permission context is used (PASSTHROUGH for all tools).
         */
        public Builder permissionContext(PermissionContext permissionContext) {
            this.permissionContext = permissionContext;
            return this;
        }

        // ==================== Harness orchestration setters ====================

        /**
         * Sets the stable identifier used as the agent's namespace key in the composite filesystem
         * (e.g. {@code [agents, <agentId>, users, <userId>, ...]}). When unset, {@link #build()}
         * falls back to {@link #name(String)} for the namespace key.
         */
        public Builder agentId(String agentId) {
            this.agentId = agentId;
            this.harnessOrchestrationEnabled = true;
            return this;
        }

        /**
         * Adds a marketplace / external skill repository (e.g. {@code GitSkillRepository},
         * Nacos, HTTP). Repositories compose additively with workspace skills.
         */
        public Builder skillRepository(AgentSkillRepository skillRepository) {
            if (skillRepository != null) {
                this.skillRepositories.add(skillRepository);
                this.harnessOrchestrationEnabled = true;
            }
            return this;
        }

        /**
         * Replaces the current marketplace repository list with the given collection.
         */
        public Builder skillRepositories(List<AgentSkillRepository> repositories) {
            this.skillRepositories.clear();
            if (repositories != null) {
                for (AgentSkillRepository repo : repositories) {
                    if (repo != null) {
                        this.skillRepositories.add(repo);
                    }
                }
            }
            this.harnessOrchestrationEnabled = true;
            return this;
        }

        /**
         * Configures a project-global skills directory layered below marketplace and workspace
         * skills (lowest precedence).
         */
        public Builder projectGlobalSkillsDir(Path projectGlobalSkillsDir) {
            this.projectGlobalSkillsDir = projectGlobalSkillsDir;
            this.harnessOrchestrationEnabled = true;
            return this;
        }

        /**
         * Sets the workspace directory. Pass {@code null} to use the default
         * {@code ${cwd}/.agentscope/workspace}.
         */
        public Builder workspace(Path workspace) {
            this.workspace = workspace;
            this.harnessOrchestrationEnabled = true;
            return this;
        }

        /**
         * Sets the workspace directory from a filesystem path string. Equivalent to
         * {@link #workspace(Path)} with {@code Path.of(path.strip())}.
         */
        public Builder workspace(String path) {
            if (path == null) {
                this.workspace = null;
            } else {
                String trimmed = path.strip();
                if (trimmed.isEmpty()) {
                    throw new IllegalArgumentException("workspace path must not be blank");
                }
                this.workspace = Path.of(trimmed);
            }
            this.harnessOrchestrationEnabled = true;
            return this;
        }

        public Builder environmentMemory(String environmentMemory) {
            this.environmentMemory = environmentMemory;
            this.harnessOrchestrationEnabled = true;
            return this;
        }

        /**
         * Escape hatch: sets a custom {@link AbstractFilesystem} implementation directly.
         */
        public Builder abstractFilesystem(AbstractFilesystem backend) {
            this.abstractFilesystem = backend;
            this.harnessOrchestrationEnabled = true;
            return this;
        }

        /** Configures Mode 2 — sandbox filesystem. */
        public Builder filesystem(SandboxFilesystemSpec spec) {
            this.sandboxFilesystemSpec = spec;
            this.harnessOrchestrationEnabled = true;
            return this;
        }

        /** Configures Mode 1 — composite (non-sandbox) filesystem. */
        public Builder filesystem(RemoteFilesystemSpec spec) {
            this.remoteFilesystemSpec = spec;
            this.harnessOrchestrationEnabled = true;
            return this;
        }

        /** Configures Mode 3 — local filesystem with shell. */
        public Builder filesystem(LocalFilesystemSpec spec) {
            this.localFilesystemSpec = spec;
            this.harnessOrchestrationEnabled = true;
            return this;
        }

        /** Enables high-level distributed sandbox configuration. */
        public Builder sandboxDistributed(SandboxDistributedOptions options) {
            this.sandboxDistributedOptions = options;
            this.harnessOrchestrationEnabled = true;
            return this;
        }

        /** Enables the {@link CompactionHook} with the given configuration. */
        public Builder compaction(CompactionConfig config) {
            this.compactionConfig = config;
            this.harnessOrchestrationEnabled = true;
            return this;
        }

        /** Enables {@link ToolResultEvictionHook} with the given configuration. */
        public Builder toolResultEviction(ToolResultEvictionConfig config) {
            this.toolResultEvictionConfig = config;
            this.harnessOrchestrationEnabled = true;
            return this;
        }

        /** Programmatic override for {@code workspace/tools.json}. */
        public Builder toolsConfig(ToolsConfig toolsConfig) {
            this.toolsConfigOverride = toolsConfig;
            this.harnessOrchestrationEnabled = true;
            return this;
        }

        /** Adds a subagent declaration. */
        public Builder subagent(SubagentDeclaration declaration) {
            this.subagentDeclarations.add(declaration);
            this.harnessOrchestrationEnabled = true;
            return this;
        }

        public Builder subagents(List<SubagentDeclaration> declarations) {
            this.subagentDeclarations.addAll(declarations);
            this.harnessOrchestrationEnabled = true;
            return this;
        }

        /** Adds a fully custom subagent factory for a given agent id. */
        public Builder subagentFactory(
                String name, Function<String, io.agentscope.core.agent.Agent> factory) {
            this.customSubagentFactories.add(
                    new ReActAgentBuilderSupport.SubagentFactoryEntry(name, factory));
            this.harnessOrchestrationEnabled = true;
            return this;
        }

        /** Sets a custom {@link TaskRepository} for background subagent execution. */
        public Builder taskRepository(TaskRepository taskRepository) {
            this.taskRepository = taskRepository;
            this.harnessOrchestrationEnabled = true;
            return this;
        }

        /** Injects an external subagent tool (typically {@code SessionsTool}). */
        public Builder externalSubagentTool(Object tool) {
            this.externalSubagentTool = tool;
            this.harnessOrchestrationEnabled = true;
            return this;
        }

        /** Sets a resolver for model name strings to {@link Model} instances for subagents. */
        public Builder modelResolver(Function<String, Model> resolver) {
            this.modelResolver = resolver;
            this.harnessOrchestrationEnabled = true;
            return this;
        }

        /**
         * Adds a custom context file (relative to workspace) that will be loaded into the system
         * prompt alongside AGENTS.md, MEMORY.md, and KNOWLEDGE.md.
         */
        public Builder additionalContextFile(String relativePath) {
            if (relativePath != null && !relativePath.isBlank()) {
                this.additionalContextFiles.add(relativePath);
                this.harnessOrchestrationEnabled = true;
            }
            return this;
        }

        /** Sets the maximum token budget for workspace context. */
        public Builder maxContextTokens(int maxTokens) {
            this.maxContextTokens = maxTokens;
            this.harnessOrchestrationEnabled = true;
            return this;
        }

        /**
         * Switches workspace context rendering between markdown (default) and legacy XML style.
         */
        public Builder useLegacyXmlWorkspaceContext(boolean enabled) {
            this.useLegacyXmlWorkspaceContext = enabled;
            this.harnessOrchestrationEnabled = true;
            return this;
        }

        /**
         * Enables or disables agent execution trace logging via {@link AgentTraceHook}.
         * Default is {@code true}.
         */
        public Builder enableAgentTracingLog(boolean enabled) {
            this.agentTracingLogEnabled = enabled;
            this.harnessOrchestrationEnabled = true;
            return this;
        }

        /** Skips registration of {@link FilesystemTool}. */
        public Builder disableFilesystemTools() {
            this.disableFilesystemTools = true;
            return this;
        }

        /** Skips registration of {@link ShellExecuteTool}. */
        public Builder disableShellTool() {
            this.disableShellTool = true;
            return this;
        }

        /** Disables dynamic per-call skill loading from the workspace filesystem. */
        public Builder disableDynamicSkills() {
            this.disableDynamicSkills = true;
            return this;
        }

        /** Disables dynamic per-call subagent reload from the workspace filesystem. */
        public Builder disableDynamicSubagents() {
            this.disableDynamicSubagents = true;
            return this;
        }

        /** Skips registration of {@link MemorySearchTool}, {@link MemoryGetTool}, {@link SessionSearchTool}. */
        public Builder disableMemoryTools() {
            this.disableMemoryTools = true;
            return this;
        }

        /** Skips registration of {@link MemoryFlushHook} and {@link MemoryMaintenanceHook}. */
        public Builder disableMemoryHooks() {
            this.disableMemoryHooks = true;
            return this;
        }

        /** No-op since 2.0; session persistence is owned by ReActAgent itself. */
        public Builder disableSessionPersistence() {
            this.disableSessionPersistence = true;
            return this;
        }

        /** Skips registration of {@link WorkspaceContextHook}. */
        public Builder disableWorkspaceContext() {
            this.disableWorkspaceContext = true;
            return this;
        }

        /** Skips registration of {@link SubagentsHook}. */
        public Builder disableSubagents() {
            this.disableSubagents = true;
            return this;
        }

        /** Skips reading {@code workspace/tools.json}. */
        public Builder disableToolsConfig() {
            this.disableToolsConfig = true;
            return this;
        }

        /**
         * Marks this build as a leaf subagent (no nested subagent orchestration). Package-private
         * because only {@link ReActAgentBuilderSupport} (and the deprecated harness shell) should
         * mark leaf agents.
         */
        Builder asLeafSubagent() {
            this.leafSubagent = true;
            return this;
        }

        /**
         * Builds the subagent entries (general-purpose + declared + custom factories) without
         * constructing the full agent. Useful for callers that need to extract subagent factories
         * up front (for example to mount them on a session router) before assembling the parent
         * agent.
         *
         * @param resolvedWorkspace workspace path to scan for {@code subagents/*.md} declarations
         */
        public List<io.agentscope.harness.agent.hook.SubagentsHook.SubagentEntry>
                buildSubagentEntries(Path resolvedWorkspace) {
            return ReActAgentBuilderSupport.buildSubagentEntries(this, resolvedWorkspace, null);
        }

        /**
         * Same as {@link #buildSubagentEntries(Path)} but also threads a
         * {@link io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem} into the
         * subagent factories so spawned agents share the parent's sandbox session when desired.
         */
        public List<io.agentscope.harness.agent.hook.SubagentsHook.SubagentEntry>
                buildSubagentEntries(
                        Path resolvedWorkspace,
                        io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem
                                sandboxFs) {
            return ReActAgentBuilderSupport.buildSubagentEntries(
                    this, resolvedWorkspace, sandboxFs);
        }

        /**
         * Returns a new {@link Builder} pre-populated with the given agent's observable
         * configuration: name, description, system prompt, model, maxIters, generateOptions, plan
         * notebook, and a defensive copy of the toolkit.
         *
         * <p>Use to derive a related agent without re-specifying every field.
         */
        public static Builder fromAgent(ReActAgent agent) {
            Builder b = new Builder();
            b.name = agent.getName();
            b.description = agent.getDescription();
            b.sysPrompt = agent.getSysPrompt();
            b.model = agent.getModel();
            b.maxIters = agent.getMaxIters();
            b.generateOptions = agent.getGenerateOptions();
            b.toolkit = agent.getToolkit().copy();
            return b;
        }

        /**
         * Builds and returns a new ReActAgent instance with the configured settings.
         *
         * @return A new ReActAgent instance
         * @throws IllegalArgumentException if required parameters are missing or invalid
         */
        public ReActAgent build() {
            // Deep copy toolkit to avoid state interference between agents
            Toolkit agentToolkit = this.toolkit.copy();

            HarnessOrchestrationResult harnessResult =
                    harnessOrchestrationEnabled ? runHarnessOrchestration(agentToolkit) : null;

            registerToolsFromHooks(agentToolkit);

            if (enableMetaTool) {
                agentToolkit.registerMetaTool();
            }

            // Register PendingToolRecoveryHook if enabled
            if (enablePendingToolRecovery) {
                hooks.add(new PendingToolRecoveryHook());
            }

            ReActAgent agent = new ReActAgent(this, agentToolkit);

            if (harnessResult != null) {
                agent.injectHarnessRuntime(
                        harnessResult.workspaceManager,
                        harnessResult.workspaceFactory,
                        harnessResult.sessionFactory,
                        harnessResult.ownedWorkspaceIndex,
                        harnessResult.defaultSandboxContext,
                        harnessResult.compactionHook,
                        harnessResult.skillRepositories);
                if (harnessResult.selfRef != null) {
                    harnessResult.selfRef.set(agent);
                }
            }
            return agent;
        }

        /**
         * Result bundle produced by {@link #runHarnessOrchestration(Toolkit)}, passed through to
         * {@link ReActAgent#injectHarnessRuntime} after construction.
         */
        private static final class HarnessOrchestrationResult {
            WorkspaceManager workspaceManager;
            BiFunction<String, String, WorkspaceManager> workspaceFactory;
            Function<String, Session> sessionFactory;
            WorkspaceIndex ownedWorkspaceIndex;
            SandboxContext defaultSandboxContext;
            CompactionHook compactionHook;
            List<AgentSkillRepository> skillRepositories;
            AtomicReference<ReActAgent> selfRef;
        }

        /**
         * Builds the workspace/filesystem/sandbox stack, assembles the harness hooks and tools, and
         * applies them to {@code agentToolkit} + the builder's {@code hooks} set. Returns a bundle
         * of runtime state that callers must hand to
         * {@link ReActAgent#injectHarnessRuntime} after construction.
         *
         * <p>Mirrors {@code HarnessAgent.Builder.build()}'s orchestration sequence.
         */
        private HarnessOrchestrationResult runHarnessOrchestration(Toolkit agentToolkit) {
            // ---- Validation ----
            int specCount = 0;
            if (sandboxFilesystemSpec != null) specCount++;
            if (remoteFilesystemSpec != null) specCount++;
            if (localFilesystemSpec != null) specCount++;
            if (specCount > 1) {
                throw new IllegalStateException(
                        "At most one of sandboxFilesystemSpec, remoteFilesystemSpec,"
                                + " localFilesystemSpec may be configured");
            }
            if (abstractFilesystem != null && specCount > 0) {
                throw new IllegalStateException(
                        "abstractFilesystem() is an escape hatch and is mutually exclusive with"
                                + " filesystem(...) specs");
            }
            if (sandboxDistributedOptions != null && sandboxFilesystemSpec == null) {
                throw new IllegalStateException(
                        "sandboxDistributed(...) requires sandbox mode."
                                + " Configure filesystem(SandboxFilesystemSpec) first.");
            }

            Path resolvedWorkspace =
                    workspace != null
                            ? workspace
                            : Paths.get(System.getProperty("user.dir"))
                                    .resolve(".agentscope/workspace");
            String resolvedAgentId =
                    agentId != null && !agentId.isBlank()
                            ? agentId
                            : (name != null && !name.isBlank() ? name : "ReActAgent");
            Session effectiveSession =
                    sandboxDistributedOptions != null
                                    && sandboxDistributedOptions.getSession() != null
                            ? sandboxDistributedOptions.getSession()
                            : session;
            NamespaceFactory nsFactory =
                    rc -> {
                        String uid = rc != null ? rc.getUserId() : null;
                        return (uid == null || uid.isBlank()) ? List.of() : List.of(uid);
                    };
            if (effectiveSession == null) {
                effectiveSession =
                        new WorkspaceSession(resolvedWorkspace, resolvedAgentId, nsFactory);
            }

            if (remoteFilesystemSpec != null && effectiveSession instanceof WorkspaceSession) {
                throw new IllegalStateException(
                        "filesystem(RemoteFilesystemSpec) is designed for distributed /"
                                + " multi-replica deployments, but the effective Session is a local"
                                + " WorkspaceSession. Configure a distributed Session backend (for"
                                + " example RedisSession) via .session(...).");
            }
            WorkspaceIndex workspaceIndex =
                    remoteFilesystemSpec != null ? WorkspaceIndex.open(resolvedWorkspace) : null;
            AbstractFilesystem filesystem =
                    ReActAgentBuilderSupport.resolveFilesystem(
                            this, resolvedWorkspace, resolvedAgentId, workspaceIndex, nsFactory);

            // ---- Sandbox integration ----
            SandboxLifecycleHook sandboxLifecycleHook = null;
            SandboxContext defaultSandboxContext = null;
            SandboxBackedFilesystem capturedSandboxFs = null;
            if (sandboxFilesystemSpec != null) {
                if (sandboxDistributedOptions != null
                        && sandboxDistributedOptions.getSnapshotSpec() != null) {
                    sandboxFilesystemSpec.snapshotSpec(sandboxDistributedOptions.getSnapshotSpec());
                }
                capturedSandboxFs = new SandboxBackedFilesystem();
                filesystem = capturedSandboxFs;

                defaultSandboxContext = sandboxFilesystemSpec.toSandboxContext(resolvedWorkspace);
                boolean skipDistributedValidation =
                        sandboxDistributedOptions != null
                                && !sandboxDistributedOptions.isRequireDistributed();
                if (!skipDistributedValidation) {
                    ReActAgentBuilderSupport.validateDistributedSandboxConfig(
                            this, effectiveSession, defaultSandboxContext);
                }

                Session sandboxStateSession =
                        effectiveSession instanceof WorkspaceSession
                                ? new WorkspaceSession(resolvedWorkspace, resolvedAgentId, null)
                                : effectiveSession;
                SandboxStateStore stateStore =
                        sandboxFilesystemSpec.getSandboxStateStore() != null
                                ? sandboxFilesystemSpec.getSandboxStateStore()
                                : new SessionSandboxStateStore(
                                        sandboxStateSession, resolvedAgentId);
                SandboxExecutionGuard executionGuard =
                        sandboxFilesystemSpec.getExecutionGuard() != null
                                ? sandboxFilesystemSpec.getExecutionGuard()
                                : SandboxExecutionGuard.noop();
                SandboxManager sandboxManager =
                        new SandboxManager(
                                defaultSandboxContext.getClient(),
                                stateStore,
                                resolvedAgentId,
                                executionGuard);
                sandboxLifecycleHook = new SandboxLifecycleHook(sandboxManager, capturedSandboxFs);
            }
            WorkspaceManager wsManager =
                    new WorkspaceManager(resolvedWorkspace, filesystem, workspaceIndex, nsFactory);
            wsManager.validate();

            final AbstractFilesystem sharedFilesystemRef = filesystem;
            final Path capturedWorkspace = resolvedWorkspace;
            final WorkspaceIndex capturedIndex = workspaceIndex;
            BiFunction<String, String, WorkspaceManager> workspaceFactoryFn =
                    (uid, sid) -> {
                        RuntimeContext bakedRc =
                                ReActAgentBuilderSupport.buildBakedRuntimeContext(uid, sid);
                        NamespaceFactory ctxNs =
                                rc -> (uid == null || uid.isBlank()) ? List.of() : List.of(uid);
                        AbstractFilesystem ctxFs =
                                new BakedContextFilesystem(sharedFilesystemRef, bakedRc);
                        return new WorkspaceManager(capturedWorkspace, ctxFs, capturedIndex, ctxNs);
                    };

            final Session capturedDefaultSession = effectiveSession;
            final String capturedAgentId = resolvedAgentId;
            Function<String, Session> sessionFactoryFn =
                    capturedDefaultSession instanceof WorkspaceSession
                            ? uid -> {
                                NamespaceFactory baked = rc -> List.of(uid);
                                return new WorkspaceSession(
                                        capturedWorkspace, capturedAgentId, baked);
                            }
                            : null;

            // ---- Hooks ----
            if (sandboxLifecycleHook != null) {
                hooks.add(sandboxLifecycleHook);
            }
            if (agentTracingLogEnabled) {
                hooks.add(new AgentTraceHook());
            }
            if (!disableWorkspaceContext) {
                WorkspaceContextHook markdownHook =
                        new WorkspaceContextHook(
                                wsManager,
                                name != null ? name : "ReActAgent",
                                environmentMemory,
                                maxContextTokens);
                markdownHook.setAdditionalContextFiles(additionalContextFiles);
                hooks.add(markdownHook);
            }
            if (model != null && !disableMemoryHooks) {
                hooks.add(new MemoryFlushHook(wsManager, model));
            }
            if (model != null && !disableMemoryHooks) {
                MemoryConsolidator consolidator = new MemoryConsolidator(wsManager, model);
                hooks.add(new MemoryMaintenanceHook(wsManager, consolidator));
            }
            CompactionHook compactionHook = null;
            if (compactionConfig != null && model != null) {
                compactionHook = new CompactionHook(wsManager, model, compactionConfig);
                hooks.add(compactionHook);
            }
            if (toolResultEvictionConfig != null) {
                hooks.add(new ToolResultEvictionHook(filesystem, toolResultEvictionConfig));
            }
            if (!leafSubagent && !disableSubagents && model != null) {
                if (filesystem != null && !disableDynamicSubagents) {
                    DynamicSubagentsHook dynamicSubagentsHook =
                            ReActAgentBuilderSupport.buildDynamicSubagentsHook(
                                    this, wsManager, resolvedWorkspace, capturedSandboxFs);
                    if (dynamicSubagentsHook != null) {
                        hooks.add(dynamicSubagentsHook);
                    }
                } else {
                    SubagentsHook subagentsHook =
                            ReActAgentBuilderSupport.buildSubagentsHook(
                                    this, wsManager, resolvedWorkspace, capturedSandboxFs);
                    if (subagentsHook != null) {
                        hooks.add(subagentsHook);
                    }
                }
            }

            // ---- Toolkit ----
            if (!disableMemoryTools) {
                agentToolkit.registerTool(new MemorySearchTool(wsManager));
                agentToolkit.registerTool(new MemoryGetTool(wsManager));
                agentToolkit.registerTool(new SessionSearchTool(wsManager));
            }
            if (!disableFilesystemTools) {
                agentToolkit.registerTool(new FilesystemTool(filesystem));
            }
            if (!disableShellTool && filesystem instanceof AbstractSandboxFilesystem sandbox) {
                agentToolkit.registerTool(new ShellExecuteTool(sandbox));
            }

            // ---- workspace/tools.json: MCP servers + allow/deny filter ----
            ToolsConfig resolvedToolsConfig = null;
            if (!disableToolsConfig) {
                if (toolsConfigOverride != null) {
                    resolvedToolsConfig = toolsConfigOverride;
                } else if (wsManager != null) {
                    resolvedToolsConfig = ToolsConfigLoader.load(wsManager).orElse(null);
                }
            }
            if (resolvedToolsConfig != null) {
                McpServerRegistrar.register(agentToolkit, resolvedToolsConfig.getMcpServers());
            }

            // ---- Skills ----
            final AtomicReference<ReActAgent> selfRef = new AtomicReference<>();
            Supplier<RuntimeContext> currentRcSupplier =
                    () -> {
                        ReActAgent self = selfRef.get();
                        RuntimeContext rc = self != null ? self.getRuntimeContext() : null;
                        return rc != null ? rc : RuntimeContext.empty();
                    };
            List<AgentSkillRepository> orderedSkillRepos =
                    ReActAgentBuilderSupport.composeSkillRepositories(
                            this, wsManager, filesystem, currentRcSupplier);
            if (!orderedSkillRepos.isEmpty() && !disableDynamicSkills) {
                hooks.add(new DynamicSkillHook(orderedSkillRepos, agentToolkit));
            }

            // ---- Apply tools.json allow/deny filter ----
            if (resolvedToolsConfig != null) {
                ToolFilter.apply(agentToolkit, resolvedToolsConfig);
            }

            log.info(
                    "ReActAgent '{}' built with harness orchestration [workspace={}, backend={},"
                            + " subagents={}]",
                    name,
                    resolvedWorkspace,
                    filesystem.getClass().getSimpleName(),
                    !leafSubagent && !disableSubagents && model != null);

            HarnessOrchestrationResult result = new HarnessOrchestrationResult();
            result.workspaceManager = wsManager;
            result.workspaceFactory = workspaceFactoryFn;
            result.sessionFactory = sessionFactoryFn;
            result.ownedWorkspaceIndex = workspaceIndex;
            result.defaultSandboxContext = defaultSandboxContext;
            result.compactionHook = compactionHook;
            result.skillRepositories = orderedSkillRepos;
            result.selfRef = selfRef;
            return result;
        }

        /**
         * Registers tool objects declared by hooks ({@link Hook#tools()}) on the agent toolkit.
         *
         * <p>Runs after {@link Toolkit#copy()} so hook-supplied tools are scoped to this agent
         * instance without modifying the builder's original toolkit.
         */
        private void registerToolsFromHooks(Toolkit agentToolkit) {
            for (Hook hook : hooks) {
                List<Object> toolObjects = hook.tools();
                if (toolObjects == null || toolObjects.isEmpty()) {
                    continue;
                }
                for (Object toolObject : toolObjects) {
                    if (toolObject != null) {
                        agentToolkit.registerTool(toolObject);
                    }
                }
            }
        }
    }
}
