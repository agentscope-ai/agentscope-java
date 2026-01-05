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
package io.agentscope.examples.hitlchat.service;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.examples.hitlchat.dto.McpConfigRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Service for managing MCP client connections.
 *
 * <p>Provides dynamic addition and removal of MCP servers at runtime.
 */
@Service
public class McpService {

    private final Map<String, McpClientWrapper> mcpClients = new ConcurrentHashMap<>();

    /**
     * Add a new MCP server.
     *
     * @param request MCP configuration request
     * @param toolkit The toolkit to register tools to
     * @return Mono that completes when the MCP server is added
     */
    public Mono<Void> addMcpServer(McpConfigRequest request, Toolkit toolkit) {
        String name = request.getName();
        if (mcpClients.containsKey(name)) {
            return Mono.error(new IllegalArgumentException("MCP server already exists: " + name));
        }

        return buildMcpClient(request)
                .flatMap(
                        client -> {
                            mcpClients.put(name, client);
                            return toolkit.registerMcpClient(client);
                        });
    }

    /**
     * Remove an MCP server.
     *
     * @param name Name of the MCP server to remove
     * @param toolkit The toolkit to unregister tools from
     * @return Mono that completes when the MCP server is removed
     */
    public Mono<Void> removeMcpServer(String name, Toolkit toolkit) {
        McpClientWrapper client = mcpClients.remove(name);
        if (client == null) {
            return Mono.error(new IllegalArgumentException("MCP server not found: " + name));
        }
        return toolkit.removeMcpClient(name);
    }

    /**
     * List all configured MCP servers.
     *
     * @return List of MCP server names
     */
    public List<String> listMcpServers() {
        return new ArrayList<>(mcpClients.keySet());
    }

    /**
     * Check if an MCP server exists.
     *
     * @param name Name of the MCP server
     * @return true if the server exists
     */
    public boolean hasMcpServer(String name) {
        return mcpClients.containsKey(name);
    }

    private Mono<McpClientWrapper> buildMcpClient(McpConfigRequest request) {
        McpClientBuilder builder = McpClientBuilder.create(request.getName());

        String transportType = request.getTransportType().toUpperCase();
        switch (transportType) {
            case "STDIO":
                String command = request.getCommand();
                List<String> args = request.getArgs();
                if (args == null || args.isEmpty()) {
                    builder.stdioTransport(command);
                } else {
                    builder.stdioTransport(command, args.toArray(new String[0]));
                }
                break;

            case "SSE":
                builder.sseTransport(request.getUrl());
                addHeadersAndParams(builder, request);
                break;

            case "HTTP":
                builder.streamableHttpTransport(request.getUrl());
                addHeadersAndParams(builder, request);
                break;

            default:
                return Mono.error(
                        new IllegalArgumentException("Unknown transport type: " + transportType));
        }

        return builder.buildAsync();
    }

    private void addHeadersAndParams(McpClientBuilder builder, McpConfigRequest request) {
        Map<String, String> headers = request.getHeaders();
        if (headers != null) {
            headers.forEach(builder::header);
        }

        Map<String, String> queryParams = request.getQueryParams();
        if (queryParams != null) {
            queryParams.forEach(builder::queryParam);
        }
    }
}
