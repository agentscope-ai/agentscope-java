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
package io.agentscope.core.interruption;

import io.agentscope.core.exception.ToolInterruptedException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ThreadLocal-based utility for detecting tool interruption triggered by agent.interrupt().
 *
 * <p>This class allows tool implementations to detect when the agent has been interrupted
 * externally (via {@code agent.interrupt()}) and gracefully terminate their execution.
 * It uses ThreadLocal storage to maintain interrupt state per thread.
 *
 * <p><b>IMPORTANT:</b> This class is primarily for internal framework use. The setup and
 * cleanup methods ({@link #set(AtomicBoolean)} and {@link #clear()}) are called automatically
 * by the toolkit during tool execution. Tool implementations should only use
 * {@link #isInterrupted()} or {@link #checkInterrupted()}.
 *
 * <p>Example usage in tool implementation:
 * <pre>{@code
 * @Tool(name = "long_task", description = "Execute a long running task")
 * public String longTask(@ToolParam(name = "input") String input) {
 *     for (int i = 0; i < iterations; i++) {
 *         // Check if agent was interrupted externally
 *         ToolInterrupter.checkInterrupted();
 *
 *         // Process iteration
 *     }
 *     return result;
 * }
 * }</pre>
 *
 * <p>When {@code agent.interrupt()} is called, the interrupt flag is set, and
 * {@link #checkInterrupted()} will throw {@link ToolInterruptedException}, causing
 * the tool to return an interrupted result.
 */
public class ToolInterrupter {

    private static final ThreadLocal<AtomicBoolean> INTERRUPT_FLAG = new ThreadLocal<>();

    /**
     * Private constructor to prevent instantiation.
     */
    private ToolInterrupter() {}

    /**
     * Set the interrupt flag for the current thread.
     * This is typically called by the framework before tool execution.
     *
     * @param interruptFlag The atomic boolean flag to use for interrupt checking
     */
    public static void set(AtomicBoolean interruptFlag) {
        INTERRUPT_FLAG.set(interruptFlag);
    }

    /**
     * Clear the interrupt flag for the current thread.
     * <b>MUST be called in a finally block after tool execution to prevent memory leaks.</b>
     */
    public static void clear() {
        INTERRUPT_FLAG.remove();
    }

    /**
     * Check if the current tool execution should be interrupted.
     *
     * @return true if interrupted, false otherwise
     */
    public static boolean isInterrupted() {
        AtomicBoolean flag = INTERRUPT_FLAG.get();
        return flag != null && flag.get();
    }

    /**
     * Check if interrupted and throw ToolInterruptedException if so.
     * This is a convenience method for tools that want to automatically throw
     * when interrupted.
     *
     * @throws ToolInterruptedException if the current execution is interrupted
     */
    public static void checkInterrupted() {
        if (isInterrupted()) {
            throw new ToolInterruptedException("Tool execution interrupted");
        }
    }
}
