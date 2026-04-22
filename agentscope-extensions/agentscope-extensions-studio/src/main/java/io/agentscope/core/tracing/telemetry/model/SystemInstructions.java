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
