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
package io.agentscope.core.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

/**
 * Describes the input an application can collect for a suspended tool call. The application
 * renders and validates the form and handles its submission. The descriptor can be persisted
 * with the pending interaction and must contain only schema data.
 *
 * @param type application-defined form type
 * @param responseSchema JSON Schema for the form submission
 */
public record ExternalExecutionForm(String type, ObjectNode responseSchema) {

    public ExternalExecutionForm {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Form type must be non-blank");
        }
        responseSchema = Objects.requireNonNull(responseSchema, "responseSchema").deepCopy();
    }

    @Override
    public ObjectNode responseSchema() {
        return responseSchema.deepCopy();
    }
}
