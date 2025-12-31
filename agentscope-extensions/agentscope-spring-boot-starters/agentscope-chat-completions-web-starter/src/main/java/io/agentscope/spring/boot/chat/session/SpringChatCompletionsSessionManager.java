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
package io.agentscope.spring.boot.chat.session;

import io.agentscope.core.ReActAgent;

/**
 * Spring Boot interface for managing session-scoped ReActAgent instances.
 *
 * <p>This is a simplified interface where the SessionManager is responsible for creating agents
 * internally. The agent creation logic (via Spring's ObjectProvider) is encapsulated within the
 * implementation.
 */
public interface SpringChatCompletionsSessionManager {

    /**
     * Get or create a ReActAgent for the given session id.
     *
     * <p>If the session already has an active, non-expired agent, it is returned. Otherwise, a new
     * agent is created using the internally configured agent factory.
     *
     * @param sessionId session identifier; may be null to create a new session with random UUID
     * @return a ReActAgent instance (new or existing)
     * @throws IllegalStateException if agent creation fails
     */
    ReActAgent getAgent(String sessionId);
}
