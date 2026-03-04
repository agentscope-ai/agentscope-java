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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.core.model.EndpointType;

/**
 * DashScope API request DTO.
 *
 * <p>This class represents the top-level request structure for DashScope's
 * text-generation and multimodal-generation APIs.
 *
 * <p>Example JSON:
 * <pre>{@code
 * {
 *   "model": "qwen-plus",
 *   "input": {
 *     "messages": [...]
 *   },
 *   "parameters": {
 *     "result_format": "message",
 *     "temperature": 0.7
 *   }
 * }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DashScopeRequest {

    /** The model name (e.g., "qwen-plus", "qwen-vl-max"). */
    @JsonProperty("model")
    private String model;

    /** The input containing messages. */
    @JsonProperty("input")
    private DashScopeInput input;

    /** The generation parameters. */
    @JsonProperty("parameters")
    private DashScopeParameters parameters;

    /**
     * The endpoint type for endpoint selection (not serialized to JSON).
     *
     * <p>This is an internal field used to determine which DashScope API endpoint to use.
     * It does not get sent to the API.
     */
    @JsonIgnore private EndpointType endpointType;

    public DashScopeRequest() {
        this.endpointType = EndpointType.AUTO;
    }

    public DashScopeRequest(String model, DashScopeInput input, DashScopeParameters parameters) {
        this.model = model;
        this.input = input;
        this.parameters = parameters;
        this.endpointType = EndpointType.AUTO;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public DashScopeInput getInput() {
        return input;
    }

    public void setInput(DashScopeInput input) {
        this.input = input;
    }

    public DashScopeParameters getParameters() {
        return parameters;
    }

    public void setParameters(DashScopeParameters parameters) {
        this.parameters = parameters;
    }

    /**
     * Gets the endpoint type for endpoint selection.
     *
     * @return the endpoint type (defaults to AUTO)
     */
    public EndpointType getEndpointType() {
        return endpointType;
    }

    /**
     * Sets the endpoint type for endpoint selection.
     *
     * @param endpointType the endpoint type
     */
    public void setEndpointType(EndpointType endpointType) {
        this.endpointType = endpointType;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String model;
        private DashScopeInput input;
        private DashScopeParameters parameters;
        private EndpointType endpointType = EndpointType.AUTO;

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder input(DashScopeInput input) {
            this.input = input;
            return this;
        }

        public Builder parameters(DashScopeParameters parameters) {
            this.parameters = parameters;
            return this;
        }

        /**
         * Sets the endpoint type for endpoint selection.
         *
         * @param endpointType the endpoint type
         * @return this builder
         */
        public Builder endpointType(EndpointType endpointType) {
            this.endpointType = endpointType;
            return this;
        }

        public DashScopeRequest build() {
            DashScopeRequest request = new DashScopeRequest(model, input, parameters);
            request.setEndpointType(endpointType);
            return request;
        }
    }
}
