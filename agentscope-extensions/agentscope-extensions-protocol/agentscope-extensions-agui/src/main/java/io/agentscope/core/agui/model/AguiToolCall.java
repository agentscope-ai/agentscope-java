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
package io.agentscope.core.agui.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * Represents a tool call in the AG-UI protocol.
 *
 * <p>Tool calls are used in assistant messages to indicate that the agent
 * wants to invoke a tool. They contain an ID, type, and function details.
 */
public class AguiToolCall {

    private final String id;
    private final String type;
    private final AguiFunctionCall function;

    /**
     * Creates a new AguiToolCall.
     *
     * @param id The unique tool call ID
     * @param type The tool call type (typically "function")
     * @param function The function call details
     */
    @JsonCreator
    public AguiToolCall(
            @JsonProperty("id") String id,
            @JsonProperty("type") String type,
            @JsonProperty("function") AguiFunctionCall function) {
        this.id = Objects.requireNonNull(id, "id cannot be null");
        this.type = type != null ? type : "function";
        this.function = Objects.requireNonNull(function, "function cannot be null");
    }

    /**
     * Creates a new AguiToolCall with type "function".
     *
     * @param id The unique tool call ID
     * @param function The function call details
     */
    public AguiToolCall(String id, AguiFunctionCall function) {
        this(id, "function", function);
    }

    /**
     * Get the tool call ID.
     *
     * @return The tool call ID
     */
    public String getId() {
        return id;
    }

    /**
     * Get the tool call type.
     *
     * @return The type (typically "function")
     */
    public String getType() {
        return type;
    }

    /**
     * Get the function call details.
     *
     * @return The function call
     */
    public AguiFunctionCall getFunction() {
        return function;
    }

    @Override
    public String toString() {
        return "AguiToolCall{id='" + id + "', type='" + type + "', function=" + function + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AguiToolCall that = (AguiToolCall) o;
        return Objects.equals(id, that.id)
                && Objects.equals(type, that.type)
                && Objects.equals(function, that.function);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, type, function);
    }
}
