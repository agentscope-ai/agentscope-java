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

import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.State;
import io.agentscope.core.state.StateModule;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Session storage interface for AgentScope.
 *
 * <p>Sessions provide persistent storage for state objects, allowing agents, memories, toolkits,
 * and other stateful components to be saved and restored across application runs or user
 * interactions.
 *
 * <p><b>New API (Recommended):</b> Use the methods with {@link SessionKey} parameter for type-safe
 * session identification and {@link State} objects for type-safe storage.
 *
 * <ul>
 *   <li>{@link #save(SessionKey, String, State)} - Save a single state object
 *   <li>{@link #save(SessionKey, String, List)} - Save a list (incremental append)
 *   <li>{@link #get(SessionKey, String, Class)} - Get a single state object
 *   <li>{@link #getList(SessionKey, String, Class)} - Get a list of state objects
 * </ul>
 *
 * <p><b>Legacy API (Deprecated):</b> Methods using {@code String sessionId} are deprecated. Migrate
 * to the new API for better type safety and incremental storage support.
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

    // ==================== New API (Recommended) ====================

    /**
     * Save a single state value (full replacement).
     *
     * <p>This method saves a single state object, replacing any existing value with the same key.
     *
     * @param sessionKey the session identifier
     * @param key the state key (e.g., "agent_meta", "toolkit_activeGroups")
     * @param value the state value to save
     */
    default void save(SessionKey sessionKey, String key, State value) {
        throw new UnsupportedOperationException(
                "New API not implemented. Override this method or use legacy API.");
    }

    /**
     * Save a list of state values (incremental append).
     *
     * <p>Implementations should only append new elements that haven't been persisted yet, rather
     * than overwriting the entire list. This enables efficient incremental storage for large lists
     * like message histories.
     *
     * @param sessionKey the session identifier
     * @param key the state key (e.g., "memory_messages")
     * @param values the list of state values to save
     */
    default void save(SessionKey sessionKey, String key, List<? extends State> values) {
        throw new UnsupportedOperationException(
                "New API not implemented. Override this method or use legacy API.");
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
    default <T extends State> Optional<T> get(SessionKey sessionKey, String key, Class<T> type) {
        throw new UnsupportedOperationException(
                "New API not implemented. Override this method or use legacy API.");
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
    default <T extends State> List<T> getList(
            SessionKey sessionKey, String key, Class<T> itemType) {
        throw new UnsupportedOperationException(
                "New API not implemented. Override this method or use legacy API.");
    }

    /**
     * Check if a session exists.
     *
     * @param sessionKey the session identifier
     * @return true if the session exists
     */
    default boolean exists(SessionKey sessionKey) {
        throw new UnsupportedOperationException(
                "New API not implemented. Override this method or use legacy API.");
    }

    /**
     * Delete a session and all its data.
     *
     * @param sessionKey the session identifier
     */
    default void delete(SessionKey sessionKey) {
        throw new UnsupportedOperationException(
                "New API not implemented. Override this method or use legacy API.");
    }

    /**
     * List all session keys.
     *
     * @return set of all session keys
     */
    default Set<SessionKey> listSessionKeys() {
        throw new UnsupportedOperationException(
                "New API not implemented. Override this method or use legacy API.");
    }

    // ==================== Legacy API (Deprecated) ====================

    /**
     * Save the state of multiple StateModules to a session.
     *
     * <p>This method persists the state of all provided StateModules under the specified session
     * ID. The implementation determines the storage mechanism (files, database, etc.).
     *
     * @param sessionId Unique identifier for the session
     * @param stateModules Map of component names to StateModule instances
     * @deprecated Use the new State-based API with {@link SessionKey} instead: {@link
     *     StateModule#saveTo(Session, SessionKey)}
     */
    @Deprecated
    void saveSessionState(String sessionId, Map<String, StateModule> stateModules);

    /**
     * Load session state into multiple StateModules.
     *
     * <p>This method restores the state of all provided StateModules from the session storage. If
     * the session doesn't exist and allowNotExist is true, the operation completes without error.
     *
     * @param sessionId Unique identifier for the session
     * @param allowNotExist Whether to allow loading from non-existent sessions
     * @param stateModules Map of component names to StateModule instances to load into
     * @deprecated Use the new State-based API with {@link SessionKey} instead: {@link
     *     StateModule#loadFrom(Session, SessionKey)}
     */
    @Deprecated
    void loadSessionState(
            String sessionId, boolean allowNotExist, Map<String, StateModule> stateModules);

    /**
     * Load session state with default allowNotExist=true.
     *
     * @param sessionId Unique identifier for the session
     * @param stateModules Map of component names to StateModule instances to load into
     * @deprecated Use the new State-based API with {@link SessionKey} instead
     */
    @Deprecated
    default void loadSessionState(String sessionId, Map<String, StateModule> stateModules) {
        loadSessionState(sessionId, true, stateModules);
    }

    /**
     * Check if a session exists in storage.
     *
     * @param sessionId Unique identifier for the session
     * @return true if session exists
     * @deprecated Use {@link #exists(SessionKey)} instead
     */
    @Deprecated
    boolean sessionExists(String sessionId);

    /**
     * Delete a session from storage.
     *
     * @param sessionId Unique identifier for the session
     * @return true if session was deleted
     * @deprecated Use {@link #delete(SessionKey)} instead
     */
    @Deprecated
    boolean deleteSession(String sessionId);

    /**
     * Get a list of all session IDs in storage.
     *
     * @return List of session IDs
     * @deprecated Use {@link #listSessionKeys()} instead
     */
    @Deprecated
    List<String> listSessions();

    /**
     * Get information about a session (size, last modified, etc.).
     *
     * @param sessionId Unique identifier for the session
     * @return Session information
     */
    SessionInfo getSessionInfo(String sessionId);

    /**
     * Clean up any resources used by this session manager. Implementations should override this if
     * they need cleanup.
     */
    default void close() {
        // Default implementation does nothing
    }
}
