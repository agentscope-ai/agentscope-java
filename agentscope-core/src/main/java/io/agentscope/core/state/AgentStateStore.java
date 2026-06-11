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
package io.agentscope.core.state;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Persistent storage interface for AgentScope agent state.
 *
 * <p>An {@code AgentStateStore} provides save / load / delete / list operations for
 * {@link State} objects keyed by a {@code (userId, sessionId)} pair, allowing agents,
 * memories, toolkits, and other stateful components to be persisted and restored across
 * application runs or user interactions.
 *
 * <p>Slot addressing is intentionally simple:
 *
 * <ul>
 *   <li>{@code sessionId} — non-null, non-blank; identifies a conversation / session.
 *   <li>{@code userId} — nullable. {@code null} represents an anonymous / single-tenant
 *       caller (CLI usage, tests). Implementations group all anonymous sessions under a
 *       single namespace.
 * </ul>
 *
 * <p>Implementations decide how to combine the pair into a storage key (filesystem path,
 * Redis key prefix, SQL column). Callers MUST NOT concatenate them manually before calling.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * AgentStateStore store = new JsonFileAgentStateStore(Path.of("state"));
 *
 * // Save state for an anonymous session
 * store.save(null, "session-1", "agent_state", state);
 *
 * // Save state scoped to a user
 * store.save("alice", "session-1", "agent_state", state);
 *
 * // Load state
 * Optional<AgentState> loaded =
 *         store.get("alice", "session-1", "agent_state", AgentState.class);
 *
 * // List all sessions owned by a user (null lists anonymous sessions)
 * Set<String> mySessions = store.listSessionIds("alice");
 * }</pre>
 */
public interface AgentStateStore {

    /**
     * Save a single state value (full replacement).
     *
     * <p>This method saves a single state object, replacing any existing value with the same key.
     *
     * @param userId nullable user identifier; {@code null} = anonymous
     * @param sessionId session identifier; must be non-null and non-blank
     * @param key the state key (e.g., {@code "agent_state"}, {@code "toolkit_activeGroups"})
     * @param value the state value to save
     */
    void save(String userId, String sessionId, String key, State value);

    /**
     * Save a list of state values.
     *
     * <p>Different implementations may use different storage strategies:
     *
     * <ul>
     *   <li>{@link JsonFileAgentStateStore}: incremental append — only new elements are written
     *   <li>{@link InMemoryAgentStateStore}: full replacement — replaces the entire list
     * </ul>
     *
     * <p>Callers should always pass the full list. The implementation decides the storage strategy.
     *
     * @param userId nullable user identifier
     * @param sessionId session identifier; must be non-null and non-blank
     * @param key the state key (e.g., {@code "memory_messages"})
     * @param values the full list of state values
     */
    void save(String userId, String sessionId, String key, List<? extends State> values);

    /**
     * Get a single state value.
     *
     * @param userId nullable user identifier
     * @param sessionId session identifier; must be non-null and non-blank
     * @param key the state key
     * @param type the expected state type
     * @param <T> the state type
     * @return the state value, or empty if not found
     */
    <T extends State> Optional<T> get(String userId, String sessionId, String key, Class<T> type);

    /**
     * Get a list of state values.
     *
     * @param userId nullable user identifier
     * @param sessionId session identifier; must be non-null and non-blank
     * @param key the state key
     * @param itemType the expected item type
     * @param <T> the item type
     * @return the list of state values, or empty list if not found
     */
    <T extends State> List<T> getList(
            String userId, String sessionId, String key, Class<T> itemType);

    /**
     * Check if a session exists.
     *
     * @param userId nullable user identifier
     * @param sessionId session identifier; must be non-null and non-blank
     * @return true if the session has any persisted state
     */
    boolean exists(String userId, String sessionId);

    /**
     * Delete a session and all its data.
     *
     * @param userId nullable user identifier
     * @param sessionId session identifier; must be non-null and non-blank
     */
    void delete(String userId, String sessionId);

    /**
     * Delete a single state entry within a session.
     *
     * @param userId nullable user identifier
     * @param sessionId session identifier; must be non-null and non-blank
     * @param key the state key to delete
     */
    default void delete(String userId, String sessionId, String key) {
        // Default no-op; implementations should override if they support per-key deletion
    }

    /**
     * List session identifiers visible under the given user namespace.
     *
     * <p>Use {@code userId == null} to list anonymous sessions. Pass a concrete user to list
     * only that user's sessions. There is no API to list across users in one call — that is
     * a separate administrative concern (admin starter handles it by iterating known users).
     *
     * @param userId nullable user identifier
     * @return set of session identifiers stored under {@code userId}
     */
    Set<String> listSessionIds(String userId);

    /**
     * Clean up any resources used by this store. Implementations should override this if they
     * need cleanup.
     */
    default void close() {
        // Default implementation does nothing
    }
}
