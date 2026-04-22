/*
 * Copyright 2024-2026 the original author or authors.
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
package io.agentscope.core.formatter.ollama;

import io.agentscope.core.formatter.ollama.dto.OllamaFunction;
import io.agentscope.core.formatter.ollama.dto.OllamaRequest;
import io.agentscope.core.formatter.ollama.dto.OllamaTool;
import io.agentscope.core.formatter.ollama.dto.OllamaToolCall;
import io.agentscope.core.formatter.ollama.dto.OllamaToolFunction;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.ollama.OllamaOptions;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper class for managing Ollama tool conversions and options application.
 *
 */
public class OllamaToolsHelper {

    private static final Logger log = LoggerFactory.getLogger(OllamaToolsHelper.class);

    public OllamaToolsHelper() {
        // Use JsonUtils for JSON operations
    }

    /**
     * Apply GenerateOptions to OllamaRequest.
     *
     * @param request Ollama request to modify
     * @param options Generation options to apply
     * @param defaultOptions Default options to use if options parameter is null
     */
    public void applyOptions(
            OllamaRequest request, GenerateOptions options, GenerateOptions defaultOptions) {
        OllamaOptions runtimeOpts = OllamaOptions.fromGenerateOptions(options);
        OllamaOptions defaultOpts = OllamaOptions.fromGenerateOptions(defaultOptions);
        applyOptions(request, runtimeOpts, defaultOpts);
    }

    /**
     * Apply OllamaOptions to OllamaRequest.
     *
     * @param request Ollama request to modify
     * @param options Ollama options to apply
     * @param defaultOptions Default Ollama options to use if options parameter is null
     */
    public void applyOptions(
            OllamaRequest request, OllamaOptions options, OllamaOptions defaultOptions) {
        OllamaOptions merged;
        if (defaultOptions != null) {
            merged = defaultOptions.merge(options);
        } else if (options != null) {
            merged = options;
        } else {
            merged = OllamaOptions.builder().build();
        }

        // Convert to map
        @SuppressWarnings("unchecked")
        Map<String, Object> optionsMap = JsonUtils.getJsonCodec().convertValue(merged, Map.class);

        // Move top-level options from map to request object and remove from options map
        if (merged.getFormat() != null) {
            request.setFormat(merged.getFormat());
        }
        optionsMap.remove("format");

        if (merged.getKeepAlive() != null) {
            request.setKeepAlive(merged.getKeepAlive());
        }
        optionsMap.remove("keep_alive");

        if (merged.getThinkOption() != null) {
            request.setThink(merged.getThinkOption().toJsonValue());
        }
        optionsMap.remove("think");

        // Remove other non-option fields that shouldn't be in the options block
        optionsMap.remove("model");
        optionsMap.remove("executionConfig");

        request.setOptions(optionsMap);
    }

    /**
     * Converts a list of AgentScope ToolSchema objects to OllamaTool objects.
     *
     * @param tools The list of ToolSchema objects to convert.
     * @return A list of OllamaTool objects, or null if the input list is null or empty.
     */
    public List<OllamaTool> convertTools(List<ToolSchema> tools) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }
        return tools.stream().map(this::convertTool).collect(Collectors.toList());
    }

    private OllamaTool convertTool(ToolSchema tool) {
        OllamaToolFunction function = new OllamaToolFunction();
        function.setName(tool.getName());
        function.setDescription(tool.getDescription());

        // Directly map parameters from ToolSchema (which is already a Map/JSON Schema)
        if (tool.getParameters() != null) {
            function.setParameters(tool.getParameters());
        } else {
            function.setParameters(Collections.emptyMap());
        }

        return new OllamaTool(function);
    }

    /**
     * Apply tools to OllamaRequest.
     *
     * @param request Ollama request to modify
     * @param tools List of tool schemas to apply (may be null or empty)
     */
    public void applyTools(OllamaRequest request, List<ToolSchema> tools) {
        List<OllamaTool> ollamaTools = convertTools(tools);
        if (ollamaTools != null && !ollamaTools.isEmpty()) {
            request.setTools(ollamaTools);
        }
    }

    /**
     * Apply tool choice configuration to OllamaRequest.
     *
     * @param request Ollama request to modify
     * @param toolChoice The tool choice configuration (null means auto/default)
     */
    public void applyToolChoice(OllamaRequest request, ToolChoice toolChoice) {
        if (toolChoice == null) {
            return;
        }

        if (toolChoice instanceof ToolChoice.Auto) {
            request.setToolChoice("auto");
        } else if (toolChoice instanceof ToolChoice.None) {
            // "none" prevents the model from calling tools
            request.setToolChoice("none");
        } else if (toolChoice instanceof ToolChoice.Required) {
            // Ollama does not natively support generic "required" (any tool).
            // We fallback to "auto" which allows tool use, but log a debug message.
            log.debug("ToolChoice.Required is not strictly supported by Ollama. Using 'auto'.");
            request.setToolChoice("auto");
        } else if (toolChoice instanceof ToolChoice.Specific) {
            ToolChoice.Specific specific = (ToolChoice.Specific) toolChoice;
            // Format: {"type": "function", "function": {"name": "my_tool"}}
            Map<String, Object> functionObj = Map.of("name", specific.toolName());
            Map<String, Object> typeObj = Map.of("type", "function", "function", functionObj);
            request.setToolChoice(typeObj);
        }
    }

    /**
     * Convert ToolUseBlock list to OllamaToolCall list.
     *
     * @param toolBlocks The tool use blocks to convert
     * @return List of OllamaToolCall objects (empty list if input is null/empty)
     */
    public List<OllamaToolCall> convertToolCalls(List<ToolUseBlock> toolBlocks) {
        if (toolBlocks == null || toolBlocks.isEmpty()) {
            return Collections.emptyList();
        }

        List<OllamaToolCall> result = new ArrayList<>();
        for (ToolUseBlock block : toolBlocks) {
            if (block == null) {
                continue;
            }
            OllamaFunction function = new OllamaFunction(block.getName(), block.getInput());
            result.add(new OllamaToolCall(function));
        }
        return result;
    }
}
