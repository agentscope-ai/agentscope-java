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

/** Represents an input message sent to the model. */
@JsonClassDescription("Input message")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InputMessage {

    private final String role;

    private final List<MessagePart> parts;

    private final String name;

    @JsonProperty(required = true, value = "role")
    @JsonPropertyDescription("Role of the entity that created the message")
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

    public static InputMessage create(String role, List<MessagePart> parts, String name) {
        return new InputMessage(role, parts, name);
    }

    public static InputMessage create(Role role, List<MessagePart> parts, String name) {
        return new InputMessage(role.getValue(), parts, name);
    }

    private InputMessage(String role, List<MessagePart> parts, String name) {
        this.role = role;
        this.parts = parts;
        this.name = name;
    }
}
