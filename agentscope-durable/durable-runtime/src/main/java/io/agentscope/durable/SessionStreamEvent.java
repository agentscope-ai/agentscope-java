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
package io.agentscope.durable;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.durable.DurableTypes.LiveEvent;
import io.agentscope.durable.DurableTypes.PublicEvent;
import io.agentscope.durable.DurableTypes.Snapshot;
import io.agentscope.durable.EventStreamOptions.EventDeltaType;
import java.util.Objects;

/** One item from a Session event stream. Only {@link Committed} owns a Durable cursor. */
public sealed interface SessionStreamEvent
        permits SessionStreamEvent.Committed,
                SessionStreamEvent.PreviewStarted,
                SessionStreamEvent.EventDelta,
                SessionStreamEvent.SnapshotReset {

    record Committed(PublicEvent event) implements SessionStreamEvent {
        public Committed {
            Objects.requireNonNull(event);
        }
    }

    record PreviewStarted(
            String eventId,
            EventDeltaType previewType,
            String threadId,
            String turnId,
            String runId,
            String stepId,
            JsonNode data)
            implements SessionStreamEvent {
        public PreviewStarted {
            DurableJson.text(eventId, "preview eventId");
            Objects.requireNonNull(previewType);
            DurableJson.text(threadId, "threadId");
            DurableJson.text(turnId, "turnId");
            if (runId != null) DurableJson.text(runId, "runId");
            if (stepId != null) DurableJson.text(stepId, "stepId");
            data = DurableJson.copy(data);
        }
    }

    record EventDelta(
            String eventId,
            EventDeltaType deltaType,
            String blockId,
            String threadId,
            String turnId,
            String runId,
            String stepId,
            JsonNode data)
            implements SessionStreamEvent {
        public EventDelta {
            DurableJson.text(eventId, "delta eventId");
            Objects.requireNonNull(deltaType);
            if (blockId != null) DurableJson.text(blockId, "blockId");
            DurableJson.text(threadId, "threadId");
            DurableJson.text(turnId, "turnId");
            if (runId != null) DurableJson.text(runId, "runId");
            if (stepId != null) DurableJson.text(stepId, "stepId");
            data = DurableJson.copy(data);
        }
    }

    /** A cursor expired and the consumer must replace its local durable projection. */
    record SnapshotReset(Snapshot snapshot) implements SessionStreamEvent {
        public SnapshotReset {
            Objects.requireNonNull(snapshot);
        }
    }

    static SessionStreamEvent from(LiveEvent event) {
        var data = event.data();
        return switch (event.type()) {
            case "message.started" ->
                    new PreviewStarted(
                            data.required("messageId").asText(),
                            EventDeltaType.MESSAGE,
                            event.threadId(),
                            event.turnId(),
                            event.runId(),
                            event.stepId(),
                            data);
            case "message.delta" ->
                    new EventDelta(
                            data.required("messageId").asText(),
                            EventDeltaType.MESSAGE,
                            data.path("blockId").asText(null),
                            event.threadId(),
                            event.turnId(),
                            event.runId(),
                            event.stepId(),
                            data);
            case "tool.arguments.delta" ->
                    new EventDelta(
                            data.required("toolCallId").asText(),
                            EventDeltaType.TOOL_ARGUMENTS,
                            null,
                            event.threadId(),
                            event.turnId(),
                            event.runId(),
                            event.stepId(),
                            data);
            default ->
                    throw new IllegalArgumentException(
                            "Unsupported live stream event: " + event.type());
        };
    }
}
