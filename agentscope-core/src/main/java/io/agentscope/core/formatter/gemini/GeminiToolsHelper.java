/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
package io.agentscope.core.formatter.gemini;

import io.agentscope.core.formatter.gemini.dto.GeminiTool;
import io.agentscope.core.formatter.gemini.dto.GeminiTool.GeminiFunctionDeclaration;
import io.agentscope.core.formatter.gemini.dto.GeminiToolConfig;
import io.agentscope.core.formatter.gemini.dto.GeminiToolConfig.GeminiFunctionCallingConfig;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles tool registration and configuration for Gemini API.
 */
public class GeminiToolsHelper {

    private static final Logger log = LoggerFactory.getLogger(GeminiToolsHelper.class);

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
                if (toolSchema.getParameters() != null && !toolSchema.getParameters().isEmpty()) {
                    // Clean schema to remove Gemini-incompatible fields
                    Map<String, Object> cleanedParams =
                            cleanSchemaForGemini(toolSchema.getParameters());
                    declaration.setParameters(cleanedParams);

                    // Debug: Log the cleaned schema
                    try {
                        String schemaJson =
                                new com.fasterxml.jackson.databind.ObjectMapper()
                                        .writerWithDefaultPrettyPrinter()
                                        .writeValueAsString(cleanedParams);
                        log.debug(
                                "Cleaned schema for tool '{}': {}",
                                toolSchema.getName(),
                                schemaJson);
                    } catch (Exception e) {
                        log.debug("Could not serialize schema for logging: {}", e.getMessage());
                    }
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
     * Clean JSON Schema by removing Gemini-incompatible fields.
     * Recursively removes 'id' fields from the schema and its nested properties.
     *
     * @param schema The schema map to clean
     * @return Cleaned schema map (creates a new map to avoid modifying the
     *         original)
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> cleanSchemaForGemini(Map<String, Object> schema) {
        if (schema == null) {
            return null;
        }

        // Create a new map to avoid modifying the original
        Map<String, Object> cleaned = new java.util.HashMap<>(schema);

        // Remove unsupported/unnecessary fields
        cleaned.remove("id");
        cleaned.remove("$schema");
        cleaned.remove("title");
        cleaned.remove("default");
        cleaned.remove("nullable");

        // Recursively clean nested properties
        if (cleaned.containsKey("properties") && cleaned.get("properties") instanceof Map) {
            Map<String, Object> properties = (Map<String, Object>) cleaned.get("properties");
            Map<String, Object> cleanedProperties = new java.util.HashMap<>();
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                if (entry.getValue() instanceof Map) {
                    cleanedProperties.put(
                            entry.getKey(),
                            cleanSchemaForGemini((Map<String, Object>) entry.getValue()));
                } else {
                    cleanedProperties.put(entry.getKey(), entry.getValue());
                }
            }
            cleaned.put("properties", cleanedProperties);
        }

        // Clean items in arrays
        if (cleaned.containsKey("items") && cleaned.get("items") instanceof Map) {
            cleaned.put("items", cleanSchemaForGemini((Map<String, Object>) cleaned.get("items")));
        }

        // Clean additionalProperties
        if (cleaned.containsKey("additionalProperties")
                && cleaned.get("additionalProperties") instanceof Map) {
            cleaned.put(
                    "additionalProperties",
                    cleanSchemaForGemini(
                            (Map<String, Object>) cleaned.get("additionalProperties")));
        }

        // Gemini-specific: Ensure all properties are marked as required if not
        // specified
        // This prevents Gemini from treating fields as optional and returning partial
        // data
        if (cleaned.containsKey("properties") && !cleaned.containsKey("required")) {
            Object propertiesObj = cleaned.get("properties");
            if (propertiesObj instanceof Map) {
                Map<String, Object> properties = (Map<String, Object>) propertiesObj;
                if (!properties.isEmpty()) {
                    List<String> allProperties = new java.util.ArrayList<>(properties.keySet());
                    cleaned.put("required", allProperties);
                    log.debug("Gemini: Added all properties as required fields: {}", allProperties);
                }
            }
        }

        return cleaned;
    }
}
