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

import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.util.ExceptionUtils;
import io.agentscope.core.util.JsonUtils;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import reactor.core.publisher.Mono;

/**
 * Invokes tool methods with type conversion and error handling.
 * This class handles reflection-based method invocation and parameter conversion.
 */
class ToolMethodInvoker {

    private final ToolResultConverter defaultConverter;

    ToolMethodInvoker(ToolResultConverter resultConverter) {
        this.defaultConverter = resultConverter;
    }

    /**
     * Invoke tool method asynchronously with custom converter support.
     *
     * @param toolObject the object containing the method
     * @param method the method to invoke
     * @param param the tool call parameters containing input, toolUseBlock, agent, and context
     * @param customConverter custom converter for this invocation (null to use default)
     * @return Mono containing ToolResultBlock
     */
    Mono<ToolResultBlock> invokeAsync(
            Object toolObject,
            Method method,
            ToolCallParam param,
            ToolResultConverter customConverter) {
        // Use custom converter if provided, otherwise use default
        final ToolResultConverter converter =
                customConverter != null ? customConverter : defaultConverter;

        Map<String, Object> input = param.getInput();
        Agent agent = param.getAgent();
        ToolExecutionContext context = param.getContext();
        ToolEmitter emitter = param.getEmitter();

        Class<?> returnType = method.getReturnType();

        if (returnType == CompletableFuture.class) {
            // Async method returning CompletableFuture: invoke and convert to Mono
            return Mono.fromCallable(
                            () -> {
                                method.setAccessible(true);
                                Object[] args =
                                        convertParameters(method, input, agent, context, emitter);
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
                                                            converter.convert(
                                                                    r, extractGenericType(method)))
                                            .onErrorResume(this::handleError))
                    .onErrorResume(this::handleError);

        } else if (returnType == Mono.class) {
            // Async method returning Mono: invoke and flatMap
            return Mono.fromCallable(
                            () -> {
                                method.setAccessible(true);
                                Object[] args =
                                        convertParameters(method, input, agent, context, emitter);
                                @SuppressWarnings("unchecked")
                                Mono<Object> mono = (Mono<Object>) method.invoke(toolObject, args);
                                return mono;
                            })
                    .flatMap(
                            mono ->
                                    mono.map(r -> converter.convert(r, extractGenericType(method)))
                                            .onErrorResume(this::handleError))
                    .onErrorResume(this::handleError);

        } else {
            // Sync method: wrap in Mono.fromCallable
            return Mono.fromCallable(
                            () -> {
                                method.setAccessible(true);
                                Object[] args =
                                        convertParameters(method, input, agent, context, emitter);
                                Object result = method.invoke(toolObject, args);
                                return converter.convert(result, method.getGenericReturnType());
                            })
                    .onErrorResume(this::handleError);
        }
    }

    /**
     * Convert input parameters to method arguments with automatic injection support.
     *
     * <p>This method handles automatic injection of framework-managed objects:
     * <ul>
     *   <li>{@link ToolEmitter} - Streaming output emitter</li>
     *   <li>{@link Agent} - Current agent instance</li>
     *   <li>{@link ToolExecutionContext} - Business context</li>
     *   <li>Custom POJO types - Retrieved from ToolExecutionContext by type</li>
     * </ul>
     *
     * <p>Parameters without {@link ToolParam} annotation are treated as auto-injected types.
     * Parameters with {@link ToolParam} are converted from the input map.
     *
     * @param method the method
     * @param input the input map
     * @param agent the agent for Agent injection (may be null)
     * @param context the tool execution context for ToolExecutionContext injection (may be null)
     * @param emitter the tool emitter for ToolEmitter injection (may be null)
     * @return array of converted arguments
     */
    private Object[] convertParameters(
            Method method,
            Map<String, Object> input,
            Agent agent,
            ToolExecutionContext context,
            ToolEmitter emitter) {
        Parameter[] parameters = method.getParameters();

        if (parameters.length == 0) {
            return new Object[0];
        }

        Object[] args = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            Parameter param = parameters[i];

            // Special handling: inject ToolEmitter automatically
            if (param.getType() == ToolEmitter.class) {
                args[i] = emitter;
            }
            // Special handling: inject Agent automatically
            else if (param.getType() == Agent.class) {
                args[i] = agent;
            }
            // Special handling: inject ToolExecutionContext automatically
            else if (param.getType() == ToolExecutionContext.class) {
                args[i] = context;
            }
            // User-defined POJO: try to resolve from context
            else if (isUserContextPojo(param)) {
                args[i] = resolveContextParameter(param, context);
            } else {
                args[i] = convertSingleParameter(param, input);
            }
        }

        return args;
    }

    /**
     * Check if a parameter is a user-defined context POJO that should be resolved from
     * ToolExecutionContext.
     *
     * <p>Note: This method assumes ToolEmitter, Agent, and ToolExecutionContext have already been
     * filtered at the call site.
     *
     * <p>A parameter is considered a user context POJO if:
     * <ul>
     *   <li>It does NOT have @ToolParam annotation (not a tool input from LLM)</li>
     *   <li>It is NOT a primitive type (primitives must be tool inputs)</li>
     *   <li>It is NOT a framework message type (ContentBlock subclasses, Msg, etc.)</li>
     *   <li>It is NOT a common Java library type (String, List, Map, etc.)</li>
     * </ul>
     *
     * @param param The parameter to check
     * @return true if the parameter should be resolved from context as user POJO
     */
    private boolean isUserContextPojo(Parameter param) {
        // 1. Explicitly annotated with @ToolParam → tool input from LLM
        if (param.getAnnotation(ToolParam.class) != null) {
            return false;
        }

        Class<?> type = param.getType();

        // 2. Primitive types → must be tool inputs
        if (type.isPrimitive()) {
            return false;
        }

        // 3. Framework message types (ContentBlock, Msg, etc.) → not user POJOs
        // Check by class hierarchy rather than package to avoid excluding test classes
        try {
            if (ContentBlock.class.isAssignableFrom(type) || type == Msg.class) {
                return false;
            }
        } catch (Exception e) {
            // If class loading fails, continue
        }

        String packageName = type.getPackage() != null ? type.getPackage().getName() : "";

        // 4. Java standard library types → typically tool inputs
        if (packageName.startsWith("java.") || packageName.startsWith("javax.")) {
            return false;
        }

        // 5. Everything else → user-defined context POJO
        return true;
    }

    /**
     * Resolve a context parameter from ToolExecutionContext.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>If context exists: try {@link ToolExecutionContext#get(Class)} retrieval</li>
     *   <li>The get() method internally checks local store first, then global provider</li>
     * </ol>
     *
     * @param param The parameter to resolve
     * @param context The tool execution context (may be null)
     * @return Resolved parameter value, or null if resolution fails
     */
    private Object resolveContextParameter(Parameter param, ToolExecutionContext context) {
        Class<?> targetType = param.getType();

        // Get from context (delegates to store and provider)
        if (context != null) {
            return context.get(targetType);
        }

        // No context provided - cannot resolve
        return null;
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

        Class<?> rawType = parameter.getType();
        Type paramType = parameter.getParameterizedType();

        // Direct assignment only if:
        // 1. Raw types match, AND
        // 2. The parameter is not a parameterized type (no generic info to preserve)
        if (rawType.isAssignableFrom(value.getClass())
                && !(paramType instanceof ParameterizedType)) {
            return value;
        }

        // Use JsonCodec conversion with full type information to preserve generics.
        try {
            return JsonUtils.getJsonCodec().convertValue(value, paramType);
        } catch (Exception e) {
            // Fallback to string-based conversion for primitives
            return convertFromString(value.toString(), rawType);
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
     * Reactive error handler for use with {@code onErrorResume}.
     *
     * <p>Delegates to {@link #handleInvocationError(Throwable)} and wraps the
     * result in {@code Mono.just(...)}.
     *
     * @param e the error from the reactive chain
     * @return Mono containing ToolResultBlock with error info
     * @throws ToolSuspendException if found in the exception chain
     */
    private Mono<ToolResultBlock> handleError(Throwable e) {
        return Mono.just(handleInvocationError(e));
    }

    /**
     * Handle invocation errors with informative messages.
     *
     * <p>Special handling for {@link ToolSuspendException}: if found in the exception chain,
     * it will be re-thrown to allow proper suspension handling by {@link ToolExecutor}.
     *
     * @param e the throwable
     * @return ToolResultBlock with error message
     * @throws ToolSuspendException if found in the exception chain
     */
    private ToolResultBlock handleInvocationError(Throwable e) {
        // Check if the exception itself is ToolSuspendException
        if (e instanceof ToolSuspendException) {
            throw (ToolSuspendException) e;
        }

        Throwable cause = e.getCause();
        // Check for ToolSuspendException in the exception chain
        if (cause instanceof ToolSuspendException) {
            throw (ToolSuspendException) cause;
        }

        String errorMsg =
                cause != null
                        ? ExceptionUtils.getErrorMessage(cause)
                        : ExceptionUtils.getErrorMessage(e);
        return ToolResultBlock.error("Tool execution failed: " + errorMsg);
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
