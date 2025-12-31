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
package io.agentscope.core.studio.pojo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Metadata for user input messages from Studio.
 *
 * <p>This class provides type-safe metadata for messages received from AgentScope Studio,
 * including the source, request ID, and optional structured input data.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserInputMetadata {
    @JsonProperty("source")
    private final String source;

    @JsonProperty("requestId")
    private final String requestId;

    @JsonProperty("structuredInput")
    private final Map<String, Object> structuredInput;

    private UserInputMetadata(Builder builder) {
        this.source = builder.source;
        this.requestId = builder.requestId;
        this.structuredInput = builder.structuredInput;
    }

    /**
     * Gets the source of the user input.
     *
     * @return the source identifier (typically "studio")
     */
    public String getSource() {
        return source;
    }

    /**
     * Gets the request ID that this input is responding to.
     *
     * @return the request ID, or null if this is not a response to a specific request
     */
    public String getRequestId() {
        return requestId;
    }

    /**
     * Gets the structured input data if provided.
     *
     * @return map of structured input fields, or null if not provided
     */
    public Map<String, Object> getStructuredInput() {
        return structuredInput;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String source = "studio";
        private String requestId;
        private Map<String, Object> structuredInput;

        public Builder source(String source) {
            this.source = source;
            return this;
        }

        public Builder requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        public Builder structuredInput(Map<String, Object> structuredInput) {
            this.structuredInput = structuredInput;
            return this;
        }

        public UserInputMetadata build() {
            return new UserInputMetadata(this);
        }
    }
}
