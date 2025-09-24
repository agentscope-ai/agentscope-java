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
 * This follows the Python agentscope SessionBase pattern, supporting
 * multi-module sessions where multiple StateModules can be saved and
 * loaded together as a cohesive session state.
 */
public abstract class SessionBase {

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
    public abstract void saveSessionState(String sessionId, Map<String, StateModule> stateModules);

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
    public abstract void loadSessionState(
            String sessionId, boolean allowNotExist, Map<String, StateModule> stateModules);

    /**
     * Load session state with default allowNotExist=true.
     *
     * @param sessionId Unique identifier for the session
     * @param stateModules Map of component names to StateModule instances to load into
     */
    public void loadSessionState(String sessionId, Map<String, StateModule> stateModules) {
        loadSessionState(sessionId, true, stateModules);
    }

    /**
     * Check if a session exists in storage.
     *
     * @param sessionId Unique identifier for the session
     * @return true if session exists
     */
    public abstract boolean sessionExists(String sessionId);

    /**
     * Delete a session from storage.
     *
     * @param sessionId Unique identifier for the session
     * @return true if session was deleted
     */
    public abstract boolean deleteSession(String sessionId);

    /**
     * Get a list of all session IDs in storage.
     *
     * @return List of session IDs
     */
    public abstract List<String> listSessions();

    /**
     * Get information about a session (size, last modified, etc.).
     *
     * @param sessionId Unique identifier for the session
     * @return Session information
     */
    public abstract SessionInfo getSessionInfo(String sessionId);

    /**
     * Clean up any resources used by this session manager.
     * Implementations should override this if they need cleanup.
     */
    public void close() {
        // Default implementation does nothing
    }

    /**
     * Validate a session ID format.
     *
     * @param sessionId Session ID to validate
     * @throws IllegalArgumentException if session ID is invalid
     */
    protected void validateSessionId(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("Session ID cannot be null or empty");
        }
        if (sessionId.contains("/") || sessionId.contains("\\")) {
            throw new IllegalArgumentException("Session ID cannot contain path separators");
        }
        if (sessionId.length() > 255) {
            throw new IllegalArgumentException("Session ID cannot exceed 255 characters");
        }
    }

    /**
     * Information about a session.
     */
    public static class SessionInfo {
        private final String sessionId;
        private final long size;
        private final long lastModified;
        private final int componentCount;

        public SessionInfo(String sessionId, long size, long lastModified, int componentCount) {
            this.sessionId = sessionId;
            this.size = size;
            this.lastModified = lastModified;
            this.componentCount = componentCount;
        }

        public String getSessionId() {
            return sessionId;
        }

        public long getSize() {
            return size;
        }

        public long getLastModified() {
            return lastModified;
        }

        public int getComponentCount() {
            return componentCount;
        }

        @Override
        public String toString() {
            return String.format(
                    "SessionInfo{id='%s', size=%d, lastModified=%d, components=%d}",
                    sessionId, size, lastModified, componentCount);
        }
    }
}
