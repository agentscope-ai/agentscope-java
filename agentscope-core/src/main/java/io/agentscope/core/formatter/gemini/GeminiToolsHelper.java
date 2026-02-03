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
package io.agentscope.core.formatter.gemini;

import io.agentscope.core.agent.StructuredOutputCapableAgent;
import io.agentscope.core.formatter.gemini.dto.GeminiTool;
import io.agentscope.core.formatter.gemini.dto.GeminiTool.GeminiFunctionDeclaration;
import io.agentscope.core.formatter.gemini.dto.GeminiToolConfig;
import io.agentscope.core.formatter.gemini.dto.GeminiToolConfig.GeminiFunctionCallingConfig;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles tool registration and configuration for Gemini API.
 *
 * <p>
 * This helper converts AgentScope tool schemas to Gemini's Tool and ToolConfig
 * format:
 * <ul>
 * <li>Tool: Contains function declarations with JSON Schema parameters</li>
 * <li>ToolConfig: Contains function calling mode configuration</li>
 * </ul>
 *
 * <p>
 * <b>Tool Choice Mapping:</b>
 * <ul>
 * <li>Auto: mode=AUTO (model decides)</li>
 * <li>None: mode=NONE (disable tool calling)</li>
 * <li>Required: mode=ANY (force tool call from all provided tools)</li>
 * <li>Specific: mode=ANY + allowedFunctionNames (force specific tool)</li>
 * </ul>
 */
public class GeminiToolsHelper {

    private static final Logger log = LoggerFactory.getLogger(GeminiToolsHelper.class);

    /**
     * Creates a new GeminiToolsHelper.
     */
    public GeminiToolsHelper() {}

    /**
     * Convert AgentScope ToolSchema list to Gemini Tool object.
     *
     * @param tools List of tool schemas (may be null or empty)
     * @return Gemini Tool object with function declarations, or null if no tools
     */
    public GeminiTool convertToGeminiTool(List<ToolSchema> tools) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }

        List<GeminiFunctionDeclaration> functionDeclarations = new ArrayList<>();

        for (ToolSchema toolSchema : tools) {
            try {
                GeminiFunctionDeclaration declaration = new GeminiFunctionDeclaration();

                // Set name (required)
                if (toolSchema.getName() != null) {
                    declaration.setName(toolSchema.getName());
                }

                // Set description (optional)
                if (toolSchema.getDescription() != null) {
                    declaration.setDescription(toolSchema.getDescription());
                }

                // Convert parameters (directly modify toolSchema Map structure if needed,
                // but usually it is already in JSON Schema format compatible with Gemini)
                // NOTE: Gemini API is sensitive to empty parameter schemas
                // For tools with no parameters, omit the parameters field entirely
                Map<String, Object> parameters = toolSchema.getParameters();

                // Special handling for generate_response tool:
                // Gemini doesn't understand the nested {response: {...}} wrapper format.
                // We need to unwrap the inner schema so Gemini can call the tool correctly.
                if (StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME.equals(
                        toolSchema.getName())) {
                    parameters = unwrapResponseSchema(parameters);
                    log.debug(
                            "Unwrapped 'response' wrapper from generate_response tool schema for"
                                    + " Gemini compatibility");
                }

                // Only set parameters if not null and not empty
                // Gemini rejects tools with empty parameter schemas
                if (parameters != null && !parameters.isEmpty()) {
                    declaration.setParameters(parameters);
                }

                // Debug: Log the cleaned schema
                try {
                    String schemaJson = JsonUtils.getJsonCodec().toPrettyJson(parameters);
                    log.debug("Cleaned schema for tool '{}': {}", toolSchema.getName(), schemaJson);
                } catch (Exception e) {
                    log.debug("Could not serialize schema for logging: {}", e.getMessage());
                }

                functionDeclarations.add(declaration);
                log.debug("Converted tool schema: {}", toolSchema.getName());

            } catch (Exception e) {
                log.error(
                        "Failed to convert tool schema '{}': {}",
                        toolSchema.getName(),
                        e.getMessage(),
                        e);
            }
        }

        if (functionDeclarations.isEmpty()) {
            return null;
        }

        GeminiTool tool = new GeminiTool();
        tool.setFunctionDeclarations(functionDeclarations);
        return tool;
    }

    /**
     * Create Gemini ToolConfig from AgentScope ToolChoice.
     *
     * <p>
     * Tool choice mapping:
     * <ul>
     * <li>null or Auto: mode=AUTO (model decides)</li>
     * <li>None: mode=NONE (disable tool calling)</li>
     * <li>Required: mode=ANY (force tool call from all provided tools)</li>
     * <li>Specific: mode=ANY + allowedFunctionNames (force specific tool)</li>
     * </ul>
     *
     * @param toolChoice The tool choice configuration (null means auto)
     * @return Gemini ToolConfig object, or null if auto (default behavior)
     */
    public GeminiToolConfig convertToolChoice(ToolChoice toolChoice) {
        if (toolChoice == null || toolChoice instanceof ToolChoice.Auto) {
            // Auto is the default behavior, no need to set explicit config
            log.debug("ToolChoice.Auto: using default AUTO mode");
            return null;
        }

        GeminiFunctionCallingConfig config = new GeminiFunctionCallingConfig();

        if (toolChoice instanceof ToolChoice.None) {
            // NONE: disable tool calling
            config.setMode("NONE");
            log.debug("ToolChoice.None: set mode to NONE");

        } else if (toolChoice instanceof ToolChoice.Required) {
            // ANY: force tool call from all provided tools
            config.setMode("ANY");
            log.debug("ToolChoice.Required: set mode to ANY");

        } else if (toolChoice instanceof ToolChoice.Specific specific) {
            // ANY with allowedFunctionNames: force specific tool call
            config.setMode("ANY");
            config.setAllowedFunctionNames(List.of(specific.toolName()));
            log.debug("ToolChoice.Specific: set mode to ANY with tool '{}'", specific.toolName());

        } else {
            log.warn(
                    "Unknown ToolChoice type: {}, using AUTO mode",
                    toolChoice.getClass().getSimpleName());
            return null;
        }

        GeminiToolConfig toolConfig = new GeminiToolConfig();
        toolConfig.setFunctionCallingConfig(config);
        return toolConfig;
    }

    /**
     * Unwrap the "response" wrapper from generate_response tool schema.
     *
     * <p>The StructuredOutputCapableAgent creates a tool schema like:
     * <pre>
     * {
     *   "type": "object",
     *   "properties": {
     *     "response": { ... actual schema ... }
     *   },
     *   "required": ["response"]
     * }
     * </pre>
     *
     * <p>But Gemini doesn't understand this nested format and fails with
     * "未找到所需属性'response'" (Missing required property "response").
     * This method extracts the inner schema so Gemini receives:
     * <pre>
     * { ... actual schema ... }
     * </pre>
     *
     * @param parameters The original tool parameters with response wrapper
     * @return The unwrapped inner schema, or original if not wrapped
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> unwrapResponseSchema(Map<String, Object> parameters) {
        if (parameters == null) {
            return parameters;
        }

        Object propertiesObj = parameters.get("properties");
        if (!(propertiesObj instanceof Map)) {
            return parameters;
        }

        Map<String, Object> properties = (Map<String, Object>) propertiesObj;

        // Check if this is the wrapped format: only has "response" property
        if (properties.size() == 1 && properties.containsKey("response")) {
            Object responseSchema = properties.get("response");
            if (responseSchema instanceof Map) {
                Map<String, Object> innerSchema = (Map<String, Object>) responseSchema;
                // Return the inner schema directly
                log.info(
                        "Unwrapping generate_response schema: {} -> {}",
                        parameters.keySet(),
                        innerSchema.keySet());
                return new HashMap<>(innerSchema);
            }
        }

        return parameters;
    }
}
