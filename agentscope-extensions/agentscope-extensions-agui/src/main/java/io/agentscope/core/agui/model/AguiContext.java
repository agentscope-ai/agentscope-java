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
 * Represents a piece of contextual information in the AG-UI protocol.
 *
 * <p>Context items provide additional information to the agent that may be relevant
 * for generating responses. They contain a description of what the context represents
 * and its actual value.
 */
public class AguiContext {

    private final String description;
    private final String value;

    /**
     * Creates a new AguiContext.
     *
     * @param description Description of what this context represents
     * @param value The actual context value
     */
    @JsonCreator
    public AguiContext(
            @JsonProperty("description") String description, @JsonProperty("value") String value) {
        this.description = Objects.requireNonNull(description, "description cannot be null");
        this.value = Objects.requireNonNull(value, "value cannot be null");
    }

    /**
     * Get the context description.
     *
     * @return The description
     */
    public String getDescription() {
        return description;
    }

    /**
     * Get the context value.
     *
     * @return The value
     */
    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return "AguiContext{description='" + description + "', value='" + value + "'}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AguiContext that = (AguiContext) o;
        return Objects.equals(description, that.description) && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(description, value);
    }
}
