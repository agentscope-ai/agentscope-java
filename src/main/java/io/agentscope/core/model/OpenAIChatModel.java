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
package io.agentscope.core.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolChoiceOption;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * OpenAI Chat Model implementation using the official OpenAI Java SDK v3.5.3.
 * This implementation provides complete integration with OpenAI's Chat Completion API,
 * including tool calling and streaming support.
 */
public class OpenAIChatModel implements Model {

    private static final Logger log = LoggerFactory.getLogger(OpenAIChatModel.class);

    private final String baseUrl;
    private final String apiKey;
    private final ChatModel model;
    private final boolean streamEnabled;
    private final OpenAIClient client;
    private final ObjectMapper objectMapper;
    private final GenerateOptions defaultOptions;

    public OpenAIChatModel(
            String baseUrl,
            String apiKey,
            String modelName,
            boolean streamEnabled,
            GenerateOptions defaultOptions) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = ChatModel.of(modelName);
        this.streamEnabled = streamEnabled;
        this.defaultOptions = defaultOptions != null ? defaultOptions : new GenerateOptions();
        this.objectMapper = new ObjectMapper();

        // Initialize OpenAI client
        OpenAIOkHttpClient.Builder clientBuilder = OpenAIOkHttpClient.builder();

        if (apiKey != null) {
            clientBuilder.apiKey(apiKey);
        }

        if (baseUrl != null) {
            clientBuilder.baseUrl(baseUrl);
        }

        this.client = clientBuilder.build();
    }

    @Override
    public Flux<ChatResponse> streamFlux(
            FormattedMessageList messages, List<ToolSchema> tools, GenerateOptions options) {
        Instant startTime = Instant.now();
        log.debug(
                "OpenAI stream: model={}, messages={}, tools_present={}",
                model,
                messages.size(),
                tools != null && !tools.isEmpty());

        return Flux.defer(
                () -> {
                    try {
                        // Build chat completion request
                        ChatCompletionCreateParams.Builder paramsBuilder =
                                ChatCompletionCreateParams.builder().model(model);

                        // Add messages - now working directly with FormattedMessageList
                        for (FormattedMessage message : messages) {
                            addFormattedMessageToParams(paramsBuilder, message);
                        }

                        // Add tools if provided
                        if (tools != null && !tools.isEmpty()) {
                            addToolsToParams(paramsBuilder, tools);
                        }

                        // Apply generation options
                        applyGenerateOptions(paramsBuilder, options);

                        // Create the request
                        ChatCompletionCreateParams params = paramsBuilder.build();

                        if (streamEnabled) {
                            // Make streaming API call
                            StreamResponse<ChatCompletionChunk> streamResponse =
                                    client.chat().completions().createStreaming(params);

                            // Convert the SDK's Stream to Flux
                            return Flux.fromStream(streamResponse.stream())
                                    .map(chunk -> parseChunk(chunk, startTime))
                                    .filter(Objects::nonNull)
                                    .doFinally(
                                            signalType -> {
                                                try {
                                                    streamResponse.close();
                                                } catch (Exception ignored) {
                                                }
                                            });
                        } else {
                            // For non-streaming, make a single call and return as Flux
                            ChatCompletion completion = client.chat().completions().create(params);
                            ChatResponse response = parseCompletion(completion, startTime);
                            return Flux.just(response);
                        }
                    } catch (Exception e) {
                        return Flux.error(
                                new RuntimeException(
                                        "Failed to stream OpenAI API: " + e.getMessage(), e));
                    }
                });
    }

    private void addFormattedMessageToParams(
            ChatCompletionCreateParams.Builder paramsBuilder, FormattedMessage message) {
        String role = message.getRole();
        String content = message.getContentAsString();

        if (content == null) {
            content = "";
        }

        switch (role.toLowerCase()) {
            case "system":
                paramsBuilder.addSystemMessage(content);
                break;
            case "user":
                paramsBuilder.addUserMessage(content);
                break;
            case "assistant":
                // Check if this assistant message has tool calls
                if (message.hasToolCalls()) {
                    // For assistant messages with tool calls, we need to handle them properly
                    Map<String, Object> rawMessage = message.asMap();

                    // Create proper assistant message with tool
                    // calls
                    ChatCompletionAssistantMessageParam.Builder assistantBuilder =
                            ChatCompletionAssistantMessageParam.builder();

                    // Set content (can be null for tool-only messages)
                    if (content != null && !content.trim().isEmpty()) {
                        assistantBuilder.content(content);
                    }

                    // Add tool calls from the raw message
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> toolCalls =
                            (List<Map<String, Object>>) rawMessage.get("tool_calls");

                    if (toolCalls != null) {
                        for (Map<String, Object> toolCall : toolCalls) {
                            String id = (String) toolCall.get("id");
                            String type = (String) toolCall.get("type");
                            @SuppressWarnings("unchecked")
                            Map<String, Object> functionData =
                                    (Map<String, Object>) toolCall.get("function");

                            if ("function".equals(type) && functionData != null) {
                                String name = (String) functionData.get("name");
                                String arguments = (String) functionData.get("arguments");

                                // Create tool call parameter
                                var toolCallParam =
                                        ChatCompletionMessageFunctionToolCall.builder()
                                                .id(id)
                                                .function(
                                                        ChatCompletionMessageFunctionToolCall
                                                                .Function.builder()
                                                                .name(name)
                                                                .arguments(
                                                                        arguments != null
                                                                                ? arguments
                                                                                : "{}")
                                                                .build())
                                                .build();

                                assistantBuilder.addToolCall(toolCallParam);
                                log.debug(
                                        "Added tool call to assistant message: id={}, name={}",
                                        id,
                                        name);
                            }
                        }
                    }

                    paramsBuilder.addMessage(assistantBuilder.build());
                } else {
                    paramsBuilder.addAssistantMessage(content);
                }
                break;
            case "tool":
                String toolCallId = message.getToolCallId();
                if (toolCallId != null) {
                    // Use proper OpenAI tool message parameter
                    ChatCompletionToolMessageParam toolMessage =
                            ChatCompletionToolMessageParam.builder()
                                    .content(content)
                                    .toolCallId(toolCallId)
                                    .build();
                    paramsBuilder.addMessage(toolMessage);
                    log.debug("Added tool message with call ID: {}", toolCallId);
                }
                break;
            default:
                // Default to user message
                paramsBuilder.addUserMessage(content);
                break;
        }
    }

    private void addToolsToParams(
            ChatCompletionCreateParams.Builder paramsBuilder, List<ToolSchema> tools) {
        try {
            for (ToolSchema toolSchema : tools) {
                // Convert ToolSchema to OpenAI ChatCompletionTool
                // Create function definition first
                com.openai.models.FunctionDefinition.Builder functionBuilder =
                        com.openai.models.FunctionDefinition.builder().name(toolSchema.getName());

                if (toolSchema.getDescription() != null) {
                    functionBuilder.description(toolSchema.getDescription());
                }

                // Convert parameters map to proper format for OpenAI
                if (toolSchema.getParameters() != null) {
                    // Convert Map<String, Object> to FunctionParameters
                    com.openai.models.FunctionParameters.Builder funcParamsBuilder =
                            com.openai.models.FunctionParameters.builder();
                    for (Map.Entry<String, Object> entry : toolSchema.getParameters().entrySet()) {
                        funcParamsBuilder.putAdditionalProperty(
                                entry.getKey(), com.openai.core.JsonValue.from(entry.getValue()));
                    }
                    functionBuilder.parameters(funcParamsBuilder.build());
                }

                // Create ChatCompletionFunctionTool
                ChatCompletionFunctionTool functionTool =
                        ChatCompletionFunctionTool.builder()
                                .function(functionBuilder.build())
                                .build();

                // Create ChatCompletionTool
                ChatCompletionTool tool = ChatCompletionTool.ofFunction(functionTool);
                paramsBuilder.addTool(tool);

                log.debug("Added tool to OpenAI request: {}", toolSchema.getName());
            }

            // Set tool choice to auto to allow the model to decide when to use tools
            paramsBuilder.toolChoice(
                    ChatCompletionToolChoiceOption.ofAuto(
                            ChatCompletionToolChoiceOption.Auto.AUTO));

        } catch (Exception e) {
            log.error("Failed to add tools to OpenAI request: {}", e.getMessage(), e);
        }
    }

    private void applyGenerateOptions(
            ChatCompletionCreateParams.Builder paramsBuilder, GenerateOptions options) {
        GenerateOptions opt = options != null ? options : defaultOptions;
        if (opt.getTemperature() != null) paramsBuilder.temperature(opt.getTemperature());
        if (opt.getMaxTokens() != null)
            paramsBuilder.maxCompletionTokens(opt.getMaxTokens().longValue());
        if (opt.getTopP() != null) paramsBuilder.topP(opt.getTopP());
        if (opt.getFrequencyPenalty() != null)
            paramsBuilder.frequencyPenalty(opt.getFrequencyPenalty());
        if (opt.getPresencePenalty() != null)
            paramsBuilder.presencePenalty(opt.getPresencePenalty());
    }

    private ChatResponse parseCompletion(ChatCompletion completion, Instant startTime) {
        List<ContentBlock> contentBlocks = new ArrayList<>();
        ChatUsage usage = null;

        try {
            // Parse usage information
            if (completion.usage().isPresent()) {
                var openAIUsage = completion.usage().get();
                usage =
                        ChatUsage.builder()
                                .inputTokens((int) openAIUsage.promptTokens())
                                .outputTokens((int) openAIUsage.completionTokens())
                                .time(
                                        Duration.between(startTime, Instant.now()).toMillis()
                                                / 1000.0)
                                .build();
            }

            // Parse response content
            if (!completion.choices().isEmpty()) {
                ChatCompletion.Choice choice = completion.choices().get(0);
                ChatCompletionMessage message = choice.message();

                // Parse text content
                if (message.content() != null && message.content().isPresent()) {
                    String textContent = message.content().get();
                    if (!textContent.isEmpty()) {
                        contentBlocks.add(TextBlock.builder().text(textContent).build());
                    }
                }

                // Parse tool calls
                if (message.toolCalls().isPresent()) {
                    var toolCalls = message.toolCalls().get();
                    log.debug("Tool calls detected in non-stream response: {}", toolCalls.size());

                    for (var toolCall : toolCalls) {
                        if (toolCall.function().isPresent()) {
                            // Convert OpenAI tool call to AgentScope ToolUseBlock
                            try {
                                var functionToolCall = toolCall.function().get();
                                var function = functionToolCall.function();
                                Map<String, Object> argsMap = new HashMap<>();
                                String arguments = function.arguments();
                                if (arguments != null && !arguments.isEmpty()) {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> parsed =
                                            objectMapper.readValue(arguments, Map.class);
                                    if (parsed != null) argsMap.putAll(parsed);
                                }

                                contentBlocks.add(
                                        ToolUseBlock.builder()
                                                .id(functionToolCall.id())
                                                .name(function.name())
                                                .input(argsMap)
                                                .content(arguments)
                                                .build());

                                log.debug(
                                        "Parsed tool call: id={}, name={}",
                                        functionToolCall.id(),
                                        function.name());
                            } catch (Exception ex) {
                                log.warn(
                                        "Failed to parse tool call arguments: {}", ex.getMessage());
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.warn("Failed to parse completion: {}", e.getMessage());
            // Add a fallback text block
            contentBlocks.add(
                    TextBlock.builder().text("Error parsing response: " + e.getMessage()).build());
        }

        return ChatResponse.builder()
                .id(completion.id())
                .content(contentBlocks)
                .usage(usage)
                .build();
    }

    private ChatResponse parseChunk(ChatCompletionChunk chunk, Instant startTime) {
        List<ContentBlock> contentBlocks = new ArrayList<>();
        ChatUsage usage = null;

        try {
            // Parse usage information (usually only in the last chunk)
            if (chunk.usage().isPresent()) {
                var openAIUsage = chunk.usage().get();
                usage =
                        ChatUsage.builder()
                                .inputTokens((int) openAIUsage.promptTokens())
                                .outputTokens((int) openAIUsage.completionTokens())
                                .time(
                                        Duration.between(startTime, Instant.now()).toMillis()
                                                / 1000.0)
                                .build();
            }

            // Parse chunk content
            if (!chunk.choices().isEmpty()) {
                ChatCompletionChunk.Choice choice = chunk.choices().get(0);
                ChatCompletionChunk.Choice.Delta delta = choice.delta();

                // Parse text content
                if (delta.content() != null && delta.content().isPresent()) {
                    String textContent = delta.content().get();
                    if (!textContent.isEmpty()) {
                        contentBlocks.add(TextBlock.builder().text(textContent).build());
                    }
                }

                // Parse tool calls (in streaming, these come incrementally)
                if (delta.toolCalls().isPresent()) {
                    var toolCalls = delta.toolCalls().get();
                    log.debug("Streaming tool calls detected: {}", toolCalls.size());

                    for (var toolCall : toolCalls) {
                        if (toolCall.function().isPresent()) {
                            try {
                                var function = toolCall.function().get();
                                String toolCallId =
                                        toolCall.id()
                                                .orElse("streaming_" + System.currentTimeMillis());
                                String toolName = function.name().orElse("");
                                String arguments = function.arguments().orElse("");

                                // For streaming, we get partial tool calls that need to be
                                // accumulated
                                // Only process when we have a tool name (arguments may be partial)
                                if (!toolName.isEmpty()) {
                                    Map<String, Object> argsMap = new HashMap<>();

                                    // Try to parse arguments only if they look complete
                                    // (simple heuristic: starts with { and ends with })
                                    if (!arguments.isEmpty()
                                            && arguments.trim().startsWith("{")
                                            && arguments.trim().endsWith("}")) {
                                        try {
                                            @SuppressWarnings("unchecked")
                                            Map<String, Object> parsed =
                                                    objectMapper.readValue(arguments, Map.class);
                                            if (parsed != null) argsMap.putAll(parsed);
                                        } catch (Exception parseEx) {
                                            log.debug(
                                                    "Partial arguments in streaming (expected): {}",
                                                    arguments.length() > 50
                                                            ? arguments.substring(0, 50) + "..."
                                                            : arguments);
                                            // Don't warn for partial JSON - this is normal in
                                            // streaming
                                        }
                                    } else if (!arguments.isEmpty()) {
                                        log.debug(
                                                "Partial tool arguments received: {}",
                                                arguments.length() > 30
                                                        ? arguments.substring(0, 30) + "..."
                                                        : arguments);
                                    }

                                    // Create ToolUseBlock even with partial arguments
                                    // The ReActAgent's ToolCallAccumulator will handle accumulation
                                    ToolUseBlock toolUseBlock =
                                            ToolUseBlock.builder()
                                                    .id(toolCallId)
                                                    .name(toolName)
                                                    .input(argsMap)
                                                    .content(arguments) // Store raw arguments for
                                                    // accumulation
                                                    .build();
                                    contentBlocks.add(toolUseBlock);
                                    log.debug(
                                            "Added streaming tool call chunk: id={}, name={},"
                                                    + " args_complete={}",
                                            toolCallId,
                                            toolName,
                                            !argsMap.isEmpty());
                                }
                            } catch (Exception ex) {
                                log.warn(
                                        "Failed to parse streaming tool call: {}", ex.getMessage());
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.warn("Failed to parse chunk: {}", e.getMessage());
            return null; // Skip malformed chunks
        }

        return ChatResponse.builder().id(chunk.id()).content(contentBlocks).usage(usage).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String baseUrl;
        private String apiKey;
        private String modelName;
        private boolean streamEnabled = true;
        private GenerateOptions defaultOptions = new GenerateOptions();

        private Builder() {}

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder stream(boolean streamEnabled) {
            this.streamEnabled = streamEnabled;
            return this;
        }

        public Builder defaultOptions(GenerateOptions options) {
            this.defaultOptions = options;
            return this;
        }

        public OpenAIChatModel build() {
            return new OpenAIChatModel(baseUrl, apiKey, modelName, streamEnabled, defaultOptions);
        }
    }
}
