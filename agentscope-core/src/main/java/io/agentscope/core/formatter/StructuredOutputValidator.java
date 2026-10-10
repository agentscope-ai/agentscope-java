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
import com.networknt.schema.SchemaException;
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

    // Deliberate divergence from ToolValidator's registry (which leaves format assertions
    // off): the platform-side check on the native path promises strict schema conformance,
    // so formats are enforced here — per review feedback on this PR. The synthetic-tool
    // validation can adopt the same strictness in a follow-up; until then the native path
    // is the stricter of the two by design.
    //
    // The registry's built-in cache is keyed by the serialized schema string. It is bounded
    // only when callers pass class-derived schemas; per-request dynamic schemas (e.g. AG-UI /
    // studio per-call structured output) would grow it without bound — do not route such
    // schemas through this helper until the cache has an explicit bound.
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

    /**
     * Marker message used on the unknown/transient failure path: the retry feedback is
     * deliberately neutral (the model's output may have been valid) instead of asserting a
     * schema mismatch, and exhausted retries rethrow the original cause so internal faults
     * surface instead of masquerading as model errors.
     */
    public static final String UNKNOWN_FAILURE_MARKER =
            "internal validation error (transient) — original exception is attached to the"
                    + " failed attempt";

    private StructuredOutputValidator() {}

    /**
     * Validates a model output against a schema.
     *
     * <p>Failure domains: an output/schema mismatch is reported as validation errors (model
     * domain — the caller may retry); configuration problems (missing schema, schema
     * compilation or registry failures) throw {@link StructuredOutputConfigurationException}
     * instead, so callers never spend retries on a configuration fault.
     *
     * @param output the parsed model output (JSON object)
     * @param schema the schema to validate against
     * @return the list of validation errors; empty when the output conforms
     * @throws StructuredOutputConfigurationException when the schema is missing or cannot be
     *     compiled (configuration error, fail-closed)
     */
    public static List<ValidationError> validate(JsonNode output, JsonSchema schema) {
        // Fail-closed: a missing schema is a configuration error, not a pass.
        if (schema == null || schema.getSchema() == null) {
            throw new StructuredOutputConfigurationException(
                    "structured_output_schema_required: schema must not be null");
        }
        if (output == null) {
            return List.of(new ValidationError("$", "output is null"));
        }
        Schema compiled;
        try {
            compiled = REGISTRY.getSchema(canonical(schema.getSchema()));
        } catch (SchemaException compileFailure) {
            // A schema that fails to compile is a configuration error: surface it as such
            // (with the original cause) instead of letting the caller treat it as a
            // model-output problem.
            throw new StructuredOutputConfigurationException(
                    "structured_output_schema_invalid: schema failed to compile — "
                            + compileFailure.getMessage(),
                    compileFailure);
        }
        List<Error> messages;
        try {
            messages = compiled.validate(output);
        } catch (SchemaException validationFailure) {
            // networknt can also fail lazily at validation time (e.g. an unresolvable
            // $ref): a schema/configuration fault, not a model-output mismatch —
            // classify it accordingly instead of leaking the raw library exception.
            throw new StructuredOutputConfigurationException(
                    "structured_output_schema_invalid: schema failed during validation — "
                            + validationFailure.getMessage(),
                    validationFailure);
        }
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
