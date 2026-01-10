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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LiveAgentStateEvent Tests")
class LiveAgentStateEventTest {

    @Test
    @DisplayName("Should create simple state event")
    void shouldCreateSimpleStateEvent() {
        LiveAgentStateEvent event =
                LiveAgentStateEvent.of(
                        LiveAgentState.DISCONNECTED,
                        LiveAgentState.CONNECTED,
                        "Connection established");

        assertEquals(LiveAgentState.DISCONNECTED, event.previousState());
        assertEquals(LiveAgentState.CONNECTED, event.currentState());
        assertEquals("Connection established", event.reason());
        assertEquals(0, event.reconnectAttempt());
        assertNull(event.error());
        assertNotNull(event.timestamp());
    }

    @Test
    @DisplayName("Should create reconnecting event")
    void shouldCreateReconnectingEvent() {
        LiveAgentStateEvent event =
                LiveAgentStateEvent.reconnecting(LiveAgentState.DISCONNECTED, 3, "Attempt 3 of 5");

        assertEquals(LiveAgentState.DISCONNECTED, event.previousState());
        assertEquals(LiveAgentState.RECONNECTING, event.currentState());
        assertEquals(3, event.reconnectAttempt());
        assertTrue(event.isRecoveryEvent());
    }

    @Test
    @DisplayName("Should create error event")
    void shouldCreateErrorEvent() {
        Exception error = new RuntimeException("Connection lost");
        LiveAgentStateEvent event =
                LiveAgentStateEvent.error(LiveAgentState.CONNECTED, LiveAgentState.FAILED, error);

        assertEquals(LiveAgentState.FAILED, event.currentState());
        assertEquals(error, event.error());
        assertTrue(event.isError());
    }

    @Test
    @DisplayName("Should identify recovery events")
    void shouldIdentifyRecoveryEvents() {
        assertTrue(
                LiveAgentStateEvent.of(LiveAgentState.DISCONNECTED, LiveAgentState.RECONNECTING, "")
                        .isRecoveryEvent());
        assertTrue(
                LiveAgentStateEvent.of(LiveAgentState.RECONNECTING, LiveAgentState.RESUMING, "")
                        .isRecoveryEvent());
        assertTrue(
                LiveAgentStateEvent.of(LiveAgentState.RESUMING, LiveAgentState.RECOVERED, "")
                        .isRecoveryEvent());

        assertFalse(
                LiveAgentStateEvent.of(LiveAgentState.DISCONNECTED, LiveAgentState.CONNECTED, "")
                        .isRecoveryEvent());
    }

    @Test
    @DisplayName("Should identify error events by FAILED state")
    void shouldIdentifyErrorEventsByFailedState() {
        LiveAgentStateEvent event =
                LiveAgentStateEvent.of(
                        LiveAgentState.RECONNECTING, LiveAgentState.FAILED, "Max retries reached");

        assertTrue(event.isError());
        assertNull(event.error());
    }

    @Test
    @DisplayName("Should identify error events by error presence")
    void shouldIdentifyErrorEventsByErrorPresence() {
        Exception error = new RuntimeException("Network error");
        LiveAgentStateEvent event =
                LiveAgentStateEvent.error(
                        LiveAgentState.CONNECTED, LiveAgentState.DISCONNECTED, error);

        assertTrue(event.isError());
        assertEquals(error, event.error());
    }
}
