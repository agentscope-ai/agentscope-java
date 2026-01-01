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
package io.agentscope.spring.boot.chat.service;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.chat.completions.service.ChatCompletionsAgentHelper;
import io.agentscope.core.session.Session;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Spring Boot service for managing ReActAgent lifecycle in chat completions.
 *
 * <p>This service is a Spring-specific wrapper around {@link ChatCompletionsAgentHelper}, adapting
 * Spring's {@link ObjectProvider} to the framework-agnostic helper class.
 *
 * <p>Features:
 *
 * <ul>
 *   <li>Creates new prototype-scoped agent instances via {@link ObjectProvider}
 *   <li>Loads agent state from {@link Session} if session exists
 *   <li>Saves agent state to {@link Session} after request completes
 *   <li>Generates session IDs for new sessions
 * </ul>
 */
@Service
public class ChatCompletionsAgentService {

    private final ChatCompletionsAgentHelper helper;

    /**
     * Constructs a new ChatCompletionsAgentService.
     *
     * @param agentProvider Provider for creating new ReActAgent instances (prototype-scoped)
     * @param session The Session bean for state storage
     * @throws NullPointerException if agentProvider or session is null
     */
    public ChatCompletionsAgentService(ObjectProvider<ReActAgent> agentProvider, Session session) {
        Objects.requireNonNull(agentProvider, "agentProvider must not be null");
        Objects.requireNonNull(session, "session must not be null");
        this.helper = new ChatCompletionsAgentHelper(agentProvider::getObject, session);
    }

    /**
     * Get a ReActAgent for the given session id.
     *
     * <p>This method creates a new prototype-scoped agent instance and loads its state from the
     * configured Session if the session exists.
     *
     * @param sessionId session identifier; may be null to use a new session with random UUID
     * @return a ReActAgent instance with state loaded from Session if exists
     * @throws IllegalStateException if agent creation fails
     */
    public ReActAgent getAgent(String sessionId) {
        return helper.getAgent(sessionId);
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
        helper.saveAgentState(sessionId, agent);
    }

    /**
     * Generate a session ID if the provided one is null or blank.
     *
     * @param sessionId the original session ID
     * @return the original session ID if valid, or a new UUID if null/blank
     */
    public String resolveSessionId(String sessionId) {
        return helper.resolveSessionId(sessionId);
    }

    /**
     * Get the underlying Session used for state storage.
     *
     * @return the Session instance
     */
    public Session getSession() {
        return helper.getSession();
    }
}
