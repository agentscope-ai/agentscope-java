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
package io.agentscope.core.agent;

/**
 * Types of events emitted during agent execution.
 *
 * <p>Each event type represents a specific stage in the agent's reasoning-acting loop.
 * Events provide a clear separation of concerns for monitoring agent behavior.
 */
public enum EventType {
    /**
     * Reasoning event - Agent thinking and planning.
     *
     * <p>Characteristics:
     * <ul>
     *   <li>Message role: {@link io.agentscope.core.message.MsgRole#ASSISTANT}</li>
     *   <li>Content: TextBlock, ThinkingBlock, and/or tool call requests</li>
     *   <li>Streaming: Supported (multiple events with same message ID)</li>
     * </ul>
     */
    REASONING,

    /**
     * Tool execution result event.
     *
     * <p>Characteristics:
     * <ul>
     *   <li>Message role: {@link io.agentscope.core.message.MsgRole#TOOL}</li>
     *   <li>Content: ToolResultBlock with execution output</li>
     *   <li>Streaming: Supported for long-running tools</li>
     * </ul>
     */
    TOOL_RESULT,

    /**
     * Hint event - Information from RAG, memory, or planning systems.
     *
     * <p>Characteristics:
     * <ul>
     *   <li>Message role: {@link io.agentscope.core.message.MsgRole#USER} or SYSTEM</li>
     *   <li>Content: TextBlock with retrieved or contextual information</li>
     *   <li>Streaming: Not applicable (complete messages only)</li>
     * </ul>
     */
    HINT,

    /**
     * Final result event - The agent's complete response.
     *
     * <p>This is the message returned by {@link Agent#call(io.agentscope.core.message.Msg)}.
     * By default, this event is NOT included in the stream to avoid duplication since it's the return value.
     *
     * <p>Characteristics:
     * <ul>
     *   <li>Message role: {@link io.agentscope.core.message.MsgRole#ASSISTANT}</li>
     *   <li>Content: Final response text</li>
     *   <li>Streaming: Not applicable</li>
     * </ul>
     */
    AGENT_RESULT,

    /**
     * Summary event - Generated when max iterations reached.
     *
     * <p>Characteristics:
     * <ul>
     *   <li>Message role: {@link io.agentscope.core.message.MsgRole#ASSISTANT}</li>
     *   <li>Content: Summary of what was accomplished</li>
     *   <li>Streaming: May support streaming</li>
     * </ul>
     */
    SUMMARY,

    /**
     * Special value to stream all event types (except {@link #AGENT_RESULT}).
     *
     * <p>Use this in {@link StreamOptions} to receive all events without filtering.
     */
    ALL
}
