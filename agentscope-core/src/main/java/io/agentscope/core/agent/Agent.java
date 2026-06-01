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
 *
 * <p><b>Reply contract:</b> a single {@code call(...)} invocation produces exactly one
 * terminal {@link Msg}. Streaming variants (see {@link StreamableAgent}) may emit
 * many events but resolve to a single terminal Msg. This is enforced by the
 * {@code Mono<Msg>} return type on the call methods.
 *
 * <h3>Python 2.0 alignment</h3>
 * <p>This interface corresponds to Python's {@code agentscope.agent.Agent} class.
 * Method mapping:
 * <ul>
 *   <li>{@code Agent.reply()} &rarr; {@code CallableAgent#call(List)}</li>
 *   <li>{@code Agent.reply_stream()} &rarr; {@code io.agentscope.core.ReActAgent#streamEvents(List)}</li>
 *   <li>{@code Agent.observe()} &rarr; {@link ObservableAgent#observe(Msg)}</li>
 *   <li>{@code Agent.compress_context()} &rarr; {@code io.agentscope.core.ReActAgent#compressContext()}</li>
 * </ul>
 * <p>Constructor mapping (Python &rarr; Java {@link io.agentscope.core.ReActAgent.Builder}):
 * <ul>
 *   <li>{@code name} &rarr; {@code name()}</li>
 *   <li>{@code system_prompt} &rarr; {@code sysPrompt()}</li>
 *   <li>{@code model} &rarr; {@code model()}</li>
 *   <li>{@code toolkit} &rarr; {@code toolkit()}</li>
 *   <li>{@code middlewares} &rarr; {@code middlewares()}</li>
 *   <li>{@code state} &rarr; {@code agentState()}</li>
 *   <li>{@code model_config} &rarr; {@code modelConfig()}</li>
 *   <li>{@code context_config} &rarr; {@code contextConfig()}</li>
 *   <li>{@code react_config} &rarr; {@code reactConfig()}</li>
 * </ul>
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

    /**
     * Returns the agent's runtime {@link io.agentscope.core.state.AgentState}, or {@code null} if
     * this agent type does not maintain one.
     *
     * <p>This is the canonical access point used by tool methods declared with
     * {@code @Tool(stateInjected=true)}: the framework binds the live state to the
     * {@code AgentState} parameter at invocation time.
     */
    default io.agentscope.core.state.AgentState getAgentState() {
        return null;
    }
}
