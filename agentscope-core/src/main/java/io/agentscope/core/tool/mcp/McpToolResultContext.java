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

import io.modelcontextprotocol.spec.McpSchema;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everything an {@link McpToolResultCustomizer} sees about one completed MCP tool call.
 *
 * <p>It carries the call identity ({@link #toolName()}, {@link #remoteToolName()}, {@link
 * #toolCallId()}), the arguments that were sent, the server's result {@code _meta} and the raw
 * result. The map a customizer adjusts is exposed under {@link McpTool#METADATA_MCP_RESULT_META} on
 * the {@link io.agentscope.core.message.ToolResultBlock} the tool produces, which the agent runtime
 * copies onto the {@code ToolResultEndEvent}. The framework does not expose this map unless a
 * customizer is configured. Callers can use it for logging, tracing, UI rendering, policy checks, or
 * other application-specific processing without changing the model-facing tool output.
 *
 * @param toolName model-facing name of the tool, including any registration-time prefix
 * @param remoteToolName name the MCP server exposes for the same tool
 * @param toolCallId id of the model's tool call, or {@code null} when the call was invoked outside
 *     a message
 * @param arguments arguments actually sent to the server (preset arguments merged in)
 * @param resultMeta the result's {@code _meta} map, never {@code null} (empty when absent)
 * @param result the raw MCP result, including when the server flagged it as an error
 */
public record McpToolResultContext(
        String toolName,
        String remoteToolName,
        String toolCallId,
        Map<String, Object> arguments,
        Map<String, Object> resultMeta,
        McpSchema.CallToolResult result) {

    public McpToolResultContext {
        arguments =
                arguments == null || arguments.isEmpty()
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        resultMeta =
                resultMeta == null || resultMeta.isEmpty()
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(resultMeta));
    }
}
