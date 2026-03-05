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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ConnectionStateEvent Tests")
class ConnectionStateEventTest {

    @Test
    @DisplayName("Should create connected event")
    void shouldCreateConnectedEvent() {
        ConnectionStateEvent event = ConnectionStateEvent.connected();

        assertEquals(ConnectionState.CONNECTING, event.previousState());
        assertEquals(ConnectionState.CONNECTED, event.currentState());
        assertEquals("Connection established", event.reason());
        assertFalse(event.isDisconnected());
        assertFalse(event.hasError());
        assertNull(event.error());
    }

    @Test
    @DisplayName("Should create disconnected event with error")
    void shouldCreateDisconnectedEventWithError() {
        Exception error = new RuntimeException("Connection lost");
        ConnectionStateEvent event = ConnectionStateEvent.disconnected("Network error", error);

        assertEquals(ConnectionState.CONNECTED, event.previousState());
        assertEquals(ConnectionState.DISCONNECTED, event.currentState());
        assertEquals("Network error", event.reason());
        assertTrue(event.isDisconnected());
        assertTrue(event.hasError());
        assertSame(error, event.error());
    }

    @Test
    @DisplayName("Should create disconnected event without error")
    void shouldCreateDisconnectedEventWithoutError() {
        ConnectionStateEvent event = ConnectionStateEvent.disconnected("Normal close", null);

        assertEquals(ConnectionState.CONNECTED, event.previousState());
        assertEquals(ConnectionState.DISCONNECTED, event.currentState());
        assertEquals("Normal close", event.reason());
        assertTrue(event.isDisconnected());
        assertFalse(event.hasError());
        assertNull(event.error());
    }

    @Test
    @DisplayName("Should create closed event")
    void shouldCreateClosedEvent() {
        ConnectionStateEvent event = ConnectionStateEvent.closed();

        assertEquals(ConnectionState.CONNECTED, event.previousState());
        assertEquals(ConnectionState.CLOSED, event.currentState());
        assertEquals("Connection closed by client", event.reason());
        assertFalse(event.isDisconnected());
        assertFalse(event.hasError());
        assertNull(event.error());
    }

    @Test
    @DisplayName("Should create reconnecting event")
    void shouldCreateReconnectingEvent() {
        ConnectionStateEvent event = ConnectionStateEvent.reconnecting("Attempting to reconnect");

        assertEquals(ConnectionState.DISCONNECTED, event.previousState());
        assertEquals(ConnectionState.RECONNECTING, event.currentState());
        assertEquals("Attempting to reconnect", event.reason());
        assertFalse(event.isDisconnected());
        assertFalse(event.hasError());
        assertNull(event.error());
    }

    @Test
    @DisplayName("Should correctly identify disconnected state")
    void shouldIdentifyDisconnectedState() {
        ConnectionStateEvent disconnected =
                new ConnectionStateEvent(
                        ConnectionState.CONNECTED, ConnectionState.DISCONNECTED, "Test", null);
        ConnectionStateEvent connected = ConnectionStateEvent.connected();

        assertTrue(disconnected.isDisconnected());
        assertFalse(connected.isDisconnected());
    }

    @Test
    @DisplayName("Should correctly identify error presence")
    void shouldIdentifyErrorPresence() {
        Exception error = new RuntimeException("Test error");
        ConnectionStateEvent withError =
                new ConnectionStateEvent(
                        ConnectionState.CONNECTED,
                        ConnectionState.DISCONNECTED,
                        "Error occurred",
                        error);
        ConnectionStateEvent withoutError =
                new ConnectionStateEvent(
                        ConnectionState.CONNECTED, ConnectionState.DISCONNECTED, "Normal", null);

        assertTrue(withError.hasError());
        assertFalse(withoutError.hasError());
    }

    @Test
    @DisplayName("Should support all connection states")
    void shouldSupportAllConnectionStates() {
        // Test all possible state transitions
        ConnectionStateEvent[] events = {
            new ConnectionStateEvent(
                    ConnectionState.DISCONNECTED,
                    ConnectionState.CONNECTING,
                    "Starting connection",
                    null),
            new ConnectionStateEvent(
                    ConnectionState.CONNECTING, ConnectionState.CONNECTED, "Connected", null),
            new ConnectionStateEvent(
                    ConnectionState.CONNECTED,
                    ConnectionState.DISCONNECTED,
                    "Lost connection",
                    null),
            new ConnectionStateEvent(
                    ConnectionState.DISCONNECTED,
                    ConnectionState.RECONNECTING,
                    "Reconnecting",
                    null),
            new ConnectionStateEvent(
                    ConnectionState.RECONNECTING, ConnectionState.CONNECTED, "Reconnected", null),
            new ConnectionStateEvent(
                    ConnectionState.CONNECTED, ConnectionState.CLOSED, "Closed", null)
        };

        for (ConnectionStateEvent event : events) {
            assertNotNull(event.previousState());
            assertNotNull(event.currentState());
            assertNotNull(event.reason());
        }
    }
}
