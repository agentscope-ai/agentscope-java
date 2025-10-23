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
package io.agentscope.core.formatter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Formatter for OpenAI Chat Completion API.
 * Converts between AgentScope Msg objects and OpenAI SDK types.
 *
 * <p>Note: OpenAI has two response types (ChatCompletion for non-streaming and ChatCompletionChunk
 * for streaming), so this formatter provides specific methods for each type.
 */
public class OpenAIChatFormatter
        implements Formatter<
                ChatCompletionMessageParam, Object, ChatCompletionCreateParams.Builder> {

    private static final Logger log = LoggerFactory.getLogger(OpenAIChatFormatter.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public List<ChatCompletionMessageParam> format(List<Msg> msgs) {
        List<ChatCompletionMessageParam> result = new ArrayList<>();
        for (Msg msg : msgs) {
            ChatCompletionMessageParam param = convertToParam(msg);
            if (param != null) {
                result.add(param);
            }
        }
        return result;
    }

    @Override
    public ChatResponse parseResponse(Object response, Instant startTime) {
        // Dispatch to the appropriate parsing method based on actual type
        if (response instanceof ChatCompletion completion) {
            return parseCompletionResponse(completion, startTime);
        } else if (response instanceof ChatCompletionChunk chunk) {
            return parseChunkResponse(chunk, startTime);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported response type: " + response.getClass().getName());
        }
    }

    /**
     * Parse OpenAI non-streaming response.
     *
     * @param completion ChatCompletion from OpenAI
     * @param startTime Request start time
     * @return AgentScope ChatResponse
     */
    public ChatResponse parseCompletionResponse(ChatCompletion completion, Instant startTime) {
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

    /**
     * Parse OpenAI streaming response chunk.
     *
     * @param chunk ChatCompletionChunk from OpenAI
     * @param startTime Request start time
     * @return AgentScope ChatResponse
     */
    public ChatResponse parseChunkResponse(ChatCompletionChunk chunk, Instant startTime) {
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

    private ChatCompletionMessageParam convertToParam(Msg msg) {
        return switch (msg.getRole()) {
            case SYSTEM -> ChatCompletionMessageParam.ofSystem(convertSystemMessage(msg));
            case USER -> ChatCompletionMessageParam.ofUser(convertUserMessage(msg));
            case ASSISTANT -> ChatCompletionMessageParam.ofAssistant(convertAssistantMessage(msg));
            case TOOL -> ChatCompletionMessageParam.ofTool(convertToolMessage(msg));
        };
    }

    private ChatCompletionSystemMessageParam convertSystemMessage(Msg msg) {
        return ChatCompletionSystemMessageParam.builder().content(extractTextContent(msg)).build();
    }

    private ChatCompletionUserMessageParam convertUserMessage(Msg msg) {
        ChatCompletionUserMessageParam.Builder builder =
                ChatCompletionUserMessageParam.builder().content(extractTextContent(msg));

        if (msg.getName() != null) {
            builder.name(msg.getName());
        }

        return builder.build();
    }

    private ChatCompletionAssistantMessageParam convertAssistantMessage(Msg msg) {
        ChatCompletionAssistantMessageParam.Builder builder =
                ChatCompletionAssistantMessageParam.builder();

        String textContent = extractTextContent(msg);
        if (!textContent.isEmpty()) {
            builder.content(textContent);
        }

        if (msg.getName() != null) {
            builder.name(msg.getName());
        }

        // Handle tool calls
        List<ToolUseBlock> toolBlocks = msg.getContentBlocks(ToolUseBlock.class);
        if (!toolBlocks.isEmpty()) {
            for (ToolUseBlock toolUse : toolBlocks) {
                String argsJson;
                try {
                    argsJson = objectMapper.writeValueAsString(toolUse.getInput());
                } catch (Exception e) {
                    log.warn("Failed to serialize tool call arguments: {}", e.getMessage());
                    argsJson = "{}";
                }

                var toolCallParam =
                        ChatCompletionMessageFunctionToolCall.builder()
                                .id(toolUse.getId())
                                .function(
                                        ChatCompletionMessageFunctionToolCall.Function.builder()
                                                .name(toolUse.getName())
                                                .arguments(argsJson)
                                                .build())
                                .build();

                builder.addToolCall(toolCallParam);
                log.debug(
                        "Formatted assistant tool call: id={}, name={}",
                        toolUse.getId(),
                        toolUse.getName());
            }
        }

        return builder.build();
    }

    private ChatCompletionToolMessageParam convertToolMessage(Msg msg) {
        ToolResultBlock result = msg.getFirstContentBlock(ToolResultBlock.class);
        String toolCallId =
                result != null ? result.getId() : "unknown_" + System.currentTimeMillis();
        String content = extractTextContent(msg);

        return ChatCompletionToolMessageParam.builder()
                .content(content)
                .toolCallId(toolCallId)
                .build();
    }

    private String extractTextContent(Msg msg) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : msg.getContent()) {
            if (block instanceof TextBlock tb) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(tb.getText());
            } else if (block instanceof ThinkingBlock tb) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(tb.getThinking());
            } else if (block instanceof ToolResultBlock toolResult) {
                // Extract text from tool result output
                ContentBlock output = toolResult.getOutput();
                if (output instanceof TextBlock textBlock) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(textBlock.getText());
                }
            }
        }
        return sb.toString();
    }

    @Override
    public void applyOptions(
            ChatCompletionCreateParams.Builder paramsBuilder,
            GenerateOptions options,
            GenerateOptions defaultOptions) {
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

    @Override
    public FormatterCapabilities getCapabilities() {
        return FormatterCapabilities.builder()
                .providerName("OpenAI")
                .supportToolsApi(true)
                .supportMultiAgent(false)
                .supportVision(true)
                .supportedBlocks(
                        Set.of(
                                TextBlock.class,
                                ToolUseBlock.class,
                                ToolResultBlock.class,
                                ThinkingBlock.class))
                .build();
    }
}
