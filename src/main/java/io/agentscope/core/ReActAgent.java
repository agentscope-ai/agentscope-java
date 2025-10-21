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
import io.agentscope.core.formatter.FormatterBase;
import io.agentscope.core.formatter.OpenAIChatFormatter;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.FormattedMessageList;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.ToolResponse;
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
    private final FormatterBase formatter;
    private final int maxIters;

    public ReActAgent(
            String name,
            String sysPrompt,
            Model model,
            Toolkit toolkit,
            FormatterBase formatter,
            Memory memory,
            int maxIters,
            List<Hook> hooks) {
        super(name, memory, hooks);
        this.sysPrompt = sysPrompt;
        this.model = model;
        this.toolkit = toolkit;
        this.formatter = formatter;
        this.maxIters = maxIters;
    }

    @Override
    protected Mono<Msg> doCall(Msg msg) {
        addToMemory(msg);
        return executeReActLoop(0);
    }

    @Override
    protected Mono<Msg> doCall(List<Msg> msgs) {
        msgs.forEach(this::addToMemory);
        return executeReActLoop(0);
    }

    @Override
    protected Mono<Msg> doCall() {
        // Continue generation based on current memory without adding new messages
        return executeReActLoop(0);
    }

    /**
     * Execute the ReAct loop with simplified control flow.
     * Each iteration: Check max iterations -> Reasoning -> Check if finished -> Acting (if needed) -> Next iteration
     *
     * Note: We always execute at least one reasoning step per call(), even if memory contains
     * previous ASSISTANT messages. This ensures agents in pipelines work correctly when sharing memory.
     *
     * @param iter Current iteration number
     * @return Mono containing the final response message
     */
    private Mono<Msg> executeReActLoop(int iter) {
        // Check max iterations
        if (iter >= maxIters) {
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
            return Mono.just(errorMsg);
        }

        // Always execute reasoning for the current iteration
        // reasoning() saves all messages to memory and returns text message (if any)
        return reasoning()
                .then(
                        Mono.defer(
                                () -> {
                                    // Check if finished (examines memory for recent tool calls)
                                    if (isFinished()) {
                                        // Return the last non-empty text message or last message in
                                        // memory
                                        return Mono.fromCallable(
                                                () -> {
                                                    List<Msg> msgs = getMemory().getMessages();
                                                    // Find last text message with content
                                                    for (int i = msgs.size() - 1; i >= 0; i--) {
                                                        Msg msg = msgs.get(i);
                                                        if (msg.getContent() instanceof TextBlock tb
                                                                && !tb.getText().trim().isEmpty()
                                                                && msg.getName()
                                                                        .equals(getName())) {
                                                            return msg;
                                                        }
                                                    }
                                                    // Fallback: return last message
                                                    if (!msgs.isEmpty()) {
                                                        return msgs.get(msgs.size() - 1);
                                                    }
                                                    throw new IllegalStateException(
                                                            "Reasoning completed but no messages"
                                                                    + " generated");
                                                });
                                    }

                                    // Execute tools and continue to next iteration
                                    return acting().then(executeReActLoop(iter + 1));
                                }));
    }

    /**
     * The reasoning step in ReAct algorithm.
     * This method generates reasoning based on the current context and input.
     * Handles streaming, hook notifications, and saves all messages to memory.
     *
     * @return Mono containing the final text message (if any), or null if only tool calls were generated
     */
    private Mono<Msg> reasoning() {
        // Format messages synchronously
        List<Msg> messageList = prepareMessageList();
        FormattedMessageList formattedMessages = formatter.format(messageList);

        List<ToolSchema> toolSchemas = toolkit.getToolSchemasForModel();
        GenerateOptions options = new GenerateOptions();

        // Create reasoning context to manage state
        ReasoningContext context = new ReasoningContext(getName());
        StringBuilder accumulatedText = new StringBuilder();

        // Stream model responses and notify hooks for text/thinking chunks
        Flux<Msg> streamingFlux =
                model.streamFlux(formattedMessages, toolSchemas, options)
                        .flatMap(
                                chunk -> {
                                    List<Msg> msgs = context.processChunk(chunk);
                                    return Flux.fromIterable(msgs)
                                            .flatMap(
                                                    msg ->
                                                            notifyStreamingMsg(msg, accumulatedText)
                                                                    .thenReturn(msg));
                                });

        // Process finalized tool calls - each tool call is saved as a separate message
        Flux<Msg> toolCallFlux =
                context.emitFinalizedToolCalls()
                        .flatMap(
                                toolMsg -> {
                                    // Save each tool call message to memory immediately
                                    addToMemory(toolMsg);
                                    // Notify hooks
                                    ToolUseBlock tub = (ToolUseBlock) toolMsg.getContent();
                                    return notifyToolCall(tub).thenReturn(toolMsg);
                                });

        // Combine and finalize
        // IMPORTANT: We need to consume all items from the Flux (including toolCallFlux)
        // before checking for text messages. Using collectList() ensures all items are processed.
        return streamingFlux
                .concatWith(toolCallFlux)
                .collectList() // Force consumption of all Flux items
                .flatMap(
                        msgs -> {
                            // Save text message (if any) to memory
                            Msg textMsg = context.buildTextMessage();
                            if (textMsg != null) {
                                addToMemory(textMsg);
                                return Mono.just(textMsg);
                            }
                            // If no text, return empty Mono (tool calls already saved)
                            return Mono.empty();
                        });
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
        ContentBlock content = msg.getContent();
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
     */
    private Mono<Void> acting() {
        return Mono.fromCallable(this::extractRecentToolCalls)
                .flatMap(
                        toolCalls -> {
                            if (toolCalls.isEmpty()) {
                                return Mono.empty();
                            }

                            // Execute all tools (may be parallel or sequential based on Toolkit
                            // implementation)
                            return toolkit.callTools(toolCalls)
                                    .flatMap(
                                            responses -> {
                                                // Process each tool result: save to memory and
                                                // notify hooks
                                                return Flux.range(
                                                                0,
                                                                Math.min(
                                                                        toolCalls.size(),
                                                                        responses.size()))
                                                        .flatMap(
                                                                i -> {
                                                                    ToolResponse response =
                                                                            responses.get(i);
                                                                    ToolUseBlock originalCall =
                                                                            toolCalls.get(i);

                                                                    Msg toolMsg =
                                                                            ToolResultMessageBuilder
                                                                                    .buildToolResultMsg(
                                                                                            response,
                                                                                            originalCall,
                                                                                            getName());
                                                                    addToMemory(toolMsg);

                                                                    // Notify hooks
                                                                    ToolResultBlock trb =
                                                                            (ToolResultBlock)
                                                                                    toolMsg
                                                                                            .getContent();
                                                                    return notifyToolResult(trb);
                                                                })
                                                        .then();
                                            });
                        });
    }

    /**
     * Extract the most recent tool calls from memory.
     * Traverses backward from the last message until a non-ToolUseBlock message is encountered.
     *
     * @return List of tool use blocks from the last reasoning round
     */
    private List<ToolUseBlock> extractRecentToolCalls() {
        List<Msg> messages = getMemory().getMessages();
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        List<ToolUseBlock> toolCalls = new ArrayList<>();

        // Traverse backward to collect consecutive ToolUseBlock messages from this agent
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            if (msg.getContent() instanceof ToolUseBlock tub && msg.getName().equals(getName())) {
                toolCalls.add(0, tub); // Insert at beginning to maintain order
            } else {
                break; // Stop at first non-ToolUseBlock message
            }
        }

        return toolCalls;
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
     * Get the formatter.
     */
    public FormatterBase getFormatter() {
        return formatter;
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
        private FormatterBase formatter = new OpenAIChatFormatter();
        private Memory memory;
        private int maxIters = 10;
        private final List<Hook> hooks = new ArrayList<>();

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

        public Builder formatter(FormatterBase formatter) {
            this.formatter = formatter;
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

        public ReActAgent build() {
            return new ReActAgent(
                    name, sysPrompt, model, toolkit, formatter, memory, maxIters, hooks);
        }
    }
}
