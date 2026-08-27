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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.state.AgentState;
import io.agentscope.spring.boot.agui.common.ThreadSessionManager.ThreadSession;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

        ThreadSession renamed =
                manager.ensureSession("thread-1", "agent-c", "NewName", () -> mock(Agent.class));
        assertEquals("agent-c", renamed.getAgentId());
        assertEquals("NewName", renamed.getName());
        assertTrue(renamed.isArchived());
    }

    @Test
    void hasMemoryUsesRuntimeContextScopedAgentStateWithoutClosingTheAgent() {
        ThreadSessionManager manager = new ThreadSessionManager(10, 30);
        assertFalse(manager.hasMemory(ctx("missing")));
        assertFalse(manager.hasMemory(null));

        Agent plain = mock(Agent.class);
        manager.getOrCreateAgent("thread-plain", "agent-a", () -> plain);
        assertFalse(manager.hasMemory(ctx("thread-plain")));

        ReActAgent emptyAgent = mock(ReActAgent.class);
        AgentState emptyState = mock(AgentState.class);
        when(emptyAgent.getAgentState(any(RuntimeContext.class))).thenReturn(emptyState);
        when(emptyState.getContext()).thenReturn(List.of());
        manager.getOrCreateAgent("thread-empty", "agent-a", () -> emptyAgent);
        assertFalse(manager.hasMemory(ctx("thread-empty")));

        ReActAgent nullStateAgent = mock(ReActAgent.class);
        when(nullStateAgent.getAgentState(any(RuntimeContext.class))).thenReturn(null);
        manager.getOrCreateAgent("thread-null-state", "agent-a", () -> nullStateAgent);
        assertFalse(manager.hasMemory(ctx("thread-null-state")));
        assertFalse(manager.hasMemory(RuntimeContext.builder().userId("user-1").build()));

        ReActAgent filledAgent = mock(ReActAgent.class);
        AgentState filledState = mock(AgentState.class);
        ArgumentCaptor<RuntimeContext> contextCaptor =
                ArgumentCaptor.forClass(RuntimeContext.class);
        when(filledAgent.getAgentState(contextCaptor.capture())).thenReturn(filledState);
        when(filledState.getContext()).thenReturn(List.of(mock(Msg.class)));
        manager.getOrCreateAgent("user-1", "thread-filled", "agent-a", () -> filledAgent);
        assertTrue(
                manager.hasMemory(
                        RuntimeContext.builder()
                                .sessionId("thread-filled")
                                .userId("user-1")
                                .build()));
        assertFalse(manager.hasMemory(ctx("thread-filled")));
        assertEquals("thread-filled", contextCaptor.getValue().getSessionId());
        assertEquals("user-1", contextCaptor.getValue().getUserId());
        verify(filledAgent, never()).close();
    }

    @Test
    void sessionsAndHasMemoryAreIsolatedByUserId() {
        ThreadSessionManager manager = new ThreadSessionManager(10, 30);
        ReActAgent agentA = mock(ReActAgent.class);
        ReActAgent agentB = mock(ReActAgent.class);
        AgentState filledState = mock(AgentState.class);
        AgentState emptyState = mock(AgentState.class);
        when(filledState.getContext()).thenReturn(List.of(mock(Msg.class)));
        when(emptyState.getContext()).thenReturn(List.of());
        when(agentA.getAgentState(any(RuntimeContext.class))).thenReturn(filledState);
        when(agentB.getAgentState(any(RuntimeContext.class))).thenReturn(emptyState);

        Agent createdA = manager.getOrCreateAgent("user-a", "thread-1", "agent-a", () -> agentA);
        Agent createdB = manager.getOrCreateAgent("user-b", "thread-1", "agent-a", () -> agentB);

        assertSame(agentA, createdA);
        assertSame(agentB, createdB);
        assertEquals(2, manager.getSessionCount());
        assertSame(agentA, manager.getSession("user-a", "thread-1").orElseThrow().getAgent());
        assertSame(agentB, manager.getSession("user-b", "thread-1").orElseThrow().getAgent());
        assertTrue(manager.getSession("thread-1").isEmpty());
        assertTrue(
                manager.hasMemory(
                        RuntimeContext.builder().userId("user-a").sessionId("thread-1").build()));
        assertFalse(
                manager.hasMemory(
                        RuntimeContext.builder().userId("user-b").sessionId("thread-1").build()));
        assertFalse(manager.hasMemory(ctx("thread-1")));
    }

    @Test
    void blankUserIdSharesAnonymousSessionAndEnsureSessionKeepsUserScope() {
        ThreadSessionManager manager = new ThreadSessionManager(10, 30);
        Agent first = mock(Agent.class);
        Agent second = mock(Agent.class);

        Agent created = manager.getOrCreateAgent("   ", "thread-1", "agent-a", () -> first);
        Agent reused =
                manager.getOrCreateAgent(null, "thread-1", "agent-a", () -> mock(Agent.class));
        ThreadSession named =
                manager.ensureSession("user-1", "thread-1", "agent-a", "Orders", () -> second);

        assertSame(first, created);
        assertSame(first, reused);
        assertEquals(
                ThreadSessionManager.ANON_USER,
                manager.getSession("thread-1").orElseThrow().getUserId());
        assertEquals("user-1", named.getUserId());
        assertEquals("thread-1", named.getThreadId());
        assertEquals("Orders", named.getName());
        assertEquals(2, manager.getSessionCount());
        assertTrue(manager.removeSession("user-1", "thread-1"));
        assertTrue(manager.getSession("user-1", "thread-1").isEmpty());
        assertEquals(1, manager.getSessionCount());
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

        manager.getOrCreateAgent("user-a", "thread-shared", "agent-a", () -> mock(Agent.class));
        manager.getOrCreateAgent("user-b", "thread-shared", "agent-a", () -> mock(Agent.class));
        Map<String, ThreadSession> overwritten = manager.getSessions();
        assertEquals(2, overwritten.size());
        assertEquals("thread-shared", overwritten.get("thread-shared").getThreadId());
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

        manager.getOrCreateAgent("thread-fresh", "agent-a", () -> agent);
        manager.cleanupExpiredSessions();
        assertTrue(manager.getSession("thread-fresh").isPresent());
    }

    @Test
    void ensureCapacityCleansExpiredSessionInsteadOfEvictingNewest() throws Exception {
        ThreadSessionManager manager = new ThreadSessionManager(1, 1);
        Agent first = mock(Agent.class);
        Agent second = mock(Agent.class);
        manager.getOrCreateAgent("thread-old", "agent-a", () -> first);

        ThreadSession session = manager.getSession("thread-old").orElseThrow();
        java.lang.reflect.Field field = ThreadSession.class.getDeclaredField("lastAccess");
        field.setAccessible(true);
        field.set(session, java.time.Instant.now().minusSeconds(120));

        manager.getOrCreateAgent("thread-new", "agent-a", () -> second);
        assertEquals(1, manager.getSessionCount());
        assertTrue(manager.getSession("thread-new").isPresent());
        assertTrue(manager.getSession("thread-old").isEmpty());
    }

    private static RuntimeContext ctx(String threadId) {
        return RuntimeContext.builder().sessionId(threadId).build();
    }
}
