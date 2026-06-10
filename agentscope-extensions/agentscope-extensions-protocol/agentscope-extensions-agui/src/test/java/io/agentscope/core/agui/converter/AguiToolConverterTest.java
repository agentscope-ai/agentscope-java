/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
package io.agentscope.core.agui.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.core.agui.model.AguiTool;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for AguiToolConverter.
 */
class AguiToolConverterTest {

    private AguiToolConverter converter;

    @BeforeEach
    void setUp() {
        converter = new AguiToolConverter();
    }

    @Test
    void testConvertAguiToolToToolSchema() {
        Map<String, Object> params =
                Map.of(
                        "type", "object",
                        "properties",
                                Map.of(
                                        "city",
                                        Map.of(
                                                "type", "string",
                                                "description", "The city name")),
                        "required", List.of("city"));

        AguiTool aguiTool = new AguiTool("get_weather", "Get weather for a city", params);

        ToolSchema schema = converter.toToolSchema(aguiTool);

        assertEquals("get_weather", schema.getName());
        assertEquals("Get weather for a city", schema.getDescription());
        assertEquals("object", schema.getParameters().get("type"));
    }

    @Test
    void testConvertToolSchemaToAguiTool() {
        Map<String, Object> params =
                Map.of(
                        "type", "object",
                        "properties",
                                Map.of(
                                        "expression",
                                        Map.of(
                                                "type", "string",
                                                "description", "Math expression")),
                        "required", List.of("expression"));

        ToolSchema schema =
                ToolSchema.builder()
                        .name("calculate")
                        .description("Perform calculation")
                        .parameters(params)
                        .build();

        AguiTool aguiTool = converter.toAguiTool(schema);

        assertEquals("calculate", aguiTool.getName());
        assertEquals("Perform calculation", aguiTool.getDescription());
        assertEquals("object", aguiTool.getParameters().get("type"));
    }

    @Test
    void testConvertListOfTools() {
        List<AguiTool> aguiTools =
                List.of(
                        new AguiTool("tool1", "Description 1", Map.of("type", "object")),
                        new AguiTool("tool2", "Description 2", Map.of("type", "object")));

        List<ToolSchema> schemas = converter.toToolSchemaList(aguiTools);

        assertEquals(2, schemas.size());
        assertEquals("tool1", schemas.get(0).getName());
        assertEquals("tool2", schemas.get(1).getName());
    }

    @Test
    void testRoundTripConversion() {
        Map<String, Object> params = Map.of("type", "object", "properties", Map.of());
        AguiTool original = new AguiTool("test_tool", "Test description", params);

        ToolSchema schema = converter.toToolSchema(original);
        AguiTool converted = converter.toAguiTool(schema);

        assertEquals(original.getName(), converted.getName());
        assertEquals(original.getDescription(), converted.getDescription());
    }
}
