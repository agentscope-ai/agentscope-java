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
package io.agentscope.core.formatter.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseReasoningItem;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses OpenAI API responses to AgentScope ChatResponse.
 * Handles both ChatCompletion (non-streaming) and ChatCompletionChunk (streaming).
 */
public class OpenAIResponseParser {

    private static final Logger log = LoggerFactory.getLogger(OpenAIResponseParser.class);

    /** Placeholder name for tool call argument fragments in streaming responses. */
    protected static final String FRAGMENT_PLACEHOLDER = "__fragment__";

    private final ObjectMapper objectMapper;

    public OpenAIResponseParser() {
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Parse OpenAI response (dispatches to appropriate method based on type).
     *
     * @param response OpenAI response object (ChatCompletion, ChatCompletionChunk, or Response)
     * @param startTime Request start time for calculating duration
     * @return AgentScope ChatResponse
     */
    public ChatResponse parseResponse(Object response, Instant startTime) {
        if (response instanceof ChatCompletion completion) {
            return parseCompletionResponse(completion, startTime);
        } else if (response instanceof ChatCompletionChunk chunk) {
            return parseChunkResponse(chunk, startTime);
        } else if (response instanceof Response apiResponse) {
            return parseResponseApiResponse(apiResponse, startTime);
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
    protected ChatResponse parseCompletionResponse(ChatCompletion completion, Instant startTime) {
        List<ContentBlock> contentBlocks = new ArrayList<>();
        ChatUsage usage = null;
        String finishReason = null;

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

                if (choice.finishReason().isValid()) {
                    finishReason = choice.finishReason().asString();
                }

                // Parse thinking content FIRST (before text and tools)
                // Order matters: ThinkingBlock must come before TextBlock
                String thinkingContent = extractThinking(message);
                if (thinkingContent != null && !thinkingContent.isEmpty()) {
                    contentBlocks.add(ThinkingBlock.builder().thinking(thinkingContent).build());
                    log.debug("Parsed thinking content: {} chars", thinkingContent.length());
                }

                // Parse text content
                if (message.content() != null && message.content().isPresent()) {
                    String textContent = message.content().get();
                    if (textContent != null && !textContent.isEmpty()) {
                        contentBlocks.add(TextBlock.builder().text(textContent).build());
                    }
                }

                // Parse tool calls
                if (message.toolCalls() != null && message.toolCalls().isPresent()) {
                    var toolCalls = message.toolCalls().get();
                    log.debug("Tool calls detected in non-stream response: {}", toolCalls.size());

                    for (var toolCall : toolCalls) {
                        if (toolCall.function().isPresent()) {
                            try {
                                var functionToolCall = toolCall.function().get();
                                var function = functionToolCall.function();
                                String arguments = function.arguments();

                                log.debug(
                                        "Non-stream tool call: id={}, name={}, arguments={}",
                                        functionToolCall.id(),
                                        function.name(),
                                        arguments);

                                Map<String, Object> argsMap = new HashMap<>();
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
            log.error("Failed to parse OpenAI completion response: {}", e.getMessage(), e);
            // Return fallback response with error message
            contentBlocks.add(
                    TextBlock.builder().text("Error parsing response: " + e.getMessage()).build());
        }

        return ChatResponse.builder()
                .id(completion.id())
                .content(contentBlocks)
                .usage(usage)
                .finishReason(finishReason)
                .build();
    }

    /**
     * Parse OpenAI streaming response chunk.
     *
     * @param chunk ChatCompletionChunk from OpenAI
     * @param startTime Request start time
     * @return AgentScope ChatResponse (or null for malformed chunks)
     */
    protected ChatResponse parseChunkResponse(ChatCompletionChunk chunk, Instant startTime) {
        List<ContentBlock> contentBlocks = new ArrayList<>();
        ChatUsage usage = null;
        String finishReason = null;

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
                if (choice.finishReason().isPresent()) {
                    finishReason = choice.finishReason().get().asString();
                }

                // Parse text content
                if (delta.content() != null && delta.content().isPresent()) {
                    String textContent = delta.content().get();
                    if (textContent != null && !textContent.isEmpty()) {
                        contentBlocks.add(TextBlock.builder().text(textContent).build());
                    }
                }

                // Parse tool calls (in streaming, these come incrementally)
                if (delta.toolCalls() != null && delta.toolCalls().isPresent()) {
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

                                log.debug(
                                        "Streaming tool call chunk: id={}, name={}, arguments={}",
                                        toolCallId,
                                        toolName,
                                        arguments);

                                // For streaming, we get partial tool calls that need to be
                                // accumulated
                                if (!toolName.isEmpty()) {
                                    // First chunk with complete metadata (has tool name)
                                    Map<String, Object> argsMap = new HashMap<>();

                                    // Try to parse arguments only if they look complete
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
                                        }
                                    }

                                    contentBlocks.add(
                                            ToolUseBlock.builder()
                                                    .id(toolCallId)
                                                    .name(toolName)
                                                    .input(argsMap)
                                                    .content(
                                                            arguments) // Store raw for accumulation
                                                    .build());
                                    log.debug(
                                            "Added streaming tool call chunk: id={}, name={}",
                                            toolCallId,
                                            toolName);
                                } else if (!arguments.isEmpty()) {
                                    // Subsequent chunks with only argument fragments
                                    contentBlocks.add(
                                            ToolUseBlock.builder()
                                                    .id("") // Empty ID
                                                    .name(FRAGMENT_PLACEHOLDER)
                                                    .input(new HashMap<>())
                                                    .content(arguments)
                                                    .build());
                                    log.debug(
                                            "Added argument fragment: {}",
                                            arguments.substring(
                                                    0, Math.min(30, arguments.length())));
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
            log.error("Failed to parse OpenAI chunk response: {}", e.getMessage(), e);
            // For streaming, return null to skip malformed chunks
            return null;
        }

        return ChatResponse.builder()
                .id(chunk.id())
                .content(contentBlocks)
                .usage(usage)
                .finishReason(finishReason)
                .build();
    }

    /**
     * Check if a string is a default Object.toString() representation.
     * Default format is: ClassName@hex
     *
     * @param str the string to check
     * @return true if it matches the default Object.toString() pattern
     */
    private static boolean isDefaultObjectToString(String str) {
        // Pattern: ClassName@HexValue
        // More robust check: contains @ and everything after @ looks like hex
        if (str == null || str.isEmpty()) {
            return false;
        }
        int atIndex = str.lastIndexOf('@');
        if (atIndex > 0 && atIndex < str.length() - 1) {
            String afterAt = str.substring(atIndex + 1);
            // Check if the part after @ is all hex digits (valid hash code)
            try {
                Integer.parseUnsignedInt(afterAt, 16);
                return true; // Looks like a default toString()
            } catch (NumberFormatException e) {
                return false; // Actual content after @
            }
        }
        return false; // No @ sign, not a default toString()
    }

    /**
     * Extract thinking content from ChatCompletionMessage using reflection.
     * The OpenAI SDK may not yet expose the thinking field in the public API,
     * so we use reflection to access it if available.
     *
     * @param message ChatCompletionMessage from OpenAI API
     * @return Thinking content string, or null if not present
     */
    protected String extractThinking(ChatCompletionMessage message) {
        try {
            // First, try to use a public getter if it exists
            try {
                Field thinkingField = ChatCompletionMessage.class.getDeclaredField("thinking");
                thinkingField.setAccessible(true);
                Object thinkingValue = thinkingField.get(message);

                if (thinkingValue != null) {
                    // If it's a String, return directly
                    if (thinkingValue instanceof String) {
                        String strValue = (String) thinkingValue;
                        if (!strValue.isEmpty()) {
                            return strValue;
                        }
                    } else {
                        // For other types (Optional, wrapper objects, etc.), try toString
                        // and verify it's not a default Object.toString()
                        String strValue = thinkingValue.toString();
                        if (strValue != null
                                && !strValue.isEmpty()
                                && !isDefaultObjectToString(strValue)) {
                            return strValue;
                        }
                    }
                }
            } catch (NoSuchFieldException e) {
                // Thinking field may not exist in this SDK version
                log.trace(
                        "Thinking field not found in ChatCompletionMessage (SDK may not support it"
                                + " yet)");
            } catch (IllegalAccessException | IllegalArgumentException e) {
                // Field access issues - SDK version compatibility or security restrictions
                log.debug("Unable to access thinking field via reflection: {}", e.getMessage());
            }

            // If reflection didn't work, return null (thinking not supported or not present)
            return null;
        } catch (Exception e) {
            log.debug("Exception while extracting thinking content: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Parse OpenAI Responses API response.
     *
     * The Responses API is designed for reasoning models (o1, o3, gpt-5) and provides
     * native support for reasoning via the reasoning parameter.
     *
     * @param response Response from OpenAI Responses API
     * @param startTime Request start time
     * @return AgentScope ChatResponse
     */
    protected ChatResponse parseResponseApiResponse(Response response, Instant startTime) {
        List<ContentBlock> contentBlocks = new ArrayList<>();
        ChatUsage usage = null;
        String finishReason = "stop";

        try {
            // Parse usage information
            if (response.usage() != null && response.usage().isPresent()) {
                var openAIUsage = response.usage().get();
                long inputTokens = openAIUsage.inputTokens();
                long outputTokens = openAIUsage.outputTokens();
                long reasoningTokens = 0;

                // Extract reasoning tokens if available
                if (openAIUsage.outputTokensDetails() != null) {
                    var details = openAIUsage.outputTokensDetails();
                    reasoningTokens = details.reasoningTokens();
                }

                usage =
                        ChatUsage.builder()
                                .inputTokens((int) inputTokens)
                                .outputTokens((int) outputTokens)
                                .time(
                                        Duration.between(startTime, Instant.now()).toMillis()
                                                / 1000.0)
                                .build();

                if (reasoningTokens > 0) {
                    log.debug("Reasoning tokens used: {}", reasoningTokens);
                }
            }

            // Parse response output
            if (response.output() != null && !response.output().isEmpty()) {
                for (ResponseOutputItem outputItem : response.output()) {
                    // Extract reasoning content
                    if (outputItem.isReasoning() && outputItem.reasoning().isPresent()) {
                        ResponseReasoningItem reasoning = outputItem.reasoning().get();

                        // Extract reasoning content (the main thinking process)
                        if (reasoning.content().isPresent()) {
                            var contents = reasoning.content().get();
                            StringBuilder thinkingContent = new StringBuilder();
                            for (var content : contents) {
                                if (content.text() != null && !content.text().isEmpty()) {
                                    if (thinkingContent.length() > 0) {
                                        thinkingContent.append("\n");
                                    }
                                    thinkingContent.append(content.text());
                                }
                            }
                            if (thinkingContent.length() > 0) {
                                contentBlocks.add(
                                        ThinkingBlock.builder()
                                                .thinking(thinkingContent.toString())
                                                .build());
                                log.debug(
                                        "Extracted reasoning content: {} chars",
                                        thinkingContent.length());
                            }
                        }
                    }

                    // Extract message content
                    if (outputItem.isMessage() && outputItem.message().isPresent()) {
                        var message = outputItem.message().get();
                        if (message.content() != null && !message.content().isEmpty()) {
                            for (var contentItem : message.content()) {
                                // Extract text from content item (could be OutputText or Refusal)
                                if (contentItem.isOutputText()) {
                                    String text = contentItem.asOutputText().text();
                                    if (text != null && !text.isEmpty()) {
                                        contentBlocks.add(TextBlock.builder().text(text).build());
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // If no content blocks were added, log warning
            if (contentBlocks.isEmpty()) {
                log.debug("No output content extracted from Responses API response");
            }

        } catch (Exception e) {
            log.error("Failed to parse Responses API response: {}", e.getMessage(), e);
            // Return fallback response with error message
            contentBlocks.add(
                    TextBlock.builder().text("Error parsing response: " + e.getMessage()).build());
        }

        return ChatResponse.builder()
                .id(response.id() != null ? response.id() : "")
                .content(contentBlocks)
                .usage(usage)
                .finishReason(finishReason)
                .build();
    }
}
