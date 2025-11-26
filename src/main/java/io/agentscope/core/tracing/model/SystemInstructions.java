/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.agentscope.core.tracing.model;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents the list of system instructions sent to the model. <br>
 * Thread unsafe model.
 */
@JsonClassDescription("System instructions")
public class SystemInstructions {

    private final List<MessagePart> parts;

    @JsonPropertyDescription("List of message parts that make up the system instructions")
    public List<MessagePart> getParts() {
        return this.parts;
    }

    public static SystemInstructions create(List<MessagePart> parts) {
        return new SystemInstructions(new ArrayList<>(parts));
    }

    public List<MessagePart> getSerializableObject() {
        return this.parts;
    }

    private SystemInstructions(List<MessagePart> parts) {
        this.parts = parts;
    }
}
