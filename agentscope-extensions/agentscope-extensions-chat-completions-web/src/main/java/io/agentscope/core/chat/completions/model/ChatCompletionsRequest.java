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

import java.util.List;

/**
 * Request payload for the Chat Completions HTTP API.
 *
 * <p>This request is <b>100% compatible with OpenAI's Chat Completions API</b>. All fields follow
 * the official OpenAI specification exactly.
 *
 * <p><b>Stateless Operation:</b>
 *
 * <p>This API is fully stateless, just like OpenAI's standard API. The client is responsible for
 * maintaining conversation history and sending the complete {@code messages} array with each
 * request.
 *
 * <p><b>Example Request:</b>
 *
 * <pre>{@code
 * {
 *   "model": "gpt-4",
 *   "messages": [
 *     {"role": "system", "content": "You are a helpful assistant."},
 *     {"role": "user", "content": "What's the weather in Hangzhou?"},
 *     {"role": "assistant", "content": "Let me check...", "tool_calls": [...]},
 *     {"role": "tool", "tool_call_id": "call_xxx", "content": "25°C sunny"},
 *     {"role": "user", "content": "Thanks! And tomorrow?"}
 *   ],
 *   "stream": false
 * }
 * }</pre>
 *
 * <p><b>Tool Call Flow:</b>
 *
 * <p>When the assistant calls tools, the response includes {@code tool_calls}. The client should:
 *
 * <ol>
 *   <li>Append the assistant message (with tool_calls) to history
 *   <li>Append tool result messages for each tool call
 *   <li>Send the updated history in the next request
 * </ol>
 */
public class ChatCompletionsRequest {

    /** Model ID to use for generation. Required field per OpenAI spec. */
    private String model;

    /**
     * A list of messages comprising the conversation so far.
     *
     * <p>Required field. The client must send the complete conversation history for stateless
     * operation.
     */
    private List<ChatMessage> messages;

    /** Whether to stream responses via Server-Sent Events (SSE). Optional, defaults to false. */
    private Boolean stream;

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<ChatMessage> messages) {
        this.messages = messages;
    }

    public Boolean getStream() {
        return stream;
    }

    public void setStream(Boolean stream) {
        this.stream = stream;
    }
}
