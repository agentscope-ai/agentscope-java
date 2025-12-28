package io.agentscope.spring.boot.chat.converter;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.spring.boot.chat.api.ChatMessage;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Service for converting ChatMessage DTOs to framework internal Msg objects.
 *
 * <p>This converter handles the transformation from HTTP request DTOs (with String roles) to
 * framework internal message objects (with MsgRole enum). Supported roles: user, assistant,
 * system, tool.
 *
 * <p>This component is automatically discovered by Spring Boot's component scanning.
 */
@Component
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
