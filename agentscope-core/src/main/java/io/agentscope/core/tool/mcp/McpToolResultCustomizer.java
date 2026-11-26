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
package io.agentscope.core.tool.mcp;

import java.util.Map;

/**
 * Hook for shaping the map that an MCP tool call exposes under {@link
 * McpTool#METADATA_MCP_RESULT_META} on the {@link io.agentscope.core.message.ToolResultBlock} and,
 * from there, on {@code ToolResultEndEvent}.
 *
 * <p>The framework does not expose the server's raw {@code _meta} map by default. When at least one
 * customizer is registered, the supplied map starts empty. A customizer can read the raw map from
 * {@link McpToolResultContext#resultMeta()} and copy selected entries into the supplied map, or
 * derive its own entries. The map produced after all customizers run becomes the value stored under
 * {@link McpTool#METADATA_MCP_RESULT_META}. A later customizer can override or remove entries written
 * by an earlier one.
 *
 * <p>Register an ordered list per connection:
 *
 * <pre>{@code
 * McpClientWrapper client = McpClientBuilder.create("customized-mcp")
 *         .streamableHttpTransport("https://mcp.example.com/http")
 *         .resultCustomizers(List.of(
 *                 (ctx, resultMetadata) -> {
 *                     Object traceId = ctx.resultMeta().get("traceId");
 *                     if (traceId != null) {
 *                         resultMetadata.put("trace.id", traceId);
 *                     }
 *                 }))
 *         .buildSync();
 * }</pre>
 *
 * <p>Customizers run for every completion of every tool on the connection, including results the
 * server flagged as errors. They run on the calling thread and must be lightweight. A customizer
 * failure is logged and skipped, preserving the original tool result. Null keys and null values
 * are removed by the framework before the metadata is attached.
 *
 * @see McpToolResultContext
 * @see McpClientBuilder#resultCustomizers(List)
 */
@FunctionalInterface
public interface McpToolResultCustomizer {

    /**
     * Adjusts the result metadata for one MCP tool call.
     *
     * @param context the call, its arguments and the server's result metadata
     * @param resultMetadata mutable map that will be exposed under {@link
     *     McpTool#METADATA_MCP_RESULT_META}; starts empty
     */
    void customize(McpToolResultContext context, Map<String, Object> resultMetadata);
}
