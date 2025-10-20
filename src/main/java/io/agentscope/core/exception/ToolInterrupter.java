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

import java.time.Instant;

/**
 * Utility class for tools to signal interruption.
 *
 * <p>This class provides convenience methods for tool developers to interrupt
 * tool execution. It uses ThreadLocal to track interruption state, ensuring
 * that interruptions are detected even if the exception is caught by user code.
 *
 * <p><b>Important:</b> Even if you catch {@link ToolInterruptedException},
 * the tool result will still be marked as interrupted by the framework.
 *
 * <p>Example usage:
 * <pre>{@code
 * @Tool(description = "Check user permission")
 * public String checkPermission(
 *         @ToolParam(name = "action", description = "Action to check") String action) {
 *     if (!hasPermission(action)) {
 *         ToolInterrupter.interrupt("Permission denied for action: " + action);
 *     }
 *     return "Permission granted";
 * }
 * }</pre>
 */
public final class ToolInterrupter {

    private ToolInterrupter() {
        // Utility class, prevent instantiation
    }

    /**
     * ThreadLocal to track interruption state for each thread.
     * This ensures thread safety and allows detection even if exception is caught.
     */
    private static final ThreadLocal<InterruptionState> interruptionState =
            ThreadLocal.withInitial(() -> null);

    /**
     * Interrupt the current tool execution with a custom message.
     *
     * <p>This will mark the thread as interrupted and throw {@link ToolInterruptedException}.
     * The interruption state is tracked using ThreadLocal, so the framework can detect
     * the interruption even if you catch the exception.
     *
     * <p><b>Important:</b> Do not catch this exception unless you have a specific reason.
     * Even if caught, the tool result will be marked as interrupted.
     *
     * @param message the reason for interruption
     * @throws ToolInterruptedException always thrown to signal interruption
     */
    public static void interrupt(String message) {
        // Mark interruption state in ThreadLocal
        interruptionState.set(new InterruptionState(message, Instant.now()));

        // Throw exception to interrupt execution flow
        throw new ToolInterruptedException(message, InterruptSource.TOOL);
    }

    /**
     * Interrupt the current tool execution with a default message.
     *
     * @throws ToolInterruptedException always thrown to signal interruption
     */
    public static void interrupt() {
        interrupt("Tool execution interrupted");
    }

    /**
     * Check if the current thread has been interrupted by a tool.
     *
     * <p>This method is used internally by the framework to detect interruptions
     * even when the exception has been caught by user code.
     *
     * @return true if the current thread is marked as interrupted
     */
    public static boolean isInterrupted() {
        return interruptionState.get() != null;
    }

    /**
     * Get the interruption state for the current thread.
     *
     * <p>This is used internally by the framework for logging and debugging.
     *
     * @return the interruption state, or null if not interrupted
     */
    public static InterruptionState getState() {
        return interruptionState.get();
    }

    /**
     * Reset the interruption state for the current thread.
     *
     * <p>This is called by the framework before and after tool execution
     * to ensure clean state and prevent ThreadLocal memory leaks.
     */
    public static void reset() {
        interruptionState.remove();
    }

    /**
     * Internal class to store interruption state.
     */
    public static class InterruptionState {
        public final String message;
        public final Instant timestamp;

        InterruptionState(String message, Instant timestamp) {
            this.message = message;
            this.timestamp = timestamp;
        }
    }
}
