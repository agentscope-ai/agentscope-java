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
 * Unit tests for TTSEvent and its subclasses.
 */
class TTSEventTest {

    @Nested
    @DisplayName("SessionUpdateEvent Tests")
    class SessionUpdateEventTests {

        @Test
        @DisplayName("should create SessionUpdateEvent with session config")
        void shouldCreateSessionUpdateEvent() {
            SessionConfig sessionConfig =
                    SessionConfig.builder()
                            .mode("server_commit")
                            .voice("Cherry")
                            .sampleRate(24000)
                            .build();

            TTSEvent.SessionUpdateEvent event =
                    new TTSEvent.SessionUpdateEvent("event-123", sessionConfig);

            assertEquals("session.update", event.getType());
            assertEquals("event-123", event.getEventId());
            assertNotNull(event.getSession());
            assertEquals("server_commit", event.getSession().getMode());
            assertEquals("Cherry", event.getSession().getVoice());
        }

        @Test
        @DisplayName("should serialize SessionUpdateEvent to JSON")
        void shouldSerializeSessionUpdateEvent() {
            SessionConfig sessionConfig =
                    SessionConfig.builder()
                            .mode("commit")
                            .voice("Serena")
                            .languageType("English")
                            .responseFormat("wav")
                            .sampleRate(16000)
                            .build();

            TTSEvent.SessionUpdateEvent event =
                    new TTSEvent.SessionUpdateEvent("event-456", sessionConfig);

            String json = JsonUtils.getJsonCodec().toJson(event);

            assertNotNull(json);
            assertTrue(json.contains("\"type\":\"session.update\""));
            assertTrue(json.contains("\"event_id\":\"event-456\""));
            assertTrue(json.contains("\"session\""));
        }
    }

    @Nested
    @DisplayName("AppendTextEvent Tests")
    class AppendTextEventTests {

        @Test
        @DisplayName("should create AppendTextEvent with text")
        void shouldCreateAppendTextEvent() {
            TTSEvent.AppendTextEvent event =
                    new TTSEvent.AppendTextEvent("event-789", "Hello, world!");

            assertEquals("input_text_buffer.append", event.getType());
            assertEquals("event-789", event.getEventId());
            assertEquals("Hello, world!", event.getText());
        }

        @Test
        @DisplayName("should serialize AppendTextEvent to JSON")
        void shouldSerializeAppendTextEvent() {
            TTSEvent.AppendTextEvent event = new TTSEvent.AppendTextEvent("event-abc", "Test text");

            String json = JsonUtils.getJsonCodec().toJson(event);

            assertNotNull(json);
            assertTrue(json.contains("\"type\":\"input_text_buffer.append\""));
            assertTrue(json.contains("\"event_id\":\"event-abc\""));
            assertTrue(json.contains("\"text\":\"Test text\""));
        }

        @Test
        @DisplayName("should handle empty text")
        void shouldHandleEmptyText() {
            TTSEvent.AppendTextEvent event = new TTSEvent.AppendTextEvent("event-empty", "");

            assertEquals("", event.getText());
        }
    }

    @Nested
    @DisplayName("CommitEvent Tests")
    class CommitEventTests {

        @Test
        @DisplayName("should create CommitEvent")
        void shouldCreateCommitEvent() {
            TTSEvent.CommitEvent event = new TTSEvent.CommitEvent("event-commit");

            assertEquals("input_text_buffer.commit", event.getType());
            assertEquals("event-commit", event.getEventId());
        }

        @Test
        @DisplayName("should serialize CommitEvent to JSON")
        void shouldSerializeCommitEvent() {
            TTSEvent.CommitEvent event = new TTSEvent.CommitEvent("event-xyz");

            String json = JsonUtils.getJsonCodec().toJson(event);

            assertNotNull(json);
            assertTrue(json.contains("\"type\":\"input_text_buffer.commit\""));
            assertTrue(json.contains("\"event_id\":\"event-xyz\""));
        }
    }

    @Nested
    @DisplayName("ClearEvent Tests")
    class ClearEventTests {

        @Test
        @DisplayName("should create ClearEvent")
        void shouldCreateClearEvent() {
            TTSEvent.ClearEvent event = new TTSEvent.ClearEvent("event-clear");

            assertEquals("input_text_buffer.clear", event.getType());
            assertEquals("event-clear", event.getEventId());
        }

        @Test
        @DisplayName("should serialize ClearEvent to JSON")
        void shouldSerializeClearEvent() {
            TTSEvent.ClearEvent event = new TTSEvent.ClearEvent("event-clear-123");

            String json = JsonUtils.getJsonCodec().toJson(event);

            assertNotNull(json);
            assertTrue(json.contains("\"type\":\"input_text_buffer.clear\""));
            assertTrue(json.contains("\"event_id\":\"event-clear-123\""));
        }
    }

    @Nested
    @DisplayName("FinishEvent Tests")
    class FinishEventTests {

        @Test
        @DisplayName("should create FinishEvent")
        void shouldCreateFinishEvent() {
            TTSEvent.FinishEvent event = new TTSEvent.FinishEvent("event-finish");

            assertEquals("session.finish", event.getType());
            assertEquals("event-finish", event.getEventId());
        }

        @Test
        @DisplayName("should serialize FinishEvent to JSON")
        void shouldSerializeFinishEvent() {
            TTSEvent.FinishEvent event = new TTSEvent.FinishEvent("event-finish-456");

            String json = JsonUtils.getJsonCodec().toJson(event);

            assertNotNull(json);
            assertTrue(json.contains("\"type\":\"session.finish\""));
            assertTrue(json.contains("\"event_id\":\"event-finish-456\""));
        }
    }

    @Nested
    @DisplayName("JSON Deserialization Tests")
    class JsonDeserializationTests {

        @Test
        @DisplayName("should deserialize SessionUpdateEvent from JSON")
        void shouldDeserializeSessionUpdateEvent() {
            String json =
                    "{\"type\":\"session.update\",\"event_id\":\"event-123\","
                        + "\"session\":{\"mode\":\"server_commit\",\"voice\":\"Cherry\","
                        + "\"language_type\":\"Chinese\",\"response_format\":\"wav\",\"sample_rate\":24000}}";

            TTSEvent event = JsonUtils.getJsonCodec().fromJson(json, TTSEvent.class);

            assertNotNull(event);
            assertEquals(TTSEvent.SessionUpdateEvent.class, event.getClass());
            assertEquals("session.update", event.getType());
            assertEquals("event-123", event.getEventId());

            TTSEvent.SessionUpdateEvent updateEvent = (TTSEvent.SessionUpdateEvent) event;
            assertNotNull(updateEvent.getSession());
            assertEquals("server_commit", updateEvent.getSession().getMode());
        }

        @Test
        @DisplayName("should deserialize AppendTextEvent from JSON")
        void shouldDeserializeAppendTextEvent() {
            String json =
                    "{\"type\":\"input_text_buffer.append\",\"event_id\":\"event-456\","
                            + "\"text\":\"Hello, world!\"}";

            TTSEvent event = JsonUtils.getJsonCodec().fromJson(json, TTSEvent.class);

            assertNotNull(event);
            assertEquals(TTSEvent.AppendTextEvent.class, event.getClass());
            assertEquals("input_text_buffer.append", event.getType());

            TTSEvent.AppendTextEvent appendEvent = (TTSEvent.AppendTextEvent) event;
            assertEquals("Hello, world!", appendEvent.getText());
        }

        @Test
        @DisplayName("should deserialize CommitEvent from JSON")
        void shouldDeserializeCommitEvent() {
            String json = "{\"type\":\"input_text_buffer.commit\",\"event_id\":\"event-789\"}";

            TTSEvent event = JsonUtils.getJsonCodec().fromJson(json, TTSEvent.class);

            assertNotNull(event);
            assertEquals(TTSEvent.CommitEvent.class, event.getClass());
            assertEquals("input_text_buffer.commit", event.getType());
        }

        @Test
        @DisplayName("should deserialize ClearEvent from JSON")
        void shouldDeserializeClearEvent() {
            String json = "{\"type\":\"input_text_buffer.clear\",\"event_id\":\"event-clear\"}";

            TTSEvent event = JsonUtils.getJsonCodec().fromJson(json, TTSEvent.class);

            assertNotNull(event);
            assertEquals(TTSEvent.ClearEvent.class, event.getClass());
            assertEquals("input_text_buffer.clear", event.getType());
        }

        @Test
        @DisplayName("should deserialize FinishEvent from JSON")
        void shouldDeserializeFinishEvent() {
            String json = "{\"type\":\"session.finish\",\"event_id\":\"event-finish\"}";

            TTSEvent event = JsonUtils.getJsonCodec().fromJson(json, TTSEvent.class);

            assertNotNull(event);
            assertEquals(TTSEvent.FinishEvent.class, event.getClass());
            assertEquals("session.finish", event.getType());
        }
    }
}
