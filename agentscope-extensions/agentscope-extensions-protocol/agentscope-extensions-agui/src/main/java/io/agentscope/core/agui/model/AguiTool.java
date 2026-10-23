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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a tool definition in the AG-UI protocol.
 *
 * <p>Tools are functions that the agent can call to perform actions or retrieve information.
 * This class defines the tool's interface including its name, description, and parameter schema.
 */
public class AguiTool {

    private final String name;
    private final String description;
    private final Map<String, Object> parameters;

    /**
     * Creates a new AguiTool.
     *
     * @param name The tool name
     * @param description The tool description
     * @param parameters The parameters JSON Schema
     */
    @JsonCreator
    public AguiTool(
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("parameters") Map<String, Object> parameters) {
        this.name = Objects.requireNonNull(name, "name cannot be null");
        this.description = Objects.requireNonNull(description, "description cannot be null");
        this.parameters =
                parameters != null
                        ? Collections.unmodifiableMap(new HashMap<>(parameters))
                        : Collections.emptyMap();
    }

    /**
     * Get the tool name.
     *
     * @return The tool name
     */
    public String getName() {
        return name;
    }

    /**
     * Get the tool description.
     *
     * @return The tool description
     */
    public String getDescription() {
        return description;
    }

    /**
     * Get the parameters JSON Schema.
     *
     * @return The parameters schema as an immutable map
     */
    public Map<String, Object> getParameters() {
        return parameters;
    }

    @Override
    public String toString() {
        return "AguiTool{name='"
                + name
                + "', description='"
                + description
                + "', parameters="
                + parameters
                + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AguiTool aguiTool = (AguiTool) o;
        return Objects.equals(name, aguiTool.name)
                && Objects.equals(description, aguiTool.description)
                && Objects.equals(parameters, aguiTool.parameters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, description, parameters);
    }
}
