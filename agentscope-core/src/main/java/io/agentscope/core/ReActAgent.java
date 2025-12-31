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
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.StructuredOutputHandler;
import io.agentscope.core.agent.accumulator.ReasoningContext;
import io.agentscope.core.hook.ActingChunkEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.hook.ReasoningChunkEvent;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.memory.LongTermMemoryTools;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.memory.StaticLongTermMemoryHook;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.StructuredOutputReminder;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.rag.GenericRAGHook;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.KnowledgeRetrievalTools;
import io.agentscope.core.rag.RAGMode;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.session.Session;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.SkillHook;
import io.agentscope.core.state.AgentMetaState;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.StatePersistence;
import io.agentscope.core.state.ToolkitState;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.tool.ToolResultMessageBuilder;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.MessageUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
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
 * <p><b>Architecture:</b> The agent is organized into specialized components for maintainability:
 * <ul>
 *   <li><b>Core Loop:</b> Manages iteration flow and phase transitions
 *   <li><b>Phase Pipelines:</b> ReasoningPipeline, ActingPipeline, SummarizingPipeline handle each phase
 *   <li><b>Internal Helpers:</b> HookNotifier for hooks, MessagePreparer for message formatting
 *   <li><b>Structured Output:</b> StructuredOutputHandler provides type-safe output generation
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
 * @see StructuredOutputHandler
 */
public class ReActAgent extends AgentBase {

    private static final Logger log = LoggerFactory.getLogger(ReActAgent.class);

    // ==================== Core Dependencies ====================

    private final Memory memory;
    private final String sysPrompt;
    private final Model model;
    private final Toolkit toolkit;
    private final int maxIters;
    private final ExecutionConfig modelExecutionConfig;
    private final ExecutionConfig toolExecutionConfig;
    private final StructuredOutputReminder structuredOutputReminder;
    private final PlanNotebook planNotebook;
    private final ToolExecutionContext toolExecutionContext;
    private final StatePersistence statePersistence;

    // ==================== Internal Components ====================

    private final HookNotifier hookNotifier;
    private final MessagePreparer messagePreparer;

    // Current StructuredOutputHandler for internal hook access
    // Using AtomicReference for proper thread safety in reactive streams context
    private final AtomicReference<StructuredOutputHandler> currentStructuredOutputHandler =
            new AtomicReference<>();

    // ==================== Constructor ====================

    private ReActAgent(Builder builder) {
        super(builder.name, builder.description, builder.checkRunning, builder.hooks);

        this.memory = builder.memory;
        this.sysPrompt = builder.sysPrompt;
        this.model = builder.model;
        this.toolkit = builder.toolkit;
        this.maxIters = builder.maxIters;
        this.modelExecutionConfig = builder.modelExecutionConfig;
        this.toolExecutionConfig = builder.toolExecutionConfig;
        this.structuredOutputReminder = builder.structuredOutputReminder;
        this.planNotebook = builder.planNotebook;
        this.toolExecutionContext = builder.toolExecutionContext;
        this.statePersistence =
                builder.statePersistence != null
                        ? builder.statePersistence
                        : StatePersistence.all();

        this.hookNotifier = new HookNotifier();
        this.messagePreparer = new MessagePreparer();
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
    public void loadFrom(Session session, SessionKey sessionKey) {
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
        if (msgs != null) {
            msgs.forEach(memory::addMessage);
        }
        return executeReActLoop(null);
    }

    @Override
    protected Mono<Msg> doCall(List<Msg> msgs, Class<?> structuredOutputClass) {
        if (msgs != null && !msgs.isEmpty()) {
            msgs.forEach(memory::addMessage);
        }

        StructuredOutputHandler handler =
                new StructuredOutputHandler(
                        structuredOutputClass,
                        null,
                        toolkit,
                        memory,
                        getName(),
                        structuredOutputReminder);

        return Mono.defer(
                () -> {
                    // Set current handler for internal hook access
                    this.currentStructuredOutputHandler.set(handler);

                    handler.prepare();
                    return executeReActLoop(handler)
                            .flatMap(result -> Mono.just(handler.extractFinalResult()))
                            .doFinally(
                                    signal -> {
                                        handler.cleanup();
                                        // Clear current handler reference
                                        this.currentStructuredOutputHandler.set(null);
                                    });
                });
    }

    @Override
    protected Mono<Msg> doCall(List<Msg> msgs, JsonNode outputSchema) {
        if (msgs != null && !msgs.isEmpty()) {
            msgs.forEach(memory::addMessage);
        }

        StructuredOutputHandler handler =
                new StructuredOutputHandler(
                        null, outputSchema, toolkit, memory, getName(), structuredOutputReminder);

        return Mono.defer(
                () -> {
                    // Set current handler for internal hook access
                    this.currentStructuredOutputHandler.set(handler);

                    handler.prepare();
                    return executeReActLoop(handler)
                            .flatMap(result -> Mono.just(handler.extractFinalResult()))
                            .doFinally(
                                    signal -> {
                                        handler.cleanup();
                                        // Clear current handler reference
                                        this.currentStructuredOutputHandler.set(null);
                                    });
                });
    }

    // ==================== Core ReAct Loop ====================

    private Mono<Msg> executeReActLoop(StructuredOutputHandler handler) {
        return executeIteration(0, handler);
    }

    private Mono<Msg> executeIteration(int iter, StructuredOutputHandler handler) {
        if (iter >= maxIters) {
            return summarizing(handler);
        }

        return checkInterruptedAsync()
                .then(reasoning(handler))
                .then(Mono.defer(this::checkInterruptedAsync))
                .then(Mono.defer(() -> actingOrFinish(iter, handler)));
    }

    private Mono<Msg> actingOrFinish(int iter, StructuredOutputHandler handler) {
        List<ToolUseBlock> recentToolCalls = extractRecentToolCalls();

        if (handler != null && recentToolCalls.isEmpty() && handler.needsRetry()) {
            log.debug("Structured output retry needed (using reminder strategy)");
            return executeIteration(iter + 1, handler);
        }

        if (isFinished()) {
            return getLastAssistantMessage();
        }

        return acting().then(Mono.defer(() -> finishActingOrContinue(iter, handler)));
    }

    private Mono<Msg> finishActingOrContinue(int iter, StructuredOutputHandler handler) {
        if (handler != null && handler.isCompleted()) {
            return getLastAssistantMessage();
        }
        return executeIteration(iter + 1, handler);
    }

    /**
     * Execute the reasoning phase using pipeline pattern.
     */
    private Mono<Void> reasoning(StructuredOutputHandler handler) {
        return new ReasoningPipeline(handler).execute();
    }

    /**
     * Execute the acting phase using pipeline pattern.
     */
    private Mono<Void> acting() {
        return new ActingPipeline().execute();
    }

    /**
     * Generate summary when max iterations reached using pipeline pattern.
     */
    protected Mono<Msg> summarizing(StructuredOutputHandler handler) {
        return new SummarizingPipeline(handler).execute();
    }

    // ==================== Helper Methods ====================

    /**
     * Extract tool calls from the most recent assistant message.
     *
     * <p>Delegates to {@link MessageUtils#extractRecentToolCalls(List, String)} for the actual
     * extraction logic.
     *
     * @return List of tool use blocks from the last assistant message, or empty list if none found
     */
    private List<ToolUseBlock> extractRecentToolCalls() {
        return MessageUtils.extractRecentToolCalls(memory.getMessages(), getName());
    }

    /**
     * Check if the ReAct loop should terminate based on tool calls.
     *
     * @return true if no more tools to execute, false if more tools should be called
     */
    private boolean isFinished() {
        List<ToolUseBlock> recentToolCalls = extractRecentToolCalls();

        if (recentToolCalls.isEmpty()) {
            return true;
        }

        return recentToolCalls.stream()
                .noneMatch(toolCall -> toolkit.getTool(toolCall.getName()) != null);
    }

    private Mono<Msg> getLastAssistantMessage() {
        return Mono.fromCallable(
                () -> {
                    List<Msg> msgs = memory.getMessages();
                    for (int i = msgs.size() - 1; i >= 0; i--) {
                        Msg msg = msgs.get(i);
                        if (msg.getRole() == MsgRole.ASSISTANT) {
                            return msg;
                        }
                    }
                    if (!msgs.isEmpty()) {
                        return msgs.get(msgs.size() - 1);
                    }
                    throw new IllegalStateException(
                            "Reasoning completed but no messages generated");
                });
    }

    private GenerateOptions buildGenerateOptions() {
        GenerateOptions.Builder builder = GenerateOptions.builder();
        if (modelExecutionConfig != null) {
            builder.executionConfig(modelExecutionConfig);
        }
        return builder.build();
    }

    @Override
    protected Mono<Msg> handleInterrupt(InterruptContext context, Msg... originalArgs) {
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

    public Toolkit getToolkit() {
        return toolkit;
    }

    public int getMaxIters() {
        return maxIters;
    }

    public PlanNotebook getPlanNotebook() {
        return planNotebook;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ==================== Pipeline Classes ====================

    /**
     * Pipeline for executing the reasoning phase.
     * Handles model streaming, chunk processing, and hook notifications.
     */
    private class ReasoningPipeline {

        private final StructuredOutputHandler handler;
        private final ReasoningContext context;

        ReasoningPipeline(StructuredOutputHandler handler) {
            this.handler = handler;
            this.context = new ReasoningContext(getName());
        }

        Mono<Void> execute() {
            return prepareAndStream()
                    .onErrorResume(this::handleError)
                    .then(Mono.defer(this::finalizeReasoningStep));
        }

        private Mono<Void> prepareAndStream() {
            List<Msg> messageList = messagePreparer.prepareMessageList(handler);

            // Apply forced tool choice when in structured output mode
            GenerateOptions options =
                    handler != null
                            ? handler.createOptionsWithForcedTool(buildGenerateOptions())
                            : buildGenerateOptions();

            List<ToolSchema> toolSchemas = toolkit.getToolSchemas();

            return hookNotifier
                    .notifyPreReasoning(ReActAgent.this, messageList)
                    .flatMapMany(modifiedMsgs -> model.stream(modifiedMsgs, toolSchemas, options))
                    .concatMap(this::processChunkWithInterruptCheck)
                    .then();
        }

        private Flux<Void> processChunkWithInterruptCheck(ChatResponse chunk) {
            return checkInterruptedAsync()
                    .thenReturn(chunk)
                    .flatMapMany(this::processAndNotifyChunk);
        }

        private Flux<Void> processAndNotifyChunk(ChatResponse chunk) {
            List<Msg> msgs = context.processChunk(chunk);
            return Flux.fromIterable(msgs)
                    .concatMap(msg -> hookNotifier.notifyStreamingMsg(msg, context));
        }

        private Mono<Void> handleError(Throwable error) {
            if (error instanceof InterruptedException) {
                return finalizeWithInterrupt().then(Mono.error(error));
            }
            return Mono.error(error);
        }

        private Mono<Void> finalizeReasoningStep() {
            return finalizeReasoning(false);
        }

        private Mono<Void> finalizeWithInterrupt() {
            return finalizeReasoning(true);
        }

        private Mono<Void> finalizeReasoning(boolean wasInterrupted) {
            return Mono.fromCallable(context::buildFinalMessage)
                    .flatMap(reasoningMsg -> processFinalMessage(reasoningMsg, wasInterrupted));
        }

        private Mono<Void> processFinalMessage(Msg reasoningMsg, boolean wasInterrupted) {
            if (reasoningMsg == null) {
                return Mono.empty();
            }

            List<ToolUseBlock> toolBlocks = reasoningMsg.getContentBlocks(ToolUseBlock.class);

            return hookNotifier
                    .notifyPostReasoning(reasoningMsg)
                    .flatMap(
                            modifiedMsg -> {
                                memory.addMessage(modifiedMsg);
                                return notifyPreActingHooks(toolBlocks);
                            });
        }

        private Mono<Void> notifyPreActingHooks(List<ToolUseBlock> toolBlocks) {
            return Flux.fromIterable(toolBlocks).concatMap(hookNotifier::notifyPreActing).then();
        }
    }

    /**
     * Pipeline for executing the acting phase.
     * Handles tool execution and result processing.
     */
    private class ActingPipeline {

        Mono<Void> execute() {
            List<ToolUseBlock> toolCalls = extractRecentToolCalls();
            if (toolCalls.isEmpty()) {
                return Mono.empty();
            }

            toolkit.setChunkCallback(
                    (toolUse, chunk) -> hookNotifier.notifyActingChunk(toolUse, chunk).subscribe());

            return toolkit.callTools(
                            toolCalls, toolExecutionConfig, ReActAgent.this, toolExecutionContext)
                    .flatMapMany(responses -> processToolResults(toolCalls, responses))
                    .then()
                    .then(checkInterruptedAsync());
        }

        private Flux<Void> processToolResults(
                List<ToolUseBlock> toolCalls, List<ToolResultBlock> responses) {
            return Flux.range(0, toolCalls.size())
                    .concatMap(i -> processSingleToolResult(toolCalls.get(i), responses.get(i)));
        }

        private Mono<Void> processSingleToolResult(ToolUseBlock toolCall, ToolResultBlock result) {
            return hookNotifier
                    .notifyPostActing(toolCall, result)
                    .doOnNext(
                            processedResult -> {
                                Msg toolMsg =
                                        ToolResultMessageBuilder.buildToolResultMsg(
                                                processedResult, toolCall, getName());
                                memory.addMessage(toolMsg);
                            })
                    .then();
        }
    }

    /**
     * Pipeline for generating summary when max iterations reached.
     * Handles both structured output failure and normal summarization.
     */
    private class SummarizingPipeline {

        private final StructuredOutputHandler handler;

        SummarizingPipeline(StructuredOutputHandler handler) {
            this.handler = handler;
        }

        Mono<Msg> execute() {
            if (handler != null) {
                return handleStructuredOutputFailure();
            }
            return generateSummary();
        }

        private Mono<Msg> handleStructuredOutputFailure() {
            String errorMsg =
                    String.format(
                            "Failed to generate structured output within maximum iterations (%d)."
                                + " The model did not call the 'generate_response' function. Please"
                                + " check your system prompt, model capabilities, or increase"
                                + " maxIters.",
                            maxIters);
            log.error(errorMsg);
            return Mono.error(new IllegalStateException(errorMsg));
        }

        private Mono<Msg> generateSummary() {
            log.debug("Maximum iterations reached. Generating summary...");

            List<Msg> messageList = prepareMessageList();
            GenerateOptions options = buildGenerateOptions();
            ReasoningContext context = new ReasoningContext(getName());

            return model.stream(messageList, null, options)
                    .concatMap(chunk -> processChunk(chunk, context))
                    .then(Mono.defer(() -> buildSummaryMessage(context)))
                    .onErrorResume(InterruptedException.class, Mono::error)
                    .onErrorResume(this::handleSummaryError);
        }

        private List<Msg> prepareMessageList() {
            List<Msg> messageList = messagePreparer.prepareMessageList(null);
            messageList.add(createHintMessage());
            return messageList;
        }

        private Msg createHintMessage() {
            return Msg.builder()
                    .name("user")
                    .role(MsgRole.USER)
                    .content(
                            TextBlock.builder()
                                    .text(
                                            "You have failed to generate response within the"
                                                    + " maximum iterations. Now respond directly by"
                                                    + " summarizing the current situation.")
                                    .build())
                    .build();
        }

        private Mono<Msg> processChunk(ChatResponse chunk, ReasoningContext context) {
            return checkInterruptedAsync()
                    .thenReturn(chunk)
                    .doOnNext(context::processChunk)
                    .then(Mono.empty());
        }

        private Mono<Msg> buildSummaryMessage(ReasoningContext context) {
            Msg summaryMsg = context.buildFinalMessage();

            if (summaryMsg != null) {
                memory.addMessage(summaryMsg);
                return Mono.just(summaryMsg);
            }

            return Mono.just(createFallbackMessage());
        }

        private Msg createFallbackMessage() {
            Msg errorMsg =
                    Msg.builder()
                            .name(getName())
                            .role(MsgRole.ASSISTANT)
                            .content(
                                    TextBlock.builder()
                                            .text(
                                                    String.format(
                                                            "Maximum iterations (%d) reached."
                                                                + " Unable to generate summary.",
                                                            maxIters))
                                            .build())
                            .build();
            memory.addMessage(errorMsg);
            return errorMsg;
        }

        private Mono<Msg> handleSummaryError(Throwable error) {
            log.error("Error generating summary", error);

            Msg errorMsg =
                    Msg.builder()
                            .name(getName())
                            .role(MsgRole.ASSISTANT)
                            .content(
                                    TextBlock.builder()
                                            .text(
                                                    String.format(
                                                            "Maximum iterations (%d) reached. Error"
                                                                    + " generating summary: %s",
                                                            maxIters, error.getMessage()))
                                            .build())
                            .build();
            memory.addMessage(errorMsg);
            return Mono.just(errorMsg);
        }
    }

    // ==================== Inner Classes ====================

    /**
     * Injects reminder messages for structured output generation in PROMPT mode.
     *
     * <p>This hook automatically adds reminder messages to the model context when the agent
     * needs prompting to call the structured output tool. It ensures reliable structured output
     * generation without relying on model tool choice enforcement.
     */
    private class InternalStructuredOutputReminderHook implements Hook {

        @Override
        @SuppressWarnings("unchecked")
        public <T extends HookEvent> Mono<T> onEvent(T event) {
            // Use pattern matching to handle PreReasoningEvent
            if (event instanceof PreReasoningEvent preReasoningEvent) {
                // Access outer class field through AtomicReference (thread-safe)
                StructuredOutputHandler handler = currentStructuredOutputHandler.get();
                if (handler != null && handler.shouldInjectReminder()) {
                    List<Msg> messages = new ArrayList<>(preReasoningEvent.getInputMessages());
                    messages.add(handler.createReminderMessage());
                    preReasoningEvent.setInputMessages(messages);
                    log.debug("Injected structured output reminder via internal hook");
                }
                return Mono.just((T) preReasoningEvent);
            }
            // For other event types, just pass through
            return Mono.just(event);
        }

        @Override
        public int priority() {
            // Use high priority to ensure reminder is injected before other hooks
            return 50;
        }
    }

    /**
     * Internal component for hook notifications.
     */
    private class HookNotifier {

        Mono<List<Msg>> notifyPreReasoning(AgentBase agent, List<Msg> msgs) {
            PreReasoningEvent event =
                    new PreReasoningEvent(agent, model.getModelName(), null, msgs);
            Mono<PreReasoningEvent> result = Mono.just(event);
            for (Hook hook : getSortedHooks()) {
                result = result.flatMap(e -> hook.onEvent(e));
            }
            return result.map(PreReasoningEvent::getInputMessages);
        }

        Mono<Msg> notifyPostReasoning(Msg reasoningMsg) {
            PostReasoningEvent event =
                    new PostReasoningEvent(
                            ReActAgent.this, model.getModelName(), null, reasoningMsg);
            Mono<PostReasoningEvent> result = Mono.just(event);
            for (Hook hook : getSortedHooks()) {
                result = result.flatMap(e -> hook.onEvent(e));
            }
            return result.map(PostReasoningEvent::getReasoningMessage);
        }

        Mono<Void> notifyReasoningChunk(Msg chunk, Msg accumulated) {
            ReasoningChunkEvent event =
                    new ReasoningChunkEvent(
                            ReActAgent.this, model.getModelName(), null, chunk, accumulated);
            return Flux.fromIterable(getSortedHooks()).flatMap(hook -> hook.onEvent(event)).then();
        }

        Mono<ToolUseBlock> notifyPreActing(ToolUseBlock toolUse) {
            PreActingEvent event = new PreActingEvent(ReActAgent.this, toolkit, toolUse);
            Mono<PreActingEvent> result = Mono.just(event);
            for (Hook hook : getSortedHooks()) {
                result = result.flatMap(e -> hook.onEvent(e));
            }
            return result.map(PreActingEvent::getToolUse);
        }

        Mono<Void> notifyActingChunk(ToolUseBlock toolUse, ToolResultBlock chunk) {
            ActingChunkEvent event = new ActingChunkEvent(ReActAgent.this, toolkit, toolUse, chunk);
            return Flux.fromIterable(getSortedHooks()).flatMap(hook -> hook.onEvent(event)).then();
        }

        Mono<ToolResultBlock> notifyPostActing(ToolUseBlock toolUse, ToolResultBlock toolResult) {
            var event = new PostActingEvent(ReActAgent.this, toolkit, toolUse, toolResult);
            Mono<PostActingEvent> result = Mono.just(event);
            for (Hook hook : getSortedHooks()) {
                result = result.flatMap(e -> hook.onEvent(e));
            }
            return result.map(PostActingEvent::getToolResult);
        }

        Mono<Void> notifyStreamingMsg(Msg msg, ReasoningContext context) {
            ContentBlock content = msg.getFirstContentBlock();

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
                                .id(msg.getId())
                                .name(msg.getName())
                                .role(msg.getRole())
                                .content(accumulatedContent)
                                .build();
                return notifyReasoningChunk(msg, accumulated);
            }

            return Mono.empty();
        }
    }

    /**
     * Internal component for message preparation.
     */
    private class MessagePreparer {

        List<Msg> prepareMessageList(StructuredOutputHandler handler) {
            List<Msg> messages = new ArrayList<>();

            addSystemPromptIfNeeded(messages);
            messages.addAll(memory.getMessages());

            return messages;
        }

        private void addSystemPromptIfNeeded(List<Msg> messages) {
            if (sysPrompt != null && !sysPrompt.trim().isEmpty()) {
                Msg systemMsg =
                        Msg.builder()
                                .name("system")
                                .role(MsgRole.SYSTEM)
                                .content(TextBlock.builder().text(sysPrompt).build())
                                .build();
                messages.add(systemMsg);
            }
        }
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
        private final List<Hook> hooks = new ArrayList<>();
        private boolean enableMetaTool = false;
        private StructuredOutputReminder structuredOutputReminder =
                StructuredOutputReminder.TOOL_CHOICE;
        private PlanNotebook planNotebook;
        private SkillBox skillBox;
        private ToolExecutionContext toolExecutionContext;

        // Long-term memory configuration
        private LongTermMemory longTermMemory;
        private LongTermMemoryMode longTermMemoryMode = LongTermMemoryMode.BOTH;

        // State persistence configuration
        private StatePersistence statePersistence;

        // RAG configuration
        private final List<Knowledge> knowledgeBases = new ArrayList<>();
        private RAGMode ragMode = RAGMode.GENERIC;
        private RetrieveConfig retrieveConfig =
                RetrieveConfig.builder().limit(5).scoreThreshold(0.5).build();
        private boolean enableOnlyForUserQueries = true;

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
         *   <li>A skill hook will be added to inject skill prompts and manage skill activation</li>
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
         * Sets whether to enable RAG only for user queries.
         *
         * @param enableOnlyForUserQueries If true, RAG is only triggered for user messages
         * @return This builder instance for method chaining
         */
        public Builder enableOnlyForUserQueries(boolean enableOnlyForUserQueries) {
            this.enableOnlyForUserQueries = enableOnlyForUserQueries;
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
            if (enableMetaTool) {
                toolkit.registerMetaTool();
            }

            // Configure long-term memory if provided
            if (longTermMemory != null) {
                configureLongTermMemory();
            }

            // Configure RAG if knowledge bases are provided
            if (!knowledgeBases.isEmpty()) {
                configureRAG();
            }

            // Configure PlanNotebook if provided
            if (planNotebook != null) {
                configurePlan();
            }

            // Configure SkillBox if provided
            if (skillBox != null) {
                configureSkillBox();
            }

            // If using PROMPT mode, we need to add the internal reminder hook
            // Since InternalStructuredOutputReminderHook is a non-static inner class,
            // it can only be instantiated after ReActAgent exists. We use a delegating
            // hook that will be connected to the real internal hook after construction.
            AtomicReference<Hook> internalHookRef = new AtomicReference<>();

            if (structuredOutputReminder == StructuredOutputReminder.PROMPT) {
                // Add a delegating hook that forwards to the internal hook
                Hook delegatingHook =
                        new Hook() {
                            @Override
                            public <T extends HookEvent> Mono<T> onEvent(T event) {
                                Hook delegate = internalHookRef.get();
                                if (delegate != null) {
                                    return delegate.onEvent(event);
                                }
                                return Mono.just(event);
                            }

                            @Override
                            public int priority() {
                                return 50; // High priority to inject reminder early
                            }
                        };
                this.hooks.add(delegatingHook);
            }

            // Create the agent
            ReActAgent agent = new ReActAgent(this);

            // After agent is created, instantiate the real internal hook and connect it
            if (structuredOutputReminder == StructuredOutputReminder.PROMPT) {
                internalHookRef.set(agent.new InternalStructuredOutputReminderHook());
            }

            return agent;
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
        private void configureLongTermMemory() {
            // If agent control is enabled, register memory tools via adapter
            if (longTermMemoryMode == LongTermMemoryMode.AGENT_CONTROL
                    || longTermMemoryMode == LongTermMemoryMode.BOTH) {
                toolkit.registerTool(new LongTermMemoryTools(longTermMemory));
            }

            // If static control is enabled, register the hook for automatic memory management
            if (longTermMemoryMode == LongTermMemoryMode.STATIC_CONTROL
                    || longTermMemoryMode == LongTermMemoryMode.BOTH) {
                StaticLongTermMemoryHook hook =
                        new StaticLongTermMemoryHook(longTermMemory, memory);
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
        private void configureRAG() {
            // Aggregate knowledge bases if multiple are provided
            Knowledge aggregatedKnowledge;
            if (knowledgeBases.size() == 1) {
                aggregatedKnowledge = knowledgeBases.get(0);
            } else {
                aggregatedKnowledge = buildAggregatedKnowledge();
            }

            // Configure based on mode
            switch (ragMode) {
                case GENERIC -> {
                    // Create and add GenericRAGHook
                    GenericRAGHook ragHook =
                            new GenericRAGHook(
                                    aggregatedKnowledge, retrieveConfig, enableOnlyForUserQueries);
                    hooks.add(ragHook);
                }
                case AGENTIC -> {
                    // Register knowledge retrieval tools
                    KnowledgeRetrievalTools tools =
                            new KnowledgeRetrievalTools(aggregatedKnowledge);
                    toolkit.registerTool(tools);
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
        private void configurePlan() {
            // Register plan tools to toolkit
            toolkit.registerTool(planNotebook);

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
         *   <li>Registers skill loader tools to the toolkit
         *   <li>Adds the skill hook to inject skill prompts and manage skill activation
         * </ul>
         */
        private void configureSkillBox() {
            skillBox.bindToolkit(toolkit);
            // Register skill loader tools to toolkit
            toolkit.registerTool(skillBox);

            hooks.add(new SkillHook(skillBox));
        }
    }
}
