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

import io.agentscope.core.message.Msg;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Live event for real-time conversation.
 *
 * <p>Independent from existing Event class, specifically designed for Live scenarios.
 * Use static factory methods to create instances instead of direct constructor calls.
 *
 * <p>Usage examples:
 * <pre>{@code
 * // Audio delta event
 * LiveEvent audioEvent = LiveEvent.audioDelta(audioMsg, false);
 *
 * // Turn complete event
 * LiveEvent turnComplete = LiveEvent.turnComplete(msg);
 *
 * // Error event
 * LiveEvent error = LiveEvent.error("CONNECTION_ERROR", "WebSocket closed");
 * }</pre>
 */
public record LiveEvent(
        /** Event unique identifier */
        String eventId,
        /** Event type */
        LiveEventType type,
        /** Associated message */
        Msg message,
        /** Whether this is the last event of this type (for streaming scenarios) */
        boolean isLast,
        /** Event timestamp */
        Instant timestamp,
        /** Associated request ID */
        String requestId) {

    // ==================== Audio/Text Events ====================

    /** Audio delta event */
    public static LiveEvent audioDelta(Msg message, boolean isLast) {
        return create(LiveEventType.AUDIO_DELTA, message, isLast);
    }

    /** Text delta event */
    public static LiveEvent textDelta(Msg message, boolean isLast) {
        return create(LiveEventType.TEXT_DELTA, message, isLast);
    }

    /** Thinking delta event */
    public static LiveEvent thinkingDelta(Msg message, boolean isLast) {
        return create(LiveEventType.THINKING_DELTA, message, isLast);
    }

    /** Input transcription event */
    public static LiveEvent inputTranscription(Msg message, boolean isLast) {
        return create(LiveEventType.INPUT_TRANSCRIPTION, message, isLast);
    }

    /** Output transcription event */
    public static LiveEvent outputTranscription(Msg message, boolean isLast) {
        return create(LiveEventType.OUTPUT_TRANSCRIPTION, message, isLast);
    }

    // ==================== Session Lifecycle Events ====================

    /** Session created event */
    public static LiveEvent sessionCreated(Msg message) {
        return create(LiveEventType.SESSION_CREATED, message, true);
    }

    /** Session updated event */
    public static LiveEvent sessionUpdated(Msg message) {
        return create(LiveEventType.SESSION_UPDATED, message, true);
    }

    /** Session ended event */
    public static LiveEvent sessionEnded(String reason, boolean recoverable) {
        return create(
                LiveEventType.SESSION_ENDED,
                Msg.builder()
                        .metadata(
                                Map.of(
                                        "live.session.ended_reason",
                                        reason,
                                        "live.session.recoverable",
                                        String.valueOf(recoverable)))
                        .build(),
                true);
    }

    /** Session resumption information updated event */
    public static LiveEvent sessionResumption(String handle, boolean resumable) {
        return create(
                LiveEventType.SESSION_RESUMPTION,
                Msg.builder()
                        .metadata(
                                Map.of(
                                        "live.session.resumption_handle",
                                        handle != null ? handle : "",
                                        "live.session.resumable",
                                        String.valueOf(resumable)))
                        .build(),
                true);
    }

    // ==================== Turn Control Events ====================

    /** Turn complete event */
    public static LiveEvent turnComplete(Msg message) {
        return create(LiveEventType.TURN_COMPLETE, message, true);
    }

    /** Generation complete event */
    public static LiveEvent generationComplete(Msg message) {
        return create(LiveEventType.GENERATION_COMPLETE, message, true);
    }

    /** Response interrupted event */
    public static LiveEvent interrupted() {
        return create(LiveEventType.INTERRUPTED, null, true);
    }

    // ==================== Voice Activity Detection Events ====================

    /** Speech started detection event */
    public static LiveEvent speechStarted() {
        return create(LiveEventType.SPEECH_STARTED, null, true);
    }

    /** Speech stopped detection event */
    public static LiveEvent speechStopped() {
        return create(LiveEventType.SPEECH_STOPPED, null, true);
    }

    // ==================== Tool Calling Events ====================

    /** Tool call event */
    public static LiveEvent toolCall(Msg message, boolean isLast) {
        return create(LiveEventType.TOOL_CALL, message, isLast);
    }

    /** Tool call cancellation event */
    public static LiveEvent toolCallCancellation(Msg message) {
        return create(LiveEventType.TOOL_CALL_CANCELLATION, message, true);
    }

    // ==================== Connection State Events ====================

    /** Connection state change event */
    public static LiveEvent connectionState(String state, String reason, boolean recoverable) {
        return create(
                LiveEventType.CONNECTION_STATE,
                Msg.builder()
                        .metadata(
                                Map.of(
                                        "live.connection.state",
                                        state,
                                        "live.connection.reason",
                                        reason,
                                        "live.connection.recoverable",
                                        String.valueOf(recoverable)))
                        .build(),
                true);
    }

    /** Reconnecting event */
    public static LiveEvent reconnecting(int attempt, int maxAttempts) {
        return create(
                LiveEventType.RECONNECTING,
                Msg.builder()
                        .metadata(
                                Map.of(
                                        "live.connection.attempt",
                                        String.valueOf(attempt),
                                        "live.connection.max_attempts",
                                        String.valueOf(maxAttempts)))
                        .build(),
                true);
    }

    /** Reconnected event */
    public static LiveEvent reconnected() {
        return create(LiveEventType.RECONNECTED, null, true);
    }

    /** Go-away notification event */
    public static LiveEvent goAway(long timeLeftMs) {
        return create(
                LiveEventType.GO_AWAY,
                Msg.builder()
                        .metadata(Map.of("live.go_away.time_left_ms", String.valueOf(timeLeftMs)))
                        .build(),
                true);
    }

    // ==================== Other Events ====================

    /** Error event */
    public static LiveEvent error(String errorType, String errorMessage) {
        return create(
                LiveEventType.ERROR,
                Msg.builder()
                        .metadata(
                                Map.of(
                                        "live.error.type", errorType,
                                        "live.error.message", errorMessage))
                        .build(),
                true);
    }

    /** Error event with LiveErrorType */
    public static LiveEvent error(LiveErrorType errorType, String errorMessage) {
        return error(errorType.name(), errorMessage);
    }

    /** Usage metadata event */
    public static LiveEvent usageMetadata(Msg message) {
        return create(LiveEventType.USAGE_METADATA, message, true);
    }

    /** Unknown event (for debugging, original type stored in metadata) */
    public static LiveEvent unknown(String originalType, Object rawData) {
        // Convert rawData to string and store in metadata for logging
        String rawStr = rawData != null ? rawData.toString() : "";
        return new LiveEvent(
                UUID.randomUUID().toString(),
                LiveEventType.UNKNOWN,
                Msg.builder()
                        .metadata(
                                Map.of(
                                        "live.original_type",
                                        originalType,
                                        "live.raw_preview",
                                        rawStr.length() > 200
                                                ? rawStr.substring(0, 200) + "..."
                                                : rawStr))
                        .build(),
                true,
                Instant.now(),
                null);
    }

    // ==================== Internal Factory Method ====================

    private static LiveEvent create(LiveEventType type, Msg message, boolean isLast) {
        return new LiveEvent(
                UUID.randomUUID().toString(), type, message, isLast, Instant.now(), null);
    }

    // ==================== Convenience Methods ====================

    /** Get metadata value */
    public String getMetadata(String key) {
        if (message == null || message.getMetadata() == null) {
            return null;
        }
        Object value = message.getMetadata().get(key);
        return value != null ? value.toString() : null;
    }

    /** Check if this is an error event */
    public boolean isError() {
        return type == LiveEventType.ERROR;
    }

    /** Check if this is a session ended event */
    public boolean isSessionEnded() {
        return type == LiveEventType.SESSION_ENDED;
    }
}
