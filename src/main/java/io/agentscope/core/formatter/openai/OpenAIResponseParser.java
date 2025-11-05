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
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
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
     * @param response OpenAI response object (ChatCompletion or ChatCompletionChunk)
     * @param startTime Request start time for calculating duration
     * @return AgentScope ChatResponse
     */
    public ChatResponse parseResponse(Object response, Instant startTime) {
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
    protected ChatResponse parseCompletionResponse(ChatCompletion completion, Instant startTime) {
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

        return ChatResponse.builder().id(chunk.id()).content(contentBlocks).usage(usage).build();
    }
}
