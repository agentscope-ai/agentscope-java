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
import java.util.Objects;

/**
 * Event fired after agent completes processing.
 *
 * <p><b>Modifiable:</b> Yes - {@link #setFinalMessage(Msg)}
 *
 * <p><b>Context:</b>
 * <ul>
 *   <li>{@link #getAgent()} - The agent instance</li>
 *   <li>{@link #getMemory()} - Agent's memory (includes final message)</li>
 *   <li>{@link #getFinalMessage()} - The final response message (modifiable)</li>
 * </ul>
 *
 * <p><b>Use Cases:</b>
 * <ul>
 *   <li>Post-process the final agent response</li>
 *   <li>Add final metadata or formatting</li>
 *   <li>Filter or sanitize output</li>
 *   <li>Log outgoing responses</li>
 * </ul>
 */
public final class PostCallEvent extends HookEvent {

    private Msg finalMessage;

    /**
     * Constructor for PostCallEvent.
     *
     * @param agent The agent instance (must not be null)
     * @param finalMessage The final response message (must not be null)
     * @throws NullPointerException if agent or finalMessage is null
     */
    public PostCallEvent(Agent agent, Msg finalMessage) {
        super(HookEventType.POST_CALL, agent);
        this.finalMessage = Objects.requireNonNull(finalMessage, "finalMessage cannot be null");
    }

    /**
     * Get the final response message returned by the agent.
     *
     * @return The final message
     */
    public Msg getFinalMessage() {
        return finalMessage;
    }

    /**
     * Modify the final response message.
     *
     * @param finalMessage The new final message (must not be null)
     * @throws NullPointerException if finalMessage is null
     */
    public void setFinalMessage(Msg finalMessage) {
        this.finalMessage = Objects.requireNonNull(finalMessage, "finalMessage cannot be null");
    }
}
