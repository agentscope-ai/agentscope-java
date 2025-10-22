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
 * <p>All callback methods return Mono to support async operations. Methods can modify the data
 * being processed by returning a modified version. The default implementation returns the original
 * data unchanged.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * agent.addHook(new Hook() {
 *     @Override
 *     public Mono<Msg> onReasoning(Agent agent, Msg msg) {
 *         System.out.println("Thinking: " + msg.getContent());
 *         return Mono.just(msg);  // Return original or modified
 *     }
 *
 *     @Override
 *     public Mono<ToolUseBlock> onToolCall(Agent agent, ToolUseBlock toolUse) {
 *         System.out.println("Calling tool: " + toolUse.getName());
 *         // Can modify tool parameters here
 *         return Mono.just(toolUse);
 *     }
 * });
 * }</pre>
 */
public interface Hook {

    /**
     * Called before agent starts processing. Hook can access messages from agent.getMemory().
     *
     * @param agent The agent instance
     * @return Mono that completes when processing is done
     */
    default Mono<Void> onStart(Agent agent) {
        return Mono.empty();
    }

    /**
     * Called when agent emits a reasoning message (text/thinking). Can modify the reasoning
     * message.
     *
     * <p>Note: This is called for each complete message. For real-time streaming chunks,
     * use {@link #onReasoningChunk(Agent, Msg)} instead.
     *
     * @param agent The agent instance
     * @param msg The message emitted during reasoning
     * @return Mono containing potentially modified message
     */
    default Mono<Msg> onReasoning(Agent agent, Msg msg) {
        return Mono.just(msg);
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
     * Called when agent emits a reasoning chunk during streaming. This is called for each
     * chunk as it arrives from the model, allowing real-time streaming display.
     *
     * <p>The content of the message depends on the mode returned by {@link #reasoningChunkMode()}:
     * <ul>
     *   <li>{@link ChunkMode#INCREMENTAL}: msg contains only the new content chunk</li>
     *   <li>{@link ChunkMode#CUMULATIVE}: msg contains all accumulated content so far</li>
     * </ul>
     *
     * @param agent The agent instance
     * @param msg The chunk message (incremental) or accumulated message (cumulative)
     * @return Mono containing potentially modified message
     */
    default Mono<Msg> onReasoningChunk(Agent agent, Msg msg) {
        return Mono.just(msg);
    }

    /**
     * Called when agent makes a tool call. Can modify the tool use block.
     *
     * @param agent The agent instance
     * @param toolUse The tool use block
     * @return Mono containing potentially modified tool use block
     */
    default Mono<ToolUseBlock> onToolCall(Agent agent, ToolUseBlock toolUse) {
        return Mono.just(toolUse);
    }

    /**
     * Called when a tool emits a streaming chunk during execution.
     *
     * <p>This is called for intermediate messages emitted by tools via {@link
     * io.agentscope.core.tool.ToolEmitter}. These chunks are NOT sent to the LLM - only the final
     * return value of the tool method is sent to the LLM.
     *
     * <p>This callback is purely observational and cannot modify the chunk data.
     *
     * @param agent The agent instance
     * @param toolUse The tool use block identifying the tool call
     * @param chunk The streaming chunk emitted by the tool
     * @return Mono that completes when processing is done
     */
    default Mono<Void> onToolChunk(Agent agent, ToolUseBlock toolUse, ToolResultBlock chunk) {
        return Mono.empty();
    }

    /**
     * Called when tool execution completes. Can modify the tool result.
     *
     * @param agent The agent instance
     * @param toolUse The tool use block identifying the tool call
     * @param toolResult The tool result block
     * @return Mono containing potentially modified tool result
     */
    default Mono<ToolResultBlock> onToolResult(
            Agent agent, ToolUseBlock toolUse, ToolResultBlock toolResult) {
        return Mono.just(toolResult);
    }

    /**
     * Called when agent completes processing. Can modify the final message.
     *
     * @param agent The agent instance
     * @param finalMsg The final aggregated message
     * @return Mono containing potentially modified final message
     */
    default Mono<Msg> onComplete(Agent agent, Msg finalMsg) {
        return Mono.just(finalMsg);
    }

    /**
     * Called when an error occurs. Cannot modify the error, only for notification.
     *
     * @param agent The agent instance
     * @param error The error
     * @return Mono that completes when processing is done
     */
    default Mono<Void> onError(Agent agent, Throwable error) {
        return Mono.empty();
    }
}
