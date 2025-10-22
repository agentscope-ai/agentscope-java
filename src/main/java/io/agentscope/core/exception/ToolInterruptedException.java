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
 * Exception thrown when a tool execution is interrupted.
 *
 * <p>This exception can be thrown by tool implementations to signal that
 * the tool execution should be interrupted. It is typically caught by the
 * agent's execution framework and handled appropriately.
 *
 * <p>Example usage in a tool:
 * <pre>{@code
 * @Tool(name = "long_task", description = "Execute a long running task")
 * public String longTask(@ToolParam(name = "input") String input) {
 *     for (int i = 0; i < iterations; i++) {
 *         if (ToolInterrupter.isInterrupted()) {
 *             throw new ToolInterruptedException("Task cancelled by user");
 *         }
 *         // Process iteration
 *     }
 *     return result;
 * }
 * }</pre>
 */
public class ToolInterruptedException extends RuntimeException {

    /**
     * Constructs a new ToolInterruptedException with the specified detail message.
     *
     * @param message The detail message
     */
    public ToolInterruptedException(String message) {
        super(message);
    }

    /**
     * Constructs a new ToolInterruptedException with the specified detail message and cause.
     *
     * @param message The detail message
     * @param cause The cause
     */
    public ToolInterruptedException(String message, Throwable cause) {
        super(message, cause);
    }
}
