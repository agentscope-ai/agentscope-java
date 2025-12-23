/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.agentscope.extensions.aigateway.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SearchedToolTest {

    @Test
    void testDefaultConstructor() {
        SearchedTool tool = new SearchedTool();

        assertNotNull(tool);
        assertNull(tool.getName());
        assertNull(tool.getTitle());
        assertNull(tool.getDescription());
        assertNull(tool.getInputSchema());
        assertNull(tool.getOutputSchema());
    }

    @Test
    void testAllArgsConstructor() {
        Map<String, Object> inputSchema = new HashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", new HashMap<>());

        Map<String, Object> outputSchema = new HashMap<>();
        outputSchema.put("type", "string");

        SearchedTool tool =
                new SearchedTool(
                        "test_tool",
                        "Test Tool",
                        "A test tool description",
                        inputSchema,
                        outputSchema);

        assertEquals("test_tool", tool.getName());
        assertEquals("Test Tool", tool.getTitle());
        assertEquals("A test tool description", tool.getDescription());
        assertEquals(inputSchema, tool.getInputSchema());
        assertEquals(outputSchema, tool.getOutputSchema());
    }

    @Test
    void testSettersAndGetters() {
        SearchedTool tool = new SearchedTool();

        tool.setName("my_tool");
        tool.setTitle("My Tool");
        tool.setDescription("Description of my tool");

        Map<String, Object> inputSchema = new HashMap<>();
        inputSchema.put("type", "object");
        tool.setInputSchema(inputSchema);

        Map<String, Object> outputSchema = new HashMap<>();
        outputSchema.put("type", "array");
        tool.setOutputSchema(outputSchema);

        assertEquals("my_tool", tool.getName());
        assertEquals("My Tool", tool.getTitle());
        assertEquals("Description of my tool", tool.getDescription());
        assertEquals(inputSchema, tool.getInputSchema());
        assertEquals(outputSchema, tool.getOutputSchema());
    }

    @Test
    void testToString() {
        SearchedTool tool = new SearchedTool();
        tool.setName("weather_tool");
        tool.setTitle("Weather Tool");
        tool.setDescription("Get weather info");

        String str = tool.toString();

        assertNotNull(str);
        assertTrue(str.contains("weather_tool"));
        assertTrue(str.contains("Weather Tool"));
        assertTrue(str.contains("Get weather info"));
    }

    @Test
    void testToStringWithNullValues() {
        SearchedTool tool = new SearchedTool();

        String str = tool.toString();

        assertNotNull(str);
        assertTrue(str.contains("SearchedTool"));
    }

    @Test
    void testComplexInputSchema() {
        SearchedTool tool = new SearchedTool();

        Map<String, Object> cityProperty = new HashMap<>();
        cityProperty.put("type", "string");
        cityProperty.put("description", "City name");

        Map<String, Object> properties = new HashMap<>();
        properties.put("city", cityProperty);

        Map<String, Object> inputSchema = new HashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", properties);
        inputSchema.put("required", new String[] {"city"});

        tool.setInputSchema(inputSchema);

        Map<String, Object> result = tool.getInputSchema();
        assertEquals("object", result.get("type"));
        assertNotNull(result.get("properties"));
    }
}
