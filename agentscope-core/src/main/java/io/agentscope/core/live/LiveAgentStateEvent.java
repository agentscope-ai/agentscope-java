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

import java.time.Instant;

/**
 * LiveAgent state change event.
 *
 * @param previousState the previous state
 * @param currentState the current state
 * @param reason the reason for state change
 * @param reconnectAttempt the reconnection attempt count (only valid for RECONNECTING state)
 * @param error the associated error (optional)
 * @param timestamp the event timestamp
 */
public record LiveAgentStateEvent(
        LiveAgentState previousState,
        LiveAgentState currentState,
        String reason,
        int reconnectAttempt,
        Throwable error,
        Instant timestamp) {

    /**
     * Creates a state change event with current timestamp.
     *
     * @param previousState the previous state
     * @param currentState the current state
     * @param reason the reason for state change
     * @param reconnectAttempt the reconnection attempt count
     * @param error the associated error
     */
    public LiveAgentStateEvent(
            LiveAgentState previousState,
            LiveAgentState currentState,
            String reason,
            int reconnectAttempt,
            Throwable error) {
        this(previousState, currentState, reason, reconnectAttempt, error, Instant.now());
    }

    /**
     * Creates a simple state change event.
     *
     * @param previousState the previous state
     * @param currentState the current state
     * @param reason the reason for state change
     * @return a new state event
     */
    public static LiveAgentStateEvent of(
            LiveAgentState previousState, LiveAgentState currentState, String reason) {
        return new LiveAgentStateEvent(previousState, currentState, reason, 0, null);
    }

    /**
     * Creates a reconnecting event.
     *
     * @param previousState the previous state
     * @param attempt the reconnection attempt number
     * @param reason the reason for reconnection
     * @return a new reconnecting event
     */
    public static LiveAgentStateEvent reconnecting(
            LiveAgentState previousState, int attempt, String reason) {
        return new LiveAgentStateEvent(
                previousState, LiveAgentState.RECONNECTING, reason, attempt, null);
    }

    /**
     * Creates an error event.
     *
     * @param previousState the previous state
     * @param currentState the current state
     * @param error the error that occurred
     * @return a new error event
     */
    public static LiveAgentStateEvent error(
            LiveAgentState previousState, LiveAgentState currentState, Throwable error) {
        return new LiveAgentStateEvent(previousState, currentState, error.getMessage(), 0, error);
    }

    /**
     * Checks if this is an error event.
     *
     * @return true if this event represents an error
     */
    public boolean isError() {
        return error != null || currentState == LiveAgentState.FAILED;
    }

    /**
     * Checks if this is a recovery-related event.
     *
     * @return true if this event is related to recovery
     */
    public boolean isRecoveryEvent() {
        return currentState == LiveAgentState.RECONNECTING
                || currentState == LiveAgentState.RESUMING
                || currentState == LiveAgentState.RECOVERED;
    }
}
