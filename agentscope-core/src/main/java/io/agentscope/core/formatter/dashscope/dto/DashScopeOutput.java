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
package io.agentscope.core.formatter.dashscope.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * DashScope output DTO.
 *
 * <p>This class represents the output section of a DashScope API response.
 *
 * <p>Example JSON:
 * <pre>{@code
 * {
 *   "choices": [
 *     {
 *       "message": {...},
 *       "finish_reason": "stop"
 *     }
 *   ],
 *   "finish_reason": "stop"
 * }
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DashScopeOutput {

    /** List of choices (typically one for chat completions). */
    @JsonProperty("choices")
    private List<DashScopeChoice> choices;

    /** Top-level finish reason. */
    @JsonProperty("finish_reason")
    private String finishReason;

    /** Legacy text field (for result_format="text"). */
    @JsonProperty("text")
    private String text;

    public DashScopeOutput() {}

    public List<DashScopeChoice> getChoices() {
        return choices;
    }

    public void setChoices(List<DashScopeChoice> choices) {
        this.choices = choices;
    }

    public String getFinishReason() {
        return finishReason;
    }

    public void setFinishReason(String finishReason) {
        this.finishReason = finishReason;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    /**
     * Get the first choice if available.
     *
     * @return the first choice, or null if no choices
     */
    public DashScopeChoice getFirstChoice() {
        if (choices != null && !choices.isEmpty()) {
            return choices.get(0);
        }
        return null;
    }
}
