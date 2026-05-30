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
package io.agentscope.core.llm.interfacesweb.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.model.ToolSchema;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AnthropicToolConverter Tests")
class AnthropicToolConverterTest {

    @Test
    @DisplayName("Should convert Anthropic tools to AgentScope tool schemas")
    void shouldConvertAnthropicTools() {
        AnthropicTool tool = new AnthropicTool();
        tool.setName("get_weather");
        tool.setDescription("Get weather");
        tool.setInputSchema(Map.of("type", "object"));

        List<ToolSchema> schemas = new AnthropicToolConverter().convert(List.of(tool));

        assertEquals(1, schemas.size());
        assertEquals("get_weather", schemas.get(0).getName());
        assertEquals("Get weather", schemas.get(0).getDescription());
        assertEquals(Map.of("type", "object"), schemas.get(0).getParameters());
    }

    @Test
    @DisplayName("Should skip invalid tools and apply defaults")
    void shouldSkipInvalidToolsAndApplyDefaults() {
        AnthropicTool minimal = new AnthropicTool();
        minimal.setName("lookup");
        AnthropicTool blank = new AnthropicTool();
        blank.setName(" ");

        List<ToolSchema> schemas =
                new AnthropicToolConverter().convert(Arrays.asList(null, blank, minimal));

        assertTrue(new AnthropicToolConverter().convert(null).isEmpty());
        assertTrue(new AnthropicToolConverter().convert(List.of()).isEmpty());
        assertEquals(1, schemas.size());
        assertEquals("", schemas.get(0).getDescription());
        assertEquals(Map.of("type", "object"), schemas.get(0).getParameters());
    }
}
