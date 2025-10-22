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
import io.agentscope.core.tool.mcp.McpTool;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Toolkit manages the registration, retrieval, and execution of agent tools.
 * This class acts as a facade, delegating specific responsibilities to specialized components:
 * - ToolSchemaGenerator: Generates JSON schemas for tool parameters
 * - ToolMethodInvoker: Handles method invocation and parameter conversion
 * - ToolResultConverter: Converts method results to ToolResultBlock
 * - ParallelToolExecutor: Handles parallel/sequential tool execution
 *
 * Additionally supports:
 * - Tool group management for dynamic tool activation
 * - State management via StateModule interface
 * - Meta tool for runtime tool group control
 */
public class Toolkit extends StateModuleBase {

    private static final Logger logger = LoggerFactory.getLogger(Toolkit.class);

    private final Map<String, AgentTool> tools = new ConcurrentHashMap<>();
    private final Map<String, McpClientWrapper> mcpClients = new ConcurrentHashMap<>();
    private final Map<String, RegisteredToolFunction> registeredTools = new ConcurrentHashMap<>();
    private final ToolGroupManager groupManager = new ToolGroupManager();
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

        // Store in both maps
        tools.put(toolName, tool);
        registeredTools.put(toolName, registered);

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
        return tools.get(name);
    }

    /**
     * Get all tool names.
     */
    public Set<String> getToolNames() {
        return new HashSet<>(tools.keySet());
    }

    /**
     * Get tool schemas in OpenAI format, respecting active tool groups.
     * Aligned with Python Toolkit.get_json_schemas() with group filtering.
     */
    public List<Map<String, Object>> getToolSchemas() {
        List<Map<String, Object>> schemas = new ArrayList<>();

        for (RegisteredToolFunction registered : registeredTools.values()) {
            AgentTool tool = registered.getTool();
            String groupName = registered.getGroupName();

            // Filter: Only include if ungrouped OR in active group
            if (!groupManager.isInActiveGroup(groupName)) {
                continue; // Skip inactive tools
            }

            Map<String, Object> schema = new HashMap<>();
            schema.put("type", "function");

            Map<String, Object> function = new HashMap<>();
            function.put("name", tool.getName());
            function.put("description", tool.getDescription());

            // Use extended parameters if available
            function.put("parameters", registered.getExtendedParameters());

            schema.put("function", function);
            schemas.add(schema);
        }

        return schemas;
    }

    /**
     * Get tool schemas as ToolSchema objects for model consumption.
     * Updated to respect active tool groups.
     *
     * @return List of ToolSchema objects
     */
    public List<ToolSchema> getToolSchemasForModel() {
        List<ToolSchema> schemas = new ArrayList<>();

        for (RegisteredToolFunction registered : registeredTools.values()) {
            AgentTool tool = registered.getTool();
            String groupName = registered.getGroupName();

            // Filter: Only include if ungrouped OR in active group
            if (!groupManager.isInActiveGroup(groupName)) {
                continue; // Skip inactive tools
            }

            ToolSchema schema =
                    ToolSchema.builder()
                            .name(tool.getName())
                            .description(tool.getDescription())
                            .parameters(registered.getExtendedParameters())
                            .build();
            schemas.add(schema);
        }

        return schemas;
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
     * Call a tool using a ToolUseBlock and return a ToolResultBlock (synchronous).
     *
     * @param toolCall The tool use block containing tool name and arguments
     * @return ToolResultBlock containing the result
     * @deprecated Use {@link #callToolAsync(ToolUseBlock)} instead
     */
    @Deprecated
    public ToolResultBlock callTool(ToolUseBlock toolCall) {
        return callToolAsync(toolCall).block();
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

    // ==================== MCP Client Registration ====================

    /**
     * Registers an MCP client and all its tools.
     *
     * @param mcpClientWrapper the MCP client wrapper
     * @return Mono that completes when registration is finished
     */
    public Mono<Void> registerMcpClient(McpClientWrapper mcpClientWrapper) {
        return registerMcpClient(mcpClientWrapper, null, null);
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
        return registerMcpClient(mcpClientWrapper, enableTools, null);
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
        return registerMcpClient(mcpClientWrapper, enableTools, disableTools, null);
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

                            // Register with group and MCP client name
                            registerAgentTool(
                                    agentTool, groupName, null, mcpClientWrapper.getName());
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
    public Mono<Void> removeMcpClient(String mcpClientName) {
        McpClientWrapper wrapper = mcpClients.remove(mcpClientName);
        if (wrapper == null) {
            logger.warn("MCP client not found: {}", mcpClientName);
            return Mono.empty();
        }

        logger.info("Removing MCP client: {}", mcpClientName);

        // Remove all tools from this MCP client
        List<String> toolsToRemove =
                registeredTools.values().stream()
                        .filter(reg -> mcpClientName.equals(reg.getMcpClientName()))
                        .map(reg -> reg.getTool().getName())
                        .collect(Collectors.toList());

        toolsToRemove.forEach(
                toolName -> {
                    tools.remove(toolName);
                    registeredTools.remove(toolName);
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
    public Set<String> getMcpClientNames() {
        return new HashSet<>(mcpClients.keySet());
    }

    /**
     * Gets an MCP client wrapper by name.
     *
     * @param name the MCP client name
     * @return the MCP client wrapper, or null if not found
     */
    public McpClientWrapper getMcpClient(String name) {
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
        for (String toolName : toolsToRemove) {
            tools.remove(toolName);
            registeredTools.remove(toolName);
        }
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
        AgentTool metaTool = createResetEquippedToolsAgentTool();

        // Register without group (meta tool is always available)
        registerAgentTool(metaTool, null, null, null);

        logger.info("Registered meta tool: reset_equipped_tools");
    }

    /**
     * Create the AgentTool for reset_equipped_tools.
     */
    private AgentTool createResetEquippedToolsAgentTool() {
        return new AgentTool() {
            @Override
            public String getName() {
                return "reset_equipped_tools";
            }

            @Override
            public String getDescription() {
                // CRITICAL: Must match Python exactly (_toolkit.py line 611-615)
                return "Reset the equipped tools by activating specified tool groups.\n\n"
                        + getActivatedNotes();
            }

            @Override
            public Map<String, Object> getParameters() {
                // Build schema dynamically based on available tool groups
                Map<String, Object> schema = new HashMap<>();
                schema.put("type", "object");

                // Properties
                Map<String, Object> properties = new HashMap<>();
                Map<String, Object> toActivateParam = new HashMap<>();
                toActivateParam.put("type", "array");

                Map<String, Object> items = new HashMap<>();
                items.put("type", "string");

                // CRITICAL: Generate enum from available tool groups
                List<String> availableGroups = new ArrayList<>(groupManager.getToolGroupNames());
                if (!availableGroups.isEmpty()) {
                    items.put("enum", availableGroups);
                }

                toActivateParam.put("items", items);
                toActivateParam.put("description", "The list of tool group names to activate.");

                properties.put("to_activate", toActivateParam);
                schema.put("properties", properties);

                // Required fields
                schema.put("required", List.of("to_activate"));

                return schema;
            }

            @Override
            public Mono<ToolResultBlock> callAsync(Map<String, Object> input) {
                try {
                    @SuppressWarnings("unchecked")
                    List<String> toActivate = (List<String>) input.get("to_activate");

                    if (toActivate == null) {
                        return Mono.just(
                                ToolResultBlock.error("Missing required parameter: to_activate"));
                    }

                    String result = resetEquippedToolsImpl(toActivate);
                    return Mono.just(ToolResultBlock.text(result));
                } catch (Exception e) {
                    return Mono.just(ToolResultBlock.error(e.getMessage()));
                }
            }
        };
    }

    /**
     * Implementation of reset_equipped_tools logic.
     * Aligned with Python Toolkit.reset_equipped_tools() (lines 604-647).
     *
     * CRITICAL SEMANTICS: Only activates specified groups, does NOT deactivate others.
     *
     * @param toActivate List of tool group names to activate
     * @return Success message describing activated tools
     * @throws IllegalArgumentException if any group doesn't exist
     */
    private String resetEquippedToolsImpl(List<String> toActivate) {
        // Validate all groups exist
        for (String groupName : toActivate) {
            groupManager.validateGroupExists(groupName);
        }

        // Activate groups (Python line 684: only calls update with active=True)
        updateToolGroups(toActivate, true);

        // Build response message
        StringBuilder result = new StringBuilder();
        result.append("Successfully activated tool groups: ").append(toActivate).append("\n\n");

        // List activated tools
        result.append("Activated tools:\n");
        for (String groupName : toActivate) {
            ToolGroup group = groupManager.getToolGroup(groupName);
            result.append(String.format("- Group '%s': %s\n", groupName, group.getDescription()));
            for (String toolName : group.getTools()) {
                AgentTool tool = tools.get(toolName);
                if (tool != null) {
                    result.append(String.format("  - %s: %s\n", toolName, tool.getDescription()));
                }
            }
        }

        return result.toString();
    }
}
