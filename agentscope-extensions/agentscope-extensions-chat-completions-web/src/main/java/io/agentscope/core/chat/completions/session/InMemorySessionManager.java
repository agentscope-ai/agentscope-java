/*
 * Copyright 2024-2026 the original author or authors.
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
package io.agentscope.core.chat.completions.session;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SimpleSessionKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory {@link ChatCompletionsSessionManager} implementation with optional state persistence.
 *
 * <p>This implementation caches ReActAgent instances in memory with TTL-based expiration.
 *
 * <p><b>Two modes of operation:</b>
 *
 * <ul>
 *   <li><b>Pure in-memory (default):</b> No Session provided. Agent state lives in memory only and
 *       is lost when the agent expires or JVM exits.
 *   <li><b>With persistence:</b> Session provided. Agent state is saved to Session when agents
 *       expire and restored when recreated after JVM restart.
 * </ul>
 *
 * <p><b>Usage:</b>
 *
 * <pre>{@code
 * // Pure in-memory (default)
 * ChatCompletionsSessionManager manager = new InMemorySessionManager();
 *
 * // With JSON file persistence
 * Session session = new JsonSession(Path.of("sessions"));
 * ChatCompletionsSessionManager manager = new InMemorySessionManager(session);
 * }</pre>
 */
public class InMemorySessionManager implements ChatCompletionsSessionManager {

    private static final Logger log = LoggerFactory.getLogger(InMemorySessionManager.class);
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    private final Map<String, Entry> agents = new ConcurrentHashMap<>();
    private final Session session; // nullable, null means pure in-memory mode
    private final Duration ttl;

    /** Create a new InMemorySessionManager in pure in-memory mode with default 30-minute TTL. */
    public InMemorySessionManager() {
        this(null, DEFAULT_TTL);
    }

    /**
     * Create a new InMemorySessionManager with optional Session for state persistence.
     *
     * @param session The Session implementation for state persistence; null for pure in-memory mode
     */
    public InMemorySessionManager(Session session) {
        this(session, DEFAULT_TTL);
    }

    /**
     * Create a new InMemorySessionManager with custom Session and TTL.
     *
     * @param session The Session implementation for state persistence; null for pure in-memory mode
     * @param ttl The time-to-live for cached agents; null uses default (30 minutes)
     */
    public InMemorySessionManager(Session session, Duration ttl) {
        this.session = session;
        this.ttl = ttl != null ? ttl : DEFAULT_TTL;
    }

    /**
     * Returns an existing non-expired {@link ReActAgent} for the given session or creates a new one
     * if none exists or the previous one has expired.
     *
     * <p>If the sessionId is {@code null} or blank, a new random UUID is generated as the session
     * identifier. The session TTL is refreshed each time the agent is accessed.
     *
     * <p>When a new agent is created and a Session is configured, this method attempts to restore
     * its state from the Session if previously saved state exists.
     *
     * @param sessionId the session identifier; if {@code null} or blank, a new identifier is
     *     generated
     * @param agentSupplier the supplier used to create a new {@link ReActAgent} when required
     * @return the existing or newly created {@link ReActAgent} associated with the session
     * @throws IllegalStateException if the agentSupplier returns null
     */
    @Override
    public ReActAgent getOrCreateAgent(String sessionId, Supplier<ReActAgent> agentSupplier) {
        String key =
                (sessionId == null || sessionId.isBlank())
                        ? UUID.randomUUID().toString()
                        : sessionId;

        pruneExpired();

        Entry existing = agents.get(key);
        if (existing != null && !existing.isExpired()) {
            agents.put(key, existing.touch());
            log.debug("Reusing existing agent for session: {}", key);
            return existing.agent();
        }

        // Create new agent
        ReActAgent agent = agentSupplier.get();
        if (agent == null) {
            log.error(
                    "Failed to create ReActAgent: agentSupplier returned null for session: {}",
                    key);
            throw new IllegalStateException(
                    "Failed to create ReActAgent: agentSupplier returned null");
        }

        // Try to restore state from Session if configured and exists
        if (session != null && session.exists(SimpleSessionKey.of(key))) {
            try {
                agent.loadFrom(session, key);
                log.debug("Restored agent state from session: {}", key);
            } catch (Exception e) {
                log.warn("Failed to restore agent state for session: {}", key, e);
            }
        }

        agents.put(key, new Entry(agent, Instant.now(), ttl));
        log.debug("Created new agent for session: {}", key);
        return agent;
    }

    /**
     * Save the state of all active agents to the underlying Session.
     *
     * <p>This method has no effect if no Session is configured (pure in-memory mode).
     *
     * <p>Call this method periodically or on application shutdown to ensure state is persisted.
     */
    public void saveAllAgentStates() {
        if (session == null) {
            log.debug("No session configured, skipping state save");
            return;
        }
        for (Map.Entry<String, Entry> entry : agents.entrySet()) {
            saveAgentState(entry.getKey(), entry.getValue().agent());
        }
        log.debug("Saved state for {} active agents", agents.size());
    }

    private void saveAgentState(String sessionId, ReActAgent agent) {
        if (session == null) {
            return;
        }
        try {
            agent.saveTo(session, sessionId);
            log.debug("Saved agent state for session: {}", sessionId);
        } catch (Exception e) {
            log.warn("Failed to save agent state for session: {}", sessionId, e);
        }
    }

    private void pruneExpired() {
        Instant now = Instant.now();
        int beforeSize = agents.size();

        agents.entrySet()
                .removeIf(
                        e -> {
                            if (e.getValue().expiresAt().isBefore(now)) {
                                // Save state before removing from cache (if Session configured)
                                saveAgentState(e.getKey(), e.getValue().agent());
                                return true;
                            }
                            return false;
                        });

        int afterSize = agents.size();
        if (beforeSize != afterSize) {
            log.debug(
                    "Pruned {} expired sessions (before: {}, after: {})",
                    beforeSize - afterSize,
                    beforeSize,
                    afterSize);
        }
    }

    /**
     * Get the number of active agents in the cache.
     *
     * @return Number of cached agents
     */
    public int getActiveAgentCount() {
        return agents.size();
    }

    /**
     * Get the underlying Session instance.
     *
     * @return The Session used for state storage, or null if pure in-memory mode
     */
    public Session getSession() {
        return session;
    }

    /**
     * Clear all cached agents.
     *
     * <p>Useful for testing or when you need to reset all sessions.
     */
    public void clear() {
        agents.clear();
        log.debug("Cleared all cached agents");
    }

    private record Entry(ReActAgent agent, Instant createdAt, Duration ttl) {

        Instant expiresAt() {
            return createdAt.plus(ttl);
        }

        boolean isExpired() {
            return expiresAt().isBefore(Instant.now());
        }

        Entry touch() {
            return new Entry(agent, Instant.now(), ttl);
        }
    }
}
