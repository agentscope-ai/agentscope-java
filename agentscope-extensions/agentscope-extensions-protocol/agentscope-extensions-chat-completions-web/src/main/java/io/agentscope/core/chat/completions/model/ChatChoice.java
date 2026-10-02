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

/**
 * Single choice in a Chat Completions style response.
 *
 * <p>This class follows OpenAI's Chat Completions API format.
 *
 * <p>Example:
 *
 * <pre>{@code
 * {
 *   "index": 0,
 *   "message": {
 *     "role": "assistant",
 *     "content": "Hello! How can I help you?"
 *   },
 *   "finish_reason": "stop"
 * }
 * }</pre>
 *
 * <p>Possible finish_reason values:
 *
 * <ul>
 *   <li>"stop" - Natural end of message
 *   <li>"length" - Max tokens reached
 *   <li>"tool_calls" - Model wants to call tools
 *   <li>"content_filter" - Content was filtered
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatChoice {

    private int index;

    /** The message content for non-streaming responses. */
    private ChatMessage message;

    /** The delta content for streaming responses. */
    private ChatMessage delta;

    /** Reason the model stopped generating. Uses snake_case per OpenAI spec. */
    @JsonProperty("finish_reason")
    private String finishReason;

    /** Log probability information (optional, OpenAI field). */
    private Object logprobs;

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public ChatMessage getMessage() {
        return message;
    }

    public void setMessage(ChatMessage message) {
        this.message = message;
    }

    public ChatMessage getDelta() {
        return delta;
    }

    public void setDelta(ChatMessage delta) {
        this.delta = delta;
    }

    public String getFinishReason() {
        return finishReason;
    }

    public void setFinishReason(String finishReason) {
        this.finishReason = finishReason;
    }

    public Object getLogprobs() {
        return logprobs;
    }

    public void setLogprobs(Object logprobs) {
        this.logprobs = logprobs;
    }
}
