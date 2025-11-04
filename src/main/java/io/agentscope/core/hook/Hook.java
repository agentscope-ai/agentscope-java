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
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Hook interface for monitoring and intercepting agent execution.
 *
 * <p>All callback methods return Mono to support async operations.
 * Pre-hooks can modify input data, post-hooks can modify output data.
 * The default implementation returns the original data unchanged.
 *
 * <p>This interface provides comprehensive monitoring capabilities including
 * streaming support and error handling.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * agent.addHook(new Hook() {
 *     @Override
 *     public Mono<Msg> postReasoning(Agent agent, Msg msg) {
 *         // Log or process the reasoning result
 *         System.out.println("Agent reasoning completed");
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
     * Can modify the messages that will be sent to the LLM.
     *
     * <p>This hook receives the messages that are about to be sent to the LLM for reasoning.
     * You can modify this list to inject additional context, hints, or instructions.
     *
     * <p>Use this hook to:
     * <ul>
     *   <li>Inject hints or additional context into the prompt</li>
     *   <li>Filter or modify existing messages</li>
     *   <li>Add system instructions dynamically</li>
     *   <li>Log reasoning input</li>
     * </ul>
     *
     * <p><b>IMPORTANT:</b> The returned message list will be used as-is for LLM reasoning.
     * Ensure the list is properly formatted and contains valid messages.
     *
     * @param agent The agent instance
     * @param msgs The messages about to be sent to LLM (can be modified)
     * @return Mono containing potentially modified message list
     */
    default Mono<List<Msg>> preReasoning(Agent agent, List<Msg> msgs) {
        return Mono.just(msgs);
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
     * <p><b>Note:</b> Supports streaming with configurable chunk modes.
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
     * <p>This hook is called individually for each tool, allowing parallel or sequential
     * execution depending on the tool configuration.
     *
     * <p>Use this hook to:
     * <ul>
     *   <li>Validate or modify tool parameters for each tool call</li>
     *   <li>Add authentication or context to individual tool calls</li>
     *   <li>Implement per-tool authorization checks</li>
     *   <li>Log or monitor individual tool invocations</li>
     * </ul>
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
     * <p>This hook processes one tool at a time and is triggered for each individual
     * tool execution.
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
     * <p><b>Note:</b> Enables real-time progress monitoring for tool execution.
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
     * <p><b>Note:</b> Provides centralized error monitoring and handling.
     *
     * @param agent The agent instance
     * @param error The error
     * @return Mono that completes when processing is done
     */
    default Mono<Void> onError(Agent agent, Throwable error) {
        return Mono.empty();
    }
}
