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
package io.agentscope.core.live.session;

/**
 * Connection state change event.
 *
 * <p>Emitted when the underlying WebSocket connection state changes.
 */
public record ConnectionStateEvent(
        /** Previous state */
        ConnectionState previousState,
        /** Current state */
        ConnectionState currentState,
        /** Reason for state change */
        String reason,
        /** Associated error if any */
        Throwable error) {

    /**
     * Create a connected event.
     *
     * @return connection established event
     */
    public static ConnectionStateEvent connected() {
        return new ConnectionStateEvent(
                ConnectionState.CONNECTING,
                ConnectionState.CONNECTED,
                "Connection established",
                null);
    }

    /**
     * Create a disconnected event.
     *
     * @param reason reason for disconnection
     * @param error associated error
     * @return disconnection event
     */
    public static ConnectionStateEvent disconnected(String reason, Throwable error) {
        return new ConnectionStateEvent(
                ConnectionState.CONNECTED, ConnectionState.DISCONNECTED, reason, error);
    }

    /**
     * Create a closed event.
     *
     * @return connection closed event
     */
    public static ConnectionStateEvent closed() {
        return new ConnectionStateEvent(
                ConnectionState.CONNECTED,
                ConnectionState.CLOSED,
                "Connection closed by client",
                null);
    }

    /**
     * Create a reconnecting event.
     *
     * @param reason reason for reconnection
     * @return reconnecting event
     */
    public static ConnectionStateEvent reconnecting(String reason) {
        return new ConnectionStateEvent(
                ConnectionState.DISCONNECTED, ConnectionState.RECONNECTING, reason, null);
    }

    /**
     * Check if this is a disconnected event.
     *
     * @return true if current state is DISCONNECTED
     */
    public boolean isDisconnected() {
        return currentState == ConnectionState.DISCONNECTED;
    }

    /**
     * Check if this event has an associated error.
     *
     * @return true if error is not null
     */
    public boolean hasError() {
        return error != null;
    }
}
