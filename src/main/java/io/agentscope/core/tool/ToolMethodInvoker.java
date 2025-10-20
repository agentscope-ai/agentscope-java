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
import io.agentscope.core.exception.ToolInterruptedException;
import io.agentscope.core.exception.ToolInterrupter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Invokes tool methods with type conversion and error handling.
 * This class handles reflection-based method invocation and parameter conversion.
 *
 * <p>This class also manages interruption detection using ThreadLocal state tracking.
 * Even if a tool catches {@link ToolInterruptedException}, the framework will detect
 * the interruption and return an interrupted response.
 */
class ToolMethodInvoker {

    private static final Logger logger = LoggerFactory.getLogger(ToolMethodInvoker.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    private final ObjectMapper objectMapper;
    private final ToolResponseConverter responseConverter;

    ToolMethodInvoker(ObjectMapper objectMapper, ToolResponseConverter responseConverter) {
        this.objectMapper = objectMapper;
        this.responseConverter = responseConverter;
    }

    /**
     * Invoke tool method with input (synchronous).
     *
     * @param toolObject the object containing the method
     * @param method the method to invoke
     * @param input the input parameters
     * @return ToolResponse containing the result or error
     * @deprecated Use {@link #invokeAsync(Object, Method, Map)} instead
     */
    @Deprecated
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
     * Invoke tool method asynchronously with support for CompletableFuture and Mono return types.
     *
     * <p>This method includes interruption detection:
     * - Resets interruption state before execution
     * - Checks for interruptions after execution (even if exception was caught)
     * - Cleans up ThreadLocal to prevent memory leaks
     * - Applies timeout protection
     *
     * @param toolObject the object containing the method
     * @param method the method to invoke
     * @param input the input parameters
     * @return Mono containing ToolResponse
     */
    Mono<ToolResponse> invokeAsync(Object toolObject, Method method, Map<String, Object> input) {
        Class<?> returnType = method.getReturnType();

        if (returnType == CompletableFuture.class) {
            // Async method returning CompletableFuture
            return executeWithInterruptionHandling(
                    () -> {
                        method.setAccessible(true);
                        Object[] args = convertParameters(method, input);
                        @SuppressWarnings("unchecked")
                        CompletableFuture<Object> future =
                                (CompletableFuture<Object>) method.invoke(toolObject, args);
                        return future;
                    },
                    future -> Mono.fromFuture(future),
                    extractGenericType(method));

        } else if (returnType == Mono.class) {
            // Async method returning Mono
            return executeWithInterruptionHandling(
                    () -> {
                        method.setAccessible(true);
                        Object[] args = convertParameters(method, input);
                        @SuppressWarnings("unchecked")
                        Mono<Object> mono = (Mono<Object>) method.invoke(toolObject, args);
                        return mono;
                    },
                    mono -> mono,
                    extractGenericType(method));

        } else {
            // Sync method
            return executeWithInterruptionHandling(
                    () -> {
                        method.setAccessible(true);
                        Object[] args = convertParameters(method, input);
                        return method.invoke(toolObject, args);
                    },
                    result -> Mono.just(result),
                    method.getReturnType());
        }
    }

    /**
     * Execute tool method with interruption handling and cleanup.
     *
     * @param invoker function that invokes the tool method
     * @param resultConverter function to convert result to Mono
     * @param resultType the expected result type
     * @param <T> the intermediate result type
     * @return Mono containing ToolResponse
     */
    private <T> Mono<ToolResponse> executeWithInterruptionHandling(
            ThrowingSupplier<T> invoker,
            java.util.function.Function<T, Mono<Object>> resultConverter,
            Type resultType) {

        return Mono.fromCallable(
                        () -> {
                            // Reset interruption state before execution
                            ToolInterrupter.reset();

                            try {
                                return invoker.get();
                            } catch (ToolInterruptedException e) {
                                // Catch interruption during method.invoke()
                                logger.info(
                                        "Tool execution interrupted during invocation: {}",
                                        e.getMessage());
                                throw e; // Re-throw to be handled by onErrorResume
                            } catch (InvocationTargetException e) {
                                // Unwrap and check for ToolInterruptedException
                                Throwable cause = e.getCause();
                                if (cause instanceof ToolInterruptedException) {
                                    logger.info(
                                            "Tool execution interrupted (wrapped): {}",
                                            cause.getMessage());
                                    throw (ToolInterruptedException) cause;
                                }
                                throw e;
                            }
                        })
                .flatMap(resultConverter)
                .map(
                        result -> {
                            // Check interruption state after execution
                            // This catches cases where tool caught the exception
                            if (ToolInterrupter.isInterrupted()) {
                                ToolInterrupter.InterruptionState state =
                                        ToolInterrupter.getState();
                                logger.warn(
                                        "Tool was interrupted but exception was caught. "
                                                + "Ignoring returned result. Reason: {}",
                                        state != null ? state.message : "unknown");
                                return ToolResponse.interrupted();
                            }

                            // Normal execution
                            return responseConverter.convert(result, resultType);
                        })
                .doFinally(signal -> ToolInterrupter.reset()) // Clean up ThreadLocal
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(DEFAULT_TIMEOUT)
                .onErrorResume(this::handleExecutionError);
    }

    /**
     * Handle execution errors including interruptions and timeouts.
     */
    private Mono<ToolResponse> handleExecutionError(Throwable e) {
        // Handle InvocationTargetException (wraps the actual exception)
        if (e instanceof InvocationTargetException) {
            Throwable cause = ((InvocationTargetException) e).getCause();
            if (cause instanceof ToolInterruptedException) {
                logger.info("Tool execution interrupted: {}", cause.getMessage());
                return Mono.just(ToolResponse.interrupted());
            }
            e = cause != null ? cause : e;
        }

        // Handle ToolInterruptedException directly
        if (e instanceof ToolInterruptedException) {
            logger.info("Tool execution interrupted: {}", e.getMessage());
            return Mono.just(ToolResponse.interrupted());
        }

        // Handle timeout as interruption
        if (e instanceof TimeoutException) {
            logger.warn("Tool execution timeout after {}", DEFAULT_TIMEOUT);
            return Mono.just(ToolResponse.interrupted());
        }

        // Handle other exceptions
        return Mono.just(
                handleInvocationError(
                        e instanceof Exception ? (Exception) e : new RuntimeException(e)));
    }

    /**
     * Functional interface for suppliers that can throw exceptions.
     */
    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
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
