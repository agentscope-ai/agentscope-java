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
                    declaration.setParameters(toolSchema.getParameters());
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
}
