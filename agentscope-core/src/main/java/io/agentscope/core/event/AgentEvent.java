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
            name = "EXTERNAL_EXECUTION_RESULT")
})
public abstract class AgentEvent {

    private final String id;
    private final String createdAt;

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

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{id='" + id + "', type=" + getType() + '}';
    }
}
