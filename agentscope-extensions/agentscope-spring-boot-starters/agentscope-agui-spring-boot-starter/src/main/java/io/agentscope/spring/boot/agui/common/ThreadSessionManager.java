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
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.AguiUtil;
import io.agentscope.core.state.AgentState;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages agent sessions by {@code (userId, threadId)} for server-side memory.
 *
 * <p>When server-side memory is enabled, the same agent instance is reused for requests with the
 * same user and thread, preserving conversation history across requests. Sessions for different
 * users never share a slot, even when they reuse a thread id.
 *
 * <p><b>Usage:</b>
 *
 * <pre>{@code
 * ThreadSessionManager manager = new ThreadSessionManager(1000, 30);
 *
 * Agent agent =
 *         manager.getOrCreateAgent(
 *                 "user-1", "thread-123", "default", () -> createAgent());
 *
 * boolean hasMemory =
 *         manager.hasMemory(
 *                 RuntimeContext.builder()
 *                         .userId("user-1")
 *                         .sessionId("thread-123")
 *                         .build());
 *
 * manager.cleanupExpiredSessions();
 * }</pre>
 */
public class ThreadSessionManager {

    private static final Logger logger = LoggerFactory.getLogger(ThreadSessionManager.class);

    /**
     * Sentinel namespace for callers that pass {@code userId == null}.
     *
     * <p>Must stay in sync with the anonymous sentinel used by {@code ReActAgent#slotKey}
     * ({@value #ANON_USER}).
     */
    static final String ANON_USER = "__anon__";

    private final Map<SessionKey, ThreadSession> sessions = new ConcurrentHashMap<>();
    private final int maxSessions;
    private final int sessionTimeoutMinutes;

    /**
     * Creates a new ThreadSessionManager.
     *
     * @param maxSessions Maximum number of sessions to maintain
     * @param sessionTimeoutMinutes AgentStateStore timeout in minutes (0 = no timeout)
     */
    public ThreadSessionManager(int maxSessions, int sessionTimeoutMinutes) {
        this.maxSessions = maxSessions;
        this.sessionTimeoutMinutes = sessionTimeoutMinutes;
    }

    /**
     * Get or create an agent for the given threadId under the anonymous user slot.
     *
     * @param threadId The thread identifier
     * @param agentId The agent type identifier
     * @param agentFactory Factory to create new agents if needed
     * @return The agent for this thread
     */
    public Agent getOrCreateAgent(String threadId, String agentId, Supplier<Agent> agentFactory) {
        return getOrCreateAgent(null, threadId, agentId, agentFactory);
    }

    /**
     * Get or create an agent for the given user and thread.
     *
     * <p>This method is thread-safe. Concurrent requests for the same {@code (userId, threadId)}
     * share the same agent instance.
     *
     * @param userId The user identifier, may be {@code null} for anonymous
     * @param threadId The thread identifier
     * @param agentId The agent type identifier
     * @param agentFactory Factory to create new agents if needed
     * @return The agent for this user and thread
     */
    public Agent getOrCreateAgent(
            String userId, String threadId, String agentId, Supplier<Agent> agentFactory) {
        ensureCapacity();
        return sessions.compute(
                        sessionKey(userId, threadId),
                        (k, existing) ->
                                upsertSession(
                                        existing, userId, threadId, agentId, null, agentFactory))
                .getAgent();
    }

    /**
     * Ensure a session exists for the given threadId under the anonymous user slot.
     *
     * @param threadId The thread identifier
     * @param agentId The agent type identifier
     * @param name Display name for the thread (may be null)
     * @param agentFactory Factory to create new agents if needed
     * @return The session for this thread
     */
    public ThreadSession ensureSession(
            String threadId, String agentId, String name, Supplier<Agent> agentFactory) {
        return ensureSession(null, threadId, agentId, name, agentFactory);
    }

    /**
     * Ensure a session exists for the given user and thread, creating one if needed.
     *
     * @param userId The user identifier, may be {@code null} for anonymous
     * @param threadId The thread identifier
     * @param agentId The agent type identifier
     * @param name Display name for the thread (may be null)
     * @param agentFactory Factory to create new agents if needed
     * @return The session for this user and thread
     */
    public ThreadSession ensureSession(
            String userId,
            String threadId,
            String agentId,
            String name,
            Supplier<Agent> agentFactory) {
        ensureCapacity();
        return sessions.compute(
                sessionKey(userId, threadId),
                (k, existing) ->
                        upsertSession(existing, userId, threadId, agentId, name, agentFactory));
    }

    /**
     * Check if a session exists and has memory for the given runtime context.
     *
     * <p>The session is keyed by {@link RuntimeContext#getUserId()} and {@link
     * RuntimeContext#getSessionId()}. When the agent is a harness wrapper, the inner {@link
     * ReActAgent} is inspected without closing it.
     *
     * @param runtimeContext The runtime context identifying the user and thread
     * @return true if the session exists and the agent has non-empty memory
     */
    public boolean hasMemory(RuntimeContext runtimeContext) {
        if (runtimeContext == null || runtimeContext.getSessionId() == null) {
            return false;
        }
        ThreadSession session =
                sessions.get(sessionKey(runtimeContext.getUserId(), runtimeContext.getSessionId()));
        if (session == null) {
            return false;
        }

        ReActAgent reActAgent = AguiUtil.asReActAgent(session.getAgent());
        if (reActAgent == null) {
            return false;
        }
        AgentState state = reActAgent.getAgentState(runtimeContext);
        return state != null && !state.getContext().isEmpty();
    }

    /**
     * Get the session for a threadId under the anonymous user slot if it exists.
     *
     * @param threadId The thread identifier
     * @return Optional containing the session, or empty if not found
     */
    public Optional<ThreadSession> getSession(String threadId) {
        return getSession(null, threadId);
    }

    /**
     * Get the session for a user and thread if it exists.
     *
     * @param userId The user identifier, may be {@code null} for anonymous
     * @param threadId The thread identifier
     * @return Optional containing the session, or empty if not found
     */
    public Optional<ThreadSession> getSession(String userId, String threadId) {
        return Optional.ofNullable(sessions.get(sessionKey(userId, threadId)));
    }

    /**
     * Returns an unmodifiable snapshot of all sessions keyed by threadId.
     *
     * <p>When two users share a thread id, the later entry in iteration order overwrites the
     * snapshot. Use {@link ThreadSession#getUserId()} and {@link ThreadSession#getThreadId()} on
     * each value for tenant-safe identity.
     *
     * @return session snapshot
     */
    public Map<String, ThreadSession> getSessions() {
        Map<String, ThreadSession> snapshot = new LinkedHashMap<>();
        for (ThreadSession session : sessions.values()) {
            snapshot.put(session.getThreadId(), session);
        }
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * Remove a session by threadId under the anonymous user slot.
     *
     * @param threadId The thread identifier
     * @return true if a session was removed
     */
    public boolean removeSession(String threadId) {
        return removeSession(null, threadId);
    }

    /**
     * Remove a session by user and thread.
     *
     * @param userId The user identifier, may be {@code null} for anonymous
     * @param threadId The thread identifier
     * @return true if a session was removed
     */
    public boolean removeSession(String userId, String threadId) {
        return sessions.remove(sessionKey(userId, threadId)) != null;
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

    private ThreadSession upsertSession(
            ThreadSession existing,
            String userId,
            String threadId,
            String agentId,
            String name,
            Supplier<Agent> agentFactory) {
        if (existing == null) {
            logger.debug(
                    "Creating new session for user {} threadId {}",
                    normalizeUser(userId),
                    threadId);
            ThreadSession created =
                    new ThreadSession(userId, threadId, agentId, agentFactory.get());
            if (name != null && !name.isBlank()) {
                created.setName(name);
            }
            return created;
        }
        if (!existing.getAgentId().equals(agentId)) {
            logger.debug(
                    "Agent type changed for user {} threadId {}: {} -> {}",
                    normalizeUser(userId),
                    threadId,
                    existing.getAgentId(),
                    agentId);
            ThreadSession replacement =
                    new ThreadSession(userId, threadId, agentId, agentFactory.get());
            replacement.setName(name != null && !name.isBlank() ? name : existing.getName());
            replacement.setArchived(existing.isArchived());
            return replacement;
        }
        if (name != null && !name.isBlank()) {
            existing.setName(name);
        }
        existing.updateLastAccess();
        return existing;
    }

    private static SessionKey sessionKey(String userId, String threadId) {
        return new SessionKey(normalizeUser(userId), threadId);
    }

    private static String normalizeUser(String userId) {
        return userId == null || userId.isBlank() ? ANON_USER : userId;
    }

    private void ensureCapacity() {
        if (sessions.size() >= maxSessions) {
            cleanupExpiredSessions();
            if (sessions.size() >= maxSessions) {
                removeOldestSession();
            }
        }
    }

    /** Remove the oldest session to make room for new ones. */
    private void removeOldestSession() {
        SessionKey oldestKey = null;
        Instant oldestTime = Instant.MAX;

        for (var entry : sessions.entrySet()) {
            if (entry.getValue().getLastAccess().isBefore(oldestTime)) {
                oldestTime = entry.getValue().getLastAccess();
                oldestKey = entry.getKey();
            }
        }

        if (oldestKey != null) {
            sessions.remove(oldestKey);
            logger.debug(
                    "Removed oldest session: user {} thread {}",
                    oldestKey.userId(),
                    oldestKey.threadId());
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

    private record SessionKey(String userId, String threadId) {}

    /** Represents a thread session with its agent and metadata. */
    public static class ThreadSession {

        private final String userId;
        private final String threadId;
        private final String agentId;
        private final Agent agent;
        private final Instant createdAt;
        private Instant lastAccess;
        private volatile String name;
        private volatile boolean archived;

        ThreadSession(String userId, String threadId, String agentId, Agent agent) {
            this.userId = normalizeUser(userId);
            this.threadId = threadId;
            this.agentId = agentId;
            this.agent = agent;
            Instant now = Instant.now();
            this.createdAt = now;
            this.lastAccess = now;
        }

        public String getUserId() {
            return userId;
        }

        public String getThreadId() {
            return threadId;
        }

        public String getAgentId() {
            return agentId;
        }

        public Agent getAgent() {
            return agent;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public Instant getLastAccess() {
            return lastAccess;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public boolean isArchived() {
            return archived;
        }

        public void setArchived(boolean archived) {
            this.archived = archived;
        }

        public void updateLastAccess() {
            this.lastAccess = Instant.now();
        }
    }
}
