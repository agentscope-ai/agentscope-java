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
package io.agentscope.core.formatter.dashscope.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DashScope tool call DTO.
 *
 * <p>This class represents a tool call made by the assistant.
 *
 * <p>Example:
 * <pre>{@code
 * {
 *   "id": "call_abc123",
 *   "type": "function",
 *   "function": {
 *     "name": "get_weather",
 *     "arguments": "{\"location\": \"Beijing\"}"
 *   }
 * }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DashScopeToolCall {

    /** Unique identifier for this tool call. */
    @JsonProperty("id")
    private String id;

    /** Tool type, always "function" for now. */
    @JsonProperty("type")
    private String type = "function";

    /** The function to call. */
    @JsonProperty("function")
    private DashScopeFunction function;

    /** Index in the tool_calls array (for streaming). */
    @JsonProperty("index")
    private Integer index;

    public DashScopeToolCall() {}

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public DashScopeFunction getFunction() {
        return function;
    }

    public void setFunction(DashScopeFunction function) {
        this.function = function;
    }

    public Integer getIndex() {
        return index;
    }

    public void setIndex(Integer index) {
        this.index = index;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final DashScopeToolCall toolCall = new DashScopeToolCall();

        public Builder id(String id) {
            toolCall.setId(id);
            return this;
        }

        public Builder type(String type) {
            toolCall.setType(type);
            return this;
        }

        public Builder function(DashScopeFunction function) {
            toolCall.setFunction(function);
            return this;
        }

        public Builder index(Integer index) {
            toolCall.setIndex(index);
            return this;
        }

        public DashScopeToolCall build() {
            return toolCall;
        }
    }
}
