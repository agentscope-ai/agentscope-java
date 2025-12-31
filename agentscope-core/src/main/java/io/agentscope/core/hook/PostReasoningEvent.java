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
import java.util.Objects;

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
 * </ul>
 */
public final class PostReasoningEvent extends ReasoningEvent {

    private Msg reasoningMessage;

    /**
     * Constructor for PostReasoningEvent.
     *
     * @param agent The agent instance (must not be null)
     * @param modelName The model name (must not be null)
     * @param generateOptions The generation options (may be null)
     * @param reasoningMessage The reasoning result message (must not be null)
     * @throws NullPointerException if agent, modelName, or reasoningMessage is null
     */
    public PostReasoningEvent(
            Agent agent, String modelName, GenerateOptions generateOptions, Msg reasoningMessage) {
        super(HookEventType.POST_REASONING, agent, modelName, generateOptions);
        this.reasoningMessage =
                Objects.requireNonNull(reasoningMessage, "reasoningMessage cannot be null");
    }

    /**
     * Get the reasoning result message from LLM.
     *
     * @return The reasoning message
     */
    public Msg getReasoningMessage() {
        return reasoningMessage;
    }

    /**
     * Modify the reasoning result message.
     *
     * @param reasoningMessage The new reasoning message (must not be null)
     * @throws NullPointerException if reasoningMessage is null
     */
    public void setReasoningMessage(Msg reasoningMessage) {
        this.reasoningMessage =
                Objects.requireNonNull(reasoningMessage, "reasoningMessage cannot be null");
    }
}
