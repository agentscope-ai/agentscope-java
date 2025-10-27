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
package io.agentscope.core.hook;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import reactor.core.publisher.Mono;

/**
 * Hook interface for monitoring and intercepting agent execution.
 *
 * <p>All callback methods return Mono to support async operations.
 * Pre-hooks can modify input data, post-hooks can modify output data.
 * The default implementation returns the original data unchanged.
 *
 * <p>This interface is designed to align with Python's hook system while
 * adding Java-specific enhancements like streaming support and error handling.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * agent.addHook(new Hook() {
 *     @Override
 *     public Mono<Msg> postReasoning(Agent agent, Msg msg) {
 *         // Extract text content from message
 *         String text = msg.getContent().stream()
 *             .filter(block -> block instanceof TextBlock)
 *             .map(block -> ((TextBlock) block).getText())
 *             .collect(Collectors.joining());
 *         System.out.println("Thinking: " + text);
 *         return Mono.just(msg);  // Return original or modified
 *     }
 *
 *     @Override
 *     public Mono<ToolUseBlock> preActing(Agent agent, ToolUseBlock toolUse) {
 *         System.out.println("Calling tool: " + toolUse.getName());
 *         // Can modify tool parameters here
 *         return Mono.just(toolUse);
 *     }
 * });
 * }</pre>
 */
public interface Hook {

    // ========================================================================
    // Agent Call Lifecycle
    // ========================================================================

    /**
     * Called before agent starts processing (before call() execution).
     * Notification-only, cannot modify data.
     *
     * <p>At this point, the input message(s) have already been added to memory.
     * This hook is a notification point before the reasoning-acting loop starts.
     *
     * <p>Use this hook to:
     * <ul>
     *   <li>Log the start of agent execution</li>
     *   <li>Initialize execution-specific resources</li>
     *   <li>Track agent invocation metrics</li>
     * </ul>
     *
     * <p><b>Python equivalent:</b> {@code pre_reply}
     *
     * @param agent The agent instance (use agent.getMemory() to access all messages)
     * @return Mono that completes when processing is done
     */
    default Mono<Void> preCall(Agent agent) {
        return Mono.empty();
    }

    /**
     * Called after agent completes processing (after call() execution).
     * Can modify the final message returned by the agent.
     *
     * <p>This is the final hook before returning the result to the caller.
     * The message passed here is the final response from the agent.
     *
     * <p>Use this hook to:
     * <ul>
     *   <li>Post-process the final agent response</li>
     *   <li>Add final metadata or formatting</li>
     *   <li>Filter or sanitize output</li>
     *   <li>Log outgoing responses</li>
     * </ul>
     *
     * <p><b>Python equivalent:</b> {@code post_reply}
     *
     * @param agent The agent instance
     * @param finalMsg The final message returned by the agent
     * @return Mono containing potentially modified message
     */
    default Mono<Msg> postCall(Agent agent, Msg finalMsg) {
        return Mono.just(finalMsg);
    }

    // ========================================================================
    // Reasoning Lifecycle
    // ========================================================================

    /**
     * Called before agent starts reasoning (before reasoning() execution).
     * Notification-only, no parameters.
     *
     * <p>The reasoning process internally retrieves messages from memory and
     * constructs the prompt. This hook cannot modify the input since reasoning()
     * has no parameters - it builds context internally from agent state.
     *
     * <p>Use this hook to:
     * <ul>
     *   <li>Inspect the current memory state via agent.getMemory()</li>
     *   <li>Log reasoning start events</li>
     *   <li>Initialize reasoning-specific resources</li>
     * </ul>
     *
     * <p><b>Python equivalent:</b> {@code pre_reasoning}
     * <br><b>Python method signature:</b> {@code async def _reasoning(self) -> Msg}
     *
     * @param agent The agent instance
     * @return Mono that completes when processing is done
     */
    default Mono<Void> preReasoning(Agent agent) {
        return Mono.empty();
    }

    /**
     * Called after agent completes reasoning (after reasoning() execution).
     * Can modify the reasoning result message.
     *
     * <p><b>IMPORTANT:</b> The message content is a list that may contain multiple blocks:
     * <ul>
     *   <li>Text blocks - regular text output</li>
     *   <li>Thinking blocks - model's reasoning process</li>
     *   <li>Multiple tool use blocks - if model returns multiple tool calls</li>
     * </ul>
     *
     * <p>You can modify this message, including:
     * <ul>
     *   <li>Filtering or modifying tool calls before execution</li>
     *   <li>Adding/removing content blocks</li>
     *   <li>Modifying text or thinking content</li>
     *   <li>Adding metadata</li>
     * </ul>
     *
     * <p>This is called for each complete reasoning message. For real-time streaming,
     * use {@link #onReasoningChunk(Agent, Msg)} instead.
     *
     * <p><b>Python equivalent:</b> {@code post_reasoning}
     * <br><b>Python method signature:</b> {@code async def _reasoning(self) -> Msg}
     *
     * @param agent The agent instance
     * @param reasoningMsg The reasoning result message (content is a list)
     * @return Mono containing potentially modified message
     */
    default Mono<Msg> postReasoning(Agent agent, Msg reasoningMsg) {
        return Mono.just(reasoningMsg);
    }

    /**
     * Specifies the mode for reasoning chunk callbacks.
     *
     * <p>This determines what content is passed to {@link #onReasoningChunk(Agent, Msg)}:
     * <ul>
     *   <li>{@link ChunkMode#INCREMENTAL}: Receives only new content chunks</li>
     *   <li>{@link ChunkMode#CUMULATIVE}: Receives accumulated content so far</li>
     * </ul>
     *
     * @return The reasoning chunk mode, defaults to INCREMENTAL
     */
    default ChunkMode reasoningChunkMode() {
        return ChunkMode.INCREMENTAL;
    }

    /**
     * Called when agent emits a reasoning chunk during streaming.
     * Notification-only, cannot modify the chunk.
     *
     * <p>The content of the message depends on {@link #reasoningChunkMode()}:
     * <ul>
     *   <li>{@link ChunkMode#INCREMENTAL}: msg contains only the new content chunk</li>
     *   <li>{@link ChunkMode#CUMULATIVE}: msg contains all accumulated content so far</li>
     * </ul>
     *
     * <p>Use this hook to:
     * <ul>
     *   <li>Display streaming output in real-time</li>
     *   <li>Monitor reasoning progress</li>
     *   <li>Log streaming content</li>
     * </ul>
     *
     * <p><b>Note:</b> Python does not have an equivalent streaming hook.
     * This is a Java-specific enhancement.
     *
     * @param agent The agent instance
     * @param chunkMsg The chunk message (read-only)
     * @return Mono that completes when processing is done
     */
    default Mono<Void> onReasoningChunk(Agent agent, Msg chunkMsg) {
        return Mono.empty();
    }

    // ========================================================================
    // Acting/Tool Lifecycle
    // ========================================================================

    /**
     * Called before agent executes a single tool call (before acting() execution).
     * Can modify the tool use block.
     *
     * <p><b>IMPORTANT - Per-Tool Invocation:</b> This hook is called <b>ONCE PER TOOL</b>.
     * If the reasoning result contains multiple tool calls, this hook will be invoked
     * multiple times, once for each tool call.
     *
     * <p>This matches Python's behavior where {@code _acting(tool_call)} is called
     * individually for each tool, either in parallel or sequentially depending on
     * the {@code parallel_tool_calls} setting.
     *
     * <p>Use this hook to:
     * <ul>
     *   <li>Validate or modify tool parameters for each tool call</li>
     *   <li>Add authentication or context to individual tool calls</li>
     *   <li>Implement per-tool authorization checks</li>
     *   <li>Log or monitor individual tool invocations</li>
     * </ul>
     *
     * <p><b>Python equivalent:</b> {@code pre_acting}
     * <br><b>Python method signature:</b> {@code async def _acting(self, tool_call: ToolUseBlock) -> Msg | None}
     *
     * @param agent The agent instance
     * @param toolUse The tool use block for this specific tool call
     * @return Mono containing potentially modified tool use block
     */
    default Mono<ToolUseBlock> preActing(Agent agent, ToolUseBlock toolUse) {
        return Mono.just(toolUse);
    }

    /**
     * Called after a single tool execution completes (after acting() execution).
     * Can modify the tool result.
     *
     * <p><b>IMPORTANT - Per-Tool Invocation:</b> This hook is called <b>ONCE PER TOOL</b>
     * execution. It receives the tool result for the single tool that was just executed.
     *
     * <p>This matches Python's behavior where {@code _acting()} processes one tool at
     * a time and post hooks are triggered for each individual tool execution.
     *
     * <p>Use this hook to:
     * <ul>
     *   <li>Post-process individual tool results</li>
     *   <li>Filter or sanitize tool output</li>
     *   <li>Add metadata to results</li>
     *   <li>Handle tool execution errors</li>
     *   <li>Transform result format</li>
     * </ul>
     *
     * <p><b>Python equivalent:</b> {@code post_acting}
     * <br><b>Python method signature:</b> {@code async def _acting(self, tool_call: ToolUseBlock) -> Msg | None}
     *
     * @param agent The agent instance
     * @param toolUse The tool use block identifying the tool call
     * @param toolResult The tool result block for this specific tool execution
     * @return Mono containing potentially modified tool result
     */
    default Mono<ToolResultBlock> postActing(
            Agent agent, ToolUseBlock toolUse, ToolResultBlock toolResult) {
        return Mono.just(toolResult);
    }

    /**
     * Called when a tool emits a streaming chunk during execution.
     * Notification-only, cannot modify the chunk.
     *
     * <p>This is called for intermediate messages emitted by tools via {@link
     * io.agentscope.core.tool.ToolEmitter}. These chunks are NOT sent to the LLM -
     * only the final return value of the tool method is sent to the LLM.
     *
     * <p>Use this hook to:
     * <ul>
     *   <li>Display tool execution progress</li>
     *   <li>Log intermediate tool outputs</li>
     *   <li>Monitor long-running tool operations</li>
     * </ul>
     *
     * <p><b>Note:</b> Python does not have an equivalent streaming hook.
     * This is a Java-specific enhancement.
     *
     * @param agent The agent instance
     * @param toolUse The tool use block identifying the tool call
     * @param chunk The streaming chunk emitted by the tool (read-only)
     * @return Mono that completes when processing is done
     */
    default Mono<Void> onActingChunk(Agent agent, ToolUseBlock toolUse, ToolResultBlock chunk) {
        return Mono.empty();
    }

    // ========================================================================
    // Error Handling
    // ========================================================================

    /**
     * Called when an error occurs during agent execution.
     * Cannot modify the error, only for notification.
     *
     * <p>Use this hook to:
     * <ul>
     *   <li>Log errors with context</li>
     *   <li>Send error notifications</li>
     *   <li>Collect error metrics</li>
     *   <li>Implement custom error handling</li>
     * </ul>
     *
     * <p><b>Note:</b> Python does not have an equivalent error hook.
     * This is a Java-specific enhancement.
     *
     * @param agent The agent instance
     * @param error The error
     * @return Mono that completes when processing is done
     */
    default Mono<Void> onError(Agent agent, Throwable error) {
        return Mono.empty();
    }
}
