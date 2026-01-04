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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.subagent.SubAgentConfig;
import io.agentscope.core.tool.subagent.SubAgentProvider;
import io.agentscope.core.tool.subagent.SubAgentTool;
import io.agentscope.core.tracing.TracerRegistry;
import java.lang.reflect.Method;
import java.util.Collections;
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
 *   <li>ToolExecutor: Handles parallel/sequential tool execution with validation</li>
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
public class Toolkit {

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
    private final ToolExecutor executor;
    private BiConsumer<ToolUseBlock, ToolResultBlock> chunkCallback;

    /**
     * Create a Toolkit with default configuration (sequential execution using Reactor).
     */
    public Toolkit() {
        this(ToolkitConfig.defaultConfig());
    }

    /**
     * Create a Toolkit with custom configuration.
     *
     * @param config Toolkit configuration (if null, uses defaultConfig())
     */
    public Toolkit(ToolkitConfig config) {
        this.config = config != null ? config : ToolkitConfig.defaultConfig();
        this.responseConverter = new ToolResultConverter(objectMapper);
        this.methodInvoker = new ToolMethodInvoker(objectMapper, responseConverter);
        this.schemaProvider = new ToolSchemaProvider(toolRegistry, groupManager);
        this.metaToolFactory = new MetaToolFactory(groupManager, toolRegistry);
        this.mcpClientManager =
                new McpClientManager(
                        toolRegistry,
                        groupManager,
                        (tool, groupName, mcpClientName, presetParameters) ->
                                registerAgentTool(
                                        tool, groupName, null, mcpClientName, presetParameters));

        // Create executor based on configuration
        if (config.hasCustomExecutor()) {
            this.executor =
                    new ToolExecutor(
                            toolRegistry,
                            groupManager,
                            this.config,
                            methodInvoker,
                            config.getExecutorService());
        } else {
            this.executor =
                    new ToolExecutor(toolRegistry, groupManager, this.config, methodInvoker);
        }
    }

    /**
     * Create a fluent builder for registering tools with optional configuration.
     *
     * <p>Example usage:
     * <pre>{@code
     * // Register tool object
     * toolkit.registration()
     *     .tool(myToolObject)
     *     .group("myGroup")
     *     .presetParameters(Map.of(
     *         "myTool", Map.of("apiKey", "secret")
     *     ))
     *     .apply();
     *
     * // Register MCP client
     * toolkit.registration()
     *     .mcpClient(mcpClientWrapper)
     *     .enableTools(List.of("tool1", "tool2"))
     *     .group("mcpGroup")
     *     .presetParameters(Map.of(
     *         "tool1", Map.of("apiKey", "key1")
     *     ))
     *     .apply();
     * }</pre>
     *
     * @return A new ToolRegistration builder
     */
    public ToolRegistration registration() {
        return new ToolRegistration(this);
    }

    /**
     * Register a tool object by scanning for methods annotated with @Tool.
     * @param toolObject the object containing tool methods
     */
    public void registerTool(Object toolObject) {
        registerTool(toolObject, null, null, null);
    }

    /**
     * Internal method: Register a tool object with group, extended model, and preset parameters.
     */
    private void registerTool(
            Object toolObject,
            String groupName,
            ExtendedModel extendedModel,
            Map<String, Map<String, Object>> presetParameters) {
        if (toolObject == null) {
            throw new IllegalArgumentException("Tool object cannot be null");
        }

        // Check if the object is an AgentTool instance
        if (toolObject instanceof AgentTool) {
            AgentTool agentTool = (AgentTool) toolObject;
            String toolName = agentTool.getName();
            Map<String, Object> toolPresets =
                    (presetParameters != null && presetParameters.containsKey(toolName))
                            ? presetParameters.get(toolName)
                            : null;
            registerAgentTool(agentTool, groupName, extendedModel, null, toolPresets);
            return;
        }

        Class<?> clazz = toolObject.getClass();
        Method[] methods = clazz.getDeclaredMethods();

        for (Method method : methods) {
            if (method.isAnnotationPresent(Tool.class)) {
                Tool toolAnnotation = method.getAnnotation(Tool.class);
                String toolName =
                        toolAnnotation.name().isEmpty() ? method.getName() : toolAnnotation.name();
                Map<String, Object> toolPresets =
                        (presetParameters != null && presetParameters.containsKey(toolName))
                                ? presetParameters.get(toolName)
                                : null;
                registerToolMethod(toolObject, method, groupName, extendedModel, toolPresets);
            }
        }
    }

    /**
     * Register an AgentTool instance directly.
     * @param tool the AgentTool to register
     */
    public void registerAgentTool(AgentTool tool) {
        registerAgentTool(tool, null, null, null, null);
    }

    /**
     * Internal method to register AgentTool with full metadata including preset parameters.
     */
    private void registerAgentTool(
            AgentTool tool,
            String groupName,
            ExtendedModel extendedModel,
            String mcpClientName,
            Map<String, Object> presetParameters) {
        if (tool == null) {
            throw new IllegalArgumentException("AgentTool cannot be null");
        }

        String toolName = tool.getName();

        // Validate group exists if specified
        if (groupName != null) {
            groupManager.validateGroupExists(groupName);
        }

        // Create registered wrapper with preset parameters
        RegisteredToolFunction registered =
                new RegisteredToolFunction(
                        tool, groupName, extendedModel, mcpClientName, presetParameters);

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
     * Retrieves a tool by its name.
     *
     * @param name The name of the tool to retrieve
     * @return The AgentTool instance, or null if not found
     */
    public AgentTool getTool(String name) {
        return toolRegistry.getTool(name);
    }

    /**
     * Gets the names of all registered tools.
     *
     * @return A set of all tool names (never null, may be empty)
     */
    public Set<String> getToolNames() {
        return toolRegistry.getToolNames();
    }

    // ==================== External Tool Support ====================

    /**
     * Register an external tool using only its schema definition.
     *
     * <p>External tools are tools that will be executed outside the framework. When a model
     * returns a call to an external tool, the framework will not execute it but instead
     * return the tool call to the user via a message with
     * {@link io.agentscope.core.message.GenerateReason#TOOL_SUSPENDED}.
     *
     * <p>Example usage:
     * <pre>{@code
     * ToolSchema schema = ToolSchema.builder()
     *     .name("query_database")
     *     .description("Query external database")
     *     .parameters(Map.of(
     *         "type", "object",
     *         "properties", Map.of("sql", Map.of("type", "string")),
     *         "required", List.of("sql")
     *     ))
     *     .build();
     *
     * toolkit.registerSchema(schema);
     * }</pre>
     *
     * @param schema The tool schema containing name, description, and parameters
     * @throws NullPointerException if schema is null
     * @see SchemaOnlyTool
     * @see #isExternalTool(String)
     */
    public void registerSchema(ToolSchema schema) {
        registerAgentTool(new SchemaOnlyTool(schema));
    }

    /**
     * Register multiple external tools using their schema definitions.
     *
     * @param schemas List of tool schemas to register
     * @throws NullPointerException if schemas is null
     * @see #registerSchema(ToolSchema)
     */
    public void registerSchemas(List<ToolSchema> schemas) {
        if (schemas != null) {
            schemas.forEach(this::registerSchema);
        }
    }

    /**
     * Check if a tool is an external tool (schema-only, requires user execution).
     *
     * <p>External tools are registered using {@link #registerSchema(ToolSchema)} and should
     * be executed outside the framework. When this method returns true, the framework will
     * skip execution and return the tool call to the user.
     *
     * @param toolName The name of the tool to check
     * @return true if the tool is an external tool (SchemaOnlyTool), false otherwise
     */
    public boolean isExternalTool(String toolName) {
        AgentTool tool = getTool(toolName);
        return tool instanceof SchemaOnlyTool;
    }

    /**
     * Get tool schemas as ToolSchema objects.
     * Updated to respect active tool groups.
     *
     * @return List of ToolSchema objects
     */
    public List<ToolSchema> getToolSchemas() {
        return schemaProvider.getToolSchemas();
    }

    /**
     * Register a tool method with group, extended model, and preset parameters.
     */
    private void registerToolMethod(
            Object toolObject,
            Method method,
            String groupName,
            ExtendedModel extendedModel,
            Map<String, Object> presetParameters) {
        Tool toolAnnotation = method.getAnnotation(Tool.class);

        String toolName =
                !toolAnnotation.name().isEmpty() ? toolAnnotation.name() : method.getName();
        String description =
                !toolAnnotation.description().isEmpty()
                        ? toolAnnotation.description()
                        : "Tool: " + toolName;

        AgentTool tool =
                new AgentTool() {
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
                        // Exclude preset parameters from the schema
                        Set<String> excludeParams =
                                presetParameters != null
                                        ? presetParameters.keySet()
                                        : Collections.emptySet();
                        return schemaGenerator.generateParameterSchema(method, excludeParams);
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        return methodInvoker.invokeAsync(toolObject, method, param);
                    }
                };

        registerAgentTool(tool, groupName, extendedModel, null, presetParameters);
    }

    /**
     * Set the chunk callback for streaming tool responses.
     *
     * <p>This is an internal method used by ReActAgent to receive streaming updates from tool
     * executions. When tools emit progress updates via ToolEmitter, this callback will be invoked
     * with the tool use block and the incremental result chunk.
     *
     * <p><b>Note:</b> This method is primarily intended for internal framework use. Most users
     * should not need to call this directly as it is automatically configured by the agent.
     *
     * @param callback Callback to invoke when tools emit chunks via ToolEmitter
     */
    public void setChunkCallback(BiConsumer<ToolUseBlock, ToolResultBlock> callback) {
        this.chunkCallback = callback;
        executor.setChunkCallback(callback);
    }

    /**
     * Execute a tool with the given parameters.
     *
     * <p>Example usage:
     *
     * <pre>{@code
     * // Simple call
     * ToolCallParam param = ToolCallParam.builder()
     *     .toolUseBlock(toolCall)
     *     .build();
     * toolkit.callTool(param);
     *
     * // With agent and context
     * ToolCallParam param = ToolCallParam.builder()
     *     .toolUseBlock(toolCall)
     *     .agent(agent)
     *     .context(context)
     *     .build();
     * toolkit.callTool(param);
     * }</pre>
     *
     * @param param Tool call parameters containing execution information
     * @return Mono containing execution result
     */
    public Mono<ToolResultBlock> callTool(ToolCallParam param) {
        return TracerRegistry.get().callTool(this, param, () -> executor.execute(param));
    }

    /**
     * Execute multiple tools asynchronously with agent-level context (internal use by
     * ReActAgent).
     *
     * <p><b>Internal API - Not recommended for external use.</b> This method is primarily
     * intended for use by {@link io.agentscope.core.ReActAgent} and other framework components.
     *
     * <p>This method handles parallel/sequential execution based on toolkit configuration and
     * applies execution config (timeout, retry) from multiple levels. The agent context is
     * merged with toolkit default context during tool execution.
     *
     * @param toolCalls List of tool calls to execute
     * @param agentExecutionConfig Execution config from agent level (can be null)
     * @param agent The agent making the calls (may be null)
     * @param agentContext The agent-level tool execution context (may be null)
     * @return Mono containing list of tool responses
     */
    public Mono<List<ToolResultBlock>> callTools(
            List<ToolUseBlock> toolCalls,
            ExecutionConfig agentExecutionConfig,
            Agent agent,
            ToolExecutionContext agentContext) {
        // Merge execution configs: agent-level > toolkit-level > system default
        ExecutionConfig effectiveConfig =
                ExecutionConfig.mergeConfigs(
                        agentExecutionConfig,
                        ExecutionConfig.mergeConfigs(
                                config.getExecutionConfig(), ExecutionConfig.TOOL_DEFAULTS));

        return executor.executeAll(
                toolCalls, config.isParallel(), effectiveConfig, agent, agentContext);
    }

    // ==================== MCP Client Registration (Delegated) ====================

    /**
     * Registers an MCP client and all its tools.
     *
     * <p>For more complex registration scenarios (filtering, groups, preset parameters),
     * use the builder API: {@code toolkit.registration().mcpClient(...).apply()}
     *
     * @param mcpClientWrapper the MCP client wrapper
     * @return Mono that completes when registration is finished
     */
    public Mono<Void> registerMcpClient(McpClientWrapper mcpClientWrapper) {
        return mcpClientManager.registerMcpClient(mcpClientWrapper);
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

    // ==================== Tool Group Management (Delegated) ====================

    /**
     * Create a new tool group with specified activation status.
     *
     * @param groupName Name of the tool group
     * @param description Description of the tool group
     * @param active Whether the group should be active by default
     * @throws IllegalArgumentException if group already exists
     */
    public void createToolGroup(String groupName, String description, boolean active) {
        groupManager.createToolGroup(groupName, description, active);
    }

    /**
     * Create a new tool group (active by default).
     *
     * @param groupName Name of the tool group
     * @param description Description of the tool group
     * @throws IllegalArgumentException if group already exists
     */
    public void createToolGroup(String groupName, String description) {
        groupManager.createToolGroup(groupName, description);
    }

    /**
     * Update the activation status of tool groups.
     *
     * <p>When {@code allowToolDeletion} is disabled and {@code active} is false, the deactivation
     * will be ignored and a warning will be logged.
     *
     * @param groupNames List of tool group names to update
     * @param active Whether to activate (true) or deactivate (false) the groups
     * @throws IllegalArgumentException if any group doesn't exist
     */
    public void updateToolGroups(List<String> groupNames, boolean active) {
        if (!active && !config.isAllowToolDeletion()) {
            logger.warn(
                    "Tool deletion is disabled - ignoring deactivation of tool groups: {}",
                    groupNames);
            return;
        }
        groupManager.updateToolGroups(groupNames, active);
    }

    /**
     * Remove a tool by name from the toolkit.
     *
     * @param toolName Name of the tool to remove
     */
    public void removeTool(String toolName) {
        if (!config.isAllowToolDeletion()) {
            logger.warn("Tool deletion is disabled - ignoring removal of tool: {}", toolName);
            return;
        }
        toolRegistry.removeTool(toolName);
    }

    /**
     * Remove tool groups and all tools within them.
     *
     * <p>When {@code allowToolDeletion} is disabled, the removal will be ignored and a warning
     * will be logged.
     *
     * @param groupNames List of tool group names to remove
     */
    public void removeToolGroups(List<String> groupNames) {
        if (!config.isAllowToolDeletion()) {
            logger.warn(
                    "Tool deletion is disabled - ignoring removal of tool groups: {}", groupNames);
            return;
        }
        Set<String> toolsToRemove = groupManager.removeToolGroups(groupNames);
        // Remove tools from registry
        toolRegistry.removeTools(toolsToRemove);
    }

    /**
     * Get active tool group names.
     *
     * <p>Returns a list of all currently active tool group names. Only tools belonging to active
     * groups can be called by agents. This method is useful for debugging tool availability
     * and verifying group activation state.
     *
     * @return List of active group names, never null but may be empty
     */
    public List<String> getActiveGroups() {
        return groupManager.getActiveGroups();
    }

    /**
     * Set the active tool groups.
     *
     * <p>This method is typically called by ReActAgent when restoring state from a session.
     *
     * @param groups List of group names to set as active
     */
    public void setActiveGroups(List<String> groups) {
        groupManager.setActiveGroups(groups);
    }

    /**
     * Get a tool group by name.
     *
     * @param groupName Name of the tool group
     * @return ToolGroup or null if not found
     */
    public ToolGroup getToolGroup(String groupName) {
        return groupManager.getToolGroup(groupName);
    }

    // ==================== Meta Tool Registration ====================

    /**
     * Register the meta tool that allows agents to dynamically manage tool groups.
     *
     * This creates a tool that wraps the toolkit's resetEquippedTools method,
     * allowing the agent to activate tool groups during execution.
     */
    public void registerMetaTool() {
        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();

        // Register without group (meta tool is always available)
        registerAgentTool(metaTool, null, null, null, null);

        logger.info("Registered meta tool: reset_equipped_tools");
    }

    /**
     * Update preset parameters for a registered tool at runtime.
     *
     * <p>This method allows dynamic modification of preset parameters without re-registering the
     * tool. This is useful for updating session-specific context (like session IDs or timestamps)
     * or refreshing credentials.
     *
     * @param toolName The name of the tool to update
     * @param newPresetParameters The new preset parameters (null will be treated as empty map)
     * @throws IllegalArgumentException if the tool is not found
     */
    public void updateToolPresetParameters(
            String toolName, Map<String, Object> newPresetParameters) {
        RegisteredToolFunction registered = toolRegistry.getRegisteredTool(toolName);
        if (registered == null) {
            throw new IllegalArgumentException("Tool not found: " + toolName);
        }
        registered.updatePresetParameters(newPresetParameters);
        logger.debug("Updated preset parameters for tool '{}'", toolName);
    }

    // ==================== Deep Copy ====================

    /**
     * Create a deep copy of this toolkit.
     *
     * @return A new Toolkit instance with copied state
     */
    public Toolkit copy() {
        Toolkit copy = new Toolkit(this.config);

        // Copy all registered tools
        this.toolRegistry.copyTo(copy.toolRegistry);

        // Copy all tool groups and their states
        this.groupManager.copyTo(copy.groupManager);

        return copy;
    }

    /**
     * Fluent builder for registering tools with optional configuration.
     *
     * <p>This builder provides a clear, type-safe way to register tools with various options
     * without method proliferation.
     */
    public static class ToolRegistration {
        private final Toolkit toolkit;
        private Object toolObject;
        private AgentTool agentTool;
        private McpClientWrapper mcpClientWrapper;
        private SubAgentProvider<?> subAgentProvider;
        private SubAgentConfig subAgentConfig;
        private String groupName;
        private Map<String, Map<String, Object>> presetParameters;
        private ExtendedModel extendedModel;
        private List<String> enableTools;
        private List<String> disableTools;

        private ToolRegistration(Toolkit toolkit) {
            this.toolkit = toolkit;
        }

        /**
         * Set the tool object to register (scans for @Tool methods).
         *
         * @param toolObject Object containing @Tool annotated methods
         * @return This builder for chaining
         */
        public ToolRegistration tool(Object toolObject) {
            this.toolObject = toolObject;
            return this;
        }

        /**
         * Set the AgentTool instance to register.
         *
         * @param agentTool The AgentTool instance
         * @return This builder for chaining
         */
        public ToolRegistration agentTool(AgentTool agentTool) {
            this.agentTool = agentTool;
            return this;
        }

        /**
         * Set the MCP client to register.
         *
         * @param mcpClientWrapper The MCP client wrapper
         * @return This builder for chaining
         */
        public ToolRegistration mcpClient(McpClientWrapper mcpClientWrapper) {
            this.mcpClientWrapper = mcpClientWrapper;
            return this;
        }

        /**
         * Register a sub-agent as a tool with default configuration.
         *
         * <p>The tool name and description are derived from the agent's properties. Uses a single
         * "task" string parameter by default.
         *
         * <p>Example:
         *
         * <pre>{@code
         * toolkit.registration()
         *     .subAgent(() -> ReActAgent.builder()
         *         .name("ResearchAgent")
         *         .model(model)
         *         .build())
         *     .apply();
         * }</pre>
         *
         * @param provider Factory for creating agent instances (called for each invocation)
         * @return This builder for chaining
         */
        public ToolRegistration subAgent(SubAgentProvider<?> provider) {
            return subAgent(provider, null);
        }

        /**
         * Register a sub-agent as a tool with custom configuration.
         *
         * <p>Sub-agents support multi-turn conversation with session-based state management. The
         * tool exposes two parameters: {@code message} (required) and {@code session_id} (optional,
         * for continuing existing conversations).
         *
         * <p>Example with custom tool name and description:
         *
         * <pre>{@code
         * toolkit.registration()
         *     .subAgent(
         *         () -> ReActAgent.builder().name("Expert").model(model).build(),
         *         SubAgentConfig.builder()
         *             .toolName("ask_expert")
         *             .description("Ask the domain expert a question")
         *             .build())
         *     .apply();
         * }</pre>
         *
         * <p>Example with persistent session for cross-process conversations:
         *
         * <pre>{@code
         * toolkit.registration()
         *     .subAgent(
         *         () -> ReActAgent.builder().name("Assistant").model(model).build(),
         *         SubAgentConfig.builder()
         *             .session(new JsonSession(Path.of("sessions")))
         *             .forwardEvents(true)
         *             .build())
         *     .apply();
         * }</pre>
         *
         * @param provider Factory for creating agent instances (called for each session)
         * @param config Configuration for the sub-agent tool, or null to use defaults (tool name
         *     derived from agent name, InMemorySession for state, events forwarded)
         * @return This builder for chaining
         * @see SubAgentConfig
         * @see SubAgentConfig#defaults()
         */
        public ToolRegistration subAgent(SubAgentProvider<?> provider, SubAgentConfig config) {
            this.subAgentProvider = provider;
            this.subAgentConfig = config;
            return this;
        }

        /**
         * Set the list of tools to enable from the MCP client.
         *
         * <p>Only applicable when using mcpClient(). If not specified, all tools are enabled.
         *
         * @param enableTools List of tool names to enable
         * @return This builder for chaining
         */
        public ToolRegistration enableTools(List<String> enableTools) {
            this.enableTools = enableTools;
            return this;
        }

        /**
         * Set the list of tools to disable from the MCP client.
         *
         * <p>Only applicable when using mcpClient().
         *
         * @param disableTools List of tool names to disable
         * @return This builder for chaining
         */
        public ToolRegistration disableTools(List<String> disableTools) {
            this.disableTools = disableTools;
            return this;
        }

        /**
         * Set the tool group name.
         *
         * @param groupName The group name (null for ungrouped)
         * @return This builder for chaining
         */
        public ToolRegistration group(String groupName) {
            this.groupName = groupName;
            return this;
        }

        /**
         * Set preset parameters that will be automatically injected during tool execution.
         *
         * <p>These parameters are not exposed in the JSON schema.
         *
         * <p>The map should have tool names as keys and parameter maps as values:
         * <pre>{@code
         * Map.of(
         *     "toolName1", Map.of("param1", "value1", "param2", "value2"),
         *     "toolName2", Map.of("param1", "value3")
         * )
         * }</pre>
         *
         * @param presetParameters Map from tool name to its preset parameters
         * @return This builder for chaining
         */
        public ToolRegistration presetParameters(
                Map<String, Map<String, Object>> presetParameters) {
            this.presetParameters = presetParameters;
            return this;
        }

        /**
         * Set the extended model for dynamic schema extension.
         *
         * @param extendedModel The extended model
         * @return This builder for chaining
         */
        public ToolRegistration extendedModel(ExtendedModel extendedModel) {
            this.extendedModel = extendedModel;
            return this;
        }

        /**
         * Apply the registration with all configured options.
         *
         * @throws IllegalStateException if none of tool(), agentTool(), mcpClient() or subAgent() was set
         * @throws IllegalStateException if set multiple of: tool(), agentTool(), mcpClient(), or subAgent().
         */
        public void apply() {
            int toolCount = 0;
            if (toolObject != null) toolCount++;
            if (agentTool != null) toolCount++;
            if (mcpClientWrapper != null) toolCount++;
            if (subAgentProvider != null) toolCount++;

            if (toolCount == 0) {
                throw new IllegalStateException(
                        "Must call one of: tool(), agentTool(), mcpClient(), or subAgent() before"
                                + " apply()");
            }
            if (toolCount > 1) {
                throw new IllegalStateException(
                        "Cannot set multiple registration types. Use only one of: tool(),"
                                + " agentTool(), mcpClient(), or subAgent().");
            }

            if (toolObject != null) {
                toolkit.registerTool(toolObject, groupName, extendedModel, presetParameters);
            } else if (agentTool != null) {
                String toolName = agentTool.getName();
                Map<String, Object> toolPresets =
                        (presetParameters != null && presetParameters.containsKey(toolName))
                                ? presetParameters.get(toolName)
                                : null;
                toolkit.registerAgentTool(agentTool, groupName, extendedModel, null, toolPresets);
            } else if (mcpClientWrapper != null) {
                toolkit.mcpClientManager
                        .registerMcpClient(
                                mcpClientWrapper,
                                enableTools,
                                disableTools,
                                groupName,
                                presetParameters)
                        .block();
            } else if (subAgentProvider != null) {
                SubAgentTool subAgentTool = new SubAgentTool(subAgentProvider, subAgentConfig);
                toolkit.registerAgentTool(subAgentTool, groupName, extendedModel, null, null);
            }
        }
    }
}
