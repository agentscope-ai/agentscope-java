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
package io.agentscope.extensions.higress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HigressToolSearchResultTest {

    @Test
    void testParse_NullResult() {
        HigressToolSearchResult result = HigressToolSearchResult.parse(null);

        assertFalse(result.isSuccess());
        assertEquals("CallToolResult is null", result.getErrorMessage());
        assertTrue(result.getTools().isEmpty());
    }

    @Test
    void testParse_ErrorResult() {
        McpSchema.CallToolResult callToolResult =
                new McpSchema.CallToolResult(Collections.emptyList(), true, null);

        HigressToolSearchResult result = HigressToolSearchResult.parse(callToolResult);

        assertFalse(result.isSuccess());
        assertEquals("Tool call returned error", result.getErrorMessage());
        assertTrue(result.getTools().isEmpty());
    }

    @Test
    void testParse_EmptyContent() {
        McpSchema.CallToolResult callToolResult =
                new McpSchema.CallToolResult(Collections.emptyList(), false, null);

        HigressToolSearchResult result = HigressToolSearchResult.parse(callToolResult);

        assertFalse(result.isSuccess());
        assertEquals("No content in tool search result", result.getErrorMessage());
    }

    @Test
    void testParse_FromStructuredContent() {
        Map<String, Object> inputSchema = new HashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", Map.of("city", Map.of("type", "string")));
        inputSchema.put("required", List.of("city"));

        Map<String, Object> tool1 = new HashMap<>();
        tool1.put("name", "weather_tool");
        tool1.put("description", "Query weather");
        tool1.put("title", "Weather Tool");
        tool1.put("inputSchema", inputSchema);
        tool1.put("outputSchema", Collections.emptyMap());

        Map<String, Object> structuredContent = new HashMap<>();
        structuredContent.put("tools", List.of(tool1));

        McpSchema.CallToolResult callToolResult =
                new McpSchema.CallToolResult(Collections.emptyList(), false, structuredContent);

        HigressToolSearchResult result = HigressToolSearchResult.parse(callToolResult);

        assertTrue(result.isSuccess());
        assertNull(result.getErrorMessage());
        assertEquals(1, result.getTools().size());

        HigressToolSearchResult.ToolInfo toolInfo = result.getTools().get(0);
        assertEquals("weather_tool", toolInfo.name());
        assertEquals("Query weather", toolInfo.description());
        assertEquals("Weather Tool", toolInfo.title());
        assertNotNull(toolInfo.inputSchema());
    }

    @Test
    void testParse_FromStructuredContent_MultipleTools() {
        Map<String, Object> tool1 = new HashMap<>();
        tool1.put("name", "tool1");
        tool1.put("description", "Description 1");
        tool1.put("title", "Tool 1");

        Map<String, Object> tool2 = new HashMap<>();
        tool2.put("name", "tool2");
        tool2.put("description", "Description 2");
        tool2.put("title", "Tool 2");

        Map<String, Object> structuredContent = new HashMap<>();
        structuredContent.put("tools", List.of(tool1, tool2));

        McpSchema.CallToolResult callToolResult =
                new McpSchema.CallToolResult(Collections.emptyList(), false, structuredContent);

        HigressToolSearchResult result = HigressToolSearchResult.parse(callToolResult);

        assertTrue(result.isSuccess());
        assertEquals(2, result.getTools().size());
        assertEquals(List.of("tool1", "tool2"), result.getToolNames());
    }

    @Test
    void testParse_FromTextContent() {
        String jsonText =
                "{\"tools\":[{\"name\":\"map___weather\",\"description\":\"Query weather\","
                        + "\"title\":\"Weather\",\"inputSchema\":{\"type\":\"object\"},"
                        + "\"outputSchema\":{}}]}";

        McpSchema.TextContent textContent = new McpSchema.TextContent(jsonText);
        McpSchema.CallToolResult callToolResult =
                new McpSchema.CallToolResult(List.of(textContent), false, null);

        HigressToolSearchResult result = HigressToolSearchResult.parse(callToolResult);

        assertTrue(result.isSuccess());
        assertEquals(1, result.getTools().size());
        assertEquals("map___weather", result.getTools().get(0).name());
    }

    @Test
    void testParse_FromTextContent_InvalidJson() {
        McpSchema.TextContent textContent = new McpSchema.TextContent("invalid json");
        McpSchema.CallToolResult callToolResult =
                new McpSchema.CallToolResult(List.of(textContent), false, null);

        HigressToolSearchResult result = HigressToolSearchResult.parse(callToolResult);

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("Failed to parse tool search result"));
    }

    @Test
    void testParse_FromTextContent_EmptyText() {
        McpSchema.TextContent textContent = new McpSchema.TextContent("");
        McpSchema.CallToolResult callToolResult =
                new McpSchema.CallToolResult(List.of(textContent), false, null);

        HigressToolSearchResult result = HigressToolSearchResult.parse(callToolResult);

        assertFalse(result.isSuccess());
    }

    @Test
    void testParse_FromTextContent_NullText() {
        McpSchema.TextContent textContent = new McpSchema.TextContent((String) null);
        McpSchema.CallToolResult callToolResult =
                new McpSchema.CallToolResult(List.of(textContent), false, null);

        HigressToolSearchResult result = HigressToolSearchResult.parse(callToolResult);

        assertFalse(result.isSuccess());
    }

    @Test
    void testParse_StructuredContentPriorityOverContent() {
        // Both structuredContent and content are present
        // structuredContent should be parsed first
        Map<String, Object> tool = new HashMap<>();
        tool.put("name", "structured_tool");
        tool.put("description", "From structured");
        tool.put("title", "Structured");

        Map<String, Object> structuredContent = new HashMap<>();
        structuredContent.put("tools", List.of(tool));

        String jsonText =
                "{\"tools\":[{\"name\":\"content_tool\",\"description\":\"From content\","
                        + "\"title\":\"Content\"}]}";
        McpSchema.TextContent textContent = new McpSchema.TextContent(jsonText);

        McpSchema.CallToolResult callToolResult =
                new McpSchema.CallToolResult(List.of(textContent), false, structuredContent);

        HigressToolSearchResult result = HigressToolSearchResult.parse(callToolResult);

        assertTrue(result.isSuccess());
        assertEquals(1, result.getTools().size());
        assertEquals("structured_tool", result.getTools().get(0).name());
    }

    @Test
    void testParse_InvalidStructuredContent_FallbackToContent() {
        // Invalid structuredContent format, should fall back to content
        Map<String, Object> structuredContent = new HashMap<>();
        structuredContent.put("tools", "not a list"); // Invalid format

        String jsonText =
                "{\"tools\":[{\"name\":\"content_tool\",\"description\":\"From content\","
                        + "\"title\":\"Content\"}]}";
        McpSchema.TextContent textContent = new McpSchema.TextContent(jsonText);

        McpSchema.CallToolResult callToolResult =
                new McpSchema.CallToolResult(List.of(textContent), false, structuredContent);

        HigressToolSearchResult result = HigressToolSearchResult.parse(callToolResult);

        assertTrue(result.isSuccess());
        assertEquals("content_tool", result.getTools().get(0).name());
    }

    @Test
    void testParse_StructuredContent_FilterNonMapItems() {
        // Tools list contains non-Map items which should be filtered
        Map<String, Object> validTool = new HashMap<>();
        validTool.put("name", "valid_tool");
        validTool.put("description", "Valid");
        validTool.put("title", "Valid Tool");

        // Use Arrays.asList to allow null elements
        List<Object> toolsList = new java.util.ArrayList<>();
        toolsList.add(validTool);
        toolsList.add("invalid");
        toolsList.add(123);
        toolsList.add(null);

        Map<String, Object> structuredContent = new HashMap<>();
        structuredContent.put("tools", toolsList);

        McpSchema.CallToolResult callToolResult =
                new McpSchema.CallToolResult(Collections.emptyList(), false, structuredContent);

        HigressToolSearchResult result = HigressToolSearchResult.parse(callToolResult);

        assertTrue(result.isSuccess());
        assertEquals(1, result.getTools().size());
        assertEquals("valid_tool", result.getTools().get(0).name());
    }

    @Test
    void testGetToolNames() {
        Map<String, Object> tool1 = Map.of("name", "tool_a", "description", "A", "title", "A");
        Map<String, Object> tool2 = Map.of("name", "tool_b", "description", "B", "title", "B");
        Map<String, Object> tool3 = Map.of("name", "tool_c", "description", "C", "title", "C");

        Map<String, Object> structuredContent = new HashMap<>();
        structuredContent.put("tools", List.of(tool1, tool2, tool3));

        McpSchema.CallToolResult callToolResult =
                new McpSchema.CallToolResult(Collections.emptyList(), false, structuredContent);

        HigressToolSearchResult result = HigressToolSearchResult.parse(callToolResult);

        assertEquals(List.of("tool_a", "tool_b", "tool_c"), result.getToolNames());
    }

    @Test
    void testToString_Success() {
        Map<String, Object> tool = Map.of("name", "my_tool", "description", "Desc", "title", "My");

        Map<String, Object> structuredContent = new HashMap<>();
        structuredContent.put("tools", List.of(tool));

        McpSchema.CallToolResult callToolResult =
                new McpSchema.CallToolResult(Collections.emptyList(), false, structuredContent);

        HigressToolSearchResult result = HigressToolSearchResult.parse(callToolResult);

        String str = result.toString();
        assertTrue(str.contains("tools="));
        assertTrue(str.contains("my_tool"));
    }

    @Test
    void testToString_Error() {
        HigressToolSearchResult result = HigressToolSearchResult.parse(null);

        String str = result.toString();
        assertTrue(str.contains("error="));
        assertTrue(str.contains("CallToolResult is null"));
    }

    @Test
    void testToolInfo_AllFields() {
        Map<String, Object> inputSchema = new HashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put(
                "properties",
                Map.of("param1", Map.of("type", "string"), "param2", Map.of("type", "integer")));
        inputSchema.put("required", List.of("param1"));

        Map<String, Object> outputSchema = Map.of("type", "object");

        Map<String, Object> tool = new HashMap<>();
        tool.put("name", "full_tool");
        tool.put("description", "Full description");
        tool.put("title", "Full Title");
        tool.put("inputSchema", inputSchema);
        tool.put("outputSchema", outputSchema);

        Map<String, Object> structuredContent = Map.of("tools", List.of(tool));

        McpSchema.CallToolResult callToolResult =
                new McpSchema.CallToolResult(Collections.emptyList(), false, structuredContent);

        HigressToolSearchResult result = HigressToolSearchResult.parse(callToolResult);
        HigressToolSearchResult.ToolInfo toolInfo = result.getTools().get(0);

        assertEquals("full_tool", toolInfo.name());
        assertEquals("Full description", toolInfo.description());
        assertEquals("Full Title", toolInfo.title());
        assertNotNull(toolInfo.inputSchema());
        assertNotNull(toolInfo.outputSchema());
        assertEquals("object", toolInfo.inputSchema().get("type"));
    }

    @Test
    void testToolInfo_NullSchemas() {
        Map<String, Object> tool = new HashMap<>();
        tool.put("name", "simple_tool");
        tool.put("description", "Simple");
        tool.put("title", "Simple Tool");
        tool.put("inputSchema", null);
        tool.put("outputSchema", null);

        Map<String, Object> structuredContent = Map.of("tools", List.of(tool));

        McpSchema.CallToolResult callToolResult =
                new McpSchema.CallToolResult(Collections.emptyList(), false, structuredContent);

        HigressToolSearchResult result = HigressToolSearchResult.parse(callToolResult);
        HigressToolSearchResult.ToolInfo toolInfo = result.getTools().get(0);

        assertEquals("simple_tool", toolInfo.name());
        assertNull(toolInfo.inputSchema());
        assertNull(toolInfo.outputSchema());
    }

    @Test
    void testParse_EmptyToolsList() {
        Map<String, Object> structuredContent = Map.of("tools", Collections.emptyList());

        McpSchema.CallToolResult callToolResult =
                new McpSchema.CallToolResult(Collections.emptyList(), false, structuredContent);

        HigressToolSearchResult result = HigressToolSearchResult.parse(callToolResult);

        assertTrue(result.isSuccess());
        assertTrue(result.getTools().isEmpty());
        assertTrue(result.getToolNames().isEmpty());
    }
}
