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
 * Error types for real-time conversation.
 *
 * <p>Unified error type enumeration that maps error codes from various providers.
 */
public enum LiveErrorType {

    // ========== Generic Errors ==========

    /** WebSocket connection failed */
    CONNECTION_ERROR,

    /** Invalid or expired API key */
    AUTHENTICATION_ERROR,

    /** Invalid configuration parameters */
    CONFIGURATION_ERROR,

    /** Message format error */
    PROTOCOL_ERROR,

    /** Rate limit exceeded (QPM/TPM) */
    RATE_LIMIT_ERROR,

    /** Server internal error */
    SERVER_ERROR,

    /** Operation timeout */
    TIMEOUT_ERROR,

    /** Audio format error */
    AUDIO_FORMAT_ERROR,

    /** Session expired */
    SESSION_EXPIRED,

    /** Interruption handling failed */
    INTERRUPTION_ERROR,

    // ========== Doubao Specific Errors ==========

    /** Empty audio packet (45000002) */
    DOUBAO_EMPTY_AUDIO,

    /** 10-minute silence timeout (45000003) */
    DOUBAO_SILENCE_TIMEOUT,

    /** Server processing error (55000001) */
    DOUBAO_SERVER_PROCESSING,

    /** Downstream service connection failed (55000030) */
    DOUBAO_SERVICE_UNAVAILABLE,

    /** Downstream returned error (55002070) */
    DOUBAO_AUDIO_FLOW_ERROR,

    // ========== Gemini Specific Errors ==========

    /** Session duration exceeded (audio-only 15min, audio-video 2min) */
    GEMINI_SESSION_DURATION_EXCEEDED,

    /** Connection duration exceeded (approximately 10min) */
    GEMINI_CONNECTION_TIMEOUT,

    // ========== OpenAI Specific Errors ==========

    /** VAD idle timeout */
    OPENAI_VAD_IDLE_TIMEOUT
}
