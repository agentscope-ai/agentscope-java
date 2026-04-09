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
package io.agentscope.core.tool;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.mcp.McpTool;
import java.util.Collections;
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
        void registerAgentToolWithMcpClient(
                AgentTool tool,
                String groupName,
                String mcpClientName,
                Map<String, Object> presetParameters);
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
        return registerMcpClient(mcpClientWrapper, enableTools, disableTools, groupName, null);
    }

    /**
     * Registers an MCP client with tool filtering, group assignment, and preset parameters.
     *
     * @param mcpClientWrapper the MCP client wrapper
     * @param enableTools list of tool names to enable (null means enable all)
     * @param disableTools list of tool names to disable (null means disable none)
     * @param groupName the group name to assign MCP tools to
     * @param presetParametersMapping map from tool name to preset parameters for that tool
     * @return Mono that completes when registration is finished
     */
    Mono<Void> registerMcpClient(
            McpClientWrapper mcpClientWrapper,
            List<String> enableTools,
            List<String> disableTools,
            String groupName,
            Map<String, Map<String, Object>> presetParametersMapping) {

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

                            // Get preset parameters for this specific tool
                            Map<String, Object> toolPresetParams =
                                    presetParametersMapping != null
                                            ? presetParametersMapping.get(mcpTool.name())
                                            : null;

                            McpTool agentTool =
                                    new McpTool(
                                            mcpTool.name(),
                                            mcpTool.description() != null
                                                    ? mcpTool.description()
                                                    : "",
                                            McpTool.convertMcpSchemaToParameters(
                                                    mcpTool.inputSchema(),
                                                    toolPresetParams != null
                                                            ? toolPresetParams.keySet()
                                                            : Collections.emptySet()),
                                            mcpClientWrapper);

                            // Register with group, MCP client name, and preset parameters via
                            // callback
                            registrationCallback.registerAgentToolWithMcpClient(
                                    agentTool,
                                    groupName,
                                    mcpClientWrapper.getName(),
                                    toolPresetParams);
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
     * @return set of MCP client names, never null but may be empty
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
     * @param enableTools list of tools to enable (null means all), takes precedence over disableTools
     * @param disableTools list of tools to disable (null means none)
     * @return true if the tool should be registered
     */
    private boolean shouldRegisterTool(
            String toolName, List<String> enableTools, List<String> disableTools) {
        // Default: register all tools
        boolean result = true;

        if (disableTools != null && !disableTools.isEmpty()) {
            result = !disableTools.contains(toolName);
        }

        if (enableTools != null && !enableTools.isEmpty()) {
            result = enableTools.contains(toolName);
        }

        return result;
    }
}
