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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

public class JsonSchemaSpecTest {

    @Test
    void testBasicBuilder() {
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of());

        JsonSchemaSpec spec = JsonSchemaSpec.builder().name("test_schema").schema(schema).build();

        assertEquals("test_schema", spec.getName());
        assertEquals(schema, spec.getSchema());
        assertEquals(true, spec.getStrict()); // Default is true
    }

    @Test
    void testBuilderWithStrictMode() {
        Map<String, Object> schema = Map.of("type", "object");

        JsonSchemaSpec spec =
                JsonSchemaSpec.builder().name("test").schema(schema).strict(false).build();

        assertEquals(false, spec.getStrict());
    }

    @Test
    void testBuilderWithoutName() {
        Map<String, Object> schema = Map.of("type", "object");

        assertThrows(
                IllegalArgumentException.class,
                () -> JsonSchemaSpec.builder().schema(schema).build());
    }

    @Test
    void testBuilderWithoutSchema() {
        assertThrows(
                IllegalArgumentException.class,
                () -> JsonSchemaSpec.builder().name("test").build());
    }

    @Test
    void testBuilderWithEmptyName() {
        Map<String, Object> schema = Map.of("type", "object");

        assertThrows(
                IllegalArgumentException.class,
                () -> JsonSchemaSpec.builder().name("").schema(schema).build());
    }

    @Test
    void testBuilderWithEmptySchema() {
        assertThrows(
                IllegalArgumentException.class,
                () -> JsonSchemaSpec.builder().name("test").schema(Map.of()).build());
    }

    @Test
    void testImmutableSchema() {
        Map<String, Object> schema = Map.of("type", "object");
        JsonSchemaSpec spec = JsonSchemaSpec.builder().name("test").schema(schema).build();

        // Get the schema and verify it's immutable
        Map<String, Object> retrievedSchema = spec.getSchema();
        assertThrows(
                UnsupportedOperationException.class, () -> retrievedSchema.put("new_key", "value"));
    }

    @Test
    void testComplexSchema() {
        Map<String, Object> schema =
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of("name", Map.of("type", "string"), "age", Map.of("type", "integer")),
                        "required",
                        java.util.List.of("name"));

        JsonSchemaSpec spec =
                JsonSchemaSpec.builder().name("person").schema(schema).strict(true).build();

        assertNotNull(spec);
        assertEquals("person", spec.getName());
        assertTrue(spec.getStrict());
        assertEquals(3, spec.getSchema().size());
    }

    @Test
    void testStrictNull() {
        Map<String, Object> schema = Map.of("type", "object");

        JsonSchemaSpec spec =
                JsonSchemaSpec.builder().name("test").schema(schema).strict(null).build();

        assertNull(spec.getStrict());
    }
}
