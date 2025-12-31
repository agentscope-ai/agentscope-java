/*
 * Copyright 2024-2026 the original author or authors.
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

import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.state.State;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory implementation of the Session interface.
 *
 * <p>This implementation stores session state in memory using a ConcurrentHashMap. It is suitable
 * for single-process applications where persistence across restarts is not required.
 *
 * <p><b>Thread Safety:</b> This class is thread-safe. It uses ConcurrentHashMap for session storage
 * and creates defensive copies of state data during save operations.
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
 * SessionKey sessionKey = SimpleSessionKey.of("user123");
 *
 * // Save state
 * session.save(sessionKey, "agent_meta", new AgentMetaState("id", "name", "desc", "prompt"));
 *
 * // Load state
 * Optional<AgentMetaState> meta = session.get(sessionKey, "agent_meta", AgentMetaState.class);
 * }</pre>
 */
public class InMemorySession implements Session {

    /** Storage for session states. Key: sessionKey string, Value: state data map */
    private final Map<String, SessionData> sessions = new ConcurrentHashMap<>();

    /**
     * Save a single state value.
     *
     * @param sessionKey the session identifier
     * @param key the state key (e.g., "agent_meta", "toolkit_activeGroups")
     * @param value the state value to save
     */
    @Override
    public void save(SessionKey sessionKey, String key, State value) {
        String sessionKeyStr = serializeSessionKey(sessionKey);
        SessionData data = sessions.computeIfAbsent(sessionKeyStr, k -> new SessionData());
        data.setSingleState(key, value);
    }

    /**
     * Save a list of state values (replacement).
     *
     * <p>Unlike JsonSession which uses incremental append, InMemorySession replaces the entire list.
     * Callers should pass the full list, and InMemorySession stores it as-is.
     *
     * @param sessionKey the session identifier
     * @param key the state key (e.g., "memory_messages")
     * @param values the full list of state values to store
     */
    @Override
    public void save(SessionKey sessionKey, String key, List<? extends State> values) {
        String sessionKeyStr = serializeSessionKey(sessionKey);
        SessionData data = sessions.computeIfAbsent(sessionKeyStr, k -> new SessionData());
        data.setListState(key, values);
    }

    /**
     * Get a single state value.
     *
     * @param sessionKey the session identifier
     * @param key the state key
     * @param type the expected state type
     * @param <T> the state type
     * @return the state value, or empty if not found
     */
    @Override
    public <T extends State> Optional<T> get(SessionKey sessionKey, String key, Class<T> type) {
        String sessionKeyStr = serializeSessionKey(sessionKey);
        SessionData data = sessions.get(sessionKeyStr);
        if (data == null) {
            return Optional.empty();
        }
        State state = data.getSingleState(key);
        if (state == null) {
            return Optional.empty();
        }
        if (!type.isInstance(state)) {
            throw new ClassCastException(
                    "State for key '"
                            + key
                            + "' is of type "
                            + state.getClass().getName()
                            + ", expected "
                            + type.getName());
        }
        return Optional.of(type.cast(state));
    }

    /**
     * Get a list of state values.
     *
     * @param sessionKey the session identifier
     * @param key the state key
     * @param itemType the expected item type
     * @param <T> the item type
     * @return the list of state values, or empty list if not found
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T extends State> List<T> getList(SessionKey sessionKey, String key, Class<T> itemType) {
        String sessionKeyStr = serializeSessionKey(sessionKey);
        SessionData data = sessions.get(sessionKeyStr);
        if (data == null) {
            return List.of();
        }
        List<? extends State> list = data.getListState(key);
        if (list == null) {
            return List.of();
        }
        return (List<T>) list;
    }

    /**
     * Check if a session exists.
     *
     * @param sessionKey the session identifier
     * @return true if the session exists
     */
    @Override
    public boolean exists(SessionKey sessionKey) {
        String sessionKeyStr = serializeSessionKey(sessionKey);
        return sessions.containsKey(sessionKeyStr);
    }

    /**
     * Delete a session and all its data.
     *
     * @param sessionKey the session identifier
     */
    @Override
    public void delete(SessionKey sessionKey) {
        String sessionKeyStr = serializeSessionKey(sessionKey);
        sessions.remove(sessionKeyStr);
    }

    /**
     * List all session keys.
     *
     * @return set of all session keys
     */
    @Override
    public Set<SessionKey> listSessionKeys() {
        return sessions.keySet().stream().map(SimpleSessionKey::of).collect(Collectors.toSet());
    }

    /**
     * Serialize a SessionKey to a string for use as a map key.
     *
     * @param sessionKey the session key
     * @return string representation
     */
    private String serializeSessionKey(SessionKey sessionKey) {
        if (sessionKey instanceof SimpleSessionKey simple) {
            return simple.sessionId();
        }
        return sessionKey.toString();
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

    /** Internal class to hold session data. */
    private static class SessionData {
        private final Map<String, State> singleStates = new ConcurrentHashMap<>();
        private final Map<String, List<State>> listStates = new ConcurrentHashMap<>();

        void setSingleState(String key, State value) {
            singleStates.put(key, value);
        }

        State getSingleState(String key) {
            return singleStates.get(key);
        }

        void setListState(String key, List<? extends State> values) {
            // Use List.copyOf for immutable copy to prevent external modification
            listStates.put(key, List.copyOf(values));
        }

        List<? extends State> getListState(String key) {
            return listStates.get(key);
        }
    }
}
