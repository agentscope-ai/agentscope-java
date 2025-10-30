/*
 * Copyright 2024-2025 the original author or authors.
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

package io.agentscope.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

public class ResponseFormatTest {

    @Test
    void testJsonObject() {
        ResponseFormat format = ResponseFormat.jsonObject();

        assertNotNull(format);
        assertEquals("json_object", format.getType());
        assertNull(format.getJsonSchema());
        assertTrue(format.isJsonObject());
        assertFalse(format.isJsonSchema());
    }

    @Test
    void testJsonSchema() {
        JsonSchemaSpec schema =
                JsonSchemaSpec.builder().name("test").schema(Map.of("type", "object")).build();

        ResponseFormat format = ResponseFormat.jsonSchema(schema);

        assertNotNull(format);
        assertEquals("json_schema", format.getType());
        assertEquals(schema, format.getJsonSchema());
        assertFalse(format.isJsonObject());
        assertTrue(format.isJsonSchema());
    }

    @Test
    void testJsonSchemaWithNull() {
        assertThrows(IllegalArgumentException.class, () -> ResponseFormat.jsonSchema(null));
    }

    @Test
    void testIsJsonObject() {
        ResponseFormat format = ResponseFormat.jsonObject();
        assertTrue(format.isJsonObject());
        assertFalse(format.isJsonSchema());
    }

    @Test
    void testIsJsonSchema() {
        JsonSchemaSpec schema =
                JsonSchemaSpec.builder().name("test").schema(Map.of("type", "object")).build();
        ResponseFormat format = ResponseFormat.jsonSchema(schema);

        assertTrue(format.isJsonSchema());
        assertFalse(format.isJsonObject());
    }

    @Test
    void testJsonSchemaIntegration() {
        // Create a complex schema
        Map<String, Object> schemaMap =
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of(
                                "employees",
                                Map.of("type", "array", "items", Map.of("type", "string"))),
                        "required",
                        java.util.List.of("employees"));

        JsonSchemaSpec schema =
                JsonSchemaSpec.builder()
                        .name("employee_list")
                        .schema(schemaMap)
                        .strict(true)
                        .build();

        ResponseFormat format = ResponseFormat.jsonSchema(schema);

        assertEquals("json_schema", format.getType());
        assertNotNull(format.getJsonSchema());
        assertEquals("employee_list", format.getJsonSchema().getName());
        assertEquals(true, format.getJsonSchema().getStrict());
    }
}
