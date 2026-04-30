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

import io.agentscope.core.agent.Agent;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.state.StateModule;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Distributed implementation of {@link AguiSessionManager} that delegates state management to an
 * external {@link Session} store (e.g., Redis, MySQL).
 *
 * <p>Unlike {@link InMemoryAguiSessionManager}, this implementation does not maintain a local cache
 * of agent instances. Each call to {@link #getOrCreateAgent} creates a new agent via the provided
 * factory and automatically restores its state from the session store via {@code
 * agent.loadIfExists()}. When a request completes, {@link #saveAgent} persists the agent's state
 * back via {@code agent.saveTo()}.
 *
 * <p>This is suitable for distributed deployments where requests for the same conversation thread
 * may be routed to different machines. The shared {@link Session} store ensures conversation state
 * is accessible from any machine.
 *
 * <p><b>Usage:</b>
 *
 * <pre>{@code
 * Session redisSession = RedisSession.builder()
 *     .jedisClient(jedis)
 *     .build();
 *
 * AguiSessionManager manager = new SessionAwareAguiSessionManager(redisSession);
 *
 * // Creates agent, loads state from Redis automatically
 * Agent agent = manager.getOrCreateAgent("thread-123", "default", () -> createAgent());
 *
 * // ... agent processes request ...
 *
 * // Saves state back to Redis
 * manager.saveAgent("thread-123", "default", agent);
 * }</pre>
 */
public class SessionAwareAguiSessionManager implements AguiSessionManager {

    private static final Logger logger =
            LoggerFactory.getLogger(SessionAwareAguiSessionManager.class);

    private final Session session;

    /**
     * Creates a new SessionAwareAguiSessionManager.
     *
     * @param session The shared session store (e.g., RedisSession)
     */
    public SessionAwareAguiSessionManager(Session session) {
        this.session = Objects.requireNonNull(session, "session cannot be null");
    }

    @Override
    public Agent getOrCreateAgent(String threadId, String agentId, Supplier<Agent> agentFactory) {
        logger.debug("Creating agent for threadId: {}, agentId: {}", threadId, agentId);
        Agent agent = agentFactory.get();

        // Restore agent state from external session store if the agent supports it
        if (agent instanceof StateModule stateModule) {
            SessionKey sessionKey = buildSessionKey(agentId, threadId);
            boolean loaded = stateModule.loadIfExists(session, sessionKey);
            logger.debug(
                    "State load for threadId {}, agentId {}: {}",
                    threadId,
                    agentId,
                    loaded ? "restored" : "new session");
        }

        return agent;
    }

    @Override
    public void saveAgent(String threadId, String agentId, Agent agent) {
        if (agent instanceof StateModule stateModule) {
            SessionKey sessionKey = buildSessionKey(agentId, threadId);
            stateModule.saveTo(session, sessionKey);
            logger.debug("Saved agent state for threadId: {}, agentId: {}", threadId, agentId);
        }
    }

    @Override
    public boolean hasMemory(String threadId, String agentId) {
        SessionKey sessionKey = buildSessionKey(agentId, threadId);
        return session.exists(sessionKey);
    }

    @Override
    public boolean removeSession(String threadId, String agentId) {
        SessionKey sessionKey = buildSessionKey(agentId, threadId);
        if (session.exists(sessionKey)) {
            session.delete(sessionKey);
            logger.debug("Removed session for threadId: {}, agentId: {}", threadId, agentId);
            return true;
        }
        return false;
    }

    @Override
    public void cleanupExpiredSessions() {
        // No-op: external session stores (e.g., Redis) manage expiration via TTL
    }

    @Override
    public int getSessionCount() {
        Set<SessionKey> keys = session.listSessionKeys();
        return keys.size();
    }

    @Override
    public void clear() {
        Set<SessionKey> keys = session.listSessionKeys();
        for (SessionKey key : keys) {
            session.delete(key);
        }
        logger.debug("Cleared {} sessions from external store", keys.size());
    }

    /**
     * Build a composite session key from agentId and threadId.
     *
     * <p>The resulting key format is {@code agentId:threadId}, ensuring that
     * different agents maintain separate session state even for the same thread.
     *
     * @param agentId the agent type identifier
     * @param threadId the thread identifier
     * @return a {@link SessionKey} combining agentId and threadId
     */
    private static SessionKey buildSessionKey(String agentId, String threadId) {
        return SimpleSessionKey.of(agentId + ":" + threadId);
    }
}
