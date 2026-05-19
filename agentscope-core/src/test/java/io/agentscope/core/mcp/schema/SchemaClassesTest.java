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

package io.agentscope.core.mcp.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ContentBlockTest {

    @Test
    void testContentBlockCreation() {
        ContentBlock block = new ContentBlock();
        assertNotNull(block);
    }

    @Test
    void testContentBlockIsRecord() {
        ContentBlock block = new ContentBlock();
        assertTrue(block.getClass().isRecord());
    }
}

class ToolDefinitionTest {

    @Test
    void testToolDefinitionCreation() {
        Optional<String> description = Optional.of("Test tool");
        Map<String, Object> inputSchema = new HashMap<>();
        inputSchema.put("type", "object");
        ToolDefinition tool = new ToolDefinition(description, inputSchema, "test-tool");
        assertEquals("test-tool", tool.name());
        assertEquals(description, tool.description());
        assertEquals(inputSchema, tool.inputSchema());
    }

    @Test
    void testToolDefinitionBuilder() {
        ToolDefinition tool =
                ToolDefinition.builder()
                        .Name("calculator.add")
                        .Description("Add two numbers")
                        .InputSchema(new HashMap<>())
                        .build();
        assertEquals("calculator.add", tool.name());
        assertEquals("Add two numbers", tool.description().get());
    }

    @Test
    void testToolDefinitionBuilderOptionalFields() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        ToolDefinition tool = ToolDefinition.builder().Name("tool1").InputSchema(schema).build();
        assertEquals("tool1", tool.name());
        assertTrue(tool.description().isEmpty());
    }

    @Test
    void testToolDefinitionEquality() {
        Map<String, Object> schema = new HashMap<>();
        ToolDefinition tool1 = new ToolDefinition(Optional.of("Desc"), schema, "calculator");
        ToolDefinition tool2 = new ToolDefinition(Optional.of("Desc"), schema, "calculator");
        assertEquals(tool1, tool2);
    }
}
