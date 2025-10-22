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
package io.agentscope.core.tool.mcp;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * AgentTool implementation that wraps an MCP (Model Context Protocol) tool.
 * This class bridges MCP tools to the AgentScope tool system, allowing
 * agents to invoke remote MCP tools seamlessly.
 *
 * <p>Features:
 * <ul>
 *   <li>Converts AgentScope tool calls to MCP protocol calls</li>
 *   <li>Handles parameter merging with preset arguments</li>
 *   <li>Converts MCP results to AgentScope ToolResultBlocks</li>
 *   <li>Supports reactive execution with Mono</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * McpTool tool = new McpTool(
 *     "get_weather",
 *     "Get current weather for a location",
 *     parametersSchema,
 *     mcpClientWrapper,
 *     Map.of("units", "celsius")  // preset args
 * );
 *
 * ToolResultBlock result = tool.callAsync(Map.of("location", "Beijing")).block();
 * }</pre>
 */
public class McpTool implements AgentTool {

    private static final Logger logger = LoggerFactory.getLogger(McpTool.class);

    private final String name;
    private final String description;
    private final Map<String, Object> parameters;
    private final McpClientWrapper clientWrapper;
    private final Map<String, Object> presetArguments;

    private ToolUseBlock currentToolUseBlock;

    /**
     * Constructs a new McpTool without preset arguments.
     *
     * @param name the tool name
     * @param description the tool description
     * @param parameters the JSON schema for tool parameters
     * @param clientWrapper the MCP client wrapper
     */
    public McpTool(
            String name,
            String description,
            Map<String, Object> parameters,
            McpClientWrapper clientWrapper) {
        this(name, description, parameters, clientWrapper, null);
    }

    /**
     * Constructs a new McpTool with preset arguments.
     *
     * @param name the tool name
     * @param description the tool description
     * @param parameters the JSON schema for tool parameters
     * @param clientWrapper the MCP client wrapper
     * @param presetArguments preset arguments to merge with each call (can be null)
     */
    public McpTool(
            String name,
            String description,
            Map<String, Object> parameters,
            McpClientWrapper clientWrapper,
            Map<String, Object> presetArguments) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
        this.clientWrapper = clientWrapper;
        this.presetArguments = presetArguments != null ? new HashMap<>(presetArguments) : null;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public Map<String, Object> getParameters() {
        return parameters;
    }

    @Override
    public void setCurrentToolUseBlock(ToolUseBlock toolUseBlock) {
        this.currentToolUseBlock = toolUseBlock;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(Map<String, Object> input) {
        logger.debug("Calling MCP tool '{}' with input: {}", name, input);

        // Merge preset arguments with input arguments
        Map<String, Object> mergedArgs = mergeArguments(input);

        return clientWrapper
                .callTool(name, mergedArgs)
                .map(McpContentConverter::convertCallToolResult)
                .doOnSuccess(result -> logger.debug("MCP tool '{}' completed successfully", name))
                .onErrorResume(
                        e -> {
                            logger.error("Error calling MCP tool '{}': {}", name, e.getMessage());
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
            McpSchema.JsonSchema inputSchema) {
        Map<String, Object> parameters = new HashMap<>();

        if (inputSchema == null) {
            parameters.put("type", "object");
            parameters.put("properties", new HashMap<>());
            parameters.put("required", new java.util.ArrayList<>());
            return parameters;
        }

        parameters.put("type", inputSchema.type() != null ? inputSchema.type() : "object");
        parameters.put(
                "properties",
                inputSchema.properties() != null ? inputSchema.properties() : new HashMap<>());
        parameters.put(
                "required",
                inputSchema.required() != null
                        ? inputSchema.required()
                        : new java.util.ArrayList<>());

        if (inputSchema.additionalProperties() != null) {
            parameters.put("additionalProperties", inputSchema.additionalProperties());
        }

        return parameters;
    }
}
