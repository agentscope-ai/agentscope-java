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
import io.agentscope.core.mcp.schema.CallToolResult;
import io.agentscope.core.mcp.tool.Tool;
import io.agentscope.core.mcp.tool.ToolManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CallToolHandlerTest {

    private CallToolHandler handler;
    private ToolManager toolManager;

    @BeforeEach
    void setUp() {
        toolManager = new ToolManager();
        handler = new CallToolHandler(toolManager);
    }

    @Test
    void callLocalTool() throws Exception {
        Tool fake =
                new Tool() {
                    @Override
                    public String getName() {
                        return "echo";
                    }

                    @Override
                    public String getDescription() {
                        return "Echo tool";
                    }

                    @Override
                    public Map<String, Object> getInputSchema() {
                        return Map.of();
                    }

                    @Override
                    public Object execute(Object arguments) {
                        return "OK:" + (arguments == null ? "" : arguments.toString());
                    }
                };

        toolManager.register(fake);

        Map<String, Object> params = new HashMap<>();
        params.put("name", "echo");
        params.put("arguments", Map.of("msg", "hello"));

        Object res = handler.handle(params);
        assertEquals(CallToolResult.class, res.getClass());
        CallToolResult ctr = (CallToolResult) res;
        List<Object> content = ctr.content();
        assertEquals(1, content.size());
        Object block = content.get(0);
        assertEquals(true, block.toString().contains("OK:"));
    }

    @Test
    void callToolWithMapResult() throws Exception {
        Tool mapTool =
                new Tool() {
                    @Override
                    public String getName() {
                        return "map.tool";
                    }

                    @Override
                    public String getDescription() {
                        return "Returns a map";
                    }

                    @Override
                    public Map<String, Object> getInputSchema() {
                        return Map.of();
                    }

                    @Override
                    public Object execute(Object arguments) {
                        return Map.of("key1", "value1", "key2", "value2");
                    }
                };

        toolManager.register(mapTool);

        Map<String, Object> params = new HashMap<>();
        params.put("name", "map.tool");
        params.put("arguments", Map.of());

        Object res = handler.handle(params);
        assertTrue(res instanceof CallToolResult);
        CallToolResult result = (CallToolResult) res;
        assertNotNull(result.content());
    }

    @Test
    void callNonExistentTool() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("name", "non.existent");
        params.put("arguments", Map.of());

        JsonRpcRequest request = new JsonRpcRequest("1", "tools/call", params);
        JsonRpcResponse response = handler.handleMessage(request);

        // Should have an error
        assertNotNull(response.getError());
    }

    @Test
    void callToolWithStringResult() throws Exception {
        Tool stringTool =
                new Tool() {
                    @Override
                    public String getName() {
                        return "string.tool";
                    }

                    @Override
                    public String getDescription() {
                        return "Returns string";
                    }

                    @Override
                    public Map<String, Object> getInputSchema() {
                        return Map.of();
                    }

                    @Override
                    public Object execute(Object arguments) throws Exception {
                        return "simple string result";
                    }
                };

        toolManager.register(stringTool);

        Map<String, Object> params = new HashMap<>();
        params.put("name", "string.tool");
        params.put("arguments", Map.of());

        JsonRpcRequest request = new JsonRpcRequest("2", "tools/call", params);
        JsonRpcResponse response = handler.handleMessage(request);

        CallToolResult result = (CallToolResult) response.getResult();
        assertNotNull(result.content());
        assertTrue(result.content().size() > 0);
    }
}
