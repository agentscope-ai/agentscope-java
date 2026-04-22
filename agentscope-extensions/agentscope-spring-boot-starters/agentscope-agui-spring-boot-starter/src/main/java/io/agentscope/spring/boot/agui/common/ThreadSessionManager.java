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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.memory.Memory;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages agent sessions by threadId for server-side memory management.
 *
 * <p>This manager maintains a pool of agent instances, each associated with a threadId. When
 * server-side memory is enabled, the same agent instance is reused for requests with the same
 * threadId, preserving conversation history across requests.
 *
 * <p><b>Usage:</b>
 *
 * <pre>{@code
 * ThreadSessionManager manager = new ThreadSessionManager(1000, 30);
 *
 * // Get or create an agent for a thread
 * Agent agent = manager.getOrCreateAgent("thread-123", "default", () -> createAgent());
 *
 * // Check if agent has memory
 * boolean hasMemory = manager.hasMemory("thread-123");
 *
 * // Clean up expired sessions
 * manager.cleanupExpiredSessions();
 * }</pre>
 */
public class ThreadSessionManager {

    private static final Logger logger = LoggerFactory.getLogger(ThreadSessionManager.class);

    private final Map<String, ThreadSession> sessions = new ConcurrentHashMap<>();
    private final int maxSessions;
    private final int sessionTimeoutMinutes;

    /**
     * Creates a new ThreadSessionManager.
     *
     * @param maxSessions Maximum number of sessions to maintain
     * @param sessionTimeoutMinutes Session timeout in minutes (0 = no timeout)
     */
    public ThreadSessionManager(int maxSessions, int sessionTimeoutMinutes) {
        this.maxSessions = maxSessions;
        this.sessionTimeoutMinutes = sessionTimeoutMinutes;
    }

    /**
     * Get or create an agent for the given threadId.
     *
     * <p>This method is thread-safe. It uses atomic operations to ensure that concurrent requests
     * for the same threadId will share the same agent instance.
     *
     * @param threadId The thread identifier
     * @param agentId The agent type identifier
     * @param agentFactory Factory to create new agents if needed
     * @return The agent for this thread
     */
    public Agent getOrCreateAgent(String threadId, String agentId, Supplier<Agent> agentFactory) {
        // Clean up if we're at capacity
        if (sessions.size() >= maxSessions) {
            cleanupExpiredSessions();
            // If still at capacity, remove oldest session
            if (sessions.size() >= maxSessions) {
                removeOldestSession();
            }
        }

        // Use compute() for atomic check-and-update to avoid race conditions
        ThreadSession session =
                sessions.compute(
                        threadId,
                        (k, existing) -> {
                            if (existing == null) {
                                // No existing session, create new one
                                logger.debug("Creating new session for threadId: {}", threadId);
                                return new ThreadSession(agentId, agentFactory.get());
                            }
                            if (!existing.getAgentId().equals(agentId)) {
                                // Agent type changed, create new session
                                logger.debug(
                                        "Agent type changed for threadId {}: {} -> {}",
                                        threadId,
                                        existing.getAgentId(),
                                        agentId);
                                return new ThreadSession(agentId, agentFactory.get());
                            }
                            // Same agent type, update access time and reuse
                            existing.updateLastAccess();
                            return existing;
                        });

        return session.getAgent();
    }

    /**
     * Check if a session exists and has memory for the given threadId.
     *
     * @param threadId The thread identifier
     * @return true if the session exists and the agent has non-empty memory
     */
    public boolean hasMemory(String threadId) {
        ThreadSession session = sessions.get(threadId);
        if (session == null) {
            return false;
        }

        Agent agent = session.getAgent();
        // Check if the agent has a memory and if it has any messages
        // ReActAgent is the main agent type that has memory
        if (agent instanceof ReActAgent reactAgent) {
            Memory memory = reactAgent.getMemory();
            return memory != null && !memory.getMessages().isEmpty();
        }

        return false;
    }

    /**
     * Get the session for a threadId if it exists.
     *
     * @param threadId The thread identifier
     * @return Optional containing the session, or empty if not found
     */
    public Optional<ThreadSession> getSession(String threadId) {
        return Optional.ofNullable(sessions.get(threadId));
    }

    /**
     * Remove a session by threadId.
     *
     * @param threadId The thread identifier
     * @return true if a session was removed
     */
    public boolean removeSession(String threadId) {
        return sessions.remove(threadId) != null;
    }

    /** Clean up sessions that have been inactive for longer than the timeout. */
    public void cleanupExpiredSessions() {
        if (sessionTimeoutMinutes <= 0) {
            return;
        }

        Instant cutoff = Instant.now().minusSeconds(sessionTimeoutMinutes * 60L);
        int removed = 0;

        var iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue().getLastAccess().isBefore(cutoff)) {
                iterator.remove();
                removed++;
            }
        }

        if (removed > 0) {
            logger.debug("Cleaned up {} expired sessions", removed);
        }
    }

    /** Remove the oldest session to make room for new ones. */
    private void removeOldestSession() {
        String oldestKey = null;
        Instant oldestTime = Instant.MAX;

        for (var entry : sessions.entrySet()) {
            if (entry.getValue().getLastAccess().isBefore(oldestTime)) {
                oldestTime = entry.getValue().getLastAccess();
                oldestKey = entry.getKey();
            }
        }

        if (oldestKey != null) {
            sessions.remove(oldestKey);
            logger.debug("Removed oldest session: {}", oldestKey);
        }
    }

    /**
     * Get the current number of active sessions.
     *
     * @return Number of sessions
     */
    public int getSessionCount() {
        return sessions.size();
    }

    /** Clear all sessions. */
    public void clear() {
        sessions.clear();
    }

    /** Represents a thread session with its agent and metadata. */
    public static class ThreadSession {

        private final String agentId;
        private final Agent agent;
        private Instant lastAccess;

        ThreadSession(String agentId, Agent agent) {
            this.agentId = agentId;
            this.agent = agent;
            this.lastAccess = Instant.now();
        }

        public String getAgentId() {
            return agentId;
        }

        public Agent getAgent() {
            return agent;
        }

        public Instant getLastAccess() {
            return lastAccess;
        }

        void updateLastAccess() {
            this.lastAccess = Instant.now();
        }
    }
}
