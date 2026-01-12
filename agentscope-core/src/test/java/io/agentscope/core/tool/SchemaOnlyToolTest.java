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
package io.agentscope.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * Unit tests for SchemaOnlyTool.
 *
 * <p>Tests verify that SchemaOnlyTool correctly implements AgentTool interface and throws
 * appropriate exception when callAsync is invoked.
 */
@Tag("unit")
@DisplayName("SchemaOnlyTool Unit Tests")
class SchemaOnlyToolTest {

    private ToolSchema testSchema;
    private SchemaOnlyTool schemaOnlyTool;

    @BeforeEach
    void setUp() {
        testSchema =
                ToolSchema.builder()
                        .name("query_database")
                        .description("Query an external database")
                        .parameters(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of("sql", Map.of("type", "string")),
                                        "required",
                                        List.of("sql")))
                        .build();
        schemaOnlyTool = new SchemaOnlyTool(testSchema);
    }

    @Test
    @DisplayName("Should create SchemaOnlyTool from ToolSchema")
    void testCreateFromToolSchema() {
        assertNotNull(schemaOnlyTool);
        assertEquals("query_database", schemaOnlyTool.getName());
        assertEquals("Query an external database", schemaOnlyTool.getDescription());
        assertNotNull(schemaOnlyTool.getParameters());
        assertEquals("object", schemaOnlyTool.getParameters().get("type"));
    }

    @Test
    @DisplayName("Should create SchemaOnlyTool with name, description, parameters")
    void testCreateWithParameters() {
        Map<String, Object> params =
                Map.of("type", "object", "properties", Map.of("id", Map.of("type", "integer")));

        SchemaOnlyTool tool = new SchemaOnlyTool("get_user", "Get user by ID", params);

        assertEquals("get_user", tool.getName());
        assertEquals("Get user by ID", tool.getDescription());
        assertEquals(params, tool.getParameters());
    }

    @Test
    @DisplayName("Should throw NullPointerException when schema is null")
    void testNullSchema() {
        assertThrows(NullPointerException.class, () -> new SchemaOnlyTool((ToolSchema) null));
    }

    @Test
    @DisplayName("Should throw NullPointerException when name is null")
    void testNullName() {
        assertThrows(
                NullPointerException.class,
                () -> new SchemaOnlyTool(null, "description", Map.of()));
    }

    @Test
    @DisplayName("Should throw NullPointerException when description is null")
    void testNullDescription() {
        assertThrows(NullPointerException.class, () -> new SchemaOnlyTool("name", null, Map.of()));
    }

    @Test
    @DisplayName("Should handle null parameters by using empty map")
    void testNullParameters() {
        SchemaOnlyTool tool = new SchemaOnlyTool("name", "description", null);
        assertNotNull(tool.getParameters());
        assertTrue(tool.getParameters().isEmpty());
    }

    @Test
    @DisplayName("Should throw UnsupportedOperationException on callAsync")
    void testCallAsyncThrowsException() {
        ToolUseBlock toolUse =
                ToolUseBlock.builder()
                        .id("test-id")
                        .name("query_database")
                        .input(Map.of("sql", "SELECT * FROM users"))
                        .build();

        ToolCallParam param =
                ToolCallParam.builder().toolUseBlock(toolUse).input(toolUse.getInput()).build();

        StepVerifier.create(schemaOnlyTool.callAsync(param))
                .expectError(ToolSuspendException.class)
                .verify();
    }

    @Test
    @DisplayName("Should have immutable parameters")
    void testParametersImmutability() {
        Map<String, Object> parameters = schemaOnlyTool.getParameters();
        assertThrows(UnsupportedOperationException.class, () -> parameters.put("new_key", "value"));
    }
}
