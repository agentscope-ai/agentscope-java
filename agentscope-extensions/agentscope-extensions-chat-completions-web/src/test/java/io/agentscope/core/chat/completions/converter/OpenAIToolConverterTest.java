/*
 * Copyright 2024-2026 the original author or authors.
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
package io.agentscope.core.chat.completions.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.chat.completions.model.OpenAITool;
import io.agentscope.core.chat.completions.model.OpenAIToolFunction;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OpenAIToolConverter}.
 */
@DisplayName("OpenAIToolConverter Tests")
class OpenAIToolConverterTest {

    private OpenAIToolConverter converter;

    @BeforeEach
    void setUp() {
        converter = new OpenAIToolConverter();
    }

    @Nested
    @DisplayName("Convert To ToolSchemas Tests")
    class ConvertToToolSchemasTests {

        @Test
        @DisplayName("Should convert valid function tool to ToolSchema")
        void shouldConvertValidFunctionToolToToolSchema() {
            OpenAIToolFunction function = new OpenAIToolFunction();
            function.setName("get_weather");
            function.setDescription("Get weather for a location");
            function.setParameters(
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of("location", Map.of("type", "string"))));

            OpenAITool tool = new OpenAITool(function);

            List<ToolSchema> schemas = converter.convertToToolSchemas(List.of(tool));

            assertEquals(1, schemas.size());
            ToolSchema schema = schemas.get(0);
            assertEquals("get_weather", schema.getName());
            assertEquals("Get weather for a location", schema.getDescription());
            assertNotNull(schema.getParameters());
        }

        @Test
        @DisplayName("Should return empty list for null input")
        void shouldReturnEmptyListForNullInput() {
            List<ToolSchema> schemas = converter.convertToToolSchemas(null);

            assertTrue(schemas.isEmpty());
        }

        @Test
        @DisplayName("Should return empty list for empty input")
        void shouldReturnEmptyListForEmptyInput() {
            List<ToolSchema> schemas = converter.convertToToolSchemas(List.of());

            assertTrue(schemas.isEmpty());
        }

        @Test
        @DisplayName("Should skip null tools in list")
        void shouldSkipNullToolsInList() {
            OpenAIToolFunction function = new OpenAIToolFunction();
            function.setName("valid_tool");
            function.setDescription("Valid");
            OpenAITool validTool = new OpenAITool(function);

            List<OpenAITool> tools = new ArrayList<>();
            tools.add(validTool);
            tools.add(null);

            List<ToolSchema> schemas = converter.convertToToolSchemas(tools);

            assertEquals(1, schemas.size());
            assertEquals("valid_tool", schemas.get(0).getName());
        }

        @Test
        @DisplayName("Should skip non-function type tools")
        void shouldSkipNonFunctionTypeTools() {
            OpenAITool tool = new OpenAITool();
            tool.setType("code_interpreter"); // Not supported

            List<ToolSchema> schemas = converter.convertToToolSchemas(List.of(tool));

            assertTrue(schemas.isEmpty());
        }

        @Test
        @DisplayName("Should skip tools with null function")
        void shouldSkipToolsWithNullFunction() {
            OpenAITool tool = new OpenAITool();
            tool.setType("function");
            tool.setFunction(null);

            List<ToolSchema> schemas = converter.convertToToolSchemas(List.of(tool));

            assertTrue(schemas.isEmpty());
        }

        @Test
        @DisplayName("Should skip tools with null or empty name")
        void shouldSkipToolsWithNullOrEmptyName() {
            OpenAIToolFunction function1 = new OpenAIToolFunction();
            function1.setName(null);
            function1.setDescription("Test");
            OpenAITool tool1 = new OpenAITool(function1);

            OpenAIToolFunction function2 = new OpenAIToolFunction();
            function2.setName("");
            function2.setDescription("Test");
            OpenAITool tool2 = new OpenAITool(function2);

            OpenAIToolFunction function3 = new OpenAIToolFunction();
            function3.setName("   ");
            function3.setDescription("Test");
            OpenAITool tool3 = new OpenAITool(function3);

            List<ToolSchema> schemas = converter.convertToToolSchemas(List.of(tool1, tool2, tool3));

            assertTrue(schemas.isEmpty());
        }

        @Test
        @DisplayName("Should use empty string for null or empty description")
        void shouldUseEmptyStringForNullOrEmptyDescription() {
            OpenAIToolFunction function1 = new OpenAIToolFunction();
            function1.setName("tool1");
            function1.setDescription(null);
            OpenAITool tool1 = new OpenAITool(function1);

            OpenAIToolFunction function2 = new OpenAIToolFunction();
            function2.setName("tool2");
            function2.setDescription("");
            OpenAITool tool2 = new OpenAITool(function2);

            List<ToolSchema> schemas = converter.convertToToolSchemas(List.of(tool1, tool2));

            assertEquals(2, schemas.size());
            assertEquals("", schemas.get(0).getDescription());
            assertEquals("", schemas.get(1).getDescription());
        }

        @Test
        @DisplayName("Should handle tools with null parameters")
        void shouldHandleToolsWithNullParameters() {
            OpenAIToolFunction function = new OpenAIToolFunction();
            function.setName("no_params");
            function.setDescription("No parameters");
            function.setParameters(null);

            OpenAITool tool = new OpenAITool(function);

            List<ToolSchema> schemas = converter.convertToToolSchemas(List.of(tool));

            assertEquals(1, schemas.size());
            // ToolSchema converts null parameters to empty map
            assertNotNull(schemas.get(0).getParameters());
            assertTrue(schemas.get(0).getParameters().isEmpty());
        }

        @Test
        @DisplayName("Should preserve strict parameter")
        void shouldPreserveStrictParameter() {
            OpenAIToolFunction function = new OpenAIToolFunction();
            function.setName("strict_tool");
            function.setDescription("Strict tool");
            function.setStrict(true);

            OpenAITool tool = new OpenAITool(function);

            List<ToolSchema> schemas = converter.convertToToolSchemas(List.of(tool));

            assertEquals(1, schemas.size());
            assertTrue(schemas.get(0).getStrict());
        }

        @Test
        @DisplayName("Should convert multiple tools")
        void shouldConvertMultipleTools() {
            OpenAIToolFunction function1 = new OpenAIToolFunction();
            function1.setName("tool1");
            function1.setDescription("Tool 1");
            OpenAITool tool1 = new OpenAITool(function1);

            OpenAIToolFunction function2 = new OpenAIToolFunction();
            function2.setName("tool2");
            function2.setDescription("Tool 2");
            OpenAITool tool2 = new OpenAITool(function2);

            List<ToolSchema> schemas = converter.convertToToolSchemas(List.of(tool1, tool2));

            assertEquals(2, schemas.size());
            assertEquals("tool1", schemas.get(0).getName());
            assertEquals("tool2", schemas.get(1).getName());
        }

        @Test
        @DisplayName("Should handle complex parameters")
        void shouldHandleComplexParameters() {
            Map<String, Object> properties = new HashMap<>();
            properties.put("location", Map.of("type", "string", "description", "City name"));
            properties.put(
                    "unit", Map.of("type", "string", "enum", List.of("celsius", "fahrenheit")));

            Map<String, Object> parameters = new HashMap<>();
            parameters.put("type", "object");
            parameters.put("properties", properties);
            parameters.put("required", List.of("location"));

            OpenAIToolFunction function = new OpenAIToolFunction();
            function.setName("get_weather");
            function.setDescription("Get weather");
            function.setParameters(parameters);

            OpenAITool tool = new OpenAITool(function);

            List<ToolSchema> schemas = converter.convertToToolSchemas(List.of(tool));

            assertEquals(1, schemas.size());
            assertNotNull(schemas.get(0).getParameters());
            assertEquals(parameters, schemas.get(0).getParameters());
        }
    }

    @Nested
    @DisplayName("Convert To ToolSchema Tests")
    class ConvertToToolSchemaTests {

        @Test
        @DisplayName("Should convert single tool to ToolSchema")
        void shouldConvertSingleToolToToolSchema() {
            OpenAIToolFunction function = new OpenAIToolFunction();
            function.setName("single_tool");
            function.setDescription("Single tool");

            OpenAITool tool = new OpenAITool(function);

            ToolSchema schema = converter.convertToToolSchema(tool);

            assertNotNull(schema);
            assertEquals("single_tool", schema.getName());
        }

        @Test
        @DisplayName("Should return null for null input")
        void shouldReturnNullForNullInput() {
            ToolSchema schema = converter.convertToToolSchema(null);

            assertNull(schema);
        }

        @Test
        @DisplayName("Should return null for invalid tool")
        void shouldReturnNullForInvalidTool() {
            OpenAITool tool = new OpenAITool();
            tool.setType("code_interpreter"); // Invalid type

            ToolSchema schema = converter.convertToToolSchema(tool);

            assertNull(schema);
        }
    }
}
