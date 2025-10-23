/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
import io.agentscope.core.agent.accumulator.ReasoningContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
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
 * This agent follows the Python version's architecture and provides:
 * - Reasoning and acting steps in the ReAct algorithm
 * - Tool calling capabilities
 * - Memory management
 * - Streaming support
 *
 * Method names are aligned with the Python version:
 * - call(): Main response generation
 * - reasoning(): Reasoning step in ReAct loop
 * - acting(): Acting step in ReAct loop
 */
public class ReActAgent extends AgentBase {

    private static final Logger log = LoggerFactory.getLogger(ReActAgent.class);

    private final String sysPrompt;
    private final Model model;
    private final Toolkit toolkit;
    private final int maxIters;

    public ReActAgent(
            String name,
            String sysPrompt,
            Model model,
            Toolkit toolkit,
            Memory memory,
            int maxIters,
            List<Hook> hooks) {
        super(name, memory, hooks);
        this.sysPrompt = sysPrompt;
        this.model = model;
        this.toolkit = toolkit;
        this.maxIters = maxIters;
    }

    @Override
    protected Mono<Msg> doCall(Msg msg) {
        return Mono.fromCallable(
                () -> {
                    addToMemory(msg);
                    return executeReActLoop();
                });
    }

    @Override
    protected Mono<Msg> doCall(List<Msg> msgs) {
        return Mono.fromCallable(
                () -> {
                    msgs.forEach(this::addToMemory);
                    return executeReActLoop();
                });
    }

    @Override
    protected Mono<Msg> doCall() {
        // Continue generation based on current memory without adding new messages
        return Mono.fromCallable(this::executeReActLoop);
    }

    /**
     * Execute the ReAct loop with simplified control flow.
     * Each iteration: Check max iterations -> Reasoning -> Check if finished -> Acting (if needed) -> Next iteration
     *
     * Note: We always execute at least one reasoning step per call(), even if memory contains
     * previous ASSISTANT messages. This ensures agents in pipelines work correctly when sharing memory.
     *
     * @return The final response message
     * @throws InterruptedException if execution is interrupted
     */
    private Msg executeReActLoop() throws InterruptedException {
        for (int iter = 0; iter < maxIters; iter++) {
            // Checkpoint: Check for interruption before each iteration
            checkInterrupted();

            // Execute reasoning for the current iteration
            // reasoning() saves all messages to memory and returns text message (if any)
            reasoning();

            // Checkpoint: Check for interruption after reasoning
            // IMPORTANT: Before checking, extract recent tool calls from memory
            // This ensures that if interruption happens here, we can generate fake results
            List<ToolUseBlock> recentToolCalls = extractRecentToolCalls();
            if (!recentToolCalls.isEmpty()) {
                setPendingToolCalls(recentToolCalls);
            }

            checkInterrupted();

            // Check if finished (examines memory for recent tool calls)
            if (isFinished()) {
                // Return the last non-empty text message or last message in memory
                List<Msg> msgs = getMemory().getMessages();
                // Find last text message with content
                for (int i = msgs.size() - 1; i >= 0; i--) {
                    Msg msg = msgs.get(i);
                    ContentBlock firstBlock = msg.getFirstContentBlock();
                    if (firstBlock instanceof TextBlock tb && !tb.getText().trim().isEmpty()) {
                        return msg;
                    }
                }
                // Fallback: return last message
                if (!msgs.isEmpty()) {
                    return msgs.get(msgs.size() - 1);
                }
                throw new IllegalStateException("Reasoning completed but no messages generated");
            }

            // Execute tools and continue to next iteration
            acting();
        }

        // Maximum iterations reached
        Msg errorMsg =
                Msg.builder()
                        .name(getName())
                        .role(MsgRole.ASSISTANT)
                        .content(
                                TextBlock.builder()
                                        .text(
                                                "Maximum iterations ("
                                                        + maxIters
                                                        + ") reached. Please refine your"
                                                        + " request.")
                                        .build())
                        .build();
        addToMemory(errorMsg);
        return errorMsg;
    }

    /**
     * The reasoning step in ReAct algorithm.
     * This method generates reasoning based on the current context and input.
     * Handles streaming, hook notifications, and saves all messages to memory.
     *
     * @throws InterruptedException if execution is interrupted during reasoning
     */
    private void reasoning() throws InterruptedException {
        // Notify preReasoning hook
        notifyPreReasoning(this).block();

        // Prepare message list - Model will format internally using its formatter
        List<Msg> messageList = prepareMessageList();

        List<ToolSchema> toolSchemas = toolkit.getToolSchemasForModel();
        GenerateOptions options = GenerateOptions.builder().build();

        // Create reasoning context to manage state
        ReasoningContext context = new ReasoningContext(getName());
        StringBuilder accumulatedText = new StringBuilder();

        boolean interrupted = false;

        try {
            // Stream chunks in real-time using toIterable() for synchronous iteration
            // This allows us to process and notify hooks for each chunk as it arrives
            Flux<ChatResponse> streamFlux = model.stream(messageList, toolSchemas, options);
            for (var chunk : streamFlux.toIterable()) {
                // Checkpoint: Check for interruption during streaming
                checkInterrupted();

                List<Msg> msgs = context.processChunk(chunk);
                for (Msg msg : msgs) {
                    notifyStreamingMsg(msg, accumulatedText).block();
                }
            }
        } catch (InterruptedException e) {
            // Mark as interrupted but continue to save tool calls if any
            interrupted = true;
            throw e; // Re-throw to propagate
        } finally {
            // Build final message with ALL blocks (text + thinking + tools)
            Msg reasoningMsg = context.buildFinalMessage();

            if (reasoningMsg != null) {
                // Extract tool calls for interrupt handling
                List<ToolUseBlock> toolBlocks = reasoningMsg.getContentBlocks(ToolUseBlock.class);
                if (!toolBlocks.isEmpty() && interrupted) {
                    setPendingToolCalls(toolBlocks);
                }

                // Notify postReasoning hook - this can modify the reasoning message
                reasoningMsg = notifyPostReasoning(reasoningMsg).block();

                // Save the complete message to memory
                addToMemory(reasoningMsg);

                // Notify preActing hooks for each tool call in the message
                for (ToolUseBlock tub : toolBlocks) {
                    notifyPreActing(tub).block();
                }
            }
        }
    }

    /**
     * Notify hooks about streaming messages during reasoning.
     * Handles TextBlock and ThinkingBlock content.
     *
     * @param msg The streaming message
     * @param accumulatedText StringBuilder tracking accumulated text for CUMULATIVE mode
     * @return Mono that completes when all hooks are notified
     */
    private Mono<Void> notifyStreamingMsg(Msg msg, StringBuilder accumulatedText) {
        ContentBlock content = msg.getFirstContentBlock();
        if (content instanceof TextBlock tb) {
            // For text blocks, accumulate and call onReasoningChunk
            accumulatedText.append(tb.getText());

            Msg accumulated =
                    Msg.builder()
                            .id(msg.getId())
                            .name(msg.getName())
                            .role(msg.getRole())
                            .content(TextBlock.builder().text(accumulatedText.toString()).build())
                            .build();

            return notifyReasoningChunk(msg, accumulated);
        } else if (content instanceof ThinkingBlock) {
            // For thinking blocks, call onReasoningChunk without accumulation
            return notifyReasoningChunk(msg, msg);
        }
        return Mono.empty();
    }

    /**
     * The acting step in ReAct algorithm.
     * This method executes tools based on the most recent tool calls in memory.
     * Each tool result is saved to memory and hooks are notified.
     *
     * @throws InterruptedException if execution is interrupted during tool execution
     */
    private void acting() throws InterruptedException {
        List<ToolUseBlock> toolCalls = extractRecentToolCalls();
        if (toolCalls.isEmpty()) {
            return;
        }

        // Set up chunk callback to notify hooks when tools emit streaming responses
        toolkit.setChunkCallback(
                (toolUse, chunk) -> {
                    // Notify hooks synchronously
                    notifyActingChunk(toolUse, chunk).block();
                });

        // Execute all tools (may be parallel or sequential based on Toolkit implementation)
        // Note: Tools will run to completion even if interrupt is triggered during execution
        List<ToolResultBlock> responses = toolkit.callTools(toolCalls).block();

        // Process each tool result: save to memory and notify hooks
        int count = Math.min(toolCalls.size(), responses.size());
        for (int i = 0; i < count; i++) {
            ToolResultBlock response = responses.get(i);
            ToolUseBlock originalCall = toolCalls.get(i);

            Msg toolMsg =
                    ToolResultMessageBuilder.buildToolResultMsg(response, originalCall, getName());
            addToMemory(toolMsg);

            // Notify postActing hooks
            ToolResultBlock trb = (ToolResultBlock) toolMsg.getFirstContentBlock();
            notifyPostActing(originalCall, trb).block();
        }

        // Checkpoint: Check for interruption after all tool results are saved
        checkInterrupted();
    }

    /**
     * Notify preReasoning hook.
     * This is added to support the new hook design aligned with Python.
     *
     * @param agent The agent instance
     * @return Mono that completes when all hooks are notified
     */
    private Mono<Void> notifyPreReasoning(AgentBase agent) {
        return Flux.fromIterable(getHooks()).flatMap(hook -> hook.preReasoning(agent)).then();
    }

    /**
     * Extract the most recent tool calls from memory.
     * Looks for the last ASSISTANT message from this agent and extracts all tool_use blocks.
     *
     * @return List of tool use blocks from the last reasoning round
     */
    private List<ToolUseBlock> extractRecentToolCalls() {
        List<Msg> messages = getMemory().getMessages();
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        // Traverse backward to find the last ASSISTANT message from this agent
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg msg = messages.get(i);

            // Look for ASSISTANT messages from this agent
            if (msg.getRole() == MsgRole.ASSISTANT && msg.getName().equals(getName())) {
                // Extract all tool_use blocks from this message
                List<ToolUseBlock> toolCalls = msg.getContentBlocks(ToolUseBlock.class);
                if (!toolCalls.isEmpty()) {
                    return toolCalls;
                }
                // If this ASSISTANT message has no tools, we're done
                break;
            }
        }

        return List.of();
    }

    /**
     * Check if the ReAct loop should finish.
     * Examines the most recent messages in memory to determine if there are any pending tool calls.
     *
     * @return true if finished (no pending tool calls or all tool calls are invalid)
     */
    private boolean isFinished() {
        List<ToolUseBlock> recentToolCalls = extractRecentToolCalls();

        // If no tool calls, we're finished
        if (recentToolCalls.isEmpty()) {
            return true;
        }

        // If all tool calls are invalid (not registered in toolkit), we're finished
        // This handles the "finish" function pattern where models call non-existent tools
        boolean finished =
                recentToolCalls.stream()
                        .noneMatch(toolCall -> toolkit.getTool(toolCall.getName()) != null);
        return finished;
    }

    private List<Msg> prepareMessageList() {
        List<Msg> messages = new ArrayList<>();

        // Add system prompt
        if (sysPrompt != null && !sysPrompt.trim().isEmpty()) {
            Msg systemMsg =
                    Msg.builder()
                            .name("system")
                            .role(MsgRole.SYSTEM)
                            .content(TextBlock.builder().text(sysPrompt).build())
                            .build();
            messages.add(systemMsg);
        }

        // Add memory messages
        messages.addAll(getMemory().getMessages());

        return messages;
    }

    @Override
    protected Mono<Msg> handleInterrupt(InterruptContext context, Msg... originalArgs) {
        // Build recovery message with user-friendly text (aligned with Python)
        String recoveryText = "I noticed that you have interrupted me. What can I do for you?";

        Msg recoveryMsg =
                Msg.builder()
                        .name(getName())
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text(recoveryText).build())
                        .build();

        // Save recovery message to memory
        addToMemory(recoveryMsg);

        return Mono.just(recoveryMsg);
    }

    /**
     * Get the system prompt.
     */
    public String getSysPrompt() {
        return sysPrompt;
    }

    /**
     * Get the model.
     */
    public Model getModel() {
        return model;
    }

    /**
     * Get the toolkit.
     */
    public Toolkit getToolkit() {
        return toolkit;
    }

    /**
     * Get maximum iterations for ReAct loop.
     *
     * @return maximum iterations
     */
    public int getMaxIters() {
        return maxIters;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private String sysPrompt;
        private Model model;
        private Toolkit toolkit = new Toolkit();
        private Memory memory;
        private int maxIters = 10;
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

        /**
         * Enable meta tool for dynamic tool group management.
         * When enabled, the agent can use reset_equipped_tools to activate tool groups.
         *
         * @param enableMetaTool Whether to enable meta tool
         * @return This builder
         */
        public Builder enableMetaTool(boolean enableMetaTool) {
            this.enableMetaTool = enableMetaTool;
            return this;
        }

        public ReActAgent build() {
            // Auto-register meta tool if enabled
            if (enableMetaTool) {
                toolkit.registerMetaTool();
            }

            return new ReActAgent(name, sysPrompt, model, toolkit, memory, maxIters, hooks);
        }
    }
}
