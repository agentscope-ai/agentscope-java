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
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
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

    private final Map<String, AgentTool> tools = new ConcurrentHashMap<>();
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
     * Set the interrupt flag for tool execution.
     * This flag is checked by tools during execution to support cooperative interruption.
     *
     * @param interruptFlag Atomic boolean flag for interrupt checking
     */
    public void setInterruptFlag(AtomicBoolean interruptFlag) {
        methodInvoker.setInterruptFlag(interruptFlag);
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
}
