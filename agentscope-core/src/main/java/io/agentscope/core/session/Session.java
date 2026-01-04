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
import io.agentscope.core.state.State;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Session storage interface for AgentScope.
 *
 * <p>Sessions provide persistent storage for state objects, allowing agents, memories, toolkits,
 * and other stateful components to be saved and restored across application runs or user
 * interactions.
 *
 * <ul>
 *   <li>{@link #save(SessionKey, String, State)} - Save a single state object
 *   <li>{@link #save(SessionKey, String, List)} - Save a list (incremental append)
 *   <li>{@link #get(SessionKey, String, Class)} - Get a single state object
 *   <li>{@link #getList(SessionKey, String, Class)} - Get a list of state objects
 * </ul>
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * Session session = new JsonSession(Path.of("sessions"));
 * SessionKey sessionKey = SimpleSessionKey.of("user_123");
 *
 * // Save state
 * session.save(sessionKey, "agent_meta", new AgentMetaState("id", "name", "desc", "prompt"));
 * session.save(sessionKey, "memory_messages", messages);  // incremental append
 *
 * // Load state
 * Optional<AgentMetaState> meta = session.get(sessionKey, "agent_meta", AgentMetaState.class);
 * List<Msg> messages = session.getList(sessionKey, "memory_messages", Msg.class);
 * }</pre>
 */
public interface Session {

    /**
     * Save a single state value (full replacement).
     *
     * <p>This method saves a single state object, replacing any existing value with the same key.
     *
     * @param sessionKey the session identifier
     * @param key the state key (e.g., "agent_meta", "toolkit_activeGroups")
     * @param value the state value to save
     */
    void save(SessionKey sessionKey, String key, State value);

    /**
     * Save a list of state values.
     *
     * <p>Different implementations may use different storage strategies:
     *
     * <ul>
     *   <li>JsonSession: Incremental append - only appends new elements not yet persisted
     *   <li>InMemorySession: Full replacement - replaces the entire list
     * </ul>
     *
     * <p>Callers should always pass the full list. The implementation decides the storage strategy.
     *
     * @param sessionKey the session identifier
     * @param key the state key (e.g., "memory_messages")
     * @param values the full list of state values
     */
    void save(SessionKey sessionKey, String key, List<? extends State> values);

    /**
     * Get a single state value.
     *
     * @param sessionKey the session identifier
     * @param key the state key
     * @param type the expected state type
     * @param <T> the state type
     * @return the state value, or empty if not found
     */
    <T extends State> Optional<T> get(SessionKey sessionKey, String key, Class<T> type);

    /**
     * Get a list of state values.
     *
     * @param sessionKey the session identifier
     * @param key the state key
     * @param itemType the expected item type
     * @param <T> the item type
     * @return the list of state values, or empty list if not found
     */
    <T extends State> List<T> getList(SessionKey sessionKey, String key, Class<T> itemType);

    /**
     * Check if a session exists.
     *
     * @param sessionKey the session identifier
     * @return true if the session exists
     */
    boolean exists(SessionKey sessionKey);

    /**
     * Delete a session and all its data.
     *
     * @param sessionKey the session identifier
     */
    void delete(SessionKey sessionKey);

    /**
     * List all session keys.
     *
     * @return set of all session keys
     */
    Set<SessionKey> listSessionKeys();

    /**
     * Clean up any resources used by this session manager. Implementations should override this if
     * they need cleanup.
     */
    default void close() {
        // Default implementation does nothing
    }
}
