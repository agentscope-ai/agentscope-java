/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.agentscope.core.chat.completions.model;

/**
 * Represents a tool call in OpenAI-compatible format.
 *
 * <p>This DTO is used to serialize tool calls in the conversation context, allowing clients to
 * reconstruct the full conversation history including tool invocations.
 *
 * <p>Example JSON:
 *
 * <pre>{@code
 * {
 *   "id": "call_abc123",
 *   "type": "function",
 *   "function": {
 *     "name": "get_weather",
 *     "arguments": "{\"city\":\"Hangzhou\"}"
 *   }
 * }
 * }</pre>
 */
public class ToolCall {

    private String id;

    private String type = "function";

    private FunctionCall function;

    /** Default constructor for deserialization. */
    public ToolCall() {}

    /**
     * Creates a new tool call.
     *
     * @param id Unique identifier for this tool call
     * @param name Function name
     * @param arguments JSON string of function arguments
     */
    public ToolCall(String id, String name, String arguments) {
        this.id = id;
        this.type = "function";
        this.function = new FunctionCall(name, arguments);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public FunctionCall getFunction() {
        return function;
    }

    public void setFunction(FunctionCall function) {
        this.function = function;
    }

    /** Represents the function details within a tool call. */
    public static class FunctionCall {

        private String name;

        private String arguments;

        /** Default constructor for deserialization. */
        public FunctionCall() {}

        /**
         * Creates a new function call.
         *
         * @param name Function name
         * @param arguments JSON string of function arguments
         */
        public FunctionCall(String name, String arguments) {
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
    }
}
