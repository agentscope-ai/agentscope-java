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

package io.agentscope.core.mcp.server;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.mcp.tool.Tool;
import io.agentscope.core.mcp.transport.Transport;
import io.agentscope.core.mcp.transport.TransportException;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class McpServerTest {

    @Mock private Transport mockTransport;

    @Mock private Tool mockTool;

    public McpServerTest() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testMcpServerCreation() {
        McpServer server = new McpServer(mockTransport);
        assertNotNull(server);
    }

    @Test
    void testRegisterTool() {
        McpServer server = new McpServer(mockTransport);
        when(mockTool.getName()).thenReturn("test.tool");
        server.registerTool(mockTool);
        // Tool should be registered without throwing an exception
        verify(mockTool, atLeastOnce()).getName();
    }

    @Test
    void testServerInitialization() {
        McpServer server = new McpServer(mockTransport);
        assertNotNull(server);
        // Server should be created successfully
    }

    @Test
    void testMultipleToolsRegistration() {
        McpServer server = new McpServer(mockTransport);
        Tool tool1 = mock(Tool.class);
        Tool tool2 = mock(Tool.class);

        when(tool1.getName()).thenReturn("tool1");
        when(tool2.getName()).thenReturn("tool2");

        server.registerTool(tool1);
        server.registerTool(tool2);

        verify(tool1, atLeastOnce()).getName();
        verify(tool2, atLeastOnce()).getName();
    }

    @Test
    void testServerStartAndStop() throws Exception {
        when(mockTransport.isConnected()).thenReturn(true).thenReturn(false);
        when(mockTransport.receive()).thenThrow(new TransportException("EOF"));

        McpServer server = new McpServer(mockTransport);
        server.start();

        Thread.sleep(100);

        server.stop();
        verify(mockTransport, times(1)).close();
    }
}
