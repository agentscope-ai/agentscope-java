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
package io.agentscope.core.chat.completions.service;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.session.Session;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Framework-agnostic helper for managing ReActAgent lifecycle in chat completions.
 *
 * <p>This helper handles:
 *
 * <ul>
 *   <li>Creating new agent instances via a {@link Supplier}
 *   <li>Loading agent state from {@link Session} if session exists
 *   <li>Saving agent state to {@link Session} after request completes
 *   <li>Generating session IDs for new sessions
 * </ul>
 *
 * <p>Each request gets a fresh agent instance, with state loaded from and saved to the configured
 * Session storage (e.g., InMemorySession, JsonSession, MysqlSession).
 *
 * <p><b>Usage example:</b>
 *
 * <pre>{@code
 * Session session = new InMemorySession();
 * ChatCompletionsAgentHelper helper = new ChatCompletionsAgentHelper(
 *     () -> ReActAgent.builder().name("myAgent").build(),
 *     session
 * );
 *
 * String sessionId = helper.resolveSessionId(requestSessionId);
 * ReActAgent agent = helper.getAgent(sessionId);
 * // ... use agent ...
 * helper.saveAgentState(sessionId, agent);
 * }</pre>
 */
public class ChatCompletionsAgentHelper {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionsAgentHelper.class);

    private final Supplier<ReActAgent> agentSupplier;
    private final Session session;

    /**
     * Constructs a new ChatCompletionsAgentHelper.
     *
     * @param agentSupplier Supplier for creating new ReActAgent instances
     * @param session The Session for state storage
     * @throws NullPointerException if agentSupplier or session is null
     */
    public ChatCompletionsAgentHelper(Supplier<ReActAgent> agentSupplier, Session session) {
        this.agentSupplier =
                Objects.requireNonNull(agentSupplier, "agentSupplier must not be null");
        this.session = Objects.requireNonNull(session, "session must not be null");
    }

    /**
     * Get a ReActAgent for the given session id.
     *
     * <p>This method creates a new agent instance and loads its state from the configured Session
     * if the session exists.
     *
     * @param sessionId session identifier; should be resolved via {@link #resolveSessionId} first
     * @return a ReActAgent instance with state loaded from Session if exists
     * @throws IllegalStateException if agent creation fails
     */
    public ReActAgent getAgent(String sessionId) {
        // Create a new agent instance
        ReActAgent agent = agentSupplier.get();
        if (agent == null) {
            throw new IllegalStateException("Failed to create ReActAgent: supplier returned null");
        }

        // Load state from Session if exists
        if (agent.loadIfExists(session, sessionId)) {
            log.debug("Loaded agent state from session: {}", sessionId);
        } else {
            log.debug("Created new agent for session: {}", sessionId);
        }

        return agent;
    }

    /**
     * Save the agent's state to the Session.
     *
     * <p>This method should be called after each request completes to persist the agent's state
     * (including memory) for future requests.
     *
     * @param sessionId the session identifier
     * @param agent the agent whose state should be saved
     */
    public void saveAgentState(String sessionId, ReActAgent agent) {
        try {
            agent.saveTo(session, sessionId);
            log.debug("Saved agent state to session: {}", sessionId);
        } catch (Exception e) {
            log.warn("Failed to save agent state for session: {}", sessionId, e);
        }
    }

    /**
     * Generate a session ID if the provided one is null or blank.
     *
     * @param sessionId the original session ID
     * @return the original session ID if valid, or a new UUID if null/blank
     */
    public String resolveSessionId(String sessionId) {
        return (sessionId == null || sessionId.isBlank())
                ? UUID.randomUUID().toString()
                : sessionId;
    }

    /**
     * Get the underlying Session used for state storage.
     *
     * @return the Session instance
     */
    public Session getSession() {
        return session;
    }
}
