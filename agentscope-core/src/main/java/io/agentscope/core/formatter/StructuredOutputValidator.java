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

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.ArrayList;
import java.util.List;

/**
 * Response-side JSON Schema validation for structured outputs.
 *
 * <p>The request side is covered by {@link ResponseFormat} + {@link JsonSchema}
 * (model constraint); this class adds the <em>platform-side check</em>: it
 * validates the model's actual output against the same schema and returns
 * actionable errors (instance location + message).
 *
 * <p>Schemas are compiled through a shared {@link SchemaRegistry}, whose
 * built-in cache (keyed by the schema JSON string) makes repeated validation
 * cheap.
 */
public final class StructuredOutputValidator {

    private static final SchemaRegistry REGISTRY =
            SchemaRegistry.withDefaultDialect(
                    SpecificationVersion.DRAFT_2020_12,
                    builder ->
                            // Enable format assertions so "format": "email" / "date-time"
                            // are really checked (disabled by default, which would let
                            // malformed format values pass).
                            builder.schemaRegistryConfig(
                                    com.networknt.schema.SchemaRegistryConfig.builder()
                                            .formatAssertionsEnabled(true)
                                            .build()));

    private StructuredOutputValidator() {}

    /**
     * Validates a model output against a schema.
     *
     * @param output the parsed model output (JSON object)
     * @param schema the schema to validate against
     * @return the list of validation errors; empty when the output conforms
     */
    public static List<ValidationError> validate(JsonNode output, JsonSchema schema) {
        // Fail-closed: a missing schema is a configuration error, not a pass.
        if (schema == null || schema.getSchema() == null) {
            throw new IllegalArgumentException(
                    "structured_output_schema_required: schema must not be null");
        }
        if (output == null) {
            return List.of(new ValidationError("$", "output is null"));
        }
        Schema compiled = REGISTRY.getSchema(canonical(schema.getSchema()));
        List<Error> messages = compiled.validate(output);
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<ValidationError> errors = new ArrayList<>(messages.size());
        for (Error message : messages) {
            errors.add(
                    new ValidationError(
                            message.getInstanceLocation() == null
                                    ? "#"
                                    : message.getInstanceLocation().toString(),
                            message.getMessage()));
        }
        return List.copyOf(errors);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static String canonical(Object schemaMap) {
        try {
            return MAPPER.writeValueAsString(schemaMap);
        } catch (Exception e) {
            return String.valueOf(schemaMap);
        }
    }

    /** A single validation error: JSON instance location + human-readable message. */
    public record ValidationError(String instanceLocation, String message) {}
}
