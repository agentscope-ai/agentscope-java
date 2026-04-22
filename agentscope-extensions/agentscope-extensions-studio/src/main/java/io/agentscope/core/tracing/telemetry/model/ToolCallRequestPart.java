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

/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.agentscope.core.tracing.telemetry.model;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

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
