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

import io.agentscope.core.message.ToolResultBlock;

/**
 * Interface for emitting streaming responses during tool execution.
 *
 * <p>Tool methods can declare a ToolEmitter parameter to send intermediate progress updates and
 * messages during execution. These streaming chunks are delivered to registered hooks via
 * {@code onActingChunk()} events but are NOT sent to the LLM. Only the final return value of the
 * tool method is sent to the LLM as the tool result.
 *
 * <p><b>Key Characteristics:</b>
 * <ul>
 *   <li>ToolEmitter is auto-injected by the framework - no {@link ToolParam} annotation needed</li>
 *   <li>Emitted chunks go to hooks (for monitoring/logging), not to the LLM</li>
 *   <li>Useful for long-running tools to provide progress feedback</li>
 *   <li>Does not affect the tool schema visible to the LLM</li>
 * </ul>
 *
 * <p><b>Usage Example:</b>
 *
 * <pre>{@code
 * @Tool(name = "long_task", description = "Execute a long running task")
 * public ToolResultBlock execute(
 *     @ToolParam(name = "input") String input,
 *     ToolEmitter emitter  // Automatically injected by framework
 * ) {
 *     emitter.emit(ToolResultBlock.text("Starting task..."));
 *
 *     // Step 1
 *     processStep1(input);
 *     emitter.emit(ToolResultBlock.text("Step 1 completed"));
 *
 *     // Step 2
 *     processStep2(input);
 *     emitter.emit(ToolResultBlock.text("Step 2 completed"));
 *
 *     // Final result (this is what the LLM sees)
 *     return ToolResultBlock.text("Task completed successfully");
 * }
 * }</pre>
 */
public interface ToolEmitter {

    /**
     * Emit a streaming response chunk during tool execution.
     *
     * <p>This method sends intermediate messages to registered hooks via {@code onToolChunk()}.
     * The emitted chunks do NOT affect what the LLM receives - only the tool method's return value
     * is sent to the LLM.
     *
     * @param chunk The chunk to emit
     */
    void emit(ToolResultBlock chunk);
}
