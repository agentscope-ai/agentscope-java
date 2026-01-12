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
import java.time.Instant;
import java.util.List;

/**
 * Streaming chunk for Chat Completions API.
 *
 * <p>This follows OpenAI's streaming response format for chat completions. Each chunk contains a
 * delta with partial content.
 *
 * <p><b>Example streaming chunk:</b>
 *
 * <pre>
 * {
 *   "id": "chatcmpl-xxx",
 *   "object": "chat.completion.chunk",
 *   "created": 1234567890,
 *   "model": "gpt-4",
 *   "choices": [{
 *     "index": 0,
 *     "delta": {"content": "Hello"},
 *     "finish_reason": null
 *   }]
 * }
 * </pre>
 *
 * <p><b>Tool call streaming chunk:</b>
 *
 * <pre>
 * {
 *   "id": "chatcmpl-xxx",
 *   "object": "chat.completion.chunk",
 *   "created": 1234567890,
 *   "model": "gpt-4",
 *   "choices": [{
 *     "index": 0,
 *     "delta": {
 *       "tool_calls": [{
 *         "index": 0,
 *         "id": "call_abc",
 *         "type": "function",
 *         "function": {"name": "get_weather", "arguments": "{...}"}
 *       }]
 *     },
 *     "finish_reason": null
 *   }]
 * }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatCompletionsChunk {

    private String id;

    private String object = "chat.completion.chunk";

    private long created;

    private String model;

    private List<ChatChoice> choices;

    /** Default constructor. */
    public ChatCompletionsChunk() {
        this.created = Instant.now().getEpochSecond();
    }

    /**
     * Create a chunk with basic info.
     *
     * @param id Request ID
     * @param model Model name
     */
    public ChatCompletionsChunk(String id, String model) {
        this();
        this.id = id;
        this.model = model;
    }

    /**
     * Create a text content chunk.
     *
     * @param id Request ID
     * @param model Model name
     * @param content Text content delta
     * @return ChatCompletionsChunk with text delta
     */
    public static ChatCompletionsChunk textChunk(String id, String model, String content) {
        ChatCompletionsChunk chunk = new ChatCompletionsChunk(id, model);

        ChatMessage delta = new ChatMessage();
        delta.setRole("assistant");
        delta.setContent(content);

        ChatChoice choice = new ChatChoice();
        choice.setIndex(0);
        choice.setDelta(delta);

        chunk.setChoices(List.of(choice));
        return chunk;
    }

    /**
     * Create a tool call chunk.
     *
     * @param id Request ID
     * @param model Model name
     * @param toolCalls Tool calls to include
     * @return ChatCompletionsChunk with tool calls
     */
    public static ChatCompletionsChunk toolCallChunk(
            String id, String model, List<ToolCall> toolCalls) {
        ChatCompletionsChunk chunk = new ChatCompletionsChunk(id, model);

        ChatMessage delta = new ChatMessage();
        delta.setRole("assistant");
        delta.setToolCalls(toolCalls);

        ChatChoice choice = new ChatChoice();
        choice.setIndex(0);
        choice.setDelta(delta);

        chunk.setChoices(List.of(choice));
        return chunk;
    }

    /**
     * Create a tool result chunk.
     *
     * <p>This represents the result of a tool execution in streaming format. While not part of the
     * standard OpenAI streaming response (since OpenAI doesn't execute tools), AgentScope's
     * ReActAgent executes tools internally, so we expose the results in the stream.
     *
     * <p><b>Example output:</b>
     *
     * <pre>
     * {
     *   "id": "chatcmpl-xxx",
     *   "object": "chat.completion.chunk",
     *   "created": 1234567890,
     *   "model": "gpt-4",
     *   "choices": [{
     *     "index": 0,
     *     "delta": {
     *       "role": "tool",
     *       "tool_call_id": "call_abc",
     *       "name": "get_weather",
     *       "content": "The weather is sunny..."
     *     }
     *   }]
     * }
     * </pre>
     *
     * @param id Request ID
     * @param model Model name
     * @param toolCallId The ID of the tool call this result corresponds to
     * @param toolName The name of the tool that was executed
     * @param content The tool execution result content
     * @return ChatCompletionsChunk with tool result
     */
    public static ChatCompletionsChunk toolResultChunk(
            String id, String model, String toolCallId, String toolName, String content) {
        ChatCompletionsChunk chunk = new ChatCompletionsChunk(id, model);

        ChatMessage delta = new ChatMessage();
        delta.setRole("tool");
        delta.setToolCallId(toolCallId);
        delta.setName(toolName);
        delta.setContent(content);

        ChatChoice choice = new ChatChoice();
        choice.setIndex(0);
        choice.setDelta(delta);

        chunk.setChoices(List.of(choice));
        return chunk;
    }

    /**
     * Create a finish chunk.
     *
     * @param id Request ID
     * @param model Model name
     * @param finishReason The finish reason (stop, tool_calls, etc.)
     * @return ChatCompletionsChunk with finish_reason
     */
    public static ChatCompletionsChunk finishChunk(String id, String model, String finishReason) {
        ChatCompletionsChunk chunk = new ChatCompletionsChunk(id, model);

        ChatMessage delta = new ChatMessage();

        ChatChoice choice = new ChatChoice();
        choice.setIndex(0);
        choice.setDelta(delta);
        choice.setFinishReason(finishReason);

        chunk.setChoices(List.of(choice));
        return chunk;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getObject() {
        return object;
    }

    public void setObject(String object) {
        this.object = object;
    }

    public long getCreated() {
        return created;
    }

    public void setCreated(long created) {
        this.created = created;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<ChatChoice> getChoices() {
        return choices;
    }

    public void setChoices(List<ChatChoice> choices) {
        this.choices = choices;
    }
}
