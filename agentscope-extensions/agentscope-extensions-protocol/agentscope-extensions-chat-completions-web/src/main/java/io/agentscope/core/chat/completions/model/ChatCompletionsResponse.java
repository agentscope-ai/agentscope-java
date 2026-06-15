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
 * Response payload for the Chat Completions HTTP API.
 *
 * <p>This response is <b>100% compatible with OpenAI's Chat Completions API format</b>. All fields
 * follow the official OpenAI specification exactly.
 *
 * <p><b>Standard Response:</b>
 *
 * <pre>{@code
 * {
 *   "id": "chatcmpl-xxx",
 *   "object": "chat.completion",
 *   "created": 1234567890,
 *   "model": "gpt-4",
 *   "choices": [
 *     {
 *       "index": 0,
 *       "message": {"role": "assistant", "content": "Hello!"},
 *       "finish_reason": "stop"
 *     }
 *   ],
 *   "usage": {
 *     "prompt_tokens": 10,
 *     "completion_tokens": 5,
 *     "total_tokens": 15
 *   }
 * }
 * }</pre>
 *
 * <p><b>Response with Tool Calls:</b>
 *
 * <p>When the assistant calls tools:
 *
 * <pre>{@code
 * {
 *   "id": "chatcmpl-xxx",
 *   "object": "chat.completion",
 *   "choices": [{
 *     "index": 0,
 *     "message": {
 *       "role": "assistant",
 *       "content": null,
 *       "tool_calls": [{
 *         "id": "call_abc",
 *         "type": "function",
 *         "function": {"name": "get_weather", "arguments": "{\"city\":\"Hangzhou\"}"}
 *       }]
 *     },
 *     "finish_reason": "tool_calls"
 *   }],
 *   "usage": {...}
 * }
 * }</pre>
 *
 * <p><b>Stateless Client Responsibility:</b>
 *
 * <p>The client must:
 *
 * <ol>
 *   <li>Append the assistant message from response to their conversation history
 *   <li>If tool_calls present, execute tools and append tool result messages
 *   <li>Send the complete history in the next request
 * </ol>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatCompletionsResponse {

    /** Unique identifier for this completion. */
    private String id;

    /** Object type, always "chat.completion" for non-streaming responses. */
    private String object = "chat.completion";

    /** Unix timestamp when the completion was created. */
    private long created;

    /** The model used for generation. */
    private String model;

    /** List of completion choices. */
    private List<ChatChoice> choices;

    /** Token usage statistics. */
    private Usage usage;

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

    public Usage getUsage() {
        return usage;
    }

    public void setUsage(Usage usage) {
        this.usage = usage;
    }

    /**
     * Token usage statistics following OpenAI's format.
     *
     * <p>All fields use snake_case per OpenAI specification.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Usage {

        /** Number of tokens in the prompt. */
        @JsonProperty("prompt_tokens")
        private Integer promptTokens;

        /** Number of tokens in the completion. */
        @JsonProperty("completion_tokens")
        private Integer completionTokens;

        /** Total tokens (prompt + completion). */
        @JsonProperty("total_tokens")
        private Integer totalTokens;

        public Usage() {}

        public Usage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
        }

        public Integer getPromptTokens() {
            return promptTokens;
        }

        public void setPromptTokens(Integer promptTokens) {
            this.promptTokens = promptTokens;
        }

        public Integer getCompletionTokens() {
            return completionTokens;
        }

        public void setCompletionTokens(Integer completionTokens) {
            this.completionTokens = completionTokens;
        }

        public Integer getTotalTokens() {
            return totalTokens;
        }

        public void setTotalTokens(Integer totalTokens) {
            this.totalTokens = totalTokens;
        }
    }
}
