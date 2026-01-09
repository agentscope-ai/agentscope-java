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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LiveAgentState Tests")
class LiveAgentStateTest {

    @Test
    @DisplayName("FAILED and CLOSED should be terminal states")
    void failedAndClosedShouldBeTerminalStates() {
        assertTrue(LiveAgentState.FAILED.isTerminal());
        assertTrue(LiveAgentState.CLOSED.isTerminal());

        assertFalse(LiveAgentState.CONNECTED.isTerminal());
        assertFalse(LiveAgentState.DISCONNECTED.isTerminal());
        assertFalse(LiveAgentState.RECONNECTING.isTerminal());
        assertFalse(LiveAgentState.RESUMING.isTerminal());
        assertFalse(LiveAgentState.RECOVERED.isTerminal());
    }

    @Test
    @DisplayName("CONNECTED and RECOVERED should allow sending")
    void connectedAndRecoveredShouldAllowSending() {
        assertTrue(LiveAgentState.CONNECTED.canSend());
        assertTrue(LiveAgentState.RECOVERED.canSend());

        assertFalse(LiveAgentState.DISCONNECTED.canSend());
        assertFalse(LiveAgentState.RECONNECTING.canSend());
        assertFalse(LiveAgentState.RESUMING.canSend());
        assertFalse(LiveAgentState.FAILED.canSend());
        assertFalse(LiveAgentState.CLOSED.canSend());
    }

    @Test
    @DisplayName("RECONNECTING and RESUMING should be recovering states")
    void reconnectingAndResumingShouldBeRecoveringStates() {
        assertTrue(LiveAgentState.RECONNECTING.isRecovering());
        assertTrue(LiveAgentState.RESUMING.isRecovering());

        assertFalse(LiveAgentState.CONNECTED.isRecovering());
        assertFalse(LiveAgentState.DISCONNECTED.isRecovering());
        assertFalse(LiveAgentState.RECOVERED.isRecovering());
        assertFalse(LiveAgentState.FAILED.isRecovering());
        assertFalse(LiveAgentState.CLOSED.isRecovering());
    }
}
