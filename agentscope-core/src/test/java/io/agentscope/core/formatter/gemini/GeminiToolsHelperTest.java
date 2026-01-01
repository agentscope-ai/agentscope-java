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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.genai.types.FunctionCallingConfig;
import com.google.genai.types.FunctionCallingConfigMode;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.genai.types.Tool;
import com.google.genai.types.ToolConfig;
import com.google.genai.types.Type;
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
        Tool tool = helper.convertToGeminiTool(List.of(toolSchema));

        // Verify
        assertNotNull(tool);
        assertTrue(tool.functionDeclarations().isPresent());
        assertEquals(1, tool.functionDeclarations().get().size());

        FunctionDeclaration funcDecl = tool.functionDeclarations().get().get(0);
        assertEquals("search", funcDecl.name().get());
        assertEquals("Search for information", funcDecl.description().get());

        // Verify parameters schema
        assertTrue(funcDecl.parameters().isPresent());
        Schema schema = funcDecl.parameters().get();
        assertEquals(Type.Known.OBJECT, schema.type().get().knownEnum());
        assertTrue(schema.properties().isPresent());
        assertTrue(schema.required().isPresent());
        assertEquals(List.of("query"), schema.required().get());
    }

    @Test
    void testConvertEmptyToolList() {
        Tool tool = helper.convertToGeminiTool(List.of());
        assertNull(tool);

        tool = helper.convertToGeminiTool(null);
        assertNull(tool);
    }

    @Test
    void testConvertParametersWithVariousTypes() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("name", Map.of("type", "string"));
        properties.put("age", Map.of("type", "integer"));
        properties.put("score", Map.of("type", "number"));
        properties.put("active", Map.of("type", "boolean"));
        properties.put("tags", Map.of("type", "array", "items", Map.of("type", "string")));

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);

        Schema schema = helper.convertParametersToSchema(parameters);

        assertNotNull(schema);
        assertEquals(Type.Known.OBJECT, schema.type().get().knownEnum());
        assertTrue(schema.properties().isPresent());

        Map<String, Schema> props = schema.properties().get();
        assertEquals(Type.Known.STRING, props.get("name").type().get().knownEnum());
        assertEquals(Type.Known.INTEGER, props.get("age").type().get().knownEnum());
        assertEquals(Type.Known.NUMBER, props.get("score").type().get().knownEnum());
        assertEquals(Type.Known.BOOLEAN, props.get("active").type().get().knownEnum());
        assertEquals(Type.Known.ARRAY, props.get("tags").type().get().knownEnum());
    }

    @Test
    void testToolChoiceAuto() {
        // Auto or null should return null (use default)
        ToolConfig config = helper.convertToolChoice(new ToolChoice.Auto());
        assertNull(config);

        config = helper.convertToolChoice(null);
        assertNull(config);
    }

    @Test
    void testToolChoiceNone() {
        ToolConfig config = helper.convertToolChoice(new ToolChoice.None());

        assertNotNull(config);
        assertTrue(config.functionCallingConfig().isPresent());

        FunctionCallingConfig funcConfig = config.functionCallingConfig().get();
        assertTrue(funcConfig.mode().isPresent());
        assertEquals(FunctionCallingConfigMode.Known.NONE, funcConfig.mode().get().knownEnum());
    }

    @Test
    void testToolChoiceRequired() {
        ToolConfig config = helper.convertToolChoice(new ToolChoice.Required());

        assertNotNull(config);
        assertTrue(config.functionCallingConfig().isPresent());

        FunctionCallingConfig funcConfig = config.functionCallingConfig().get();
        assertTrue(funcConfig.mode().isPresent());
        assertEquals(FunctionCallingConfigMode.Known.ANY, funcConfig.mode().get().knownEnum());
    }

    @Test
    void testToolChoiceSpecific() {
        ToolConfig config = helper.convertToolChoice(new ToolChoice.Specific("search"));

        assertNotNull(config);
        assertTrue(config.functionCallingConfig().isPresent());

        FunctionCallingConfig funcConfig = config.functionCallingConfig().get();
        assertTrue(funcConfig.mode().isPresent());
        assertEquals(FunctionCallingConfigMode.Known.ANY, funcConfig.mode().get().knownEnum());

        assertTrue(funcConfig.allowedFunctionNames().isPresent());
        assertEquals(List.of("search"), funcConfig.allowedFunctionNames().get());
    }

    @Test
    void testConvertMultipleTools() {
        ToolSchema tool1 = ToolSchema.builder().name("search").description("Search tool").build();

        ToolSchema tool2 =
                ToolSchema.builder().name("calculate").description("Calculator tool").build();

        Tool tool = helper.convertToGeminiTool(List.of(tool1, tool2));

        assertNotNull(tool);
        assertTrue(tool.functionDeclarations().isPresent());
        assertEquals(2, tool.functionDeclarations().get().size());

        List<FunctionDeclaration> funcDecls = tool.functionDeclarations().get();
        assertEquals("search", funcDecls.get(0).name().get());
        assertEquals("calculate", funcDecls.get(1).name().get());
    }

    @Test
    void testConvertNestedParameters() {
        // Create nested object schema
        Map<String, Object> addressProps = new HashMap<>();
        addressProps.put("street", Map.of("type", "string"));
        addressProps.put("city", Map.of("type", "string"));

        Map<String, Object> properties = new HashMap<>();
        properties.put("name", Map.of("type", "string"));
        properties.put("address", Map.of("type", "object", "properties", addressProps));

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);

        Schema schema = helper.convertParametersToSchema(parameters);

        assertNotNull(schema);
        assertTrue(schema.properties().isPresent());

        Map<String, Schema> props = schema.properties().get();
        Schema addressSchema = props.get("address");
        assertNotNull(addressSchema);
        assertEquals(Type.Known.OBJECT, addressSchema.type().get().knownEnum());

        assertTrue(addressSchema.properties().isPresent());
        Map<String, Schema> addressNestedProps = addressSchema.properties().get();
        assertEquals(Type.Known.STRING, addressNestedProps.get("street").type().get().knownEnum());
        assertEquals(Type.Known.STRING, addressNestedProps.get("city").type().get().knownEnum());
    }
}
