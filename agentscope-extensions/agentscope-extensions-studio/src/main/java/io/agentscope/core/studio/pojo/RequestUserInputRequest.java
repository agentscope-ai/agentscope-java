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

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Request payload for requesting user input from Studio web interface.
 *
 * <p>This is sent via POST /trpc/requestUserInput to prompt the user for input.
 */
public class RequestUserInputRequest {
    @JsonProperty("requestId")
    private final String requestId;

    @JsonProperty("runId")
    private final String runId;

    @JsonProperty("agentId")
    private final String agentId;

    @JsonProperty("agentName")
    private final String agentName;

    @JsonProperty("structuredInput")
    private final Object structuredInput;

    private RequestUserInputRequest(Builder builder) {
        this.requestId = builder.requestId;
        this.runId = builder.runId;
        this.agentId = builder.agentId;
        this.agentName = builder.agentName;
        this.structuredInput = builder.structuredInput;
    }

    /**
     * Gets the unique identifier for this user input request.
     *
     * @return the request ID
     */
    public String getRequestId() {
        return requestId;
    }

    /**
     * Gets the run ID associated with this input request.
     *
     * @return the run ID
     */
    public String getRunId() {
        return runId;
    }

    /**
     * Gets the ID of the agent requesting input.
     *
     * @return the agent ID
     */
    public String getAgentId() {
        return agentId;
    }

    /**
     * Gets the name of the agent requesting input.
     *
     * @return the agent name
     */
    public String getAgentName() {
        return agentName;
    }

    /**
     * Gets the structured input schema (if any).
     *
     * @return the structured input schema, or an empty map if not specified
     */
    public Object getStructuredInput() {
        return structuredInput;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String requestId;
        private String runId;
        private String agentId;
        private String agentName;
        private Object structuredInput = Map.of();

        public Builder requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        public Builder runId(String runId) {
            this.runId = runId;
            return this;
        }

        public Builder agentId(String agentId) {
            this.agentId = agentId;
            return this;
        }

        public Builder agentName(String agentName) {
            this.agentName = agentName;
            return this;
        }

        public Builder structuredInput(Object structuredInput) {
            this.structuredInput = structuredInput != null ? structuredInput : Map.of();
            return this;
        }

        public RequestUserInputRequest build() {
            return new RequestUserInputRequest(this);
        }
    }
}
