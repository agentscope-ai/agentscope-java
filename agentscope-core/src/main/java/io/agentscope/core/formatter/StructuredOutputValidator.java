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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Response-side JSON Schema validation for structured outputs.
 *
 * <p>The request side is covered by {@link ResponseFormat} + {@link JsonSchema}
 * (model constraint); this class adds the <em>platform-side check</em>: it
 * validates the model's actual output against the same schema and returns
 * actionable errors (instance location + message).
 *
 * <p>Schemas are compiled once and cached (schema JSON canonical string as
 * key), so repeated validation is cheap.
 */
public final class StructuredOutputValidator {

    private static final SchemaRegistry REGISTRY =
            SchemaRegistry.withDefaultDialect(
                    SpecificationVersion.DRAFT_2020_12,
                    builder ->
                            // P2-2（维护者评审）：启用 format assertions（format: email / date-time
                            // 等真实校验，默认关闭会放行非法格式值）
                            builder.schemaRegistryConfig(
                                    com.networknt.schema.SchemaRegistryConfig.builder()
                                            .formatAssertionsEnabled(true)
                                            .build()));
    private static final ConcurrentHashMap<String, Schema> CACHE = new ConcurrentHashMap<>();

    private StructuredOutputValidator() {}

    /**
     * Validates a model output against a schema.
     *
     * @param output the parsed model output (JSON object)
     * @param schema the schema to validate against
     * @return the list of validation errors; empty when the output conforms
     */
    public static List<ValidationError> validate(JsonNode output, JsonSchema schema) {
        // P2-1（维护者评审）：fail-closed——schema 缺失是配置错误，直接抛出而非静默放行
        if (schema == null || schema.getSchema() == null) {
            throw new IllegalArgumentException(
                    "structured_output_schema_required: schema must not be null");
        }
        if (output == null) {
            return List.of(new ValidationError("$", "output is null"));
        }
        Schema compiled =
                CACHE.computeIfAbsent(
                        canonical(schema.getSchema()), json -> REGISTRY.getSchema(json));
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
