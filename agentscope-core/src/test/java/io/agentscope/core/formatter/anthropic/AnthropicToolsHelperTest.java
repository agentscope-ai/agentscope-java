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
package io.agentscope.core.formatter.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.core.formatter.anthropic.dto.AnthropicRequest;
import io.agentscope.core.formatter.anthropic.dto.AnthropicTool;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for AnthropicToolsHelper. */
class AnthropicToolsHelperTest {

    @Test
    void testApplyToolsWithSimpleSchema() {
        AnthropicRequest request = new AnthropicRequest();
        request.setModel("claude-sonnet-4-5-20250929");
        request.setMaxTokens(1024);

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", Map.of("query", Map.of("type", "string")));
        parameters.put("required", List.of("query"));

        ToolSchema toolSchema =
                ToolSchema.builder()
                        .name("search")
                        .description("Search for information")
                        .parameters(parameters)
                        .build();

        GenerateOptions options = GenerateOptions.builder().build();
        AnthropicToolsHelper.applyTools(request, List.of(toolSchema), options);

        List<AnthropicTool> tools = request.getTools();
        assertNotNull(tools);
        assertEquals(1, tools.size());

        AnthropicTool tool = tools.get(0);
        assertEquals("search", tool.getName());
        assertEquals("Search for information", tool.getDescription());
        assertNotNull(tool.getInputSchema());
    }

    @Test
    void testApplyToolsWithMultipleSchemas() {
        AnthropicRequest request = new AnthropicRequest();

        ToolSchema schema1 =
                ToolSchema.builder()
                        .name("tool1")
                        .description("First tool")
                        .parameters(Map.of("type", "object"))
                        .build();

        ToolSchema schema2 =
                ToolSchema.builder()
                        .name("tool2")
                        .description("Second tool")
                        .parameters(Map.of("type", "object"))
                        .build();

        GenerateOptions options = GenerateOptions.builder().build();
        AnthropicToolsHelper.applyTools(request, List.of(schema1, schema2), options);

        List<AnthropicTool> tools = request.getTools();
        assertNotNull(tools);
        assertEquals(2, tools.size());
    }

    @Test
    void testApplyToolsWithNullOrEmptyList() {
        AnthropicRequest request = new AnthropicRequest();

        // Null list
        AnthropicToolsHelper.applyTools(request, null, null);
        assertNull(request.getTools());

        // Empty list
        request = new AnthropicRequest();
        AnthropicToolsHelper.applyTools(request, List.of(), null);
        assertNull(request.getTools());
    }

    @Test
    void testApplyToolChoiceAuto() {
        AnthropicRequest request = new AnthropicRequest();

        ToolSchema schema =
                ToolSchema.builder()
                        .name("search")
                        .description("Search")
                        .parameters(Map.of("type", "object"))
                        .build();

        GenerateOptions options =
                GenerateOptions.builder().toolChoice(new ToolChoice.Auto()).build();
        AnthropicToolsHelper.applyTools(request, List.of(schema), options);

        @SuppressWarnings("unchecked")
        Map<String, String> toolChoice = (Map<String, String>) request.getToolChoice();
        assertNotNull(toolChoice);
        assertEquals("auto", toolChoice.get("type"));
    }

    @Test
    void testApplyToolChoiceNone() {
        AnthropicRequest request = new AnthropicRequest();

        ToolSchema schema =
                ToolSchema.builder()
                        .name("search")
                        .description("Search")
                        .parameters(Map.of("type", "object"))
                        .build();

        GenerateOptions options =
                GenerateOptions.builder().toolChoice(new ToolChoice.None()).build();
        AnthropicToolsHelper.applyTools(request, List.of(schema), options);

        @SuppressWarnings("unchecked")
        Map<String, String> toolChoice = (Map<String, String>) request.getToolChoice();
        assertNotNull(toolChoice);
        // None maps to "any" in Anthropic implementation provided in
        // AnthropicToolsHelper.java
        assertEquals("any", toolChoice.get("type"));
    }

    @Test
    void testApplyToolChoiceRequired() {
        AnthropicRequest request = new AnthropicRequest();

        ToolSchema schema =
                ToolSchema.builder()
                        .name("search")
                        .description("Search")
                        .parameters(Map.of("type", "object"))
                        .build();

        GenerateOptions options =
                GenerateOptions.builder().toolChoice(new ToolChoice.Required()).build();
        AnthropicToolsHelper.applyTools(request, List.of(schema), options);

        @SuppressWarnings("unchecked")
        Map<String, String> toolChoice = (Map<String, String>) request.getToolChoice();
        assertNotNull(toolChoice);
        // Required maps to "any" in Anthropic implementation provided in
        // AnthropicToolsHelper.java
        assertEquals("any", toolChoice.get("type"));
    }

    @Test
    void testApplyToolChoiceSpecific() {
        AnthropicRequest request = new AnthropicRequest();

        ToolSchema schema =
                ToolSchema.builder()
                        .name("search")
                        .description("Search")
                        .parameters(Map.of("type", "object"))
                        .build();

        GenerateOptions options =
                GenerateOptions.builder().toolChoice(new ToolChoice.Specific("search")).build();
        AnthropicToolsHelper.applyTools(request, List.of(schema), options);

        @SuppressWarnings("unchecked")
        Map<String, String> toolChoice = (Map<String, String>) request.getToolChoice();
        assertNotNull(toolChoice);
        assertEquals("tool", toolChoice.get("type"));
        assertEquals("search", toolChoice.get("name"));
    }

    @Test
    void testApplyOptionsWithTemperature() {
        AnthropicRequest request = new AnthropicRequest();

        GenerateOptions options = GenerateOptions.builder().temperature(0.7).build();

        AnthropicToolsHelper.applyOptions(request, options, null);

        assertEquals(0.7, request.getTemperature(), 0.001);
    }

    @Test
    void testApplyOptionsWithTopP() {
        AnthropicRequest request = new AnthropicRequest();

        GenerateOptions options = GenerateOptions.builder().topP(0.9).build();

        AnthropicToolsHelper.applyOptions(request, options, null);

        assertEquals(0.9, request.getTopP(), 0.001);
    }

    @Test
    void testApplyOptionsWithMaxTokens() {
        AnthropicRequest request = new AnthropicRequest();

        GenerateOptions options = GenerateOptions.builder().maxTokens(2048).build();

        AnthropicToolsHelper.applyOptions(request, options, null);

        assertEquals(2048, request.getMaxTokens());
    }

    @Test
    void testApplyOptionsWithAllParameters() {
        AnthropicRequest request = new AnthropicRequest();

        GenerateOptions options =
                GenerateOptions.builder().temperature(0.8).topP(0.95).maxTokens(3000).build();

        AnthropicToolsHelper.applyOptions(request, options, null);

        assertEquals(0.8, request.getTemperature(), 0.001);
        assertEquals(0.95, request.getTopP(), 0.001);
        assertEquals(3000, request.getMaxTokens());
    }

    @Test
    void testApplyOptionsWithDefaultFallback() {
        AnthropicRequest request = new AnthropicRequest();

        GenerateOptions defaultOptions =
                GenerateOptions.builder().temperature(0.5).topP(0.9).build();

        // No options provided, should use default
        AnthropicToolsHelper.applyOptions(request, null, defaultOptions);

        assertEquals(0.5, request.getTemperature(), 0.001);
        assertEquals(0.9, request.getTopP(), 0.001);
    }

    @Test
    void testApplyOptionsOverridesDefault() {
        AnthropicRequest request = new AnthropicRequest();

        GenerateOptions options = GenerateOptions.builder().temperature(0.7).build();
        GenerateOptions defaultOptions = GenerateOptions.builder().temperature(0.5).build();

        // Options should override default
        AnthropicToolsHelper.applyOptions(request, options, defaultOptions);

        assertEquals(0.7, request.getTemperature(), 0.001);
    }

    @Test
    void testApplyToolsWithComplexParameters() {
        AnthropicRequest request = new AnthropicRequest();

        Map<String, Object> properties = new HashMap<>();
        properties.put("name", Map.of("type", "string", "description", "Person name"));
        properties.put("age", Map.of("type", "integer", "minimum", 0, "maximum", 150));
        properties.put("tags", Map.of("type", "array", "items", Map.of("type", "string")));

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", List.of("name"));

        ToolSchema schema =
                ToolSchema.builder()
                        .name("create_person")
                        .description("Create a person")
                        .parameters(parameters)
                        .build();

        AnthropicToolsHelper.applyTools(request, List.of(schema), null);

        List<AnthropicTool> tools = request.getTools();
        assertNotNull(tools);
        assertEquals(1, tools.size());
        AnthropicTool tool = tools.get(0);
        assertEquals("create_person", tool.getName());
        assertNotNull(tool.getInputSchema());
    }

    // ==================== New Parameters Tests ====================

    @Test
    void testApplyOptionsWithTopK() {
        AnthropicRequest request = new AnthropicRequest();

        GenerateOptions options = GenerateOptions.builder().topK(40).build();

        AnthropicToolsHelper.applyOptions(request, options, null);

        assertEquals(40, request.getTopK());
    }

    @Test
    void testApplyOptionsTopKFromDefaultOptions() {
        AnthropicRequest request = new AnthropicRequest();

        GenerateOptions options = GenerateOptions.builder().temperature(0.5).build();
        GenerateOptions defaultOptions = GenerateOptions.builder().topK(30).build();

        AnthropicToolsHelper.applyOptions(request, options, defaultOptions);

        assertEquals(0.5, request.getTemperature(), 0.001);
        assertEquals(30, request.getTopK());
    }
}
