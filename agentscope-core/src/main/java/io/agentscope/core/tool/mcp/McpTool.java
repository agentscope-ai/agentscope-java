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
import io.agentscope.core.permission.PermissionContext;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
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
 *   <li>Surfaces permission policy via {@link #checkPermissions(Map, PermissionContext)}: read-only
 *       MCP tools auto-allow; everything else requires explicit user authorization (matches the
 *       Python {@code MCPTool} default).
 * </ul>
 */
public class McpTool extends ToolBase {

    private static final Logger logger = LoggerFactory.getLogger(McpTool.class);

    private final Map<String, Object> outputSchema;
    private final McpClientWrapper clientWrapper;
    private final Map<String, Object> presetArguments;

    /** Preferred constructor used by {@link McpClientManager}. */
    public McpTool(
            String name,
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
            Map<String, Object> toolInput, PermissionContext context) {
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
     * @param param The tool call parameters containing toolUseBlock, input, and agent
     * @return a Mono that emits the tool result when the MCP call completes
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        logger.debug("Calling MCP tool '{}' with input: {}", getName(), param.getInput());

        // Merge preset arguments with input arguments
        Map<String, Object> mergedArgs = mergeArguments(param.getInput());

        return clientWrapper
                .callTool(getName(), mergedArgs)
                .map(McpContentConverter::convertCallToolResult)
                .doOnSuccess(
                        result -> logger.debug("MCP tool '{}' completed successfully", getName()))
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
     * @param input the input arguments
     * @return merged arguments
     */
    private Map<String, Object> mergeArguments(Map<String, Object> input) {
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
