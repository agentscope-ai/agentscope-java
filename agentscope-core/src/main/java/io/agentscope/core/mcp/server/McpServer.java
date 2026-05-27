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

import io.agentscope.core.mcp.handler.CallToolHandler;
import io.agentscope.core.mcp.handler.HandlerRegistry;
import io.agentscope.core.mcp.handler.InitializeHandler;
import io.agentscope.core.mcp.handler.ListToolsHandler;
import io.agentscope.core.mcp.handler.MessageRouter;
import io.agentscope.core.mcp.tool.Tool;
import io.agentscope.core.mcp.tool.ToolManager;
import io.agentscope.core.mcp.transport.Transport;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Facade for creating and running an MCP server.
 */
public class McpServer {

    private static final Logger logger = Logger.getLogger(McpServer.class.getName());

    private final Transport transport;
    private final MessageRouter router;
    private final ToolManager toolManager;
    private final HandlerRegistry handlerRegistry;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public McpServer(Transport transport) {
        this.transport = transport;
        this.handlerRegistry = new HandlerRegistry();
        this.toolManager = new ToolManager();
        this.router = new MessageRouter(transport, handlerRegistry);

        // Register built-in handlers
        registerBuiltIns();
    }

    private void registerBuiltIns() {
        // Handlers will be registered with the router via the handler registry
        handlerRegistry.register("initialize", new InitializeHandler(toolManager));
        handlerRegistry.register("tools/list", new ListToolsHandler(toolManager));
        handlerRegistry.register("tools/call", new CallToolHandler(toolManager));

        // No example external tools are auto-registered by default.
    }

    /**
     * Register additional server-side tools.
     */
    public void registerTool(Tool tool) {
        toolManager.register(tool);
    }

    /**
     * Start processing messages on the transport in a background thread.
     */
    public void start() {
        executor.submit(
                () -> {
                    try {
                        router.processMessages();
                    } catch (Exception e) {
                        logger.severe("MCP Server stopped: " + e.getMessage());
                    }
                });
    }

    public void stop() throws Exception {
        transport.close();
        executor.shutdownNow();
    }
}
