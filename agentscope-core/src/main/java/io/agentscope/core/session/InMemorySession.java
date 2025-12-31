/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.session;

import io.agentscope.core.state.StateModule;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of the Session interface.
 *
 * <p>This implementation stores session state in memory using a ConcurrentHashMap. It is suitable
 * for single-process applications where persistence across restarts is not required.
 *
 * <p><b>Thread Safety:</b> This class is thread-safe. It uses ConcurrentHashMap for session storage
 * and creates defensive copies of state data during save operations.
 *
 * <p><b>Important:</b> The {@link StateModule#stateDict()} method should return either an immutable
 * map or a new map instance for each call. If it returns a mutable map that is later modified
 * externally, state consistency cannot be guaranteed.
 *
 * <p><b>Limitations:</b>
 *
 * <ul>
 *   <li>State is lost when the JVM exits
 *   <li>Not suitable for distributed environments
 *   <li>Memory usage grows with number of sessions
 * </ul>
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * Session session = new InMemorySession();
 *
 * // Save session
 * SessionManager.forSessionId("user123")
 *     .withSession(session)
 *     .addComponent(agent)
 *     .saveSession();
 *
 * // Load session
 * SessionManager.forSessionId("user123")
 *     .withSession(session)
 *     .addComponent(agent)
 *     .loadIfExists();
 * }</pre>
 */
public class InMemorySession implements Session {

    /** Storage for session states. Key: sessionId, Value: component states map */
    private final Map<String, SessionData> sessions = new ConcurrentHashMap<>();

    @Override
    public void saveSessionState(String sessionId, Map<String, StateModule> stateModules) {
        Map<String, Map<String, Object>> componentStates = new HashMap<>();
        for (Map.Entry<String, StateModule> entry : stateModules.entrySet()) {
            componentStates.put(entry.getKey(), entry.getValue().stateDict());
        }
        sessions.put(sessionId, new SessionData(componentStates, Instant.now()));
    }

    @Override
    public void loadSessionState(
            String sessionId, boolean allowNotExist, Map<String, StateModule> stateModules) {
        SessionData sessionData = sessions.get(sessionId);
        if (sessionData == null) {
            if (!allowNotExist) {
                throw new IllegalArgumentException("Session not found: " + sessionId);
            }
            return;
        }

        Map<String, Map<String, Object>> componentStates = sessionData.getComponentStates();
        for (Map.Entry<String, StateModule> entry : stateModules.entrySet()) {
            Map<String, Object> state = componentStates.get(entry.getKey());
            if (state != null) {
                entry.getValue().loadStateDict(state, false);
            }
        }
    }

    @Override
    public boolean sessionExists(String sessionId) {
        return sessions.containsKey(sessionId);
    }

    @Override
    public boolean deleteSession(String sessionId) {
        return sessions.remove(sessionId) != null;
    }

    @Override
    public List<String> listSessions() {
        return new ArrayList<>(sessions.keySet());
    }

    @Override
    public SessionInfo getSessionInfo(String sessionId) {
        SessionData sessionData = sessions.get(sessionId);
        if (sessionData == null) {
            return null;
        }

        int componentCount = sessionData.getComponentStates().size();

        return new SessionInfo(
                sessionId,
                componentCount,
                sessionData.getLastModified().toEpochMilli(),
                componentCount);
    }

    /**
     * Get the number of active sessions.
     *
     * @return Number of sessions currently stored
     */
    public int getSessionCount() {
        return sessions.size();
    }

    /**
     * Clear all sessions from memory.
     *
     * <p>This is useful for testing or when you want to reset all state.
     */
    public void clearAll() {
        sessions.clear();
    }

    /** Internal class to hold session data with metadata. */
    private static class SessionData {
        private final Map<String, Map<String, Object>> componentStates;
        private final Instant lastModified;

        SessionData(Map<String, Map<String, Object>> componentStates, Instant lastModified) {
            this.componentStates = new HashMap<>(componentStates);
            this.lastModified = lastModified;
        }

        Map<String, Map<String, Object>> getComponentStates() {
            return componentStates;
        }

        Instant getLastModified() {
            return lastModified;
        }
    }
}
