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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Unit tests for Toolkit external tool support.
 *
 * <p>Tests verify registration of schema-only tools and isExternalTool detection.
 */
@Tag("unit")
@DisplayName("Toolkit External Tool Support Tests")
class ExternalToolSupportTest {

    private Toolkit toolkit;

    @BeforeEach
    void setUp() {
        toolkit = new Toolkit();
    }

    @Test
    @DisplayName("Should register external tool via registerSchema")
    void testRegisterSchema() {
        ToolSchema schema =
                ToolSchema.builder()
                        .name("external_api")
                        .description("Call external API")
                        .parameters(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of("endpoint", Map.of("type", "string"))))
                        .build();

        toolkit.registerSchema(schema);

        // Verify tool is registered
        AgentTool tool = toolkit.getTool("external_api");
        assertNotNull(tool);
        assertTrue(tool instanceof SchemaOnlyTool);
        assertEquals("external_api", tool.getName());
        assertEquals("Call external API", tool.getDescription());
    }

    @Test
    @DisplayName("Should register multiple external tools via registerSchemas")
    void testRegisterSchemas() {
        List<ToolSchema> schemas =
                List.of(
                        ToolSchema.builder()
                                .name("tool_a")
                                .description("Tool A")
                                .parameters(Map.of("type", "object"))
                                .build(),
                        ToolSchema.builder()
                                .name("tool_b")
                                .description("Tool B")
                                .parameters(Map.of("type", "object"))
                                .build());

        toolkit.registerSchemas(schemas);

        assertNotNull(toolkit.getTool("tool_a"));
        assertNotNull(toolkit.getTool("tool_b"));
        assertTrue(toolkit.isExternalTool("tool_a"));
        assertTrue(toolkit.isExternalTool("tool_b"));
    }

    @Test
    @DisplayName("Should identify external tool via isExternalTool")
    void testIsExternalTool() {
        // Register an external tool
        ToolSchema externalSchema =
                ToolSchema.builder()
                        .name("external_tool")
                        .description("External")
                        .parameters(Map.of("type", "object"))
                        .build();
        toolkit.registerSchema(externalSchema);

        // Register an internal tool
        toolkit.registerAgentTool(
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "internal_tool";
                    }

                    @Override
                    public String getDescription() {
                        return "Internal";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        return Map.of("type", "object");
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        return Mono.just(ToolResultBlock.text("result"));
                    }
                });

        assertTrue(toolkit.isExternalTool("external_tool"));
        assertFalse(toolkit.isExternalTool("internal_tool"));
        assertFalse(toolkit.isExternalTool("nonexistent_tool"));
    }

    @Test
    @DisplayName("Should include external tools in tool schemas")
    void testExternalToolInSchemas() {
        ToolSchema schema =
                ToolSchema.builder()
                        .name("db_query")
                        .description("Query database")
                        .parameters(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of("sql", Map.of("type", "string")),
                                        "required",
                                        List.of("sql")))
                        .build();

        toolkit.registerSchema(schema);

        List<ToolSchema> schemas = toolkit.getToolSchemas();
        assertEquals(1, schemas.size());
        assertEquals("db_query", schemas.get(0).getName());
    }

    @Test
    @DisplayName("Should handle null schemas list gracefully")
    void testRegisterSchemasNull() {
        // Should not throw
        toolkit.registerSchemas(null);
        assertTrue(toolkit.getToolNames().isEmpty());
    }

    @Test
    @DisplayName("Should handle empty schemas list")
    void testRegisterSchemasEmpty() {
        toolkit.registerSchemas(List.of());
        assertTrue(toolkit.getToolNames().isEmpty());
    }

    @Test
    @DisplayName("Should mix external and internal tools")
    void testMixedTools() {
        // Register external tool
        toolkit.registerSchema(
                ToolSchema.builder()
                        .name("external")
                        .description("External tool")
                        .parameters(Map.of("type", "object"))
                        .build());

        // Register internal tool via annotation
        toolkit.registerTool(new InternalToolExample());

        // Verify both are registered
        assertEquals(2, toolkit.getToolNames().size());
        assertTrue(toolkit.isExternalTool("external"));
        assertFalse(toolkit.isExternalTool("calculator"));

        // Verify schemas include both
        List<ToolSchema> schemas = toolkit.getToolSchemas();
        assertEquals(2, schemas.size());
    }

    /** Sample internal tool for testing. */
    static class InternalToolExample {
        @Tool(name = "calculator", description = "Calculate expression")
        public String calculate(@ToolParam(name = "expression") String expression) {
            return "result";
        }
    }
}
