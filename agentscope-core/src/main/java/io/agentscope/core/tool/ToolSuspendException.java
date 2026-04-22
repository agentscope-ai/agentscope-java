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
package io.agentscope.core.tool;

/**
 * Exception thrown to signal that a tool execution should be suspended and handled externally.
 *
 * <p>This exception is used by tools that require external execution (e.g., tools registered
 * via schema-only registration). When thrown, the framework will:
 * <ol>
 *   <li>Convert the exception to a pending {@code ToolResultBlock}</li>
 *   <li>Return a suspended message to the user with {@code GenerateReason.TOOL_SUSPENDED}</li>
 *   <li>Wait for the user to provide the tool execution result</li>
 * </ol>
 *
 * <p>Example usage in a custom tool:
 * <pre>{@code
 * @Tool(name = "external_api", description = "Call external API")
 * public ToolResultBlock callExternalApi(@ToolParam(name = "url") String url) {
 *     // Signal that this tool needs external execution
 *     throw new ToolSuspendException("Requires external API call to: " + url);
 * }
 * }</pre>
 *
 * @see SchemaOnlyTool
 */
public class ToolSuspendException extends RuntimeException {

    private final String reason;

    /**
     * Creates a new ToolSuspendException with default message.
     */
    public ToolSuspendException() {
        this(null);
    }

    /**
     * Creates a new ToolSuspendException with a custom reason.
     *
     * @param reason the reason for suspension, will be included in the pending ToolResultBlock
     */
    public ToolSuspendException(String reason) {
        super(reason != null ? reason : "Tool execution suspended");
        this.reason = reason;
    }

    /**
     * Gets the user-defined reason for suspension.
     *
     * @return the reason, or null if not specified
     */
    public String getReason() {
        return reason;
    }
}
