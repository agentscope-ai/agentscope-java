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
package io.agentscope.core.mcp.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.mcp.message.JsonRpcRequest;
import io.agentscope.core.mcp.message.JsonRpcResponse;
import io.agentscope.core.mcp.schema.ListToolsResult;
import io.agentscope.core.mcp.tool.Tool;
import io.agentscope.core.mcp.tool.ToolManager;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ListToolsHandlerTest {

    private ListToolsHandler handler;
    private ToolManager toolManager;

    @BeforeEach
    void setUp() {
        toolManager = new ToolManager();
        handler = new ListToolsHandler(toolManager);
    }

    @Test
    void testListEmptyTools() throws Exception {
        JsonRpcRequest request = new JsonRpcRequest("1", "tools/list", null);
        JsonRpcResponse response = handler.handleMessage(request);

        assertNotNull(response);
        assertTrue(response.getResult() instanceof ListToolsResult);
        ListToolsResult result = (ListToolsResult) response.getResult();
        assertEquals(0, result.tools().size());
    }

    @Test
    void testListToolsWithRegisteredTools() throws Exception {
        // Register a tool
        Tool mockTool =
                new Tool() {
                    @Override
                    public String getName() {
                        return "test.tool";
                    }

                    @Override
                    public String getDescription() {
                        return "A test tool";
                    }

                    @Override
                    public Map<String, Object> getInputSchema() {
                        return Map.of("type", "object");
                    }

                    @Override
                    public Object execute(Object arguments) throws Exception {
                        return null;
                    }
                };

        toolManager.register(mockTool);

        JsonRpcRequest request = new JsonRpcRequest("2", "tools/list", null);
        JsonRpcResponse response = handler.handleMessage(request);

        ListToolsResult result = (ListToolsResult) response.getResult();
        assertEquals(1, result.tools().size());
    }

    @Test
    void testListMultipleTools() throws Exception {
        // Register multiple tools
        for (int i = 0; i < 3; i++) {
            final int index = i;
            Tool tool =
                    new Tool() {
                        @Override
                        public String getName() {
                            return "tool." + index;
                        }

                        @Override
                        public String getDescription() {
                            return "Tool " + index;
                        }

                        @Override
                        public Map<String, Object> getInputSchema() {
                            return Map.of();
                        }

                        @Override
                        public Object execute(Object arguments) throws Exception {
                            return null;
                        }
                    };
            toolManager.register(tool);
        }

        JsonRpcRequest request = new JsonRpcRequest("3", "tools/list", null);
        JsonRpcResponse response = handler.handleMessage(request);

        ListToolsResult result = (ListToolsResult) response.getResult();
        assertEquals(3, result.tools().size());
    }

    @Test
    void testResponseIdPreserved() throws Exception {
        String requestId = "unique-id-12345";
        JsonRpcRequest request = new JsonRpcRequest(requestId, "tools/list", null);
        JsonRpcResponse response = handler.handleMessage(request);

        assertEquals(requestId, response.getId().orElse(null));
    }
}
