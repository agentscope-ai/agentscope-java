/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
package io.agentscope.core.agui.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a message in the AG-UI protocol.
 *
 * <p>Messages are the primary communication unit in the AG-UI protocol.
 * They contain content, role information, and optionally tool calls or tool call IDs.
 *
 * <p>The {@code content} field supports two formats:
 * <ul>
 *   <li>Plain text string - for simple text messages (backward compatible)</li>
 *   <li>Array of content parts - for multimodal messages containing text, images, etc.</li>
 * </ul>
 *
 * <p>Message roles:
 * <ul>
 *   <li>user - Messages from the user</li>
 *   <li>assistant - Messages from the AI assistant</li>
 *   <li>system - System instructions</li>
 *   <li>tool - Tool execution results</li>
 * </ul>
 */
@JsonDeserialize(using = AguiMessageDeserializer.class)
public class AguiMessage {

    private final String id;
    private final String role;
    private final String content;
    private final List<AguiContentPart> contentParts;
    private final List<AguiToolCall> toolCalls;
    private final String toolCallId;

    /**
     * Creates a new AguiMessage with full control over all fields.
     *
     * <p>At most one of {@code content} and {@code contentParts} should be non-null.
     *
     * @param id The unique message ID
     * @param role The message role (user, assistant, system, tool)
     * @param content The plain text content (for simple messages)
     * @param contentParts The multimodal content parts (for multimodal messages)
     * @param toolCalls Tool calls for assistant messages (optional)
     * @param toolCallId Tool call ID for tool messages (optional)
     */
    public AguiMessage(
            String id,
            String role,
            String content,
            List<AguiContentPart> contentParts,
            List<AguiToolCall> toolCalls,
            String toolCallId) {
        this.id = Objects.requireNonNull(id, "id cannot be null");
        this.role = Objects.requireNonNull(role, "role cannot be null");
        this.content = content;
        this.contentParts =
                contentParts != null ? Collections.unmodifiableList(contentParts) : null;
        this.toolCalls =
                toolCalls != null
                        ? Collections.unmodifiableList(toolCalls)
                        : Collections.emptyList();
        this.toolCallId = toolCallId;
    }

    /**
     * Jackson-friendly creator constructor used by both Jackson 2.x and Jackson 3.x.
     *
     * <p>The core Jackson annotations ({@link JsonCreator}, {@link JsonProperty}) share
     * the same package ({@code com.fasterxml.jackson.annotation}) across Jackson 2 and
     * Jackson 3, so this constructor is discovered by both versions and serves as a
     * portable entry point when the Jackson 2-only {@link JsonDeserialize} on this class
     * is ignored by Jackson 3 (e.g. Spring Framework 7 / Spring Boot 4 use Jackson 3
     * under the {@code tools.jackson.*} package).
     *
     * <p>The {@code content} field is a union type in the AG-UI protocol
     * ({@code string | InputContent[]}). This constructor accepts it as {@link Object}
     * and dispatches by runtime type:
     * <ul>
     *   <li>{@link String} - stored as plain text content</li>
     *   <li>{@link List} - converted to {@link AguiContentPart} entries by inspecting
     *       the {@code type} discriminator on each element map</li>
     * </ul>
     */
    @JsonCreator
    @SuppressWarnings("unchecked")
    static AguiMessage fromJson(
            @JsonProperty("id") String id,
            @JsonProperty("role") String role,
            @JsonProperty("content") Object content,
            @JsonProperty("toolCalls") List<AguiToolCall> toolCalls,
            @JsonProperty("toolCallId") String toolCallId) {
        String textContent = null;
        List<AguiContentPart> parts = null;

        if (content instanceof String text) {
            textContent = text;
        } else if (content instanceof List<?> list) {
            parts = new ArrayList<>(list.size());
            for (Object element : list) {
                if (element instanceof AguiContentPart part) {
                    parts.add(part);
                } else if (element instanceof Map<?, ?> map) {
                    parts.add(toContentPart((Map<String, Object>) map));
                } else if (element != null) {
                    throw new IllegalArgumentException(
                            "Unsupported content part element type: "
                                    + element.getClass().getName());
                }
            }
        } else if (content != null) {
            throw new IllegalArgumentException(
                    "Unsupported content type: " + content.getClass().getName());
        }

        return new AguiMessage(id, role, textContent, parts, toolCalls, toolCallId);
    }

    @SuppressWarnings("unchecked")
    private static AguiContentPart toContentPart(Map<String, Object> map) {
        Object type = map.get("type");
        if (type == null) {
            throw new IllegalArgumentException(
                    "Content part is missing required 'type' discriminator: " + map);
        }
        String typeStr = type.toString();
        return switch (typeStr) {
            case "text" -> new AguiTextContent(asString(map.get("text")));
            case "image" ->
                    new AguiImageContent(
                            toContentSource((Map<String, Object>) map.get("source")),
                            (Map<String, Object>) map.get("metadata"));
            default ->
                    throw new IllegalArgumentException("Unsupported content part type: " + typeStr);
        };
    }

    private static AguiContentSource toContentSource(Map<String, Object> map) {
        if (map == null) {
            throw new IllegalArgumentException("Image content is missing required 'source' field");
        }
        Object type = map.get("type");
        if (type == null) {
            throw new IllegalArgumentException(
                    "Content source is missing required 'type' discriminator: " + map);
        }
        String typeStr = type.toString();
        return switch (typeStr) {
            case "url" ->
                    new AguiUrlSource(asString(map.get("value")), asString(map.get("mimeType")));
            case "data" ->
                    new AguiDataSource(asString(map.get("value")), asString(map.get("mimeType")));
            default ->
                    throw new IllegalArgumentException(
                            "Unsupported content source type: " + typeStr);
        };
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    /**
     * Creates a new AguiMessage with plain text content (backward compatible constructor).
     *
     * @param id The unique message ID
     * @param role The message role (user, assistant, system, tool)
     * @param content The message content
     * @param toolCalls Tool calls for assistant messages (optional)
     * @param toolCallId Tool call ID for tool messages (optional)
     */
    public AguiMessage(
            String id,
            String role,
            String content,
            List<AguiToolCall> toolCalls,
            String toolCallId) {
        this(id, role, content, null, toolCalls, toolCallId);
    }

    /**
     * Creates a simple user message.
     *
     * @param id The message ID
     * @param content The message content
     * @return A new user message
     */
    public static AguiMessage userMessage(String id, String content) {
        return new AguiMessage(id, "user", content, null, null);
    }

    /**
     * Creates a simple assistant message.
     *
     * @param id The message ID
     * @param content The message content
     * @return A new assistant message
     */
    public static AguiMessage assistantMessage(String id, String content) {
        return new AguiMessage(id, "assistant", content, null, null);
    }

    /**
     * Creates a system message.
     *
     * @param id The message ID
     * @param content The message content
     * @return A new system message
     */
    public static AguiMessage systemMessage(String id, String content) {
        return new AguiMessage(id, "system", content, null, null);
    }

    /**
     * Creates a tool result message.
     *
     * @param id The message ID
     * @param toolCallId The ID of the tool call this is responding to
     * @param content The tool result content
     * @return A new tool message
     */
    public static AguiMessage toolMessage(String id, String toolCallId, String content) {
        return new AguiMessage(id, "tool", content, null, toolCallId);
    }

    /**
     * Creates a multimodal message with content parts.
     *
     * @param id The message ID
     * @param role The message role
     * @param contentParts The multimodal content parts
     * @return A new multimodal message
     */
    public static AguiMessage multimodalMessage(
            String id, String role, List<AguiContentPart> contentParts) {
        return new AguiMessage(id, role, null, contentParts, null, null);
    }

    /**
     * Get the message ID.
     *
     * @return The message ID
     */
    @JsonProperty("id")
    public String getId() {
        return id;
    }

    /**
     * Get the message role.
     *
     * @return The role (user, assistant, system, tool)
     */
    @JsonProperty("role")
    public String getRole() {
        return role;
    }

    /**
     * Get the plain text message content.
     *
     * @return The content string, or null if this is a multimodal message
     */
    @JsonProperty("content")
    public String getContent() {
        return content;
    }

    /**
     * Get the multimodal content parts.
     *
     * @return The content parts list, or null if this is a plain text message
     */
    @JsonIgnore
    public List<AguiContentPart> getContentParts() {
        return contentParts;
    }

    /**
     * Check if this message contains multimodal content parts.
     *
     * @return true if content parts are present
     */
    @JsonIgnore
    public boolean isMultimodal() {
        return contentParts != null && !contentParts.isEmpty();
    }

    /**
     * Get the tool calls (for assistant messages).
     *
     * @return The tool calls as an immutable list, empty if none
     */
    @JsonProperty("toolCalls")
    public List<AguiToolCall> getToolCalls() {
        return toolCalls;
    }

    /**
     * Get the tool call ID (for tool messages).
     *
     * @return The tool call ID, or null if not a tool message
     */
    @JsonProperty("toolCallId")
    public String getToolCallId() {
        return toolCallId;
    }

    /**
     * Check if this is a user message.
     *
     * @return true if role is "user"
     */
    public boolean isUserMessage() {
        return "user".equals(role);
    }

    /**
     * Check if this is an assistant message.
     *
     * @return true if role is "assistant"
     */
    public boolean isAssistantMessage() {
        return "assistant".equals(role);
    }

    /**
     * Check if this is a system message.
     *
     * @return true if role is "system"
     */
    public boolean isSystemMessage() {
        return "system".equals(role);
    }

    /**
     * Check if this is a tool message.
     *
     * @return true if role is "tool"
     */
    public boolean isToolMessage() {
        return "tool".equals(role);
    }

    /**
     * Check if this message has tool calls.
     *
     * @return true if tool calls are present
     */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    @Override
    public String toString() {
        return "AguiMessage{id='"
                + id
                + "', role='"
                + role
                + "', content='"
                + content
                + "', contentParts="
                + contentParts
                + ", toolCalls="
                + toolCalls
                + ", toolCallId='"
                + toolCallId
                + "'}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AguiMessage that = (AguiMessage) o;
        return Objects.equals(id, that.id)
                && Objects.equals(role, that.role)
                && Objects.equals(content, that.content)
                && Objects.equals(contentParts, that.contentParts)
                && Objects.equals(toolCalls, that.toolCalls)
                && Objects.equals(toolCallId, that.toolCallId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, role, content, contentParts, toolCalls, toolCallId);
    }
}
