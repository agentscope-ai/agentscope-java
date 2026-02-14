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
package io.agentscope.core.live;

/**
 * LiveAgent state enumeration.
 *
 * <p>State transitions:
 *
 * <pre>
 * DISCONNECTED → CONNECTING → CONNECTED → DISCONNECTED → RECONNECTING → RESUMING → RECOVERED
 *                                 │                           │
 *                                 └──────────────────────────►│
 *                                                             ▼
 *                                                          FAILED → CLOSED
 * </pre>
 */
public enum LiveAgentState {

    /** Connecting - session created, handshake in progress. */
    CONNECTING,

    /** Connected - can send and receive messages normally. */
    CONNECTED,

    /** Disconnected - connection is lost, may trigger reconnection. */
    DISCONNECTED,

    /** Reconnecting - attempting to reconnect. */
    RECONNECTING,

    /** Resuming - using native recovery mechanism (Gemini sessionResumption / Doubao dialog_id). */
    RESUMING,

    /** Recovered - session has been successfully recovered. */
    RECOVERED,

    /** Failed - reconnection failed, cannot recover. */
    FAILED,

    /** Closed - session is closed (terminal state). */
    CLOSED;

    /**
     * Checks if this is a terminal state.
     *
     * @return true if this is a terminal state (FAILED or CLOSED)
     */
    public boolean isTerminal() {
        return this == FAILED || this == CLOSED;
    }

    /**
     * Checks if messages can be sent in this state.
     *
     * @return true if messages can be sent
     */
    public boolean canSend() {
        return this == CONNECTED || this == RECOVERED;
    }

    /**
     * Checks if the agent is in a recovery state.
     *
     * @return true if reconnecting or resuming
     */
    public boolean isRecovering() {
        return this == RECONNECTING || this == RESUMING;
    }
}
