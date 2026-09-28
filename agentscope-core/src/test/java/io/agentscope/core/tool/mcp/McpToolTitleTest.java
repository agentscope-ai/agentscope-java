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
package io.agentscope.core.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.core.tool.AgentTool;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A registered MCP tool must keep the server's human-readable display title reachable through the
 * public {@link AgentTool} surface, so a caller rendering a permission confirmation can label the
 * operation for an end user instead of showing {@code repair__create_ticket}.
 */
class McpToolTitleTest {

    private McpClientWrapper clientWrapper;
    private Map<String, Object> parameters;

    @BeforeEach
    void setUp() {
        clientWrapper = McpClientWrapperTestSupport.mockWrapper("test-client", true);
        parameters = new HashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", new HashMap<>());
    }

    @Test
    void resolveDisplayTitle_prefersToolTitle() {
        McpSchema.Tool tool =
                new McpSchema.Tool(
                        "create_ticket",
                        "提交报修工单",
                        "Creates a repair ticket",
                        null,
                        null,
                        new McpSchema.ToolAnnotations(
                                "Annotation Title", null, null, null, null, null),
                        null);

        assertEquals("提交报修工单", McpTool.resolveDisplayTitle(tool));
    }

    @Test
    void resolveDisplayTitle_fallsBackToAnnotationTitle() {
        McpSchema.Tool tool =
                new McpSchema.Tool(
                        "create_ticket",
                        null,
                        "Creates a repair ticket",
                        null,
                        null,
                        new McpSchema.ToolAnnotations("提交报修工单", null, null, null, null, null),
                        null);

        assertEquals("提交报修工单", McpTool.resolveDisplayTitle(tool));
    }

    @Test
    void resolveDisplayTitle_skipsBlankTitles() {
        McpSchema.Tool blank =
                new McpSchema.Tool("create_ticket", "   ", "desc", null, null, null, null);
        assertNull(McpTool.resolveDisplayTitle(blank), "a blank title is no title");

        assertNull(
                McpTool.resolveDisplayTitle(null),
                "a missing tool definition must not fail the registration");
    }

    @Test
    void resolveDisplayTitle_absentAnnotationsYieldsNull() {
        McpSchema.Tool tool =
                new McpSchema.Tool("create_ticket", null, "desc", null, null, null, null);

        assertNull(McpTool.resolveDisplayTitle(tool));
    }

    @Test
    void registeredTool_exposesTitleThroughAgentToolSurface() {
        McpTool tool =
                new McpTool(
                        "mcp__create_ticket",
                        "create_ticket",
                        "Creates a repair ticket",
                        parameters,
                        null,
                        clientWrapper,
                        null,
                        "test-client",
                        false,
                        "提交报修工单");

        // Toolkit hands callers an AgentTool, so the title has to be reachable at that width.
        AgentTool asAgentTool = tool;
        assertEquals("提交报修工单", asAgentTool.getTitle());
        assertEquals(
                "mcp__create_ticket",
                asAgentTool.getName(),
                "the programmatic name stays untouched by the display title");
    }

    @Test
    void registeredToolWithoutTitle_returnsNullRatherThanThrowing() {
        McpTool tool =
                new McpTool(
                        "create_ticket",
                        "Creates a repair ticket",
                        parameters,
                        null,
                        clientWrapper,
                        null,
                        "test-client",
                        false);

        assertNull(tool.getTitle(), "servers that send no title keep working unchanged");
    }
}
