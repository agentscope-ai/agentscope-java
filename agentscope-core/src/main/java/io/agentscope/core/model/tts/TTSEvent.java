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
package io.agentscope.core.model.tts;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Base class for TTS WebSocket events.
 *
 * <p>All events sent to the DashScope TTS service via WebSocket extend this class.
 * Each event has a type and an event_id for tracking.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = TTSEvent.SessionUpdateEvent.class, name = "session.update"),
    @JsonSubTypes.Type(value = TTSEvent.AppendTextEvent.class, name = "input_text_buffer.append"),
    @JsonSubTypes.Type(value = TTSEvent.CommitEvent.class, name = "input_text_buffer.commit"),
    @JsonSubTypes.Type(value = TTSEvent.ClearEvent.class, name = "input_text_buffer.clear"),
    @JsonSubTypes.Type(value = TTSEvent.FinishEvent.class, name = "session.finish")
})
public abstract class TTSEvent {

    @JsonProperty("type")
    private final String type;

    @JsonProperty("event_id")
    private final String eventId;

    protected TTSEvent(String type, String eventId) {
        this.type = type;
        this.eventId = eventId;
    }

    public String getType() {
        return type;
    }

    public String getEventId() {
        return eventId;
    }

    /**
     * Session update event.
     */
    public static class SessionUpdateEvent extends TTSEvent {
        @JsonProperty("session")
        private final SessionConfig session;

        @JsonCreator
        public SessionUpdateEvent(
                @JsonProperty("event_id") String eventId,
                @JsonProperty("session") SessionConfig session) {
            super("session.update", eventId);
            this.session = session;
        }

        public SessionConfig getSession() {
            return session;
        }
    }

    /**
     * Append text to input buffer event.
     */
    public static class AppendTextEvent extends TTSEvent {
        @JsonProperty("text")
        private final String text;

        @JsonCreator
        public AppendTextEvent(
                @JsonProperty("event_id") String eventId, @JsonProperty("text") String text) {
            super("input_text_buffer.append", eventId);
            this.text = text;
        }

        public String getText() {
            return text;
        }
    }

    /**
     * Commit text buffer event.
     */
    public static class CommitEvent extends TTSEvent {
        @JsonCreator
        public CommitEvent(@JsonProperty("event_id") String eventId) {
            super("input_text_buffer.commit", eventId);
        }
    }

    /**
     * Clear text buffer event.
     */
    public static class ClearEvent extends TTSEvent {
        @JsonCreator
        public ClearEvent(@JsonProperty("event_id") String eventId) {
            super("input_text_buffer.clear", eventId);
        }
    }

    /**
     * Session finish event.
     */
    public static class FinishEvent extends TTSEvent {
        @JsonCreator
        public FinishEvent(@JsonProperty("event_id") String eventId) {
            super("session.finish", eventId);
        }
    }
}
