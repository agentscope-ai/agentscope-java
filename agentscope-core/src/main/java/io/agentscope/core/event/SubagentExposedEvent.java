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
package io.agentscope.core.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Emitted when a spawned subagent is exposed as a user-addressable entry point via
 * {@code agent_spawn(expose_to_user=true)}. SSE/streaming consumers use this event to
 * render a new conversation entry point in the client UI.
 */
public class SubagentExposedEvent extends AgentEvent {

    private final String subagentId;
    private final String agentId;
    private final String sessionId;
    private final String label;

    @JsonCreator
    public SubagentExposedEvent(
            @JsonProperty("id") String id,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("subagentId") String subagentId,
            @JsonProperty("agentId") String agentId,
            @JsonProperty("sessionId") String sessionId,
            @JsonProperty("label") String label) {
        super(id, createdAt);
        this.subagentId = subagentId;
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.label = label;
    }

    public SubagentExposedEvent(String subagentId, String agentId, String sessionId, String label) {
        this.subagentId = subagentId;
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.label = label;
    }

    @Override
    public AgentEventType getType() {
        return AgentEventType.SUBAGENT_EXPOSED;
    }

    public String getSubagentId() {
        return subagentId;
    }

    public String getAgentId() {
        return agentId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getLabel() {
        return label;
    }
}
