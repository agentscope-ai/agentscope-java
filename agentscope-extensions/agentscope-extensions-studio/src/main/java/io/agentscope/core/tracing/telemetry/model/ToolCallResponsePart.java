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

/** Represents a tool call result sent to the model or a built-in tool call outcome and details. */
@JsonClassDescription("Tool call response part")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolCallResponsePart implements MessagePart {

    private final String type;

    private final String id;

    private final Object response;

    @JsonProperty(required = true, value = "type")
    @JsonPropertyDescription("The type of the content captured in this part")
    @Override
    public String getType() {
        return this.type;
    }

    @JsonProperty(value = "id")
    @JsonPropertyDescription("Unique tool call identifier")
    public String getId() {
        return this.id;
    }

    @JsonProperty(value = "response")
    @JsonPropertyDescription("Tool call response")
    public Object getResponse() {
        return this.response;
    }

    public static ToolCallResponsePart create(Object response) {
        return new ToolCallResponsePart("tool_call_response", null, response);
    }

    public static ToolCallResponsePart create(String id, Object response) {
        return new ToolCallResponsePart("tool_call_response", id, response);
    }

    private ToolCallResponsePart(String type, String id, Object response) {
        this.type = type;
        this.id = id;
        this.response = response;
    }
}
