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
package io.agentscope.extensions.model.gemini.formatter;

import com.google.genai.types.FunctionCallingConfig;
import com.google.genai.types.FunctionCallingConfigMode;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Tool;
import com.google.genai.types.ToolConfig;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.gemini.tool.GeminiServerTool;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converts and combines tool configuration for the Gemini API.
 *
 * <p><b>Responsibilities:</b>
 * <ul>
 *   <li>Convert AgentScope {@link ToolSchema} definitions into a Gemini {@link Tool} containing
 *       function declarations and JSON Schema parameters</li>
 *   <li>Append provider-specific {@link GeminiServerTool} entries to {@link
 *       GenerateContentConfig} while preserving existing function tools</li>
 *   <li>Convert AgentScope {@link ToolChoice} into Gemini {@link ToolConfig} function-calling
 *       policy</li>
 * </ul>
 *
 * <p>Function tools and Gemini server tools remain separate entries in {@code
 * GenerateContentConfig.tools}; tool-choice policy applies to function calling only.
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

    /** Creates a new GeminiToolsHelper. */
    public GeminiToolsHelper() {}

    /**
     * Append Gemini server tools to a content config without mutating its existing tool list.
     *
     * @param config      Existing content config
     * @param serverTools Gemini server tools to append (may be null or empty)
     * @return A config containing both existing and server tools, or {@code config} when there are
     * no server tools
     */
    public static GenerateContentConfig mergeServerTools(
            GenerateContentConfig config, List<GeminiServerTool> serverTools) {
        if (serverTools == null || serverTools.isEmpty()) {
            return config;
        }
        GenerateContentConfig.Builder updateConfigBuilder = config.toBuilder();
        ToolConfig.Builder toolConfigBuilder =
                config.toolConfig().map(ToolConfig::toBuilder).orElseGet(ToolConfig::builder);

        List<Tool> mergedTools = new ArrayList<>(config.tools().orElseGet(List::of));
        serverTools.stream().map(GeminiServerTool::toTool).forEach(mergedTools::add);
        updateConfigBuilder
                .tools(mergedTools)
                // You must set the `include_server_side_tool_invocations` flag to `true` to enable
                // tool context looping.
                .toolConfig(toolConfigBuilder.includeServerSideToolInvocations(true).build());
        return updateConfigBuilder.build();
    }

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
            FunctionDeclaration.Builder builder = FunctionDeclaration.builder();

            if (toolSchema.getName() != null) {
                builder.name(toolSchema.getName());
            }

            if (toolSchema.getDescription() != null) {
                builder.description(toolSchema.getDescription());
            }

            if (toolSchema.getParameters() != null && !toolSchema.getParameters().isEmpty()) {
                builder.parametersJsonSchema(toolSchema.getParameters());
            }

            functionDeclarations.add(builder.build());
            log.debug("Converted tool schema: {}", toolSchema.getName());
        }

        return Tool.builder().functionDeclarations(functionDeclarations).build();
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
