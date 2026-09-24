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
package io.agentscope.core.tool.mcp;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * {@link ToolBase} subclass that wraps an MCP (Model Context Protocol) tool.
 *
 * <p>Bridges remote MCP tools to the AgentScope tool system so they participate in permission
 * evaluation and the agent's pending-confirmation flow alongside built-in and {@code @Tool} tools.
 *
 * <p>Features:
 * <ul>
 *   <li>Translates AgentScope tool calls into MCP protocol calls.
 *   <li>Merges caller arguments with preset arguments configured at registration time.
 *   <li>Converts MCP results into AgentScope {@link ToolResultBlock}s.
 *   <li>Surfaces permission policy via {@link #checkPermissions(Map, PermissionContextState)}: read-only
 *       MCP tools auto-allow; everything else requires explicit user authorization (matches the
 *       Python {@code MCPTool} default).
 * </ul>
 */
public class McpTool extends ToolBase {

    private static final Logger logger = LoggerFactory.getLogger(McpTool.class);

    private final Map<String, Object> outputSchema;
    private final String remoteToolName;
    private final McpClientWrapper clientWrapper;
    private final Map<String, Object> presetArguments;

    /**
     * Per-tool restriction for request metadata propagation. Effective propagation requires both
     * this flag and the connection-level {@link McpClientWrapper#isPropagateMeta()} to be {@code
     * true} (logical AND): the wrapper flag is read live on every tool call, while this flag is
     * fixed at registration time (from {@code McpClientManager}) and can only further restrict
     * propagation for this specific tool.
     *
     * <p>Note the two switches have different memory: an explicit {@code true} recorded here at
     * registration time is remembered, so a tool can start propagating again once the operator
     * re-enables a connection that was built with {@code propagateMeta(false)} — whereas the
     * connection-level switch is always the live ceiling and cannot be overruled while it is
     * {@code false}.
     */
    private volatile boolean propagateMeta = true;

    /** Preferred constructor used by {@link io.agentscope.core.tool.McpClientManager}. */
    public McpTool(
            String name,
            String description,
            Map<String, Object> parameters,
            Map<String, Object> outputSchema,
            McpClientWrapper clientWrapper,
            Map<String, Object> presetArguments,
            String mcpName,
            boolean readOnly) {
        this(
                name,
                name,
                description,
                parameters,
                outputSchema,
                clientWrapper,
                presetArguments,
                mcpName,
                readOnly);
    }

    /** Registers a namespaced model-facing name while calling the original MCP tool name. */
    public McpTool(
            String name,
            String remoteToolName,
            String description,
            Map<String, Object> parameters,
            Map<String, Object> outputSchema,
            McpClientWrapper clientWrapper,
            Map<String, Object> presetArguments,
            String mcpName,
            boolean readOnly) {
        super(
                ToolBase.builder()
                        .name(Objects.requireNonNull(name, "name cannot be null"))
                        .description(description != null ? description : "")
                        .inputSchema(parameters != null ? parameters : new HashMap<>())
                        .readOnly(readOnly)
                        .concurrencySafe(false)
                        .mcp(Objects.requireNonNull(mcpName, "mcpName cannot be null")));
        this.remoteToolName = Objects.requireNonNull(remoteToolName);
        this.outputSchema = outputSchema != null ? new HashMap<>(outputSchema) : null;
        this.clientWrapper = Objects.requireNonNull(clientWrapper, "clientWrapper cannot be null");
        this.presetArguments = presetArguments != null ? new HashMap<>(presetArguments) : null;
    }

    /**
     * @deprecated Use {@link #McpTool(String, String, Map, Map, McpClientWrapper, Map, String,
     *     boolean)} so the MCP server name and read-only hint are propagated. Kept for source
     *     compatibility with callers that pre-date the {@link ToolBase} integration.
     */
    @Deprecated(since = "2.0.0")
    public McpTool(
            String name,
            String description,
            Map<String, Object> parameters,
            McpClientWrapper clientWrapper) {
        this(
                name,
                description,
                parameters,
                null,
                clientWrapper,
                null,
                clientWrapper.getName(),
                false);
    }

    /**
     * @deprecated Use {@link #McpTool(String, String, Map, Map, McpClientWrapper, Map, String,
     *     boolean)} so the MCP server name and read-only hint are propagated.
     */
    @Deprecated(since = "2.0.0")
    public McpTool(
            String name,
            String description,
            Map<String, Object> parameters,
            McpClientWrapper clientWrapper,
            Map<String, Object> presetArguments) {
        this(
                name,
                description,
                parameters,
                null,
                clientWrapper,
                presetArguments,
                clientWrapper.getName(),
                false);
    }

    /**
     * @deprecated Use {@link #McpTool(String, String, Map, Map, McpClientWrapper, Map, String,
     *     boolean)} so the MCP server name and read-only hint are propagated.
     */
    @Deprecated(since = "2.0.0")
    public McpTool(
            String name,
            String description,
            Map<String, Object> parameters,
            Map<String, Object> outputSchema,
            McpClientWrapper clientWrapper,
            Map<String, Object> presetArguments) {
        this(
                name,
                description,
                parameters,
                outputSchema,
                clientWrapper,
                presetArguments,
                clientWrapper.getName(),
                false);
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return outputSchema != null ? new HashMap<>(outputSchema) : null;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(
            Map<String, Object> toolInput, PermissionContextState context) {
        if (isReadOnly()) {
            return Mono.just(
                    PermissionDecision.allow(
                            "Read-only MCP tool '" + getName() + "' allowed without prompt"));
        }
        return Mono.just(
                PermissionDecision.ask(
                        "MCP tool '"
                                + getName()
                                + "' requires explicit authorization before each call"));
    }

    /**
     * Executes this MCP tool asynchronously with the given parameters.
     *
     * <p>This method merges any preset arguments with the input arguments (input takes precedence),
     * calls the remote MCP tool via the client wrapper, and converts the result to a
     * {@link ToolResultBlock}. If an error occurs, it returns an error result instead of failing.
     *
     * <p>Request metadata (user {@link McpMeta} entries plus the framework tool-call id) is
     * propagated to the MCP server only when both {@link #isPropagateMeta()} and the
     * connection-level {@link McpClientWrapper#isPropagateMeta()} are {@code true} (logical AND);
     * otherwise the {@code meta} field is omitted from the request entirely.
     *
     * @param param The tool call parameters containing toolUseBlock, input, and agent
     * @return a Mono that emits the tool result when the MCP call completes
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return callAsyncForExecution(param)
                .onErrorResume(
                        e -> {
                            logger.error(
                                    "Error calling MCP tool '{}': {}", getName(), e.getMessage());
                            String errorMsg =
                                    e.getMessage() != null
                                            ? e.getMessage()
                                            : e.getClass().getSimpleName();
                            return Mono.just(ToolResultBlock.error("MCP tool error: " + errorMsg));
                        });
    }

    /**
     * Executes this MCP tool through the framework's execution infrastructure.
     *
     * <p>Transport-level failures stay as reactive error signals so the executor can apply its
     * retry policy. Protocol-level business errors ({@code isError=true}) remain completed error
     * results and are never retried.
     *
     * @param param The tool call parameters containing toolUseBlock, input, and agent
     * @return a Mono that emits the tool result, or signals an error on failure
     */
    @Override
    public Mono<ToolResultBlock> callAsyncForExecution(ToolCallParam param) {
        Objects.requireNonNull(param, "param must not be null");
        logger.debug("Calling MCP tool '{}' with input: {}", getName(), param.getInput());

        // Merge preset arguments with input arguments
        Map<String, Object> mergedArgs = mergeArguments(param.getInput());

        // Propagate request metadata only when both switches allow it; null omits the MCP
        // `_meta` field entirely so no metadata (user McpMeta entries nor the tool-call id)
        // leaves the process. The wrapper switch is read live so disabling a connection takes
        // effect immediately, even for tools registered earlier.
        boolean propagate = propagateMeta && clientWrapper.isPropagateMeta();
        Map<String, Object> metaMap = propagate ? buildMetaMap(param) : null;

        return clientWrapper
                .callTool(remoteToolName, mergedArgs, metaMap)
                .map(McpContentConverter::convertCallToolResult)
                .doOnSuccess(
                        result -> logger.debug("MCP tool '{}' completed successfully", getName()));
    }

    /**
     * Checks whether this tool allows request metadata propagation.
     *
     * <p>This is a per-tool restriction only: the actual decision also ANDs the connection-level
     * {@link McpClientWrapper#isPropagateMeta()}, which is read live on every tool call.
     *
     * @return true if this tool permits user {@link McpMeta} entries and the tool-call id to be
     *     included in tool call requests, false otherwise
     */
    public boolean isPropagateMeta() {
        return propagateMeta;
    }

    /**
     * Configures whether this tool allows request metadata propagation.
     *
     * <p>The initial value is fixed at registration time (registration-level override, or {@code
     * true} by default). Setting this to {@code false} permanently restricts this tool; setting it
     * back to {@code true} only re-enables propagation if the connection-level switch on
     * {@link McpClientWrapper} also allows it.
     *
     * @param propagateMeta true to allow metadata propagation for this tool (default), false to
     *     omit the {@code meta} field entirely from this tool's requests
     */
    public void setPropagateMeta(boolean propagateMeta) {
        this.propagateMeta = propagateMeta;
    }

    /**
     * Gets the name of the MCP client that provides this tool.
     *
     * @return the MCP client name
     */
    public String getClientName() {
        return clientWrapper.getName();
    }

    /**
     * Gets the preset arguments configured for this tool.
     *
     * @return the preset arguments, or null if none configured
     */
    public Map<String, Object> getPresetArguments() {
        return presetArguments != null ? new HashMap<>(presetArguments) : null;
    }

    /**
     * Merges input arguments with preset arguments.
     * Input arguments take precedence over preset arguments.
     *
     * <p>Package-private for direct unit testing of the defensive {@code null} branches that
     * {@link ToolCallParam#getInput()} cannot reach in production.
     *
     * @param input the input arguments
     * @return merged arguments
     */
    // Visible for testing; not an extension point for subclasses (see javadoc above).
    Map<String, Object> mergeArguments(Map<String, Object> input) {
        if (presetArguments == null || presetArguments.isEmpty()) {
            return input != null ? input : new HashMap<>();
        }

        Map<String, Object> merged = new HashMap<>(presetArguments);
        if (input != null) {
            merged.putAll(input);
        }
        return merged;
    }

    /**
     * Builds the request metadata for a tool call.
     *
     * <p>Extracts MCP meta from the runtime context by the {@link McpMeta} type namespace and
     * attaches the framework tool-call id. Transport metadata is not model input and must not
     * alter the tool schema.
     *
     * @param param the tool call parameters
     * @return the request metadata map, possibly empty but never null
     */
    private Map<String, Object> buildMetaMap(ToolCallParam param) {
        Map<String, Object> meta = new HashMap<>(extractMcpMeta(param));
        if (param.getToolUseBlock() != null && param.getToolUseBlock().getId() != null) {
            meta.put("io.agentscope/toolCallId", param.getToolUseBlock().getId());
        }
        return meta;
    }

    /**
     * Extracts MCP meta from the given tool call parameters.
     *
     * @param param the tool call parameters
     * @return the extracted MCP meta
     */
    private Map<String, Object> extractMcpMeta(ToolCallParam param) {
        if (param == null || param.getRuntimeContext() == null) {
            return Collections.emptyMap();
        }
        McpMeta mcpMeta = param.getRuntimeContext().get(McpMeta.class);
        if (mcpMeta == null || mcpMeta.isEmpty()) {
            return Collections.emptyMap();
        }
        return mcpMeta.entries();
    }

    /**
     * Converts MCP JsonSchema to AgentScope parameters format.
     *
     * @param inputSchema the MCP JsonSchema
     * @return parameters map in AgentScope format
     */
    public static Map<String, Object> convertMcpSchemaToParameters(
            McpSchema.JsonSchema inputSchema, Set<String> excludeParams) {
        Map<String, Object> parameters = new HashMap<>();

        if (inputSchema == null) {
            parameters.put("type", "object");
            parameters.put("properties", new HashMap<>());
            parameters.put("required", new ArrayList<>());
            return parameters;
        }
        Map<String, Object> properties =
                inputSchema.properties() != null
                        ? new HashMap<>(inputSchema.properties())
                        : new HashMap<>();
        List<String> required =
                inputSchema.required() != null
                        ? new ArrayList<>(inputSchema.required())
                        : new ArrayList<>();

        // Exclude preset parameters from the schema
        if (excludeParams != null) {
            required.removeAll(excludeParams);
            properties.keySet().removeAll(excludeParams);
        }

        parameters.put("type", inputSchema.type() != null ? inputSchema.type() : "object");
        parameters.put("properties", properties);
        parameters.put("required", required);

        if (inputSchema.additionalProperties() != null) {
            parameters.put("additionalProperties", inputSchema.additionalProperties());
        }

        // Preserve $defs and definitions for $ref resolution
        if (inputSchema.defs() != null && !inputSchema.defs().isEmpty()) {
            parameters.put("$defs", new HashMap<>(inputSchema.defs()));
        }

        if (inputSchema.definitions() != null && !inputSchema.definitions().isEmpty()) {
            parameters.put("definitions", new HashMap<>(inputSchema.definitions()));
        }

        return parameters;
    }
}
