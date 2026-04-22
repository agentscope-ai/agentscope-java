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

package io.agentscope.core.tracing.telemetry.model;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/** Represents text content sent to or received from the model. */
@JsonClassDescription("Text part")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReasoningPart implements MessagePart {

    private final String type;

    private final String content;

    @JsonProperty(required = true, value = "type")
    @JsonPropertyDescription("The type of the content captured in this part")
    @Override
    public String getType() {
        return this.type;
    }

    @JsonProperty(required = true, value = "content")
    @JsonPropertyDescription("Reasoning/thinking content sent to or received from the model")
    public String getContent() {
        return this.content;
    }

    public static ReasoningPart create(String content) {
        return new ReasoningPart("reasoning", content);
    }

    private ReasoningPart(String type, String content) {
        this.type = type;
        this.content = content;
    }
}
