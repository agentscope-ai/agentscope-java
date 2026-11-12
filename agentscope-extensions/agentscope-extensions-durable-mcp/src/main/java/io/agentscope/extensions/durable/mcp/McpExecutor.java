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

package io.agentscope.extensions.durable.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.tool.ToolValidator;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableProtocol;
import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.ExecutionResult;
import io.agentscope.durable.DurableTypes.ExecutionState;
import io.agentscope.durable.ExecutionMessages;
import io.agentscope.durable.Executor;
import io.agentscope.durable.ToolBinding;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** MCP execution with optional frozen descriptors and explicitly advertised operation recovery. */
public final class McpExecutor implements Executor {
    public record Target(
            McpClientWrapper client, String tool, Map<String, Object> arguments, Runnable release)
            implements AutoCloseable {
        public Target(McpClientWrapper client, String tool, Map<String, Object> arguments) {
            this(client, tool, arguments, () -> {});
        }

        public Target {
            Objects.requireNonNull(client);
            DurableJson.text(tool, "tool");
            arguments = Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
            Objects.requireNonNull(release);
        }

        public void close() {
            release.run();
        }

        public static Target leased(
                McpConnections.Lease lease, String tool, Map<String, Object> arguments) {
            try {
                return new Target(lease.client(), tool, arguments, lease::close);
            } catch (RuntimeException failure) {
                lease.close();
                throw failure;
            }
        }
    }

    private final Function<ExecutionRequest, Target> resolver;
    private final Duration timeout;
    private final ToolBinding binding;
    private final McpRecovery recovery;

    /** Direct borrowed-client mode: ordinary MCP does not promise recovery. */
    public McpExecutor(Function<ExecutionRequest, Target> resolver, Duration timeout) {
        this(null, resolver, timeout);
    }

    public McpExecutor(
            ToolBinding binding, Function<ExecutionRequest, Target> resolver, Duration timeout) {
        this.resolver = Objects.requireNonNull(resolver);
        this.timeout = Objects.requireNonNull(timeout);
        if (timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("Positive timeout required");
        this.binding = binding;
        this.recovery =
                binding == null
                        ? null
                        : McpRecovery.advertised(binding.target().required("descriptor"));
        if (binding != null) {
            var target = binding.target();
            if (!target.required("tool")
                    .asText()
                    .equals(target.required("descriptor").path("name").asText()))
                throw new IllegalArgumentException("Frozen MCP tool identity differs");
            if (recovery != null) {
                var query = target.path("recoveryTools").path(recovery.queryTool());
                if (!query.path("annotations").path("readOnlyHint").asBoolean())
                    throw new IllegalArgumentException("MCP execution lookup must be read-only");
                if (recovery.cancelTool() != null)
                    target.required("recoveryTools").required(recovery.cancelTool());
            }
        }
    }

    /** Freeze discovery once; credentials in server should be references resolved by the host. */
    public static ToolBinding binding(
            String alias,
            String version,
            String executor,
            JsonNode server,
            McpSchema.Tool tool,
            List<McpSchema.Tool> catalog) {
        var descriptor = DurableJson.value(tool);
        var target =
                (ObjectNode)
                        DurableJson.value(
                                Map.of(
                                        "server",
                                        server,
                                        "tool",
                                        tool.name(),
                                        "descriptor",
                                        descriptor));
        var recovery = McpRecovery.advertised(descriptor);
        if (recovery != null) {
            var definitions = new LinkedHashMap<String, JsonNode>();
            for (String name :
                    recovery.cancelTool() == null
                            ? List.of(recovery.queryTool())
                            : List.of(recovery.queryTool(), recovery.cancelTool())) {
                var found =
                        catalog.stream()
                                .filter(candidate -> candidate.name().equals(name))
                                .toList();
                if (found.size() != 1)
                    throw new IllegalArgumentException("Recovery tool missing or ambiguous");
                var value = DurableJson.value(found.get(0));
                if (name.equals(recovery.queryTool())
                        && !value.path("annotations").path("readOnlyHint").asBoolean())
                    throw new IllegalArgumentException("MCP execution lookup must be read-only");
                definitions.put(name, value);
            }
            target.set("recoveryTools", DurableJson.value(definitions));
        }
        var schema = new LinkedHashMap<String, Object>();
        schema.put("name", alias);
        schema.put("description", tool.description() == null ? "" : tool.description());
        schema.put("parameters", tool.inputSchema());
        if (descriptor.hasNonNull("outputSchema"))
            schema.put("outputSchema", descriptor.get("outputSchema"));
        return new ToolBinding(alias, version, executor, DurableJson.value(schema), target);
    }

    public Capabilities capabilities() {
        return new Capabilities(
                recovery != null && recovery.idempotentStart(),
                recovery != null,
                recovery != null && recovery.cancelTool() != null);
    }

    @Override
    public void validateStart(ExecutionRequest request) {
        try (var target = resolver.apply(request)) {
            verify(request, target, null);
        }
    }

    @SuppressWarnings("unchecked")
    private void verify(ExecutionRequest request, Target target, String control) {
        if (!target.client().isInitialized()) target.client().initialize().block(timeout);
        if (binding == null) return;
        binding.verify(request);
        var frozen = binding.target();
        if (!frozen.required("tool").asText().equals(target.tool())
                || !DurableJson.hash(target.arguments())
                        .equals(DurableJson.hash(request.call().arguments())))
            throw incompatible("Resolved MCP invocation differs from frozen binding");
        if (recovery != null && !target.client().isPropagateMeta())
            throw incompatible("MCP recovery requires execution metadata");
        var tools = target.client().listTools().block(timeout);
        if (tools == null) throw incompatible("MCP tool discovery returned no descriptors");
        if (control != null) {
            // Existing operations use their frozen control contract even when new execution is
            // unavailable.
            verifyDescriptor(frozen.required("recoveryTools").required(control), tools);
            return;
        }
        verifyDescriptor(frozen.required("descriptor"), tools);
        frozen.path("recoveryTools")
                .elements()
                .forEachRemaining(expected -> verifyDescriptor(expected, tools));
        String invalid =
                ToolValidator.validateInput(
                        request.call().arguments().toString(),
                        DurableJson.convert(
                                frozen.required("descriptor").required("inputSchema"), Map.class));
        if (invalid != null)
            throw new DurableException(
                    DurableException.Code.INVALID, "Invalid MCP arguments: " + invalid);
    }

    private static void verifyDescriptor(JsonNode expected, List<McpSchema.Tool> live) {
        var matches =
                live.stream()
                        .filter(tool -> tool.name().equals(expected.path("name").asText()))
                        .toList();
        if (matches.size() != 1
                || !DurableJson.hash(expected).equals(DurableJson.hash(matches.get(0))))
            throw incompatible("MCP tool descriptor changed");
    }

    private Map<String, Object> identity(ExecutionRequest request) {
        return Map.of(
                "version",
                McpRecovery.VERSION,
                "executionId",
                ExecutionMessages.identity(request),
                "requestDigest",
                DurableJson.hash(request));
    }

    public ExecutionResult startExecution(ExecutionRequest request) {
        try (var target = resolver.apply(request)) {
            verify(request, target, null);
            var meta =
                    target.client().isPropagateMeta()
                            ? recovery == null
                                    ? Map.<String, Object>of(
                                            "executionId",
                                            ExecutionMessages.identity(request),
                                            "requestDigest",
                                            DurableJson.hash(request))
                                    : Map.<String, Object>of(
                                            McpRecovery.META_KEY, identity(request))
                            : null;
            var result =
                    target.client()
                            .callTool(target.tool(), target.arguments(), meta)
                            .block(timeout);
            return recovery == null ? ordinary(request, result) : observation(request, result);
        }
    }

    public ExecutionResult query(ExecutionRequest request, String handle) {
        if (recovery == null)
            return ExecutionResult.unknown(
                    "MCP server exposes no execution lookup; reconcile the original call");
        return control(request, handle, recovery.queryTool());
    }

    public ExecutionResult cancel(ExecutionRequest request, String handle) {
        if (recovery == null || recovery.cancelTool() == null)
            return ExecutionResult.unknown(
                    "MCP cancellation cannot confirm termination of an external effect");
        return control(request, handle, recovery.cancelTool());
    }

    private ExecutionResult control(ExecutionRequest request, String handle, String operation) {
        if (handle != null && !ExecutionMessages.identity(request).equals(handle))
            throw incompatible("Original MCP operation identity changed");
        try (var target = resolver.apply(request)) {
            verify(request, target, operation);
            var result =
                    target.client()
                            .callTool(
                                    operation,
                                    identity(request),
                                    Map.of(McpRecovery.META_KEY, identity(request)))
                            .block(timeout);
            return observation(request, result);
        }
    }

    private static ExecutionResult ordinary(
            ExecutionRequest request, McpSchema.CallToolResult result) {
        if (result == null) return ExecutionResult.unknown("MCP returned no outcome");
        return new ExecutionResult(
                Boolean.TRUE.equals(result.isError())
                        ? ExecutionState.FAILED
                        : ExecutionState.SUCCEEDED,
                ExecutionMessages.identity(request),
                DurableJson.value(Map.of("format", "mcp.result.v1", "result", result)),
                null);
    }

    private ExecutionResult observation(ExecutionRequest request, McpSchema.CallToolResult result) {
        String handle = ExecutionMessages.identity(request);
        try {
            if (result == null || Boolean.TRUE.equals(result.isError()))
                throw incompatible("Unconfirmed MCP observation");
            var value = DurableJson.value(result).required("structuredContent");
            DurableProtocol.validate("mcpExecutionObservation", value);
            if (!value.path("version").isIntegralNumber()
                    || value.path("version").asInt() != McpRecovery.VERSION
                    || !handle.equals(value.path("executionId").asText())
                    || !DurableJson.hash(request).equals(value.path("requestDigest").asText()))
                throw incompatible("MCP observation identity differs");
            var state = ExecutionState.valueOf(value.required("state").asText());
            JsonNode envelope = null;
            if (state == ExecutionState.SUCCEEDED || state == ExecutionState.FAILED) {
                var outcome = value.required("result");
                if (!outcome.path("content").isArray()
                        || !outcome.path("isError").isBoolean()
                        || outcome.path("isError").asBoolean() != (state == ExecutionState.FAILED))
                    throw incompatible("MCP terminal observation is inconsistent");
                var nativeResult = DurableJson.convert(outcome, McpSchema.CallToolResult.class);
                envelope =
                        DurableJson.value(
                                Map.of("format", "mcp.result.v1", "result", nativeResult));
            } else if (value.hasNonNull("result"))
                throw incompatible("Non-result observation contains a result");
            return new ExecutionResult(state, handle, envelope, value.path("error").asText(null));
        } catch (RuntimeException malformed) {
            return new ExecutionResult(
                    ExecutionState.UNKNOWN,
                    handle,
                    null,
                    "MCP operation observation is unconfirmed");
        }
    }

    private static DurableException incompatible(String message) {
        return new DurableException(DurableException.Code.INCOMPATIBLE, message);
    }
}
