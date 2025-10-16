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
import io.agentscope.core.message.ToolUseBlock;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Toolkit manages the registration, retrieval, and execution of agent tools.
 * This class acts as a facade, delegating specific responsibilities to specialized components:
 * - ToolSchemaGenerator: Generates JSON schemas for tool parameters
 * - ToolMethodInvoker: Handles method invocation and parameter conversion
 * - ToolResponseConverter: Converts method results to ToolResponse
 */
public class Toolkit {

    private final Map<String, AgentTool> tools = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ToolSchemaGenerator schemaGenerator = new ToolSchemaGenerator();
    private final ToolResponseConverter responseConverter;
    private final ToolMethodInvoker methodInvoker;

    public Toolkit() {
        this.responseConverter = new ToolResponseConverter(objectMapper);
        this.methodInvoker = new ToolMethodInvoker(objectMapper, responseConverter);
    }

    /**
     * Register a tool object by scanning for methods annotated with @Tool.
     * @param toolObject the object containing tool methods
     */
    public void registerTool(Object toolObject) {
        if (toolObject == null) {
            throw new IllegalArgumentException("Tool object cannot be null");
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
                    public ToolResponse call(Map<String, Object> input) {
                        return methodInvoker.invoke(toolObject, method, input);
                    }
                };

        tools.put(toolName, tool);
    }

    /**
     * Call a tool using a ToolUseBlock and return a ToolResponse.
     *
     * @param toolCall The tool use block containing tool name and arguments
     * @return ToolResponse containing the result
     */
    public ToolResponse callTool(ToolUseBlock toolCall) {
        AgentTool tool = getTool(toolCall.getName());
        if (tool == null) {
            return ToolResponse.error("Tool not found: " + toolCall.getName());
        }

        try {
            return tool.call(toolCall.getInput());
        } catch (Exception e) {
            String errorMsg =
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return ToolResponse.error("Tool execution failed: " + errorMsg);
        }
    }
}
