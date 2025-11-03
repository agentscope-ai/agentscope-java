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
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ExecutionConfig;
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
 * <p>ReAct is an agent design pattern that combines reasoning (thinking and planning) with acting
 * (tool execution) in an iterative loop. The agent alternates between these two phases until it
 * either completes the task or reaches the maximum iteration limit.
 *
 * <p><b>How It Works:</b>
 * <ol>
 *   <li><b>Reasoning Phase:</b> The agent analyzes the current context and decides what action to
 *       take next. It may generate tool calls, request information, or produce a final response.
 *   <li><b>Acting Phase:</b> If tool calls were generated during reasoning, the agent executes
 *       them in parallel and adds the results to memory.
 *   <li><b>Iteration:</b> The loop continues until the agent produces a response without tool
 *       calls, or until maxIters is reached.
 * </ol>
 *
 * <p><b>Key Features:</b>
 * <ul>
 *   <li><b>Tool Calling:</b> Execute arbitrary tools with annotation-based registration. Tools can
 *       be called in parallel and support streaming responses.
 *   <li><b>Memory Management:</b> All messages (user inputs, reasoning steps, tool results) are
 *       automatically saved to memory for context continuity.
 *   <li><b>Streaming Support:</b> Real-time response generation with chunk-by-chunk updates
 *       delivered through hooks.
 *   <li><b>Hook System:</b> Monitor and modify agent execution at multiple points (before/after
 *       reasoning, before/after acting, on chunks, on errors).
 *   <li><b>Interruption:</b> Gracefully interrupt long-running operations with automatic cleanup
 *       and recovery messages.
 *   <li><b>Structured Output:</b> Generate type-safe responses using tool-based structured output
 *       with JSON schema validation.
 *   <li><b>Max Iterations Handling:</b> Automatically generates a summary when unable to complete
 *       within the iteration limit.
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
 * <p><b>Builder Pattern:</b> This class uses the builder pattern for construction. Configure the
 * agent by calling builder methods and then call {@code build()} to create the instance. Once
 * created, the agent configuration is immutable.
 *
 * <p><b>Thread Safety:</b> Agent instances are designed for single-threaded use per invocation.
 * While the agent uses reactive patterns (Mono/Flux) internally, concurrent calls to the same
 * agent instance may lead to memory consistency issues.
 *
 * @see #builder()
 * @see #call(Msg)
 * @see #reasoning()
 * @see #acting()
 */
public class ReActAgent extends AgentBase {

    private static final Logger log = LoggerFactory.getLogger(ReActAgent.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // ReActAgent manages its own memory
    private final Memory memory;
    private final String sysPrompt;
    private final Model model;
    private final Toolkit toolkit;
    private final int maxIters;
    private final ExecutionConfig modelExecutionConfig;
    private final ExecutionConfig toolExecutionConfig;

    // Pending tool calls for interrupt handling (moved from AgentBase)
    private final AtomicReference<List<ToolUseBlock>> pendingToolCalls =
            new AtomicReference<>(null);

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
        // ReActAgent manages its own memory
        this.memory = memory != null ? memory : new InMemoryMemory();
        this.sysPrompt = sysPrompt;
        this.model = model;
        this.toolkit = toolkit;
        this.maxIters = maxIters;
        this.modelExecutionConfig = modelExecutionConfig;
        this.toolExecutionConfig = toolExecutionConfig;

        // Register memory as a nested state module
        addNestedModule("memory", this.memory);
    }

    @Override
    protected Mono<Msg> doCall(Msg msg) {
        // Add input message to memory
        if (msg != null) {
            memory.addMessage(msg);
        }
        return executeReActLoop();
    }

    @Override
    protected Mono<Msg> doCall(List<Msg> msgs) {
        // Add input messages to memory
        if (msgs != null) {
            msgs.forEach(memory::addMessage);
        }
        return executeReActLoop();
    }

    @Override
    protected Mono<Msg> doCall() {
        // Continue generation based on current memory without adding new messages
        return executeReActLoop();
    }

    /**
     * Call with structured output support (single message).
     * Generates a response conforming to the specified JSON schema.
     * The structured data will be stored in the returned message's metadata field.
     *
     * @param msg Input message
     * @param structuredOutputClass Class defining the structure of the output
     * @return Mono containing response message with structured data in metadata
     */
    @Override
    public Mono<Msg> call(Msg msg, Class<?> structuredOutputClass) {
        return callWithToolBasedStructuredOutput(List.of(msg), structuredOutputClass);
    }

    /**
     * Call with structured output support (multiple messages).
     * Generates a response conforming to the specified JSON schema.
     * The structured data will be stored in the returned message's metadata field.
     *
     * @param msgs Input messages
     * @param structuredOutputClass Class defining the structure of the output
     * @return Mono containing response message with structured data in metadata
     */
    @Override
    public Mono<Msg> call(List<Msg> msgs, Class<?> structuredOutputClass) {
        return callWithToolBasedStructuredOutput(msgs, structuredOutputClass);
    }

    /**
     * Call with structured output support (no new messages).
     * Generates a response conforming to the specified JSON schema based on current memory state.
     * The structured data will be stored in the returned message's metadata field.
     *
     * @param structuredOutputClass Class defining the structure of the output
     * @return Mono containing response message with structured data in metadata
     */
    @Override
    public Mono<Msg> call(Class<?> structuredOutputClass) {
        return callWithToolBasedStructuredOutput(List.of(), structuredOutputClass);
    }

    /**
     * Generate structured output using tool-based approach (multiple messages).
     * Creates a temporary "generate_response" tool that the agent must call with the structured
     * data. Returns a message with the structured data stored in metadata.
     *
     * @param msgs Input messages to add to memory
     * @param structuredOutputClass Class defining the expected structure
     * @return Mono containing response message with structured data in metadata
     */
    private Mono<Msg> callWithToolBasedStructuredOutput(
            List<Msg> msgs, Class<?> structuredOutputClass) {
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
                    return Mono.fromRunnable(() -> msgs.forEach(memory::addMessage))
                            .then(executeReActLoop())
                            .flatMap(this::extractStructuredOutputFromResponse)
                            .doFinally(
                                    signal -> {
                                        // Clean up: unregister the temporary tool
                                        toolkit.removeTool("generate_response");
                                    });
                });
    }

    /**
     * Extract structured output data from response message metadata.
     * Unwraps the "response" key and creates a new message with the data directly in metadata.
     *
     * @param responseMsg The response message from tool execution
     * @return Mono containing Msg with unwrapped structured data in metadata
     */
    private Mono<Msg> extractStructuredOutputFromResponse(Msg responseMsg) {
        if (responseMsg.getMetadata() == null
                || !responseMsg.getMetadata().containsKey("response")) {
            return Mono.error(
                    new IllegalStateException("Structured output not found in response metadata"));
        }

        Object responseData = responseMsg.getMetadata().get("response");

        Msg finalMsg =
                Msg.builder()
                        .name(responseMsg.getName())
                        .role(responseMsg.getRole())
                        .content(responseMsg.getContent())
                        .metadata(
                                responseData instanceof Map
                                        ? (Map<String, Object>) responseData
                                        : Map.of("data", responseData))
                        .build();

        return Mono.just(finalMsg);
    }

    /**
     * Create AgentTool from JSON schema for tool-based structured output.
     *
     * <p>This creates a temporary tool named "generate_response" that accepts a single "response"
     * parameter matching the provided schema. When the model calls this tool, the structured data
     * is extracted and returned in the message metadata.
     *
     * @param schema JSON schema defining the expected structure of the response
     * @return AgentTool instance for structured output generation
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

                            // Convert responseData to JSON string for content
                            String contentText = "";
                            if (responseData != null) {
                                try {
                                    ObjectMapper mapper = new ObjectMapper();
                                    contentText = mapper.writeValueAsString(responseData);
                                } catch (Exception e) {
                                    // Fallback to toString if JSON serialization fails
                                    contentText = responseData.toString();
                                }
                            }

                            // Create result message with response data in metadata
                            Msg responseMsg =
                                    Msg.builder()
                                            .name(getName())
                                            .role(MsgRole.ASSISTANT)
                                            .content(TextBlock.builder().text(contentText).build())
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
                    List<Msg> msgs = memory.getMessages();
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
                                pendingToolCalls.set(toolBlocks);
                            }

                            // Notify postReasoning hook (may modify the message)
                            return notifyPostReasoning(reasoningMsg)
                                    .flatMap(
                                            modifiedMsg -> {
                                                // Save to memory in flatMap to ensure order
                                                memory.addMessage(modifiedMsg);

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
            pendingToolCalls.set(toolCalls);
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

                    // Create options with execution config if available
                    GenerateOptions.Builder optionsBuilder = GenerateOptions.builder();
                    if (modelExecutionConfig != null) {
                        optionsBuilder.executionConfig(modelExecutionConfig);
                    }
                    GenerateOptions options = optionsBuilder.build();

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

                    // Execute all tools with execution config
                    Mono<List<ToolResultBlock>> toolExecution =
                            toolkit.callTools(toolCalls, toolExecutionConfig);

                    return toolExecution
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
                                                                memory.addMessage(toolMsg);

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
     * This is added to support the new hook design.
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
        List<Msg> messages = memory.getMessages();
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
     * This method searches recent memory for TOOL role messages and extracts the response_msg from
     * the generate_response tool's metadata.
     *
     * @return The response message if generate_response was successful, null otherwise
     */
    private Msg checkStructuredOutputResponse() {
        List<Msg> msgs = memory.getMessages();
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
        messages.addAll(memory.getMessages());

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
                    List<Msg> messageList = prepareMessageList();

                    // Add hint message
                    messageList.add(hintMsg);

                    // Call model WITHOUT tools to generate summary
                    // Create options with execution config if available
                    GenerateOptions.Builder optionsBuilder = GenerateOptions.builder();
                    if (modelExecutionConfig != null) {
                        optionsBuilder.executionConfig(modelExecutionConfig);
                    }
                    GenerateOptions options = optionsBuilder.build();

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
                                                    memory.addMessage(summaryMsg);
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
                                                memory.addMessage(errorMsg);
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
                                        memory.addMessage(errorMsg);
                                        return Mono.just(errorMsg);
                                    });
                });
    }

    @Override
    protected Mono<Msg> handleInterrupt(InterruptContext context, Msg... originalArgs) {
        // Build recovery message with user-friendly text
        String recoveryText = "I noticed that you have interrupted me. What can I do for you?";

        Msg recoveryMsg =
                Msg.builder()
                        .name(getName())
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text(recoveryText).build())
                        .build();

        // Save recovery message to memory
        memory.addMessage(recoveryMsg);

        return Mono.just(recoveryMsg);
    }

    /**
     * Get the memory instance managed by this agent.
     *
     * @return The memory instance
     */
    public Memory getMemory() {
        return memory;
    }

    /**
     * Set a new memory instance for this agent.
     * Note: This replaces the memory and updates the nested module registry.
     *
     * @param memory The new memory instance
     */
    public void setMemory(Memory memory) {
        if (memory == null) {
            throw new IllegalArgumentException("Memory cannot be null");
        }
        // Note: Since memory is final, we cannot actually replace it.
        // This method is provided for API compatibility but throws an exception.
        throw new UnsupportedOperationException(
                "Memory cannot be replaced after agent construction. "
                        + "Create a new agent instance if you need different memory.");
    }

    /**
     * Observe a message without generating a reply.
     * Adds the observed message to memory for future context.
     *
     * @param msg Message to observe
     * @return Mono that completes when observation is done
     */
    @Override
    protected Mono<Void> doObserve(Msg msg) {
        if (msg != null) {
            memory.addMessage(msg);
        }
        return Mono.empty();
    }

    /**
     * Observe multiple messages without generating a reply.
     * Adds all observed messages to memory for future context.
     *
     * @param msgs Messages to observe
     * @return Mono that completes when all observations are done
     */
    @Override
    protected Mono<Void> doObserve(List<Msg> msgs) {
        if (msgs != null && !msgs.isEmpty()) {
            msgs.forEach(memory::addMessage);
        }
        return Mono.empty();
    }

    /**
     * Notify all hooks before reasoning step.
     * This is called before the agent starts the reasoning phase.
     *
     * @return Mono that completes when all hooks are notified
     */
    protected Mono<Void> notifyPreReasoning() {
        return Flux.fromIterable(getHooks()).flatMap(hook -> hook.preReasoning(this)).then();
    }

    /**
     * Notify all hooks after reasoning step, allowing modification of reasoning message.
     * This is called after the agent completes the reasoning phase.
     *
     * @param reasoningMsg The reasoning message generated by the model
     * @return Mono containing potentially modified reasoning message
     */
    protected Mono<Msg> notifyPostReasoning(Msg reasoningMsg) {
        Mono<Msg> result = Mono.just(reasoningMsg);
        for (Hook hook : getHooks()) {
            result = result.flatMap(m -> hook.postReasoning(this, m));
        }
        return result;
    }

    /**
     * Notify all hooks during reasoning streaming.
     * Sends chunks based on each hook's preferred mode (incremental vs cumulative).
     *
     * @param chunk The incremental chunk message
     * @param accumulated The accumulated message so far
     * @return Mono that completes when all hooks are notified
     */
    protected Mono<Void> notifyReasoningChunk(Msg chunk, Msg accumulated) {
        return Flux.fromIterable(getHooks())
                .flatMap(
                        hook -> {
                            Msg msgToSend =
                                    hook.reasoningChunkMode()
                                                    == io.agentscope.core.hook.ChunkMode.CUMULATIVE
                                            ? accumulated
                                            : chunk;
                            return hook.onReasoningChunk(this, msgToSend);
                        })
                .then();
    }

    /**
     * Notify all hooks before tool execution, allowing modification of tool parameters.
     * This is called before each tool is executed.
     *
     * @param toolUse Tool use block containing tool call information
     * @return Mono containing potentially modified tool use block
     */
    protected Mono<ToolUseBlock> notifyPreActing(ToolUseBlock toolUse) {
        Mono<ToolUseBlock> result = Mono.just(toolUse);
        for (Hook hook : getHooks()) {
            result = result.flatMap(t -> hook.preActing(this, t));
        }
        return result;
    }

    /**
     * Notify all hooks during tool execution streaming.
     * This is called for each chunk emitted by a streaming tool.
     *
     * @param toolUse Tool use block identifying the tool call
     * @param chunk The streaming chunk emitted by the tool
     * @return Mono that completes when all hooks are notified
     */
    protected Mono<Void> notifyActingChunk(ToolUseBlock toolUse, ToolResultBlock chunk) {
        return Flux.fromIterable(getHooks())
                .flatMap(hook -> hook.onActingChunk(this, toolUse, chunk))
                .then();
    }

    /**
     * Notify all hooks after tool execution, allowing modification of tool result.
     * This is called after each tool execution completes.
     *
     * @param toolUse Tool use block identifying the tool call
     * @param toolResult Tool result block containing execution results
     * @return Mono containing potentially modified tool result block
     */
    protected Mono<ToolResultBlock> notifyPostActing(
            ToolUseBlock toolUse, ToolResultBlock toolResult) {
        Mono<ToolResultBlock> result = Mono.just(toolResult);
        for (Hook hook : getHooks()) {
            result = result.flatMap(t -> hook.postActing(this, toolUse, t));
        }
        return result;
    }

    /**
     * Get the system prompt.
     *
     * @return the system prompt used to configure the agent's behavior
     */
    public String getSysPrompt() {
        return sysPrompt;
    }

    /**
     * Get the model.
     *
     * @return the language model used for generating responses
     */
    public Model getModel() {
        return model;
    }

    /**
     * Get the toolkit.
     *
     * @return the toolkit containing available tools for the agent to use
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

    /**
     * Create a new builder for constructing ReActAgent instances.
     *
     * <p>The builder pattern allows for step-by-step configuration of the agent's
     * properties including name, system prompt, model, toolkit, memory, and hooks.
     * Use the builder methods to set the desired configuration and then call {@code build()}
     * to create the immutable agent instance.
     *
     * @return a new Builder instance for constructing a ReActAgent
     */
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
        private ExecutionConfig modelExecutionConfig;
        private ExecutionConfig toolExecutionConfig;
        private final List<Hook> hooks = new ArrayList<>();
        private boolean enableMetaTool = false;

        private Builder() {}

        /**
         * Set the agent name.
         *
         * @param name The name of the agent
         * @return This builder
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Set the system prompt for the agent.
         *
         * @param sysPrompt The system prompt to guide agent behavior
         * @return This builder
         */
        public Builder sysPrompt(String sysPrompt) {
            this.sysPrompt = sysPrompt;
            return this;
        }

        /**
         * Set the language model for the agent.
         *
         * @param model The language model to use for reasoning
         * @return This builder
         */
        public Builder model(Model model) {
            this.model = model;
            return this;
        }

        /**
         * Set the toolkit containing tools the agent can use.
         *
         * @param toolkit The toolkit with available tools
         * @return This builder
         */
        public Builder toolkit(Toolkit toolkit) {
            this.toolkit = toolkit;
            return this;
        }

        /**
         * Set the memory implementation for the agent.
         *
         * @param memory The memory to store conversation history
         * @return This builder
         */
        public Builder memory(Memory memory) {
            this.memory = memory;
            return this;
        }

        /**
         * Set the maximum number of ReAct iterations.
         *
         * @param maxIters Maximum iterations before triggering summarization
         * @return This builder
         */
        public Builder maxIters(int maxIters) {
            this.maxIters = maxIters;
            return this;
        }

        /**
         * Add a single hook to monitor agent execution.
         *
         * @param hook The hook to add
         * @return This builder
         */
        public Builder hook(Hook hook) {
            this.hooks.add(hook);
            return this;
        }

        /**
         * Add multiple hooks to monitor agent execution.
         *
         * @param hooks The list of hooks to add
         * @return This builder
         */
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
         * Set the execution configuration for model API calls.
         *
         * <p>This controls timeout and retry behavior for model API calls during the reasoning phase.
         * If not set, the model's default execution config will be used.
         *
         * @param modelExecutionConfig The model execution configuration, or null for model defaults
         * @return This builder
         */
        public Builder modelExecutionConfig(ExecutionConfig modelExecutionConfig) {
            this.modelExecutionConfig = modelExecutionConfig;
            return this;
        }

        /**
         * Set the execution configuration for tool executions.
         *
         * <p>This controls timeout and retry behavior for tool executions during the acting phase.
         * If not set, the toolkit's default execution config will be used.
         *
         * @param toolExecutionConfig The tool execution configuration, or null for toolkit defaults
         * @return This builder
         */
        public Builder toolExecutionConfig(ExecutionConfig toolExecutionConfig) {
            this.toolExecutionConfig = toolExecutionConfig;
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
                    modelExecutionConfig,
                    toolExecutionConfig,
                    hooks);
        }
    }
}
