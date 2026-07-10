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
package io.agentscope.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ToolSchema}.
 */
@Tag("unit")
@DisplayName("ToolSchema Unit Tests")
class ToolSchemaTest {

    @Test
    @DisplayName("Should build schema with all fields including title")
    void testBuildWithTitle() {
        ToolSchema schema =
                ToolSchema.builder()
                        .name("calculator")
                        .title("Advanced Calculator")
                        .description("A powerful calculator tool")
                        .parameters(Map.of("type", "object"))
                        .strict(true)
                        .build();

        assertEquals("calculator", schema.getName());
        assertEquals("Advanced Calculator", schema.getTitle());
        assertEquals("A powerful calculator tool", schema.getDescription());
        assertNotNull(schema.getParameters());
        assertEquals(Boolean.TRUE, schema.getStrict());
    }

    @Test
    @DisplayName("Should return null title when not set")
    void testTitleNullByDefault() {
        ToolSchema schema =
                ToolSchema.builder().name("simple_tool").description("Simple tool").build();

        assertEquals("simple_tool", schema.getName());
        assertNull(schema.getTitle());
    }

    @Test
    @DisplayName("Should handle empty title")
    void testEmptyTitle() {
        ToolSchema schema = ToolSchema.builder().name("tool").title("").description("desc").build();

        assertEquals("", schema.getTitle());
    }

    @Test
    @DisplayName("Should require name")
    void testRequireName() {
        assertThrows(
                NullPointerException.class, () -> ToolSchema.builder().description("desc").build());
    }

    @Test
    @DisplayName("Should require description")
    void testRequireDescription() {
        assertThrows(NullPointerException.class, () -> ToolSchema.builder().name("tool").build());
    }

    @Test
    @DisplayName("Should have immutable parameters")
    void testParametersImmutability() {
        ToolSchema schema =
                ToolSchema.builder()
                        .name("tool")
                        .description("desc")
                        .parameters(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of("key", Map.of("type", "string")),
                                        "required",
                                        List.of("key")))
                        .build();

        assertThrows(
                UnsupportedOperationException.class,
                () -> schema.getParameters().put("extra", "value"));
    }

    @Test
    @DisplayName("Should have title distinct from name")
    void testTitleDistinctFromName() {
        ToolSchema schema =
                ToolSchema.builder()
                        .name("query_users_db")
                        .title("Query Users")
                        .description("Query the users database")
                        .build();

        assertEquals("query_users_db", schema.getName());
        assertEquals("Query Users", schema.getTitle());
        assertNotEquals(schema.getName(), schema.getTitle());
    }

    @Test
    @DisplayName("Should handle null strict mode")
    void testNullStrictMode() {
        ToolSchema schema = ToolSchema.builder().name("tool").description("desc").build();

        assertNull(schema.getStrict());
    }
}
