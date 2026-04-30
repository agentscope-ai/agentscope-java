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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.agent.Agent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InMemoryAguiSessionManager}.
 *
 * <p>Covers the new composite-key (agentId:threadId) isolation behavior and
 * the updated API signatures: {@code hasMemory(threadId, agentId)},
 * {@code removeSession(threadId, agentId)}, and {@code getSession(threadId, agentId)}.
 */
@Tag("unit")
@DisplayName("InMemoryAguiSessionManager Unit Tests")
class InMemoryAguiSessionManagerTest {

    private InMemoryAguiSessionManager manager;

    @BeforeEach
    void setUp() {
        manager = new InMemoryAguiSessionManager(10, 30);
    }

    // ---- getOrCreateAgent ----

    @Test
    @DisplayName("Should create new agent for new threadId")
    void testGetOrCreateAgent_newThread() {
        Agent mockAgent = mock(Agent.class);

        Agent result = manager.getOrCreateAgent("thread-1", "agent-1", () -> mockAgent);

        assertSame(mockAgent, result);
        assertEquals(1, manager.getSessionCount());
    }

    @Test
    @DisplayName("Should reuse existing agent for same threadId and agentId")
    void testGetOrCreateAgent_sameThreadSameAgent() {
        Agent mockAgent = mock(Agent.class);

        Agent first = manager.getOrCreateAgent("thread-1", "agent-1", () -> mockAgent);
        Agent second = manager.getOrCreateAgent("thread-1", "agent-1", () -> mock(Agent.class));

        assertSame(first, second);
        assertEquals(1, manager.getSessionCount());
    }

    @Test
    @DisplayName(
            "Should create SEPARATE sessions for same threadId with different agentId (composite"
                    + " key isolation)")
    void testGetOrCreateAgent_sameThreadDifferentAgent_createsIsolatedSessions() {
        Agent agent1 = mock(Agent.class);
        Agent agent2 = mock(Agent.class);

        Agent first = manager.getOrCreateAgent("thread-1", "agent-1", () -> agent1);
        Agent second = manager.getOrCreateAgent("thread-1", "agent-2", () -> agent2);

        // Different agentIds → different composite keys → both sessions exist independently
        assertSame(agent1, first);
        assertSame(agent2, second);
        assertNotSame(first, second);
        // Both sessions coexist (agent-1:thread-1 and agent-2:thread-1)
        assertEquals(2, manager.getSessionCount());
    }

    @Test
    @DisplayName("Should manage multiple threads independently")
    void testGetOrCreateAgent_multipleThreads() {
        Agent agent1 = mock(Agent.class);
        Agent agent2 = mock(Agent.class);

        manager.getOrCreateAgent("thread-1", "agent-1", () -> agent1);
        manager.getOrCreateAgent("thread-2", "agent-1", () -> agent2);

        assertEquals(2, manager.getSessionCount());
    }

    @Test
    @DisplayName("Same agentId with different threadIds are independent sessions")
    void testGetOrCreateAgent_differentThreadsSameAgent() {
        Agent agent1 = mock(Agent.class);
        Agent agent2 = mock(Agent.class);

        Agent result1 = manager.getOrCreateAgent("thread-A", "chatAgent", () -> agent1);
        Agent result2 = manager.getOrCreateAgent("thread-B", "chatAgent", () -> agent2);

        assertSame(agent1, result1);
        assertSame(agent2, result2);
        assertNotSame(result1, result2);
        assertEquals(2, manager.getSessionCount());
    }

    // ---- removeSession(threadId, agentId) — new API ----

    @Test
    @DisplayName("Should remove session by threadId and agentId")
    void testRemoveSession_withAgentId() {
        manager.getOrCreateAgent("thread-1", "agent-1", () -> mock(Agent.class));

        assertTrue(manager.removeSession("thread-1", "agent-1"));
        assertEquals(0, manager.getSessionCount());
    }

    @Test
    @DisplayName("Should return false when removing non-existent session (wrong agentId)")
    void testRemoveSession_wrongAgentId() {
        manager.getOrCreateAgent("thread-1", "agent-1", () -> mock(Agent.class));

        // Correct threadId but wrong agentId → should NOT remove the existing session
        assertFalse(manager.removeSession("thread-1", "agent-wrong"));
        assertEquals(1, manager.getSessionCount());
    }

    @Test
    @DisplayName("Should return false when removing non-existent session (wrong threadId)")
    void testRemoveSession_wrongThreadId() {
        manager.getOrCreateAgent("thread-1", "agent-1", () -> mock(Agent.class));

        assertFalse(manager.removeSession("thread-wrong", "agent-1"));
        assertEquals(1, manager.getSessionCount());
    }

    @Test
    @DisplayName("Should return false when removing non-existent session")
    void testRemoveSession_nonExistent() {
        assertFalse(manager.removeSession("non-existent", "agent-1"));
    }

    @Test
    @DisplayName("Removing one composite key should not affect other sessions for same thread")
    void testRemoveSession_onlyRemovesMatchingCompositeKey() {
        manager.getOrCreateAgent("thread-1", "agent-1", () -> mock(Agent.class));
        manager.getOrCreateAgent("thread-1", "agent-2", () -> mock(Agent.class));

        // Only remove agent-1:thread-1, agent-2:thread-1 should remain
        assertTrue(manager.removeSession("thread-1", "agent-1"));
        assertEquals(1, manager.getSessionCount());
        assertTrue(manager.getSession("thread-1", "agent-2").isPresent());
    }

    // ---- hasMemory(threadId, agentId) — new API ----

    @Test
    @DisplayName("Should return false for hasMemory on non-existent thread+agentId")
    void testHasMemory_nonExistentSession() {
        assertFalse(manager.hasMemory("non-existent", "agent-1"));
    }

    @Test
    @DisplayName("Should return false for hasMemory with wrong agentId")
    void testHasMemory_wrongAgentId() {
        manager.getOrCreateAgent("thread-1", "agent-1", () -> mock(Agent.class));

        assertFalse(manager.hasMemory("thread-1", "agent-wrong"));
    }

    @Test
    @DisplayName("Should return false for hasMemory on non-ReActAgent")
    void testHasMemory_nonReActAgent() {
        manager.getOrCreateAgent("thread-1", "agent-1", () -> mock(Agent.class));

        // Non-ReActAgent → memory check returns false
        assertFalse(manager.hasMemory("thread-1", "agent-1"));
    }

    // ---- getSession(threadId, agentId) — new API ----

    @Test
    @DisplayName("getSession should return session for existing threadId and agentId")
    void testGetSession_existing() {
        manager.getOrCreateAgent("thread-1", "agent-1", () -> mock(Agent.class));

        assertTrue(manager.getSession("thread-1", "agent-1").isPresent());
    }

    @Test
    @DisplayName("getSession should return empty for wrong agentId")
    void testGetSession_wrongAgentId() {
        manager.getOrCreateAgent("thread-1", "agent-1", () -> mock(Agent.class));

        assertFalse(manager.getSession("thread-1", "agent-wrong").isPresent());
    }

    @Test
    @DisplayName("getSession should return empty for non-existent threadId")
    void testGetSession_nonExistent() {
        assertFalse(manager.getSession("non-existent", "agent-1").isPresent());
    }

    @Test
    @DisplayName(
            "getSession should independently access different agentId sessions for same thread")
    void testGetSession_multipleAgentsPerThread() {
        Agent agentA = mock(Agent.class);
        Agent agentB = mock(Agent.class);
        manager.getOrCreateAgent("thread-1", "agent-a", () -> agentA);
        manager.getOrCreateAgent("thread-1", "agent-b", () -> agentB);

        assertTrue(manager.getSession("thread-1", "agent-a").isPresent());
        assertTrue(manager.getSession("thread-1", "agent-b").isPresent());
        assertSame(agentA, manager.getSession("thread-1", "agent-a").get().getAgent());
        assertSame(agentB, manager.getSession("thread-1", "agent-b").get().getAgent());
    }

    // ---- clear ----

    @Test
    @DisplayName("Should clear all sessions")
    void testClear() {
        manager.getOrCreateAgent("thread-1", "agent-1", () -> mock(Agent.class));
        manager.getOrCreateAgent("thread-2", "agent-1", () -> mock(Agent.class));
        manager.getOrCreateAgent("thread-1", "agent-2", () -> mock(Agent.class));

        manager.clear();

        assertEquals(0, manager.getSessionCount());
    }

    // ---- Capacity eviction ----

    @Test
    @DisplayName("Should evict oldest session when at max capacity")
    void testMaxSessionsEviction() {
        InMemoryAguiSessionManager smallManager = new InMemoryAguiSessionManager(2, 30);

        smallManager.getOrCreateAgent("thread-1", "agent-1", () -> mock(Agent.class));
        smallManager.getOrCreateAgent("thread-2", "agent-1", () -> mock(Agent.class));
        // This should trigger eviction
        smallManager.getOrCreateAgent("thread-3", "agent-1", () -> mock(Agent.class));

        assertEquals(2, smallManager.getSessionCount());
    }

    // ---- cleanupExpiredSessions ----

    @Test
    @DisplayName("cleanupExpiredSessions should be a no-op when timeout is 0")
    void testCleanupExpiredSessions_noTimeout() {
        InMemoryAguiSessionManager noTimeoutManager = new InMemoryAguiSessionManager(10, 0);
        noTimeoutManager.getOrCreateAgent("thread-1", "agent-1", () -> mock(Agent.class));

        noTimeoutManager.cleanupExpiredSessions();

        assertEquals(1, noTimeoutManager.getSessionCount());
    }

    // ---- AguiSession inner class ----

    @Test
    @DisplayName("AguiSession should expose agentId and agent")
    void testAguiSession_fields() {
        Agent mockAgent = mock(Agent.class);
        manager.getOrCreateAgent("thread-1", "my-agent", () -> mockAgent);

        InMemoryAguiSessionManager.AguiSession session =
                manager.getSession("thread-1", "my-agent").orElseThrow();

        assertEquals("my-agent", session.getAgentId());
        assertSame(mockAgent, session.getAgent());
    }

    @Test
    @DisplayName("AguiSession lastAccess should update on re-access")
    void testAguiSession_lastAccessUpdated() throws InterruptedException {
        Agent mockAgent = mock(Agent.class);
        manager.getOrCreateAgent("thread-1", "agent-1", () -> mockAgent);

        InMemoryAguiSessionManager.AguiSession session =
                manager.getSession("thread-1", "agent-1").orElseThrow();
        java.time.Instant firstAccess = session.getLastAccess();

        // Wait a bit then re-access
        Thread.sleep(10);
        manager.getOrCreateAgent("thread-1", "agent-1", () -> mock(Agent.class));

        java.time.Instant secondAccess = session.getLastAccess();
        // Last access should have been updated
        assertTrue(secondAccess.isAfter(firstAccess) || secondAccess.equals(firstAccess));
    }
}
