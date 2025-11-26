/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.agentscope.core.tracing.model;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import javax.annotation.Nullable;

/** Represents a tool call requested by the model. */
@JsonClassDescription("Tool call request part")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolCallRequestPart implements MessagePart {

    private final String type;

    private final String id;

    private final String name;

    private final Object arguments;

    @JsonProperty(required = true, value = "type")
    @JsonPropertyDescription("The type of the content captured in this part")
    @Override
    public String getType() {
        return this.type;
    }

    @JsonProperty(value = "id")
    @JsonPropertyDescription("Unique identifier for the tool call")
    @Nullable
    public String getId() {
        return this.id;
    }

    @JsonProperty(required = true, value = "name")
    @JsonPropertyDescription("Name of the tool")
    public String getName() {
        return this.name;
    }

    @JsonProperty(value = "arguments")
    @JsonPropertyDescription("Arguments for the tool call")
    @Nullable
    public Object getArguments() {
        return this.arguments;
    }

    public static ToolCallRequestPart create(String name) {
        return new ToolCallRequestPart("tool_call", null, name, null);
    }

    public static ToolCallRequestPart create(String id, String name, Object arguments) {
        return new ToolCallRequestPart("tool_call", id, name, arguments);
    }

    private ToolCallRequestPart(String type, String id, String name, Object arguments) {
        this.type = type;
        this.id = id;
        this.name = name;
        this.arguments = arguments;
    }
}
