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

/**
 * Live event types for real-time conversation.
 *
 * <p>Unified event type enumeration that maps server events from various providers.
 * For detailed mapping relationships, refer to design document 07-event-mapping.md.
 */
public enum LiveEventType {

    // ========== Session Lifecycle ==========

    /** Session created successfully */
    SESSION_CREATED,

    /** Session configuration updated successfully */
    SESSION_UPDATED,

    /** Session ended (non-recoverable) */
    SESSION_ENDED,

    /** Session resumption information updated (Gemini/Doubao) */
    SESSION_RESUMPTION,

    // ========== Connection State ==========

    /** Connection state changed */
    CONNECTION_STATE,

    /** Reconnecting in progress */
    RECONNECTING,

    /** Reconnection successful */
    RECONNECTED,

    /** Go-away notification (Gemini goAway) */
    GO_AWAY,

    // ========== Audio/Text Output ==========

    /** Audio delta chunk */
    AUDIO_DELTA,

    /** Text delta chunk */
    TEXT_DELTA,

    /** Input transcription (user speech to text) */
    INPUT_TRANSCRIPTION,

    /** Output transcription (model speech to text) */
    OUTPUT_TRANSCRIPTION,

    // ========== Voice Activity Detection ==========

    /** User started speaking detected */
    SPEECH_STARTED,

    /** User stopped speaking detected */
    SPEECH_STOPPED,

    // ========== Tool Calling ==========

    /** Tool call request */
    TOOL_CALL,

    /** Tool call cancellation (when user interrupts) */
    TOOL_CALL_CANCELLATION,

    // ========== Turn Control ==========

    /** Model response completed */
    TURN_COMPLETE,

    /** Generation completed (earlier than turn complete, Gemini specific) */
    GENERATION_COMPLETE,

    /** Response interrupted */
    INTERRUPTED,

    // ========== Others ==========

    /** Usage metadata statistics */
    USAGE_METADATA,

    /** Error occurred */
    ERROR,

    /** Unknown event (for debugging) */
    UNKNOWN
}
