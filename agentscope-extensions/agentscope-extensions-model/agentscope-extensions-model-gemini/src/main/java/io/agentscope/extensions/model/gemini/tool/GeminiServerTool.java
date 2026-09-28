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
package io.agentscope.extensions.model.gemini.tool;

import com.google.genai.types.Tool;

/**
 * Gemini-specific server tool definition.
 *
 * <p>Server tools are executed by Gemini instead of the local AgentScope toolkit. Construct the
 * strongly typed Google GenAI SDK tool definition, then wrap it with {@link #of(Tool)}. SDK
 * builders own parameter names, types, and validation.
 *
 * <p>Example:
 *
 * <pre>{@code
 * GeminiServerTool.of(Tool.builder()
 *         .googleSearch(GoogleSearch.builder()
 *                 .excludeDomains(List.of("example.com"))
 *                 .build())
 *         .build());
 * }</pre>
 */
public final class GeminiServerTool {

    private final Tool tool;

    private GeminiServerTool(Tool tool) {
        this.tool = tool;
    }

    /**
     * Wraps an SDK-built Gemini server tool definition.
     *
     * <p>Custom client tools are not accepted; use the regular toolkit tool schema for those.
     *
     * @param tool the SDK tool definition
     * @return a validated server tool
     * @throws IllegalArgumentException if the tool is null or contains local function tools
     */
    public static GeminiServerTool of(Tool tool) {
        if (tool == null) {
            throw new IllegalArgumentException("Gemini server tool must not be null");
        }
        if (tool.functionDeclarations().isPresent() || tool.functions().isPresent()) {
            throw new IllegalArgumentException(
                    "GeminiServerTool does not accept local function tools");
        }
        return new GeminiServerTool(tool);
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
