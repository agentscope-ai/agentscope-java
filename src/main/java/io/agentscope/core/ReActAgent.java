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
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.RegisteredToolFunction;
import io.agentscope.core.tool.ToolResultMessageBuilder;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonSchemaUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

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
        return Mono.fromRunnable(() -> addToMemory(msg)).then(executeReActLoop());
    }

    @Override
    protected Mono<Msg> doCall(List<Msg> msgs) {
        return Mono.fromRunnable(() -> msgs.forEach(this::addToMemory)).then(executeReActLoop());
    }

    @Override
    protected Mono<Msg> doCall() {
        // Continue generation based on current memory without adding new messages
        return executeReActLoop();
    }

    @Override
    public <T> Mono<T> call(Msg msg, Class<T> structuredOutputClass) {
        return callWithToolBasedStructuredOutput(List.of(msg), structuredOutputClass);
    }

    @Override
    public <T> Mono<T> call(List<Msg> msgs, Class<T> structuredOutputClass) {
        return callWithToolBasedStructuredOutput(msgs, structuredOutputClass);
    }

    /**
     * Generate structured output using tool-based approach (multiple messages).
     */
    private <T> Mono<T> callWithToolBasedStructuredOutput(
            List<Msg> msgs, Class<T> structuredOutputClass) {
        return Mono.defer(
                () -> {
                    // Generate JSON schema from target class
                    Map<String, Object> jsonSchema =
                            JsonSchemaUtils.generateSchemaFromClass(structuredOutputClass);

                    // Create tool from schema
                    AgentTool finishTool = createStructuredOutputTool(jsonSchema);
                    RegisteredToolFunction registeredTool =
                            new RegisteredToolFunction(finishTool, null, null, null);
                    toolkit.registerAgentTool(registeredTool.getTool());

                    // Add messages and execute normal ReAct loop
                    return Mono.fromRunnable(() -> msgs.forEach(this::addToMemory))
                            .then(executeReActLoop())
                            .flatMap(
                                    responseMsg -> {
                                        // Extract structured data from metadata
                                        if (responseMsg.getMetadata() != null
                                                && responseMsg
                                                        .getMetadata()
                                                        .containsKey("response")) {
                                            Object data = responseMsg.getMetadata().get("response");
                                            return Mono.just(
                                                    JsonSchemaUtils.convertToObject(
                                                            data, structuredOutputClass));
                                        }

                                        return Mono.error(
                                                new IllegalStateException(
                                                        "Structured output not found in response"
                                                                + " metadata"));
                                    })
                            .doFinally(
                                    signal -> {
                                        // Clean up: unregister the temporary tool
                                        toolkit.removeTool("generate_response");
                                    });
                });
    }

    /**
     * Create AgentTool from JSON schema for tool-based structured output.
     */
    private AgentTool createStructuredOutputTool(Map<String, Object> schema) {

        return new AgentTool() {
            @Override
            public String getName() {
                return "generate_response";
            }

            @Override
            public String getDescription() {
                return "Generate the final structured response. Call this function when"
                        + " you have all the information needed to provide a complete answer.";
            }

            @Override
            public Map<String, Object> getParameters() {
                // Wrap schema in a "response" parameter
                Map<String, Object> params = new HashMap<>();
                params.put("type", "object");
                params.put("properties", Map.of("response", schema));
                params.put("required", List.of("response"));
                return params;
            }

            @Override
            public Mono<ToolResultBlock> callAsync(Map<String, Object> input) {
                return Mono.fromCallable(
                        () -> {
                            // Extract response data
                            Object responseData = input.get("response");

                            // Create result message with response data in metadata
                            Msg responseMsg =
                                    Msg.builder()
                                            .name(getName())
                                            .role(MsgRole.ASSISTANT)
                                            .content(TextBlock.builder().text("").build())
                                            .metadata(
                                                    responseData != null
                                                            ? Map.of("response", responseData)
                                                            : Map.of())
                                            .build();

                            // Create ToolResultBlock with metadata
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

    /**
     * Get the last ASSISTANT message from memory.
     *
     * @return Mono containing the last ASSISTANT message
     */
    private Mono<Msg> getLastAssistantMessage() {
        return Mono.fromCallable(
                () -> {
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
                    throw new IllegalStateException(
                            "Reasoning completed but no messages generated");
                });
    }

    /**
     * Finalize reasoning by building and saving the final message.
     * This method simulates the finally block semantics - it must execute whether reasoning
     * completes successfully or is interrupted.
     *
     * @param context The reasoning context containing accumulated content
     * @param wasInterrupted Whether this finalization is triggered by an interruption
     * @return Mono that completes when finalization is done
     */
    private Mono<Void> finalizeReasoning(ReasoningContext context, boolean wasInterrupted) {
        return Mono.fromCallable(context::buildFinalMessage)
                .flatMap(
                        reasoningMsg -> {
                            if (reasoningMsg == null) {
                                return Mono.empty();
                            }

                            List<ToolUseBlock> toolBlocks =
                                    reasoningMsg.getContentBlocks(ToolUseBlock.class);

                            // If interrupted and has tool calls, set them as pending
                            if (wasInterrupted && !toolBlocks.isEmpty()) {
                                setPendingToolCalls(toolBlocks);
                            }

                            // Notify postReasoning hook (may modify the message)
                            return notifyPostReasoning(reasoningMsg)
                                    .flatMap(
                                            modifiedMsg -> {
                                                // Save to memory in flatMap to ensure order
                                                addToMemory(modifiedMsg);

                                                // Notify preActing hooks for each tool call
                                                return Flux.fromIterable(toolBlocks)
                                                        .concatMap(this::notifyPreActing)
                                                        .then();
                                            });
                        });
    }

    /**
     * Execute the ReAct loop with simplified control flow.
     * Each iteration: Check max iterations -> Reasoning -> Check if finished -> Acting (if needed) -> Next iteration
     *
     * Note: We always execute at least one reasoning step per call(), even if memory contains
     * previous ASSISTANT messages. This ensures agents in pipelines work correctly when sharing memory.
     *
     * @return Mono containing the final response message
     */
    private Mono<Msg> executeReActLoop() {
        return executeIteration(0);
    }

    /**
     * Execute a single iteration of the ReAct loop.
     *
     * @param iter Current iteration number
     * @return Mono containing the final response message
     */
    private Mono<Msg> executeIteration(int iter) {
        if (iter >= maxIters) {
            return summarizing();
        }

        return checkInterruptedAsync()
                .then(reasoning())
                .then(Mono.defer(this::afterReasoning))
                .then(Mono.defer(() -> actingOrFinish(iter)));
    }

    /**
     * Process state after reasoning completes.
     * Extracts tool calls and checks for interruption.
     *
     * @return Mono that completes when post-reasoning processing is done
     */
    private Mono<Void> afterReasoning() {
        List<ToolUseBlock> toolCalls = extractRecentToolCalls();
        if (!toolCalls.isEmpty()) {
            setPendingToolCalls(toolCalls);
        }
        return checkInterruptedAsync();
    }

    /**
     * Either finish the loop or continue with acting step.
     *
     * @param iter Current iteration number
     * @return Mono containing the final response message
     */
    private Mono<Msg> actingOrFinish(int iter) {
        if (isFinished()) {
            return getLastAssistantMessage();
        }

        return acting().then(Mono.defer(() -> afterActing(iter)));
    }

    /**
     * Process state after acting completes.
     * Checks for structured output response or continues to next iteration.
     *
     * @param iter Current iteration number
     * @return Mono containing the final response message
     */
    private Mono<Msg> afterActing(int iter) {
        Msg structuredOutputMsg = checkStructuredOutputResponse();
        if (structuredOutputMsg != null) {
            return Mono.just(structuredOutputMsg);
        }
        return executeIteration(iter + 1);
    }

    /**
     * The reasoning step in ReAct algorithm.
     * This method generates reasoning based on the current context and input.
     * Handles streaming, hook notifications, and saves all messages to memory.
     *
     * @return Mono that completes when reasoning is done (or errors if interrupted)
     */
    private Mono<Void> reasoning() {
        return Mono.defer(
                () -> {
                    ReasoningContext context = new ReasoningContext(getName());

                    // Prepare message list - Model will format internally using its formatter
                    List<Msg> messageList = prepareMessageList();

                    // Create default options
                    GenerateOptions options = GenerateOptions.builder().build();

                    // Always pass tools for tool-based structured output
                    List<ToolSchema> toolSchemas = toolkit.getToolSchemasForModel();

                    // Main flow: notify preReasoning -> stream -> process chunks
                    Mono<Void> mainFlow =
                            notifyPreReasoning(this)
                                    .thenMany(model.stream(messageList, toolSchemas, options))
                                    .concatMap(
                                            chunk ->
                                                    checkInterruptedAsync()
                                                            .thenReturn(chunk)
                                                            .flatMapMany(
                                                                    c -> {
                                                                        List<Msg> msgs =
                                                                                context
                                                                                        .processChunk(
                                                                                                c);
                                                                        return Flux.fromIterable(
                                                                                        msgs)
                                                                                .concatMap(
                                                                                        msg ->
                                                                                                notifyStreamingMsg(
                                                                                                        msg,
                                                                                                        context));
                                                                    }))
                                    .then();

                    // Simulate try-catch-finally: capture error, always finalize, re-throw error
                    AtomicReference<Throwable> caughtError = new AtomicReference<>();

                    return mainFlow.doOnError(caughtError::set)
                            .onErrorResume(e -> Mono.empty())
                            .then(
                                    Mono.defer(
                                            () -> {
                                                boolean wasInterrupted =
                                                        caughtError.get()
                                                                instanceof InterruptedException;
                                                return finalizeReasoning(context, wasInterrupted);
                                            }))
                            .then(
                                    Mono.defer(
                                            () -> {
                                                Throwable error = caughtError.get();
                                                return error != null
                                                        ? Mono.error(error)
                                                        : Mono.empty();
                                            }));
                });
    }

    /**
     * Notify hooks about streaming messages during reasoning.
     * Handles TextBlock and ThinkingBlock content.
     *
     * @param msg The streaming message
     * @param context The reasoning context containing accumulated state
     * @return Mono that completes when all hooks are notified
     */
    private Mono<Void> notifyStreamingMsg(Msg msg, ReasoningContext context) {
        ContentBlock content = msg.getFirstContentBlock();

        ContentBlock accumulatedContent = null;
        if (content instanceof TextBlock) {
            accumulatedContent = TextBlock.builder().text(context.getAccumulatedText()).build();
        } else if (content instanceof ThinkingBlock) {
            accumulatedContent =
                    ThinkingBlock.builder().text(context.getAccumulatedThinking()).build();
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

    /**
     * The acting step in ReAct algorithm.
     * This method executes tools based on the most recent tool calls in memory.
     * Each tool result is saved to memory and hooks are notified.
     *
     * @return Mono that completes when all tools are executed and results are saved
     */
    private Mono<Void> acting() {
        return Mono.defer(
                () -> {
                    List<ToolUseBlock> toolCalls = extractRecentToolCalls();
                    if (toolCalls.isEmpty()) {
                        return Mono.empty();
                    }

                    // Set up chunk callback to notify hooks when tools emit streaming responses
                    toolkit.setChunkCallback(
                            (toolUse, chunk) -> notifyActingChunk(toolUse, chunk).subscribe());

                    // Execute all tools and process results
                    return toolkit.callTools(toolCalls)
                            .flatMapMany(
                                    responses ->
                                            Flux.zip(
                                                            Flux.fromIterable(toolCalls),
                                                            Flux.fromIterable(responses))
                                                    .concatMap(
                                                            tuple -> {
                                                                ToolUseBlock originalCall =
                                                                        tuple.getT1();
                                                                ToolResultBlock response =
                                                                        tuple.getT2();

                                                                Msg toolMsg =
                                                                        ToolResultMessageBuilder
                                                                                .buildToolResultMsg(
                                                                                        response,
                                                                                        originalCall,
                                                                                        getName());

                                                                // Save to memory synchronously
                                                                addToMemory(toolMsg);

                                                                // Notify postActing hook
                                                                ToolResultBlock trb =
                                                                        (ToolResultBlock)
                                                                                toolMsg
                                                                                        .getFirstContentBlock();
                                                                return notifyPostActing(
                                                                        originalCall, trb);
                                                            }))
                            .then()
                            .then(checkInterruptedAsync());
                });
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
        return recentToolCalls.stream()
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
     * Generate a response by summarizing the current situation when the agent
     * fails to solve the problem within the maximum iterations.
     *
     * <p>This method is called when the agent reaches maxIters without calling
     * the finish function. It prompts the model to summarize the current state
     * and provide a response based on what has been done so far.
     *
     * <p>Aligned with Python implementation: _summarizing() in _react_agent.py:479-513
     *
     * @return Mono containing the summary message
     */
    protected Mono<Msg> summarizing() {
        return Mono.defer(
                () -> {
                    log.debug("Maximum iterations reached. Generating summary...");

                    // Create hint message to guide the model
                    Msg hintMsg =
                            Msg.builder()
                                    .name("user")
                                    .role(MsgRole.USER)
                                    .content(
                                            TextBlock.builder()
                                                    .text(
                                                            "You have failed to generate response"
                                                                + " within the maximum iterations."
                                                                + " Now respond directly by"
                                                                + " summarizing the current"
                                                                + " situation.")
                                                    .build())
                                    .build();

                    // Prepare messages: system prompt + memory + hint
                    List<Msg> messageList = new ArrayList<>();

                    // Add system prompt
                    if (sysPrompt != null && !sysPrompt.trim().isEmpty()) {
                        messageList.add(
                                Msg.builder()
                                        .name("system")
                                        .role(MsgRole.SYSTEM)
                                        .content(TextBlock.builder().text(sysPrompt).build())
                                        .build());
                    }

                    // Add memory messages
                    messageList.addAll(getMemory().getMessages());

                    // Add hint message
                    messageList.add(hintMsg);

                    // Call model WITHOUT tools to generate summary
                    GenerateOptions options = GenerateOptions.builder().build();

                    // Create reasoning context to accumulate response
                    ReasoningContext context = new ReasoningContext(getName());

                    return model.stream(messageList, null, options)
                            .concatMap(
                                    chunk ->
                                            checkInterruptedAsync()
                                                    .thenReturn(chunk)
                                                    .doOnNext(context::processChunk))
                            .then(
                                    Mono.defer(
                                            () -> {
                                                Msg summaryMsg = context.buildFinalMessage();

                                                if (summaryMsg != null) {
                                                    addToMemory(summaryMsg);
                                                    return Mono.just(summaryMsg);
                                                }

                                                // Fallback if no content generated
                                                Msg errorMsg =
                                                        Msg.builder()
                                                                .name(getName())
                                                                .role(MsgRole.ASSISTANT)
                                                                .content(
                                                                        TextBlock.builder()
                                                                                .text(
                                                                                        "Maximum"
                                                                                            + " iterations"
                                                                                            + " ("
                                                                                                + maxIters
                                                                                                + ") reached."
                                                                                                + " Unable"
                                                                                                + " to generate"
                                                                                                + " summary.")
                                                                                .build())
                                                                .build();
                                                addToMemory(errorMsg);
                                                return Mono.just(errorMsg);
                                            }))
                            .onErrorResume(InterruptedException.class, Mono::error)
                            .onErrorResume(
                                    e -> {
                                        log.error("Error generating summary", e);

                                        Msg errorMsg =
                                                Msg.builder()
                                                        .name(getName())
                                                        .role(MsgRole.ASSISTANT)
                                                        .content(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "Maximum iterations"
                                                                                        + " ("
                                                                                        + maxIters
                                                                                        + ") reached."
                                                                                        + " Error"
                                                                                        + " generating"
                                                                                        + " summary:"
                                                                                        + " "
                                                                                        + e
                                                                                                .getMessage())
                                                                        .build())
                                                        .build();
                                        addToMemory(errorMsg);
                                        return Mono.just(errorMsg);
                                    });
                });
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
