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
 */
public class Toolkit {

    private static final Logger logger = LoggerFactory.getLogger(Toolkit.class);

    private final Map<String, AgentTool> tools = new ConcurrentHashMap<>();
    private final Map<String, McpClientWrapper> mcpClients = new ConcurrentHashMap<>();
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
    }

    /**
     * Register a tool object by scanning for methods annotated with @Tool.
     * @param toolObject the object containing tool methods
     */
    public void registerTool(Object toolObject) {
        if (toolObject == null) {
            throw new IllegalArgumentException("Tool object cannot be null");
        }

        // Check if the object is an AgentTool instance
        if (toolObject instanceof AgentTool) {
            registerAgentTool((AgentTool) toolObject);
            return;
        }

        Class<?> clazz = toolObject.getClass();
        Method[] methods = clazz.getDeclaredMethods();

        for (Method method : methods) {
            if (method.isAnnotationPresent(Tool.class)) {
                registerToolMethod(toolObject, method);
            }
        }
    }

    /**
     * Register an AgentTool instance directly.
     * @param tool the AgentTool to register
     */
    public void registerAgentTool(AgentTool tool) {
        if (tool == null) {
            throw new IllegalArgumentException("AgentTool cannot be null");
        }
        tools.put(tool.getName(), tool);
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
     * Get tool schemas in OpenAI format.
     */
    public List<Map<String, Object>> getToolSchemas() {
        List<Map<String, Object>> schemas = new ArrayList<>();

        // Add regular tools
        for (AgentTool tool : tools.values()) {
            Map<String, Object> schema = new HashMap<>();
            schema.put("type", "function");

            Map<String, Object> function = new HashMap<>();
            function.put("name", tool.getName());
            function.put("description", tool.getDescription());
            function.put("parameters", tool.getParameters());

            schema.put("function", function);
            schemas.add(schema);
        }

        return schemas;
    }

    /**
     * Get tool schemas as ToolSchema objects for model consumption.
     * This method converts the internal tool representation to the format expected by models.
     *
     * @return List of ToolSchema objects
     */
    public List<ToolSchema> getToolSchemasForModel() {
        List<ToolSchema> schemas = new ArrayList<>();

        for (AgentTool tool : tools.values()) {
            ToolSchema schema =
                    ToolSchema.builder()
                            .name(tool.getName())
                            .description(tool.getDescription())
                            .parameters(tool.getParameters())
                            .build();
            schemas.add(schema);
        }

        return schemas;
    }

    /**
     * Register a single tool method.
     */
    private void registerToolMethod(Object toolObject, Method method) {
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

        tools.put(toolName, tool);
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

        if (mcpClientWrapper == null) {
            return Mono.error(new IllegalArgumentException("MCP client wrapper cannot be null"));
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
                                    "Registering MCP tool: {} from client {}",
                                    mcpTool.name(),
                                    mcpClientWrapper.getName());

                            McpTool agentTool =
                                    new McpTool(
                                            mcpTool.name(),
                                            mcpTool.description() != null
                                                    ? mcpTool.description()
                                                    : "",
                                            McpTool.convertMcpSchemaToParameters(
                                                    mcpTool.inputSchema()),
                                            mcpClientWrapper);
                            registerAgentTool(agentTool);
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
                tools.values().stream()
                        .filter(tool -> tool instanceof McpTool)
                        .filter(tool -> ((McpTool) tool).getClientName().equals(mcpClientName))
                        .map(AgentTool::getName)
                        .collect(Collectors.toList());

        toolsToRemove.forEach(
                toolName -> {
                    tools.remove(toolName);
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
}
