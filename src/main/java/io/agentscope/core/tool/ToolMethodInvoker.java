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
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Map;

/**
 * Invokes tool methods with type conversion and error handling.
 * This class handles reflection-based method invocation and parameter conversion.
 */
class ToolMethodInvoker {

    private final ObjectMapper objectMapper;
    private final ToolResponseConverter responseConverter;

    ToolMethodInvoker(ObjectMapper objectMapper, ToolResponseConverter responseConverter) {
        this.objectMapper = objectMapper;
        this.responseConverter = responseConverter;
    }

    /**
     * Invoke tool method with input.
     *
     * @param toolObject the object containing the method
     * @param method the method to invoke
     * @param input the input parameters
     * @return ToolResponse containing the result or error
     */
    ToolResponse invoke(Object toolObject, Method method, Map<String, Object> input) {
        try {
            method.setAccessible(true);
            Object[] args = convertParameters(method, input);
            Object result = method.invoke(toolObject, args);
            return responseConverter.convert(result, method.getReturnType());
        } catch (Exception e) {
            return handleInvocationError(e);
        }
    }

    /**
     * Convert input parameters to method arguments.
     *
     * @param method the method
     * @param input the input map
     * @return array of converted arguments
     */
    private Object[] convertParameters(Method method, Map<String, Object> input) {
        Parameter[] parameters = method.getParameters();

        if (parameters.length == 0) {
            return new Object[0];
        }

        Object[] args = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            args[i] = convertSingleParameter(parameters[i], input);
        }

        return args;
    }

    /**
     * Convert a single parameter from input map.
     *
     * @param parameter the parameter to convert
     * @param input the input map
     * @return converted parameter value
     */
    private Object convertSingleParameter(Parameter parameter, Map<String, Object> input) {
        String paramName = parameter.getName();
        Object value = input.get(paramName);

        if (value == null) {
            return null;
        }

        Class<?> paramType = parameter.getType();

        // Direct assignment if types match
        if (paramType.isAssignableFrom(value.getClass())) {
            return value;
        }

        // Try ObjectMapper conversion first
        try {
            return objectMapper.convertValue(value, paramType);
        } catch (Exception e) {
            // Fallback to string-based conversion for primitives
            return convertFromString(value.toString(), paramType);
        }
    }

    /**
     * Convert string value to target type (fallback for primitives).
     *
     * @param stringValue the string value
     * @param targetType the target type
     * @return converted value
     */
    private Object convertFromString(String stringValue, Class<?> targetType) {
        if (targetType == Integer.class || targetType == int.class) {
            return Integer.parseInt(stringValue);
        } else if (targetType == Long.class || targetType == long.class) {
            return Long.parseLong(stringValue);
        } else if (targetType == Double.class || targetType == double.class) {
            return Double.parseDouble(stringValue);
        } else if (targetType == Boolean.class || targetType == boolean.class) {
            return Boolean.parseBoolean(stringValue);
        } else if (targetType == Float.class || targetType == float.class) {
            return Float.parseFloat(stringValue);
        }
        return stringValue;
    }

    /**
     * Handle invocation errors with informative messages.
     *
     * @param e the exception
     * @return ToolResponse with error message
     */
    private ToolResponse handleInvocationError(Exception e) {
        Throwable cause = e.getCause();
        String errorMsg = cause != null ? getErrorMessage(cause) : getErrorMessage(e);
        return ToolResponse.error("Tool execution failed: " + errorMsg);
    }

    /**
     * Extract error message from throwable.
     *
     * @param throwable the exception
     * @return error message
     */
    private String getErrorMessage(Throwable throwable) {
        if (throwable == null) {
            return "Unknown error";
        }

        String message = throwable.getMessage();
        if (message != null && !message.isEmpty()) {
            return message;
        }

        Throwable cause = throwable.getCause();
        if (cause != null && cause.getMessage() != null) {
            return cause.getMessage();
        }

        return throwable.getClass().getSimpleName();
    }
}
