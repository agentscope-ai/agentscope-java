/*
 * Copyright 2024-2026 the original author or authors.
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
package io.agentscope.core.state;

/**
 * State record for agent metadata.
 *
 * <p>This record captures the essential metadata of an agent for persistence. It is used by {@link
 * io.agentscope.core.ReActAgent} to save and restore agent configuration across sessions.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * AgentMetaState state = new AgentMetaState("agent_001", "Assistant", "A helpful assistant", "You are a helpful assistant.");
 * session.save(sessionKey, "agent_meta", state);
 *
 * // Later, restore the state
 * Optional<AgentMetaState> loaded = session.get(sessionKey, "agent_meta", AgentMetaState.class);
 * }</pre>
 *
 * @param id the unique identifier of the agent
 * @param name the display name of the agent
 * @param description a brief description of the agent's purpose
 * @param systemPrompt the system prompt used to configure agent behavior
 * @see State
 * @see StateModule
 */
public record AgentMetaState(String id, String name, String description, String systemPrompt)
        implements State {}
