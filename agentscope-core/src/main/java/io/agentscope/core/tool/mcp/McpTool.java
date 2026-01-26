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
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /**
     * Returns the name of this MCP tool.
     *
     * @return the tool name
     */
    @Override
    public String getName() {
        return name;
    }

    /**
     * Returns the description of this MCP tool.
     *
     * @return the tool description
     */
    @Override
    public String getDescription() {
        return description;
    }

    /**
     * Returns the JSON schema parameters for this MCP tool.
     *
     * @return the parameters schema map
     */
    @Override
    public Map<String, Object> getParameters() {
        return parameters;
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
        logger.debug("Calling MCP tool '{}' with input: {}", name, param.getInput());

        // Merge preset arguments with input arguments
        Map<String, Object> mergedArgs = mergeArguments(param.getInput());

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

        return parameters;
    }
}
