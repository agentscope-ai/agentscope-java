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
package io.agentscope.examples.planskillcombo;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Tool result data. {@code id} maps to {@code ToolResultBlock#getId()}.
 *
 * <pre>{@code
 * {"type":"toolResult","id":"call_123","toolName":"query_metrics","output":"CPU 使用率: 95%"}
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ToolResultMessage extends StreamMessage {

    private final String id;
    private final String toolName;
    private final String output;

    @JsonCreator
    public ToolResultMessage(
            @JsonProperty("id") String id,
            @JsonProperty("toolName") String toolName,
            @JsonProperty("output") String output) {
        this.id = id;
        this.toolName = toolName;
        this.output = output != null ? output : "";
    }

    public String getId() {
        return id;
    }

    public String getToolName() {
        return toolName;
    }

    public String getOutput() {
        return output;
    }
}
