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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Base class for all fine-grained agent events.
 *
 * <p>Each event carries a unique ID, creation timestamp, and type discriminator.
 * Events are emitted during agent execution and can be consumed via reactive streams.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = AgentStartEvent.class, name = "AGENT_START"),
    @JsonSubTypes.Type(value = AgentEndEvent.class, name = "AGENT_END"),
    @JsonSubTypes.Type(value = AgentResultEvent.class, name = "AGENT_RESULT"),
    @JsonSubTypes.Type(value = ModelCallStartEvent.class, name = "MODEL_CALL_START"),
    @JsonSubTypes.Type(value = ModelCallEndEvent.class, name = "MODEL_CALL_END"),
    @JsonSubTypes.Type(value = TextBlockStartEvent.class, name = "TEXT_BLOCK_START"),
    @JsonSubTypes.Type(value = TextBlockDeltaEvent.class, name = "TEXT_BLOCK_DELTA"),
    @JsonSubTypes.Type(value = TextBlockEndEvent.class, name = "TEXT_BLOCK_END"),
    @JsonSubTypes.Type(value = ThinkingBlockStartEvent.class, name = "THINKING_BLOCK_START"),
    @JsonSubTypes.Type(value = ThinkingBlockDeltaEvent.class, name = "THINKING_BLOCK_DELTA"),
    @JsonSubTypes.Type(value = ThinkingBlockEndEvent.class, name = "THINKING_BLOCK_END"),
    @JsonSubTypes.Type(value = DataBlockStartEvent.class, name = "DATA_BLOCK_START"),
    @JsonSubTypes.Type(value = DataBlockDeltaEvent.class, name = "DATA_BLOCK_DELTA"),
    @JsonSubTypes.Type(value = DataBlockEndEvent.class, name = "DATA_BLOCK_END"),
    @JsonSubTypes.Type(value = ToolCallStartEvent.class, name = "TOOL_CALL_START"),
    @JsonSubTypes.Type(value = ToolCallDeltaEvent.class, name = "TOOL_CALL_DELTA"),
    @JsonSubTypes.Type(value = ToolCallEndEvent.class, name = "TOOL_CALL_END"),
    @JsonSubTypes.Type(value = ToolResultStartEvent.class, name = "TOOL_RESULT_START"),
    @JsonSubTypes.Type(value = ToolResultTextDeltaEvent.class, name = "TOOL_RESULT_TEXT_DELTA"),
    @JsonSubTypes.Type(value = ToolResultDataDeltaEvent.class, name = "TOOL_RESULT_DATA_DELTA"),
    @JsonSubTypes.Type(value = ToolResultEndEvent.class, name = "TOOL_RESULT_END"),
    @JsonSubTypes.Type(value = ExceedMaxItersEvent.class, name = "EXCEED_MAX_ITERS"),
    @JsonSubTypes.Type(value = RequireUserConfirmEvent.class, name = "REQUIRE_USER_CONFIRM"),
    @JsonSubTypes.Type(
            value = RequireExternalExecutionEvent.class,
            name = "REQUIRE_EXTERNAL_EXECUTION"),
    @JsonSubTypes.Type(value = UserConfirmResultEvent.class, name = "USER_CONFIRM_RESULT"),
    @JsonSubTypes.Type(
            value = ExternalExecutionResultEvent.class,
            name = "EXTERNAL_EXECUTION_RESULT"),
    @JsonSubTypes.Type(value = RequestStopEvent.class, name = "REQUEST_STOP"),
    @JsonSubTypes.Type(value = SubagentExposedEvent.class, name = "SUBAGENT_EXPOSED"),
    @JsonSubTypes.Type(value = HintBlockEvent.class, name = "HINT_BLOCK"),
    @JsonSubTypes.Type(value = AllToolsDeniedEvent.class, name = "ALL_TOOLS_DENIED"),
    @JsonSubTypes.Type(value = CustomEvent.class, name = "CUSTOM")
})
public abstract class AgentEvent {

    /**
     * Well-known {@link #metadata} key correlating a forwarded subagent event to a harness
     * {@code TaskRecord} / remote Agent Protocol task id. Distinct from {@link #source}, which
     * identifies the originating agent path ({@code parentSession/agentId}).
     */
    public static final String METADATA_TASK_ID = "taskId";

    /**
     * Well-known {@link #metadata} key identifying the parent agent's session that initiated a
     * remote subagent run. Distinct from {@link #source} (which embeds the same id as a path
     * prefix) and from {@link #METADATA_TASK_ID} (which identifies the harness task).
     */
    public static final String METADATA_PARENT_SESSION_ID = "parentSessionId";

    private final String id;
    private final String createdAt;
    private String source;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, Object> metadata;

    protected AgentEvent() {
        this.id = UUID.randomUUID().toString().replace("-", "");
        this.createdAt = Instant.now().toString();
    }

    protected AgentEvent(String id, String createdAt) {
        this.id = id;
        this.createdAt = createdAt;
    }

    public abstract AgentEventType getType();

    public String getId() {
        return id;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    /**
     * Returns the source path identifying the originating agent. {@code null} for events from the
     * top-level (parent) agent; a slash-separated path (e.g. {@code "main/researcher"}) for events
     * forwarded from a subagent.
     */
    public String getSource() {
        return source;
    }

    /**
     * Sets the source path and returns this event. Used by the subagent event forwarding mechanism
     * to tag child events before injecting them into the parent's event stream.
     */
    public AgentEvent withSource(String source) {
        this.source = source;
        return this;
    }

    /**
     * Returns optional metadata attached to this event. May be {@code null} or empty.
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * Attaches arbitrary key-value metadata to this event and returns it for chaining.
     */
    public AgentEvent withMetadata(Map<String, Object> metadata) {
        this.metadata = metadata != null ? new LinkedHashMap<>(metadata) : null;
        return this;
    }

    /**
     * Merges a single metadata entry into this event (creates the map if absent) and returns it
     * for chaining. Passing a {@code null} value removes the key when present.
     */
    public AgentEvent withMetadataEntry(String key, Object value) {
        if (key == null || key.isBlank()) {
            return this;
        }
        Map<String, Object> next = new LinkedHashMap<>();
        if (this.metadata != null) {
            next.putAll(this.metadata);
        }
        if (value == null) {
            next.remove(key);
        } else {
            next.put(key, value);
        }
        this.metadata = next.isEmpty() ? null : next;
        return this;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{id='" + id + "', type=" + getType() + '}';
    }
}
