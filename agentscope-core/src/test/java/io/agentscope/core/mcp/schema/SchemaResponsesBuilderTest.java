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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SchemaResponsesBuilderTest {

    @Test
    void testInitializeResultBuilder() {
        Map<String, Object> capabilities = new HashMap<>();
        capabilities.put("tools", Map.of());
        Map<String, Object> serverInfo = new HashMap<>();
        serverInfo.put("name", "TestServer");

        InitializeResult result =
                InitializeResult.builder()
                        .ProtocolVersion("2024-11-05")
                        .Capabilities(capabilities)
                        .ServerInfo(serverInfo)
                        .build();

        assertNotNull(result);
        assertEquals("2024-11-05", result.protocolVersion());
        assertEquals(capabilities, result.capabilities());
        assertEquals(serverInfo, result.serverInfo());
    }

    @Test
    void testListToolsResultBuilder() {
        List<Object> tools = new ArrayList<>();
        Map<String, Object> tool = new HashMap<>();
        tool.put("name", "calculator");
        tools.add(tool);

        ListToolsResult result =
                ListToolsResult.builder().Tools(tools).NextCursor("next-page").build();

        assertNotNull(result);
        assertEquals(tools, result.tools());
        assertEquals("next-page", result.nextCursor().orElse(null));
    }

    @Test
    void testCallToolResultBuilder() {
        List<Object> content = new ArrayList<>();
        Map<String, Object> textBlock = new HashMap<>();
        textBlock.put("type", "text");
        textBlock.put("text", "42");
        content.add(textBlock);

        CallToolResult result = CallToolResult.builder().Content(content).IsError(false).build();

        assertNotNull(result);
        assertEquals(content, result.content());
        assertEquals(false, result.isError().orElse(null));
    }

    @Test
    void testCallToolResultWithError() {
        List<Object> content = new ArrayList<>();
        CallToolResult result = CallToolResult.builder().Content(content).IsError(true).build();

        assertNotNull(result);
        assertEquals(true, result.isError().orElse(null));
    }

    @Test
    void testToolDefinitionBuilder() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", new HashMap<>());

        ToolDefinition tool =
                ToolDefinition.builder()
                        .Name("add")
                        .Description("Add two numbers")
                        .InputSchema(schema)
                        .build();

        assertNotNull(tool);
        assertEquals("add", tool.name());
        assertTrue(tool.description().isPresent());
        assertEquals("Add two numbers", tool.description().get());
        assertEquals(schema, tool.inputSchema());
    }

    @Test
    void testToolDefinitionWithoutDescription() {
        Map<String, Object> schema = new HashMap<>();
        ToolDefinition tool = ToolDefinition.builder().Name("multiply").InputSchema(schema).build();

        assertNotNull(tool);
        assertEquals("multiply", tool.name());
        assertTrue(tool.description().isEmpty());
    }
}
