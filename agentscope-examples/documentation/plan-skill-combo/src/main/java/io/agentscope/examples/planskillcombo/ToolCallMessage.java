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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Tool call data. {@code id} maps to {@code ToolUseBlock#getId()}.
 *
 * <pre>{@code
 * {"type":"toolCall","id":"call_123","toolName":"query_metrics","input":{"server":"web-server-01"}}
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ToolCallMessage extends StreamMessage {

    private final String id;
    private final String toolName;
    private final Map<String, Object> input;

    @JsonCreator
    public ToolCallMessage(
            @JsonProperty("id") String id,
            @JsonProperty("toolName") String toolName,
            @JsonProperty("input") Map<String, Object> input) {
        this.id = id;
        this.toolName = toolName;
        this.input =
                input == null
                        ? Collections.emptyMap()
                        : Collections.unmodifiableMap(new HashMap<>(input));
    }

    public String getId() {
        return id;
    }

    public String getToolName() {
        return toolName;
    }

    public Map<String, Object> getInput() {
        return input;
    }
}
