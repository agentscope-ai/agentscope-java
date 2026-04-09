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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.util.JsonUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for RealtimeTTSResponseEvent.
 */
class RealtimeTTSResponseEventTest {

    @Nested
    @DisplayName("ErrorEvent Tests")
    class ErrorEventTests {

        @Test
        @DisplayName("should deserialize error event")
        void shouldDeserializeErrorEvent() {
            String json = "{\"type\":\"error\",\"error\":\"Invalid API key\"}";

            RealtimeTTSResponseEvent event =
                    JsonUtils.getJsonCodec().fromJson(json, RealtimeTTSResponseEvent.class);

            assertNotNull(event);
            assertTrue(event instanceof RealtimeTTSResponseEvent.ErrorEvent);
            assertEquals("error", event.getType());
            RealtimeTTSResponseEvent.ErrorEvent errorEvent =
                    (RealtimeTTSResponseEvent.ErrorEvent) event;
            assertEquals("Invalid API key", errorEvent.getError().toString());
        }
    }

    @Nested
    @DisplayName("SessionCreatedEvent Tests")
    class SessionCreatedEventTests {

        @Test
        @DisplayName("should deserialize session created event")
        void shouldDeserializeSessionCreatedEvent() {
            String json = "{\"type\":\"session.created\",\"session\":{\"id\":\"session-123\"}}";

            RealtimeTTSResponseEvent event =
                    JsonUtils.getJsonCodec().fromJson(json, RealtimeTTSResponseEvent.class);

            assertNotNull(event);
            assertTrue(event instanceof RealtimeTTSResponseEvent.SessionCreatedEvent);
            assertEquals("session.created", event.getType());
            RealtimeTTSResponseEvent.SessionCreatedEvent sessionEvent =
                    (RealtimeTTSResponseEvent.SessionCreatedEvent) event;
            assertNotNull(sessionEvent.getSession());
            assertEquals("session-123", sessionEvent.getSession().getId());
        }
    }

    @Nested
    @DisplayName("SessionUpdatedEvent Tests")
    class SessionUpdatedEventTests {

        @Test
        @DisplayName("should deserialize session updated event")
        void shouldDeserializeSessionUpdatedEvent() {
            String json = "{\"type\":\"session.updated\"}";

            RealtimeTTSResponseEvent event =
                    JsonUtils.getJsonCodec().fromJson(json, RealtimeTTSResponseEvent.class);

            assertNotNull(event);
            assertTrue(event instanceof RealtimeTTSResponseEvent.SessionUpdatedEvent);
            assertEquals("session.updated", event.getType());
        }
    }

    @Nested
    @DisplayName("InputTextBufferCommittedEvent Tests")
    class InputTextBufferCommittedEventTests {

        @Test
        @DisplayName("should deserialize input text buffer committed event")
        void shouldDeserializeInputTextBufferCommittedEvent() {
            String json = "{\"type\":\"input_text_buffer.committed\",\"item_id\":\"item-456\"}";

            RealtimeTTSResponseEvent event =
                    JsonUtils.getJsonCodec().fromJson(json, RealtimeTTSResponseEvent.class);

            assertNotNull(event);
            assertTrue(event instanceof RealtimeTTSResponseEvent.InputTextBufferCommittedEvent);
            assertEquals("input_text_buffer.committed", event.getType());
            RealtimeTTSResponseEvent.InputTextBufferCommittedEvent committedEvent =
                    (RealtimeTTSResponseEvent.InputTextBufferCommittedEvent) event;
            assertEquals("item-456", committedEvent.getItemId());
        }
    }

    @Nested
    @DisplayName("InputTextBufferClearedEvent Tests")
    class InputTextBufferClearedEventTests {

        @Test
        @DisplayName("should deserialize input text buffer cleared event")
        void shouldDeserializeInputTextBufferClearedEvent() {
            String json = "{\"type\":\"input_text_buffer.cleared\"}";

            RealtimeTTSResponseEvent event =
                    JsonUtils.getJsonCodec().fromJson(json, RealtimeTTSResponseEvent.class);

            assertNotNull(event);
            assertTrue(event instanceof RealtimeTTSResponseEvent.InputTextBufferClearedEvent);
            assertEquals("input_text_buffer.cleared", event.getType());
        }
    }

    @Nested
    @DisplayName("ResponseCreatedEvent Tests")
    class ResponseCreatedEventTests {

        @Test
        @DisplayName("should deserialize response created event")
        void shouldDeserializeResponseCreatedEvent() {
            String json = "{\"type\":\"response.created\",\"response\":{\"id\":\"resp-789\"}}";

            RealtimeTTSResponseEvent event =
                    JsonUtils.getJsonCodec().fromJson(json, RealtimeTTSResponseEvent.class);

            assertNotNull(event);
            assertTrue(event instanceof RealtimeTTSResponseEvent.ResponseCreatedEvent);
            assertEquals("response.created", event.getType());
            RealtimeTTSResponseEvent.ResponseCreatedEvent responseEvent =
                    (RealtimeTTSResponseEvent.ResponseCreatedEvent) event;
            assertNotNull(responseEvent.getResponse());
            assertEquals("resp-789", responseEvent.getResponse().getId());
        }
    }

    @Nested
    @DisplayName("ResponseOutputItemAddedEvent Tests")
    class ResponseOutputItemAddedEventTests {

        @Test
        @DisplayName("should deserialize response output item added event")
        void shouldDeserializeResponseOutputItemAddedEvent() {
            String json =
                    "{\"type\":\"response.output_item.added\",\"item\":{\"id\":\"item-999\"}}";

            RealtimeTTSResponseEvent event =
                    JsonUtils.getJsonCodec().fromJson(json, RealtimeTTSResponseEvent.class);

            assertNotNull(event);
            assertTrue(event instanceof RealtimeTTSResponseEvent.ResponseOutputItemAddedEvent);
            assertEquals("response.output_item.added", event.getType());
            RealtimeTTSResponseEvent.ResponseOutputItemAddedEvent itemEvent =
                    (RealtimeTTSResponseEvent.ResponseOutputItemAddedEvent) event;
            assertNotNull(itemEvent.getItem());
            assertEquals("item-999", itemEvent.getItem().getId());
        }
    }

    @Nested
    @DisplayName("ResponseOutputItemDoneEvent Tests")
    class ResponseOutputItemDoneEventTests {

        @Test
        @DisplayName("should deserialize response output item done event")
        void shouldDeserializeResponseOutputItemDoneEvent() {
            String json = "{\"type\":\"response.output_item.done\"}";

            RealtimeTTSResponseEvent event =
                    JsonUtils.getJsonCodec().fromJson(json, RealtimeTTSResponseEvent.class);

            assertNotNull(event);
            assertTrue(event instanceof RealtimeTTSResponseEvent.ResponseOutputItemDoneEvent);
            assertEquals("response.output_item.done", event.getType());
        }
    }

    @Nested
    @DisplayName("ResponseContentPartAddedEvent Tests")
    class ResponseContentPartAddedEventTests {

        @Test
        @DisplayName("should deserialize response content part added event")
        void shouldDeserializeResponseContentPartAddedEvent() {
            String json =
                    "{\"type\":\"response.content_part.added\",\"content_part\":{\"id\":\"part-123\"}}";

            RealtimeTTSResponseEvent event =
                    JsonUtils.getJsonCodec().fromJson(json, RealtimeTTSResponseEvent.class);

            assertNotNull(event);
            assertTrue(event instanceof RealtimeTTSResponseEvent.ResponseContentPartAddedEvent);
            assertEquals("response.content_part.added", event.getType());
            RealtimeTTSResponseEvent.ResponseContentPartAddedEvent contentPartEvent =
                    (RealtimeTTSResponseEvent.ResponseContentPartAddedEvent) event;
            assertNotNull(contentPartEvent.getContentPart());
            assertEquals("part-123", contentPartEvent.getContentPart().getId());
        }
    }

    @Nested
    @DisplayName("ResponseContentPartDoneEvent Tests")
    class ResponseContentPartDoneEventTests {

        @Test
        @DisplayName("should deserialize response content part done event")
        void shouldDeserializeResponseContentPartDoneEvent() {
            String json = "{\"type\":\"response.content_part.done\"}";

            RealtimeTTSResponseEvent event =
                    JsonUtils.getJsonCodec().fromJson(json, RealtimeTTSResponseEvent.class);

            assertNotNull(event);
            assertTrue(event instanceof RealtimeTTSResponseEvent.ResponseContentPartDoneEvent);
            assertEquals("response.content_part.done", event.getType());
        }
    }

    @Nested
    @DisplayName("ResponseAudioDeltaEvent Tests")
    class ResponseAudioDeltaEventTests {

        @Test
        @DisplayName("should deserialize response audio delta event")
        void shouldDeserializeResponseAudioDeltaEvent() {
            String json = "{\"type\":\"response.audio.delta\",\"delta\":\"base64audio==\"}";

            RealtimeTTSResponseEvent event =
                    JsonUtils.getJsonCodec().fromJson(json, RealtimeTTSResponseEvent.class);

            assertNotNull(event);
            assertTrue(event instanceof RealtimeTTSResponseEvent.ResponseAudioDeltaEvent);
            assertEquals("response.audio.delta", event.getType());
            RealtimeTTSResponseEvent.ResponseAudioDeltaEvent audioEvent =
                    (RealtimeTTSResponseEvent.ResponseAudioDeltaEvent) event;
            assertEquals("base64audio==", audioEvent.getDelta());
        }
    }

    @Nested
    @DisplayName("ResponseAudioDoneEvent Tests")
    class ResponseAudioDoneEventTests {

        @Test
        @DisplayName("should deserialize response audio done event")
        void shouldDeserializeResponseAudioDoneEvent() {
            String json = "{\"type\":\"response.audio.done\"}";

            RealtimeTTSResponseEvent event =
                    JsonUtils.getJsonCodec().fromJson(json, RealtimeTTSResponseEvent.class);

            assertNotNull(event);
            assertTrue(event instanceof RealtimeTTSResponseEvent.ResponseAudioDoneEvent);
            assertEquals("response.audio.done", event.getType());
        }
    }

    @Nested
    @DisplayName("ResponseDoneEvent Tests")
    class ResponseDoneEventTests {

        @Test
        @DisplayName("should deserialize response done event")
        void shouldDeserializeResponseDoneEvent() {
            String json = "{\"type\":\"response.done\"}";

            RealtimeTTSResponseEvent event =
                    JsonUtils.getJsonCodec().fromJson(json, RealtimeTTSResponseEvent.class);

            assertNotNull(event);
            assertTrue(event instanceof RealtimeTTSResponseEvent.ResponseDoneEvent);
            assertEquals("response.done", event.getType());
        }
    }

    @Nested
    @DisplayName("SessionFinishedEvent Tests")
    class SessionFinishedEventTests {

        @Test
        @DisplayName("should deserialize session finished event")
        void shouldDeserializeSessionFinishedEvent() {
            String json = "{\"type\":\"session.finished\"}";

            RealtimeTTSResponseEvent event =
                    JsonUtils.getJsonCodec().fromJson(json, RealtimeTTSResponseEvent.class);

            assertNotNull(event);
            assertTrue(event instanceof RealtimeTTSResponseEvent.SessionFinishedEvent);
            assertEquals("session.finished", event.getType());
        }
    }
}
