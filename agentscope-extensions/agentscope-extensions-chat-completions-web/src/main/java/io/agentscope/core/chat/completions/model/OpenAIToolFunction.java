/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.agentscope.core.chat.completions.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * OpenAI tool function definition for Chat Completions API requests.
 *
 * <p>This class represents the function definition in a tool, following OpenAI's format.
 *
 * <p>Example:
 * <pre>{@code
 * {
 *   "name": "get_weather",
 *   "description": "Get the current weather",
 *   "parameters": {
 *     "type": "object",
 *     "properties": {
 *       "location": {"type": "string"}
 *     },
 *     "required": ["location"]
 *   }
 * }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OpenAIToolFunction {

    /** The name of the function. */
    @JsonProperty("name")
    private String name;

    /** The description of the function. */
    @JsonProperty("description")
    private String description;

    /** The JSON Schema for the function parameters. */
    @JsonProperty("parameters")
    private Map<String, Object> parameters;

    /** Whether to enable strict mode for schema validation. */
    @JsonProperty("strict")
    private Boolean strict;

    /** Default constructor for deserialization. */
    public OpenAIToolFunction() {}

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, Object> parameters) {
        this.parameters = parameters;
    }

    public Boolean getStrict() {
        return strict;
    }

    public void setStrict(Boolean strict) {
        this.strict = strict;
    }
}

