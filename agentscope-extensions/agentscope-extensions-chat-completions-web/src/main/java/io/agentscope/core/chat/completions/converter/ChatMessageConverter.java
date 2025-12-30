/*
 * Copyright 2024-2025 the original author or authors.
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

import io.agentscope.core.chat.completions.model.ChatMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service for converting ChatMessage DTOs to framework internal Msg objects.
 *
 * <p>This converter handles the transformation from HTTP request DTOs (with String roles) to
 * framework internal message objects (with MsgRole enum). Supported roles: user, assistant,
 * system, tool.
 */
public class ChatMessageConverter {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageConverter.class);

    /**
     * Convert ChatMessage list to Msg list, supporting full conversation history.
     *
     * <p>This method converts HTTP request DTOs (ChatMessage with String role) to framework
     * internal objects (Msg with MsgRole enum). Supported roles: user, assistant, system, tool.
     *
     * @param chatMessages The chat messages from the request
     * @return List of Msg objects
     * @throws IllegalArgumentException if an unsupported role is encountered
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

        // Convert string role to enum, throwing exception for unknown roles
        // This prevents silent data corruption (e.g., new enum values being converted to USER)
        MsgRole role = convertRole(roleStr);

        String content = chatMsg.getContent();
        if (content == null) {
            log.warn("Message with null content, using empty string");
            content = "";
        }

        return Msg.builder().role(role).content(TextBlock.builder().text(content).build()).build();
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
