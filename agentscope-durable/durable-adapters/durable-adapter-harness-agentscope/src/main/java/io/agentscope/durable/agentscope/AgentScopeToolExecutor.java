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

package io.agentscope.durable.agentscope;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolValidator;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.ExecutionResult;
import io.agentscope.durable.DurableTypes.ExecutionState;
import io.agentscope.durable.Executor;
import io.agentscope.durable.ToolBinding;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Executes borrowed native tools on Hands without an Agent/model or implicit tool retries.
 * Native permission admission belongs to the Brain; the host must authorize the Worker boundary.
 * Register it as a WorkerRuntime provider for durable admission. Arbitrary Java calls do not gain
 * effect recovery.
 */
public final class AgentScopeToolExecutor implements Executor {
    private final Map<String, ToolBinding> bindings;
    private final Map<String, AgentTool> tools;
    private final Toolkit toolkit;
    private final Duration timeout;

    public AgentScopeToolExecutor(
            Toolkit toolkit, Collection<ToolBinding> bindings, Duration timeout) {
        this.bindings = ToolBinding.catalog(bindings);
        this.toolkit = Objects.requireNonNull(toolkit);
        this.timeout = Objects.requireNonNull(timeout);
        if (timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("Positive tool timeout required");
        var values = new LinkedHashMap<String, AgentTool>();
        this.bindings.forEach(
                (name, binding) -> {
                    var tool = toolkit.getTool(name);
                    if (tool == null || toolkit.isExternalTool(name))
                        throw new IllegalArgumentException(
                                "Hands requires an executable native tool: " + name);
                    verifySchema(binding, tool);
                    values.put(name, tool);
                });
        tools = Map.copyOf(values);
    }

    public static ToolBinding binding(
            AgentTool tool, String version, String executor, JsonNode target) {
        return new ToolBinding(tool.getName(), version, executor, schema(tool), target);
    }

    private static JsonNode schema(AgentTool tool) {
        var value = new LinkedHashMap<String, Object>();
        value.put("name", tool.getName());
        value.put("description", tool.getDescription() == null ? "" : tool.getDescription());
        value.put("parameters", tool.getParameters());
        if (tool.getOutputSchema() != null) value.put("outputSchema", tool.getOutputSchema());
        if (tool.getStrict() != null) value.put("strict", tool.getStrict());
        if (tool.getDeferLoading() != null) value.put("deferLoading", tool.getDeferLoading());
        return DurableJson.value(value);
    }

    private static void verifySchema(ToolBinding binding, AgentTool tool) {
        if (!DurableJson.hash(binding.schema()).equals(DurableJson.hash(schema(tool))))
            throw new DurableException(
                    DurableException.Code.INCOMPATIBLE,
                    "Native tool schema differs from frozen binding");
    }

    @SuppressWarnings("unchecked")
    public static void registerSchemas(Toolkit toolkit, Collection<ToolBinding> bindings) {
        for (var binding : ToolBinding.catalog(bindings).values()) {
            var existing = toolkit.getTool(binding.name());
            if (existing != null) {
                verifySchema(binding, existing);
                continue;
            }
            var schema = binding.schema();
            var builder =
                    ToolSchema.builder()
                            .name(binding.name())
                            .description(schema.path("description").asText(""))
                            .parameters(
                                    DurableJson.convert(schema.required("parameters"), Map.class));
            if (schema.hasNonNull("outputSchema"))
                builder.outputSchema(DurableJson.convert(schema.get("outputSchema"), Map.class));
            if (schema.hasNonNull("strict")) builder.strict(schema.get("strict").asBoolean());
            if (schema.hasNonNull("deferLoading"))
                builder.deferLoading(schema.get("deferLoading").asBoolean());
            toolkit.registerSchema(builder.build());
        }
    }

    public Capabilities capabilities() {
        return new Capabilities(false, false, false);
    }

    @Override
    public void validateStart(ExecutionRequest request) {
        var binding = bindings.get(request.call().name());
        if (binding == null)
            throw new DurableException(
                    DurableException.Code.INCOMPATIBLE, "Native tool is unavailable");
        binding.verify(request);
        if (toolkit.getTool(binding.name()) != tools.get(binding.name()))
            throw new DurableException(
                    DurableException.Code.INCOMPATIBLE, "Native tool registration changed");
        verifySchema(binding, tools.get(binding.name()));
        if (!request.call().arguments().isObject())
            throw new IllegalArgumentException("Native tool arguments must be an object");
        String invalid =
                ToolValidator.validateInput(
                        request.call().arguments().toString(),
                        tools.get(binding.name()).getParameters());
        if (invalid != null)
            throw new DurableException(
                    DurableException.Code.INVALID, "Invalid tool arguments: " + invalid);
    }

    @SuppressWarnings("unchecked")
    public ExecutionResult startExecution(ExecutionRequest request) {
        validateStart(request);
        var binding = bindings.get(request.call().name());
        Map<String, Object> arguments = DurableJson.convert(request.call().arguments(), Map.class);
        var call =
                ToolUseBlock.builder()
                        .id(request.call().callId())
                        .name(binding.name())
                        .input(arguments)
                        .content(request.call().arguments().toString())
                        .build();
        var origin = request.call().origin();
        var contextBuilder =
                RuntimeContext.builder()
                        .userId(request.session().namespace())
                        .sessionId(request.session().sessionId())
                        .threadId(request.thread().threadId())
                        .turnId(request.turnId())
                        .runId(origin == null ? null : origin.runId())
                        .put("durable.executionId", request.executionId())
                        .put("durable.toolBinding", binding.digest());
        if (origin != null) contextBuilder.put("durable.origin", origin);
        var context = contextBuilder.build();
        var result =
                toolkit.callTool(
                                ToolCallParam.builder()
                                        .toolUseBlock(call)
                                        .input(arguments)
                                        .propagateErrors(true)
                                        .runtimeContext(context)
                                        .build())
                        .block(timeout);
        if (result == null || result.isSuspended())
            return ExecutionResult.unknown("Native tool did not confirm a terminal result");
        if (result.getState() == ToolResultState.RUNNING) {
            boolean error =
                    result.getOutput() != null
                            && result.getOutput().stream()
                                    .anyMatch(
                                            content ->
                                                    content instanceof TextBlock text
                                                            && text.getText() != null
                                                            && text.getText()
                                                                    .startsWith("[ERROR]"));
            result = result.withState(error ? ToolResultState.ERROR : ToolResultState.SUCCESS);
        }
        var state =
                result.getState() == ToolResultState.SUCCESS
                        ? ExecutionState.SUCCEEDED
                        : result.getState() == ToolResultState.INTERRUPTED
                                ? ExecutionState.CANCELLED
                                : ExecutionState.FAILED;
        var value =
                JsonUtils.getJsonCodec()
                        .convertValue(
                                result.withIdAndName(call.getId(), call.getName()), Map.class);
        return new ExecutionResult(
                state,
                request.executionId(),
                DurableJson.value(Map.of("format", "agentscope.tool-result.v1", "result", value)),
                null);
    }

    public ExecutionResult query(ExecutionRequest request, String handle) {
        return ExecutionResult.unknown("Native tool has no external operation lookup");
    }

    public ExecutionResult cancel(ExecutionRequest request, String handle) {
        return ExecutionResult.unknown(
                "Native call interruption cannot confirm an external effect stopped");
    }
}
