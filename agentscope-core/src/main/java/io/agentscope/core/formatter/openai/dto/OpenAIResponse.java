/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.formatter.openai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * OpenAI Chat Completion API response DTO.
 *
 * <p>This class represents the response structure from OpenAI's Chat Completion API.
 * It supports both non-streaming and streaming (chunk) responses.
 *
 * <p>Example non-streaming response:
 * <pre>{@code
 * {
 *   "id": "chatcmpl-123",
 *   "object": "chat.completion",
 *   "created": 1677652280,
 *   "model": "gpt-4o",
 *   "choices": [{
 *     "index": 0,
 *     "message": {
 *       "role": "assistant",
 *       "content": "Hello!"
 *     },
 *     "finish_reason": "stop"
 *   }],
 *   "usage": {
 *     "prompt_tokens": 10,
 *     "completion_tokens": 20,
 *     "total_tokens": 30
 *   }
 * }
 * }</pre>
 *
 * <p>Example streaming chunk:
 * <pre>{@code
 * {
 *   "id": "chatcmpl-123",
 *   "object": "chat.completion.chunk",
 *   "created": 1677652280,
 *   "model": "gpt-4o",
 *   "choices": [{
 *     "index": 0,
 *     "delta": {
 *       "content": "Hello"
 *     },
 *     "finish_reason": null
 *   }]
 * }
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenAIResponse {

    /** Unique identifier for the completion. */
    @JsonProperty("id")
    private String id;

    /** Object type ("chat.completion" or "chat.completion.chunk"). */
    @JsonProperty("object")
    private String object;

    /** Unix timestamp of when the completion was created. */
    @JsonProperty("created")
    private Long created;

    /** The model used for completion. */
    @JsonProperty("model")
    private String model;

    /** List of completion choices. */
    @JsonProperty("choices")
    private List<OpenAIChoice> choices;

    /** Token usage statistics. */
    @JsonProperty("usage")
    private OpenAIUsage usage;

    /** System fingerprint for reproducibility. */
    @JsonProperty("system_fingerprint")
    private String systemFingerprint;

    /** Error information (if request failed). */
    @JsonProperty("error")
    private OpenAIError error;

    public OpenAIResponse() {}

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

    public Long getCreated() {
        return created;
    }

    public void setCreated(Long created) {
        this.created = created;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<OpenAIChoice> getChoices() {
        return choices;
    }

    public void setChoices(List<OpenAIChoice> choices) {
        this.choices = choices;
    }

    public OpenAIUsage getUsage() {
        return usage;
    }

    public void setUsage(OpenAIUsage usage) {
        this.usage = usage;
    }

    public String getSystemFingerprint() {
        return systemFingerprint;
    }

    public void setSystemFingerprint(String systemFingerprint) {
        this.systemFingerprint = systemFingerprint;
    }

    public OpenAIError getError() {
        return error;
    }

    public void setError(OpenAIError error) {
        this.error = error;
    }

    /**
     * Check if this response represents an error.
     *
     * @return true if the response contains an error
     */
    public boolean isError() {
        return error != null;
    }

    /**
     * Check if this is a streaming chunk response.
     *
     * <p>Detects streaming chunks by:
     * <ol>
     *   <li>Object type equals "chat.completion.chunk" (OpenAI standard)</li>
     *   <li>OR presence of delta field without message field (GLM and other OpenAI-compatible APIs)</li>
     * </ol>
     *
     * @return true if this is a streaming chunk response
     */
    public boolean isChunk() {
        return "chat.completion.chunk".equals(object)
                || (choices != null
                        && choices.stream().anyMatch(choice -> choice.getDelta() != null));
    }

    /**
     * Get the first choice if available.
     *
     * @return the first choice, or null if no choices
     */
    public OpenAIChoice getFirstChoice() {
        if (choices != null && !choices.isEmpty()) {
            return choices.get(0);
        }
        return null;
    }
}
