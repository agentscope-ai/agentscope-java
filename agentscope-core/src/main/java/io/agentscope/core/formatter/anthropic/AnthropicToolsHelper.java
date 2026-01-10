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
package io.agentscope.core.formatter.anthropic;

import io.agentscope.core.formatter.anthropic.dto.AnthropicRequest;
import io.agentscope.core.formatter.anthropic.dto.AnthropicTool;
import io.agentscope.core.model.GenerateOptions;
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
 * Helper class for tool registration and option application for Anthropic API.
 */
public class AnthropicToolsHelper {

    private static final Logger log = LoggerFactory.getLogger(AnthropicToolsHelper.class);

    /**
     * Apply tools to the Anthropic request.
     *
     * @param request The Anthropic request
     * @param tools   List of tool schemas
     * @param options Generate options containing tool choice
     */
    public static void applyTools(
            AnthropicRequest request, List<ToolSchema> tools, GenerateOptions options) {
        if (tools == null || tools.isEmpty()) {
            return;
        }

        // Convert and add tools
        List<AnthropicTool> anthropicTools = new ArrayList<>();
        for (ToolSchema schema : tools) {
            Map<String, Object> inputSchema = schema.getParameters();

            AnthropicTool tool =
                    new AnthropicTool(schema.getName(), schema.getDescription(), inputSchema);
            anthropicTools.add(tool);
        }

        request.setTools(anthropicTools);

        // Apply tool choice if specified
        if (options != null && options.getToolChoice() != null) {
            applyToolChoice(request, options.getToolChoice());
        }
    }

    /**
     * Convert tool parameters to Map.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> convertToMap(Object parameters) {
        try {
            if (parameters == null) {
                return new HashMap<>();
            }
            // Convert to JSON string and back to Map
            String json = JsonUtils.getJsonCodec().toJson(parameters);
            return JsonUtils.getJsonCodec().fromJson(json, Map.class);
        } catch (Exception e) {
            log.error("Failed to convert tool parameters to Map", e);
            return new HashMap<>();
        }
    }

    /**
     * Apply tool choice to the request.
     */
    private static void applyToolChoice(AnthropicRequest request, ToolChoice toolChoice) {
        if (toolChoice instanceof ToolChoice.Auto) {
            Map<String, String> choice = new HashMap<>();
            choice.put("type", "auto");
            request.setToolChoice(choice);
        } else if (toolChoice instanceof ToolChoice.None) {
            // Anthropic doesn't have None, use Any instead
            Map<String, String> choice = new HashMap<>();
            choice.put("type", "any");
            request.setToolChoice(choice);
        } else if (toolChoice instanceof ToolChoice.Required) {
            // Anthropic doesn't have a direct "required" option, use "any" which forces
            // tool
            // use
            log.warn(
                    "Anthropic API doesn't support ToolChoice.Required directly, using 'any'"
                            + " instead");
            Map<String, String> choice = new HashMap<>();
            choice.put("type", "any");
            request.setToolChoice(choice);
        } else if (toolChoice instanceof ToolChoice.Specific specific) {
            Map<String, String> choice = new HashMap<>();
            choice.put("type", "tool");
            choice.put("name", specific.toolName());
            request.setToolChoice(choice);
        } else {
            log.warn("Unknown tool choice type: {}", toolChoice);
        }
    }

    /**
     * Apply generation options to the request.
     *
     * @param request        The Anthropic request
     * @param options        Generate options
     * @param defaultOptions Default generate options
     */
    public static void applyOptions(
            AnthropicRequest request, GenerateOptions options, GenerateOptions defaultOptions) {
        // Temperature
        Double temperature = getOption(options, defaultOptions, GenerateOptions::getTemperature);
        if (temperature != null) {
            request.setTemperature(temperature);
        }

        // Top P
        Double topP = getOption(options, defaultOptions, GenerateOptions::getTopP);
        if (topP != null) {
            request.setTopP(topP);
        }

        // Top K
        Integer topK = getOption(options, defaultOptions, GenerateOptions::getTopK);
        if (topK != null) {
            request.setTopK(topK);
        }

        // Max tokens
        Integer maxTokens = getOption(options, defaultOptions, GenerateOptions::getMaxTokens);
        if (maxTokens != null) {
            request.setMaxTokens(maxTokens);
        }

        // Note: Additional headers and query params are handled by the client, not the
        // request
        // Additional body params can be added to metadata if needed
        applyAdditionalBodyParams(request, defaultOptions);
        applyAdditionalBodyParams(request, options);
    }

    private static void applyAdditionalBodyParams(AnthropicRequest request, GenerateOptions opts) {
        if (opts == null) return;
        Map<String, Object> params = opts.getAdditionalBodyParams();
        if (params != null && !params.isEmpty()) {
            Map<String, Object> metadata = request.getMetadata();
            if (metadata == null) {
                metadata = new HashMap<>();
                request.setMetadata(metadata);
            }
            metadata.putAll(params);
            log.debug("Applied {} additional body params to Anthropic request", params.size());
        }
    }

    /**
     * Get option value, preferring specific over default.
     */
    private static <T> T getOption(
            GenerateOptions options,
            GenerateOptions defaultOptions,
            java.util.function.Function<GenerateOptions, T> getter) {
        if (options != null) {
            T value = getter.apply(options);
            if (value != null) {
                return value;
            }
        }
        if (defaultOptions != null) {
            return getter.apply(defaultOptions);
        }
        return null;
    }
}
