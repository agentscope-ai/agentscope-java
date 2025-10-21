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
    private final String finishFunctionName;
    private final boolean parallelToolCalls;
    private final int maxIters;

    public ReActAgent(String name, String sysPrompt, Model model, Toolkit toolkit, Memory memory) {
        this(
                name,
                sysPrompt,
                model,
                toolkit,
                new OpenAIChatFormatter(),
                memory,
                10,
                "generate_response",
                false,
                List.of());
    }

    public ReActAgent(
            String name,
            String sysPrompt,
            Model model,
            Toolkit toolkit,
            FormatterBase formatter,
            Memory memory,
            int maxIters,
            String finishFunctionName,
            boolean parallelToolCalls,
            List<Hook> hooks) {
        super(name, memory, hooks);
        this.sysPrompt = sysPrompt;
        this.model = model;
        this.toolkit = toolkit;
        this.formatter = formatter;
        this.finishFunctionName = finishFunctionName;
        this.parallelToolCalls = parallelToolCalls;
        this.maxIters = maxIters;
    }

    @Override
    protected Flux<Msg> doCall(Msg msg) {
        addToMemory(msg);
        return executeReActLoop(0);
    }

    @Override
    protected Flux<Msg> doCall(List<Msg> msgs) {
        for (Msg m : msgs) {
            addToMemory(m);
        }
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
     * @return Flux of messages from this iteration and subsequent iterations
     */
    private Flux<Msg> executeReActLoop(int iter) {
        // Check max iterations
        if (iter >= maxIters) {
            return Flux.empty();
        }

        // Always execute reasoning for the current iteration
        // (Do not skip based on historical messages in memory)
        Flux<Msg> reasoningFlux = reasoning();

        // Acting phase + next iteration (if needed)
        Flux<Msg> actingAndNext =
                Mono.fromCallable(
                                () -> {
                                    // Check if finished after reasoning
                                    List<Msg> msgs = getMemory().getMessages();
                                    if (msgs == null || msgs.isEmpty()) {
                                        return false;
                                    }
                                    Msg lastMsg = msgs.get(msgs.size() - 1);
                                    return !isFinished(lastMsg);
                                })
                        .flatMapMany(
                                needsActing -> {
                                    if (!needsActing) {
                                        // Finished, no more iterations
                                        return Flux.empty();
                                    }
                                    // Execute tools and continue to next iteration
                                    Flux<Msg> actingFlux = acting();
                                    Flux<Msg> nextIteration = executeReActLoop(iter + 1);
                                    return Flux.concat(actingFlux, nextIteration);
                                });

        return Flux.concat(reasoningFlux, actingAndNext);
    }

    /**
     * The reasoning step in ReAct algorithm.
     * This method generates reasoning based on the current context and input.
     * Streams messages as they arrive from the model.
     */
    private Flux<Msg> reasoning() {
        // Format messages synchronously
        List<Msg> messageList = prepareMessageList();
        FormattedMessageList formattedMessages = formatter.format(messageList);

        List<ToolSchema> toolSchemas = toolkit.getToolSchemasForModel();
        GenerateOptions options = new GenerateOptions();

        // Create reasoning context to manage state
        ReasoningContext context = new ReasoningContext(getName());

        // Stream model responses - each chunk is immediately emitted
        Flux<Msg> streamedMsgs =
                model.streamFlux(formattedMessages, toolSchemas, options)
                        .flatMap(
                                chunk -> {
                                    // Process chunk and get streaming messages
                                    List<Msg> msgs = context.processChunk(chunk);

                                    // Notify hooks about streaming chunks with accumulated context
                                    return Flux.fromIterable(msgs)
                                            .flatMap(
                                                    chunkMsg -> {
                                                        // For text blocks, notify with accumulated
                                                        // content
                                                        if (chunkMsg.getContent()
                                                                instanceof TextBlock) {
                                                            Msg accumulated =
                                                                    context
                                                                            .buildAccumulatedTextMsg();
                                                            return notifyReasoningChunk(
                                                                            chunkMsg, accumulated)
                                                                    .thenReturn(chunkMsg);
                                                        }
                                                        // For other blocks, just emit
                                                        return Mono.just(chunkMsg);
                                                    });
                                });

        // Combine streams and save to memory BEFORE completing
        return streamedMsgs
                .concatWith(Flux.defer(() -> context.emitFinalizedToolCalls()))
                .concatWith(
                        Mono.fromRunnable(
                                () -> {
                                    // Save aggregated message to memory
                                    Msg aggregated = context.buildMemoryMessage();
                                    if (aggregated != null) {
                                        addToMemory(aggregated);
                                    }
                                }));
    }

    /**
     * Notify hooks about reasoning chunk with accumulated context.
     */
    private Mono<Void> notifyReasoningChunk(Msg chunk, Msg accumulated) {
        return Flux.fromIterable(getHooks())
                .flatMap(hook -> hook.onReasoningChunk(this, chunk, accumulated))
                .then();
    }

    /**
     * The acting step in ReAct algorithm.
     * This method executes actions based on reasoning results.
     */
    private Flux<Msg> acting() {
        return Mono.fromCallable(
                        () -> {
                            // Synchronously get tool calls from last message
                            List<Msg> messages = getMemory().getMessages();
                            if (messages == null || messages.isEmpty()) {
                                return List.<ToolUseBlock>of();
                            }
                            Msg lastMsg = messages.get(messages.size() - 1);
                            return extractToolCalls(lastMsg);
                        })
                .flatMapMany(
                        toolCalls -> {
                            if (toolCalls.isEmpty()) {
                                return Flux.empty();
                            }

                            // Execute tools asynchronously
                            return toolkit.callTools(toolCalls)
                                    .flatMapMany(
                                            responses -> {
                                                // Build tool result messages
                                                List<Msg> toolResults = new ArrayList<>();
                                                for (int i = 0;
                                                        i < responses.size()
                                                                && i < toolCalls.size();
                                                        i++) {
                                                    ToolResponse response = responses.get(i);
                                                    ToolUseBlock originalCall = toolCalls.get(i);

                                                    Msg toolMsg =
                                                            ToolResultMessageBuilder
                                                                    .buildToolResultMsg(
                                                                            response,
                                                                            originalCall,
                                                                            getName());
                                                    addToMemory(toolMsg);
                                                    toolResults.add(toolMsg);
                                                }

                                                // Return all tool results (triggers hooks)
                                                return Flux.fromIterable(toolResults);
                                            });
                        });
    }

    /**
     * Check if the message indicates the ReAct loop should finish.
     */
    private boolean isFinished(Msg msg) {
        // Check if the message contains a finish function call
        List<ToolUseBlock> toolCalls = extractToolCalls(msg);
        return toolCalls.stream()
                .noneMatch(toolCall -> toolkit.getTool(toolCall.getName()) != null);
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
     * Extract tool calls from a message.
     */
    private List<ToolUseBlock> extractToolCalls(Msg msg) {
        List<ToolUseBlock> toolCalls = new ArrayList<>();
        ContentBlock content = msg.getContent();

        if (content instanceof ToolUseBlock toolUseBlock) {
            toolCalls.add(toolUseBlock);
        }

        return toolCalls;
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
     * Get the finish function name.
     */
    public String getFinishFunctionName() {
        return finishFunctionName;
    }

    /**
     * Check if parallel tool calls are enabled.
     */
    public boolean isParallelToolCalls() {
        return parallelToolCalls;
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
        private String finishFunctionName = "generate_response";
        private boolean parallelToolCalls = false;
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

        public Builder finishFunctionName(String finishFunctionName) {
            this.finishFunctionName = finishFunctionName;
            return this;
        }

        public Builder parallelToolCalls(boolean parallelToolCalls) {
            this.parallelToolCalls = parallelToolCalls;
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
                    name,
                    sysPrompt,
                    model,
                    toolkit,
                    formatter,
                    memory,
                    maxIters,
                    finishFunctionName,
                    parallelToolCalls,
                    hooks);
        }
    }
}
