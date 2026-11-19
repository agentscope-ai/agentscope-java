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
package io.agentscope.extensions.model.openaiofficial.tool;

import com.openai.models.responses.Tool;
import com.openai.models.responses.ToolSearchTool;

/**
 * OpenAI Responses API server-side tool definition.
 *
 * <p>Construct an SDK built-in tool, then wrap it with {@link #of(Tool)}. Custom function tools
 * and tools that require an external runtime or connector are rejected; use the regular AgentScope
 * toolkit for custom functions.
 */
public final class OpenAIServerTool {

    /** Stable tool type for the current web search tool. */
    public static final String WEB_SEARCH = "web_search";

    /** Stable tool type for code interpreter. */
    public static final String CODE_INTERPRETER = "code_interpreter";

    /** Stable tool type for image generation. */
    public static final String IMAGE_GENERATION = "image_generation";

    /** Stable tool type for tool search. */
    public static final String TOOL_SEARCH = "tool_search";

    private final Tool tool;
    private final String type;

    private OpenAIServerTool(Tool tool, String type) {
        this.tool = tool;
        this.type = type;
    }

    /**
     * Wraps an SDK-built OpenAI built-in tool.
     *
     * @param tool the SDK tool definition
     * @return a validated server tool
     * @throws IllegalArgumentException if the tool is null or unsupported
     */
    public static OpenAIServerTool of(Tool tool) {
        if (tool == null) {
            throw new IllegalArgumentException("OpenAI server tool must not be null");
        }

        String type = resolveType(tool);
        if (type == null) {
            throw new IllegalArgumentException(
                    "OpenAIServerTool supports provider-executed built-in tools only. Custom"
                            + " functions, namespaces, MCP, computer use, shell, apply patch, and"
                            + " local runtime tools are not accepted.");
        }

        if (TOOL_SEARCH.equals(type)) {
            tool.search()
                    .flatMap(ToolSearchTool::execution)
                    .filter(ToolSearchTool.Execution.CLIENT::equals)
                    .ifPresent(
                            execution -> {
                                throw new IllegalArgumentException(
                                        "Client-executed tool_search is not a provider-executed"
                                                + " server tool.");
                            });
        }

        try {
            tool.validate();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "Invalid OpenAI server tool definition: " + e.getMessage(), e);
        }

        return new OpenAIServerTool(tool, type);
    }

    private static String resolveType(Tool tool) {
        if (tool.webSearch().isPresent()) {
            return WEB_SEARCH;
        }
        if (tool.codeInterpreter().isPresent()) {
            return CODE_INTERPRETER;
        }
        if (tool.imageGeneration().isPresent()) {
            return IMAGE_GENERATION;
        }
        if (tool.search().isPresent()) {
            return TOOL_SEARCH;
        }
        return null;
    }

    /**
     * Gets the stable Responses API tool type.
     *
     * @return the tool type
     */
    public String getType() {
        return type;
    }

    /**
     * Returns the cached SDK tool definition.
     *
     * @return the SDK tool
     */
    public Tool toTool() {
        return tool;
    }
}
