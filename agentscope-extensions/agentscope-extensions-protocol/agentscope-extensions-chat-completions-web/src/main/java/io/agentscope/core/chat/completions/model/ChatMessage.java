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
package io.agentscope.core.chat.completions.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Chat message representation for the Chat Completions API.
 *
 * <p>This DTO is compatible with OpenAI's chat message format and supports:
 *
 * <ul>
 *   <li>Basic text messages (user, assistant, system)
 *   <li>Tool call requests (assistant messages with tool_calls)
 *   <li>Tool results (tool messages with tool_call_id)
 * </ul>
 *
 * <p>Example formats:
 *
 * <pre>{@code
 * // User message
 * {"role": "user", "content": "Hello"}
 *
 * // Assistant message with tool call
 * {
 *   "role": "assistant",
 *   "content": "Let me check the weather",
 *   "tool_calls": [{
 *     "id": "call_abc",
 *     "type": "function",
 *     "function": {"name": "get_weather", "arguments": "{\"city\":\"Hangzhou\"}"}
 *   }]
 * }
 *
 * // Tool result message
 * {"role": "tool", "tool_call_id": "call_abc", "content": "{\"temp\": 25}"}
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatMessage {

    private String role;

    private String content;

    /** Tool calls made by the assistant (only for assistant role). */
    @JsonProperty("tool_calls")
    private List<ToolCall> toolCalls;

    /** ID of the tool call this message is responding to (only for tool role). */
    @JsonProperty("tool_call_id")
    private String toolCallId;

    /** Name of the tool (only for tool role). */
    private String name;

    /** Default constructor for deserialization. */
    public ChatMessage() {}

    /**
     * Creates a simple text message.
     *
     * @param role Message role (user, assistant, system, tool)
     * @param content Message content
     */
    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    /**
     * Creates an assistant message with tool calls.
     *
     * @param content Optional text content
     * @param toolCalls List of tool calls
     * @return New ChatMessage instance
     */
    public static ChatMessage assistantWithToolCalls(String content, List<ToolCall> toolCalls) {
        ChatMessage msg = new ChatMessage("assistant", content);
        msg.setToolCalls(toolCalls);
        return msg;
    }

    /**
     * Creates a tool result message.
     *
     * @param toolCallId ID of the tool call being responded to
     * @param name Name of the tool
     * @param content Tool execution result
     * @return New ChatMessage instance
     */
    public static ChatMessage toolResult(String toolCallId, String name, String content) {
        ChatMessage msg = new ChatMessage("tool", content);
        msg.setToolCallId(toolCallId);
        msg.setName(name);
        return msg;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<ToolCall> toolCalls) {
        this.toolCalls = toolCalls;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
