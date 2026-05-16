/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.spring.boot.agui.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.state.StateModule;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SessionAwareAguiSessionManager}.
 */
@Tag("unit")
@DisplayName("SessionAwareAguiSessionManager Unit Tests")
class SessionAwareAguiSessionManagerTest {

    private Session mockSession;
    private SessionAwareAguiSessionManager manager;

    @BeforeEach
    void setUp() {
        mockSession = mock(Session.class);
        manager = new SessionAwareAguiSessionManager(mockSession);
    }

    @Test
    @DisplayName("Should throw NullPointerException when session is null")
    void testConstructor_nullSession() {
        assertThrows(NullPointerException.class, () -> new SessionAwareAguiSessionManager(null));
    }

    @Test
    @DisplayName("Should always create new agent via factory")
    void testGetOrCreateAgent_alwaysCreatesNew() {
        Agent agent1 = mock(Agent.class);
        Agent agent2 = mock(Agent.class);

        Agent first = manager.getOrCreateAgent("thread-1", "agent-1", () -> agent1);
        Agent second = manager.getOrCreateAgent("thread-1", "agent-1", () -> agent2);

        assertSame(agent1, first);
        assertSame(agent2, second);
        assertNotSame(first, second);
    }

    @Test
    @DisplayName("Should call loadIfExists on StateModule agents during getOrCreateAgent")
    void testGetOrCreateAgent_loadsStateForStatefulAgent() {
        StatefulAgent statefulAgent = mock(StatefulAgent.class);
        when(statefulAgent.loadIfExists(any(Session.class), any(SessionKey.class)))
                .thenReturn(true);

        Agent result = manager.getOrCreateAgent("thread-1", "agent-1", () -> statefulAgent);

        assertSame(statefulAgent, result);
        // SessionAwareAguiSessionManager uses composite key "agentId:threadId"
        verify(statefulAgent).loadIfExists(mockSession, SimpleSessionKey.of("agent-1:thread-1"));
    }

    @Test
    @DisplayName("Should not call loadIfExists on plain Agent (non-StateModule)")
    void testGetOrCreateAgent_skipsLoadForPlainAgent() {
        Agent plainAgent = mock(Agent.class);

        Agent result = manager.getOrCreateAgent("thread-1", "agent-1", () -> plainAgent);

        assertSame(plainAgent, result);
        // No loadIfExists call since Agent doesn't implement StateModule
    }

    @Test
    @DisplayName("Should call saveTo on StateModule agents via saveAgent")
    void testSaveAgent_savesStateForStatefulAgent() {
        StatefulAgent statefulAgent = mock(StatefulAgent.class);

        manager.saveAgent("thread-1", "agent-1", statefulAgent);

        // SessionAwareAguiSessionManager uses composite key "agentId:threadId"
        verify(statefulAgent).saveTo(mockSession, SimpleSessionKey.of("agent-1:thread-1"));
    }

    @Test
    @DisplayName("Should be a no-op for plain Agent (non-StateModule) via saveAgent")
    void testSaveAgent_skipsForPlainAgent() {
        Agent plainAgent = mock(Agent.class);

        manager.saveAgent("thread-1", "agent-1", plainAgent);

        // No saveTo call since Agent doesn't implement StateModule
    }

    @Test
    @DisplayName("Should delegate hasMemory to session.exists using composite key")
    void testHasMemory_exists() {
        // SessionAwareAguiSessionManager uses composite key "agentId:threadId"
        when(mockSession.exists(SimpleSessionKey.of("agent-1:thread-1"))).thenReturn(true);

        assertTrue(manager.hasMemory("thread-1", "agent-1"));
        verify(mockSession).exists(SimpleSessionKey.of("agent-1:thread-1"));
    }

    @Test
    @DisplayName("Should return false for hasMemory when session does not exist")
    void testHasMemory_notExists() {
        when(mockSession.exists(SimpleSessionKey.of("agent-1:thread-1"))).thenReturn(false);

        assertFalse(manager.hasMemory("thread-1", "agent-1"));
    }

    @Test
    @DisplayName("Should delete session when it exists using composite key")
    void testRemoveSession_exists() {
        // SessionAwareAguiSessionManager uses composite key "agentId:threadId"
        when(mockSession.exists(SimpleSessionKey.of("agent-1:thread-1"))).thenReturn(true);

        assertTrue(manager.removeSession("thread-1", "agent-1"));
        verify(mockSession).delete(SimpleSessionKey.of("agent-1:thread-1"));
    }

    @Test
    @DisplayName("Should return false when removing non-existent session")
    void testRemoveSession_notExists() {
        when(mockSession.exists(SimpleSessionKey.of("agent-1:thread-1"))).thenReturn(false);

        assertFalse(manager.removeSession("thread-1", "agent-1"));
        verify(mockSession, never()).delete(any());
    }

    @Test
    @DisplayName("cleanupExpiredSessions should be a no-op")
    void testCleanupExpiredSessions() {
        manager.cleanupExpiredSessions();

        // Should not interact with session at all
        verify(mockSession, never()).delete(any());
        verify(mockSession, never()).exists(any());
    }

    @Test
    @DisplayName("Should return count from session.listSessionKeys")
    void testGetSessionCount() {
        Set keys = new HashSet<>();
        keys.add(SimpleSessionKey.of("thread-1"));
        keys.add(SimpleSessionKey.of("thread-2"));
        when(mockSession.listSessionKeys()).thenReturn(keys);

        assertEquals(2, manager.getSessionCount());
    }

    @Test
    @DisplayName("Should return 0 when no sessions exist")
    void testGetSessionCount_empty() {
        when(mockSession.listSessionKeys()).thenReturn(Collections.emptySet());

        assertEquals(0, manager.getSessionCount());
    }

    @Test
    @DisplayName("Should delete all session keys on clear")
    void testClear() {
        Set keys = new HashSet<>();
        keys.add(SimpleSessionKey.of("thread-1"));
        keys.add(SimpleSessionKey.of("thread-2"));
        when(mockSession.listSessionKeys()).thenReturn(keys);

        manager.clear();

        verify(mockSession).delete(SimpleSessionKey.of("thread-1"));
        verify(mockSession).delete(SimpleSessionKey.of("thread-2"));
        verify(mockSession, times(2)).delete(any());
    }

    @Test
    @DisplayName("Should handle empty keys on clear")
    void testClear_empty() {
        when(mockSession.listSessionKeys()).thenReturn(Collections.emptySet());

        manager.clear();

        verify(mockSession, never()).delete(any());
    }

    /** Helper interface for mocking an Agent that also implements StateModule. */
    interface StatefulAgent extends Agent, StateModule {}
}
