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

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * OpenAI function call DTO.
 *
 * <p>This class represents the function details in a tool call.
 *
 * <p>Example:
 * <pre>{@code
 * {
 *   "name": "get_weather",
 *   "arguments": "{\"location\": \"Beijing\"}"
 * }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenAIFunction {

    /** The name of the function to call. */
    @JsonProperty("name")
    private String name;

    /** The arguments to pass to the function (JSON string). */
    @JsonProperty("arguments")
    private String arguments;

    /** Gemini thought signature (opaque string). */
    @JsonProperty("thought_signature")
    @JsonAlias("thoughtSignature")
    private String thoughtSignature;

    public OpenAIFunction() {}

    public OpenAIFunction(String name, String arguments) {
        this.name = name;
        this.arguments = arguments;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getArguments() {
        return arguments;
    }

    public void setArguments(String arguments) {
        this.arguments = arguments;
    }

    public String getThoughtSignature() {
        return thoughtSignature;
    }

    public void setThoughtSignature(String thoughtSignature) {
        this.thoughtSignature = thoughtSignature;
    }

    public static OpenAIFunction of(String name, String arguments) {
        return new OpenAIFunction(name, arguments);
    }

    public static OpenAIFunction of(String name, String arguments, String thoughtSignature) {
        OpenAIFunction function = new OpenAIFunction(name, arguments);
        function.setThoughtSignature(thoughtSignature);
        return function;
    }
}
