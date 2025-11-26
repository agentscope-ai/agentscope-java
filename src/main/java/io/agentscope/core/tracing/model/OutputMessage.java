/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.agentscope.core.tracing.model;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;
import javax.annotation.Nullable;

@JsonClassDescription("Output message")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OutputMessage {

    private final String role;

    private final List<MessagePart> parts;

    private final String name;

    private final String finishReason;

    @JsonProperty(required = true, value = "role")
    @JsonPropertyDescription("Role of response")
    public String getRole() {
        return this.role;
    }

    @JsonProperty(required = true, value = "parts")
    @JsonPropertyDescription("List of message parts that make up the message content")
    public List<MessagePart> getParts() {
        return this.parts;
    }

    @Nullable
    @JsonProperty(value = "name")
    @JsonPropertyDescription("The name of the participant")
    public String getName() {
        return this.name;
    }

    @JsonProperty(required = true, value = "finish_reason")
    @JsonPropertyDescription("Reason for finishing the generation")
    public String getFinishReason() {
        return this.finishReason;
    }

    public static OutputMessage create(
            String role, List<MessagePart> parts, String name, String finishReason) {
        return new OutputMessage(role, parts, name, finishReason);
    }

    public static OutputMessage create(
            Role role, List<MessagePart> parts, String name, String finishReason) {
        return new OutputMessage(role.getValue(), parts, name, finishReason);
    }

    private OutputMessage(String role, List<MessagePart> parts, String name, String finishReason) {
        this.role = role;
        this.parts = parts;
        this.name = name;
        this.finishReason = finishReason;
    }
}
