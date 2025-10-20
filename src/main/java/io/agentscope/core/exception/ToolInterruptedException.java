/*
 * Copyright 2024-2025 the original author or authors.
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
package io.agentscope.core.exception;

/**
 * Exception raised when a tool execution is interrupted.
 * Aligns with Python's ToolInterruptedError.
 *
 * <p>This exception can be thrown in several scenarios:
 * <ul>
 *   <li>Tool code explicitly calls {@link ToolInterrupter#interrupt()}</li>
 *   <li>User calls {@code agent.interrupt()} during tool execution</li>
 *   <li>System interrupts due to timeout or resource limits</li>
 * </ul>
 */
public class ToolInterruptedException extends AgentScopeException {

    private final InterruptSource source;
    private final String toolName;

    /**
     * Constructs a new exception with the specified message and source.
     *
     * @param message the detail message
     * @param source the source of the interruption
     */
    public ToolInterruptedException(String message, InterruptSource source) {
        super(message);
        this.source = source;
        this.toolName = null;
    }

    /**
     * Constructs a new exception with the specified message, source, and tool name.
     *
     * @param message the detail message
     * @param source the source of the interruption
     * @param toolName the name of the tool being interrupted
     */
    public ToolInterruptedException(String message, InterruptSource source, String toolName) {
        super(message);
        this.source = source;
        this.toolName = toolName;
    }

    /**
     * Get the source of this interruption.
     *
     * @return the interruption source
     */
    public InterruptSource getSource() {
        return source;
    }

    /**
     * Get the name of the tool that was interrupted.
     *
     * @return the tool name, or null if not applicable
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * Get a user-facing message based on the interruption source.
     *
     * @return a formatted message appropriate for the interruption source
     */
    public String getUserMessage() {
        return switch (source) {
            case USER -> "The operation has been interrupted by the user.";
            case TOOL ->
                    String.format(
                            "Tool '%s' execution was interrupted: %s",
                            toolName != null ? toolName : "unknown", getMessage());
            case SYSTEM -> "The operation was interrupted by the system.";
        };
    }
}
