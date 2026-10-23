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
package io.agentscope.core.chat.completions.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.chat.completions.model.ChatMessage;
import io.agentscope.core.chat.completions.model.ToolCall;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service for converting ChatMessage DTOs to framework internal Msg objects.
 *
 * <p>This converter handles the transformation from HTTP request DTOs (with String roles) to
 * framework internal message objects (with MsgRole enum). Supported roles: user, assistant, system,
 * tool.
 *
 * <p>Extended support for:
 *
 * <ul>
 *   <li>Tool calls in assistant messages (tool_calls field)
 *   <li>Tool results in tool messages (tool_call_id field)
 * </ul>
 */
public class ChatMessageConverter {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageConverter.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Converts a list of {@link ChatMessage} DTOs to framework internal {@link Msg} objects,
     * supporting full conversation history including tool calls.
     *
     * <p>This method converts HTTP request DTOs (ChatMessage with String role) to framework
     * internal objects (Msg with MsgRole enum). Supported roles: user, assistant, system, tool.
     *
     * @param chatMessages The chat messages from the HTTP request to convert
     * @return A list of converted {@link Msg} objects; returns an empty list if input is null or
     *     empty
     * @throws IllegalArgumentException if an unsupported role is encountered in any message
     */
    public List<Msg> convertMessages(List<ChatMessage> chatMessages) {
        if (chatMessages == null || chatMessages.isEmpty()) {
            return List.of();
        }

        return chatMessages.stream()
                .filter(Objects::nonNull)
                .map(this::convertMessage)
                .collect(Collectors.toList());
    }

    /**
     * Convert a single ChatMessage to Msg.
     *
     * @param chatMsg The chat message to convert
     * @return The converted Msg object
     * @throws IllegalArgumentException if an unsupported role is encountered
     */
    private Msg convertMessage(ChatMessage chatMsg) {
        String roleStr = chatMsg.getRole();
        if (roleStr == null || roleStr.isBlank()) {
            log.warn("Message with null/empty role, defaulting to USER");
            roleStr = "user";
        }

        // Convert string role to enum
        MsgRole role = convertRole(roleStr);

        // Handle tool result messages specially
        if (role == MsgRole.TOOL) {
            return convertToolResultMessage(chatMsg);
        }

        // Handle assistant messages with tool calls
        if (role == MsgRole.ASSISTANT
                && chatMsg.getToolCalls() != null
                && !chatMsg.getToolCalls().isEmpty()) {
            return convertAssistantWithToolCalls(chatMsg);
        }

        // Regular message conversion
        String content = chatMsg.getContent();
        if (content == null) {
            log.warn("Message with null content, using empty string");
            content = "";
        }

        return Msg.builder().role(role).content(TextBlock.builder().text(content).build()).build();
    }

    /**
     * Convert an assistant message with tool calls to Msg.
     *
     * @param chatMsg The assistant message containing tool calls
     * @return The converted Msg object with ToolUseBlocks
     */
    private Msg convertAssistantWithToolCalls(ChatMessage chatMsg) {
        List<ContentBlock> contentBlocks = new ArrayList<>();

        // Add text content if present
        if (chatMsg.getContent() != null && !chatMsg.getContent().isEmpty()) {
            contentBlocks.add(TextBlock.builder().text(chatMsg.getContent()).build());
        }

        // Convert tool calls to ToolUseBlocks
        for (ToolCall toolCall : chatMsg.getToolCalls()) {
            Map<String, Object> input = parseArguments(toolCall.getFunction().getArguments());
            contentBlocks.add(
                    ToolUseBlock.builder()
                            .id(toolCall.getId())
                            .name(toolCall.getFunction().getName())
                            .input(input)
                            .build());
        }

        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(contentBlocks.toArray(new ContentBlock[0]))
                .build();
    }

    /**
     * Convert a tool result message to Msg.
     *
     * @param chatMsg The tool result message
     * @return The converted Msg object with ToolResultBlock
     */
    private Msg convertToolResultMessage(ChatMessage chatMsg) {
        String toolCallId = chatMsg.getToolCallId();
        String name = chatMsg.getName();
        String content = chatMsg.getContent() != null ? chatMsg.getContent() : "";

        // ToolResultBlock.output is List<ContentBlock>, so wrap the text in a TextBlock
        ToolResultBlock resultBlock =
                new ToolResultBlock(toolCallId, name, TextBlock.builder().text(content).build());

        return Msg.builder().role(MsgRole.TOOL).content(resultBlock).build();
    }

    /**
     * Parse JSON arguments string to Map.
     *
     * @param arguments JSON string of arguments
     * @return Parsed Map, or empty map if parsing fails
     */
    private Map<String, Object> parseArguments(String arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return Map.of();
        }

        try {
            return objectMapper.readValue(arguments, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse tool arguments: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * Convert string role to MsgRole enum.
     *
     * @param roleStr The role string (case-insensitive)
     * @return The corresponding MsgRole enum value
     * @throws IllegalArgumentException if the role is not supported
     */
    private MsgRole convertRole(String roleStr) {
        return switch (roleStr.toLowerCase()) {
            case "user" -> MsgRole.USER;
            case "assistant" -> MsgRole.ASSISTANT;
            case "system" -> MsgRole.SYSTEM;
            case "tool" -> MsgRole.TOOL;
            default -> {
                log.error(
                        "Unknown message role: '{}'. Supported roles: user, assistant, system,"
                                + " tool",
                        roleStr);
                throw new IllegalArgumentException(
                        String.format(
                                "Unknown message role: '%s'. Supported roles: user, assistant,"
                                        + " system, tool",
                                roleStr));
            }
        };
    }
}
