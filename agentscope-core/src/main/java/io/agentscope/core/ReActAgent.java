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
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.agent.StructuredOutputCapableAgent;
import io.agentscope.core.agent.accumulator.ReasoningContext;
import io.agentscope.core.hook.ActingChunkEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PendingToolRecoveryHook;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PostSummaryEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.hook.PreSummaryEvent;
import io.agentscope.core.hook.ReasoningChunkEvent;
import io.agentscope.core.hook.SummaryChunkEvent;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.interruption.InterruptSource;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.memory.LongTermMemoryTools;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.memory.StaticLongTermMemoryHook;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.StructuredOutputReminder;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.rag.GenericRAGHook;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.KnowledgeRetrievalTools;
import io.agentscope.core.rag.RAGMode;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.session.Session;
import io.agentscope.core.shutdown.AgentShuttingDownException;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import io.agentscope.core.shutdown.PartialReasoningPolicy;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.SkillHook;
import io.agentscope.core.state.AgentMetaState;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.StatePersistence;
import io.agentscope.core.state.ToolkitState;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.tool.ToolResultMessageBuilder;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.ExceptionUtils;
import io.agentscope.core.util.MessageUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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
public class ReActAgent extends StructuredOutputCapableAgent {

    private static final Logger log = LoggerFactory.getLogger(ReActAgent.class);
    private static final GracefulShutdownManager shutdownManager =
            GracefulShutdownManager.getInstance();

    // ==================== Core Dependencies ====================

    private final Memory memory;
    private final String sysPrompt;
    private final Model model;
    private final int maxIters;
    private final ExecutionConfig modelExecutionConfig;
    private final ExecutionConfig toolExecutionConfig;
    private final GenerateOptions generateOptions;
    private final PlanNotebook planNotebook;
    private final ToolExecutionContext toolExecutionContext;
    private final StatePersistence statePersistence;
    private RuntimeContext pendingRuntimeContext;

    /**
     * Per-call system message, propagated across PreCallEvent → PreReasoningEvent /
     * PreSummaryEvent. It is safe to use an {@link java.util.concurrent.atomic.AtomicReference}
     * here because {@code AgentBase.acquireExecution()} guarantees that only one {@code call()}
     * runs concurrently per agent instance, so this reference is effectively owned by a single
     * logical execution at any time.
     */
    private final java.util.concurrent.atomic.AtomicReference<Msg> currentSystemMsg =
            new java.util.concurrent.atomic.AtomicReference<>();

    // ==================== Constructor ====================

    private ReActAgent(Builder builder, Toolkit agentToolkit) {
        super(
                builder.name,
                builder.description,
                builder.checkRunning,
                new ArrayList<>(builder.hooks),
                agentToolkit,
                builder.structuredOutputReminder);

        this.memory = builder.memory;
        this.sysPrompt = builder.sysPrompt;
        this.model = builder.model;
        this.maxIters = builder.maxIters;
        this.modelExecutionConfig = builder.modelExecutionConfig;
        this.toolExecutionConfig = builder.toolExecutionConfig;
        this.generateOptions = builder.generateOptions;
        this.planNotebook = builder.planNotebook;
        this.toolExecutionContext = builder.toolExecutionContext;
        this.statePersistence =
                builder.statePersistence != null
                        ? builder.statePersistence
                        : StatePersistence.all();
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
        if (sysPrompt != null && !sysPrompt.trim().isEmpty()) {
            return Msg.builder()
                    .name("system")
                    .role(MsgRole.SYSTEM)
                    .content(TextBlock.builder().text(sysPrompt).build())
                    .build();
        }
        return null;
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
     */
    public Mono<Msg> call(List<Msg> msgs, RuntimeContext context) {
        this.pendingRuntimeContext = context;
        return call(msgs);
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

    // ==================== New StateModule API ====================

    /**
     * Save agent state to the session using the new API.
     *
     * <p>This method saves the state of all managed components according to the StatePersistence
     * configuration:
     *
     * <ul>
     *   <li>Agent metadata (always saved)
     *   <li>Memory messages (if memoryManaged is true)
     *   <li>Toolkit activeGroups (if toolkitManaged is true)
     *   <li>PlanNotebook state (if planNotebookManaged is true)
     * </ul>
     *
     * @param session the session to save state to
     * @param sessionKey the session identifier
     */
    @Override
    public void saveTo(Session session, SessionKey sessionKey) {
        // Save agent metadata
        session.save(
                sessionKey,
                "agent_meta",
                new AgentMetaState(getAgentId(), getName(), getDescription(), sysPrompt));

        // Save memory if managed
        if (statePersistence.memoryManaged()) {
            memory.saveTo(session, sessionKey);
        }

        // Save toolkit activeGroups if managed
        if (statePersistence.toolkitManaged() && toolkit != null) {
            session.save(
                    sessionKey,
                    "toolkit_activeGroups",
                    new ToolkitState(toolkit.getActiveGroups()));
        }

        // Save PlanNotebook if managed
        if (statePersistence.planNotebookManaged() && planNotebook != null) {
            planNotebook.saveTo(session, sessionKey);
        }
    }

    /**
     * Load agent state from the session using the new API.
     *
     * <p>This method loads the state of all managed components according to the StatePersistence
     * configuration.
     *
     * @param session the session to load state from
     * @param sessionKey the session identifier
     */
    @Override
    public boolean loadIfExists(Session session, SessionKey sessionKey) {
        shutdownManager.bindSession(this, session, sessionKey);
        return super.loadIfExists(session, sessionKey);
    }

    @Override
    public void loadFrom(Session session, SessionKey sessionKey) {
        shutdownManager.bindSession(this, session, sessionKey);
        // Load memory if managed
        if (statePersistence.memoryManaged()) {
            memory.loadFrom(session, sessionKey);
        }

        // Load toolkit activeGroups if managed
        if (statePersistence.toolkitManaged() && toolkit != null) {
            session.get(sessionKey, "toolkit_activeGroups", ToolkitState.class)
                    .ifPresent(state -> toolkit.setActiveGroups(state.activeGroups()));
        }

        // Load PlanNotebook if managed
        if (statePersistence.planNotebookManaged() && planNotebook != null) {
            planNotebook.loadFrom(session, sessionKey);
        }
    }

    // ==================== Protected API ====================

    @Override
    protected Mono<Msg> doCall(List<Msg> msgs) {
        Set<String> pendingIds = getPendingToolUseIds();

        // No pending tools -> normal processing
        if (pendingIds.isEmpty()) {
            addToMemory(msgs);
            return executeIteration(0);
        }

        // Has pending tools but no input -> resume (execute pending tools directly)
        if (msgs == null || msgs.isEmpty()) {
            return acting(0);
        }

        // Has pending tools + input -> check if user provided tool results
        List<ToolResultBlock> providedResults =
                msgs.stream()
                        .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                        .toList();

        if (!providedResults.isEmpty()) {
            // User provided tool results -> validate and add
            validateAndAddToolResults(msgs, pendingIds);
            return hasPendingToolUse() ? acting(0) : executeIteration(0);
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
     * Find the last assistant message in memory.
     *
     * @return The last assistant message, or null if not found
     */
    private Msg findLastAssistantMsg() {
        List<Msg> memoryMsgs = memory.getMessages();
        for (int i = memoryMsgs.size() - 1; i >= 0; i--) {
            Msg msg = memoryMsgs.get(i);
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
                memory.getMessages().stream()
                        .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                        .map(ToolResultBlock::getId)
                        .collect(Collectors.toSet());

        return lastAssistant.getContentBlocks(ToolUseBlock.class).stream()
                .map(ToolUseBlock::getId)
                .filter(id -> !existingResultIds.contains(id))
                .collect(Collectors.toSet());
    }

    /**
     * Validate input messages when there are pending tool calls, then add to memory.
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

        msgs.forEach(memory::addMessage);
    }

    /**
     * Add messages to memory if not null.
     *
     * @param msgs The messages to add
     */
    private void addToMemory(List<Msg> msgs) {
        if (msgs != null) {
            msgs.forEach(memory::addMessage);
        }
    }

    // ==================== Core ReAct Loop ====================

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
                .then(notifyPreReasoningEvent(memory.getMessages()))
                .flatMapMany(
                        event -> {
                            GenerateOptions options =
                                    event.getEffectiveGenerateOptions() != null
                                            ? event.getEffectiveGenerateOptions()
                                            : buildGenerateOptions();
                            List<Msg> modelInput =
                                    prependSystemMsg(
                                            event.getInputMessages(), event.getSystemMessage());
                            return model.stream(modelInput, toolkit.getToolSchemas(), options)
                                    .concatMap(chunk -> checkInterruptedAsync().thenReturn(chunk));
                        })
                .doOnNext(
                        chunk -> {
                            List<Msg> chunkMsgs = context.processChunk(chunk);
                            // Notify streaming hooks for each chunk message
                            for (Msg msg : chunkMsgs) {
                                notifyReasoningChunk(msg, context).subscribe();
                            }
                        })
                .then(Mono.defer(() -> Mono.justOrEmpty(context.buildFinalMessage())))
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
                                // Manually interruption will save the msg, while system
                                // interruption will discard on specific config
                                if (!discard) {
                                    memory.addMessage(msg);
                                }
                            }
                            return Mono.error(error);
                        })
                .flatMap(this::notifyPostReasoning)
                .flatMap(
                        event -> {
                            Msg msg = event.getReasoningMessage();
                            if (msg != null) {
                                memory.addMessage(msg);
                            }

                            // HITL stop
                            if (event.isStopRequested()) {
                                return Mono.just(
                                        msg.withGenerateReason(
                                                GenerateReason.REASONING_STOP_REQUESTED));
                            }

                            // gotoReasoning requested (e.g., by StructuredOutputHook)
                            if (event.isGotoReasoningRequested()) {
                                // Validation already done in PostReasoningEvent.gotoReasoning()
                                List<Msg> gotoMsgs = event.getGotoReasoningMsgs();
                                if (gotoMsgs != null) {
                                    gotoMsgs.forEach(memory::addMessage);
                                }
                                // Continue to next iteration, ignoring maxIters for this entry
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
     * Execute the acting phase.
     *
     * <p>This method executes only pending tools (those without results in memory),
     * notifies hooks for successful tool results, and decides whether to continue iteration
     * or return (HITL stop, suspended tools, or structured output).
     *
     * <p>For tools that throw {@link io.agentscope.core.tool.ToolSuspendException}:
     * <ul>
     *   <li>The exception is caught by Toolkit and converted to a pending ToolResultBlock</li>
     *   <li>Successful results are stored in memory, pending results are not</li>
     *   <li>Returns Msg with {@link GenerateReason#TOOL_SUSPENDED} containing suspended ToolUseBlocks</li>
     * </ul>
     *
     * @param iter Current iteration number
     * @return Mono containing the final result message
     */
    private Mono<Msg> acting(int iter) {
        // Extract only pending tool calls (those without results in memory)
        List<ToolUseBlock> pendingToolCalls = extractPendingToolCalls();

        if (pendingToolCalls.isEmpty()) {
            // No pending tools have been executed, continue to next iteration
            return executeIteration(iter + 1);
        }

        // Forward tool chunks into ActingChunkEvent hooks without overwriting user callbacks.
        toolkit.setInternalChunkCallback(
                (toolUse, chunk) -> notifyActingChunk(toolUse, chunk).subscribe());

        // Execute only pending tools (those without results in memory)
        return notifyPreActingHooks(pendingToolCalls)
                .flatMap(this::executeToolCalls)
                .flatMap(
                        results -> {
                            // Separate success and pending results
                            List<Map.Entry<ToolUseBlock, ToolResultBlock>> successPairs =
                                    results.stream()
                                            .filter(e -> !e.getValue().isSuspended())
                                            .toList();
                            List<Map.Entry<ToolUseBlock, ToolResultBlock>> pendingPairs =
                                    results.stream()
                                            .filter(e -> e.getValue().isSuspended())
                                            .toList();

                            // If no success results to process
                            if (successPairs.isEmpty()) {
                                if (!pendingPairs.isEmpty()) {
                                    return Mono.just(buildSuspendedMsg(pendingPairs));
                                }
                                return executeIteration(iter + 1);
                            }

                            // Process success results through hooks and add to memory
                            return Flux.fromIterable(successPairs)
                                    .concatMap(this::notifyPostActingHook)
                                    .last()
                                    .flatMap(
                                            event -> {
                                                // HITL stop (also triggered by
                                                // StructuredOutputHook when completed)
                                                if (event.isStopRequested()) {
                                                    return Mono.just(
                                                            event.getToolResultMsg()
                                                                    .withGenerateReason(
                                                                            GenerateReason
                                                                                    .ACTING_STOP_REQUESTED));
                                                }

                                                // If there are pending results, build suspended Msg
                                                if (!pendingPairs.isEmpty()) {
                                                    return Mono.just(
                                                            buildSuspendedMsg(pendingPairs));
                                                }

                                                // Continue next iteration
                                                return executeIteration(iter + 1);
                                            });
                        });
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
     * Notify PostActingEvent hook for a single tool result, build message and add to memory.
     */
    private Mono<PostActingEvent> notifyPostActingHook(
            Map.Entry<ToolUseBlock, ToolResultBlock> entry) {
        ToolUseBlock toolUse = entry.getKey();
        ToolResultBlock result = entry.getValue();

        // Build tool result message first so hooks can access it
        Msg toolMsg = ToolResultMessageBuilder.buildToolResultMsg(result, toolUse, getName());

        // Create event with toolResultMsg already set
        PostActingEvent event = new PostActingEvent(this, toolkit, toolUse, result);
        event.setToolResultMsg(toolMsg);

        // Notify hooks and add to memory
        return notifyHooks(event).doOnNext(e -> memory.addMessage(e.getToolResultMsg()));
    }

    /**
     * Generate summary when max iterations reached.
     */
    protected Mono<Msg> summarizing() {
        log.debug("Maximum iterations reached. Generating summary...");

        List<Msg> messageList = prepareSummaryMessages();
        GenerateOptions generateOptions = buildGenerateOptions();

        return notifyPreSummaryHook(messageList, generateOptions)
                .flatMap(
                        preSummaryEvent -> {
                            List<Msg> effectiveMessages =
                                    prependSystemMsg(
                                            preSummaryEvent.getInputMessages(),
                                            preSummaryEvent.getSystemMessage());
                            GenerateOptions effectiveOptions =
                                    preSummaryEvent.getEffectiveGenerateOptions();

                            return streamAndAccumulateSummary(effectiveMessages, effectiveOptions)
                                    .flatMap(
                                            msg ->
                                                    notifyPostSummaryHook(msg, effectiveOptions)
                                                            .map(
                                                                    postEvent -> {
                                                                        Msg finalMsg =
                                                                                postEvent
                                                                                        .getSummaryMessage()
                                                                                        .withGenerateReason(
                                                                                                GenerateReason
                                                                                                        .MAX_ITERATIONS);
                                                                        memory.addMessage(finalMsg);
                                                                        return finalMsg;
                                                                    }));
                        })
                .onErrorResume(this::handleSummaryError);
    }

    private Mono<Msg> streamAndAccumulateSummary(
            List<Msg> messages, GenerateOptions generateOptions) {
        return model.stream(messages, null, generateOptions)
                .concatMap(chunk -> checkInterruptedAsync().thenReturn(chunk))
                .reduce(
                        new ReasoningContext(getName()),
                        (ctx, chunk) -> {
                            List<Msg> streamedMessages = ctx.processChunk(chunk);
                            for (Msg streamedMessage : streamedMessages) {
                                notifySummaryChunk(streamedMessage, ctx, generateOptions)
                                        .subscribe();
                            }
                            return ctx;
                        })
                .map(ReasoningContext::buildFinalMessage);
    }

    private List<Msg> prepareSummaryMessages() {
        List<Msg> messageList = new ArrayList<>(memory.getMessages());
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
        memory.addMessage(errorMsg);
        return Mono.just(errorMsg);
    }

    // ==================== Helper Methods ====================

    /**
     * Prepends the system message to {@code msgs} if non-null.
     *
     * <p>Called immediately before each {@code model.stream()} invocation to build the final
     * LLM input without contaminating the in-memory message list.
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
        return MessageUtils.extractRecentToolCalls(memory.getMessages(), getName());
    }

    /**
     * Extract only pending tool calls (those without results in memory) from the most recent
     * assistant message.
     *
     * <p>This method filters out tool calls that already have corresponding results in memory,
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

    // ==================== Hook Notification Methods ====================

    /**
     * Generic hook notification method.
     */
    private <T extends HookEvent> Mono<T> notifyHooks(T event) {
        Mono<T> result = Mono.just(event);
        for (Hook hook : getSortedHooks()) {
            result = result.flatMap(hook::onEvent);
        }
        return result;
    }

    private Mono<PreReasoningEvent> notifyPreReasoningEvent(List<Msg> msgs) {
        PreReasoningEvent event = new PreReasoningEvent(this, model.getModelName(), null, msgs);
        event.setSystemMessage(currentSystemMsg.get());
        return notifyHooks(event);
    }

    private Mono<PostReasoningEvent> notifyPostReasoning(Msg msg) {
        return notifyHooks(new PostReasoningEvent(this, model.getModelName(), null, msg));
    }

    private Mono<List<ToolUseBlock>> notifyPreActingHooks(List<ToolUseBlock> toolCalls) {
        return Flux.fromIterable(toolCalls)
                .concatMap(tool -> notifyHooks(new PreActingEvent(this, toolkit, tool)))
                .map(PreActingEvent::getToolUse)
                .collectList();
    }

    private Mono<Void> notifyActingChunk(ToolUseBlock toolUse, ToolResultBlock chunk) {
        ActingChunkEvent event =
                new ActingChunkEvent(
                        this,
                        toolkit,
                        toolUse,
                        chunk.withIdAndName(toolUse.getId(), toolUse.getName()));
        return Flux.fromIterable(getSortedHooks()).flatMap(hook -> hook.onEvent(event)).then();
    }

    private Mono<Void> notifyReasoningChunk(Msg chunkMsg, ReasoningContext context) {
        ContentBlock content = chunkMsg.getFirstContentBlock();

        ContentBlock accumulatedContent = null;
        if (content instanceof TextBlock) {
            accumulatedContent = TextBlock.builder().text(context.getAccumulatedText()).build();
        } else if (content instanceof ThinkingBlock) {
            accumulatedContent =
                    ThinkingBlock.builder().thinking(context.getAccumulatedThinking()).build();
        } else if (content instanceof ToolUseBlock tub) {
            // Support streaming ToolUseBlock events
            ToolUseBlock accumulated = context.getAccumulatedToolCall(tub.getId());
            if (accumulated != null) {
                accumulatedContent = accumulated;
            } else {
                // If no accumulated data, use the current chunk directly
                accumulatedContent = tub;
            }
        }

        if (accumulatedContent != null) {
            Msg accumulated =
                    Msg.builder()
                            .id(chunkMsg.getId())
                            .name(chunkMsg.getName())
                            .role(chunkMsg.getRole())
                            .content(accumulatedContent)
                            .build();
            if (context.getChatUsage() != null) {
                accumulated
                        .getMetadata()
                        .put(MessageMetadataKeys.CHAT_USAGE, context.getChatUsage());
            }
            ReasoningChunkEvent event =
                    new ReasoningChunkEvent(
                            this, model.getModelName(), null, chunkMsg, accumulated);
            return Flux.fromIterable(getSortedHooks()).flatMap(hook -> hook.onEvent(event)).then();
        }

        return Mono.empty();
    }

    // ==================== Summary Hook Notification Methods ====================

    private Mono<PreSummaryEvent> notifyPreSummaryHook(
            List<Msg> msgs, GenerateOptions generateOptions) {
        PreSummaryEvent event =
                new PreSummaryEvent(
                        this, model.getModelName(), generateOptions, msgs, maxIters, maxIters);
        event.setSystemMessage(currentSystemMsg.get());
        return notifyHooks(event);
    }

    private Mono<PostSummaryEvent> notifyPostSummaryHook(Msg msg, GenerateOptions generateOptions) {
        return notifyHooks(new PostSummaryEvent(this, model.getModelName(), generateOptions, msg));
    }

    private Mono<Void> notifySummaryChunk(
            Msg chunkMsg, ReasoningContext context, GenerateOptions generateOptions) {
        ContentBlock content = chunkMsg.getFirstContentBlock();

        ContentBlock accumulatedContent = null;
        if (content instanceof TextBlock) {
            accumulatedContent = TextBlock.builder().text(context.getAccumulatedText()).build();
        } else if (content instanceof ThinkingBlock) {
            accumulatedContent =
                    ThinkingBlock.builder().thinking(context.getAccumulatedThinking()).build();
        }

        if (accumulatedContent != null) {
            Msg accumulated =
                    Msg.builder()
                            .id(chunkMsg.getId())
                            .name(chunkMsg.getName())
                            .role(chunkMsg.getRole())
                            .content(accumulatedContent)
                            .build();
            if (context.getChatUsage() != null) {
                accumulated
                        .getMetadata()
                        .put(MessageMetadataKeys.CHAT_USAGE, context.getChatUsage());
            }
            SummaryChunkEvent event =
                    new SummaryChunkEvent(
                            this, model.getModelName(), generateOptions, chunkMsg, accumulated);
            return Flux.fromIterable(getSortedHooks()).flatMap(hook -> hook.onEvent(event)).then();
        }

        return Mono.empty();
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

        memory.addMessage(recoveryMsg);
        return Mono.just(recoveryMsg);
    }

    @Override
    protected Mono<Void> doObserve(Msg msg) {
        if (msg != null) {
            memory.addMessage(msg);
        }
        return Mono.empty();
    }

    // ==================== Getters ====================

    @Override
    public Memory getMemory() {
        return memory;
    }

    public void setMemory(Memory memory) {
        throw new UnsupportedOperationException(
                "Memory cannot be replaced after agent construction. "
                        + "Create a new agent instance if you need different memory.");
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

    public PlanNotebook getPlanNotebook() {
        return planNotebook;
    }

    /**
     * Gets the configured generation options for this agent.
     *
     * @return The generation options, or null if not configured
     */
    public GenerateOptions getGenerateOptions() {
        return generateOptions;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ==================== Builder ====================

    public static class Builder {
        private String name;
        private String description;
        private String sysPrompt;
        private boolean checkRunning = true;
        private Model model;
        private Toolkit toolkit = new Toolkit();
        private Memory memory = new InMemoryMemory();
        private int maxIters = 10;
        private ExecutionConfig modelExecutionConfig;
        private ExecutionConfig toolExecutionConfig;
        private GenerateOptions generateOptions;
        private final Set<Hook> hooks = new LinkedHashSet<>();
        private boolean enableMetaTool = false;
        private StructuredOutputReminder structuredOutputReminder =
                StructuredOutputReminder.TOOL_CHOICE;
        private PlanNotebook planNotebook;
        private SkillBox skillBox;
        private ToolExecutionContext toolExecutionContext;
        private boolean enablePendingToolRecovery = false;

        // Long-term memory configuration
        private LongTermMemory longTermMemory;
        private LongTermMemoryMode longTermMemoryMode = LongTermMemoryMode.BOTH;
        private boolean longTermMemoryAsyncRecord = false;

        // State persistence configuration
        private StatePersistence statePersistence;

        // RAG configuration
        private final Set<Knowledge> knowledgeBases = new LinkedHashSet<>();
        private RAGMode ragMode = RAGMode.GENERIC;
        private RetrieveConfig retrieveConfig =
                RetrieveConfig.builder().limit(5).scoreThreshold(0.5).build();

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
         * Sets the memory for storing conversation history.
         *
         * @param memory The memory implementation, can be null (defaults to InMemoryMemory)
         * @return This builder instance for method chaining
         */
        public Builder memory(Memory memory) {
            this.memory = memory;
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
         * Sets the PlanNotebook for plan-based task execution.
         *
         * <p>When provided, the PlanNotebook will be integrated into the agent:
         * <ul>
         *   <li>Plan management tools will be automatically registered to the toolkit
         *   <li>A hook will be added to inject plan hints before each reasoning step
         * </ul>
         *
         * @param planNotebook The configured PlanNotebook instance, can be null
         * @return This builder instance for method chaining
         */
        public Builder planNotebook(PlanNotebook planNotebook) {
            this.planNotebook = planNotebook;
            return this;
        }

        /**
         * Sets the skill box for this agent.
         *
         * <p>The skill box is used to manage the skills for this agent. It will be used to register the skills to the toolkit.
         * <ul>
         *   <li>Skill loader tools will be automatically registered to the toolkit</li>
         *   <li>A skill hook will be added to inject skill prompts on {@link io.agentscope.core.hook.PreCallEvent}
         *       and manage skill activation</li>
         * </ul>
         * @param skillBox The skill box to use for this agent
         * @return This builder instance for method chaining
         */
        public Builder skillBox(SkillBox skillBox) {
            this.skillBox = skillBox;
            return this;
        }

        /**
         * Sets the long-term memory for this agent.
         *
         * <p>Long-term memory enables the agent to remember information across sessions.
         * It can be used in combination with {@link #longTermMemoryMode(LongTermMemoryMode)}
         * to control whether memory management is automatic, agent-controlled, or both.
         *
         * @param longTermMemory The long-term memory implementation
         * @return This builder instance for method chaining
         * @see LongTermMemoryMode
         */
        public Builder longTermMemory(LongTermMemory longTermMemory) {
            this.longTermMemory = longTermMemory;
            return this;
        }

        /**
         * Sets the long-term memory mode.
         *
         * <p>This determines how long-term memory is integrated with the agent:
         * <ul>
         *   <li><b>AGENT_CONTROL:</b> Memory tools are registered for agent to call</li>
         *   <li><b>STATIC_CONTROL:</b> Framework automatically retrieves/records memory</li>
         *   <li><b>BOTH:</b> Combines both approaches (default)</li>
         * </ul>
         *
         * @param mode The long-term memory mode
         * @return This builder instance for method chaining
         * @see LongTermMemoryMode
         */
        public Builder longTermMemoryMode(LongTermMemoryMode mode) {
            this.longTermMemoryMode = mode;
            return this;
        }

        /**
         * Sets whether long-term memory recording should be performed asynchronously.
         *
         * <p>When enabled, the framework will record memories to long-term storage
         * in a fire-and-forget manner, without blocking the agent's main execution flow.
         * This improves response latency but means memory persistence is not guaranteed
         * before the agent returns its response.
         *
         * <p>When disabled (default), the framework waits for the recording operation
         * to complete before returning the agent's response. This ensures memory
         * persistence is finalized but may increase response latency.
         *
         * <p>Note: This setting only affects the static control mode (STATIC_CONTROL, BOTH).
         * Agent-controlled recording through tools is always synchronous.
         *
         * @param asyncRecord Whether to record memories asynchronously
         * @return This builder instance for method chaining
         */
        public Builder longTermMemoryAsyncRecord(boolean asyncRecord) {
            this.longTermMemoryAsyncRecord = asyncRecord;
            return this;
        }

        /**
         * Sets the state persistence configuration.
         *
         * <p>Use this to control which components' state is managed by the agent during
         * saveTo/loadFrom operations. By default, all components are managed.
         *
         * <p>Example usage:
         *
         * <pre>{@code
         * ReActAgent agent = ReActAgent.builder()
         *     .name("assistant")
         *     .model(model)
         *     .statePersistence(StatePersistence.builder()
         *         .planNotebookManaged(false)  // Let user manage PlanNotebook separately
         *         .build())
         *     .build();
         * }</pre>
         *
         * @param statePersistence The state persistence configuration
         * @return This builder instance for method chaining
         * @see StatePersistence
         */
        public Builder statePersistence(StatePersistence statePersistence) {
            this.statePersistence = statePersistence;
            return this;
        }

        /**
         * Enables plan functionality with default configuration.
         *
         * <p>This is a convenience method equivalent to:
         * <pre>{@code
         * planNotebook(PlanNotebook.builder().build())
         * }</pre>
         *
         * @return This builder instance for method chaining
         */
        public Builder enablePlan() {
            this.planNotebook = PlanNotebook.builder().build();
            return this;
        }

        /**
         * Adds a knowledge base for RAG (Retrieval-Augmented Generation).
         *
         * @param knowledge The knowledge base to add
         * @return This builder instance for method chaining
         */
        public Builder knowledge(Knowledge knowledge) {
            if (knowledge != null) {
                this.knowledgeBases.add(knowledge);
            }
            return this;
        }

        /**
         * Adds multiple knowledge bases for RAG.
         *
         * @param knowledges The list of knowledge bases to add
         * @return This builder instance for method chaining
         */
        public Builder knowledges(List<Knowledge> knowledges) {
            if (knowledges != null) {
                this.knowledgeBases.addAll(knowledges);
            }
            return this;
        }

        /**
         * Sets the RAG mode.
         *
         * @param mode The RAG mode (GENERIC, AGENTIC, or NONE)
         * @return This builder instance for method chaining
         */
        public Builder ragMode(RAGMode mode) {
            if (mode != null) {
                this.ragMode = mode;
            }
            return this;
        }

        /**
         * Sets the retrieve configuration for RAG.
         *
         * @param config The retrieve configuration
         * @return This builder instance for method chaining
         */
        public Builder retrieveConfig(RetrieveConfig config) {
            if (config != null) {
                this.retrieveConfig = config;
            }
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
         * Builds and returns a new ReActAgent instance with the configured settings.
         *
         * @return A new ReActAgent instance
         * @throws IllegalArgumentException if required parameters are missing or invalid
         */
        public ReActAgent build() {
            // Deep copy toolkit to avoid state interference between agents
            Toolkit agentToolkit = this.toolkit.copy();

            registerToolsFromHooks(agentToolkit);

            if (enableMetaTool) {
                agentToolkit.registerMetaTool();
            }

            // Register PendingToolRecoveryHook if enabled
            if (enablePendingToolRecovery) {
                hooks.add(new PendingToolRecoveryHook());
            }

            // Configure long-term memory if provided
            if (longTermMemory != null) {
                configureLongTermMemory(agentToolkit);
            }

            // Configure RAG if knowledge bases are provided
            if (!knowledgeBases.isEmpty()) {
                configureRAG(agentToolkit);
            }

            // Configure PlanNotebook if provided
            if (planNotebook != null) {
                configurePlan(agentToolkit);
            }

            // Configure SkillBox if provided
            if (skillBox != null) {
                configureSkillBox(agentToolkit);
            }

            return new ReActAgent(this, agentToolkit);
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

        /**
         * Configures long-term memory based on the selected mode.
         *
         * <p>This method sets up long-term memory integration:
         * <ul>
         *   <li>AGENT_CONTROL: Registers memory tools for agent to call</li>
         *   <li>STATIC_CONTROL: Registers StaticLongTermMemoryHook for automatic retrieval/recording</li>
         *   <li>BOTH: Combines both approaches (registers tools + hook)</li>
         * </ul>
         */
        private void configureLongTermMemory(Toolkit agentToolkit) {
            // If agent control is enabled, register memory tools via adapter
            if (longTermMemoryMode == LongTermMemoryMode.AGENT_CONTROL
                    || longTermMemoryMode == LongTermMemoryMode.BOTH) {
                agentToolkit.registerTool(new LongTermMemoryTools(longTermMemory));
            }

            // If static control is enabled, register the hook for automatic memory management
            if (longTermMemoryMode == LongTermMemoryMode.STATIC_CONTROL
                    || longTermMemoryMode == LongTermMemoryMode.BOTH) {
                StaticLongTermMemoryHook hook =
                        new StaticLongTermMemoryHook(
                                longTermMemory, memory, longTermMemoryAsyncRecord);
                hooks.add(hook);
            }
        }

        /**
         * Configures RAG (Retrieval-Augmented Generation) based on the selected mode.
         *
         * <p>This method automatically sets up the appropriate hooks or tools based on the RAG mode:
         * <ul>
         *   <li>GENERIC: Adds a GenericRAGHook to automatically inject knowledge</li>
         *   <li>AGENTIC: Registers KnowledgeRetrievalTools for agent-controlled retrieval</li>
         *   <li>NONE: Does nothing</li>
         * </ul>
         */
        private void configureRAG(Toolkit agentToolkit) {
            // Aggregate knowledge bases if multiple are provided
            Knowledge aggregatedKnowledge;
            if (knowledgeBases.size() == 1) {
                aggregatedKnowledge = knowledgeBases.iterator().next();
            } else {
                aggregatedKnowledge = buildAggregatedKnowledge();
            }

            // Configure based on mode
            switch (ragMode) {
                case GENERIC -> {
                    // Create and add GenericRAGHook
                    GenericRAGHook ragHook =
                            new GenericRAGHook(aggregatedKnowledge, retrieveConfig);
                    hooks.add(ragHook);
                }
                case AGENTIC -> {
                    // Register knowledge retrieval tools
                    KnowledgeRetrievalTools tools =
                            new KnowledgeRetrievalTools(aggregatedKnowledge, retrieveConfig);
                    agentToolkit.registerTool(tools);
                }
                case NONE -> {
                    // Do nothing
                }
            }
        }

        private Knowledge buildAggregatedKnowledge() {
            return new Knowledge() {
                @Override
                public Mono<Void> addDocuments(List<Document> documents) {
                    return Flux.fromIterable(knowledgeBases)
                            .flatMap(kb -> kb.addDocuments(documents))
                            .then();
                }

                @Override
                public Mono<List<Document>> retrieve(String query, RetrieveConfig config) {
                    return Flux.fromIterable(knowledgeBases)
                            .flatMap(kb -> kb.retrieve(query, config))
                            .collectList()
                            .map(this::mergeAndSortResults);
                }

                private List<Document> mergeAndSortResults(List<List<Document>> allResults) {
                    return allResults.stream()
                            .flatMap(List::stream)
                            .collect(
                                    Collectors.toMap(
                                            Document::getId,
                                            doc -> doc,
                                            (doc1, doc2) ->
                                                    doc1.getScore() != null
                                                                    && doc2.getScore() != null
                                                                    && doc1.getScore()
                                                                            > doc2.getScore()
                                                            ? doc1
                                                            : doc2))
                            .values()
                            .stream()
                            .sorted(
                                    Comparator.comparing(
                                            Document::getScore,
                                            Comparator.nullsLast(Comparator.reverseOrder())))
                            .limit(retrieveConfig.getLimit())
                            .toList();
                }
            };
        }

        /**
         * Configures PlanNotebook integration.
         *
         * <p>This method automatically:
         * <ul>
         *   <li>Registers plan management tools to the toolkit
         *   <li>Adds a hook to inject plan hints before each reasoning step
         * </ul>
         */
        private void configurePlan(Toolkit agentToolkit) {
            // Register plan tools to toolkit
            agentToolkit.registerTool(planNotebook);

            // Add plan hint hook
            Hook planHintHook =
                    new Hook() {
                        @Override
                        public <T extends HookEvent> Mono<T> onEvent(T event) {
                            if (event instanceof PreReasoningEvent) {
                                PreReasoningEvent e = (PreReasoningEvent) event;
                                return planNotebook
                                        .getCurrentHint()
                                        .map(
                                                hintMsg -> {
                                                    List<Msg> modifiedMsgs =
                                                            new ArrayList<>(e.getInputMessages());
                                                    modifiedMsgs.add(hintMsg);
                                                    e.setInputMessages(modifiedMsgs);
                                                    return (T) e;
                                                })
                                        .defaultIfEmpty(event);
                            }
                            return Mono.just(event);
                        }
                    };

            hooks.add(planHintHook);
        }

        /**
         * Configures SkillBox integration.
         *
         * <p>This method automatically:
         * <ul>
         *   <li>Registers skill load tool to the toolkit
         *   <li>Adds the skill hook to inject skill prompts on {@link io.agentscope.core.hook.PreCallEvent}
         *       (priority {@link io.agentscope.core.skill.SkillHook#SKILL_HOOK_PRIORITY}) and manage skill
         *       activation
         *   <li>Uploads skill files to the upload directory if auto upload is enabled
         * </ul>
         */
        private void configureSkillBox(Toolkit agentToolkit) {
            skillBox.bindToolkit(agentToolkit);
            // Register skill loader tools to toolkit
            skillBox.registerSkillLoadTool();

            // If auto upload is enabled, upload skill files
            if (skillBox.isAutoUploadSkill()) {
                skillBox.uploadSkillFiles();
            }

            hooks.add(new SkillHook(skillBox));
        }
    }
}
