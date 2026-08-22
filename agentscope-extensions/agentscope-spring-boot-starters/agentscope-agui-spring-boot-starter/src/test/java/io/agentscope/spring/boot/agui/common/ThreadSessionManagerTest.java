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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.state.AgentState;
import io.agentscope.spring.boot.agui.common.ThreadSessionManager.ThreadSession;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ThreadSessionManager}. */
@Tag("unit")
@DisplayName("ThreadSessionManager Unit Tests")
class ThreadSessionManagerTest {

    @Test
    void getOrCreateAgentCreatesAndReusesSession() {
        ThreadSessionManager manager = new ThreadSessionManager(10, 30);
        AtomicInteger creations = new AtomicInteger();
        Agent first = mock(Agent.class);
        Agent second = mock(Agent.class);

        Agent created =
                manager.getOrCreateAgent(
                        "thread-1",
                        "agent-a",
                        () -> {
                            creations.incrementAndGet();
                            return first;
                        });
        Agent reused =
                manager.getOrCreateAgent(
                        "thread-1",
                        "agent-a",
                        () -> {
                            creations.incrementAndGet();
                            return second;
                        });

        assertSame(first, created);
        assertSame(first, reused);
        assertEquals(1, creations.get());
        assertEquals(1, manager.getSessionCount());
    }

    @Test
    void getOrCreateAgentReplacesAgentAndPreservesMetadata() {
        ThreadSessionManager manager = new ThreadSessionManager(10, 30);
        Agent oldAgent = mock(Agent.class);
        Agent newAgent = mock(Agent.class);

        manager.getOrCreateAgent("thread-1", "agent-a", () -> oldAgent);
        ThreadSession session = manager.getSession("thread-1").orElseThrow();
        session.setName("Orders");
        session.setArchived(true);

        Agent replaced = manager.getOrCreateAgent("thread-1", "agent-b", () -> newAgent);
        ThreadSession updated = manager.getSession("thread-1").orElseThrow();

        assertSame(newAgent, replaced);
        assertEquals("agent-b", updated.getAgentId());
        assertEquals("Orders", updated.getName());
        assertTrue(updated.isArchived());
        assertNotSame(session, updated);
    }

    @Test
    void ensureSessionCreatesNamedSessionAndUpdatesName() {
        ThreadSessionManager manager = new ThreadSessionManager(10, 30);
        Agent agent = mock(Agent.class);

        ThreadSession created =
                manager.ensureSession("thread-1", "agent-a", "  Demo  ", () -> agent);
        assertEquals("agent-a", created.getAgentId());
        assertEquals("  Demo  ", created.getName());
        assertSame(agent, created.getAgent());

        ThreadSession renamed =
                manager.ensureSession("thread-1", "agent-a", "Renamed", () -> mock(Agent.class));
        assertSame(created, renamed);
        assertEquals("Renamed", renamed.getName());
    }

    @Test
    void ensureSessionIgnoresBlankNameAndPreservesExistingOnAgentChange() {
        ThreadSessionManager manager = new ThreadSessionManager(10, 30);
        Agent first = mock(Agent.class);
        Agent second = mock(Agent.class);

        ThreadSession created = manager.ensureSession("thread-1", "agent-a", null, () -> first);
        assertEquals(null, created.getName());

        manager.ensureSession("thread-1", "agent-a", "   ", () -> first);
        assertEquals(null, manager.getSession("thread-1").orElseThrow().getName());

        created.setName("KeepMe");
        created.setArchived(true);
        ThreadSession replaced = manager.ensureSession("thread-1", "agent-b", "  ", () -> second);

        assertEquals("agent-b", replaced.getAgentId());
        assertEquals("KeepMe", replaced.getName());
        assertTrue(replaced.isArchived());
        assertSame(second, replaced.getAgent());
    }

    @Test
    void hasMemoryUsesThreadScopedAgentState() {
        ThreadSessionManager manager = new ThreadSessionManager(10, 30);
        assertFalse(manager.hasMemory("missing"));

        Agent plain = mock(Agent.class);
        manager.getOrCreateAgent("thread-plain", "agent-a", () -> plain);
        assertFalse(manager.hasMemory("thread-plain"));

        ReActAgent emptyAgent = mock(ReActAgent.class);
        AgentState emptyState = mock(AgentState.class);
        when(emptyAgent.getAgentState(null, "thread-empty")).thenReturn(emptyState);
        when(emptyState.getContext()).thenReturn(List.of());
        manager.getOrCreateAgent("thread-empty", "agent-a", () -> emptyAgent);
        assertFalse(manager.hasMemory("thread-empty"));

        ReActAgent filledAgent = mock(ReActAgent.class);
        AgentState filledState = mock(AgentState.class);
        when(filledAgent.getAgentState(null, "thread-filled")).thenReturn(filledState);
        when(filledState.getContext()).thenReturn(List.of(mock(Msg.class)));
        manager.getOrCreateAgent("thread-filled", "agent-a", () -> filledAgent);
        assertTrue(manager.hasMemory("thread-filled"));
    }

    @Test
    void getSessionsReturnsUnmodifiableSnapshot() {
        ThreadSessionManager manager = new ThreadSessionManager(10, 30);
        Agent agent = mock(Agent.class);
        manager.getOrCreateAgent("thread-1", "agent-a", () -> agent);

        Map<String, ThreadSession> snapshot = manager.getSessions();
        assertEquals(1, snapshot.size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.put("thread-2", mock(ThreadSession.class)));
    }

    @Test
    void removeClearAndCapacityEvictionWork() {
        ThreadSessionManager manager = new ThreadSessionManager(1, 0);
        Agent first = mock(Agent.class);
        Agent second = mock(Agent.class);

        manager.getOrCreateAgent("thread-1", "agent-a", () -> first);
        assertTrue(manager.removeSession("thread-1"));
        assertFalse(manager.removeSession("thread-1"));
        assertEquals(0, manager.getSessionCount());

        manager.getOrCreateAgent("thread-1", "agent-a", () -> first);
        // maxSessions=1 and timeout disabled → creating another session evicts the oldest.
        manager.getOrCreateAgent("thread-2", "agent-a", () -> second);
        assertEquals(1, manager.getSessionCount());
        assertTrue(manager.getSession("thread-2").isPresent());
        assertTrue(manager.getSession("thread-1").isEmpty());

        manager.clear();
        assertEquals(0, manager.getSessionCount());
    }

    @Test
    void cleanupExpiredSessionsRemovesInactiveOnes() throws Exception {
        ThreadSessionManager manager = new ThreadSessionManager(10, 1);
        Agent agent = mock(Agent.class);
        manager.getOrCreateAgent("thread-old", "agent-a", () -> agent);

        ThreadSession session = manager.getSession("thread-old").orElseThrow();
        // Force lastAccess into the past beyond the 1-minute timeout.
        java.lang.reflect.Field field = ThreadSession.class.getDeclaredField("lastAccess");
        field.setAccessible(true);
        field.set(session, java.time.Instant.now().minusSeconds(120));

        manager.cleanupExpiredSessions();
        assertTrue(manager.getSession("thread-old").isEmpty());
    }
}
