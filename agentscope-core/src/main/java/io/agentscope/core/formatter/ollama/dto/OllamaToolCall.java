/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.formatter.ollama.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Ollama tool call DTO.
 * Represents a specific tool call invocation.
 *
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OllamaToolCall {

    /** The function to be called. */
    @JsonProperty("function")
    private OllamaFunction function;

    public OllamaToolCall() {}

    public OllamaToolCall(OllamaFunction function) {
        this.function = function;
    }

    public OllamaFunction getFunction() {
        return function;
    }

    public void setFunction(OllamaFunction function) {
        this.function = function;
    }
}
