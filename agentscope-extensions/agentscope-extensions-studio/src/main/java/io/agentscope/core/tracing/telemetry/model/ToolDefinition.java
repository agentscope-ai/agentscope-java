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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.Map;

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

    @JsonProperty(value = "description")
    @JsonPropertyDescription("Description of tool")
    public String getDescription() {
        return this.description;
    }

    @JsonProperty(value = "parameters")
    @JsonPropertyDescription("Parameters definitions of tool")
    public Map<String, Object> getParameters() {
        return this.parameters;
    }

    public static ToolDefinition create(String type, String name) {
        return create(type, name, null, null);
    }

    public static ToolDefinition create(
            String type, String name, String description, Map<String, Object> parameters) {
        return new ToolDefinition(type, name, description, parameters);
    }

    private ToolDefinition(
            String type, String name, String description, Map<String, Object> parameters) {
        this.type = type;
        this.name = name;
        this.description = description;
        this.parameters = parameters;
    }
}
