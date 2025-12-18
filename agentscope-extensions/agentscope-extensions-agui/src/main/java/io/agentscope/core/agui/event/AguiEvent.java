/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agui.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Base interface for all AG-UI protocol events.
 *
 * <p>All events in the AG-UI protocol implement this interface and provide
 * common properties like event type, thread ID, and run ID.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = RunStartedEvent.class, name = "RUN_STARTED"),
    @JsonSubTypes.Type(value = RunFinishedEvent.class, name = "RUN_FINISHED"),
    @JsonSubTypes.Type(value = TextMessageStartEvent.class, name = "TEXT_MESSAGE_START"),
    @JsonSubTypes.Type(value = TextMessageContentEvent.class, name = "TEXT_MESSAGE_CONTENT"),
    @JsonSubTypes.Type(value = TextMessageEndEvent.class, name = "TEXT_MESSAGE_END"),
    @JsonSubTypes.Type(value = ToolCallStartEvent.class, name = "TOOL_CALL_START"),
    @JsonSubTypes.Type(value = ToolCallArgsEvent.class, name = "TOOL_CALL_ARGS"),
    @JsonSubTypes.Type(value = ToolCallEndEvent.class, name = "TOOL_CALL_END"),
    @JsonSubTypes.Type(value = StateSnapshotEvent.class, name = "STATE_SNAPSHOT"),
    @JsonSubTypes.Type(value = StateDeltaEvent.class, name = "STATE_DELTA"),
    @JsonSubTypes.Type(value = RawEvent.class, name = "RAW")
})
public interface AguiEvent {

    /**
     * Get the event type.
     *
     * @return The event type
     */
    AguiEventType getType();

    /**
     * Get the thread ID associated with this event.
     *
     * @return The thread ID
     */
    String getThreadId();

    /**
     * Get the run ID associated with this event.
     *
     * @return The run ID
     */
    String getRunId();
}
