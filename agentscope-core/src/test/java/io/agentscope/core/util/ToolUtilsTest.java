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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Unit tests for {@link ToolUtils}.
 */
@Tag("unit")
@DisplayName("ToolUtils Unit Tests")
class ToolUtilsTest {

    @Test
    @DisplayName("Should return null when toolName is null")
    void testResolveTitleWithNullToolName() {
        assertNull(ToolUtils.resolveToolTitle(new Toolkit(), null));
    }

    @Test
    @DisplayName("Should return null when toolkit is null")
    void testResolveTitleWithNullToolkit() {
        assertNull(ToolUtils.resolveToolTitle(null, "some_tool"));
    }

    @Test
    @DisplayName("Should return null when tool is not found in toolkit")
    void testResolveTitleWhenToolNotFound() {
        Toolkit toolkit = new Toolkit();
        assertNull(ToolUtils.resolveToolTitle(toolkit, "non_existent_tool"));
    }

    @Test
    @DisplayName("Should return tool title when tool exists with custom title")
    void testResolveTitleWhenToolHasTitle() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(stubTool("my_tool", "My Human-Readable Tool"));

        assertEquals("My Human-Readable Tool", ToolUtils.resolveToolTitle(toolkit, "my_tool"));
    }

    @Test
    @DisplayName("Should return null title when tool exists but title is null")
    void testResolveTitleWhenToolHasNullTitle() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(stubTool("null_title_tool", null));

        assertNull(ToolUtils.resolveToolTitle(toolkit, "null_title_tool"));
    }

    @Test
    @DisplayName("Should return null when both toolkit and toolName are null")
    void testResolveTitleWithBothNull() {
        assertNull(ToolUtils.resolveToolTitle(null, null));
    }

    private static AgentTool stubTool(String name, String title) {
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
                return "";
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.empty();
            }

            @Override
            public Map<String, Object> getParameters() {
                return Map.of();
            }
        };
    }
}
