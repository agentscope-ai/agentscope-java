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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

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

    public DashScopeRequest() {}

    public DashScopeRequest(String model, DashScopeInput input, DashScopeParameters parameters) {
        this.model = model;
        this.input = input;
        this.parameters = parameters;
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

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String model;
        private DashScopeInput input;
        private DashScopeParameters parameters;

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

        public DashScopeRequest build() {
            return new DashScopeRequest(model, input, parameters);
        }
    }
}
