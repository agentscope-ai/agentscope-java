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
package io.agentscope.core.formatter;

import java.util.List;

/**
 * Thrown when a model output fails JSON Schema validation after all retry
 * attempts are exhausted.
 *
 * <p>Carries the schema identity and the structured error list (instance
 * location + message per error), so callers can surface actionable details
 * to users or log them for diagnosis.
 */
public class StructuredOutputValidationException extends IllegalArgumentException {

    private final String schemaName;
    private final List<StructuredOutputValidator.ValidationError> errors;
    private final String parseErrorMessage;
    private final List<FailedAttempt> failedAttempts;

    public StructuredOutputValidationException(
            String schemaName, List<StructuredOutputValidator.ValidationError> errors) {
        this(schemaName, errors, null, List.of());
    }

    public StructuredOutputValidationException(
            String schemaName,
            List<StructuredOutputValidator.ValidationError> errors,
            String parseErrorMessage,
            List<FailedAttempt> failedAttempts) {
        super(buildMessage(schemaName, errors, parseErrorMessage));
        this.schemaName = schemaName;
        this.errors = errors == null ? List.of() : List.copyOf(errors);
        this.parseErrorMessage = parseErrorMessage;
        this.failedAttempts = failedAttempts == null ? List.of() : List.copyOf(failedAttempts);
    }

    private static String buildMessage(
            String schemaName,
            List<StructuredOutputValidator.ValidationError> errors,
            String parseErrorMessage) {
        StringBuilder sb = new StringBuilder("structured_output_schema_invalid");
        if (parseErrorMessage != null) {
            sb.append(" | ").append(parseErrorMessage);
        }
        if (schemaName != null && !schemaName.isBlank()) {
            sb.append(": schema=").append(schemaName);
        }
        int shown = 0;
        for (StructuredOutputValidator.ValidationError error : errors) {
            if (shown++ >= 3) {
                break;
            }
            sb.append(" | ").append(error.instanceLocation()).append(":").append(error.message());
        }
        return sb.toString();
    }

    public String getSchemaName() {
        return schemaName;
    }

    public List<StructuredOutputValidator.ValidationError> getErrors() {
        return errors;
    }

    public String getParseErrorMessage() {
        return parseErrorMessage;
    }

    /** All recorded failed attempts leading to this exception (chronological). */
    public List<FailedAttempt> getFailedAttempts() {
        return failedAttempts;
    }
}
