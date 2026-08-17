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
 * Emitted when an agent begins processing an invocation.
 */
public class AgentStartEvent extends AgentEvent {

    private final String sessionId;
    private final String replyId;
    private final String name;
    private final String role;

    @JsonCreator
    public AgentStartEvent(
            @JsonProperty("id") String id,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("sessionId") String sessionId,
            @JsonProperty("replyId") String replyId,
            @JsonProperty("name") String name,
            @JsonProperty("role") String role) {
        super(id, createdAt);
        this.sessionId = sessionId;
        this.replyId = replyId;
        this.name = name;
        this.role = role != null ? role : "assistant";
    }

    public AgentStartEvent(String sessionId, String replyId, String name) {
        this.sessionId = sessionId;
        this.replyId = replyId;
        this.name = name;
        this.role = "assistant";
    }

    @Override
    public AgentEventType getType() {
        return AgentEventType.AGENT_START;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getReplyId() {
        return replyId;
    }

    public String getName() {
        return name;
    }

    public String getRole() {
        return role;
    }
}
