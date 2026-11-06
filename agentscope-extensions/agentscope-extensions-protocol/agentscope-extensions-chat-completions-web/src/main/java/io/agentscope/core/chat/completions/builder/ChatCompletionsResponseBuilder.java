/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.chat.completions.builder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.chat.completions.model.ChatChoice;
import io.agentscope.core.chat.completions.model.ChatCompletionsRequest;
import io.agentscope.core.chat.completions.model.ChatCompletionsResponse;
import io.agentscope.core.chat.completions.model.ChatMessage;
import io.agentscope.core.chat.completions.model.ToolCall;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for building Chat Completions API responses.
 *
 * <p>This builder handles the construction of response objects that are 100% compatible with
 * OpenAI's Chat Completions API format.
 *
 * <p><b>Response Format:</b>
 *
 * <pre>{@code
 * {
 *   "id": "chatcmpl-xxx",
 *   "object": "chat.completion",
 *   "created": 1234567890,
 *   "model": "gpt-4",
 *   "choices": [{
 *     "index": 0,
 *     "message": {"role": "assistant", "content": "Hello!"},
 *     "finish_reason": "stop"
 *   }],
 *   "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
 * }
 * }</pre>
 *
 * <p><b>Tool Calls:</b> When the agent uses tools, the response includes tool_calls:
 *
 * <pre>{@code
 * {
 *   "message": {
 *     "role": "assistant",
 *     "content": null,
 *     "tool_calls": [{
 *       "id": "call_abc",
 *       "type": "function",
 *       "function": {"name": "get_weather", "arguments": "{\"city\":\"Hangzhou\"}"}
 *     }]
 *   },
 *   "finish_reason": "tool_calls"
 * }
 * }</pre>
 */
public class ChatCompletionsResponseBuilder {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Build a successful chat completion response.
     *
     * @param request The original request
     * @param reply The agent's reply message
     * @param requestId The request ID for tracking
     * @return A {@link ChatCompletionsResponse} containing the agent's reply
     */
    public ChatCompletionsResponse buildResponse(
            ChatCompletionsRequest request, Msg reply, String requestId) {
        ChatCompletionsResponse response = new ChatCompletionsResponse();
        response.setId(requestId);
        response.setObject("chat.completion");
        response.setCreated(Instant.now().getEpochSecond());
        response.setModel(request.getModel());

        ChatChoice choice = new ChatChoice();
        choice.setIndex(0);

        // Convert Msg to ChatMessage, handling tool calls
        ChatMessage message = convertMsgToChatMessage(reply);
        choice.setMessage(message);

        choice.setFinishReason(finishReasonOf(reply));

        response.setChoices(List.of(choice));
        response.setUsage(usageOf(reply));

        return response;
    }

    /**
     * Map the agent's final reply to an OpenAI {@code finish_reason}. Shared by the non-streaming
     * response and the final streaming chunk.
     *
     * @param reply The agent's final reply, may be null
     * @return {@code "tool_calls"} when the agent suspended for tools or the reply contains tool
     *     calls, {@code "length"} when it hit the iteration limit, otherwise {@code "stop"}
     */
    public static String finishReasonOf(Msg reply) {
        GenerateReason generateReason = reply != null ? reply.getGenerateReason() : null;
        boolean hasToolCalls =
                reply != null
                        && reply.getRole() == MsgRole.ASSISTANT
                        && reply.hasContentBlocks(ToolUseBlock.class);
        if (generateReason == GenerateReason.TOOL_SUSPENDED || hasToolCalls) {
            return "tool_calls";
        }
        if (generateReason == GenerateReason.MAX_ITERATIONS) {
            return "length";
        }
        return "stop";
    }

    /**
     * Map the invocation's total usage to the OpenAI {@code usage} object. Shared by the
     * non-streaming response and the streaming usage chunk.
     *
     * <p>Uses {@link Msg#getInvocationUsage()} rather than {@link Msg#getChatUsage()}: the latter
     * covers only the last model call, so it would under-report any request in which the agent
     * called a tool. Returns {@code null} (field omitted) when no usage was reported, instead of
     * reporting zeros.
     *
     * @param reply The agent's final reply, may be null
     * @return The usage of the whole request, or null when none was reported
     */
    public static ChatCompletionsResponse.Usage usageOf(Msg reply) {
        ChatUsage usage = reply != null ? reply.getInvocationUsage() : null;
        if (usage == null) {
            return null;
        }
        return new ChatCompletionsResponse.Usage(
                usage.getInputTokens(), usage.getOutputTokens(), usage.getTotalTokens());
    }

    /**
     * Build an error response.
     *
     * @param request The original request
     * @param error The error that occurred
     * @param requestId The request ID for tracking
     * @return A {@link ChatCompletionsResponse} containing the error message with finish reason
     *     "error"
     */
    public ChatCompletionsResponse buildErrorResponse(
            ChatCompletionsRequest request, Throwable error, String requestId) {
        ChatCompletionsResponse response = new ChatCompletionsResponse();
        response.setId(requestId);
        response.setObject("chat.completion");
        response.setCreated(Instant.now().getEpochSecond());
        response.setModel(request.getModel());

        ChatChoice choice = new ChatChoice();
        choice.setIndex(0);
        String errorMessage = error != null ? error.getMessage() : "Unknown error occurred";
        ChatMessage message = new ChatMessage("assistant", "Error: " + errorMessage);
        choice.setMessage(message);
        choice.setFinishReason("error");

        response.setChoices(List.of(choice));

        return response;
    }

    /**
     * Convert an internal Msg to an external ChatMessage.
     *
     * <p>Handles text content and tool calls for OpenAI compatibility.
     *
     * @param msg The internal message to convert
     * @return The OpenAI-compatible ChatMessage
     */
    public ChatMessage convertMsgToChatMessage(Msg msg) {
        if (msg == null) {
            return new ChatMessage("assistant", "");
        }

        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setRole(
                msg.getRole() != null ? msg.getRole().name().toLowerCase() : "assistant");

        List<ContentBlock> contentBlocks = msg.getContent();
        if (contentBlocks != null && !contentBlocks.isEmpty()) {
            // Extract text content - always set (may be empty string)
            String textContent =
                    contentBlocks.stream()
                            .filter(TextBlock.class::isInstance)
                            .map(TextBlock.class::cast)
                            .map(TextBlock::getText)
                            .collect(Collectors.joining());
            // Set content even if empty to match OpenAI's format
            chatMessage.setContent(textContent);

            // Extract tool calls for assistant role
            if (msg.getRole() == MsgRole.ASSISTANT) {
                List<ToolCall> toolCalls =
                        contentBlocks.stream()
                                .filter(ToolUseBlock.class::isInstance)
                                .map(ToolUseBlock.class::cast)
                                .map(this::convertToolUseBlockToToolCall)
                                .collect(Collectors.toList());
                if (!toolCalls.isEmpty()) {
                    chatMessage.setToolCalls(toolCalls);
                }
            }
        } else {
            // No content blocks - set empty content
            chatMessage.setContent("");
        }

        return chatMessage;
    }

    /**
     * Extract text content from a Msg safely.
     *
     * @param msg The message to extract text from
     * @return The text content as a {@link String}, or an empty string if the message is null or
     *     contains no text
     */
    public String extractTextContent(Msg msg) {
        if (msg == null) {
            return "";
        }
        String text = msg.getTextContent();
        return text != null ? text : "";
    }

    /**
     * Convert a ToolUseBlock to a ToolCall, serializing the input Map to JSON string.
     *
     * <p>Prioritizes the content field (raw JSON string) over the input Map, as some providers
     * like DashScope store arguments in the content field.
     *
     * @param block The ToolUseBlock to convert
     * @return The OpenAI-compatible ToolCall
     */
    private ToolCall convertToolUseBlockToToolCall(ToolUseBlock block) {
        // Prioritize content field (raw JSON string) over input map
        // DashScope and some providers store arguments in content field
        String argumentsJson;
        String content = block.getContent();
        Map<String, Object> input = block.getInput();

        if (content != null && !content.isEmpty()) {
            argumentsJson = content;
        } else if (input != null && !input.isEmpty()) {
            // Only serialize input if it's not empty
            argumentsJson = serializeMapToJson(input);
        } else {
            // Both content and input are empty - use empty object for non-streaming
            argumentsJson = "{}";
        }

        return new ToolCall(block.getId(), block.getName(), argumentsJson);
    }

    /**
     * Serialize a Map to a JSON string.
     *
     * @param map The map to serialize
     * @return JSON string representation, or "{}" if serialization fails
     */
    private String serializeMapToJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
