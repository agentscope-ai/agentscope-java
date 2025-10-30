/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.tool;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.mcp.McpTool;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Manages MCP (Model Context Protocol) client registration and lifecycle.
 * Handles MCP client initialization, tool registration, and cleanup.
 */
class McpClientManager {

    private static final Logger logger = LoggerFactory.getLogger(McpClientManager.class);

    private final Map<String, McpClientWrapper> mcpClients = new ConcurrentHashMap<>();
    private final ToolRegistry toolRegistry;
    private final ToolGroupManager groupManager;
    private final ToolRegistrationCallback registrationCallback;

    /**
     * Callback interface for tool registration.
     */
    @FunctionalInterface
    interface ToolRegistrationCallback {
        void registerAgentToolWithMcpClient(AgentTool tool, String groupName, String mcpClientName);
    }

    McpClientManager(
            ToolRegistry toolRegistry,
            ToolGroupManager groupManager,
            ToolRegistrationCallback registrationCallback) {
        this.toolRegistry = toolRegistry;
        this.groupManager = groupManager;
        this.registrationCallback = registrationCallback;
    }

    /**
     * Registers an MCP client and all its tools.
     *
     * @param mcpClientWrapper the MCP client wrapper
     * @return Mono that completes when registration is finished
     */
    Mono<Void> registerMcpClient(McpClientWrapper mcpClientWrapper) {
        return registerMcpClient(mcpClientWrapper, null, null, null);
    }

    /**
     * Registers an MCP client with tool filtering.
     *
     * @param mcpClientWrapper the MCP client wrapper
     * @param enableTools list of tool names to enable (null means enable all)
     * @return Mono that completes when registration is finished
     */
    Mono<Void> registerMcpClient(McpClientWrapper mcpClientWrapper, List<String> enableTools) {
        return registerMcpClient(mcpClientWrapper, enableTools, null, null);
    }

    /**
     * Registers an MCP client with tool filtering.
     *
     * @param mcpClientWrapper the MCP client wrapper
     * @param enableTools list of tool names to enable (null means enable all)
     * @param disableTools list of tool names to disable (null means disable none)
     * @return Mono that completes when registration is finished
     */
    Mono<Void> registerMcpClient(
            McpClientWrapper mcpClientWrapper,
            List<String> enableTools,
            List<String> disableTools) {
        return registerMcpClient(mcpClientWrapper, enableTools, disableTools, null);
    }

    /**
     * Registers an MCP client with tool filtering and group assignment.
     *
     * @param mcpClientWrapper the MCP client wrapper
     * @param enableTools list of tool names to enable (null means enable all)
     * @param disableTools list of tool names to disable (null means disable none)
     * @param groupName the group name to assign MCP tools to
     * @return Mono that completes when registration is finished
     */
    Mono<Void> registerMcpClient(
            McpClientWrapper mcpClientWrapper,
            List<String> enableTools,
            List<String> disableTools,
            String groupName) {

        if (mcpClientWrapper == null) {
            return Mono.error(new IllegalArgumentException("MCP client wrapper cannot be null"));
        }

        // Validate group exists if specified
        if (groupName != null) {
            try {
                groupManager.validateGroupExists(groupName);
            } catch (IllegalArgumentException e) {
                return Mono.error(e);
            }
        }

        logger.info("Registering MCP client: {}", mcpClientWrapper.getName());

        return mcpClientWrapper
                .initialize()
                .then(Mono.defer(mcpClientWrapper::listTools))
                .flatMapMany(Flux::fromIterable)
                .filter(tool -> shouldRegisterTool(tool.name(), enableTools, disableTools))
                .doOnNext(
                        mcpTool -> {
                            logger.debug(
                                    "Registering MCP tool: {} from client {} into group {}",
                                    mcpTool.name(),
                                    mcpClientWrapper.getName(),
                                    groupName);

                            McpTool agentTool =
                                    new McpTool(
                                            mcpTool.name(),
                                            mcpTool.description() != null
                                                    ? mcpTool.description()
                                                    : "",
                                            McpTool.convertMcpSchemaToParameters(
                                                    mcpTool.inputSchema()),
                                            mcpClientWrapper);

                            // Register with group and MCP client name via callback
                            registrationCallback.registerAgentToolWithMcpClient(
                                    agentTool, groupName, mcpClientWrapper.getName());
                        })
                .then()
                .doOnSuccess(
                        v -> {
                            mcpClients.put(mcpClientWrapper.getName(), mcpClientWrapper);
                            logger.info(
                                    "MCP client '{}' registered successfully",
                                    mcpClientWrapper.getName());
                        })
                .doOnError(
                        e ->
                                logger.error(
                                        "Failed to register MCP client: {}",
                                        mcpClientWrapper.getName(),
                                        e));
    }

    /**
     * Removes an MCP client and all its tools.
     *
     * @param mcpClientName the name of the MCP client to remove
     * @return Mono that completes when removal is finished
     */
    Mono<Void> removeMcpClient(String mcpClientName) {
        McpClientWrapper wrapper = mcpClients.remove(mcpClientName);
        if (wrapper == null) {
            logger.warn("MCP client not found: {}", mcpClientName);
            return Mono.empty();
        }

        logger.info("Removing MCP client: {}", mcpClientName);

        // Remove all tools from this MCP client
        List<String> toolsToRemove =
                toolRegistry.getAllRegisteredTools().values().stream()
                        .filter(reg -> mcpClientName.equals(reg.getMcpClientName()))
                        .map(reg -> reg.getTool().getName())
                        .collect(Collectors.toList());

        toolsToRemove.forEach(
                toolName -> {
                    toolRegistry.removeTool(toolName);
                    logger.debug("Removed MCP tool: {}", toolName);
                });

        return Mono.fromRunnable(wrapper::close)
                .then()
                .doOnSuccess(
                        v -> logger.info("MCP client '{}' removed successfully", mcpClientName));
    }

    /**
     * Gets all registered MCP client names.
     *
     * @return set of MCP client names
     */
    Set<String> getMcpClientNames() {
        return new HashSet<>(mcpClients.keySet());
    }

    /**
     * Gets an MCP client wrapper by name.
     *
     * @param name the MCP client name
     * @return the MCP client wrapper, or null if not found
     */
    McpClientWrapper getMcpClient(String name) {
        return mcpClients.get(name);
    }

    /**
     * Determines if a tool should be registered based on enable/disable lists.
     *
     * @param toolName the tool name
     * @param enableTools list of tools to enable (null means all)
     * @param disableTools list of tools to disable (null means none)
     * @return true if the tool should be registered
     */
    private boolean shouldRegisterTool(
            String toolName, List<String> enableTools, List<String> disableTools) {
        // If enableTools is specified, only register tools in the list
        if (enableTools != null && !enableTools.isEmpty()) {
            return enableTools.contains(toolName);
        }

        // If disableTools is specified, exclude tools in the list
        if (disableTools != null && !disableTools.isEmpty()) {
            return !disableTools.contains(toolName);
        }

        // Default: register all tools
        return true;
    }
}
