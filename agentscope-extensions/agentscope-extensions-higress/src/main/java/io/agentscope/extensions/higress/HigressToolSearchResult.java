/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.extensions.higress;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.core.util.JsonException;
import io.agentscope.core.util.JsonUtils;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents the result of calling the x_higress_tool_search tool.
 *
 * <p>The x_higress_tool_search tool returns a list of recommended tools based on
 * semantic similarity to the query. This class provides methods to parse and access
 * the search results.
 *
 * <p>Example response structure from Higress:
 * <pre>{@code
 * {
 *   "content": [
 *     {
 *       "type": "text",
 *       "text": "{\"tools\":[...]}"
 *     }
 *   ],
 *   "structuredContent": {
 *     "tools": [
 *       {
 *         "name": "map___maps_weather",
 *         "description": "根据城市名称或者标准adcode查询指定城市的天气",
 *         "title": "maps_weather",
 *         "inputSchema": {...},
 *         "outputSchema": {}
 *       }
 *     ]
 *   }
 * }
 * }</pre>
 *
 */
public class HigressToolSearchResult {

    private static final Logger logger = LoggerFactory.getLogger(HigressToolSearchResult.class);

    private final List<ToolInfo> tools;
    private final boolean success;
    private final String errorMessage;

    private HigressToolSearchResult(List<ToolInfo> tools, boolean success, String errorMessage) {
        this.tools = tools != null ? tools : Collections.emptyList();
        this.success = success;
        this.errorMessage = errorMessage;
    }

    /**
     * Parses the tool search result from MCP CallToolResult.
     *
     * @param callToolResult the result from calling x_higress_tool_search
     * @return parsed HigressToolSearchResult
     */
    public static HigressToolSearchResult parse(McpSchema.CallToolResult callToolResult) {
        if (callToolResult == null) {
            return error("CallToolResult is null");
        }

        if (Boolean.TRUE.equals(callToolResult.isError())) {
            return error("Tool call returned error");
        }

        // Try to parse from structuredContent first
        Object structuredContent = callToolResult.structuredContent();
        if (structuredContent instanceof Map<?, ?> structuredMap) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> typedMap = (Map<String, Object>) structuredMap;
                return parseFromStructuredContent(typedMap);
            } catch (Exception e) {
                logger.debug(
                        "Failed to parse structuredContent, trying content: {}", e.getMessage());
            }
        }

        // Fall back to parsing from content
        if (callToolResult.content() != null && !callToolResult.content().isEmpty()) {
            try {
                return parseFromContent(callToolResult.content());
            } catch (Exception e) {
                logger.error("Failed to parse content: {}", e.getMessage(), e);
                return error("Failed to parse tool search result: " + e.getMessage());
            }
        }

        return error("No content in tool search result");
    }

    /**
     * Parses from structuredContent map.
     */
    @SuppressWarnings("unchecked")
    private static HigressToolSearchResult parseFromStructuredContent(
            Map<String, Object> structuredContent) {
        Object toolsObj = structuredContent.get("tools");
        if (toolsObj instanceof List<?> toolsList) {
            List<ToolInfo> tools =
                    toolsList.stream()
                            .filter(item -> item instanceof Map)
                            .map(item -> parseToolInfo((Map<String, Object>) item))
                            .toList();
            return success(tools);
        }
        throw new IllegalArgumentException("Invalid structuredContent format");
    }

    /**
     * Parses from content list (text content).
     */
    private static HigressToolSearchResult parseFromContent(List<McpSchema.Content> contentList)
            throws JsonException {
        for (McpSchema.Content content : contentList) {
            if (content instanceof McpSchema.TextContent textContent) {
                String text = textContent.text();
                if (text != null && !text.isEmpty()) {
                    ToolSearchResponse response =
                            JsonUtils.getJsonCodec().fromJson(text, ToolSearchResponse.class);
                    if (response.tools != null) {
                        return success(response.tools);
                    }
                }
            }
        }
        throw new IllegalArgumentException("No valid text content found");
    }

    /**
     * Parses a single tool info from a map.
     */
    @SuppressWarnings("unchecked")
    private static ToolInfo parseToolInfo(Map<String, Object> map) {
        return new ToolInfo(
                (String) map.get("name"),
                (String) map.get("description"),
                (String) map.get("title"),
                (Map<String, Object>) map.get("inputSchema"),
                (Map<String, Object>) map.get("outputSchema"));
    }

    private static HigressToolSearchResult success(List<ToolInfo> tools) {
        return new HigressToolSearchResult(tools, true, null);
    }

    private static HigressToolSearchResult error(String message) {
        return new HigressToolSearchResult(Collections.emptyList(), false, message);
    }

    /**
     * Returns whether the search was successful.
     *
     * @return true if successful
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Returns the error message if the search failed.
     *
     * @return error message, or null if successful
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Returns the list of recommended tools.
     *
     * @return list of tools, never null but may be empty
     */
    public List<ToolInfo> getTools() {
        return tools;
    }

    /**
     * Returns the names of all found tools.
     *
     * @return list of tool names
     */
    public List<String> getToolNames() {
        return tools.stream().map(ToolInfo::name).toList();
    }

    @Override
    public String toString() {
        if (!success) {
            return "HigressToolSearchResult{error='" + errorMessage + "'}";
        }
        return "HigressToolSearchResult{tools=" + getToolNames() + "}";
    }

    /**
     * Represents a single tool returned from the search.
     *
     * @param name the tool name (e.g., "map___maps_weather")
     * @param description the tool description
     * @param title the tool title (e.g., "maps_weather")
     * @param inputSchema the input parameter JSON schema
     * @param outputSchema the output parameter JSON schema
     */
    public record ToolInfo(
            String name,
            String description,
            String title,
            Map<String, Object> inputSchema,
            Map<String, Object> outputSchema) {}

    /**
     * Internal class for JSON deserialization.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ToolSearchResponse {
        @JsonProperty("tools")
        List<ToolInfo> tools;
    }
}
