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
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Toolkit {

    private final Map<String, AgentTool> tools = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

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

        // Determine tool name
        String toolName =
                !toolAnnotation.name().isEmpty() ? toolAnnotation.name() : method.getName();

        // Determine tool description
        String description =
                !toolAnnotation.description().isEmpty()
                        ? toolAnnotation.description()
                        : "Tool: " + toolName;

        // Extract result converter class from annotation
        Class<? extends ToolCallResultConverter> resultConverterClass =
                toolAnnotation.resultConverter() == null
                        ? DefaultToolCallResultConverter.class
                        : toolAnnotation.resultConverter();

        // Create tool wrapper
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
                        return generateParameterSchema(method);
                    }

                    @Override
                    public ToolResponse call(Map<String, Object> input) {
                        ToolCallResultConverter converter = new DefaultToolCallResultConverter();
                        try {
                            converter = resultConverterClass.getDeclaredConstructor().newInstance();
                        } catch (Exception e) {
                            // Fallback to default converter if instantiation fails
                        }
                        return invokeToolMethod(toolObject, method, input, converter);
                    }
                };

        tools.put(toolName, tool);
    }

    /**
     * Generate parameter schema for a method.
     */
    private Map<String, Object> generateParameterSchema(Method method) {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        List<String> required = new ArrayList<>();

        Parameter[] parameters = method.getParameters();
        for (Parameter param : parameters) {
            ToolParam toolParam = param.getAnnotation(ToolParam.class);
            String paramName = param.getName();

            Map<String, Object> paramSchema = new HashMap<>();
            paramSchema.put("type", getJsonType(param.getType()));

            if (toolParam != null) {
                if (!toolParam.description().isEmpty()) {
                    paramSchema.put("description", toolParam.description());
                }
                if (toolParam.required()) {
                    required.add(paramName);
                }
            }

            properties.put(paramName, paramSchema);
        }

        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }

        return schema;
    }

    /**
     * Get JSON type for Java class.
     */
    private String getJsonType(Class<?> clazz) {
        if (clazz == String.class) {
            return "string";
        } else if (clazz == Integer.class
                || clazz == int.class
                || clazz == Long.class
                || clazz == long.class) {
            return "integer";
        } else if (clazz == Double.class
                || clazz == double.class
                || clazz == Float.class
                || clazz == float.class) {
            return "number";
        } else if (clazz == Boolean.class || clazz == boolean.class) {
            return "boolean";
        } else if (clazz.isArray() || List.class.isAssignableFrom(clazz)) {
            return "array";
        } else {
            return "object";
        }
    }

    /**
     * Invoke tool method with input.
     */
    private ToolResponse invokeToolMethod(
            Object toolObject,
            Method method,
            Map<String, Object> input,
            ToolCallResultConverter converter) {
        try {
            method.setAccessible(true);
            Parameter[] parameters = method.getParameters();

            Object result;
            if (parameters.length == 0) {
                result = method.invoke(toolObject);
            } else if (parameters.length == 1) {
                // Convert input map to parameter type
                Object paramValue = convertInputToParameter(input, parameters[0]);
                result = method.invoke(toolObject, paramValue);
            } else {
                // For multiple parameters, extract by parameter name
                Object[] args = new Object[parameters.length];
                for (int i = 0; i < parameters.length; i++) {
                    Parameter param = parameters[i];
                    Object paramValue =
                            convertInputToParameter(
                                    Collections.singletonMap(
                                            param.getName(), input.get(param.getName())),
                                    param);
                    args[i] = paramValue;
                }
                result = method.invoke(toolObject, args);
            }

            return converter.convert(result, method.getReturnType());

        } catch (Exception e) {
            return ToolResponse.error("Tool execution failed: " + e.getMessage());
        }
    }

    /**
     * Convert input map value to parameter type.
     */
    private Object convertInputToParameter(Map<String, Object> input, Parameter parameter) {
        String paramName = parameter.getName();
        Object value = input.get(paramName);

        if (value == null) {
            return null;
        }

        Class<?> paramType = parameter.getType();

        if (paramType.isAssignableFrom(value.getClass())) {
            return value;
        }

        try {
            return objectMapper.convertValue(value, paramType);
        } catch (Exception e) {
            // Fallback to string conversion for simple types
            String stringValue = value.toString();

            if (paramType == Integer.class || paramType == int.class) {
                return Integer.parseInt(stringValue);
            } else if (paramType == Long.class || paramType == long.class) {
                return Long.parseLong(stringValue);
            } else if (paramType == Double.class || paramType == double.class) {
                return Double.parseDouble(stringValue);
            } else if (paramType == Boolean.class || paramType == boolean.class) {
                return Boolean.parseBoolean(stringValue);
            }

            return stringValue;
        }
    }

    /**
     * Call a tool using a ToolUseBlock and return a ToolResponse.
     * This method is used by the ParallelToolExecutor.
     *
     * @param toolCall The tool use block containing tool name and arguments
     * @return ToolResponse containing the result
     */
    public ToolResponse callTool(ToolUseBlock toolCall) {
        try {
            AgentTool tool = getTool(toolCall.getName());
            if (tool == null) {
                return ToolResponse.error("Tool not found: " + toolCall.getName());
            }

            // Execute the tool
            return tool.call(toolCall.getInput());

        } catch (Exception e) {
            return ToolResponse.error("Tool execution failed: " + e.getMessage());
        }
    }
}
