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
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.agent.SubagentEventBus;
import io.agentscope.core.agent.accumulator.ReasoningContext;
import io.agentscope.core.agent.config.ModelConfig;
import io.agentscope.core.agent.config.ReactConfig;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.RequestStopEvent;
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
import io.agentscope.core.formatter.JsonSchema;
import io.agentscope.core.formatter.ResponseFormat;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.LegacyHookDispatcher;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.interruption.InterruptControl;
import io.agentscope.core.interruption.InterruptSource;
import io.agentscope.core.memory.AgentStateMemoryView;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.memory.LongTermMemoryTools;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.memory.StaticLongTermMemoryHook;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.MiddlewareChain;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionEngine;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.rag.GenericRAGHook;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.KnowledgeRetrievalTools;
import io.agentscope.core.rag.RAGMode;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.shutdown.AgentShuttingDownException;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import io.agentscope.core.shutdown.GracefulShutdownMiddleware;
import io.agentscope.core.shutdown.PartialReasoningPolicy;
import io.agentscope.core.skill.DynamicSkillMiddleware;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.LegacyStateLoader;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.tool.ToolResultMessageBuilder;
import io.agentscope.core.tool.ToolValidator;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.ExceptionUtils;
import io.agentscope.core.util.JsonSchemaUtils;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.core.util.MessageUtils;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
 *   <li><b>Structured Output:</b> per-call {@code generate_response} tool provides type-safe output
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
 * <p><b>Thread Safety:</b> {@code ReActAgent} is <em>not</em> thread-safe. A single instance
 * processes exactly one {@code call()} at a time; a concurrent invocation on the same instance
 * throws {@link IllegalStateException}. For web services or other concurrent scenarios, create
 * one agent instance per request via a factory method. {@link io.agentscope.core.model.Model},
 * {@link io.agentscope.core.tool.Toolkit} (as a template — {@code build()} deep-copies it), and
 * {@link io.agentscope.core.state.AgentStateStore} are all safe to share across instances.
 */
@SuppressWarnings("deprecation")
public class ReActAgent extends AgentBase implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ReActAgent.class);
    private static final GracefulShutdownManager shutdownManager =
            GracefulShutdownManager.getInstance();

    /** Tool name used for the per-call structured-output {@code generate_response} tool. */
    public static final String STRUCTURED_OUTPUT_TOOL_NAME = "generate_response";

    /**
     * @deprecated Permission HITL no longer uses a Reactor Sink. Confirm results are now
     *     delivered via a second {@code agent.call(msgs)} carrying a {@link ConfirmResult}
     *     payload — see {@code applyConfirmResults}. This constant is retained as a
     *     compile-time marker and will be removed in a future release.
     */
    @Deprecated
    public static final String CONFIRM_SINK_KEY = "io.agentscope.core.ReActAgent.confirmSink";

    // ==================== Core Dependencies ====================

    private final String sysPrompt;
    private final Model model;
    private final int maxIters;
    private final ExecutionConfig modelExecutionConfig;
    private final ExecutionConfig toolExecutionConfig;
    private final GenerateOptions generateOptions;

    /**
     * Agent-owned toolkit (a deep copy made at {@code build()} time, isolated per agent instance).
     * Shared across this agent's concurrent calls; per-call structured-output tools are NOT
     * registered here — they live on the per-call {@link CallExecution} scope.
     */
    private final Toolkit toolkit;

    private final ToolExecutionContext toolExecutionContext;

    private final List<MiddlewareBase> middlewares;
    private final boolean enablePendingToolRecovery;

    // ==================== Persistence ====================

    private final AgentStateStore stateStore;

    /**
     * Builder-time fallback {@code sessionId}, used only when a call does not supply a
     * {@code sessionId} via its {@link RuntimeContext}. Each call still picks its own active slot
     * (see {@link #activateSlotForContext(RuntimeContext)}); this is only the fallback when RC
     * carries no per-call session identity.
     */
    private final String defaultSessionId;

    /**
     * Builder-time permission template, applied to every freshly-created slot. Nullable.
     */
    private final PermissionContextState initialPermissionContext;

    // ==================== 2.0 Core Fields ====================

    /** Active per-call RuntimeContext, set during call lifecycle only. */
    private volatile RuntimeContext activeRc;

    /** Cache of state per {@code (userId, sessionId)} slot key. */
    private final ConcurrentHashMap<String, AgentState> stateCache = new ConcurrentHashMap<>();

    /**
     * Per-slot permission engine cache: runtime-added ASK rules accumulate within the owning
     * slot rather than leaking across users / sessions.
     */
    private final ConcurrentHashMap<String, PermissionEngine> permissionEngineCache =
            new ConcurrentHashMap<>();

    private final ModelConfig modelConfig;
    private final ReactConfig reactConfig;

    /**
     * Reactor Context key carrying the {@code streamEvents} event sink into the underlying
     * {@code call()} subscription. The sink is read in {@link #doCall(List)} and bound to the
     * freshly-built {@link CallExecution#eventSink} for that subscription, so concurrent
     * {@code streamEvents} invocations on one agent each carry their own sink (no shared instance
     * field, no race).
     */
    private static final String EVENT_SINK_KEY = "io.agentscope.core.ReActAgent.eventSink";

    @SuppressWarnings("deprecation")
    private final LegacyHookDispatcher hookDispatcher;

    // ==================== Constructor ====================

    private ReActAgent(Builder builder, Toolkit agentToolkit) {
        super(builder.name, builder.description, new ArrayList<>(builder.hooks));

        this.toolkit = agentToolkit != null ? agentToolkit : new Toolkit();
        this.sysPrompt = builder.sysPrompt;
        this.model = builder.model;
        this.maxIters = builder.maxIters;
        this.modelExecutionConfig = builder.modelExecutionConfig;
        this.toolExecutionConfig = builder.toolExecutionConfig;
        this.generateOptions = builder.generateOptions;
        this.toolExecutionContext = builder.toolExecutionContext;
        this.enablePendingToolRecovery = builder.enablePendingToolRecovery;
        List<MiddlewareBase> mws = new ArrayList<>();
        mws.add(new GracefulShutdownMiddleware(shutdownManager));
        mws.addAll(builder.middlewares);
        this.middlewares = List.copyOf(mws);

        this.stateStore = builder.stateStore;
        this.defaultSessionId =
                builder.defaultSessionId != null && !builder.defaultSessionId.isBlank()
                        ? builder.defaultSessionId
                        : (builder.name != null ? builder.name : "ReActAgent");
        this.initialPermissionContext = builder.permissionContext;

        this.modelConfig = assembleModelConfig(builder);
        this.reactConfig = assembleReactConfig(builder);
        this.hookDispatcher = new LegacyHookDispatcher(this);

        if (this.stateStore != null) {
            shutdownManager.bindStateSaver(
                    this,
                    // The saver receives the precise per-(userId, sessionId) AgentState bound to
                    // the
                    // interrupted request, so persist that session directly rather than the
                    // instance "last-active" CallExecution (which is wrong under concurrency).
                    agentState ->
                            stateStore.save(
                                    agentState.getUserId(),
                                    agentState.getSessionId(),
                                    "agent_state",
                                    agentState));
        }
    }

    /**
     * Internal slot identifier — {@code (userId or "__anon__") + "/" + sessionId}.
     * Not part of the public API.
     */
    private static String slotKey(String userId, String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        return (userId == null || userId.isBlank() ? "__anon__" : userId) + "/" + sessionId;
    }

    /** Reverse of {@link #slotKey}: the parsed {@code (userId, sessionId)} pair. */
    private record SlotRef(String userId, String sessionId) {
        static SlotRef parse(String slotKey) {
            int slash = slotKey.lastIndexOf('/');
            String u = slotKey.substring(0, slash);
            String s = slotKey.substring(slash + 1);
            return new SlotRef("__anon__".equals(u) ? null : u, s);
        }
    }

    /**
     * Initial agent-state load for a specific {@code (userId, sessionId)} slot. Tries (in order):
     * the configured {@link AgentStateStore} for an {@code agent_state} entry, the v1 legacy
     * session keys ({@code memory_messages} + {@code toolkit_activeGroups}) via
     * {@link LegacyStateLoader}, and finally a fresh state if neither yields anything.
     */
    private static AgentState loadOrCreateAgentStateForSlot(
            AgentStateStore stateStore,
            String userId,
            String sessionId,
            PermissionContextState permCtx,
            String agentId) {
        AgentState fresh = freshState(permCtx, agentId, userId, sessionId);
        if (stateStore == null) {
            return fresh;
        }
        try {
            return stateStore
                    .get(userId, sessionId, "agent_state", AgentState.class)
                    .orElseGet(
                            () -> {
                                AgentState legacy =
                                        LegacyStateLoader.loadFromLegacySession(
                                                stateStore, userId, sessionId);
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
            log.warn(
                    "Failed to load AgentState for slot (userId={}, sessionId={}): {}",
                    userId,
                    sessionId,
                    e.getMessage());
            return fresh;
        }
    }

    private static AgentState freshState(
            PermissionContextState permCtx, String agentId, String userId, String sessionId) {
        AgentState.Builder asb =
                AgentState.builder().sessionId(sessionId != null ? sessionId : agentId);
        if (userId != null) {
            asb.userId(userId);
        }
        if (permCtx != null) {
            asb.permissionContext(permCtx);
        }
        return asb.build();
    }

    /**
     * Persist the current {@link AgentState} via the configured {@link AgentStateStore}, or {@code
     * Mono.empty()} when no AgentStateStore was provided. Synchronises toolkit activeGroups into the state
     * before writing.
     */
    private Mono<Void> saveStateToSession(CallExecution scope) {
        if (stateStore == null) {
            return Mono.empty();
        }
        syncToolkitToState(scope.state);
        SlotRef ref = SlotRef.parse(scope.slotKey);
        AgentState toSave = scope.state;
        return Mono.<Void>fromRunnable(
                        () -> stateStore.save(ref.userId, ref.sessionId, "agent_state", toSave))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Per-call slot activation. Reads {@code (userId, sessionId)} from the given RuntimeContext
     * (falling back to {@link #defaultSessionId} when absent), and atomically swaps the active
     * {@code #state} + {@code #permissionEngine} to that slot's cached entries.
     *
     * <p>When a {@link AgentStateStore} is configured the state is always reloaded from the store
     * at the beginning of each call so that distributed deployments (where the same sessionId may
     * drift across machines) see the latest persisted state rather than a stale local cache entry.
     * The per-call cost of one store read is negligible compared to the LLM round-trip.
     *
     * <p>Safe to call from {@code beforeAgentExecution} only — caller must hold the
     * {@code AgentBase.acquireExecution} lock.
     */
    private CallExecution activateSlotForContext(RuntimeContext ctx) {
        String sid = ctx != null ? ctx.getSessionId() : null;
        if (sid == null || sid.isBlank()) {
            sid = defaultSessionId;
        }
        String uid = ctx != null ? ctx.getUserId() : null;
        String slot = slotKey(uid, sid);
        final String finalUid = uid;
        final String finalSid = sid;
        AgentState loaded;
        if (stateStore != null) {
            loaded =
                    loadOrCreateAgentStateForSlot(
                            stateStore, finalUid, finalSid, initialPermissionContext, getAgentId());
            stateCache.put(slot, loaded);
        } else {
            loaded =
                    stateCache.computeIfAbsent(
                            slot,
                            k ->
                                    loadOrCreateAgentStateForSlot(
                                            null,
                                            finalUid,
                                            finalSid,
                                            initialPermissionContext,
                                            getAgentId()));
        }
        PermissionEngine loadedEngine;
        if (stateStore != null) {
            loadedEngine = new PermissionEngine(loaded.getPermissionContext());
            permissionEngineCache.put(slot, loadedEngine);
        } else {
            loadedEngine =
                    permissionEngineCache.computeIfAbsent(
                            slot, k -> new PermissionEngine(loaded.getPermissionContext()));
        }
        CallExecution scope = new CallExecution(loaded, loadedEngine, slot);
        if (toolkit != null) {
            toolkit.setActiveGroups(loaded.getToolContext().getActivatedGroups());
        }
        return scope;
    }

    // ==================== Config assembly helpers ====================

    private static ModelConfig assembleModelConfig(Builder b) {
        int retries = b.flatMaxRetries != null ? b.flatMaxRetries : ModelConfig.DEFAULT_MAX_RETRIES;
        return new ModelConfig(retries, b.flatFallbackModel);
    }

    private static ReactConfig assembleReactConfig(Builder b) {
        boolean stop =
                b.flatStopOnReject != null
                        ? b.flatStopOnReject
                        : ReactConfig.DEFAULT_STOP_ON_REJECT;
        return new ReactConfig(b.maxIters, stop);
    }

    // ==================== RuntimeContext ====================

    @Override
    protected Object callSerializationKey(RuntimeContext rc) {
        // Serialize calls per (userId, sessionId) slot: same-session calls share cached AgentState
        // /
        // conversation history, so they must run one-at-a-time; distinct sessions run in parallel.
        String sid = rc != null ? rc.getSessionId() : null;
        if (sid == null || sid.isBlank()) {
            sid = defaultSessionId;
        }
        String uid = rc != null ? rc.getUserId() : null;
        return slotKey(uid, sid);
    }

    @Override
    protected Object beforeAgentExecution(List<Msg> msgs, RuntimeContext rc) {
        RuntimeContext ctx = rc;
        if (ctx == null) {
            ctx = RuntimeContext.empty();
        }
        // Per-call: resolve the (userId, sessionId) slot carried by the RuntimeContext (falling
        // back to the builder-time default when absent) and build a fresh per-call scope bound to
        // that slot's cached state / permissionEngine. The returned reference is the authoritative
        // per-call scope (carried on the Reactor Context); the instance field is only a
        // side-channel default for out-of-call accessors.
        CallExecution scope = activateSlotForContext(ctx);
        // Expose the call-scoped AgentState on the RuntimeContext so middlewares / tools resolve
        // the active session's state via rc.getAgentState() (call-scoped, concurrency-safe)
        // rather than agent.getAgentState() (not call-scoped under concurrency).
        ctx.setAgentState(scope.state);
        this.activeRc = ctx;
        bindRuntimeContextToHooks(ctx);
        // Seed per-call state onto the active execution scope. The system message is initialised
        // by consumeSystemMsgAfterPreCall; the event sink (if any) is bound in doCall() from the
        // per-subscription Reactor Context carried by streamEvents.
        scope.rc = ctx;
        scope.systemMsg = null;
        // Clear any stale interrupt signal for this session before the new call begins.
        scope.state.interruptControl().reset();
        return scope;
    }

    @Override
    protected Msg seedSystemMsg(Object callExectution) {
        RuntimeContext rc =
                callExectution instanceof CallExecution ce ? ce.rc : getRuntimeContext();
        String base = sysPrompt != null ? sysPrompt.trim() : "";
        String prompt = applySystemPromptMiddlewares(base, rc);
        if (prompt == null || prompt.isEmpty()) {
            return null;
        }
        return SystemMessage.builder()
                .name("system")
                .content(TextBlock.builder().text(prompt).build())
                .build();
    }

    @Override
    protected AgentState stateForCall(Object callScope) {
        return callScope instanceof CallExecution ce ? ce.state : getAgentState();
    }

    private String applySystemPromptMiddlewares(String prompt, RuntimeContext ctx) {
        if (middlewares.isEmpty()) {
            return prompt;
        }
        // Only build a reactive chain if at least one middleware overrides onSystemPrompt
        // (the default implementation is identity). This avoids an unnecessary block() call
        // which would fail on non-blocking schedulers (e.g. Reactor parallel scheduler).
        boolean hasOverride = false;
        for (MiddlewareBase mw : middlewares) {
            try {
                if (mw.getClass()
                                .getMethod(
                                        "onSystemPrompt",
                                        Agent.class,
                                        RuntimeContext.class,
                                        String.class)
                                .getDeclaringClass()
                        != MiddlewareBase.class) {
                    hasOverride = true;
                    break;
                }
            } catch (NoSuchMethodException ignored) {
                hasOverride = true;
                break;
            }
        }
        if (!hasOverride) {
            return prompt;
        }
        Mono<String> result = Mono.just(prompt);
        for (MiddlewareBase mw : middlewares) {
            result = result.flatMap(p -> mw.onSystemPrompt(this, ctx, p));
        }
        return result.block();
    }

    @Override
    protected void consumeSystemMsgAfterPreCall(Msg systemMsg, Object callScope) {
        CallExecution ce = (CallExecution) callScope;
        ce.systemMsg = systemMsg;
        syncToolkitToState(ce.state);
    }

    @Override
    protected void afterAgentExecution() {
        this.activeRc = null;
        unbindRuntimeContextFromHooks();
    }

    private RuntimeContext buildMergedRuntimeContext(RuntimeContext run) {
        if (run == null) {
            if (toolExecutionContext != null) {
                return RuntimeContext.builder().toolExecutionContext(toolExecutionContext).build();
            }
            return RuntimeContext.empty();
        }
        if (toolExecutionContext != null) {
            return RuntimeContext.builder(run)
                    .toolExecutionContext(
                            ToolExecutionContext.merge(
                                    run.asToolExecutionContext(), toolExecutionContext))
                    .build();
        }
        return run;
    }

    /**
     * Calls the agent with a per-call {@link RuntimeContext} (metadata for hooks and tools, not
     * persisted).
     */
    public Mono<Msg> call(List<Msg> msgs, RuntimeContext context) {
        return callInternal(msgs, context, this::doCall);
    }

    public Mono<Msg> call(List<Msg> msgs, Class<?> structuredOutputClass, RuntimeContext context) {
        return callInternal(msgs, context, m -> doCall(m, structuredOutputClass));
    }

    public Mono<Msg> call(List<Msg> msgs, JsonNode outputSchema, RuntimeContext context) {
        return callInternal(msgs, context, m -> doCall(m, outputSchema));
    }

    /**
     * Calls the agent with a plain text input and per-call {@link RuntimeContext}.
     *
     * @param text    input text (wrapped into a {@link UserMessage})
     * @param context per-call runtime context
     * @return response message
     */
    public Mono<Msg> call(String text, RuntimeContext context) {
        return call(List.of(new UserMessage(text)), context);
    }

    /**
     * Calls the agent with a plain text input, structured output class, and per-call
     * {@link RuntimeContext}.
     *
     * @param text                 input text (wrapped into a {@link UserMessage})
     * @param structuredOutputClass class defining the structure
     * @param context              per-call runtime context
     * @return response message with structured data in metadata
     */
    public Mono<Msg> call(String text, Class<?> structuredOutputClass, RuntimeContext context) {
        return call(List.of(new UserMessage(text)), structuredOutputClass, context);
    }

    /**
     * Attaches the caller-supplied {@link RuntimeContext} to the Reactor Context of the given
     * publisher under {@link AgentBase#RUNTIME_CONTEXT_KEY}, so the call lifecycle reads it
     * per-subscription (concurrency-safe) rather than from a shared instance field.
     */
    private Mono<Msg> withRuntimeContext(Mono<Msg> mono, RuntimeContext context) {
        return context == null ? mono : mono.contextWrite(c -> c.put(RUNTIME_CONTEXT_KEY, context));
    }

    private Flux<Event> withRuntimeContext(Flux<Event> flux, RuntimeContext context) {
        return context == null ? flux : flux.contextWrite(c -> c.put(RUNTIME_CONTEXT_KEY, context));
    }

    // ==================== Interrupt (per-session) ====================

    /**
     * @deprecated Use {@link #interrupt(String, String)} with explicit userId/sessionId.
     */
    @Deprecated
    @Override
    public void interrupt() {
        interrupt(null, defaultSessionId);
    }

    /** @deprecated Use {@link #interrupt(String, String, Msg)} with explicit userId/sessionId. */
    @Deprecated
    @Override
    public void interrupt(Msg msg) {
        interrupt(null, defaultSessionId, msg);
    }

    /** @deprecated Use {@link #interrupt(String, String)} with explicit userId/sessionId. */
    @Deprecated
    @Override
    public void interrupt(InterruptSource source) {
        AgentState target = stateCache.get(slotKey(null, defaultSessionId));
        if (target != null) {
            target.interruptControl().trigger(source, null);
        }
    }

    /**
     * Interrupts the in-flight call identified by the given {@link RuntimeContext}.
     * Uses {@code ctx.getUserId()} and {@code ctx.getSessionId()} to locate the session.
     *
     * @param ctx the runtime context identifying the session to interrupt
     */
    public void interrupt(RuntimeContext ctx) {
        interrupt(ctx, null);
    }

    /**
     * Interrupts the in-flight call identified by the given {@link RuntimeContext} with an
     * associated user message.
     *
     * @param ctx the runtime context identifying the session to interrupt
     * @param msg optional user message to attach to the interrupt signal
     */
    public void interrupt(RuntimeContext ctx, Msg msg) {
        String uid = ctx != null ? ctx.getUserId() : null;
        String sid = ctx != null ? ctx.getSessionId() : null;
        if (sid == null || sid.isBlank()) {
            sid = defaultSessionId;
        }
        getAgentState(uid, sid).interruptControl().trigger(InterruptSource.USER, msg);
    }

    /**
     * Interrupts the in-flight call for a specific {@code (userId, sessionId)} session.
     *
     * @param userId the user id ({@code null} = anonymous / single-tenant)
     * @param sessionId the session id
     */
    public void interrupt(String userId, String sessionId) {
        interrupt(userId, sessionId, null);
    }

    /**
     * Interrupts the in-flight call for a specific {@code (userId, sessionId)} session with an
     * associated user message.
     */
    public void interrupt(String userId, String sessionId, Msg msg) {
        getAgentState(userId, sessionId).interruptControl().trigger(InterruptSource.USER, msg);
    }

    /**
     * @deprecated since 2.0.0, for removal. Use {@link #streamEvents(List)} (and overloads that
     *     accept {@link RuntimeContext} via {@code call()}-driven lifecycle) for the
     *     fine-grained {@code AgentEvent} stream.
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options, RuntimeContext context) {
        return withRuntimeContext(stream(msgs, options), context);
    }

    /**
     * @deprecated since 2.0.0, for removal. Use {@link #streamEvents(List)} for the
     *     fine-grained {@code AgentEvent} stream.
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    public Flux<Event> stream(
            List<Msg> msgs,
            StreamOptions options,
            Class<?> structuredModel,
            RuntimeContext context) {
        return withRuntimeContext(stream(msgs, options, structuredModel), context);
    }

    /**
     * @deprecated since 2.0.0, for removal. Use {@link #streamEvents(List)} for the
     *     fine-grained {@code AgentEvent} stream.
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    public Flux<Event> stream(
            List<Msg> msgs, StreamOptions options, JsonNode schema, RuntimeContext context) {
        return withRuntimeContext(stream(msgs, options, schema), context);
    }

    // ==================== Shared agent-stream core ====================

    /**
     * Overrides the base-class hook so that every {@code call()} variant — including structured
     * output and context-bearing overloads — runs through the same {@link #buildAgentStream} core
     * as {@code streamEvents()}.  This guarantees that the {@code onAgent} middleware chain fires
     * on <em>all</em> invocation paths, not only on the streaming path.
     *
     * <p>The result is extracted from the {@link AgentResultEvent} emitted by
     * {@link #buildAgentStream} before {@link AgentEndEvent}.
     */
    @Override
    protected Mono<Msg> callInternal(
            List<Msg> msgs, RuntimeContext context, Function<List<Msg>, Mono<Msg>> doCallFn) {
        return buildAgentStream(msgs, context, doCallFn)
                .filter(e -> e instanceof AgentResultEvent)
                .cast(AgentResultEvent.class)
                .map(AgentResultEvent::getResult)
                .takeLast(1)
                .next();
    }

    /**
     * Single implementation shared by both {@code call()} (via {@link #callInternal}) and
     * {@code streamEvents()}.
     *
     * <p>The stream is bookended by {@link AgentStartEvent} / {@link AgentEndEvent}, wraps the
     * full {@link AgentBase#runLifecycle} (shutdown guard, serialization gate, pre/post hooks,
     * tracing), and emits {@link AgentResultEvent} carrying the final {@link Msg} immediately
     * before {@link AgentEndEvent}.  The {@code onAgent} middleware chain is applied exactly
     * once around this core.
     *
     * @param msgs      input messages
     * @param context   caller-supplied per-call {@link RuntimeContext}, or {@code null}
     * @param doCallFn  the concrete call implementation ({@link #doCall} or a structured-output
     *                  variant) passed straight through to {@link AgentBase#runLifecycle}
     * @return event stream covering the full agent invocation lifecycle
     */
    private Flux<AgentEvent> buildAgentStream(
            List<Msg> msgs, RuntimeContext context, Function<List<Msg>, Mono<Msg>> doCallFn) {
        String replyId = UUID.randomUUID().toString().replace("-", "");
        Function<AgentInput, Flux<AgentEvent>> core =
                input ->
                        Flux.<AgentEvent>create(
                                sink -> {
                                    sink.next(new AgentStartEvent(null, replyId, getName()));
                                    reactor.util.context.Context subscriberCtx =
                                            reactor.util.context.Context.of(sink.contextView());

                                    // Call runLifecycle directly — NOT call() — to avoid the
                                    // onAgent chain being applied a second time.
                                    Mono<Msg> lifecycle = runLifecycle(input.msgs(), doCallFn);
                                    if (context != null) {
                                        lifecycle =
                                                lifecycle.contextWrite(
                                                        c -> c.put(RUNTIME_CONTEXT_KEY, context));
                                    }
                                    // Do not install AgentEventEmitter.CONTEXT_KEY when the
                                    // deprecated stream() → SubagentEventBus path is driving
                                    // this invocation. On that path AgentSpawnTool reads
                                    // SubagentEventBus.CONTEXT_KEY to forward child events;
                                    // installing CONTEXT_KEY here would cause execLocalSync to
                                    // take the AgentEvent path instead of the bus path, routing
                                    // child events into this Flux's internal sink where they get
                                    // filtered out by callInternal before reaching the caller.
                                    boolean isSubagentBusPath =
                                            subscriberCtx.hasKey(SubagentEventBus.CONTEXT_KEY);
                                    Disposable lifecycleDisposable =
                                            lifecycle
                                                    .contextWrite(c -> c.put(EVENT_SINK_KEY, sink))
                                                    .contextWrite(
                                                            c ->
                                                                    isSubagentBusPath
                                                                            ? c
                                                                            : c.put(
                                                                                    AgentEventEmitter
                                                                                            .CONTEXT_KEY,
                                                                                    (AgentEventEmitter)
                                                                                            sink
                                                                                                    ::next))
                                                    .doFinally(
                                                            signal -> {
                                                                sink.next(
                                                                        new AgentEndEvent(replyId));
                                                                sink.complete();
                                                            })
                                                    .contextWrite(subscriberCtx)
                                                    .subscribe(
                                                            finalMsg ->
                                                                    sink.next(
                                                                            new AgentResultEvent(
                                                                                    finalMsg)),
                                                            sink::error);
                                    sink.onCancel(lifecycleDisposable);
                                },
                                FluxSink.OverflowStrategy.BUFFER);
        return MiddlewareChain.build(middlewares, this, context, MiddlewareBase::onAgent, core)
                .apply(new AgentInput(msgs == null ? List.of() : msgs));
    }

    // ==================== streamEvents public API ====================

    /**
     * Stream fine-grained {@link AgentEvent}s from the full agent lifecycle.
     *
     * <p>Both {@code call()} and {@code streamEvents()} share the same internal
     * {@link #buildAgentStream} core, so the {@code onAgent} middleware chain fires on all paths.
     * The stream includes {@link AgentResultEvent} (carrying the final {@link Msg}) immediately
     * before {@link AgentEndEvent}.
     *
     * @param msgs input messages
     * @return event stream covering the full agent invocation lifecycle
     */
    public Flux<AgentEvent> streamEvents(List<Msg> msgs) {
        return streamEvents(msgs, (RuntimeContext) null);
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

    /**
     * Stream fine-grained {@link AgentEvent}s with a caller-supplied {@link RuntimeContext}.
     *
     * <p>Delegates directly to {@link #buildAgentStream} — the same core used by {@code call()}.
     * Concurrent invocations do not share any state; each subscription gets its own event sink and
     * lifecycle execution.
     *
     * @param msgs input messages
     * @param context runtime context to propagate into the call
     * @return event stream covering the full agent invocation lifecycle
     */
    public Flux<AgentEvent> streamEvents(List<Msg> msgs, RuntimeContext context) {
        return buildAgentStream(msgs, context, this::doCall);
    }

    /**
     * Stream fine-grained {@link AgentEvent}s for a single input message with a caller-supplied
     * {@link RuntimeContext}.
     *
     * @param msg input message
     * @param context runtime context to propagate into the call
     * @return event stream covering the full agent invocation lifecycle
     */
    public Flux<AgentEvent> streamEvents(Msg msg, RuntimeContext context) {
        return streamEvents(List.of(msg), context);
    }

    /**
     * Stream fine-grained {@link AgentEvent}s for a plain text input.
     *
     * @param text input text (wrapped into a {@link UserMessage})
     * @return event stream covering the full agent invocation lifecycle
     */
    public Flux<AgentEvent> streamEvents(String text) {
        return streamEvents(new UserMessage(text));
    }

    /**
     * Stream fine-grained {@link AgentEvent}s for a plain text input with a caller-supplied
     * {@link RuntimeContext}.
     *
     * @param text    input text (wrapped into a {@link UserMessage})
     * @param context runtime context to propagate into the call
     * @return event stream covering the full agent invocation lifecycle
     */
    public Flux<AgentEvent> streamEvents(String text, RuntimeContext context) {
        return streamEvents(new UserMessage(text), context);
    }

    // ==================== Protected API ====================

    /**
     * Resolves the per-call {@link CallExecution} from the Reactor Context (carried by the shared
     * {@code call()} lifecycle). Falls back to the instance {@link #exec} when absent (e.g. legacy
     * paths that do not flow through {@code call()}), so concurrent calls each operate on their own
     * scope.
     */
    private CallExecution scopeFrom(reactor.util.context.ContextView cv) {
        Object scope = cv.getOrDefault(CALL_SCOPE_KEY, null);
        if (!(scope instanceof CallExecution ce)) {
            throw new IllegalStateException(
                    "No CallExecution in Reactor Context — scopeFrom called outside call"
                            + " lifecycle");
        }
        return ce;
    }

    @Override
    protected Mono<Msg> doCall(List<Msg> msgs) {
        return Mono.deferContextual(
                cv -> {
                    CallExecution scope = scopeFrom(cv);
                    // When a forwarding emitter is present, the parent's tool is routing child
                    // events through a source-tagging wrapper. Skip the direct FluxSink so
                    // publishEvent() uses the forwarding emitter instead.
                    boolean hasForwardingEmitter =
                            cv.hasKey(AgentEventEmitter.FORWARDING_CONTEXT_KEY);
                    if (!hasForwardingEmitter) {
                        Object sink = cv.getOrDefault(EVENT_SINK_KEY, null);
                        if (sink instanceof FluxSink) {
                            @SuppressWarnings("unchecked")
                            FluxSink<AgentEvent> eventSink = (FluxSink<AgentEvent>) sink;
                            scope.eventSink = eventSink;
                        }
                    }
                    if (scope.eventSink == null) {
                        AgentEventEmitter.fromForwardingContext(cv)
                                .ifPresent(ae -> scope.externalEventEmitter = ae);
                    }
                    return scope.doCallInner(msgs)
                            .flatMap(result -> saveStateToSession(scope).thenReturn(result));
                });
    }

    // ==================== Structured output (per-call) ====================

    @Override
    protected Mono<Msg> doCall(List<Msg> msgs, Class<?> structuredOutputClass) {
        return doStructuredCall(msgs, structuredOutputClass, null);
    }

    @Override
    protected Mono<Msg> doCall(List<Msg> msgs, JsonNode outputSchema) {
        return doStructuredCall(msgs, null, outputSchema);
    }

    /**
     * Structured-output call dispatcher. Routes to the native path (model's {@code response_format})
     * when the model supports it, otherwise falls back to the synthetic {@code generate_response}
     * tool approach.
     */
    private Mono<Msg> doStructuredCall(List<Msg> msgs, Class<?> targetClass, JsonNode schemaDesc) {
        if (targetClass == null && schemaDesc == null) {
            return Mono.error(
                    new IllegalArgumentException(
                            "Either targetClass or schemaDesc must be provided"));
        }
        if (targetClass != null && schemaDesc != null) {
            return Mono.error(
                    new IllegalArgumentException("Cannot provide both targetClass and schemaDesc"));
        }
        Map<String, Object> jsonSchema =
                targetClass != null
                        ? JsonSchemaUtils.generateSchemaFromClass(targetClass)
                        : JsonSchemaUtils.generateSchemaFromJsonNode(schemaDesc);
        boolean hasTools = !toolkit.getToolSchemas().isEmpty();
        boolean useNative =
                hasTools
                        ? model.supportsNativeStructuredOutputWithTools()
                        : model.supportsNativeStructuredOutput();
        if (useNative) {
            return doNativeStructuredCall(msgs, jsonSchema);
        }
        return doFallbackStructuredCall(msgs, jsonSchema);
    }

    /**
     * Native structured-output path: passes the schema via {@code response_format} in
     * {@link GenerateOptions}. The model returns structured JSON as text content and the
     * ReAct loop terminates naturally (no synthetic tool needed).
     */
    private Mono<Msg> doNativeStructuredCall(List<Msg> msgs, Map<String, Object> jsonSchema) {
        return Mono.deferContextual(
                cv -> {
                    CallExecution scope = scopeFrom(cv);
                    boolean hasForwardingEmitter =
                            cv.hasKey(AgentEventEmitter.FORWARDING_CONTEXT_KEY);
                    if (!hasForwardingEmitter) {
                        Object sink = cv.getOrDefault(EVENT_SINK_KEY, null);
                        if (sink instanceof FluxSink) {
                            @SuppressWarnings("unchecked")
                            FluxSink<AgentEvent> eventSink = (FluxSink<AgentEvent>) sink;
                            scope.eventSink = eventSink;
                        }
                    }
                    if (scope.eventSink == null) {
                        AgentEventEmitter.fromForwardingContext(cv)
                                .ifPresent(ae -> scope.externalEventEmitter = ae);
                    }

                    scope.nativeResponseFormat =
                            ResponseFormat.jsonSchema(
                                    JsonSchema.builder()
                                            .name(STRUCTURED_OUTPUT_TOOL_NAME)
                                            .schema(jsonSchema)
                                            .strict(true)
                                            .build());

                    return scope.doCallInner(msgs)
                            .flatMap(
                                    result -> {
                                        Msg out = wrapNativeStructuredResult(result);
                                        return saveStateToSession(scope).thenReturn(out);
                                    });
                });
    }

    /**
     * Fallback structured-output path: injects a {@code generate_response} synthetic tool and
     * an instruction hint. When the model calls the tool, the loop stops naturally via
     * {@code PostActingEvent.stopAgent()}.
     */
    private Mono<Msg> doFallbackStructuredCall(List<Msg> msgs, Map<String, Object> jsonSchema) {
        return Mono.deferContextual(
                cv -> {
                    CallExecution scope = scopeFrom(cv);
                    boolean hasForwardingEmitter =
                            cv.hasKey(AgentEventEmitter.FORWARDING_CONTEXT_KEY);
                    if (!hasForwardingEmitter) {
                        Object sink = cv.getOrDefault(EVENT_SINK_KEY, null);
                        if (sink instanceof FluxSink) {
                            @SuppressWarnings("unchecked")
                            FluxSink<AgentEvent> eventSink = (FluxSink<AgentEvent>) sink;
                            scope.eventSink = eventSink;
                        }
                    }
                    if (scope.eventSink == null) {
                        AgentEventEmitter.fromForwardingContext(cv)
                                .ifPresent(ae -> scope.externalEventEmitter = ae);
                    }

                    scope.soTool = createStructuredOutputTool(jsonSchema);

                    return scope.doCallInner(msgs)
                            .flatMap(
                                    result -> {
                                        Msg out = result;
                                        if (scope.soCompleted && scope.soResultMsg != null) {
                                            ChatUsage aggregatedUsage =
                                                    collectAggregatedUsage(scope.state);
                                            ThinkingBlock aggregatedThinking =
                                                    collectLastThinking(scope.state);
                                            compressStructuredOutputContext(scope.state);
                                            Msg extracted =
                                                    extractStructuredResult(scope.soResultMsg);
                                            if (extracted != null) {
                                                out =
                                                        mergeCollectedMetadata(
                                                                extracted,
                                                                aggregatedUsage,
                                                                aggregatedThinking);
                                            }
                                            scope.state.contextMutable().add(out);
                                        }
                                        return saveStateToSession(scope).thenReturn(out);
                                    });
                });
    }

    private Msg wrapNativeStructuredResult(Msg result) {
        if (result == null) {
            return null;
        }
        String text = result.getTextContent();
        if (text == null || text.isBlank()) {
            return result;
        }
        try {
            Object parsed =
                    io.agentscope.core.util.JsonUtils.getJsonCodec().fromJson(text, Object.class);
            Map<String, Object> metadata =
                    new HashMap<>(result.getMetadata() != null ? result.getMetadata() : Map.of());
            metadata.put(MessageMetadataKeys.STRUCTURED_OUTPUT, parsed);
            return Msg.builderForRole(result.getRole())
                    .id(result.getId())
                    .name(result.getName())
                    .content(result.getContent())
                    .metadata(metadata)
                    .timestamp(result.getTimestamp())
                    .build();
        } catch (Exception e) {
            log.warn("Failed to parse native structured output as JSON: {}", e.getMessage());
            return result;
        }
    }

    /**
     * Remove structured-output-related messages from the conversation context and append
     * the final response.
     */
    private void compressStructuredOutputContext(AgentState agentState) {
        List<Msg> contextMutable = agentState.contextMutable();
        List<Msg> original = new ArrayList<>(contextMutable);
        contextMutable.clear();
        for (Msg msg : original) {
            if (!isStructuredOutputRelated(msg)) {
                contextMutable.add(msg);
            }
        }
    }

    private boolean isStructuredOutputRelated(Msg msg) {
        Map<String, Object> metadata = msg.getMetadata();
        if (metadata != null
                && Boolean.TRUE.equals(
                        metadata.get(MessageMetadataKeys.STRUCTURED_OUTPUT_REMINDER))) {
            return true;
        }
        if (msg.getContentBlocks(ToolUseBlock.class).stream()
                .anyMatch(tu -> STRUCTURED_OUTPUT_TOOL_NAME.equals(tu.getName()))) {
            return true;
        }
        List<ToolResultBlock> results = msg.getContentBlocks(ToolResultBlock.class);
        return !results.isEmpty()
                && results.stream()
                        .allMatch(tr -> STRUCTURED_OUTPUT_TOOL_NAME.equals(tr.getName()));
    }

    private ChatUsage collectAggregatedUsage(AgentState agentState) {
        int totalInput = 0;
        int totalOutput = 0;
        int totalCached = 0;
        double totalTime = 0;
        boolean hasUsage = false;
        for (Msg msg : agentState.getContext()) {
            if (isStructuredOutputRelated(msg) && msg.getRole() == MsgRole.ASSISTANT) {
                ChatUsage usage = msg.getChatUsage();
                if (usage != null) {
                    hasUsage = true;
                    totalInput += usage.getInputTokens();
                    totalOutput += usage.getOutputTokens();
                    totalCached += usage.getCachedTokens();
                    totalTime += usage.getTime();
                }
            }
        }
        return hasUsage
                ? ChatUsage.builder()
                        .inputTokens(totalInput)
                        .outputTokens(totalOutput)
                        .cachedTokens(totalCached)
                        .time(totalTime)
                        .build()
                : null;
    }

    private ThinkingBlock collectLastThinking(AgentState agentState) {
        ThinkingBlock last = null;
        for (Msg msg : agentState.getContext()) {
            if (isStructuredOutputRelated(msg) && msg.getRole() == MsgRole.ASSISTANT) {
                ThinkingBlock tb = msg.getFirstContentBlock(ThinkingBlock.class);
                if (tb != null) {
                    last = tb;
                }
            }
        }
        return last;
    }

    /**
     * Build the per-call {@code generate_response} tool that captures the model's structured
     * response. The tool only stores the raw response payload; schema validation is performed by
     * the tool executor before this is invoked.
     */
    private AgentTool createStructuredOutputTool(Map<String, Object> schema) {
        return new AgentTool() {
            @Override
            public String getName() {
                return STRUCTURED_OUTPUT_TOOL_NAME;
            }

            @Override
            public String getDescription() {
                return "Generate the final structured response. Call this function when"
                        + " you have all the information needed to provide a complete answer.";
            }

            @Override
            public Map<String, Object> getParameters() {
                Map<String, Object> params = new HashMap<>();
                params.put("type", "object");

                // Shallow-copy the inner schema so we can safely hoist $defs to the outer params
                // root without mutating the shared `schema` instance.
                Map<String, Object> innerSchema = new HashMap<>(schema);
                Map<String, Object> hoistedDefs = new HashMap<>();
                hoistDefsKey(innerSchema, "$defs", hoistedDefs);
                hoistDefsKey(innerSchema, "definitions", hoistedDefs);

                params.put("properties", Map.of("response", innerSchema));
                params.put("required", List.of("response"));
                if (!hoistedDefs.isEmpty()) {
                    params.put("$defs", hoistedDefs);
                }
                return params;
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.fromCallable(
                        () -> {
                            Object responseData = param.getInput().get("response");
                            String contentText = "";
                            if (responseData != null) {
                                try {
                                    contentText = JsonUtils.getJsonCodec().toJson(responseData);
                                } catch (Exception e) {
                                    contentText = responseData.toString();
                                }
                            }
                            log.debug("Structured output generated: {}", contentText);

                            Msg responseMsg =
                                    AssistantMessage.builder()
                                            .name(getName())
                                            .content(TextBlock.builder().text(contentText).build())
                                            .metadata(
                                                    responseData != null
                                                            ? Map.of("response", responseData)
                                                            : Map.of())
                                            .build();

                            Map<String, Object> toolMetadata = new HashMap<>();
                            toolMetadata.put("success", true);
                            toolMetadata.put("response_msg", responseMsg);

                            return ToolResultBlock.of(
                                    List.of(
                                            TextBlock.builder()
                                                    .text("Successfully generated response.")
                                                    .build()),
                                    toolMetadata);
                        });
            }
        };
    }

    /** Extract the structured result message from the {@code generate_response} tool result. */
    private Msg extractStructuredResult(Msg hookResultMsg) {
        if (hookResultMsg == null) {
            return null;
        }
        List<ToolResultBlock> toolResults = hookResultMsg.getContentBlocks(ToolResultBlock.class);
        for (ToolResultBlock result : toolResults) {
            if (result.getMetadata() != null
                    && Boolean.TRUE.equals(result.getMetadata().get("success"))
                    && result.getMetadata().containsKey("response_msg")) {
                Object responseMsgObj = result.getMetadata().get("response_msg");
                if (responseMsgObj instanceof Msg responseMsg) {
                    return extractResponseData(responseMsg);
                }
            }
        }
        return hookResultMsg;
    }

    private Msg extractResponseData(Msg responseMsg) {
        if (responseMsg.getMetadata() != null
                && responseMsg.getMetadata().containsKey("response")) {
            Object responseData = responseMsg.getMetadata().get("response");
            Map<String, Object> metadata = new HashMap<>(responseMsg.getMetadata());
            metadata.put(MessageMetadataKeys.STRUCTURED_OUTPUT, responseData);
            metadata.remove("response");
            return Msg.builderForRole(responseMsg.getRole())
                    .name(responseMsg.getName())
                    .content(responseMsg.getContent())
                    .metadata(metadata)
                    .build();
        }
        return responseMsg;
    }

    /** Merge aggregated {@link ChatUsage} / {@link ThinkingBlock} into the final message. */
    private Msg mergeCollectedMetadata(Msg msg, ChatUsage chatUsage, ThinkingBlock thinking) {
        Map<String, Object> metadata =
                new HashMap<>(msg.getMetadata() != null ? msg.getMetadata() : Map.of());
        if (chatUsage != null) {
            metadata.put(MessageMetadataKeys.CHAT_USAGE, chatUsage);
        }

        List<ContentBlock> newContent;
        if (thinking != null) {
            newContent = new ArrayList<>();
            newContent.add(thinking);
            if (msg.getContent() != null) {
                newContent.addAll(msg.getContent());
            }
        } else {
            newContent = msg.getContent();
        }

        return Msg.builderForRole(msg.getRole())
                .id(msg.getId())
                .name(msg.getName())
                .content(newContent)
                .metadata(metadata)
                .timestamp(msg.getTimestamp())
                .build();
    }

    /** Hoist {@code $defs}/{@code definitions} from a nested schema up to the params root. */
    @SuppressWarnings("unchecked")
    private static void hoistDefsKey(
            Map<String, Object> innerSchema, String key, Map<String, Object> target) {
        Object raw = innerSchema.remove(key);
        if (raw instanceof Map<?, ?> defs && !defs.isEmpty()) {
            target.putAll((Map<String, Object>) defs);
        }
    }

    /**
     * Per-call execution scope: holds the active {@code (userId, sessionId)} slot's mutable
     * {@link AgentState} + {@link PermissionEngine} + slot key, and hosts the entire ReAct
     * reasoning loop. Non-static inner class so the loop references the enclosing agent's
     * immutable config ({@code model}, {@code toolkit}, {@code middlewares}, …) and lifecycle
     * helpers directly. Built per-call by {@link #activateSlotForContext(RuntimeContext)}.
     */
    final class CallExecution {
        AgentState state;
        PermissionEngine permissionEngine;
        String slotKey;

        /**
         * Per-call system message, propagated across PreCallEvent → PreReasoningEvent /
         * PreSummaryEvent. Owned by a single logical execution: seeded to {@code null} at call
         * entry ({@code #beforeAgentExecution(List)}) and set by
         * {@link #consumeSystemMsgAfterPreCall(Msg, Object)}.
         */
        Msg systemMsg;

        /**
         * Per-call event sink for {@code streamEvents}. Bound in {@link ReActAgent#doCall(List)}
         * from the per-subscription Reactor Context ({@code EVENT_SINK_KEY}) and read by
         * {@link #publishEvent}.
         */
        FluxSink<AgentEvent> eventSink;

        /**
         * External event emitter for child-agent event forwarding. When a parent's tool (e.g.
         * {@code agent_spawn}) injects a forwarding emitter via
         * {@link AgentEventEmitter#FORWARDING_CONTEXT_KEY}, this child agent uses it to push
         * events into the parent's {@code streamEvents()} stream with source tagging. Takes
         * effect only when {@link #eventSink} is null.
         */
        AgentEventEmitter externalEventEmitter;

        /**
         * The call's {@link RuntimeContext} (caller-supplied metadata for hooks / tools). Set once
         * at call entry; read directly by the reasoning loop instead of the instance-level
         * {@code rc} so the loop is self-contained per call.
         */
        RuntimeContext rc;

        /**
         * Per-call structured-output tool (the {@code generate_response} tool). Non-null only for
         * fallback structured-output calls (when the model does not support native structured
         * output). Lives on this scope rather than the shared toolkit so concurrent
         * structured-output calls do not collide.
         */
        AgentTool soTool;

        /** Set to {@code true} when the {@code generate_response} tool completes successfully. */
        boolean soCompleted;

        /** The tool result message from the successful {@code generate_response} call. */
        Msg soResultMsg;

        /** Native structured-output format set on the per-call scope for native-path calls. */
        ResponseFormat nativeResponseFormat;

        CallExecution(AgentState state, PermissionEngine permissionEngine, String slotKey) {
            this.state = state;
            this.permissionEngine = permissionEngine;
            this.slotKey = slotKey;
        }

        /**
         * Per-call interrupt checkpoint: reads this call's session-scoped {@link InterruptControl}
         * (on its {@link AgentState}) so a targeted {@code interrupt(userId, sessionId)} only aborts
         * the matching session's in-flight call, not other concurrent calls on the same agent.
         */
        private Mono<Void> checkInterrupted() {
            return Mono.defer(
                    () ->
                            state.interruptControl().isInterrupted()
                                    ? Mono.error(
                                            new InterruptedException("Agent execution interrupted"))
                                    : Mono.empty());
        }

        private Mono<Msg> doCallInner(List<Msg> msgs) {
            // Graceful-shutdown deduplication: if the agent's session was previously interrupted
            // by shutdown, the client is likely retrying with the same user prompt that already
            // exists in memory. Discard the duplicate input so the agent resumes purely from its
            // saved memory context.
            if (shutdownManager.checkAndClearShutdownInterrupted(ReActAgent.this)) {
                log.info(
                        "Detected shutdown-interrupted session for agent {}, discarding duplicate"
                                + " input",
                        getName());
                msgs = List.of();
            }

            // Pending-tool-call recovery: auto-patch orphaned pending tool calls with synthetic
            // error results so the agent can continue instead of crashing.
            if (enablePendingToolRecovery) {
                maybePatchPendingToolCalls(msgs);
            }

            Set<String> pendingIds = getPendingToolUseIds();

            // No pending tools -> normal processing
            if (pendingIds.isEmpty()) {
                addToContext(msgs);
                return coreAgent();
            }

            // Permission HITL: if any pending tool is ASKING, the caller MUST supply
            // ConfirmResults (via Msg.METADATA_CONFIRM_RESULTS) before we can proceed.
            List<ToolUseBlock> asking = askingToolCalls();
            if (!asking.isEmpty()) {
                List<ConfirmResult> confirmResults = extractConfirmResults(msgs);
                if (confirmResults.isEmpty()) {
                    String pendingSummary =
                            asking.stream()
                                    .map(t -> t.getName() + " (id=" + t.getId() + ")")
                                    .collect(Collectors.joining(", "));
                    throw new IllegalStateException(
                            "Agent is paused for human-in-the-loop confirmation: the following"
                                    + " tool call(s) are in ASKING state and need your approval"
                                    + " before the agent can continue: ["
                                    + pendingSummary
                                    + "]. This call supplied no confirmation, so it cannot"
                                    + " proceed.\n"
                                    + "To resume, send a follow-up message that carries a"
                                    + " List<ConfirmResult> under the metadata key \""
                                    + Msg.METADATA_CONFIRM_RESULTS
                                    + "\", e.g.:\n"
                                    + "    UserMessage.builder()\n"
                                    + "        .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS,\n"
                                    + "            List.of(new ConfirmResult(true, toolCall))))\n"
                                    + "        .build();\n"
                                    + "Tip: capture the ToolUseBlocks from the"
                                    + " RequireUserConfirmEvent emitted when the agent paused.\n"
                                    + "If you did NOT expect a pending confirmation here, a"
                                    + " previous run most likely paused on one of these tool calls"
                                    + " and persisted that state under the same (agentId,"
                                    + " sessionId); start a fresh session, clear the persisted"
                                    + " state, or use an in-memory state store to begin clean.");
                }
                applyConfirmResults(confirmResults);
                return resumeAgent();
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

            // Recovery was disabled and user did not provide tool results — unrecoverable.
            throw new IllegalStateException(
                    "Pending tool calls exist without results. "
                            + "Enable enablePendingToolRecovery or provide tool results. "
                            + "Pending IDs: "
                            + pendingIds);
        }

        /**
         * Pull all {@link ConfirmResult}s out of the {@link Msg#METADATA_CONFIRM_RESULTS} metadata
         * key across the incoming message list.
         */
        @SuppressWarnings("unchecked")
        private List<ConfirmResult> extractConfirmResults(List<Msg> msgs) {
            if (msgs == null || msgs.isEmpty()) {
                return List.of();
            }
            List<ConfirmResult> collected = new ArrayList<>();
            for (Msg m : msgs) {
                Object raw =
                        m.getMetadata() == null
                                ? null
                                : m.getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
                if (raw instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof ConfirmResult cr) {
                            collected.add(cr);
                        }
                    }
                }
            }
            return collected;
        }

        /**
         * Apply user confirmation results to the ASKING tool calls in context.
         *
         * <p>For each result:
         * <ul>
         *   <li>{@code confirmed == true}: replace the ASKING ToolUseBlock with the (possibly
         *       modified) one from the result, set state to {@link ToolCallState#ALLOWED}, and
         *       register any attached {@link PermissionRule}s with the engine.</li>
         *   <li>{@code confirmed == false}: write a DENIED {@link ToolResultBlock} to context so
         *       the tool will no longer be pending on resume.</li>
         * </ul>
         */
        private void applyConfirmResults(List<ConfirmResult> results) {
            // Replace ASKING ToolUseBlocks with possibly-modified ones from the user, and
            // promote them to ALLOWED. Collect denied ones for separate handling.
            List<ToolUseBlock> deniedToolCalls = new ArrayList<>();
            Map<String, ToolUseBlock> replacements = new HashMap<>();
            Map<String, ToolCallState> stateUpdates = new HashMap<>();
            for (ConfirmResult r : results) {
                ToolUseBlock target = r.getToolCall();
                if (target == null) {
                    continue;
                }
                if (r.isConfirmed()) {
                    replacements.put(target.getId(), target.withState(ToolCallState.ALLOWED));
                    stateUpdates.put(target.getId(), ToolCallState.ALLOWED);
                    if (r.getRules() != null) {
                        for (PermissionRule rule : r.getRules()) {
                            if (rule != null) {
                                permissionEngine.addRule(rule);
                            }
                        }
                    }
                } else {
                    deniedToolCalls.add(target);
                }
            }
            applyToolUseBlockReplacements(replacements);
            for (ToolUseBlock denied : deniedToolCalls) {
                ToolResultBlock deniedResult =
                        ToolResultBlock.text("Permission denied by user")
                                .withIdAndName(denied.getId(), denied.getName())
                                .withState(ToolResultState.DENIED);
                Msg deniedMsg =
                        ToolResultMessageBuilder.buildToolResultMsg(
                                deniedResult, denied, getName());
                state.contextMutable().add(deniedMsg);
            }
        }

        /**
         * Locate the last assistant Msg and substitute {@code ToolUseBlock}s in-place when their id
         * appears in {@code replacements}.
         */
        private void applyToolUseBlockReplacements(Map<String, ToolUseBlock> replacements) {
            if (replacements == null || replacements.isEmpty()) {
                return;
            }
            List<Msg> ctx = state.contextMutable();
            for (int i = ctx.size() - 1; i >= 0; i--) {
                Msg m = ctx.get(i);
                if (m.getRole() != MsgRole.ASSISTANT) {
                    continue;
                }
                boolean hasMatch =
                        m.getContent().stream()
                                .anyMatch(
                                        b ->
                                                b instanceof ToolUseBlock t
                                                        && replacements.containsKey(t.getId()));
                if (!hasMatch) {
                    continue;
                }
                List<ContentBlock> rebuilt = new ArrayList<>(m.getContent().size());
                for (ContentBlock block : m.getContent()) {
                    if (block instanceof ToolUseBlock t && replacements.containsKey(t.getId())) {
                        rebuilt.add(replacements.get(t.getId()));
                    } else {
                        rebuilt.add(block);
                    }
                }
                ctx.set(i, m.withContent(rebuilt));
                return;
            }
        }

        private void maybePatchPendingToolCalls(List<Msg> msgs) {
            Set<String> pendingIds = getPendingToolUseIds();
            if (pendingIds.isEmpty()) {
                return;
            }
            if (msgs == null || msgs.isEmpty()) {
                return;
            }
            boolean userProvidedResults =
                    msgs.stream().anyMatch(m -> m.hasContentBlocks(ToolResultBlock.class));
            if (userProvidedResults) {
                return;
            }
            log.warn(
                    "Pending tool calls detected without results, auto-generating error results."
                            + " Pending IDs: {}",
                    pendingIds);
            Msg lastAssistant = findLastAssistantMsg();
            if (lastAssistant == null) {
                return;
            }
            List<ToolUseBlock> pendingToolCalls =
                    lastAssistant.getContentBlocks(ToolUseBlock.class).stream()
                            .filter(toolUse -> pendingIds.contains(toolUse.getId()))
                            .toList();
            for (ToolUseBlock toolCall : pendingToolCalls) {
                ToolResultBlock errorResult =
                        buildErrorToolResult(
                                toolCall.getId(),
                                "[ERROR] Previous tool execution failed or was interrupted. Tool: "
                                        + toolCall.getName());
                Msg toolResultMsg =
                        ToolResultMessageBuilder.buildToolResultMsg(
                                errorResult, toolCall, getName());
                state.contextMutable().add(toolResultMsg);
                log.info(
                        "Auto-generated error result for pending tool call: {} ({})",
                        toolCall.getName(),
                        toolCall.getId());
            }
        }

        private void publishEvent(AgentEvent event) {
            FluxSink<AgentEvent> sink = eventSink;
            if (sink != null) {
                sink.next(event);
            } else if (externalEventEmitter != null) {
                externalEventEmitter.emit(event);
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

            return checkInterrupted()
                    .then(
                            hookDispatcher.firePreReasoning(
                                    state.contextMutable(), systemMsg, model.getModelName()))
                    .flatMap(
                            event -> {
                                GenerateOptions options =
                                        event.getEffectiveGenerateOptions() != null
                                                ? event.getEffectiveGenerateOptions()
                                                : buildGenerateOptions();
                                if (nativeResponseFormat != null) {
                                    options =
                                            GenerateOptions.mergeOptions(
                                                    GenerateOptions.builder()
                                                            .responseFormat(nativeResponseFormat)
                                                            .build(),
                                                    options);
                                }
                                List<Msg> modelInput =
                                        prependSystemMsg(
                                                event.getInputMessages(), event.getSystemMessage());
                                List<ToolSchema> tools =
                                        toolkit.getToolSchemas(
                                                state.getToolContext().getActivatedGroups());
                                // Per-call structured-output tool: expose generate_response to the
                                // model for this call only (not registered on the shared toolkit).
                                if (soTool != null) {
                                    tools = new ArrayList<>(tools);
                                    tools.add(
                                            ToolSchema.builder()
                                                    .name(soTool.getName())
                                                    .description(soTool.getDescription())
                                                    .parameters(soTool.getParameters())
                                                    .strict(soTool.getStrict())
                                                    .outputSchema(soTool.getOutputSchema())
                                                    .build());
                                }
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
                                                        rc,
                                                        MiddlewareBase::onReasoning,
                                                        reasoningCore)
                                                .apply(
                                                        new ReasoningInput(
                                                                modelInput, tools, options));
                                // Track any RequestStopEvent emitted by middlewares while still
                                // exhausting the stream (publishEvent already fires on each event).
                                AtomicReference<RequestStopEvent> stopRequested =
                                        new AtomicReference<>();
                                return stream.doOnNext(
                                                ev -> {
                                                    if (ev instanceof RequestStopEvent rs) {
                                                        stopRequested.compareAndSet(null, rs);
                                                    }
                                                })
                                        .then(
                                                Mono.defer(
                                                        () -> {
                                                            Msg finalMsg =
                                                                    context.buildFinalMessage();
                                                            RequestStopEvent rs =
                                                                    stopRequested.get();
                                                            if (rs != null && finalMsg != null) {
                                                                // Persist the reasoning message
                                                                // before
                                                                // returning so the next call can
                                                                // resume
                                                                // from pending tool calls.
                                                                state.contextMutable()
                                                                        .add(finalMsg);
                                                                return Mono.just(
                                                                        finalMsg.withGenerateReason(
                                                                                rs
                                                                                        .getGenerateReason()));
                                                            }
                                                            return Mono.justOrEmpty(finalMsg);
                                                        }));
                            })
                    .onErrorResume(
                            InterruptedException.class,
                            error -> {
                                Msg msg = context.buildFinalMessage();
                                if (msg != null) {
                                    boolean discard =
                                            state.interruptControl().getSource()
                                                            == InterruptSource.SYSTEM
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
                    .flatMap(
                            msg -> {
                                // Short-circuit: middleware requested stop during reasoning. The
                                // msg
                                // is already persisted to context and tagged with the correct
                                // GenerateReason; skip legacy postReasoning hook.
                                if (msg.getGenerateReason()
                                                == GenerateReason.MIDDLEWARE_STOP_REQUESTED
                                        || msg.getGenerateReason()
                                                == GenerateReason.PERMISSION_ASKING) {
                                    return Mono.just(msg);
                                }
                                return runPostReasoningPipeline(msg, iter);
                            });
        }

        @SuppressWarnings("deprecation")
        private Mono<Msg> runPostReasoningPipeline(Msg msg, int iter) {
            return hookDispatcher
                    .firePostReasoning(msg, model.getModelName())
                    .flatMap(
                            event -> {
                                Msg eventMsg = event.getReasoningMessage();
                                if (eventMsg != null) {
                                    state.contextMutable().add(eventMsg);
                                }

                                // HITL stop
                                if (event.isStopRequested()) {
                                    if (eventMsg == null) {
                                        return Mono.empty();
                                    }
                                    return Mono.just(
                                            eventMsg.withGenerateReason(
                                                    GenerateReason.REASONING_STOP_REQUESTED));
                                }

                                // gotoReasoning requested (e.g., by a PostReasoning hook)
                                if (event.isGotoReasoningRequested()) {
                                    List<Msg> gotoMsgs = event.getGotoReasoningMsgs();
                                    if (gotoMsgs != null) {
                                        state.contextMutable().addAll(gotoMsgs);
                                    }
                                    return reasoning(iter + 1, true);
                                }

                                // Check finish conditions
                                if (isFinished(eventMsg)) {
                                    return Mono.justOrEmpty(eventMsg);
                                }

                                // Continue to acting
                                return checkInterrupted().then(acting(iter));
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

            return MiddlewareChain.build(
                            middlewares,
                            ReActAgent.this,
                            rc,
                            MiddlewareBase::onModelCall,
                            modelCallCore)
                    .apply(new ModelCallInput(messages, tools, options, model))
                    .doOnNext(this::publishEvent);
        }

        private Flux<AgentEvent> modelCallStream(
                ReasoningContext context, ModelCallInput mci, boolean withToolEvents) {

            String replyId = UUID.randomUUID().toString().replace("-", "");
            ModelCallBlockLifecycle blockLifecycle = new ModelCallBlockLifecycle(replyId);

            Flux<AgentEvent> modelEvents =
                    mci.model().stream(mci.messages(), mci.tools(), mci.options())
                            .concatMap(chunk -> checkInterrupted().thenReturn(chunk))
                            .concatMap(
                                    chunk ->
                                            Flux.deferContextual(
                                                    parentCtx -> {
                                                        List<Msg> chunkMsgs =
                                                                context.processChunk(chunk);
                                                        for (Msg msg : chunkMsgs) {
                                                            hookDispatcher
                                                                    .fireReasoningChunk(
                                                                            msg,
                                                                            context,
                                                                            mci.model()
                                                                                    .getModelName())
                                                                    .contextWrite(
                                                                            ctx ->
                                                                                    ctx.putAll(
                                                                                            parentCtx))
                                                                    .subscribe();
                                                        }

                                                        List<AgentEvent> events = new ArrayList<>();
                                                        for (ContentBlock block :
                                                                chunk.getContent()) {
                                                            emitBlockEvents(
                                                                    block,
                                                                    context,
                                                                    blockLifecycle,
                                                                    withToolEvents,
                                                                    events);
                                                        }
                                                        return Flux.fromIterable(events);
                                                    }));

            Flux<AgentEvent> endEvents =
                    Flux.defer(
                            () -> {
                                List<AgentEvent> events = new ArrayList<>();
                                blockLifecycle.flushAll(events);
                                events.add(new ModelCallEndEvent(replyId, context.getChatUsage()));
                                return Flux.fromIterable(events);
                            });

            return Flux.concat(Flux.just(new ModelCallStartEvent(replyId)), modelEvents, endEvents);
        }

        private void emitBlockEvents(
                ContentBlock block,
                ReasoningContext context,
                ModelCallBlockLifecycle blockLifecycle,
                boolean withToolEvents,
                List<AgentEvent> events) {

            if (block instanceof TextBlock tb) {
                blockLifecycle.startText(events);
                if (tb.getText() != null && !tb.getText().isEmpty()) {
                    events.add(
                            new TextBlockDeltaEvent(blockLifecycle.replyId, "text", tb.getText()));
                }
            } else if (block instanceof ThinkingBlock tb) {
                blockLifecycle.startThinking(events);
                if (tb.getThinking() != null && !tb.getThinking().isEmpty()) {
                    events.add(
                            new ThinkingBlockDeltaEvent(
                                    blockLifecycle.replyId, "thinking", tb.getThinking()));
                }
            } else if (withToolEvents && block instanceof ToolUseBlock tub) {
                String toolId = resolveToolCallId(tub, context);
                String toolName = tub.getName();
                blockLifecycle.startToolCall(toolId, toolName, events);
                if (tub.getContent() != null && !tub.getContent().isEmpty()) {
                    events.add(
                            new ToolCallDeltaEvent(
                                    blockLifecycle.replyId,
                                    toolId != null ? toolId : "",
                                    toolName,
                                    tub.getContent()));
                }
            }
        }

        /**
         * Tracks block lifecycle within one model-call subscription.
         *
         * <p>The model stream is consumed through {@code concatMap}, but the state holders keep the
         * previous thread-safe shape because model providers may deliver chunk content
         * unpredictably. This helper only changes when pending end events are flushed; it does not
         * change the block identity or event payloads.
         */
        private final class ModelCallBlockLifecycle {
            private final String replyId;
            private final AtomicBoolean textStarted = new AtomicBoolean(false);
            private final AtomicBoolean thinkingStarted = new AtomicBoolean(false);
            private final Map<String, String> startedToolCalls = new ConcurrentHashMap<>();

            private ModelCallBlockLifecycle(String replyId) {
                this.replyId = replyId;
            }

            private void startText(List<AgentEvent> events) {
                flushThinking(events);
                if (textStarted.compareAndSet(false, true)) {
                    events.add(new TextBlockStartEvent(replyId, "text"));
                }
            }

            private void startThinking(List<AgentEvent> events) {
                if (thinkingStarted.compareAndSet(false, true)) {
                    events.add(new ThinkingBlockStartEvent(replyId, "thinking"));
                }
            }

            private void startToolCall(String toolId, String toolName, List<AgentEvent> events) {
                if (toolId == null || startedToolCalls.containsKey(toolId)) {
                    return;
                }
                flushText(events);
                flushThinking(events);
                flushAllToolCalls(events);
                boolean visibleTool = toolName != null && !toolName.startsWith("__");
                if (visibleTool && startedToolCalls.putIfAbsent(toolId, toolName) == null) {
                    events.add(new ToolCallStartEvent(replyId, toolId, toolName));
                }
            }

            private void flushText(List<AgentEvent> events) {
                if (textStarted.compareAndSet(true, false)) {
                    events.add(new TextBlockEndEvent(replyId, "text"));
                }
            }

            private void flushThinking(List<AgentEvent> events) {
                if (thinkingStarted.compareAndSet(true, false)) {
                    events.add(new ThinkingBlockEndEvent(replyId, "thinking"));
                }
            }

            private void flushAllToolCalls(List<AgentEvent> events) {
                for (Map.Entry<String, String> tc : startedToolCalls.entrySet()) {
                    events.add(new ToolCallEndEvent(replyId, tc.getKey(), tc.getValue()));
                }
                startedToolCalls.clear();
            }

            private void flushAll(List<AgentEvent> events) {
                flushText(events);
                flushThinking(events);
                flushAllToolCalls(events);
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
                    new AtomicReference<>(List.of());

            AtomicReference<RequestStopEvent> actingStopRequested = new AtomicReference<>();
            return hookDispatcher
                    .firePreActing(pendingToolCalls, toolkit)
                    .flatMap(
                            toolCalls -> {
                                Function<ActingInput, Flux<AgentEvent>> actingCore =
                                        ai -> actingStream(ai.toolCalls(), replyId, resultHolder);
                                Flux<AgentEvent> stream =
                                        MiddlewareChain.build(
                                                        middlewares,
                                                        ReActAgent.this,
                                                        rc,
                                                        MiddlewareBase::onActing,
                                                        actingCore)
                                                .apply(new ActingInput(toolCalls));
                                return stream.doOnNext(
                                                ev -> {
                                                    if (ev instanceof RequestStopEvent rs) {
                                                        actingStopRequested.compareAndSet(null, rs);
                                                    }
                                                })
                                        .then(Mono.defer(() -> Mono.just(resultHolder.get())));
                            })
                    .flatMap(
                            results -> {
                                // Middleware requested stop during acting — return immediately with
                                // the requested GenerateReason, preserving any results already
                                // collected.
                                RequestStopEvent rs = actingStopRequested.get();
                                if (rs != null) {
                                    Msg stopMsg = buildStopMsg(results, rs.getGenerateReason());
                                    return Mono.just(stopMsg);
                                }
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

                                                    syncToolkitToState(state);
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

                                // Mark ToolUseBlock.state in context for every gated tool. ALLOWED
                                // calls run immediately; ASKING calls cause the agent to pause and
                                // return; DENIED calls get DENIED ToolResultBlocks written below.
                                Map<String, ToolCallState> stateUpdates = new HashMap<>();
                                for (ToolUseBlock tc : toolCalls) {
                                    if (autoDenied.contains(tc.getId())) {
                                        // DENIED tools don't need a state change — they'll get a
                                        // DENIED ToolResultBlock and won't reappear in pending.
                                        continue;
                                    }
                                    stateUpdates.put(
                                            tc.getId(),
                                            pending.stream()
                                                            .anyMatch(
                                                                    p ->
                                                                            p.getId()
                                                                                    .equals(
                                                                                            tc
                                                                                                    .getId()))
                                                    ? ToolCallState.ASKING
                                                    : ToolCallState.ALLOWED);
                                }
                                updateToolCallStates(stateUpdates);

                                if (pending.isEmpty()) {
                                    return runToolBatch(
                                            toolCalls, autoDenied, replyId, resultHolder);
                                }

                                // Permission HITL: surface the pending tool calls, persist any
                                // auto-denied results so the second call can identify which ones
                                // still need confirmation, then signal stop via RequestStopEvent.
                                // The agent's acting() will see the RequestStopEvent, set the
                                // GenerateReason to PERMISSION_ASKING, and return.
                                if (!autoDenied.isEmpty()) {
                                    // Write DENIED results in-place so they aren't re-evaluated on
                                    // resume.
                                    writeAutoDeniedResults(toolCalls, autoDenied);
                                }
                                // resultHolder may be inspected by the caller after stream
                                // completion;
                                // initialise it to empty since no successful execution happened.
                                resultHolder.set(List.of());
                                return Flux.<AgentEvent>just(
                                        new RequireUserConfirmEvent(replyId, pending),
                                        new RequestStopEvent(
                                                "permission asking",
                                                GenerateReason.PERMISSION_ASKING));
                            })
                    .doOnNext(this::publishEvent);
        }

        /**
         * Synthesise DENIED ToolResultBlocks for tools that were rejected by deny rules and append
         * them to context so the conversation reflects the rejection (and resume doesn't see them
         * as pending).
         */
        private void writeAutoDeniedResults(List<ToolUseBlock> toolCalls, Set<String> deniedIds) {
            for (ToolUseBlock tc : toolCalls) {
                if (!deniedIds.contains(tc.getId())) {
                    continue;
                }
                ToolResultBlock denied =
                        ToolResultBlock.text("Permission denied by rules")
                                .withIdAndName(tc.getId(), tc.getName())
                                .withState(ToolResultState.DENIED);
                Msg deniedMsg = ToolResultMessageBuilder.buildToolResultMsg(denied, tc, getName());
                state.contextMutable().add(deniedMsg);
            }
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
                                                        use.getName(),
                                                        "Permission denied by user"),
                                                new ToolResultEndEvent(
                                                        replyId,
                                                        use.getId(),
                                                        use.getName(),
                                                        ToolResultState.DENIED));
                                    });

            if (approved.isEmpty()) {
                resultHolder.set(deniedEntries);
                return deniedEvents;
            }

            // Capture the parent Reactor Context (set by AgentBase.createEventStream, which puts
            // the
            // SubagentEventBus there) so we can forward it into the inner executeToolCalls
            // subscribe.
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

                                                Set<String> chunkedToolIds =
                                                        ConcurrentHashMap.newKeySet();

                                                toolkit.setInternalChunkCallback(
                                                        (toolUse, chunk) -> {
                                                            if (chunk.getOutput() != null
                                                                    && !chunk.getOutput()
                                                                            .isEmpty()) {
                                                                chunkedToolIds.add(toolUse.getId());
                                                                for (ContentBlock block :
                                                                        chunk.getOutput()) {
                                                                    if (block
                                                                            instanceof
                                                                            TextBlock tb) {
                                                                        sink.next(
                                                                                new ToolResultTextDeltaEvent(
                                                                                        replyId,
                                                                                        toolUse
                                                                                                .getId(),
                                                                                        toolUse
                                                                                                .getName(),
                                                                                        tb
                                                                                                .getText()));
                                                                    } else {
                                                                        sink.next(
                                                                                new ToolResultDataDeltaEvent(
                                                                                        replyId,
                                                                                        toolUse
                                                                                                .getId(),
                                                                                        toolUse
                                                                                                .getName(),
                                                                                        block));
                                                                    }
                                                                }
                                                            }
                                                            hookDispatcher
                                                                    .fireActingChunk(
                                                                            toolUse, chunk, toolkit)
                                                                    .contextWrite(
                                                                            ctx ->
                                                                                    ctx.putAll(
                                                                                            parentCtx))
                                                                    .subscribe();
                                                        });

                                                Disposable toolCallsDisposable =
                                                        executeToolCalls(approved)
                                                                .contextWrite(
                                                                        ctx ->
                                                                                ctx.putAll(
                                                                                        parentCtx))
                                                                .subscribe(
                                                                        results -> {
                                                                            List<
                                                                                            Map
                                                                                                            .Entry<
                                                                                                    ToolUseBlock,
                                                                                                    ToolResultBlock>>
                                                                                    merged =
                                                                                            new ArrayList<>(
                                                                                                    deniedEntries);
                                                                            merged.addAll(results);
                                                                            resultHolder.set(
                                                                                    merged);
                                                                            for (Map.Entry<
                                                                                            ToolUseBlock,
                                                                                            ToolResultBlock>
                                                                                    entry :
                                                                                            results) {
                                                                                emitToolResultDelta(
                                                                                        sink,
                                                                                        replyId,
                                                                                        entry,
                                                                                        chunkedToolIds);
                                                                                ToolResultState
                                                                                        state =
                                                                                                determineToolResultState(
                                                                                                        entry
                                                                                                                .getValue());
                                                                                sink.next(
                                                                                        new ToolResultEndEvent(
                                                                                                replyId,
                                                                                                entry.getKey()
                                                                                                        .getId(),
                                                                                                entry.getKey()
                                                                                                        .getName(),
                                                                                                state));
                                                                            }
                                                                            sink.complete();
                                                                        },
                                                                        sink::error);
                                                sink.onCancel(toolCallsDisposable);
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
         * <p>When the agent's {@link io.agentscope.core.permission.PermissionContextState} is trivial
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
            // Tools already promoted to ALLOWED by user confirmation skip the engine entirely.
            if (use.getState() == ToolCallState.ALLOWED) {
                return Mono.just(new PermissionVerdict(use, PermissionBehavior.ALLOW));
            }
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
                                // gates execution; PASSTHROUGH and ALLOW both run, DENY is
                                // honoured.
                                return switch (decision.getBehavior()) {
                                    case ASK -> new PermissionVerdict(use, PermissionBehavior.ASK);
                                    case DENY ->
                                            new PermissionVerdict(use, PermissionBehavior.DENY);
                                    default -> new PermissionVerdict(use, PermissionBehavior.ALLOW);
                                };
                            });
        }

        private record PermissionVerdict(ToolUseBlock use, PermissionBehavior behavior) {}

        /**
         * Emit delta events for tool results that were NOT already streamed via the chunk
         * callback. For non-streaming tools the chunk callback is never invoked, so the
         * event stream would otherwise contain only START and END with no content.
         */
        private void emitToolResultDelta(
                FluxSink<AgentEvent> sink,
                String replyId,
                Map.Entry<ToolUseBlock, ToolResultBlock> entry,
                Set<String> chunkedToolIds) {
            String toolId = entry.getKey().getId();
            String toolName = entry.getKey().getName();
            if (chunkedToolIds.contains(toolId)) {
                return;
            }
            List<ContentBlock> output = entry.getValue().getOutput();
            if (output == null || output.isEmpty()) {
                return;
            }
            for (ContentBlock block : output) {
                if (block instanceof TextBlock tb) {
                    sink.next(
                            new ToolResultTextDeltaEvent(replyId, toolId, toolName, tb.getText()));
                } else {
                    sink.next(new ToolResultDataDeltaEvent(replyId, toolId, toolName, block));
                }
            }
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
            return AssistantMessage.builder()
                    .name(getName())
                    .content(content)
                    .generateReason(GenerateReason.TOOL_SUSPENDED)
                    .build();
        }

        /**
         * Build a stop-acknowledgement Msg for middleware-requested stops during acting. Preserves
         * any already-collected tool results so the caller sees partial progress.
         */
        private Msg buildStopMsg(
                List<Map.Entry<ToolUseBlock, ToolResultBlock>> results, GenerateReason reason) {
            List<ContentBlock> content = new ArrayList<>();
            if (results != null) {
                for (Map.Entry<ToolUseBlock, ToolResultBlock> pair : results) {
                    content.add(pair.getKey());
                    content.add(pair.getValue());
                }
            }
            return AssistantMessage.builder()
                    .name(getName())
                    .content(content)
                    .generateReason(reason)
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
            return dispatchToolCalls(toolCalls)
                    .map(
                            results ->
                                    IntStream.range(0, toolCalls.size())
                                            .mapToObj(
                                                    i ->
                                                            Map.entry(
                                                                    toolCalls.get(i),
                                                                    results.get(i)))
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
                                        "Tool execution failed, generating error results for {}"
                                                + " tool calls",
                                        toolCalls.size(),
                                        error);
                                List<Map.Entry<ToolUseBlock, ToolResultBlock>> errorResults =
                                        toolCalls.stream()
                                                .map(
                                                        toolCall -> {
                                                            ToolResultBlock errorResult =
                                                                    buildErrorToolResult(
                                                                            toolCall.getId(),
                                                                            "Tool execution failed:"
                                                                                    + " "
                                                                                    + errorMsg);
                                                            return Map.entry(toolCall, errorResult);
                                                        })
                                                .toList();
                                return Mono.just(errorResults);
                            });
        }

        /**
         * Resolve tool results for {@code toolCalls}, in the same order. When this call is a
         * structured-output call, any {@code generate_response} invocations are executed against
         * the per-call {@link #soTool} (never registered on the shared toolkit); all other tools go
         * through {@link Toolkit#callTools}.
         */
        private Mono<List<ToolResultBlock>> dispatchToolCalls(List<ToolUseBlock> toolCalls) {
            boolean hasStructured =
                    soTool != null
                            && toolCalls.stream()
                                    .anyMatch(t -> STRUCTURED_OUTPUT_TOOL_NAME.equals(t.getName()));
            if (!hasStructured) {
                return toolkit.callTools(
                        toolCalls,
                        toolExecutionConfig,
                        ReActAgent.this,
                        buildMergedRuntimeContext(rc));
            }

            List<ToolUseBlock> regular =
                    toolCalls.stream()
                            .filter(t -> !STRUCTURED_OUTPUT_TOOL_NAME.equals(t.getName()))
                            .toList();
            Mono<Map<String, ToolResultBlock>> regularResults =
                    regular.isEmpty()
                            ? Mono.just(Map.of())
                            : toolkit.callTools(
                                            regular,
                                            toolExecutionConfig,
                                            ReActAgent.this,
                                            buildMergedRuntimeContext(rc))
                                    .map(
                                            list -> {
                                                Map<String, ToolResultBlock> byId = new HashMap<>();
                                                for (int i = 0; i < regular.size(); i++) {
                                                    byId.put(regular.get(i).getId(), list.get(i));
                                                }
                                                return byId;
                                            });
            return regularResults.flatMap(
                    byId ->
                            Flux.fromIterable(toolCalls)
                                    .concatMap(
                                            use -> {
                                                if (STRUCTURED_OUTPUT_TOOL_NAME.equals(
                                                        use.getName())) {
                                                    return executeStructuredTool(use);
                                                }
                                                ToolResultBlock result = byId.get(use.getId());
                                                if (result == null) {
                                                    return Mono.just(
                                                            buildErrorToolResult(
                                                                    use.getId(),
                                                                    "Internal error: missing tool"
                                                                            + " result for '"
                                                                            + use.getName()
                                                                            + "'"));
                                                }
                                                return Mono.just(result);
                                            })
                                    .collectList());
        }

        /**
         * Execute the per-call {@code generate_response} tool for a single tool call, mirroring the
         * schema validation the executor performs for registered tools.
         */
        private Mono<ToolResultBlock> executeStructuredTool(ToolUseBlock use) {
            String validationError =
                    ToolValidator.validateInput(use.getContent(), soTool.getParameters());
            if (validationError != null) {
                return Mono.just(
                        buildErrorToolResult(
                                use.getId(),
                                "Parameter validation failed for tool '"
                                        + STRUCTURED_OUTPUT_TOOL_NAME
                                        + "': "
                                        + validationError));
            }
            ToolCallParam param =
                    ToolCallParam.builder()
                            .toolUseBlock(use)
                            .input(use.getInput() == null ? Map.of() : use.getInput())
                            .agent(ReActAgent.this)
                            .runtimeContext(buildMergedRuntimeContext(rc))
                            .build();
            return soTool.callAsync(param).map(rb -> rb.withIdAndName(use.getId(), use.getName()));
        }

        /**
         * Fire PostActingEvent for a single tool result, build message and add to context.
         */
        private Mono<PostActingEvent> notifyPostActingHook(
                Map.Entry<ToolUseBlock, ToolResultBlock> entry) {
            ToolUseBlock toolUse = entry.getKey();
            ToolResultBlock result = entry.getValue();

            // FIX: determine the final state and update ToolResultBlock before
            // adding to contextMutable(), so that history queries via
            // agent.getAgentState(ctx).contextMutable() reflect the correct
            // final state instead of always showing RUNNING.
            ToolResultState finalState = determineToolResultState(result);
            ToolResultBlock updatedResult = result.withState(finalState);

            Msg toolMsg =
                    ToolResultMessageBuilder.buildToolResultMsg(updatedResult, toolUse, getName());

            return hookDispatcher
                    .firePostActing(toolUse, updatedResult, toolkit, toolMsg)
                    .doOnNext(
                            e -> {
                                if (soTool != null
                                        && STRUCTURED_OUTPUT_TOOL_NAME.equals(toolUse.getName())
                                        && result.getMetadata() != null
                                        && Boolean.TRUE.equals(
                                                result.getMetadata().get("success"))) {
                                    soCompleted = true;
                                    soResultMsg = e.getToolResultMsg();
                                    e.stopAgent();
                                }
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
                            messageList, generateOptions, model.getModelName(), maxIters, systemMsg)
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
                                                                        context
                                                                                .buildFinalMessage())))
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
         * hooks (SummaryChunkEvent) and does not pass tool schemas to the model.
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
                            middlewares,
                            ReActAgent.this,
                            rc,
                            MiddlewareBase::onModelCall,
                            summaryModelCallCore)
                    .apply(new ModelCallInput(messages, null, options, model))
                    .doOnNext(this::publishEvent);
        }

        private Flux<AgentEvent> summaryModelCallStream(
                ReasoningContext context, ModelCallInput mci, GenerateOptions hookOptions) {

            String replyId = UUID.randomUUID().toString().replace("-", "");
            ModelCallBlockLifecycle blockLifecycle = new ModelCallBlockLifecycle(replyId);

            Flux<AgentEvent> modelEvents =
                    mci.model().stream(mci.messages(), mci.tools(), mci.options())
                            .concatMap(chunk -> checkInterrupted().thenReturn(chunk))
                            .concatMap(
                                    chunk ->
                                            Flux.deferContextual(
                                                    parentCtx -> {
                                                        List<Msg> chunkMsgs =
                                                                context.processChunk(chunk);
                                                        for (Msg msg : chunkMsgs) {
                                                            hookDispatcher
                                                                    .fireSummaryChunk(
                                                                            msg,
                                                                            context,
                                                                            hookOptions,
                                                                            model.getModelName())
                                                                    .contextWrite(
                                                                            ctx ->
                                                                                    ctx.putAll(
                                                                                            parentCtx))
                                                                    .subscribe();
                                                        }

                                                        List<AgentEvent> events = new ArrayList<>();
                                                        for (ContentBlock block :
                                                                chunk.getContent()) {
                                                            if (block instanceof TextBlock tb) {
                                                                blockLifecycle.startText(events);
                                                                if (tb.getText() != null
                                                                        && !tb.getText()
                                                                                .isEmpty()) {
                                                                    events.add(
                                                                            new TextBlockDeltaEvent(
                                                                                    blockLifecycle
                                                                                            .replyId,
                                                                                    "text",
                                                                                    tb.getText()));
                                                                }
                                                            } else if (block
                                                                    instanceof ThinkingBlock tb) {
                                                                blockLifecycle.startThinking(
                                                                        events);
                                                                if (tb.getThinking() != null
                                                                        && !tb.getThinking()
                                                                                .isEmpty()) {
                                                                    events.add(
                                                                            new ThinkingBlockDeltaEvent(
                                                                                    blockLifecycle
                                                                                            .replyId,
                                                                                    "thinking",
                                                                                    tb
                                                                                            .getThinking()));
                                                                }
                                                            }
                                                        }
                                                        return Flux.fromIterable(events);
                                                    }));

            Flux<AgentEvent> endEvents =
                    Flux.defer(
                            () -> {
                                List<AgentEvent> events = new ArrayList<>();
                                blockLifecycle.flushAll(events);
                                events.add(new ModelCallEndEvent(replyId, context.getChatUsage()));
                                return Flux.fromIterable(events);
                            });

            return Flux.concat(Flux.just(new ModelCallStartEvent(replyId)), modelEvents, endEvents);
        }

        private List<Msg> prepareSummaryMessages() {
            List<Msg> messageList = new ArrayList<>(state.contextMutable());
            messageList.add(
                    UserMessage.builder()
                            .name("user")
                            .content(
                                    TextBlock.builder()
                                            .text(
                                                    "You have failed to generate response within"
                                                            + " the maximum iterations. Now respond"
                                                            + " directly by summarizing the current"
                                                            + " situation.")
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
                    AssistantMessage.builder()
                            .name(getName())
                            .content(
                                    TextBlock.builder()
                                            .text(
                                                    String.format(
                                                            "Maximum iterations (%d) reached. Error"
                                                                    + " generating summary: %s",
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

        // ==================== Tool call state helpers (Permission HITL) ====================

        /**
         * Locate the last assistant Msg in context and replace the {@code state} of every
         * {@link ToolUseBlock} whose id matches the given map's key. Mirrors Python's
         * {@code _update_tool_call_state} but operates in bulk to minimise list rebuilds.
         */
        private void updateToolCallStates(Map<String, ToolCallState> updates) {
            if (updates == null || updates.isEmpty()) {
                return;
            }
            List<Msg> ctx = state.contextMutable();
            for (int i = ctx.size() - 1; i >= 0; i--) {
                Msg m = ctx.get(i);
                if (m.getRole() != MsgRole.ASSISTANT) {
                    continue;
                }
                boolean hasMatch =
                        m.getContent().stream()
                                .anyMatch(
                                        b ->
                                                b instanceof ToolUseBlock t
                                                        && updates.containsKey(t.getId()));
                if (!hasMatch) {
                    continue;
                }
                List<ContentBlock> rebuilt = new ArrayList<>(m.getContent().size());
                for (ContentBlock block : m.getContent()) {
                    if (block instanceof ToolUseBlock t && updates.containsKey(t.getId())) {
                        rebuilt.add(t.withState(updates.get(t.getId())));
                    } else {
                        rebuilt.add(block);
                    }
                }
                ctx.set(i, m.withContent(rebuilt));
                return; // only the last assistant msg holds the live tool_use blocks
            }
        }

        /** Convenience overload for a single tool call. */
        private void updateToolCallState(String toolCallId, ToolCallState newState) {
            updateToolCallStates(Map.of(toolCallId, newState));
        }

        /** Whether any ToolUseBlock in the last assistant Msg is in ASKING state. */
        private boolean hasAskingToolCalls() {
            return !askingToolCalls().isEmpty();
        }

        /** The ToolUseBlocks in the last assistant Msg that are in ASKING state (HITL pending). */
        private List<ToolUseBlock> askingToolCalls() {
            Msg last = findLastAssistantMsg();
            if (last == null) {
                return List.of();
            }
            return last.getContent().stream()
                    .filter(
                            b ->
                                    b instanceof ToolUseBlock t
                                            && t.getState() == ToolCallState.ASKING)
                    .map(ToolUseBlock.class::cast)
                    .toList();
        }
    }

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
        return Mono.deferContextual(
                cv -> {
                    CallExecution scope = scopeFrom(cv);
                    // Resolve the source from this call's session-scoped control (the
                    // context passed by AgentBase is derived from the instance-level signal,
                    // which is not call-scoped under concurrency).
                    InterruptSource source = scope.state.interruptControl().getSource();
                    if (source == InterruptSource.SYSTEM) {
                        String requestId =
                                (String) cv.getOrDefault(AgentBase.SHUTDOWN_REQUEST_ID_KEY, null);
                        shutdownManager.saveOnInterruptObserved(requestId);
                        return Mono.error(new AgentShuttingDownException());
                    }
                    String recoveryText =
                            "I noticed that you have interrupted me. What can I do for you?";
                    Msg recoveryMsg =
                            AssistantMessage.builder()
                                    .name(getName())
                                    .content(TextBlock.builder().text(recoveryText).build())
                                    .build();
                    scope.state.contextMutable().add(recoveryMsg);
                    return Mono.just(recoveryMsg);
                });
    }

    @Override
    protected Mono<Void> doObserve(Msg msg) {
        if (msg != null) {
            getAgentState().contextMutable().add(msg);
        }
        return Mono.empty();
    }

    // ==================== Getters ====================

    /** Returns this agent's toolkit (a per-instance deep copy made at build time). */
    public Toolkit getToolkit() {
        return toolkit;
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

    /**
     * @deprecated Use {@link #getAgentState(RuntimeContext)} or
     *     {@link #getAgentState(String, String)} with explicit session identity.
     *     This method delegates to the default session slot.
     */
    @Deprecated
    @Override
    public AgentState getAgentState() {
        return getAgentState(null, defaultSessionId);
    }

    @Override
    public RuntimeContext getRuntimeContext() {
        return activeRc;
    }

    /**
     * Returns the {@link AgentState} for the session identified by the given {@link RuntimeContext}.
     *
     * @param ctx the runtime context (uses {@code getUserId()} and {@code getSessionId()})
     * @return the agent state for the identified session
     */
    public AgentState getAgentState(RuntimeContext ctx) {
        String uid = ctx != null ? ctx.getUserId() : null;
        String sid = ctx != null ? ctx.getSessionId() : null;
        if (sid == null || sid.isBlank()) {
            sid = defaultSessionId;
        }
        return getAgentState(uid, sid);
    }

    /**
     * Returns the {@link AgentState} for the given {@code (userId, sessionId)} slot, loading it
     * from the configured {@link AgentStateStore} on first access and caching it for subsequent
     * calls within this JVM.
     *
     * <p>Note: in distributed deployments the authoritative reload happens at call start inside
     * {@code activateSlotForContext}. This method returns the locally cached instance (suitable
     * for the "get → mutate → save" pattern used by admin APIs and tests).
     */
    public AgentState getAgentState(String userId, String sessionId) {
        String slot = slotKey(userId, sessionId);
        return stateCache.computeIfAbsent(
                slot,
                k ->
                        loadOrCreateAgentStateForSlot(
                                stateStore,
                                userId,
                                sessionId,
                                initialPermissionContext,
                                getAgentId()));
    }

    /**
     * Switches the {@link PermissionMode} for the given {@code (userId, sessionId)} session at
     * runtime and rebuilds that session's cached {@link PermissionEngine} so the change takes
     * effect on the next tool evaluation. The configured rules and working directories are
     * preserved; only the mode changes. The change is persisted so the next {@code call} on that
     * session sees it.
     *
     * <p>Use this to implement a deliberate, user-initiated "bypass permissions" toggle (pass
     * {@link PermissionMode#BYPASS}) or to restore stricter enforcement afterwards (pass
     * {@link PermissionMode#DEFAULT}). {@code BYPASS} disables all rule evaluation, so it should be
     * an explicit, per-session action and is best paired with a sandboxed environment.
     *
     * <p>An in-flight call keeps the engine it started with; the new mode applies to subsequent
     * calls on the slot.
     *
     * @param userId user identity for the slot (may be {@code null})
     * @param sessionId session identity (falls back to the default session id when {@code null})
     * @param mode the permission mode to switch to
     */
    public void setPermissionMode(String userId, String sessionId, PermissionMode mode) {
        Objects.requireNonNull(mode, "mode must not be null");
        String sid = (sessionId == null || sessionId.isBlank()) ? defaultSessionId : sessionId;
        String slot = slotKey(userId, sid);
        AgentState s = getAgentState(userId, sid);
        s.setPermissionContext(s.getPermissionContext().withMode(mode));
        permissionEngineCache.put(slot, new PermissionEngine(s.getPermissionContext()));
        saveAgentState(userId, sid);
    }

    /**
     * Switches the {@link PermissionMode} for the session identified by the given
     * {@link RuntimeContext}. See {@link #setPermissionMode(String, String, PermissionMode)}.
     *
     * @param ctx the runtime context identifying the session
     * @param mode the permission mode to switch to
     */
    public void setPermissionMode(RuntimeContext ctx, PermissionMode mode) {
        String uid = ctx != null ? ctx.getUserId() : null;
        String sid = ctx != null ? ctx.getSessionId() : null;
        setPermissionMode(uid, sid, mode);
    }

    /**
     * Returns the current {@link PermissionMode} for the given {@code (userId, sessionId)} session.
     *
     * @param userId user identity for the slot (may be {@code null})
     * @param sessionId session identity (falls back to the default session id when {@code null})
     * @return the session's current permission mode
     */
    public PermissionMode getPermissionMode(String userId, String sessionId) {
        String sid = (sessionId == null || sessionId.isBlank()) ? defaultSessionId : sessionId;
        return getAgentState(userId, sid).getPermissionContext().getMode();
    }

    /**
     * Persists the cached {@link AgentState} for the session identified by the given
     * {@link RuntimeContext}. No-op when no store is configured or the session has never
     * been loaded.
     *
     * @param ctx the runtime context identifying the session to save
     */
    public void saveAgentState(RuntimeContext ctx) {
        String uid = ctx != null ? ctx.getUserId() : null;
        String sid = ctx != null ? ctx.getSessionId() : null;
        if (sid == null || sid.isBlank()) {
            sid = defaultSessionId;
        }
        saveAgentState(uid, sid);
    }

    /**
     * Persists the cached {@link AgentState} for the given {@code (userId, sessionId)} slot via the
     * configured {@link AgentStateStore}. No-op when no store is configured or the slot has never
     * been loaded into the cache.
     */
    public void saveAgentState(String userId, String sessionId) {
        if (stateStore == null) {
            return;
        }
        String slot = slotKey(userId, sessionId);
        AgentState s = stateCache.get(slot);
        if (s != null) {
            stateStore.save(userId, sessionId, "agent_state", s);
        }
    }

    /** Returns the {@link AgentStateStore} configured for state persistence, or {@code null}. */
    public AgentStateStore getStateStore() {
        return stateStore;
    }

    /**
     * Returns the builder-time fallback {@code sessionId}, used when a call's
     * {@link RuntimeContext} carries no {@code sessionId}.
     */
    public String getDefaultSessionId() {
        return defaultSessionId;
    }

    private void syncToolkitToState(AgentState state) {
        if (toolkit != null && state != null) {
            state.getToolContext().setActivatedGroups(toolkit.getActiveGroups());
        }
    }

    /** Returns the model-call configuration (retries, timeouts). */
    public ModelConfig getModelConfig() {
        return modelConfig;
    }

    /** Returns the reasoning-loop configuration (maxIters, stopOnReject). */
    public ReactConfig getReactConfig() {
        return reactConfig;
    }

    /** @deprecated Use {@code getAgentState(userId, sessionId).getPermissionContext()} instead. */
    @Deprecated
    public PermissionEngine getPermissionEngine() {
        String slot = slotKey(null, defaultSessionId);
        AgentState s = getAgentState(null, defaultSessionId);
        return permissionEngineCache.computeIfAbsent(
                slot, k -> new PermissionEngine(s.getPermissionContext()));
    }

    /** @deprecated Use {@code getAgentState(userId, sessionId).getPermissionContext()} instead. */
    @Deprecated
    public PermissionContextState getPermissionContext() {
        return getAgentState().getPermissionContext();
    }

    /** Returns the immutable list of registered middlewares. */
    public List<MiddlewareBase> getMiddlewares() {
        return middlewares;
    }

    /** Returns the per-model-call {@link ExecutionConfig}, or {@code null} if none was set. */
    public ExecutionConfig getModelExecutionConfig() {
        return modelExecutionConfig;
    }

    /** Returns the per-tool-call {@link ExecutionConfig}, or {@code null} if none was set. */
    public ExecutionConfig getToolExecutionConfig() {
        return toolExecutionConfig;
    }

    /** Returns the {@link ToolExecutionContext} bound at build time, or {@code null} if none. */
    public ToolExecutionContext getToolExecutionContext() {
        return toolExecutionContext;
    }

    /**
     * Returns whether pending-tool-call recovery is enabled (HITL: resume tool calls left in
     * a pending state across {@code call()} invocations).
     */
    public boolean isPendingToolRecoveryEnabled() {
        return enablePendingToolRecovery;
    }

    /** Returns the system prompt (alias for {@link #getSysPrompt()}). */
    public String getSystemPrompt() {
        return sysPrompt;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public void close() {
        // No-op for the core ReActAgent. Subclasses / wrappers (HarnessAgent) may release
        // additional resources here.
    }

    // ==================== Builder ====================

    @SuppressWarnings("deprecation")
    public static class Builder {
        String name;
        String description;
        String sysPrompt;
        Model model;
        Toolkit toolkit = new Toolkit();

        int maxIters = 10;
        ExecutionConfig modelExecutionConfig;
        ExecutionConfig toolExecutionConfig;
        GenerateOptions generateOptions;
        final Set<Hook> hooks = new LinkedHashSet<>();
        private final List<MiddlewareBase> middlewares = new ArrayList<>();
        private boolean enableMetaTool = false;
        private boolean taskListEnabled = false;
        private ToolExecutionContext toolExecutionContext;
        private boolean enablePendingToolRecovery = false;

        // 2.0 core fields
        private PermissionContextState permissionContext;

        // Flat setters backing ModelConfig / ReactConfig values
        private Integer flatMaxRetries;
        private Model flatFallbackModel;
        private Boolean flatStopOnReject;
        private AgentStateStore stateStore;
        private String defaultSessionId;

        // ==================== 1.x legacy compatibility fields ====================
        // Below fields back the deprecated `longTermMemory(...)`, `knowledge(...)`,
        // `skillBox(...)` setters. They are consumed by configureXxx() during build() so
        // legacy 1.x user code keeps producing equivalent runtime behavior.

        @Deprecated(forRemoval = true, since = "2.0.0")
        private LongTermMemory longTermMemory;

        @Deprecated(forRemoval = true, since = "2.0.0")
        private LongTermMemoryMode longTermMemoryMode = LongTermMemoryMode.BOTH;

        @Deprecated(forRemoval = true, since = "2.0.0")
        private boolean longTermMemoryAsyncRecord = false;

        @Deprecated(forRemoval = true, since = "2.0.0")
        private final Set<Knowledge> knowledgeBases = new LinkedHashSet<>();

        @Deprecated(forRemoval = true, since = "2.0.0")
        private RAGMode ragMode = RAGMode.GENERIC;

        @Deprecated(forRemoval = true, since = "2.0.0")
        private RetrieveConfig retrieveConfig =
                RetrieveConfig.builder().limit(5).scoreThreshold(0.5).build();

        @Deprecated(forRemoval = true, since = "2.0.0")
        private SkillBox skillBox;

        // ==================== 2.0 skill repository entry ====================
        // The 2.0 way to mount skills: hand the agent one or more
        // {@link AgentSkillRepository} instances. {@link #build()} installs a
        // {@link DynamicSkillMiddleware} that rebuilds the skill prompt on every call,
        // letting per-user namespaced repositories swap content under the same skill name.

        private final List<AgentSkillRepository> skillRepositories = new ArrayList<>();
        private SkillFilter skillFilter;
        private boolean dynamicSkillsEnabled = true;

        /**
         * When true, {@link DynamicSkillMiddleware} toggles the prompt provider's code-execution
         * block (per-skill {@code <files-root>} listing + instructions). Off by default —
         * enabling it without also wiring a shell-like tool will produce a prompt that asks the
         * model to do things it cannot.
         */
        private boolean skillCodeExecutionEnabled = false;

        /**
         * Stable working directory passed to {@link DynamicSkillMiddleware}. {@code null} means
         * the middleware will mkdtemp one on first reload and reuse it for the agent's lifetime.
         */
        private Path skillWorkDir;

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

        /** @deprecated No longer enforced; per-session serialization handles concurrency. */
        @Deprecated
        public Builder checkRunning(boolean checkRunning) {
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
        public Builder middleware(MiddlewareBase middleware) {
            this.middlewares.add(middleware);
            return this;
        }

        /**
         * Adds multiple middlewares for intercepting agent execution.
         *
         * @param middlewares the list of middlewares to add
         * @return this builder instance for method chaining
         */
        public Builder middlewares(List<? extends MiddlewareBase> middlewares) {
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
         * Enables the built-in task-list capability ({@code todo_write} tool + per-turn reminder).
         *
         * <p>Equivalent to {@link #enableTaskList(boolean) enableTaskList(true)}.
         *
         * @return this builder for chaining
         */
        public Builder enableTaskList() {
            return enableTaskList(true);
        }

        /**
         * Enables or disables the built-in task-list capability.
         *
         * <p>When enabled, {@link #build()} registers a {@code todo_write} tool (operating on
         * {@link io.agentscope.core.state.AgentState#getTasksContext()} with full-list-replace
         * semantics) and a {@link io.agentscope.core.middleware.TaskReminderMiddleware} that
         * re-surfaces the current list before every reasoning step. Default OFF.
         *
         * @param enabled true to enable the task list
         * @return this builder for chaining
         */
        public Builder enableTaskList(boolean enabled) {
            this.taskListEnabled = enabled;
            return this;
        }

        /**
         * Enables or disables automatic recovery from orphaned pending tool calls.
         *
         * <p>When enabled, the agent automatically detects orphaned pending tool calls and
         * patches them with synthetic error results before processing new input. This prevents
         * {@link IllegalStateException} when tool execution fails, times out, or is interrupted.
         *
         * <p>Disable this if you prefer to handle pending tool calls manually, for example
         * through HITL (Human-in-the-loop) mechanisms or custom error handling strategies.
         *
         * @param enable true to enable auto-recovery, false to disable
         * @return This builder instance for method chaining
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
         * Sets the tool execution context for this agent.
         *
         * @param toolExecutionContext The tool execution context
         * @return This builder instance for method chaining
         * @deprecated Use {@link RuntimeContext} with {@code agent.call(msg, runtimeContext)}
         *     or register POJOs directly via {@code RuntimeContext.builder().put(Type, value)}.
         */
        @Deprecated
        public Builder toolExecutionContext(ToolExecutionContext toolExecutionContext) {
            this.toolExecutionContext = toolExecutionContext;
            return this;
        }

        /**
         * Sets the {@link AgentStateStore} backing automatic AgentState load (at construction) and save
         * (after every successful {@code call()} and on graceful shutdown). When {@code null}, the
         * agent runs purely in-memory and persistence is a no-op.
         */
        public Builder stateStore(AgentStateStore stateStore) {
            this.stateStore = stateStore;
            return this;
        }

        /**
         * Sets the builder-time fallback {@code sessionId} used to persist {@code agent_state}
         * when a call does not supply a {@code sessionId} on its {@link RuntimeContext}. Defaults
         * to the agent name when unset. Per-call routing is via {@code RuntimeContext.sessionId};
         * this is only the bootstrap / single-tenant default.
         */
        public Builder defaultSessionId(String sessionId) {
            this.defaultSessionId = sessionId;
            return this;
        }

        /**
         * Sets the model-call retry budget (max attempts including the first try). Defaults to
         * {@link ModelConfig#DEFAULT_MAX_RETRIES} when unset.
         */
        public Builder maxRetries(int maxRetries) {
            if (maxRetries <= 0) {
                throw new IllegalArgumentException("maxRetries must be > 0: " + maxRetries);
            }
            this.flatMaxRetries = maxRetries;
            return this;
        }

        /**
         * Sets the fallback model invoked after the primary model exhausts its retry budget.
         * Pass {@code null} to explicitly clear (no fallback).
         */
        public Builder fallbackModel(Model fallbackModel) {
            this.flatFallbackModel = fallbackModel;
            return this;
        }

        /**
         * Convenience overload that resolves {@code modelId} via
         * {@link io.agentscope.core.model.ModelRegistry#resolve(String)} (named registration or
         * {@code provider:model} pattern like {@code openai:gpt-5.5}, {@code dashscope:qwen-max}).
         *
         * @throws IllegalArgumentException if the id cannot be resolved
         */
        public Builder fallbackModel(String modelId) {
            this.flatFallbackModel = io.agentscope.core.model.ModelRegistry.resolve(modelId);
            return this;
        }

        /**
         * Controls whether a permission rejection of any tool call terminates the reasoning loop
         * (instead of feeding the rejection back into the next reasoning round). Defaults to
         * {@link ReactConfig#DEFAULT_STOP_ON_REJECT}.
         */
        public Builder stopOnReject(boolean stopOnReject) {
            this.flatStopOnReject = stopOnReject;
            return this;
        }

        /**
         * Sets the {@link PermissionContextState} consulted by the {@link PermissionEngine} during tool
         * execution. When unset, an empty permission context is used (PASSTHROUGH for all tools).
         */
        public Builder permissionContext(PermissionContextState permissionContext) {
            this.permissionContext = permissionContext;
            return this;
        }

        // ==================== 1.x legacy compatibility setters ====================
        // The setters below are deprecated since 2.0 and will be removed in the next minor.
        // Each one captures a value used later by configureXxx() during build(), wiring the
        // corresponding legacy hook(s) and/or tool(s) into the agent so 1.x user code keeps
        // producing equivalent runtime behavior. Internal references use legacy.* packages.

        /**
         * @deprecated since 2.0.0. Long-term memory is being redesigned around the upcoming reme
         *     base class. Hooks added through this path still work.
         */
        @Deprecated(forRemoval = true, since = "2.0.0")
        public Builder longTermMemory(LongTermMemory longTermMemory) {
            this.longTermMemory = longTermMemory;
            return this;
        }

        /**
         * @deprecated since 2.0.0. See {@link #longTermMemory}.
         */
        @Deprecated(forRemoval = true, since = "2.0.0")
        public Builder longTermMemoryMode(LongTermMemoryMode mode) {
            this.longTermMemoryMode = mode;
            return this;
        }

        /**
         * @deprecated since 2.0.0. See {@link #longTermMemory}.
         */
        @Deprecated(forRemoval = true, since = "2.0.0")
        public Builder longTermMemoryAsyncRecord(boolean asyncRecord) {
            this.longTermMemoryAsyncRecord = asyncRecord;
            return this;
        }

        /**
         * @deprecated since 2.0.0. RAG is being redesigned; legacy adapters remain functional.
         */
        @Deprecated(forRemoval = true, since = "2.0.0")
        public Builder knowledge(Knowledge knowledge) {
            if (knowledge != null) {
                this.knowledgeBases.add(knowledge);
            }
            return this;
        }

        /**
         * @deprecated since 2.0.0. See {@link #knowledge}.
         */
        @Deprecated(forRemoval = true, since = "2.0.0")
        public Builder knowledges(List<Knowledge> knowledges) {
            if (knowledges != null) {
                this.knowledgeBases.addAll(knowledges);
            }
            return this;
        }

        /**
         * @deprecated since 2.0.0. See {@link #knowledge}.
         */
        @Deprecated(forRemoval = true, since = "2.0.0")
        public Builder ragMode(RAGMode mode) {
            if (mode != null) {
                this.ragMode = mode;
            }
            return this;
        }

        /**
         * @deprecated since 2.0.0. See {@link #knowledge}.
         */
        @Deprecated(forRemoval = true, since = "2.0.0")
        public Builder retrieveConfig(RetrieveConfig config) {
            if (config != null) {
                this.retrieveConfig = config;
            }
            return this;
        }

        /**
         * @deprecated since 2.0.0. Skills now flow through {@link #skillRepository} /
         *     {@link #skillRepositories}; legacy {@link io.agentscope.core.skill.SkillBox}
         *     instances are still accepted for source compatibility, but combining a
         *     {@code skillBox(...)} with {@code skillRepository(...)} is untested — new code
         *     should prefer {@link #skillRepository(AgentSkillRepository)}.
         */
        @Deprecated(forRemoval = true, since = "2.0.0")
        public Builder skillBox(SkillBox skillBox) {
            this.skillBox = skillBox;
            return this;
        }

        /**
         * Adds a single {@link AgentSkillRepository} to the layered skill stack. Multiple calls
         * append in order from low to high priority — when two repositories expose a skill with
         * the same {@link io.agentscope.core.skill.AgentSkill#getName()}, the later (higher
         * priority) entry wins.
         *
         * <p>If at least one repository is registered and dynamic skills remain enabled
         * (see {@link #dynamicSkillsEnabled(boolean)}), {@link #build()} attaches a
         * {@link DynamicSkillMiddleware} that rebuilds the skill prompt on every {@code call()}.
         */
        public Builder skillRepository(AgentSkillRepository repo) {
            if (repo != null) {
                this.skillRepositories.add(repo);
            }
            return this;
        }

        /**
         * Replaces the current repository list with the supplied collection. {@code null}
         * entries are dropped silently; passing {@code null} clears the list.
         */
        public Builder skillRepositories(List<AgentSkillRepository> repos) {
            this.skillRepositories.clear();
            if (repos != null) {
                for (AgentSkillRepository r : repos) {
                    if (r != null) {
                        this.skillRepositories.add(r);
                    }
                }
            }
            return this;
        }

        /**
         * Builder-time {@link SkillFilter} applied by the auto-installed
         * {@link DynamicSkillMiddleware}. Defaults to {@link SkillFilter#all()} when unset.
         */
        public Builder skillFilter(SkillFilter filter) {
            this.skillFilter = filter;
            return this;
        }

        /**
         * Toggles automatic installation of {@link DynamicSkillMiddleware}. Set to {@code false}
         * when an external orchestrator (e.g. {@code HarnessAgent}) wants to attach its own
         * subclass of {@link DynamicSkillMiddleware} or fall back to a static
         * {@link io.agentscope.core.skill.SkillBox}. Defaults to {@code true}.
         */
        public Builder dynamicSkillsEnabled(boolean enabled) {
            this.dynamicSkillsEnabled = enabled;
            return this;
        }

        /**
         * Enables the code-execution prompt block emitted by
         * {@link io.agentscope.core.skill.AgentSkillPromptProvider}. When on:
         * <ul>
         *   <li>If every visible skill has an on-disk origin directory (e.g. produced by
         *       {@link io.agentscope.core.skill.repository.FileSystemSkillRepository}), each
         *       {@code <skill>} entry includes a {@code <files-root>} child with the absolute
         *       path so the LLM can shell-execute scripts directly;</li>
         *   <li>Otherwise the prompt falls back to a single {@code uploadDir} root.</li>
         * </ul>
         *
         * <p>Only flip this on when the agent's toolkit has a shell-like tool wired in —
         * otherwise the prompt will ask the model to do things it cannot. Defaults to {@code false}.
         */
        public Builder skillCodeExecutionEnabled(boolean enabled) {
            this.skillCodeExecutionEnabled = enabled;
            return this;
        }

        /**
         * Sets a stable working directory used by {@link DynamicSkillMiddleware} for
         * {@code uploadSkillFiles}. Pass {@code null} (the default) to let the middleware mkdtemp
         * a fresh directory on first reload and reuse it for the agent's lifetime.
         */
        public Builder skillWorkDir(Path dir) {
            this.skillWorkDir = dir;
            return this;
        }

        /**
         * Returns a new {@link Builder} pre-populated with the given agent's observable
         * configuration: name, description, system prompt, model, maxIters, generateOptions, and
         * a defensive copy of the toolkit.
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

        // ==================== 1.x legacy configureXxx helpers ====================
        // Ported verbatim from 1.x ReActAgent.Builder (origin/1.x ReActAgent.java:1762-1925),
        // with two adjustments for 2.0:
        //   1) all legacy classes are referenced through the io.agentscope.core.legacy.* packages;
        //   2) configureLongTermMemory's static hook receives an AgentStateMemoryView that lazily
        //      reads AgentState.context via a `selfRef` shared with build().

        /**
         * Configures long-term memory based on the selected mode.
         *
         * <p>AGENT_CONTROL registers memory tools for the agent to call. STATIC_CONTROL adds
         * a {@link StaticLongTermMemoryHook} that retrieves /
         * records memory automatically. BOTH combines them. The hook reads context lazily from
         * {@code selfRef.get().getAgentState()} so it tolerates being constructed before the
         * agent itself exists.
         */
        @SuppressWarnings("deprecation")
        private void configureLongTermMemory(
                Toolkit agentToolkit,
                java.util.concurrent.atomic.AtomicReference<ReActAgent> selfRef) {
            if (longTermMemoryMode == LongTermMemoryMode.AGENT_CONTROL
                    || longTermMemoryMode == LongTermMemoryMode.BOTH) {
                agentToolkit.registerTool(new LongTermMemoryTools(longTermMemory));
            }
            if (longTermMemoryMode == LongTermMemoryMode.STATIC_CONTROL
                    || longTermMemoryMode == LongTermMemoryMode.BOTH) {
                Memory contextView =
                        new AgentStateMemoryView(
                                () -> {
                                    ReActAgent a = selfRef.get();
                                    return a == null ? null : a.getAgentState();
                                });
                hooks.add(
                        new StaticLongTermMemoryHook(
                                longTermMemory, contextView, longTermMemoryAsyncRecord));
            }
        }

        /**
         * Configures RAG (Retrieval-Augmented Generation) based on the selected mode.
         */
        @SuppressWarnings("deprecation")
        private void configureRAG(Toolkit agentToolkit) {
            Knowledge aggregatedKnowledge =
                    knowledgeBases.size() == 1
                            ? knowledgeBases.iterator().next()
                            : buildAggregatedKnowledge();

            switch (ragMode) {
                case GENERIC -> hooks.add(new GenericRAGHook(aggregatedKnowledge, retrieveConfig));
                case AGENTIC ->
                        agentToolkit.registerTool(
                                new KnowledgeRetrievalTools(aggregatedKnowledge, retrieveConfig));
                case NONE -> {
                    // intentionally no-op
                }
            }
        }

        @SuppressWarnings("deprecation")
        private Knowledge buildAggregatedKnowledge() {
            return new Knowledge() {
                @Override
                public Mono<Void> addDocuments(List<Document> documents) {
                    return reactor.core.publisher.Flux.fromIterable(knowledgeBases)
                            .flatMap(kb -> kb.addDocuments(documents))
                            .then();
                }

                @Override
                public Mono<List<Document>> retrieve(String query, RetrieveConfig config) {
                    return reactor.core.publisher.Flux.fromIterable(knowledgeBases)
                            .flatMap(kb -> kb.retrieve(query, config))
                            .collectList()
                            .map(this::mergeAndSortResults);
                }

                private List<Document> mergeAndSortResults(List<List<Document>> allResults) {
                    return allResults.stream()
                            .flatMap(List::stream)
                            .collect(
                                    java.util.stream.Collectors.toMap(
                                            Document::getId,
                                            d -> d,
                                            (d1, d2) ->
                                                    d1.getScore() != null
                                                                    && d2.getScore() != null
                                                                    && d1.getScore() > d2.getScore()
                                                            ? d1
                                                            : d2))
                            .values()
                            .stream()
                            .sorted(
                                    java.util.Comparator.comparing(
                                            Document::getScore,
                                            java.util.Comparator.nullsLast(
                                                    java.util.Comparator.reverseOrder())))
                            .limit(retrieveConfig.getLimit())
                            .toList();
                }
            };
        }

        /**
         * Registers the built-in task-list tool ({@code todo_write}) and a per-turn reminder
         * middleware. Opt-in via {@link #enableTaskList()}.
         */
        private void configureTodoTools(Toolkit agentToolkit) {
            agentToolkit.registerTool(new io.agentscope.core.tool.builtin.TodoTools());
            middlewares.add(new io.agentscope.core.middleware.TaskReminderMiddleware());
        }

        /**
         * Configures SkillBox by binding the toolkit, registering the skill-load tool, uploading
         * skill files when auto-upload is enabled, and adding the SkillHook to the chain.
         */
        @SuppressWarnings("deprecation")
        private void configureSkillBox(Toolkit agentToolkit) {
            skillBox.bindToolkit(agentToolkit);
            skillBox.registerSkillLoadTool();
            if (skillBox.isAutoUploadSkill()) {
                skillBox.uploadSkillFiles();
            }
            hooks.add(new io.agentscope.core.skill.SkillHook(skillBox));
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

            // Rebind externally-constructed middleware that holds a reference to the
            // original (pre-copy) toolkit so it uses the agent's actual instance.
            for (MiddlewareBase mw : middlewares) {
                if (mw instanceof io.agentscope.core.tool.ToolkitAware aware) {
                    aware.rebindToolkit(agentToolkit);
                }
            }

            registerToolsFromHooks(agentToolkit);

            if (enableMetaTool) {
                agentToolkit.registerMetaTool();
            }

            // 1.x legacy compat: shared selfRef gives the long-term-memory hook (constructed
            // pre-agent) a way to resolve AgentState.context lazily once the agent exists.
            AtomicReference<ReActAgent> selfRef = new AtomicReference<>();

            if (longTermMemory != null) {
                configureLongTermMemory(agentToolkit, selfRef);
            }
            if (!knowledgeBases.isEmpty()) {
                configureRAG(agentToolkit);
            }
            if (taskListEnabled) {
                configureTodoTools(agentToolkit);
            }
            if (skillBox != null) {
                configureSkillBox(agentToolkit);
            }
            if (!skillRepositories.isEmpty() && dynamicSkillsEnabled) {
                middlewares.add(
                        new DynamicSkillMiddleware(
                                List.copyOf(skillRepositories),
                                agentToolkit,
                                skillFilter != null ? skillFilter : SkillFilter.all(),
                                skillCodeExecutionEnabled,
                                skillWorkDir));
            }

            ReActAgent agent = new ReActAgent(this, agentToolkit);
            selfRef.set(agent);

            return agent;
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
