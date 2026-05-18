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
import io.agentscope.core.mcp.schema.InitializeResult;
import io.agentscope.core.mcp.tool.ToolManager;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InitializeHandlerTest {

    private InitializeHandler handler;

    @BeforeEach
    void setUp() {
        handler = new InitializeHandler(new ToolManager());
    }

    @Test
    void testInitializeResponse() throws Exception {
        JsonRpcRequest request = new JsonRpcRequest("1", "initialize", null);
        JsonRpcResponse response = handler.handleMessage(request);

        assertNotNull(response);
        assertNotNull(response.getResult());
        assertTrue(response.getResult() instanceof InitializeResult);

        InitializeResult result = (InitializeResult) response.getResult();
        assertEquals("2024-11-05", result.protocolVersion());
        assertNotNull(result.capabilities());
        assertNotNull(result.serverInfo());
    }

    @Test
    void testCapabilitiesStructure() throws Exception {
        JsonRpcRequest request = new JsonRpcRequest("2", "initialize", null);
        JsonRpcResponse response = handler.handleMessage(request);

        InitializeResult result = (InitializeResult) response.getResult();
        Map<String, Object> capabilities = result.capabilities();

        assertTrue(capabilities.containsKey("tools"));
        assertTrue(capabilities.get("tools") instanceof Map);
    }

    @Test
    void testServerInfo() throws Exception {
        JsonRpcRequest request = new JsonRpcRequest("3", "initialize", null);
        JsonRpcResponse response = handler.handleMessage(request);

        InitializeResult result = (InitializeResult) response.getResult();
        Map<String, Object> serverInfo = result.serverInfo();

        assertTrue(serverInfo.containsKey("name"));
        assertTrue(serverInfo.containsKey("version"));
        assertEquals("agentscope-core", serverInfo.get("name"));
    }

    @Test
    void testResponseIdPreserved() throws Exception {
        String requestId = "unique-request-123";
        JsonRpcRequest request = new JsonRpcRequest(requestId, "initialize", null);
        JsonRpcResponse response = handler.handleMessage(request);

        assertEquals(requestId, response.getId().orElse(null));
    }
}
