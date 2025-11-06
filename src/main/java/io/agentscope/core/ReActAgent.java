/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core;

import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.StructuredOutputHandler;
import io.agentscope.core.agent.accumulator.ReasoningContext;
import io.agentscope.core.hook.ActingChunkEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.hook.ReasoningChunkEvent;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
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
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.ToolResultMessageBuilder;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.List;
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

    // ==================== Internal Components ====================

    private final HookNotifier hookNotifier;
    private final MessagePreparer messagePreparer;

    // ==================== Constructor ====================

    public ReActAgent(
            String name,
            String sysPrompt,
            Model model,
            Toolkit toolkit,
            Memory memory,
            int maxIters,
            ExecutionConfig modelExecutionConfig,
            ExecutionConfig toolExecutionConfig,
            List<Hook> hooks) {
        super(name, hooks);
        this.memory = memory != null ? memory : new InMemoryMemory();
        this.sysPrompt = sysPrompt;
        this.model = model;
        this.toolkit = toolkit;
        this.maxIters = maxIters;
        this.modelExecutionConfig = modelExecutionConfig;
        this.toolExecutionConfig = toolExecutionConfig;

        this.hookNotifier = new HookNotifier();
        this.messagePreparer = new MessagePreparer();

        addNestedModule("memory", this.memory);
    }

    // ==================== Public API ====================

    @Override
    protected Mono<Msg> doCall(Msg msg) {
        if (msg != null) {
            memory.addMessage(msg);
        }
        return executeReActLoop(null);
    }

    @Override
    protected Mono<Msg> doCall(List<Msg> msgs) {
        if (msgs != null) {
            msgs.forEach(memory::addMessage);
        }
        return executeReActLoop(null);
    }

    @Override
    protected Mono<Msg> doCall() {
        return executeReActLoop(null);
    }

    @Override
    protected Mono<Msg> doCall(Msg msg, Class<?> structuredOutputClass) {
        return doCall(List.of(msg), structuredOutputClass);
    }

    @Override
    protected Mono<Msg> doCall(List<Msg> msgs, Class<?> structuredOutputClass) {
        if (msgs != null && !msgs.isEmpty()) {
            msgs.forEach(memory::addMessage);
        }

        StructuredOutputHandler handler =
                new StructuredOutputHandler(structuredOutputClass, toolkit, memory, getName());

        return Mono.defer(
                () -> {
                    handler.prepare();
                    return executeReActLoop(handler)
                            .flatMap(result -> Mono.just(handler.extractFinalResult()))
                            .doFinally(signal -> handler.cleanup());
                });
    }

    @Override
    protected Mono<Msg> doCall(Class<?> structuredOutputClass) {
        return doCall(List.of(), structuredOutputClass);
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
                .then(Mono.defer(() -> afterReasoning(handler)))
                .then(Mono.defer(() -> processReasoningResult(iter, handler)));
    }

    private Mono<Void> afterReasoning(StructuredOutputHandler handler) {
        return checkInterruptedAsync();
    }

    private Mono<Msg> processReasoningResult(int iter, StructuredOutputHandler handler) {
        if (isFinished()) {
            return getLastAssistantMessage();
        }

        return acting().then(Mono.defer(() -> afterActing(iter, handler)));
    }

    private Mono<Msg> afterActing(int iter, StructuredOutputHandler handler) {
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

    private List<ToolUseBlock> extractRecentToolCalls() {
        List<Msg> messages = memory.getMessages();
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            if (msg.getRole() == MsgRole.ASSISTANT && msg.getName().equals(getName())) {
                List<ToolUseBlock> toolCalls = msg.getContentBlocks(ToolUseBlock.class);
                if (!toolCalls.isEmpty()) {
                    return toolCalls;
                }
                break;
            }
        }

        return List.of();
    }

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

    @Override
    protected Mono<Void> doObserve(List<Msg> msgs) {
        if (msgs != null && !msgs.isEmpty()) {
            msgs.forEach(memory::addMessage);
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

            List<ToolSchema> toolSchemas = toolkit.getToolSchemasForModel();

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

            setupChunkCallback();

            return toolkit.callTools(toolCalls, toolExecutionConfig)
                    .flatMapMany(responses -> processToolResults(toolCalls, responses))
                    .then()
                    .then(checkInterruptedAsync());
        }

        private void setupChunkCallback() {
            toolkit.setChunkCallback(
                    (toolUse, chunk) -> hookNotifier.notifyActingChunk(toolUse, chunk).subscribe());
        }

        private Flux<Void> processToolResults(
                List<ToolUseBlock> toolCalls, List<ToolResultBlock> responses) {
            return Flux.range(0, toolCalls.size())
                    .concatMap(i -> processSingleToolResult(toolCalls.get(i), responses.get(i)));
        }

        private Mono<Void> processSingleToolResult(ToolUseBlock toolCall, ToolResultBlock result) {
            Msg toolMsg = ToolResultMessageBuilder.buildToolResultMsg(result, toolCall, getName());
            memory.addMessage(toolMsg);

            ToolResultBlock savedResult = (ToolResultBlock) toolMsg.getFirstContentBlock();
            return hookNotifier.notifyPostActing(toolCall, savedResult).then();
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
        private String sysPrompt;
        private Model model;
        private Toolkit toolkit = new Toolkit();
        private Memory memory;
        private int maxIters = 10;
        private ExecutionConfig modelExecutionConfig;
        private ExecutionConfig toolExecutionConfig;
        private final List<Hook> hooks = new ArrayList<>();
        private boolean enableMetaTool = false;

        private Builder() {}

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder sysPrompt(String sysPrompt) {
            this.sysPrompt = sysPrompt;
            return this;
        }

        public Builder model(Model model) {
            this.model = model;
            return this;
        }

        public Builder toolkit(Toolkit toolkit) {
            this.toolkit = toolkit;
            return this;
        }

        public Builder memory(Memory memory) {
            this.memory = memory;
            return this;
        }

        public Builder maxIters(int maxIters) {
            this.maxIters = maxIters;
            return this;
        }

        public Builder hook(Hook hook) {
            this.hooks.add(hook);
            return this;
        }

        public Builder hooks(List<Hook> hooks) {
            this.hooks.addAll(hooks);
            return this;
        }

        public Builder enableMetaTool(boolean enableMetaTool) {
            this.enableMetaTool = enableMetaTool;
            return this;
        }

        public Builder modelExecutionConfig(ExecutionConfig modelExecutionConfig) {
            this.modelExecutionConfig = modelExecutionConfig;
            return this;
        }

        public Builder toolExecutionConfig(ExecutionConfig toolExecutionConfig) {
            this.toolExecutionConfig = toolExecutionConfig;
            return this;
        }

        public ReActAgent build() {
            if (enableMetaTool) {
                toolkit.registerMetaTool();
            }

            return new ReActAgent(
                    name,
                    sysPrompt,
                    model,
                    toolkit,
                    memory,
                    maxIters,
                    modelExecutionConfig,
                    toolExecutionConfig,
                    hooks);
        }
    }
}
