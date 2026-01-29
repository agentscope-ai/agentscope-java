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
package io.agentscope.core.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.RawSource;
import io.agentscope.core.message.TextBlock;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LiveEvent Tests")
class LiveEventTest {

    @Test
    @DisplayName("Should create audio delta event")
    void shouldCreateAudioDeltaEvent() {
        Msg audioMsg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                AudioBlock.builder()
                                        .source(RawSource.pcm16kMono(new byte[100]))
                                        .build())
                        .build();

        LiveEvent event = LiveEvent.audioDelta(audioMsg, false);

        assertEquals(LiveEventType.AUDIO_DELTA, event.type());
        assertFalse(event.isLast());
        assertNotNull(event.eventId());
        assertNotNull(event.timestamp());
        assertEquals(audioMsg, event.message());
    }

    @Test
    @DisplayName("Should create text delta event")
    void shouldCreateTextDeltaEvent() {
        Msg textMsg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Hello").build())
                        .build();

        LiveEvent event = LiveEvent.textDelta(textMsg, true);

        assertEquals(LiveEventType.TEXT_DELTA, event.type());
        assertTrue(event.isLast());
        assertEquals(textMsg, event.message());
    }

    @Test
    @DisplayName("Should create session ended event")
    void shouldCreateSessionEndedEvent() {
        LiveEvent event = LiveEvent.sessionEnded("CONNECTION_LOST", false);

        assertEquals(LiveEventType.SESSION_ENDED, event.type());
        assertTrue(event.isLast());
        assertTrue(event.isSessionEnded());
        assertEquals("CONNECTION_LOST", event.getMetadata("live.session.ended_reason"));
        assertEquals("false", event.getMetadata("live.session.recoverable"));
    }

    @Test
    @DisplayName("Should create error event with string error type")
    void shouldCreateErrorEventWithStringErrorType() {
        LiveEvent event = LiveEvent.error("CONNECTION_ERROR", "WebSocket closed");

        assertEquals(LiveEventType.ERROR, event.type());
        assertTrue(event.isError());
        assertTrue(event.isLast());
        assertEquals("CONNECTION_ERROR", event.getMetadata("live.error.type"));
        assertEquals("WebSocket closed", event.getMetadata("live.error.message"));
    }

    @Test
    @DisplayName("Should create error event with LiveErrorType")
    void shouldCreateErrorEventWithLiveErrorType() {
        LiveEvent event =
                LiveEvent.error(LiveErrorType.CONNECTION_ERROR, "WebSocket connection failed");

        assertEquals(LiveEventType.ERROR, event.type());
        assertTrue(event.isError());
        assertEquals("CONNECTION_ERROR", event.getMetadata("live.error.type"));
        assertEquals("WebSocket connection failed", event.getMetadata("live.error.message"));
    }

    @Test
    @DisplayName("Should create session resumption event")
    void shouldCreateSessionResumptionEvent() {
        LiveEvent event = LiveEvent.sessionResumption("handle-123", true);

        assertEquals(LiveEventType.SESSION_RESUMPTION, event.type());
        assertEquals("handle-123", event.getMetadata("live.session.resumption_handle"));
        assertEquals("true", event.getMetadata("live.session.resumable"));
    }

    @Test
    @DisplayName("Should create session resumption event with null handle")
    void shouldCreateSessionResumptionEventWithNullHandle() {
        LiveEvent event = LiveEvent.sessionResumption(null, false);

        assertEquals(LiveEventType.SESSION_RESUMPTION, event.type());
        assertEquals("", event.getMetadata("live.session.resumption_handle"));
        assertEquals("false", event.getMetadata("live.session.resumable"));
    }

    @Test
    @DisplayName("Should create reconnecting event")
    void shouldCreateReconnectingEvent() {
        LiveEvent event = LiveEvent.reconnecting(2, 5);

        assertEquals(LiveEventType.RECONNECTING, event.type());
        assertEquals("2", event.getMetadata("live.connection.attempt"));
        assertEquals("5", event.getMetadata("live.connection.max_attempts"));
    }

    @Test
    @DisplayName("Should create reconnected event")
    void shouldCreateReconnectedEvent() {
        LiveEvent event = LiveEvent.reconnected();

        assertEquals(LiveEventType.RECONNECTED, event.type());
        assertTrue(event.isLast());
        assertNull(event.message());
    }

    @Test
    @DisplayName("Should create unknown event with raw data preview")
    void shouldCreateUnknownEventWithRawDataPreview() {
        Map<String, Object> rawData = Map.of("type", "custom_event", "data", "test");
        LiveEvent event = LiveEvent.unknown("custom_event", rawData);

        assertEquals(LiveEventType.UNKNOWN, event.type());
        assertEquals("custom_event", event.getMetadata("live.original_type"));
        assertNotNull(event.getMetadata("live.raw_preview"));
    }

    @Test
    @DisplayName("Should truncate long raw data preview")
    void shouldTruncateLongRawDataPreview() {
        String longData = "x".repeat(300);
        LiveEvent event = LiveEvent.unknown("long_event", longData);

        String preview = event.getMetadata("live.raw_preview");
        assertNotNull(preview);
        assertTrue(preview.length() <= 203); // 200 chars + "..."
        assertTrue(preview.endsWith("..."));
    }

    @Test
    @DisplayName("Should create turn complete event")
    void shouldCreateTurnCompleteEvent() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Done").build())
                        .build();
        LiveEvent event = LiveEvent.turnComplete(msg);

        assertEquals(LiveEventType.TURN_COMPLETE, event.type());
        assertTrue(event.isLast());
        assertEquals(msg, event.message());
    }

    @Test
    @DisplayName("Should create speech started event")
    void shouldCreateSpeechStartedEvent() {
        LiveEvent event = LiveEvent.speechStarted();

        assertEquals(LiveEventType.SPEECH_STARTED, event.type());
        assertTrue(event.isLast());
        assertNull(event.message());
    }

    @Test
    @DisplayName("Should create speech stopped event")
    void shouldCreateSpeechStoppedEvent() {
        LiveEvent event = LiveEvent.speechStopped();

        assertEquals(LiveEventType.SPEECH_STOPPED, event.type());
        assertTrue(event.isLast());
        assertNull(event.message());
    }

    @Test
    @DisplayName("Should create tool call event")
    void shouldCreateToolCallEvent() {
        Msg toolMsg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("tool_call").build())
                        .build();
        LiveEvent event = LiveEvent.toolCall(toolMsg, false);

        assertEquals(LiveEventType.TOOL_CALL, event.type());
        assertFalse(event.isLast());
        assertEquals(toolMsg, event.message());
    }

    @Test
    @DisplayName("Should create connection state event")
    void shouldCreateConnectionStateEvent() {
        LiveEvent event = LiveEvent.connectionState("CONNECTED", "Initial connection", true);

        assertEquals(LiveEventType.CONNECTION_STATE, event.type());
        assertEquals("CONNECTED", event.getMetadata("live.connection.state"));
        assertEquals("Initial connection", event.getMetadata("live.connection.reason"));
        assertEquals("true", event.getMetadata("live.connection.recoverable"));
    }

    @Test
    @DisplayName("Should create go away event")
    void shouldCreateGoAwayEvent() {
        LiveEvent event = LiveEvent.goAway(30000L);

        assertEquals(LiveEventType.GO_AWAY, event.type());
        assertEquals("30000", event.getMetadata("live.go_away.time_left_ms"));
    }

    @Test
    @DisplayName("Should return null for non-existent metadata")
    void shouldReturnNullForNonExistentMetadata() {
        LiveEvent event = LiveEvent.speechStarted();

        assertNull(event.getMetadata("non.existent.key"));
    }

    @Test
    @DisplayName("Should return null metadata when message is null")
    void shouldReturnNullMetadataWhenMessageIsNull() {
        LiveEvent event = LiveEvent.interrupted();

        assertNull(event.getMetadata("any.key"));
    }

    @Test
    @DisplayName("Should create interrupted event")
    void shouldCreateInterruptedEvent() {
        LiveEvent event = LiveEvent.interrupted();

        assertEquals(LiveEventType.INTERRUPTED, event.type());
        assertTrue(event.isLast());
        assertNull(event.message());
    }

    @Test
    @DisplayName("Should create generation complete event")
    void shouldCreateGenerationCompleteEvent() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Generated").build())
                        .build();
        LiveEvent event = LiveEvent.generationComplete(msg);

        assertEquals(LiveEventType.GENERATION_COMPLETE, event.type());
        assertTrue(event.isLast());
        assertEquals(msg, event.message());
    }

    @Test
    @DisplayName("Should create usage metadata event")
    void shouldCreateUsageMetadataEvent() {
        Msg usageMsg = Msg.builder().metadata(Map.of("tokens", "100", "cost", "0.01")).build();
        LiveEvent event = LiveEvent.usageMetadata(usageMsg);

        assertEquals(LiveEventType.USAGE_METADATA, event.type());
        assertTrue(event.isLast());
        assertEquals(usageMsg, event.message());
    }

    @Test
    @DisplayName("Should have unique event IDs")
    void shouldHaveUniqueEventIds() {
        LiveEvent event1 = LiveEvent.speechStarted();
        LiveEvent event2 = LiveEvent.speechStarted();

        assertNotEquals(event1.eventId(), event2.eventId());
    }

    @Test
    @DisplayName("Should have timestamp set")
    void shouldHaveTimestampSet() {
        LiveEvent event = LiveEvent.speechStarted();

        assertNotNull(event.timestamp());
    }
}
