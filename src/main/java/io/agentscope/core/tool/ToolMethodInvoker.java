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
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import reactor.core.publisher.Mono;

/**
 * Invokes tool methods with type conversion and error handling.
 * This class handles reflection-based method invocation and parameter conversion.
 */
class ToolMethodInvoker {

    private final ObjectMapper objectMapper;
    private final ToolResultConverter resultConverter;
    private BiConsumer<ToolUseBlock, ToolResultBlock> chunkCallback;

    ToolMethodInvoker(ObjectMapper objectMapper, ToolResultConverter resultConverter) {
        this.objectMapper = objectMapper;
        this.resultConverter = resultConverter;
    }

    /**
     * Set the chunk callback for delivering streaming chunks from ToolEmitter.
     *
     * @param callback Callback to invoke when tools emit chunks
     */
    void setChunkCallback(BiConsumer<ToolUseBlock, ToolResultBlock> callback) {
        this.chunkCallback = callback;
    }

    /**
     * Invoke tool method with input (synchronous).
     *
     * @param toolObject the object containing the method
     * @param method the method to invoke
     * @param input the input parameters
     * @return ToolResultBlock containing the result or error
     * @deprecated Use {@link #invokeAsync(Object, Method, Map)} instead
     */
    @Deprecated
    ToolResultBlock invoke(Object toolObject, Method method, Map<String, Object> input) {
        try {
            method.setAccessible(true);
            Object[] args = convertParameters(method, input);
            Object result = method.invoke(toolObject, args);
            return resultConverter.convert(result, method.getReturnType());
        } catch (Exception e) {
            return handleInvocationError(e);
        }
    }

    /**
     * Invoke tool method asynchronously with support for CompletableFuture and Mono return types.
     *
     * @param toolObject the object containing the method
     * @param method the method to invoke
     * @param input the input parameters
     * @param toolUseBlock the tool use block (for ToolEmitter injection)
     * @return Mono containing ToolResultBlock
     */
    Mono<ToolResultBlock> invokeAsync(
            Object toolObject,
            Method method,
            Map<String, Object> input,
            ToolUseBlock toolUseBlock) {
        Class<?> returnType = method.getReturnType();

        if (returnType == CompletableFuture.class) {
            // Async method returning CompletableFuture: invoke and convert to Mono
            return Mono.fromCallable(
                            () -> {
                                method.setAccessible(true);
                                Object[] args = convertParameters(method, input, toolUseBlock);
                                @SuppressWarnings("unchecked")
                                CompletableFuture<Object> future =
                                        (CompletableFuture<Object>) method.invoke(toolObject, args);
                                return future;
                            })
                    .flatMap(
                            future ->
                                    Mono.fromFuture(future)
                                            .map(
                                                    r ->
                                                            resultConverter.convert(
                                                                    r, extractGenericType(method)))
                                            .onErrorResume(
                                                    e ->
                                                            Mono.just(
                                                                    handleInvocationError(
                                                                            e instanceof Exception
                                                                                    ? (Exception) e
                                                                                    : new RuntimeException(
                                                                                            e)))));

        } else if (returnType == Mono.class) {
            // Async method returning Mono: invoke and flatMap
            return Mono.fromCallable(
                            () -> {
                                method.setAccessible(true);
                                Object[] args = convertParameters(method, input, toolUseBlock);
                                @SuppressWarnings("unchecked")
                                Mono<Object> mono = (Mono<Object>) method.invoke(toolObject, args);
                                return mono;
                            })
                    .flatMap(
                            mono ->
                                    mono.map(
                                                    r ->
                                                            resultConverter.convert(
                                                                    r, extractGenericType(method)))
                                            .onErrorResume(
                                                    e ->
                                                            Mono.just(
                                                                    handleInvocationError(
                                                                            e instanceof Exception
                                                                                    ? (Exception) e
                                                                                    : new RuntimeException(
                                                                                            e)))));

        } else {
            // Sync method: wrap in Mono.fromCallable
            return Mono.fromCallable(
                            () -> {
                                method.setAccessible(true);
                                Object[] args = convertParameters(method, input, toolUseBlock);
                                Object result = method.invoke(toolObject, args);
                                return resultConverter.convert(result, method.getReturnType());
                            })
                    .onErrorResume(
                            e ->
                                    Mono.just(
                                            handleInvocationError(
                                                    e instanceof Exception
                                                            ? (Exception) e
                                                            : new RuntimeException(e))));
        }
    }

    /**
     * Invoke tool method asynchronously (backward compatibility - no ToolEmitter support).
     *
     * @param toolObject the object containing the method
     * @param method the method to invoke
     * @param input the input parameters
     * @return Mono containing ToolResultBlock
     */
    Mono<ToolResultBlock> invokeAsync(Object toolObject, Method method, Map<String, Object> input) {
        return invokeAsync(toolObject, method, input, null);
    }

    /**
     * Convert input parameters to method arguments.
     *
     * @param method the method
     * @param input the input map
     * @return array of converted arguments
     */
    private Object[] convertParameters(Method method, Map<String, Object> input) {
        return convertParameters(method, input, null);
    }

    /**
     * Convert input parameters to method arguments with ToolEmitter support.
     *
     * @param method the method
     * @param input the input map
     * @param toolUseBlock the tool use block for ToolEmitter injection (may be null)
     * @return array of converted arguments
     */
    private Object[] convertParameters(
            Method method, Map<String, Object> input, ToolUseBlock toolUseBlock) {
        Parameter[] parameters = method.getParameters();

        if (parameters.length == 0) {
            return new Object[0];
        }

        Object[] args = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            Parameter param = parameters[i];

            // Special handling: inject ToolEmitter automatically
            if (param.getType() == ToolEmitter.class) {
                args[i] = new DefaultToolEmitter(toolUseBlock, chunkCallback);
            } else {
                args[i] = convertSingleParameter(param, input);
            }
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
        // First check for @ToolParam annotation to get explicit parameter name
        String paramName = parameter.getName(); // fallback to reflection name
        ToolParam toolParamAnnotation = parameter.getAnnotation(ToolParam.class);
        if (toolParamAnnotation != null && !toolParamAnnotation.name().isEmpty()) {
            paramName = toolParamAnnotation.name();
        }

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
     * @return ToolResultBlock with error message
     */
    private ToolResultBlock handleInvocationError(Exception e) {
        Throwable cause = e.getCause();
        String errorMsg = cause != null ? getErrorMessage(cause) : getErrorMessage(e);
        return ToolResultBlock.error("Tool execution failed: " + errorMsg);
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

    /**
     * Extract generic type from method return type (for CompletableFuture<T> or Mono<T>).
     *
     * @param method the method
     * @return the generic type, or null if not found
     */
    private Type extractGenericType(Method method) {
        Type genericReturnType = method.getGenericReturnType();
        if (genericReturnType instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) genericReturnType;
            Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
            if (actualTypeArguments.length > 0) {
                return actualTypeArguments[0];
            }
        }
        return null;
    }
}
