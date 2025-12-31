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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * OpenAI stream options DTO.
 *
 * <p>This class represents options for streaming responses.
 *
 * <p>Example:
 * <pre>{@code
 * {
 *   "include_usage": true
 * }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OpenAIStreamOptions {

    /** Whether to include usage statistics in the stream. */
    @JsonProperty("include_usage")
    private Boolean includeUsage;

    public OpenAIStreamOptions() {}

    public OpenAIStreamOptions(Boolean includeUsage) {
        this.includeUsage = includeUsage;
    }

    public Boolean getIncludeUsage() {
        return includeUsage;
    }

    public void setIncludeUsage(Boolean includeUsage) {
        this.includeUsage = includeUsage;
    }

    /**
     * Create a stream options with include_usage enabled.
     *
     * @return a new OpenAIStreamOptions with includeUsage=true
     */
    public static OpenAIStreamOptions withUsage() {
        return new OpenAIStreamOptions(true);
    }
}
