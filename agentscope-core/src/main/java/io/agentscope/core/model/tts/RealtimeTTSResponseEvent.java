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
 * Base class for WebSocket response events from DashScope Realtime TTS API.
 *
 * <p>All events received from the DashScope TTS service via WebSocket extend this class.
 * Each event has a type for identification.
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type",
        defaultImpl = RealtimeTTSResponseEvent.UnknownEvent.class)
@JsonSubTypes({
    @JsonSubTypes.Type(value = RealtimeTTSResponseEvent.ErrorEvent.class, name = "error"),
    @JsonSubTypes.Type(
            value = RealtimeTTSResponseEvent.SessionCreatedEvent.class,
            name = "session.created"),
    @JsonSubTypes.Type(
            value = RealtimeTTSResponseEvent.SessionUpdatedEvent.class,
            name = "session.updated"),
    @JsonSubTypes.Type(
            value = RealtimeTTSResponseEvent.InputTextBufferCommittedEvent.class,
            name = "input_text_buffer.committed"),
    @JsonSubTypes.Type(
            value = RealtimeTTSResponseEvent.InputTextBufferClearedEvent.class,
            name = "input_text_buffer.cleared"),
    @JsonSubTypes.Type(
            value = RealtimeTTSResponseEvent.ResponseCreatedEvent.class,
            name = "response.created"),
    @JsonSubTypes.Type(
            value = RealtimeTTSResponseEvent.ResponseOutputItemAddedEvent.class,
            name = "response.output_item.added"),
    @JsonSubTypes.Type(
            value = RealtimeTTSResponseEvent.ResponseOutputItemDoneEvent.class,
            name = "response.output_item.done"),
    @JsonSubTypes.Type(
            value = RealtimeTTSResponseEvent.ResponseContentPartAddedEvent.class,
            name = "response.content_part.added"),
    @JsonSubTypes.Type(
            value = RealtimeTTSResponseEvent.ResponseContentPartDoneEvent.class,
            name = "response.content_part.done"),
    @JsonSubTypes.Type(
            value = RealtimeTTSResponseEvent.ResponseAudioDeltaEvent.class,
            name = "response.audio.delta"),
    @JsonSubTypes.Type(
            value = RealtimeTTSResponseEvent.ResponseAudioDoneEvent.class,
            name = "response.audio.done"),
    @JsonSubTypes.Type(
            value = RealtimeTTSResponseEvent.ResponseDoneEvent.class,
            name = "response.done"),
    @JsonSubTypes.Type(
            value = RealtimeTTSResponseEvent.SessionFinishedEvent.class,
            name = "session.finished")
})
public abstract class RealtimeTTSResponseEvent {

    @JsonProperty("type")
    private final String type;

    protected RealtimeTTSResponseEvent(String type) {
        this.type = type;
    }

    public String getType() {
        return type;
    }

    /**
     * Error event.
     */
    public static class ErrorEvent extends RealtimeTTSResponseEvent {
        @JsonProperty("error")
        private final Object error;

        @JsonCreator
        public ErrorEvent(@JsonProperty("error") Object error) {
            super("error");
            this.error = error;
        }

        public Object getError() {
            return error;
        }
    }

    /**
     * Session created event.
     */
    public static class SessionCreatedEvent extends RealtimeTTSResponseEvent {
        @JsonProperty("session")
        private final SessionInfo session;

        @JsonCreator
        public SessionCreatedEvent(@JsonProperty("session") SessionInfo session) {
            super("session.created");
            this.session = session;
        }

        public SessionInfo getSession() {
            return session;
        }

        /** Session information. */
        public static class SessionInfo {
            @JsonProperty("id")
            private final String id;

            @JsonCreator
            public SessionInfo(@JsonProperty("id") String id) {
                this.id = id;
            }

            public String getId() {
                return id;
            }
        }
    }

    /**
     * Session updated event.
     */
    public static class SessionUpdatedEvent extends RealtimeTTSResponseEvent {
        @JsonCreator
        public SessionUpdatedEvent() {
            super("session.updated");
        }
    }

    /**
     * Input text buffer committed event.
     */
    public static class InputTextBufferCommittedEvent extends RealtimeTTSResponseEvent {
        @JsonProperty("item_id")
        private final String itemId;

        @JsonCreator
        public InputTextBufferCommittedEvent(@JsonProperty("item_id") String itemId) {
            super("input_text_buffer.committed");
            this.itemId = itemId;
        }

        public String getItemId() {
            return itemId;
        }
    }

    /**
     * Input text buffer cleared event.
     */
    public static class InputTextBufferClearedEvent extends RealtimeTTSResponseEvent {
        @JsonCreator
        public InputTextBufferClearedEvent() {
            super("input_text_buffer.cleared");
        }
    }

    /**
     * Response created event.
     */
    public static class ResponseCreatedEvent extends RealtimeTTSResponseEvent {
        @JsonProperty("response")
        private final ResponseInfo response;

        @JsonCreator
        public ResponseCreatedEvent(@JsonProperty("response") ResponseInfo response) {
            super("response.created");
            this.response = response;
        }

        public ResponseInfo getResponse() {
            return response;
        }

        /** Response information. */
        public static class ResponseInfo {
            @JsonProperty("id")
            private final String id;

            @JsonCreator
            public ResponseInfo(@JsonProperty("id") String id) {
                this.id = id;
            }

            public String getId() {
                return id;
            }
        }
    }

    /**
     * Response output item added event.
     */
    public static class ResponseOutputItemAddedEvent extends RealtimeTTSResponseEvent {
        @JsonProperty("item")
        private final ItemInfo item;

        @JsonCreator
        public ResponseOutputItemAddedEvent(@JsonProperty("item") ItemInfo item) {
            super("response.output_item.added");
            this.item = item;
        }

        public ItemInfo getItem() {
            return item;
        }

        /** Item information. */
        public static class ItemInfo {
            @JsonProperty("id")
            private final String id;

            @JsonCreator
            public ItemInfo(@JsonProperty("id") String id) {
                this.id = id;
            }

            public String getId() {
                return id;
            }
        }
    }

    /**
     * Response output item done event.
     */
    public static class ResponseOutputItemDoneEvent extends RealtimeTTSResponseEvent {
        @JsonCreator
        public ResponseOutputItemDoneEvent() {
            super("response.output_item.done");
        }
    }

    /**
     * Response content part added event.
     */
    public static class ResponseContentPartAddedEvent extends RealtimeTTSResponseEvent {
        @JsonProperty("content_part")
        private final ContentPartInfo contentPart;

        @JsonCreator
        public ResponseContentPartAddedEvent(
                @JsonProperty("content_part") ContentPartInfo contentPart) {
            super("response.content_part.added");
            this.contentPart = contentPart;
        }

        public ContentPartInfo getContentPart() {
            return contentPart;
        }

        /** Content part information. */
        public static class ContentPartInfo {
            @JsonProperty("id")
            private final String id;

            @JsonCreator
            public ContentPartInfo(@JsonProperty("id") String id) {
                this.id = id;
            }

            public String getId() {
                return id;
            }
        }
    }

    /**
     * Response content part done event.
     */
    public static class ResponseContentPartDoneEvent extends RealtimeTTSResponseEvent {
        @JsonCreator
        public ResponseContentPartDoneEvent() {
            super("response.content_part.done");
        }
    }

    /**
     * Response audio delta event (contains audio data).
     */
    public static class ResponseAudioDeltaEvent extends RealtimeTTSResponseEvent {
        @JsonProperty("delta")
        private final String delta;

        @JsonCreator
        public ResponseAudioDeltaEvent(@JsonProperty("delta") String delta) {
            super("response.audio.delta");
            this.delta = delta;
        }

        public String getDelta() {
            return delta;
        }
    }

    /**
     * Response audio done event.
     */
    public static class ResponseAudioDoneEvent extends RealtimeTTSResponseEvent {
        @JsonCreator
        public ResponseAudioDoneEvent() {
            super("response.audio.done");
        }
    }

    /**
     * Response done event.
     */
    public static class ResponseDoneEvent extends RealtimeTTSResponseEvent {
        @JsonCreator
        public ResponseDoneEvent() {
            super("response.done");
        }
    }

    /**
     * Session finished event.
     */
    public static class SessionFinishedEvent extends RealtimeTTSResponseEvent {
        @JsonCreator
        public SessionFinishedEvent() {
            super("session.finished");
        }
    }

    /**
     * Unknown event type (fallback for unregistered event types).
     *
     * <p>This class is used as the default implementation when Jackson encounters
     * an event type that is not registered in {@code @JsonSubTypes}. This allows
     * the code to gracefully handle new event types from the API without throwing
     * exceptions.
     */
    public static class UnknownEvent extends RealtimeTTSResponseEvent {
        @JsonCreator
        public UnknownEvent(@JsonProperty("type") String type) {
            super(type != null ? type : "unknown");
        }
    }
}
