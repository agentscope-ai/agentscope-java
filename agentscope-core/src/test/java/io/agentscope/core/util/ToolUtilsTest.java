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
package io.agentscope.core.util;

import static io.agentscope.core.util.ToolUtils.resolveToolTitle;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

@DisplayName("ToolUtils Tests")
class ToolUtilsTest {

    private Toolkit toolkit;

    @BeforeEach
    void setUp() {
        toolkit = new Toolkit();
    }

    private AgentTool toolWithTitle(String name, String title) {
        return new AgentTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getTitle() {
                return title;
            }

            @Override
            public String getDescription() {
                return "desc";
            }

            @Override
            public Map<String, Object> getParameters() {
                return Map.of("type", "object", "properties", Map.of());
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.just(ToolResultBlock.text("ok"));
            }
        };
    }

    @Test
    @DisplayName("Should return tool title when tool exists and has title")
    void testResolveToolTitle_existingToolWithTitle() {
        toolkit.registerTool(toolWithTitle("my_tool", "My Tool"));
        assertEquals("My Tool", resolveToolTitle(toolkit, "my_tool"));
    }

    @Test
    @DisplayName("Should return tool name as title when tool overrides getTitle with getName")
    void testResolveToolTitle_toolNameAsTitle() {
        toolkit.registerTool(toolWithTitle("my_tool", "my_tool"));
        assertEquals("my_tool", resolveToolTitle(toolkit, "my_tool"));
    }

    @Test
    @DisplayName("Should return null when tool does not exist in toolkit")
    void testResolveToolTitle_toolNotFound() {
        assertNull(resolveToolTitle(toolkit, "nonexistent_tool"));
    }

    @Test
    @DisplayName("Should return null when toolName is null")
    void testResolveToolTitle_nullToolName() {
        assertNull(resolveToolTitle(toolkit, null));
    }

    @Test
    @DisplayName("Should return null title when tool getTitle returns null")
    void testResolveToolTitle_toolTitleIsNull() {
        toolkit.registerTool(toolWithTitle("my_tool", null));
        assertNull(resolveToolTitle(toolkit, "my_tool"));
    }

    @Test
    @DisplayName("Should handle toolkit with multiple tools and return correct title")
    void testResolveToolTitle_multipleTools() {
        toolkit.registerTool(toolWithTitle("tool_a", "Tool A"));
        toolkit.registerTool(toolWithTitle("tool_b", "Tool B"));
        toolkit.registerTool(toolWithTitle("tool_c", "Tool C"));

        assertEquals("Tool A", resolveToolTitle(toolkit, "tool_a"));
        assertEquals("Tool B", resolveToolTitle(toolkit, "tool_b"));
        assertEquals("Tool C", resolveToolTitle(toolkit, "tool_c"));
        assertNull(resolveToolTitle(toolkit, "tool_d"));
    }
}
