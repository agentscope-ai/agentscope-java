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
import java.util.Map;

/**
 * Generic extensible event for signals that don't fit a specific {@link AgentEvent} subtype.
 *
 * <p>Used by service-layer middleware to notify front-end subscribers about state changes (task
 * progress, team membership, permission updates, etc.) without polluting the core agent event enum
 * with application-specific types.
 *
 * <p>Front-end implementations should handle unknown {@link #getName()} values gracefully — skip
 * with no error.
 *
 * <p>Well-known {@code name} values:
 * <ul>
 *   <li>{@code "state_updated"} — agent state (tasks / permission) changed during a tool call</li>
 *   <li>{@code "team_updated"} — team membership changed (member added / team created or
 *   dissolved)</li>
 * </ul>
 */
public class CustomEvent extends AgentEvent {

    private final String name;
    private final Map<String, Object> value;

    @JsonCreator
    public CustomEvent(
            @JsonProperty("id") String id,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("name") String name,
            @JsonProperty("value") Map<String, Object> value) {
        super(id, createdAt);
        this.name = name;
        this.value = value != null ? value : Map.of();
    }

    public CustomEvent(String name, Map<String, Object> value) {
        this.name = name;
        this.value = value != null ? value : Map.of();
    }

    public CustomEvent(String name) {
        this(name, Map.of());
    }

    @Override
    public AgentEventType getType() {
        return AgentEventType.CUSTOM;
    }

    /**
     * Returns the kind of notification. See class javadoc for well-known values.
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the arbitrary JSON-serializable payload whose schema depends on {@link #getName()}.
     */
    public Map<String, Object> getValue() {
        return value;
    }
}
