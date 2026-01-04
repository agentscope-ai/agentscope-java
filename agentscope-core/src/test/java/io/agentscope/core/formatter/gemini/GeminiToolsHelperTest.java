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
package io.agentscope.core.formatter.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.core.formatter.gemini.dto.GeminiTool;
import io.agentscope.core.formatter.gemini.dto.GeminiTool.GeminiFunctionDeclaration;
import io.agentscope.core.formatter.gemini.dto.GeminiToolConfig;
import io.agentscope.core.formatter.gemini.dto.GeminiToolConfig.GeminiFunctionCallingConfig;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for GeminiToolsHelper.
 */
class GeminiToolsHelperTest {

    private final GeminiToolsHelper helper = new GeminiToolsHelper();

    @Test
    void testConvertSimpleToolSchema() {
        // Create simple tool schema
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

        // Convert
        GeminiTool tool = helper.convertToGeminiTool(List.of(toolSchema));

        // Verify
        assertNotNull(tool);
        assertNotNull(tool.getFunctionDeclarations());
        assertEquals(1, tool.getFunctionDeclarations().size());

        GeminiFunctionDeclaration funcDecl = tool.getFunctionDeclarations().get(0);
        assertEquals("search", funcDecl.getName());
        assertEquals("Search for information", funcDecl.getDescription());

        // Verify parameters schema
        assertNotNull(funcDecl.getParameters());
        Map<String, Object> params = funcDecl.getParameters();
        assertEquals("object", params.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) params.get("properties");
        assertNotNull(props);
        assertNotNull(props.get("query"));
    }

    @Test
    void testConvertEmptyToolList() {
        GeminiTool tool = helper.convertToGeminiTool(List.of());
        assertNull(tool);

        tool = helper.convertToGeminiTool(null);
        assertNull(tool);
    }

    @Test
    void testToolChoiceAuto() {
        // Auto or null should return null (use default)
        GeminiToolConfig config = helper.convertToolChoice(new ToolChoice.Auto());
        assertNull(config);

        config = helper.convertToolChoice(null);
        assertNull(config);
    }

    @Test
    void testToolChoiceNone() {
        GeminiToolConfig config = helper.convertToolChoice(new ToolChoice.None());

        assertNotNull(config);
        assertNotNull(config.getFunctionCallingConfig());

        GeminiFunctionCallingConfig funcConfig = config.getFunctionCallingConfig();
        assertEquals("NONE", funcConfig.getMode());
    }

    @Test
    void testToolChoiceRequired() {
        GeminiToolConfig config = helper.convertToolChoice(new ToolChoice.Required());

        assertNotNull(config);
        assertNotNull(config.getFunctionCallingConfig());

        GeminiFunctionCallingConfig funcConfig = config.getFunctionCallingConfig();
        assertEquals("ANY", funcConfig.getMode());
    }

    @Test
    void testToolChoiceSpecific() {
        GeminiToolConfig config = helper.convertToolChoice(new ToolChoice.Specific("search"));

        assertNotNull(config);
        assertNotNull(config.getFunctionCallingConfig());

        GeminiFunctionCallingConfig funcConfig = config.getFunctionCallingConfig();
        assertEquals("ANY", funcConfig.getMode());

        assertNotNull(funcConfig.getAllowedFunctionNames());
        assertEquals(List.of("search"), funcConfig.getAllowedFunctionNames());
    }

    @Test
    void testConvertMultipleTools() {
        ToolSchema tool1 = ToolSchema.builder().name("search").description("Search tool").build();

        ToolSchema tool2 =
                ToolSchema.builder().name("calculate").description("Calculator tool").build();

        GeminiTool tool = helper.convertToGeminiTool(List.of(tool1, tool2));

        assertNotNull(tool);
        assertNotNull(tool.getFunctionDeclarations());
        assertEquals(2, tool.getFunctionDeclarations().size());

        List<GeminiFunctionDeclaration> funcDecls = tool.getFunctionDeclarations();
        assertEquals("search", funcDecls.get(0).getName());
        assertEquals("calculate", funcDecls.get(1).getName());
    }
}
