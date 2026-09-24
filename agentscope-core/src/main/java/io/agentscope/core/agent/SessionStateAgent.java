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

import io.agentscope.core.state.AgentState;

/**
 * Optional capability for agents that expose session-scoped {@link AgentState}.
 *
 * <p>Protocol adapters such as AG-UI detect this interface instead of concrete agent types, so
 * custom agents can participate in server-side memory without extending {@code ReActAgent} or
 * {@code HarnessAgent}.
 *
 * <p>This is orthogonal to {@link EventStreamingAgent}: an agent may stream events without
 * addressable session slots, or keep session state without emitting v2 events.
 */
public interface SessionStateAgent {

    /**
     * Returns the {@link AgentState} for the given {@code (userId, sessionId)} slot.
     *
     * @param userId user identity for the slot, may be {@code null} for anonymous / single-tenant
     * @param sessionId session identity, may be {@code null} to use the agent's default session
     * @return the agent state for the identified session, or {@code null} if none exists
     */
    AgentState getAgentState(String userId, String sessionId);

    /**
     * Returns the {@link AgentState} for the session identified by {@code ctx}.
     *
     * @param ctx the runtime context (uses {@code getUserId()} and {@code getSessionId()}), may be
     *     {@code null}
     * @return the agent state for the identified session, or {@code null} if none exists
     */
    default AgentState getAgentState(RuntimeContext ctx) {
        if (ctx == null) {
            return getAgentState(null, null);
        }
        return getAgentState(ctx.getUserId(), ctx.getSessionId());
    }
}
