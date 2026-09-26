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
package io.agentscope.extensions.model.anthropic.tool;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.ToolUnion;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Anthropic-specific server tool definition.
 *
 * <p>Server tools are executed on Anthropic's infrastructure within a single model call. Construct
 * SDK tool definitions with their strongly typed builders, then wrap the resulting {@link
 * ToolUnion} with {@link #of(ToolUnion)}. For API fields not yet modeled by the SDK, use the SDK
 * builder's {@code putAdditionalProperty("field", JsonValue.from(value))}.
 *
 * <p>Example:
 *
 * <pre>{@code
 * AnthropicServerTool.of(ToolUnion.ofWebFetchTool20250910(
 *         WebFetchTool20250910.builder()
 *                 .maxUses(10)
 *                 .allowedDomains(List.of("docs.anthropic.com"))
 *                 .build()));
 * }</pre>
 */
public final class AnthropicServerTool {

    private final ToolUnion toolUnion;
    private final String name;

    private AnthropicServerTool(ToolUnion toolUnion, String name) {
        this.toolUnion = toolUnion;
        this.name = name;
    }

    /**
     * Wraps an SDK-built Anthropic tool definition.
     *
     * <p>Custom client tools are not accepted; use the regular toolkit tool schema for those.
     *
     * @param toolUnion the SDK tool definition
     * @return a validated server tool
     * @throws IllegalArgumentException if the tool is null, invalid, or a custom client tool
     */
    public static AnthropicServerTool of(ToolUnion toolUnion) {
        if (toolUnion == null) {
            throw new IllegalArgumentException("Anthropic server tool must not be null");
        }
        if (toolUnion.isTool()) {
            throw new IllegalArgumentException(
                    "AnthropicServerTool does not accept custom client tools");
        }

        try {
            toolUnion.validate();
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Invalid Anthropic server tool definition: " + e.getMessage(), e);
        }

        JsonNode json = ObjectMappers.jsonMapper().valueToTree(toolUnion);
        return new AnthropicServerTool(toolUnion, json.path("name").asText());
    }

    /**
     * Gets the Anthropic tool name (e.g. {@code web_search}).
     *
     * @return the non-versioned tool name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the cached, validated SDK tool definition.
     *
     * @return the SDK tool union
     */
    public ToolUnion toToolUnion() {
        return toolUnion;
    }
}
