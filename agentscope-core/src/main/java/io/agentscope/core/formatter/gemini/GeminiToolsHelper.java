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

import com.google.genai.types.FunctionCallingConfig;
import com.google.genai.types.FunctionCallingConfigMode;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.genai.types.Tool;
import com.google.genai.types.ToolConfig;
import com.google.genai.types.Type;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles tool registration and configuration for Gemini API.
 *
 * <p>This helper converts AgentScope tool schemas to Gemini's Tool and ToolConfig format:
 * <ul>
 *   <li>Tool: Contains function declarations with JSON Schema parameters</li>
 *   <li>ToolConfig: Contains function calling mode configuration</li>
 * </ul>
 *
 * <p><b>Tool Choice Mapping:</b>
 * <ul>
 *   <li>Auto: mode=AUTO (model decides)</li>
 *   <li>None: mode=NONE (disable tool calling)</li>
 *   <li>Required: mode=ANY (force tool call from all provided tools)</li>
 *   <li>Specific: mode=ANY + allowedFunctionNames (force specific tool)</li>
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
    public Tool convertToGeminiTool(List<ToolSchema> tools) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }

        List<FunctionDeclaration> functionDeclarations = new ArrayList<>();

        for (ToolSchema toolSchema : tools) {
            try {
                FunctionDeclaration.Builder builder = FunctionDeclaration.builder();

                // Set name (required)
                if (toolSchema.getName() != null) {
                    builder.name(toolSchema.getName());
                }

                // Set description (optional)
                if (toolSchema.getDescription() != null) {
                    builder.description(toolSchema.getDescription());
                }

                // Convert parameters to Gemini Schema
                if (toolSchema.getParameters() != null && !toolSchema.getParameters().isEmpty()) {
                    Schema schema = convertParametersToSchema(toolSchema.getParameters());
                    builder.parameters(schema);
                }

                functionDeclarations.add(builder.build());
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

        return Tool.builder().functionDeclarations(functionDeclarations).build();
    }

    /**
     * Convert parameters map to Gemini Schema object.
     *
     * @param parameters Parameter schema map (JSON Schema format)
     * @return Gemini Schema object
     */
    protected Schema convertParametersToSchema(Map<String, Object> parameters) {
        Schema.Builder schemaBuilder = Schema.builder();

        // Set type (default to OBJECT)
        if (parameters.containsKey("type")) {
            String typeStr = (String) parameters.get("type");
            Type type = convertJsonTypeToGeminiType(typeStr);
            schemaBuilder.type(type);
        } else {
            schemaBuilder.type(new Type(Type.Known.OBJECT));
        }

        // Set description
        if (parameters.containsKey("description")) {
            schemaBuilder.description((String) parameters.get("description"));
        }

        // Set properties (for OBJECT type)
        if (parameters.containsKey("properties")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> propertiesMap = (Map<String, Object>) parameters.get("properties");

            Map<String, Schema> propertiesSchemas = new HashMap<>();
            for (Map.Entry<String, Object> entry : propertiesMap.entrySet()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> propertySchema = (Map<String, Object>) entry.getValue();
                propertiesSchemas.put(entry.getKey(), convertParametersToSchema(propertySchema));
            }
            schemaBuilder.properties(propertiesSchemas);
        }

        // Set required fields
        if (parameters.containsKey("required")) {
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) parameters.get("required");
            schemaBuilder.required(required);
        }

        // Set items (for ARRAY type)
        if (parameters.containsKey("items")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> itemsSchema = (Map<String, Object>) parameters.get("items");
            schemaBuilder.items(convertParametersToSchema(itemsSchema));
        }

        // Set enum values
        if (parameters.containsKey("enum")) {
            @SuppressWarnings("unchecked")
            List<String> enumValues = (List<String>) parameters.get("enum");
            schemaBuilder.enum_(enumValues);
        }

        return schemaBuilder.build();
    }

    /**
     * Convert JSON Schema type string to Gemini Type.
     *
     * @param jsonType JSON Schema type string (e.g., "object", "string", "number")
     * @return Gemini Type object
     */
    protected Type convertJsonTypeToGeminiType(String jsonType) {
        if (jsonType == null) {
            return new Type(Type.Known.TYPE_UNSPECIFIED);
        }

        return switch (jsonType.toLowerCase()) {
            case "object" -> new Type(Type.Known.OBJECT);
            case "array" -> new Type(Type.Known.ARRAY);
            case "string" -> new Type(Type.Known.STRING);
            case "number" -> new Type(Type.Known.NUMBER);
            case "integer" -> new Type(Type.Known.INTEGER);
            case "boolean" -> new Type(Type.Known.BOOLEAN);
            default -> {
                log.warn("Unknown JSON type '{}', using TYPE_UNSPECIFIED", jsonType);
                yield new Type(Type.Known.TYPE_UNSPECIFIED);
            }
        };
    }

    /**
     * Create Gemini ToolConfig from AgentScope ToolChoice.
     *
     * <p>Tool choice mapping:
     * <ul>
     *   <li>null or Auto: mode=AUTO (model decides)</li>
     *   <li>None: mode=NONE (disable tool calling)</li>
     *   <li>Required: mode=ANY (force tool call from all provided tools)</li>
     *   <li>Specific: mode=ANY + allowedFunctionNames (force specific tool)</li>
     * </ul>
     *
     * @param toolChoice The tool choice configuration (null means auto)
     * @return Gemini ToolConfig object, or null if auto (default behavior)
     */
    public ToolConfig convertToolChoice(ToolChoice toolChoice) {
        if (toolChoice == null || toolChoice instanceof ToolChoice.Auto) {
            // Auto is the default behavior, no need to set explicit config
            log.debug("ToolChoice.Auto: using default AUTO mode");
            return null;
        }

        FunctionCallingConfig.Builder configBuilder = FunctionCallingConfig.builder();

        if (toolChoice instanceof ToolChoice.None) {
            // NONE: disable tool calling
            configBuilder.mode(FunctionCallingConfigMode.Known.NONE);
            log.debug("ToolChoice.None: set mode to NONE");

        } else if (toolChoice instanceof ToolChoice.Required) {
            // ANY: force tool call from all provided tools
            configBuilder.mode(FunctionCallingConfigMode.Known.ANY);
            log.debug("ToolChoice.Required: set mode to ANY");

        } else if (toolChoice instanceof ToolChoice.Specific specific) {
            // ANY with allowedFunctionNames: force specific tool call
            configBuilder.mode(FunctionCallingConfigMode.Known.ANY);
            configBuilder.allowedFunctionNames(List.of(specific.toolName()));
            log.debug("ToolChoice.Specific: set mode to ANY with tool '{}'", specific.toolName());

        } else {
            log.warn(
                    "Unknown ToolChoice type: {}, using AUTO mode",
                    toolChoice.getClass().getSimpleName());
            return null;
        }

        FunctionCallingConfig functionCallingConfig = configBuilder.build();
        return ToolConfig.builder().functionCallingConfig(functionCallingConfig).build();
    }
}
