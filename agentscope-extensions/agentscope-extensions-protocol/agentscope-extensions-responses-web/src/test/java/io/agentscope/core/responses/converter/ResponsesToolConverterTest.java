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
package io.agentscope.core.responses.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.responses.model.ResponsesTool;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ResponsesToolConverterTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ResponsesToolConverter converter;

    @BeforeEach
    void setUp() {
        converter = new ResponsesToolConverter();
    }

    @Test
    void shouldConvertFlatFunctionTool() throws Exception {
        ResponsesTool tool = new ResponsesTool();
        tool.setType("function");
        tool.setName("get_weather");
        tool.setDescription("Get weather for a city");
        tool.setStrict(true);
        tool.setParameters(
                OBJECT_MAPPER.readTree(
                        """
                        {
                          "type": "object",
                          "properties": {
                            "city": {"type": "string"}
                          },
                          "required": ["city"]
                        }
                        """));

        List<ToolSchema> schemas = converter.convertToToolSchemas(List.of(tool));

        assertEquals(1, schemas.size());
        assertEquals("get_weather", schemas.get(0).getName());
        assertEquals("Get weather for a city", schemas.get(0).getDescription());
        assertTrue(schemas.get(0).getStrict());
        assertEquals("object", schemas.get(0).getParameters().get("type"));
    }

    @Test
    void shouldIgnoreHostedToolsWhenNoAgentScopeBackendIsRegistered() {
        ResponsesTool tool = new ResponsesTool();
        tool.setType("web_search_preview");

        List<ToolSchema> schemas = converter.convertToToolSchemas(List.of(tool));

        assertTrue(schemas.isEmpty());
    }

    @Test
    void shouldConvertSupportedToolChoiceValues() throws Exception {
        assertInstanceOf(ToolChoice.Auto.class, converter.convertToolChoice(json("\"auto\"")));
        assertInstanceOf(ToolChoice.None.class, converter.convertToolChoice(json("\"none\"")));
        assertInstanceOf(
                ToolChoice.Required.class, converter.convertToolChoice(json("\"required\"")));

        ToolChoice choice =
                converter.convertToolChoice(
                        json(
                                """
                                {
                                  "type": "function",
                                  "name": "get_weather"
                                }
                                """));

        ToolChoice.Specific specific = assertInstanceOf(ToolChoice.Specific.class, choice);
        assertEquals("get_weather", specific.toolName());
    }

    @Test
    void shouldAcceptAllowedToolsAndIgnoreHostedToolChoices() throws Exception {
        ToolChoice.Specific functionChoice =
                assertInstanceOf(
                        ToolChoice.Specific.class,
                        converter.convertToolChoice(
                                json(
                                        """
                                        {
                                          "type": "function",
                                          "name": "get_weather",
                                          "allowed_tools": ["get_weather"]
                                        }
                                        """)));
        assertEquals("get_weather", functionChoice.toolName());

        assertNull(
                converter.convertToolChoice(
                        json(
                                """
                                {
                                  "type": "web_search_preview"
                                }
                                """)));
    }

    private JsonNode json(String json) throws Exception {
        return OBJECT_MAPPER.readTree(json);
    }
}
