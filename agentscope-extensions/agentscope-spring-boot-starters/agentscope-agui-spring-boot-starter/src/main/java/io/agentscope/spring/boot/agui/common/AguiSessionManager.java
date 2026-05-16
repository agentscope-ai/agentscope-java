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
import java.util.function.Supplier;

/**
 * Manages agent sessions by threadId for server-side memory management.
 *
 * <p>This interface defines the contract for managing agent instances associated with conversation
 * threads. Implementations may store sessions in-memory (for single-instance deployments) or
 * delegate to external stores like Redis (for distributed deployments).
 *
 * <p><b>Usage:</b>
 *
 * <pre>{@code
 * AguiSessionManager manager = ...;
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
 *
 * @see InMemoryAguiSessionManager
 * @see SessionAwareAguiSessionManager
 */
public interface AguiSessionManager {

    /**
     * Get or create an agent for the given threadId.
     *
     * <p>This method should be thread-safe. If an agent already exists for this threadId with the
     * same agentId, the existing agent should be reused. If the agentId has changed, a new agent
     * should be created.
     *
     * @param threadId The thread identifier
     * @param agentId The agent type identifier
     * @param agentFactory Factory to create new agents if needed
     * @return The agent for this thread
     */
    Agent getOrCreateAgent(String threadId, String agentId, Supplier<Agent> agentFactory);

    /**
     * Check if a session exists and has memory for the given threadId and agentId.
     *
     * @param threadId The thread identifier
     * @param agentId The agent type identifier
     * @return true if the session exists and the agent has non-empty memory
     */
    boolean hasMemory(String threadId, String agentId);

    /**
     * Remove a session by threadId and agentId.
     *
     * @param threadId The thread identifier
     * @param agentId The agent type identifier
     * @return true if a session was removed
     */
    boolean removeSession(String threadId, String agentId);

    /** Clean up sessions that have been inactive for longer than the timeout. */
    void cleanupExpiredSessions();

    /**
     * Get the current number of active sessions.
     *
     * @return Number of sessions
     */
    int getSessionCount();

    /**
     * Save agent state after a request completes.
     *
     * <p>Called when an AG-UI request finishes (the event stream completes).
     * Implementations that delegate to external session stores should persist
     * the agent's state here. In-memory implementations can treat this as a no-op
     * since the agent instance is already cached locally.
     *
     * @param threadId The thread identifier
     * @param agentId The agent type identifier
     * @param agent The agent whose state should be saved
     */
    default void saveAgent(String threadId, String agentId, Agent agent) {
        // Default no-op: in-memory implementations don't need explicit saves
    }

    /** Clear all sessions. */
    void clear();
}
