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

package io.agentscope.harness.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WebToolsTest {
    private ToolResultBlock call(Object tool, String name, Map<String, Object> input) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tool);
        return toolkit.getTool(name)
                .callAsync(
                        ToolCallParam.builder()
                                .toolUseBlock(
                                        ToolUseBlock.builder()
                                                .id("web-call")
                                                .name(name)
                                                .input(input)
                                                .build())
                                .input(input)
                                .build())
                .block();
    }

    @Test
    void invalidUrlIsAToolError() {
        ToolResultBlock result =
                call(new WebTools.WebFetchTool(), "web_fetch", Map.of("url", "file:///tmp/data"));
        assertNotNull(result);
        assertEquals(ToolResultState.ERROR, result.getState());
    }

    @Test
    void missingSearchCredentialIsAToolError() {
        assumeTrue(
                System.getenv("TAVILY_API_KEY") == null
                        || System.getenv("TAVILY_API_KEY").isBlank());
        ToolResultBlock result =
                call(new WebTools.WebSearchTool(), "web_search", Map.of("query", "industry"));
        assertNotNull(result);
        assertEquals(ToolResultState.ERROR, result.getState());
    }
}
