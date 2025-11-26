/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.agentscope.core.tracing.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.Map;
import javax.annotation.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolDefinition {

    private final String type;

    private final String name;

    private final String description;

    private final Map<String, Object> parameters;

    @JsonProperty(required = true, value = "type")
    @JsonPropertyDescription("Type of tool")
    public String getType() {
        return this.type;
    }

    @JsonProperty(required = true, value = "name")
    @JsonPropertyDescription("Name of tool")
    public String getName() {
        return this.name;
    }

    @Nullable
    @JsonProperty(value = "description")
    @JsonPropertyDescription("Description of tool")
    public String getDescription() {
        return this.description;
    }

    @Nullable
    @JsonProperty(value = "parameters")
    @JsonPropertyDescription("Parameters definitions of tool")
    public Map<String, Object> getParameters() {
        return this.parameters;
    }

    public static ToolDefinition create(String type, String name) {
        return create(type, name, null, null);
    }

    public static ToolDefinition create(
            String type,
            String name,
            @Nullable String description,
            @Nullable Map<String, Object> parameters) {
        return new ToolDefinition(type, name, description, parameters);
    }

    private ToolDefinition(
            String type,
            String name,
            @Nullable String description,
            @Nullable Map<String, Object> parameters) {
        this.type = type;
        this.name = name;
        this.description = description;
        this.parameters = parameters;
    }
}
