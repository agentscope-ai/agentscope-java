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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StructuredOutputValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonSchema schema() {
        return JsonSchema.builder()
                .name("MathResponse")
                .schema(
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of(
                                        "answer", Map.of("type", "number"),
                                        "steps",
                                                Map.of(
                                                        "type",
                                                        "array",
                                                        "items",
                                                        Map.of("type", "string"))),
                                "required",
                                List.of("answer", "steps"),
                                "additionalProperties",
                                false))
                .strict(true)
                .build();
    }

    @Test
    void validatesConformingOutput() throws Exception {
        JsonNode output =
                MAPPER.readTree(
                        """
                        {"answer": 42, "steps": ["parse", "compute"]}\
                        """);
        List<StructuredOutputValidator.ValidationError> errors =
                StructuredOutputValidator.validate(output, schema());
        assertTrue(errors.isEmpty());
    }

    @Test
    void reportsMissingRequiredFieldWithInstancePath() throws Exception {
        JsonNode output =
                MAPPER.readTree(
                        """
                        {"answer": 42}\
                        """);
        List<StructuredOutputValidator.ValidationError> errors =
                StructuredOutputValidator.validate(output, schema());
        assertFalse(errors.isEmpty());
        assertTrue(
                errors.stream()
                        .anyMatch(
                                e ->
                                        e.message().contains("steps")
                                                && e.instanceLocation() != null));
    }

    @Test
    void rejectsWrongTypeWithPath() throws Exception {
        JsonNode output =
                MAPPER.readTree(
                        """
                        {"answer": "not-a-number", "steps": []}\
                        """);
        List<StructuredOutputValidator.ValidationError> errors =
                StructuredOutputValidator.validate(output, schema());
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.instanceLocation().contains("answer")));
    }

    @Test
    void jsonSchemaConvenienceMethodMatchesValidator() throws Exception {
        JsonNode good =
                MAPPER.readTree(
                        """
                        {"answer": 1, "steps": ["a"]}\
                        """);
        JsonNode bad =
                MAPPER.readTree(
                        """
                        {"steps": ["a"]}\
                        """);
        assertTrue(schema().validate(good).isEmpty());
        assertFalse(schema().validate(bad).isEmpty());
    }

    @Test
    void exceptionCarriesSchemaNameAndErrors() throws Exception {
        JsonNode bad =
                MAPPER.readTree(
                        """
                        {"answer": 1}\
                        """);
        List<StructuredOutputValidator.ValidationError> errors =
                StructuredOutputValidator.validate(bad, schema());
        StructuredOutputValidationException ex =
                new StructuredOutputValidationException("MathResponse", errors);
        assertEquals("MathResponse", ex.getSchemaName());
        assertEquals(errors.size(), ex.getErrors().size());
        assertTrue(ex.getMessage().contains("MathResponse"));
        assertTrue(ex.getMessage().contains("steps"));
    }
}
