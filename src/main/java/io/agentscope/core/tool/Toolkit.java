/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.StateModuleBase;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Toolkit manages the registration, retrieval, and execution of agent tools.
 * This class acts as a facade, delegating specific responsibilities to specialized managers:
 *
 * <p><b>Managers:</b>
 * <ul>
 *   <li>ToolRegistry: Tool registration and lookup</li>
 *   <li>ToolGroupManager: Tool group CRUD operations and active group management</li>
 *   <li>ToolSchemaProvider: Tool schema generation with group filtering</li>
 *   <li>McpClientManager: MCP client lifecycle and tool registration</li>
 *   <li>MetaToolFactory: Creates meta tools for dynamic group control</li>
 * </ul>
 *
 * <p><b>Core Components:</b>
 * <ul>
 *   <li>ToolSchemaGenerator: Generates JSON schemas for tool parameters</li>
 *   <li>ToolMethodInvoker: Handles method invocation and parameter conversion</li>
 *   <li>ToolResultConverter: Converts method results to ToolResultBlock</li>
 *   <li>ParallelToolExecutor: Handles parallel/sequential tool execution</li>
 * </ul>
 *
 * <p><b>Features:</b>
 * <ul>
 *   <li>Tool group management for dynamic tool activation</li>
 *   <li>State management via StateModule interface (activeGroups persistence)</li>
 *   <li>Meta tool for runtime tool group control (reset_equipped_tools)</li>
 *   <li>MCP (Model Context Protocol) client support for external tool providers</li>
 * </ul>
 */
public class Toolkit extends StateModuleBase {

    private static final Logger logger = LoggerFactory.getLogger(Toolkit.class);

    private final ToolGroupManager groupManager = new ToolGroupManager();
    private final ToolRegistry toolRegistry = new ToolRegistry();
    private final ToolSchemaProvider schemaProvider;
    private final MetaToolFactory metaToolFactory;
    private final McpClientManager mcpClientManager;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ToolSchemaGenerator schemaGenerator = new ToolSchemaGenerator();
    private final ToolResultConverter responseConverter;
    private final ToolMethodInvoker methodInvoker;
    private final ToolkitConfig config;
    private final ParallelToolExecutor executor;

    /**
     * Create a Toolkit with default configuration (sequential execution using Reactor).
     */
    public Toolkit() {
        this(ToolkitConfig.defaultConfig());
    }

    /**
     * Create a Toolkit with custom configuration.
     *
     * @param config Toolkit configuration
     */
    public Toolkit(ToolkitConfig config) {
        this.config = config;
        this.responseConverter = new ToolResultConverter(objectMapper);
        this.methodInvoker = new ToolMethodInvoker(objectMapper, responseConverter);
        this.schemaProvider = new ToolSchemaProvider(toolRegistry, groupManager);
        this.metaToolFactory = new MetaToolFactory(groupManager, toolRegistry);
        this.mcpClientManager =
                new McpClientManager(
                        toolRegistry,
                        groupManager,
                        (tool, groupName, mcpClientName) ->
                                registerAgentTool(tool, groupName, null, mcpClientName));

        // Create executor based on configuration
        if (config.hasCustomExecutor()) {
            this.executor = new ParallelToolExecutor(this, config.getExecutorService());
        } else {
            this.executor = new ParallelToolExecutor(this);
        }

        // Register state management for activeGroups with custom serialization
        // Since we don't have an activeGroups field, we provide functions to get/set from
        // groupManager
        registerState(
                "activeGroups",
                obj -> groupManager.getActiveGroups(), // toJson: get from groupManager
                obj -> {
                    // fromJson: set to groupManager
                    if (obj instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<String> groups = (List<String>) obj;
                        groupManager.setActiveGroups(groups);
                    }
                    return obj;
                });
    }

    /**
     * Register a tool object by scanning for methods annotated with @Tool.
     * @param toolObject the object containing tool methods
     */
    public void registerTool(Object toolObject) {
        registerTool(toolObject, null, null);
    }

    /**
     * Register a tool object with optional group assignment.
     *
     * @param toolObject The object containing tool methods
     * @param groupName The tool group to assign this tool to (null for ungrouped)
     */
    public void registerTool(Object toolObject, String groupName) {
        registerTool(toolObject, groupName, null);
    }

    /**
     * Register a tool object with group and extended model.
     *
     * @param toolObject The object containing tool methods
     * @param groupName The tool group to assign this tool to
     * @param extendedModel Extended model for dynamic schema extension
     */
    public void registerTool(
            Object toolObject,
            String groupName,
            RegisteredToolFunction.ExtendedModel extendedModel) {
        if (toolObject == null) {
            throw new IllegalArgumentException("Tool object cannot be null");
        }

        // Check if the object is an AgentTool instance
        if (toolObject instanceof AgentTool) {
            registerAgentTool((AgentTool) toolObject, groupName, extendedModel);
            return;
        }

        Class<?> clazz = toolObject.getClass();
        Method[] methods = clazz.getDeclaredMethods();

        for (Method method : methods) {
            if (method.isAnnotationPresent(Tool.class)) {
                registerToolMethod(toolObject, method, groupName, extendedModel);
            }
        }
    }

    /**
     * Register an AgentTool instance directly.
     * @param tool the AgentTool to register
     */
    public void registerAgentTool(AgentTool tool) {
        registerAgentTool(tool, null, null);
    }

    /**
     * Register an AgentTool with group and extended model.
     */
    public void registerAgentTool(
            AgentTool tool, String groupName, RegisteredToolFunction.ExtendedModel extendedModel) {
        registerAgentTool(tool, groupName, extendedModel, null);
    }

    /**
     * Internal method to register AgentTool with full metadata.
     */
    private void registerAgentTool(
            AgentTool tool,
            String groupName,
            RegisteredToolFunction.ExtendedModel extendedModel,
            String mcpClientName) {
        if (tool == null) {
            throw new IllegalArgumentException("AgentTool cannot be null");
        }

        String toolName = tool.getName();

        // Validate group exists if specified
        if (groupName != null) {
            groupManager.validateGroupExists(groupName);
        }

        // Create registered wrapper
        RegisteredToolFunction registered =
                new RegisteredToolFunction(tool, groupName, extendedModel, mcpClientName);

        // Register in toolRegistry
        toolRegistry.registerTool(toolName, tool, registered);

        // Add to group if specified
        if (groupName != null) {
            groupManager.addToolToGroup(groupName, toolName);
        }

        logger.info(
                "Registered tool '{}' in group '{}'",
                toolName,
                groupName != null ? groupName : "ungrouped");
    }

    /**
     * Get tool by name.
     */
    public AgentTool getTool(String name) {
        return toolRegistry.getTool(name);
    }

    /**
     * Get all tool names.
     */
    public Set<String> getToolNames() {
        return toolRegistry.getToolNames();
    }

    /**
     * Get tool schemas in OpenAI format, respecting active tool groups.
     * Aligned with Python Toolkit.get_json_schemas() with group filtering.
     */
    public List<Map<String, Object>> getToolSchemas() {
        return schemaProvider.getToolSchemas();
    }

    /**
     * Get tool schemas as ToolSchema objects for model consumption.
     * Updated to respect active tool groups.
     *
     * @return List of ToolSchema objects
     */
    public List<ToolSchema> getToolSchemasForModel() {
        return schemaProvider.getToolSchemasForModel();
    }

    /**
     * Register a single tool method.
     */
    private void registerToolMethod(Object toolObject, Method method) {
        registerToolMethod(toolObject, method, null, null);
    }

    /**
     * Update existing registerToolMethod to support group.
     */
    private void registerToolMethod(
            Object toolObject,
            Method method,
            String groupName,
            RegisteredToolFunction.ExtendedModel extendedModel) {
        Tool toolAnnotation = method.getAnnotation(Tool.class);

        String toolName =
                !toolAnnotation.name().isEmpty() ? toolAnnotation.name() : method.getName();
        String description =
                !toolAnnotation.description().isEmpty()
                        ? toolAnnotation.description()
                        : "Tool: " + toolName;

        AgentTool tool =
                new AgentTool() {
                    private ToolUseBlock currentToolUseBlock;

                    @Override
                    public String getName() {
                        return toolName;
                    }

                    @Override
                    public String getDescription() {
                        return description;
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        return schemaGenerator.generateParameterSchema(method);
                    }

                    @Override
                    public void setCurrentToolUseBlock(ToolUseBlock toolUseBlock) {
                        this.currentToolUseBlock = toolUseBlock;
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(Map<String, Object> input) {
                        return methodInvoker.invokeAsync(
                                toolObject, method, input, currentToolUseBlock);
                    }
                };

        registerAgentTool(tool, groupName, extendedModel, null);
    }

    /**
     * Set the chunk callback for streaming tool responses.
     *
     * @param callback Callback to invoke when tools emit chunks via ToolEmitter
     */
    public void setChunkCallback(BiConsumer<ToolUseBlock, ToolResultBlock> callback) {
        methodInvoker.setChunkCallback(callback);
    }

    /**
     * Call a tool using a ToolUseBlock and return a Mono of ToolResultBlock (asynchronous).
     *
     * @param toolCall The tool use block containing tool name and arguments
     * @return Mono containing ToolResultBlock
     */
    public Mono<ToolResultBlock> callToolAsync(ToolUseBlock toolCall) {
        AgentTool tool = getTool(toolCall.getName());
        if (tool == null) {
            return Mono.just(ToolResultBlock.error("Tool not found: " + toolCall.getName()));
        }

        // Set the current ToolUseBlock for ToolEmitter injection
        tool.setCurrentToolUseBlock(toolCall);

        return tool.callAsync(toolCall.getInput())
                .onErrorResume(
                        e -> {
                            String errorMsg =
                                    e.getMessage() != null
                                            ? e.getMessage()
                                            : e.getClass().getSimpleName();
                            return Mono.just(
                                    ToolResultBlock.error("Tool execution failed: " + errorMsg));
                        });
    }

    /**
     * Execute multiple tools asynchronously (parallel or sequential based on configuration).
     *
     * @param toolCalls List of tool calls to execute
     * @return Mono containing list of tool responses
     */
    public Mono<List<ToolResultBlock>> callTools(List<ToolUseBlock> toolCalls) {
        return executor.executeTools(toolCalls, config.isParallel());
    }

    /**
     * Get the toolkit configuration.
     *
     * @return Current ToolkitConfig
     */
    public ToolkitConfig getConfig() {
        return config;
    }

    // ==================== MCP Client Registration (Delegated) ====================

    /**
     * Registers an MCP client and all its tools.
     *
     * @param mcpClientWrapper the MCP client wrapper
     * @return Mono that completes when registration is finished
     */
    public Mono<Void> registerMcpClient(McpClientWrapper mcpClientWrapper) {
        return mcpClientManager.registerMcpClient(mcpClientWrapper);
    }

    /**
     * Registers an MCP client with tool filtering.
     *
     * @param mcpClientWrapper the MCP client wrapper
     * @param enableTools list of tool names to enable (null means enable all)
     * @return Mono that completes when registration is finished
     */
    public Mono<Void> registerMcpClient(
            McpClientWrapper mcpClientWrapper, List<String> enableTools) {
        return mcpClientManager.registerMcpClient(mcpClientWrapper, enableTools);
    }

    /**
     * Registers an MCP client with tool filtering.
     *
     * @param mcpClientWrapper the MCP client wrapper
     * @param enableTools list of tool names to enable (null means enable all)
     * @param disableTools list of tool names to disable (null means disable none)
     * @return Mono that completes when registration is finished
     */
    public Mono<Void> registerMcpClient(
            McpClientWrapper mcpClientWrapper,
            List<String> enableTools,
            List<String> disableTools) {
        return mcpClientManager.registerMcpClient(mcpClientWrapper, enableTools, disableTools);
    }

    /**
     * Registers an MCP client with tool filtering and group assignment.
     * Aligned with Python Toolkit.register_mcp_client().
     *
     * @param mcpClientWrapper the MCP client wrapper
     * @param enableTools list of tool names to enable (null means enable all)
     * @param disableTools list of tool names to disable (null means disable none)
     * @param groupName the group name to assign MCP tools to
     * @return Mono that completes when registration is finished
     */
    public Mono<Void> registerMcpClient(
            McpClientWrapper mcpClientWrapper,
            List<String> enableTools,
            List<String> disableTools,
            String groupName) {
        return mcpClientManager.registerMcpClient(
                mcpClientWrapper, enableTools, disableTools, groupName);
    }

    /**
     * Removes an MCP client and all its tools.
     *
     * @param mcpClientName the name of the MCP client to remove
     * @return Mono that completes when removal is finished
     */
    public Mono<Void> removeMcpClient(String mcpClientName) {
        return mcpClientManager.removeMcpClient(mcpClientName);
    }

    /**
     * Gets all registered MCP client names.
     *
     * @return set of MCP client names
     */
    public Set<String> getMcpClientNames() {
        return mcpClientManager.getMcpClientNames();
    }

    /**
     * Gets an MCP client wrapper by name.
     *
     * @param name the MCP client name
     * @return the MCP client wrapper, or null if not found
     */
    public McpClientWrapper getMcpClient(String name) {
        return mcpClientManager.getMcpClient(name);
    }

    // ==================== Tool Group Management (Delegated) ====================

    public void createToolGroup(String groupName, String description, boolean active) {
        groupManager.createToolGroup(groupName, description, active);
    }

    public void createToolGroup(String groupName, String description) {
        groupManager.createToolGroup(groupName, description);
    }

    public void updateToolGroups(List<String> groupNames, boolean active) {
        groupManager.updateToolGroups(groupNames, active);
    }

    public void removeToolGroups(List<String> groupNames) {
        Set<String> toolsToRemove = groupManager.removeToolGroups(groupNames);
        // Remove tools from registry
        toolRegistry.removeTools(toolsToRemove);
    }

    public String getActivatedNotes() {
        return groupManager.getActivatedNotes();
    }

    public Set<String> getToolGroupNames() {
        return groupManager.getToolGroupNames();
    }

    public List<String> getActiveGroups() {
        return groupManager.getActiveGroups();
    }

    public ToolGroup getToolGroup(String groupName) {
        return groupManager.getToolGroup(groupName);
    }

    // ==================== Meta Tool Registration ====================

    /**
     * Register the meta tool that allows agents to dynamically manage tool groups.
     * Aligned with Python Toolkit registration of reset_equipped_tools.
     *
     * This creates a tool that wraps the toolkit's resetEquippedTools method,
     * allowing the agent to activate tool groups during execution.
     */
    public void registerMetaTool() {
        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();

        // Register without group (meta tool is always available)
        registerAgentTool(metaTool, null, null, null);

        logger.info("Registered meta tool: reset_equipped_tools");
    }
}
