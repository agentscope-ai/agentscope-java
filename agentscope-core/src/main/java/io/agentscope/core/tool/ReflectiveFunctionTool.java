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

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.state.AgentState;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import reactor.core.publisher.Mono;

/**
 * {@link ToolBase} subclass generated for methods annotated with {@link Tool}.
 *
 * <p>Bridges the annotation-driven registration path in {@link Toolkit#registerToolMethod(Object,
 * Method, String, ExtendedModel, Map)} into the {@link ToolBase} contract so {@code @Tool} methods
 * participate in {@link io.agentscope.core.permission.PermissionEngine} evaluation, the safe-flag
 * machinery in {@link ToolExecutor}, and the agent's pending-confirmation flow.
 *
 * <p>Construction validates the {@code stateInjected} contract: the annotation flag must agree
 * with the presence of an {@link AgentState} parameter on the underlying method.
 */
final class ReflectiveFunctionTool extends ToolBase {

    private final Object toolObject;
    private final Method method;
    private final ToolResultConverter customConverter;
    private final ToolMethodInvoker methodInvoker;
    private final Boolean strict;

    private ReflectiveFunctionTool(
            ToolBase.Builder baseBuilder,
            Object toolObject,
            Method method,
            ToolResultConverter customConverter,
            ToolMethodInvoker methodInvoker,
            Boolean strict) {
        super(baseBuilder);
        this.toolObject = toolObject;
        this.method = method;
        this.customConverter = customConverter;
        this.methodInvoker = methodInvoker;
        this.strict = strict;
    }

    /**
     * Build a {@code ReflectiveFunctionTool} from the {@code @Tool}-annotated method.
     *
     * @throws IllegalArgumentException if the {@code stateInjected} flag disagrees with the method
     *     signature or if more than one {@link AgentState} parameter is declared.
     */
    static ReflectiveFunctionTool create(
            Object toolObject,
            Method method,
            Tool annotation,
            String name,
            String description,
            ToolSchemaGenerator schemaGenerator,
            ToolMethodInvoker methodInvoker,
            ToolResultConverter customConverter,
            Set<String> presetParamNames) {
        Objects.requireNonNull(toolObject, "toolObject must not be null");
        Objects.requireNonNull(method, "method must not be null");
        Objects.requireNonNull(annotation, "annotation must not be null");
        Objects.requireNonNull(schemaGenerator, "schemaGenerator must not be null");
        Objects.requireNonNull(methodInvoker, "methodInvoker must not be null");

        boolean methodDeclaresState = false;
        int stateCount = 0;
        for (Parameter p : method.getParameters()) {
            if (p.getType() == AgentState.class) {
                stateCount++;
                methodDeclaresState = true;
                if (p.isAnnotationPresent(ToolParam.class)) {
                    throw new IllegalArgumentException(
                            "@Tool method "
                                    + method.getDeclaringClass().getName()
                                    + "#"
                                    + method.getName()
                                    + " declares an AgentState parameter annotated with"
                                    + " @ToolParam; AgentState is auto-injected by type and must"
                                    + " not be exposed to the LLM.");
                }
            }
        }
        if (stateCount > 1) {
            throw new IllegalArgumentException(
                    "@Tool method "
                            + method.getDeclaringClass().getName()
                            + "#"
                            + method.getName()
                            + " declares more than one AgentState parameter; only one is allowed.");
        }
        if (annotation.stateInjected() && !methodDeclaresState) {
            throw new IllegalArgumentException(
                    "@Tool(stateInjected=true) on "
                            + method.getDeclaringClass().getName()
                            + "#"
                            + method.getName()
                            + " requires a parameter of type io.agentscope.core.state.AgentState.");
        }
        if (!annotation.stateInjected() && methodDeclaresState) {
            throw new IllegalArgumentException(
                    "@Tool method "
                            + method.getDeclaringClass().getName()
                            + "#"
                            + method.getName()
                            + " declares an AgentState parameter but @Tool.stateInjected is false; "
                            + "set stateInjected=true to enable state injection.");
        }

        Map<String, Object> schema =
                schemaGenerator.generateParameterSchema(
                        method, presetParamNames == null ? Set.of() : presetParamNames);

        ToolBase.Builder builder =
                ToolBase.builder()
                        .name(name)
                        .description(description)
                        .inputSchema(schema)
                        .readOnly(annotation.readOnly())
                        .concurrencySafe(annotation.concurrencySafe())
                        .externalTool(annotation.externalTool())
                        .stateInjected(annotation.stateInjected());
        if (annotation.dangerousFiles().length > 0) {
            builder.dangerousFiles(List.of(annotation.dangerousFiles()));
        }
        if (annotation.dangerousDirectories().length > 0) {
            builder.dangerousDirectories(List.of(annotation.dangerousDirectories()));
        }

        Boolean strict = annotation.strict() ? Boolean.TRUE : null;

        return new ReflectiveFunctionTool(
                builder, toolObject, method, customConverter, methodInvoker, strict);
    }

    @Override
    public Boolean getStrict() {
        return strict;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        if (isExternalTool()) {
            return Mono.just(
                    ToolResultBlock.suspended(param.getToolUseBlock(), new ToolSuspendException()));
        }
        return methodInvoker.invokeAsync(toolObject, method, param, customConverter);
    }

    Method getMethod() {
        return method;
    }

    Object getToolObject() {
        return toolObject;
    }
}
