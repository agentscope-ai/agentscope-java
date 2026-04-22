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
package io.agentscope.core.hook;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.ToolValidator;
import java.util.ArrayList;
import java.util.List;

/**
 * Event fired after LLM reasoning completes.
 *
 * <p><b>Modifiable:</b> Yes - {@link #setReasoningMessage(Msg)}
 *
 * <p><b>Context:</b>
 * <ul>
 *   <li>{@link #getAgent()} - The agent instance</li>
 *   <li>{@link #getMemory()} - Agent's memory</li>
 *   <li>{@link #getModelName()} - The model name</li>
 *   <li>{@link #getGenerateOptions()} - The generation options</li>
 *   <li>{@link #getReasoningMessage()} - The reasoning result (modifiable)</li>
 * </ul>
 *
 * <p><b>Note:</b> Message content may include text blocks, thinking blocks, and tool use blocks.
 * You can modify any of these before the agent processes tool calls.
 *
 * <p><b>Use Cases:</b>
 * <ul>
 *   <li>Filter or modify tool calls before execution</li>
 *   <li>Add/remove content blocks</li>
 *   <li>Modify text or thinking content</li>
 *   <li>Add metadata</li>
 *   <li>Request to stop the agent for human review via {@link #stopAgent()}</li>
 * </ul>
 */
public final class PostReasoningEvent extends ReasoningEvent {

    private Msg reasoningMessage;
    private boolean stopRequested = false;
    private List<Msg> gotoReasoningMsgs = null;

    /**
     * Constructor for PostReasoningEvent.
     *
     * @param agent The agent instance (must not be null)
     * @param modelName The model name (must not be null)
     * @param generateOptions The generation options (may be null)
     * @param reasoningMessage The reasoning result message (may be null)
     */
    public PostReasoningEvent(
            Agent agent, String modelName, GenerateOptions generateOptions, Msg reasoningMessage) {
        super(HookEventType.POST_REASONING, agent, modelName, generateOptions);
        this.reasoningMessage = reasoningMessage;
    }

    /**
     * Get the reasoning result message from LLM.
     *
     * @return The reasoning message, may be null
     */
    public Msg getReasoningMessage() {
        return reasoningMessage;
    }

    /**
     * Modify the reasoning result message.
     *
     * @param reasoningMessage The new reasoning message
     */
    public void setReasoningMessage(Msg reasoningMessage) {
        this.reasoningMessage = reasoningMessage;
    }

    /**
     * Request to stop the agent after this reasoning phase.
     *
     * <p>When called, the agent will return the current message containing ToolUseBlocks
     * instead of proceeding to execute the tools. The user can then review the pending
     * tool calls and resume execution by calling {@code agent.call()} with no arguments.
     *
     * <p>This enables human-in-the-loop scenarios where sensitive or important tool
     * calls need user confirmation before execution.
     */
    public void stopAgent() {
        this.stopRequested = true;
    }

    /**
     * Check if a stop has been requested.
     *
     * @return true if {@link #stopAgent()} has been called, false otherwise
     */
    public boolean isStopRequested() {
        return stopRequested;
    }

    /**
     * Request to go back to reasoning phase without adding any messages.
     *
     * <p>This is only valid when there are no pending ToolUse blocks in the reasoning message.
     * If there are pending ToolUse blocks, an {@link IllegalStateException} will be thrown
     * because ToolResult messages must be provided first.
     *
     * @throws IllegalStateException if there are pending ToolUse blocks
     */
    public void gotoReasoning() {
        gotoReasoning(List.of());
    }

    /**
     * Request to go back to reasoning phase with a single message.
     *
     * <p>If the reasoning message contains ToolUse blocks, the provided message must contain
     * matching ToolResult blocks. Additional messages (like hints or prompts) can also be included.
     *
     * @param msg The message to add to memory before reasoning (e.g., ToolResult or hint message)
     * @throws IllegalStateException if ToolResult validation fails
     */
    public void gotoReasoning(Msg msg) {
        gotoReasoning(List.of(msg));
    }

    /**
     * Request to go back to reasoning phase with multiple messages.
     *
     * <p>Validation rules:
     * <ul>
     *   <li>If no pending ToolUse: messages are added as-is (can be hints/prompts)</li>
     *   <li>If pending ToolUse: messages must contain matching ToolResult blocks</li>
     * </ul>
     *
     * @param msgs The messages to add to memory before reasoning
     * @throws IllegalStateException if ToolResult validation fails
     */
    public void gotoReasoning(List<Msg> msgs) {
        ToolValidator.validateToolResultMatch(reasoningMessage, msgs);
        this.gotoReasoningMsgs = new ArrayList<>(msgs);
    }

    /**
     * Check if a goto reasoning has been requested.
     *
     * @return true if any gotoReasoning method has been called, false otherwise
     */
    public boolean isGotoReasoningRequested() {
        return gotoReasoningMsgs != null;
    }

    /**
     * Get the messages to add before going back to reasoning.
     *
     * @return The messages to add, or null if gotoReasoning was not called
     */
    public List<Msg> getGotoReasoningMsgs() {
        return gotoReasoningMsgs;
    }
}
