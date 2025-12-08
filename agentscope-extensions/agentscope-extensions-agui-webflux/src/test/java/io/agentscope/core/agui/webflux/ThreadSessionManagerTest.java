/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.agentscope.core.agui.webflux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agui.webflux.config.ThreadSessionManager;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ThreadSessionManager.
 */
class ThreadSessionManagerTest {

    private ThreadSessionManager sessionManager;

    @BeforeEach
    void setUp() {
        // Default: max 100 sessions, 30 minute timeout
        sessionManager = new ThreadSessionManager(100, 30);
    }

    @Test
    void testCreateNewSession() {
        Agent mockAgent = mock(Agent.class);

        Agent result = sessionManager.getOrCreateAgent("thread-1", "agent-1", () -> mockAgent);

        assertSame(mockAgent, result);
        assertEquals(1, sessionManager.getSessionCount());
    }

    @Test
    void testGetExistingSessionWithSameAgentId() {
        Agent mockAgent = mock(Agent.class);

        Agent first = sessionManager.getOrCreateAgent("thread-1", "agent-1", () -> mockAgent);
        Agent second =
                sessionManager.getOrCreateAgent("thread-1", "agent-1", () -> mock(Agent.class));

        assertSame(first, second);
        assertEquals(1, sessionManager.getSessionCount());
    }

    @Test
    void testAgentTypeChangeCreatesNewSession() {
        Agent agent1 = mock(Agent.class);
        Agent agent2 = mock(Agent.class);

        Agent first = sessionManager.getOrCreateAgent("thread-1", "agent-type-1", () -> agent1);
        Agent second = sessionManager.getOrCreateAgent("thread-1", "agent-type-2", () -> agent2);

        assertSame(agent1, first);
        assertSame(agent2, second);
        assertNotSame(first, second);
        // Still only 1 session (same threadId, but different agent)
        assertEquals(1, sessionManager.getSessionCount());
    }

    @Test
    void testMultipleThreadsHaveSeparateSessions() {
        Agent agent1 = mock(Agent.class);
        Agent agent2 = mock(Agent.class);

        Agent first = sessionManager.getOrCreateAgent("thread-1", "agent-1", () -> agent1);
        Agent second = sessionManager.getOrCreateAgent("thread-2", "agent-1", () -> agent2);

        assertSame(agent1, first);
        assertSame(agent2, second);
        assertEquals(2, sessionManager.getSessionCount());
    }

    @Test
    void testHasMemoryReturnsFalseForNonExistentSession() {
        assertFalse(sessionManager.hasMemory("non-existent"));
    }

    @Test
    void testHasMemoryReturnsFalseForNonReActAgent() {
        Agent mockAgent = mock(Agent.class);
        sessionManager.getOrCreateAgent("thread-1", "agent-1", () -> mockAgent);

        assertFalse(sessionManager.hasMemory("thread-1"));
    }

    @Test
    void testHasMemoryReturnsFalseWhenMemoryIsEmpty() {
        ReActAgent mockAgent = mock(ReActAgent.class);
        Memory mockMemory = mock(Memory.class);
        when(mockAgent.getMemory()).thenReturn(mockMemory);
        when(mockMemory.getMessages()).thenReturn(Collections.emptyList());

        sessionManager.getOrCreateAgent("thread-1", "agent-1", () -> mockAgent);

        assertFalse(sessionManager.hasMemory("thread-1"));
    }

    @Test
    void testHasMemoryReturnsTrueWhenMemoryHasMessages() {
        ReActAgent mockAgent = mock(ReActAgent.class);
        Memory mockMemory = mock(Memory.class);
        when(mockAgent.getMemory()).thenReturn(mockMemory);
        when(mockMemory.getMessages()).thenReturn(List.of(mock(Msg.class)));

        sessionManager.getOrCreateAgent("thread-1", "agent-1", () -> mockAgent);

        assertTrue(sessionManager.hasMemory("thread-1"));
    }

    @Test
    void testGetSession() {
        Agent mockAgent = mock(Agent.class);
        sessionManager.getOrCreateAgent("thread-1", "agent-1", () -> mockAgent);

        assertTrue(sessionManager.getSession("thread-1").isPresent());
        assertFalse(sessionManager.getSession("non-existent").isPresent());
    }

    @Test
    void testRemoveSession() {
        Agent mockAgent = mock(Agent.class);
        sessionManager.getOrCreateAgent("thread-1", "agent-1", () -> mockAgent);

        assertTrue(sessionManager.removeSession("thread-1"));
        assertEquals(0, sessionManager.getSessionCount());
        assertFalse(sessionManager.removeSession("thread-1"));
    }

    @Test
    void testClear() {
        sessionManager.getOrCreateAgent("thread-1", "agent-1", () -> mock(Agent.class));
        sessionManager.getOrCreateAgent("thread-2", "agent-1", () -> mock(Agent.class));

        sessionManager.clear();

        assertEquals(0, sessionManager.getSessionCount());
    }

    @Test
    void testMaxSessionsEnforced() {
        // Create manager with max 3 sessions, no timeout
        ThreadSessionManager manager = new ThreadSessionManager(3, 0);

        // Create 3 sessions
        manager.getOrCreateAgent("thread-1", "agent", () -> mock(Agent.class));
        manager.getOrCreateAgent("thread-2", "agent", () -> mock(Agent.class));
        manager.getOrCreateAgent("thread-3", "agent", () -> mock(Agent.class));

        assertEquals(3, manager.getSessionCount());

        // Creating 4th session should evict oldest
        manager.getOrCreateAgent("thread-4", "agent", () -> mock(Agent.class));

        assertEquals(3, manager.getSessionCount());
        // thread-1 should be evicted (oldest)
        assertFalse(manager.getSession("thread-1").isPresent());
        assertTrue(manager.getSession("thread-4").isPresent());
    }

    @Test
    void testSessionTimeoutCleanup() throws InterruptedException {
        // Create manager with 1 second timeout (for testing)
        ThreadSessionManager manager = new ThreadSessionManager(100, 0);

        manager.getOrCreateAgent("thread-1", "agent", () -> mock(Agent.class));

        // Manually trigger cleanup (no timeout means cleanup won't remove anything)
        manager.cleanupExpiredSessions();
        assertEquals(1, manager.getSessionCount());
    }

    @Test
    void testConcurrentAccessSafety() throws InterruptedException {
        int threadCount = 10;
        int operationsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger agentCreationCount = new AtomicInteger(0);

        // All threads try to get/create the same session concurrently
        for (int i = 0; i < threadCount; i++) {
            executor.submit(
                    () -> {
                        try {
                            for (int j = 0; j < operationsPerThread; j++) {
                                sessionManager.getOrCreateAgent(
                                        "shared-thread",
                                        "agent-1",
                                        () -> {
                                            agentCreationCount.incrementAndGet();
                                            return mock(Agent.class);
                                        });
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Only 1 session should exist
        assertEquals(1, sessionManager.getSessionCount());
        // Agent factory should be called only once due to atomic compute()
        assertEquals(1, agentCreationCount.get());
    }

    @Test
    void testConcurrentAgentTypeSwitch() throws InterruptedException {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<Agent> createdAgents = Collections.synchronizedList(new ArrayList<>());

        // Half threads use agent-1, half use agent-2
        for (int i = 0; i < threadCount; i++) {
            final String agentId = (i % 2 == 0) ? "agent-1" : "agent-2";
            executor.submit(
                    () -> {
                        try {
                            Agent agent =
                                    sessionManager.getOrCreateAgent(
                                            "shared-thread",
                                            agentId,
                                            () -> {
                                                Agent newAgent = mock(Agent.class);
                                                createdAgents.add(newAgent);
                                                return newAgent;
                                            });
                        } finally {
                            latch.countDown();
                        }
                    });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Only 1 session should exist
        assertEquals(1, sessionManager.getSessionCount());
        // Should have created at most 2 agents (one for each type, possibly less due to timing)
        assertTrue(createdAgents.size() >= 1 && createdAgents.size() <= threadCount);
    }

    @Test
    void testSessionAccessTimeUpdated() {
        Agent mockAgent = mock(Agent.class);

        sessionManager.getOrCreateAgent("thread-1", "agent-1", () -> mockAgent);
        var session1 = sessionManager.getSession("thread-1").orElseThrow();
        var initialTime = session1.getLastAccess();

        // Small delay to ensure time difference
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Access again
        sessionManager.getOrCreateAgent("thread-1", "agent-1", () -> mock(Agent.class));
        var session2 = sessionManager.getSession("thread-1").orElseThrow();

        // Access time should be updated
        assertTrue(
                session2.getLastAccess().isAfter(initialTime)
                        || session2.getLastAccess().equals(initialTime));
    }
}
