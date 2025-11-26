/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.session;

import io.agentscope.core.state.StateModule;
import java.util.List;
import java.util.Map;

/**
 * Abstract base class for session management in AgentScope.
 *
 * Sessions provide persistent storage for StateModule components, allowing
 * agents, memories, toolkits, and other stateful components to be saved
 * and restored across application runs or user interactions.
 *
 * Supports multi-module sessions where multiple StateModules can be saved and
 * loaded together as a cohesive session state.
 */
public interface Session {

    /**
     * Save the state of multiple StateModules to a session.
     *
     * This method persists the state of all provided StateModules under
     * the specified session ID. The implementation determines the storage
     * mechanism (files, database, etc.).
     *
     * @param sessionId Unique identifier for the session
     * @param stateModules Map of component names to StateModule instances
     */
    void saveSessionState(String sessionId, Map<String, StateModule> stateModules);

    /**
     * Load session state into multiple StateModules.
     *
     * This method restores the state of all provided StateModules from
     * the session storage. If the session doesn't exist and allowNotExist
     * is true, the operation completes without error.
     *
     * @param sessionId Unique identifier for the session
     * @param allowNotExist Whether to allow loading from non-existent sessions
     * @param stateModules Map of component names to StateModule instances to load into
     */
    void loadSessionState(
            String sessionId, boolean allowNotExist, Map<String, StateModule> stateModules);

    /**
     * Load session state with default allowNotExist=true.
     *
     * @param sessionId Unique identifier for the session
     * @param stateModules Map of component names to StateModule instances to load into
     */
    default void loadSessionState(String sessionId, Map<String, StateModule> stateModules) {
        loadSessionState(sessionId, true, stateModules);
    }

    /**
     * Check if a session exists in storage.
     *
     * @param sessionId Unique identifier for the session
     * @return true if session exists
     */
    boolean sessionExists(String sessionId);

    /**
     * Delete a session from storage.
     *
     * @param sessionId Unique identifier for the session
     * @return true if session was deleted
     */
    boolean deleteSession(String sessionId);

    /**
     * Get a list of all session IDs in storage.
     *
     * @return List of session IDs
     */
    List<String> listSessions();

    /**
     * Get information about a session (size, last modified, etc.).
     *
     * @param sessionId Unique identifier for the session
     * @return Session information
     */
    SessionInfo getSessionInfo(String sessionId);

    /**
     * Clean up any resources used by this session manager.
     * Implementations should override this if they need cleanup.
     */
    default void close() {
        // Default implementation does nothing
    }
}
