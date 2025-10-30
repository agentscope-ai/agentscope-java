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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.StructuredOutputHelper;
import io.agentscope.core.agent.StructuredOutputStrategy;
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
import io.agentscope.core.model.ModelCapabilities;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.RegisteredToolFunction;
import io.agentscope.core.tool.ToolResultMessageBuilder;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final String sysPrompt;
    private final Model model;
    private final Toolkit toolkit;
    private final int maxIters;
    private final StructuredOutputStrategy structuredOutputStrategy;

    public ReActAgent(
            String name,
            String sysPrompt,
            Model model,
            Toolkit toolkit,
            Memory memory,
            int maxIters,
            List<Hook> hooks,
            StructuredOutputStrategy structuredOutputStrategy) {
        super(name, memory, hooks);
        this.sysPrompt = sysPrompt;
        this.model = model;
        this.toolkit = toolkit;
        this.maxIters = maxIters;
        this.structuredOutputStrategy =
                structuredOutputStrategy != null
                        ? structuredOutputStrategy
                        : StructuredOutputStrategy.AUTO;
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

    @Override
    public <T> Mono<T> call(Msg msg, Class<T> structuredOutputClass) {
        return Mono.defer(
                () -> {
                    StructuredOutputStrategy actualStrategy = determineStrategy();

                    if (actualStrategy == StructuredOutputStrategy.NATIVE) {
                        throw new UnsupportedOperationException(
                                "Native structured output not yet implemented");
                    }

                    return callWithToolBasedStructuredOutput(msg, structuredOutputClass);
                });
    }

    @Override
    public <T> Mono<T> call(List<Msg> msgs, Class<T> structuredOutputClass) {
        return Mono.defer(
                () -> {
                    StructuredOutputStrategy actualStrategy = determineStrategy();

                    if (actualStrategy == StructuredOutputStrategy.NATIVE) {
                        throw new UnsupportedOperationException(
                                "Native structured output not yet implemented");
                    }

                    return callWithToolBasedStructuredOutput(msgs, structuredOutputClass);
                });
    }

    /**
     * Determine the actual strategy to use based on model capabilities and user configuration.
     *
     * @return The strategy to use
     */
    private StructuredOutputStrategy determineStrategy() {
        if (structuredOutputStrategy == StructuredOutputStrategy.TOOL_BASED) {
            return StructuredOutputStrategy.TOOL_BASED;
        }

        if (structuredOutputStrategy == StructuredOutputStrategy.NATIVE) {
            ModelCapabilities capabilities = model.getCapabilities();
            if (!capabilities.supportsNativeStructuredOutput()) {
                throw new UnsupportedOperationException(
                        "Model "
                                + model.getModelName()
                                + " does not support native structured output");
            }
            return StructuredOutputStrategy.NATIVE;
        }

        // AUTO mode: check model capabilities
        ModelCapabilities capabilities = model.getCapabilities();
        if (capabilities.supportsNativeStructuredOutput()) {
            return StructuredOutputStrategy.NATIVE;
        } else if (capabilities.supportsToolCalling()) {
            return StructuredOutputStrategy.TOOL_BASED;
        } else {
            throw new UnsupportedOperationException(
                    "Model "
                            + model.getModelName()
                            + " does not support structured output (neither native nor"
                            + " tool-based)");
        }
    }

    /**
     * Generate structured output using tool-based approach (single message).
     *
     * @param msg Input message
     * @param structuredOutputClass Target class for structured output
     * @param <T> Type of structured output
     * @return Mono emitting the structured output object
     */
    private <T> Mono<T> callWithToolBasedStructuredOutput(Msg msg, Class<T> structuredOutputClass) {
        return Mono.fromCallable(
                () -> {
                    // Generate JSON schema from class
                    Map<String, Object> jsonSchema =
                            StructuredOutputHelper.generateJsonSchema(structuredOutputClass);

                    // Temporarily register the structured finish tool
                    RegisteredToolFunction finishTool =
                            createStructuredFinishTool(structuredOutputClass);
                    toolkit.registerAgentTool(finishTool.getTool());

                    try {
                        // Call the agent normally
                        addToMemory(msg);
                        Msg responseMsg = executeReActLoop();

                        // Extract structured data from responseMsg metadata
                        if (responseMsg.getMetadata() != null
                                && responseMsg.getMetadata().containsKey("response")) {
                            Object data = responseMsg.getMetadata().get("response");
                            return StructuredOutputHelper.convertToObject(
                                    data, structuredOutputClass);
                        }

                        throw new IllegalStateException(
                                "Structured output not found in response metadata");
                    } finally {
                        // Clean up: unregister the temporary tool
                        toolkit.removeTool("generate_response");
                    }
                });
    }

    /**
     * Generate structured output using tool-based approach (multiple messages).
     *
     * @param msgs Input messages
     * @param structuredOutputClass Target class for structured output
     * @param <T> Type of structured output
     * @return Mono emitting the structured output object
     */
    private <T> Mono<T> callWithToolBasedStructuredOutput(
            List<Msg> msgs, Class<T> structuredOutputClass) {
        return Mono.fromCallable(
                () -> {
                    // Generate JSON schema from class
                    Map<String, Object> jsonSchema =
                            StructuredOutputHelper.generateJsonSchema(structuredOutputClass);

                    // Temporarily register the structured finish tool
                    RegisteredToolFunction finishTool =
                            createStructuredFinishTool(structuredOutputClass);
                    toolkit.registerAgentTool(finishTool.getTool());

                    try {
                        // Call the agent normally
                        msgs.forEach(this::addToMemory);
                        Msg responseMsg = executeReActLoop();

                        // Extract structured data from responseMsg metadata
                        if (responseMsg.getMetadata() != null
                                && responseMsg.getMetadata().containsKey("response")) {
                            Object data = responseMsg.getMetadata().get("response");
                            return StructuredOutputHelper.convertToObject(
                                    data, structuredOutputClass);
                        }

                        throw new IllegalStateException(
                                "Structured output not found in response metadata");
                    } finally {
                        // Clean up: unregister the temporary tool
                        toolkit.removeTool("generate_response");
                    }
                });
    }

    /**
     * Create a temporary finish tool that accepts structured output conforming to the schema.
     *
     * @param structuredOutputClass The class defining the structure
     * @param <T> Type parameter
     * @return A RegisteredToolFunction for the finish tool
     */
    private <T> RegisteredToolFunction createStructuredFinishTool(Class<T> structuredOutputClass) {
        // Generate JSON schema
        Map<String, Object> jsonSchema =
                StructuredOutputHelper.generateJsonSchema(structuredOutputClass);

        // Create a tool function that captures the structured data
        AgentTool toolFunction =
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "generate_response";
                    }

                    @Override
                    public String getDescription() {
                        return "Generate the final structured response. Call this function when"
                                + " you have all the information needed to provide a complete"
                                + " answer.";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        // Return schema with a "response" parameter
                        Map<String, Object> schema = new HashMap<>();
                        schema.put("type", "object");

                        Map<String, Object> properties = new HashMap<>();
                        properties.put("response", jsonSchema);
                        schema.put("properties", properties);

                        schema.put("required", List.of("response"));
                        return schema;
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(Map<String, Object> input) {
                        return Mono.fromCallable(
                                () -> {
                                    // Extract the response data from input
                                    Object responseData = input.get("response");

                                    // Construct the response message with metadata
                                    Msg responseMsg =
                                            Msg.builder()
                                                    .name(getName())
                                                    .role(MsgRole.ASSISTANT)
                                                    .content(TextBlock.builder().text("").build())
                                                    .metadata(
                                                            responseData != null
                                                                    ? Map.of(
                                                                            "response",
                                                                            responseData)
                                                                    : Map.of())
                                                    .build();

                                    // Return ToolResultBlock with metadata containing success flag
                                    // and response_msg
                                    Map<String, Object> metadata = new HashMap<>();
                                    metadata.put("success", true);
                                    metadata.put("response_msg", responseMsg);

                                    return ToolResultBlock.builder()
                                            .output(
                                                    TextBlock.builder()
                                                            .text(
                                                                    "Successfully generated"
                                                                            + " response.")
                                                            .build())
                                            .metadata(metadata)
                                            .build();
                                });
                    }
                };

        // RegisteredToolFunction constructor: (tool, groupName, extendedModel, mcpClientName)
        return new RegisteredToolFunction(toolFunction, null, null, null);
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
                // Return the last ASSISTANT message
                List<Msg> msgs = getMemory().getMessages();
                for (int i = msgs.size() - 1; i >= 0; i--) {
                    Msg msg = msgs.get(i);
                    if (msg.getRole() == MsgRole.ASSISTANT) {
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

            // After acting, check if generate_response was called successfully
            Msg structuredOutputMsg = checkStructuredOutputResponse();
            if (structuredOutputMsg != null) {
                return structuredOutputMsg;
            }
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
        StringBuilder accumulatedThinking = new StringBuilder();

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
                    notifyStreamingMsg(msg, accumulatedText, accumulatedThinking).block();
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
     * @param accumulatedThinking StringBuilder tracking accumulated thinking for CUMULATIVE mode
     * @return Mono that completes when all hooks are notified
     */
    private Mono<Void> notifyStreamingMsg(
            Msg msg, StringBuilder accumulatedText, StringBuilder accumulatedThinking) {
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
        } else if (content instanceof ThinkingBlock tb) {
            // For thinking blocks, accumulate and call onReasoningChunk
            accumulatedThinking.append(tb.getThinking());

            Msg accumulated =
                    Msg.builder()
                            .id(msg.getId())
                            .name(msg.getName())
                            .role(msg.getRole())
                            .content(
                                    ThinkingBlock.builder()
                                            .text(accumulatedThinking.toString())
                                            .build())
                            .build();

            return notifyReasoningChunk(msg, accumulated);
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
     * Check if generate_response tool was called successfully after acting.
     * This aligns with Python version's _acting method that returns response_msg
     * when the finish function is called successfully.
     *
     * @return The response message if generate_response was successful, null otherwise
     */
    private Msg checkStructuredOutputResponse() {
        List<Msg> msgs = getMemory().getMessages();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Msg msg = msgs.get(i);
            // Look for TOOL role messages (tool results)
            if (msg.getRole() == MsgRole.TOOL) {
                List<ToolResultBlock> toolResults = msg.getContentBlocks(ToolResultBlock.class);
                for (ToolResultBlock result : toolResults) {
                    // Check if this is generate_response tool result
                    if (result.getMetadata() != null
                            && Boolean.TRUE.equals(result.getMetadata().get("success"))
                            && result.getMetadata().containsKey("response_msg")) {
                        // Extract the response message from metadata
                        Object responseMsgObj = result.getMetadata().get("response_msg");
                        if (responseMsgObj instanceof Msg responseMsg) {
                            return responseMsg;
                        }
                    }
                }
                // Only check the most recent TOOL message
                break;
            }
        }
        return null;
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
        private StructuredOutputStrategy structuredOutputStrategy = StructuredOutputStrategy.AUTO;

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

        /**
         * Set strategy for structured output generation.
         *
         * @param strategy The strategy to use (AUTO, TOOL_BASED, or NATIVE)
         * @return This builder
         */
        public Builder structuredOutputStrategy(StructuredOutputStrategy strategy) {
            this.structuredOutputStrategy = strategy;
            return this;
        }

        public ReActAgent build() {
            // Auto-register meta tool if enabled
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
                    hooks,
                    structuredOutputStrategy);
        }
    }
}
