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
 * OpenAI tool definition DTO.
 *
 * <p>This class represents a tool that can be called by the model.
 *
 * <p>Example:
 * <pre>{@code
 * {
 *   "type": "function",
 *   "function": {
 *     "name": "get_weather",
 *     "description": "Get the current weather",
 *     "parameters": {
 *       "type": "object",
 *       "properties": {
 *         "location": {"type": "string"}
 *       },
 *       "required": ["location"]
 *     }
 *   }
 * }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OpenAITool {

    /** Tool type, always "function" for now. */
    @JsonProperty("type")
    private String type = "function";

    /** The function definition. */
    @JsonProperty("function")
    private OpenAIToolFunction function;

    public OpenAITool() {}

    public OpenAITool(OpenAIToolFunction function) {
        this.function = function;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public OpenAIToolFunction getFunction() {
        return function;
    }

    public void setFunction(OpenAIToolFunction function) {
        this.function = function;
    }

    /**
     * Create a function tool.
     *
     * @param function the function definition
     * @return a new OpenAITool
     */
    public static OpenAITool function(OpenAIToolFunction function) {
        return new OpenAITool(function);
    }
}
