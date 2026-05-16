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
 * In-memory implementation of {@link AguiSessionManager}.
 *
 * <p>This implementation maintains a pool of agent instances in a {@link ConcurrentHashMap}, each
 * associated with a threadId. When server-side memory is enabled, the same agent instance is reused
 * for requests with the same threadId, preserving conversation history across requests.
 *
 * <p>This is suitable for single-instance deployments. For distributed deployments where requests
 * may be routed to different machines, use {@link SessionAwareAguiSessionManager} instead.
 *
 * <p><b>Usage:</b>
 *
 * <pre>{@code
 * AguiSessionManager manager = new InMemoryAguiSessionManager(1000, 30);
 *
 * // Get or create an agent for a thread
 * Agent agent = manager.getOrCreateAgent("thread-123", "default", () -> createAgent());
 *
 * // Check if agent has memory
 * boolean hasMemory = manager.hasMemory("thread-123", "default");
 *
 * // Clean up expired sessions
 * manager.cleanupExpiredSessions();
 * }</pre>
 */
public class InMemoryAguiSessionManager implements AguiSessionManager {

    private static final Logger logger = LoggerFactory.getLogger(InMemoryAguiSessionManager.class);

    private final Map<String, AguiSession> sessions = new ConcurrentHashMap<>();
    private final int maxSessions;
    private final int sessionTimeoutMinutes;

    /**
     * Creates a new InMemoryAguiSessionManager.
     *
     * @param maxSessions Maximum number of sessions to maintain
     * @param sessionTimeoutMinutes Session timeout in minutes (0 = no timeout)
     */
    public InMemoryAguiSessionManager(int maxSessions, int sessionTimeoutMinutes) {
        this.maxSessions = maxSessions;
        this.sessionTimeoutMinutes = sessionTimeoutMinutes;
    }

    @Override
    public Agent getOrCreateAgent(String threadId, String agentId, Supplier<Agent> agentFactory) {
        String compositeKey = buildCompositeKey(agentId, threadId);

        // Clean up if we're at capacity
        if (sessions.size() >= maxSessions) {
            cleanupExpiredSessions();
            // If still at capacity, remove oldest session
            if (sessions.size() >= maxSessions) {
                removeOldestSession();
            }
        }

        // Use compute() for atomic check-and-update to avoid race conditions
        AguiSession session =
                sessions.compute(
                        compositeKey,
                        (k, existing) -> {
                            if (existing == null) {
                                // No existing session, create new one
                                logger.debug(
                                        "Creating new session for threadId: {}, agentId: {}",
                                        threadId,
                                        agentId);
                                return new AguiSession(agentId, agentFactory.get());
                            }
                            // Same agent type, update access time and reuse
                            existing.updateLastAccess();
                            return existing;
                        });

        return session.getAgent();
    }

    @Override
    public boolean hasMemory(String threadId, String agentId) {
        String compositeKey = buildCompositeKey(agentId, threadId);
        AguiSession session = sessions.get(compositeKey);
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
     * Get the session for a threadId and agentId if it exists.
     *
     * <p>This method is specific to the in-memory implementation and not part of the {@link
     * AguiSessionManager} interface.
     *
     * @param threadId The thread identifier
     * @param agentId The agent type identifier
     * @return Optional containing the session, or empty if not found
     */
    public Optional<AguiSession> getSession(String threadId, String agentId) {
        return Optional.ofNullable(sessions.get(buildCompositeKey(agentId, threadId)));
    }

    @Override
    public boolean removeSession(String threadId, String agentId) {
        return sessions.remove(buildCompositeKey(agentId, threadId)) != null;
    }

    @Override
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

    @Override
    public int getSessionCount() {
        return sessions.size();
    }

    @Override
    public void clear() {
        sessions.clear();
    }

    /** Represents a session with its agent and metadata. */
    public static class AguiSession {

        private final String agentId;
        private final Agent agent;
        private Instant lastAccess;

        AguiSession(String agentId, Agent agent) {
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

    private static String buildCompositeKey(String agentId, String threadId) {
        return agentId + ":" + threadId;
    }
}
