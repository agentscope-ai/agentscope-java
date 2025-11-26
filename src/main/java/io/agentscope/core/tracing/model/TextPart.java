/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.agentscope.core.tracing.model;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/** Represents text content sent to or received from the model. */
@JsonClassDescription("Text part")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TextPart implements MessagePart {

    private final String type;

    private final String content;

    @JsonProperty(required = true, value = "type")
    @JsonPropertyDescription("The type of the content captured in this part")
    @Override
    public String getType() {
        return this.type;
    }

    @JsonProperty(required = true, value = "content")
    @JsonPropertyDescription("Text content sent to or received from the model")
    public String getContent() {
        return this.content;
    }

    public static TextPart create(String content) {
        return new TextPart("text", content);
    }

    private TextPart(String type, String content) {
        this.type = type;
        this.content = content;
    }
}
