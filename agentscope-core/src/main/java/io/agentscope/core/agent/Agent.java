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

import io.agentscope.core.message.Msg;

/**
 * Complete agent interface combining all capabilities.
 *
 * <p>This interface defines the core contract for agents, combining:
 * <ul>
 *   <li>{@link CallableAgent} - Process messages and generate responses</li>
 *   <li>{@link StreamableAgent} - Stream events during execution</li>
 *   <li>{@link ObservableAgent} - Observe messages without responding</li>
 * </ul>
 *
 * <p>Design Philosophy:
 * <ul>
 *   <li>Memory management is NOT part of the core Agent interface - it's the responsibility
 *       of specific agent implementations (e.g., ReActAgent)</li>
 *   <li>Structured output is a specialized capability provided by specific agents</li>
 *   <li>Observe pattern allows agents to receive messages without generating a reply,
 *       enabling multi-agent collaboration</li>
 * </ul>
 *
 * <p>All agents in the AgentScope framework should implement this interface.
 */
public interface Agent extends CallableAgent, StreamableAgent, ObservableAgent {

    /**
     * Get the unique identifier for this agent.
     *
     * @return Agent ID
     */
    String getAgentId();

    /**
     * Get the name of this agent.
     *
     * @return Agent name
     */
    String getName();

    /**
     * Get the description of this agent.
     *
     * @return Agent description
     */
    default String getDescription() {
        return "Agent(" + getAgentId() + ") " + getName();
    }

    /**
     * Interrupt the current agent execution.
     * This method sets an interrupt flag that will be checked by the agent at appropriate
     * checkpoints during execution. The interruption is cooperative and may not take effect
     * immediately.
     */
    void interrupt();

    /**
     * Interrupt the current agent execution with a user message.
     * This method sets an interrupt flag and associates a user message with the interruption.
     * The interruption is cooperative and may not take effect immediately.
     *
     * @param msg User message associated with the interruption
     */
    void interrupt(Msg msg);
}
