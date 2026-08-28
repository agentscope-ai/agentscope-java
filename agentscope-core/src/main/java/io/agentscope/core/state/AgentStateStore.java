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

import io.agentscope.core.message.ImagePayloadState;
import io.agentscope.core.message.ImagePayloadTransformer;
import io.agentscope.core.message.Msg;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * store.saveAgentState(null, "session-1", state);
 *
 * // Save state scoped to a user
 * store.saveAgentState("alice", "session-1", state);
 *
 * // Load state
 * Optional<AgentState> loaded = store.getAgentState(
 *         "alice", "session-1", AgentStateLoadMode.LEAN);
 *
 * // List all sessions owned by a user (null lists anonymous sessions)
 * Set<String> mySessions = store.listSessionIds("alice");
 * }</pre>
 */
public interface AgentStateStore {

    /** Canonical key used for the per-session {@link AgentState}. */
    String AGENT_STATE_KEY = "agent_state";

    /** Prefix for content-addressed image payload entries stored outside {@link #AGENT_STATE_KEY}. */
    String IMAGE_PAYLOAD_KEY_PREFIX = "agent_state_image_payload_";

    /**
     * Sentinel version for backends that do not support optimistic concurrency. Also returned by
     * {@link #saveIfVersion} when a CAS write conflicts (and nothing was written).
     */
    long UNVERSIONED = -1L;

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
     * Whether this backend supports optimistic concurrency via {@link #getVersioned} /
     * {@link #saveIfVersion}.
     *
     * <p>Default {@code false}: {@link #saveIfVersion} degrades to unconditional {@link #save}.
     */
    default boolean supportsVersioning() {
        return false;
    }

    /**
     * Load a single state value together with its store version.
     *
     * <p>Default implementation wraps {@link #get} and reports {@link #UNVERSIONED}.
     *
     * @param userId nullable user identifier
     * @param sessionId session identifier; must be non-null and non-blank
     * @param key the state key
     * @param type the expected state type
     * @param <T> the state type
     * @return a {@link VersionedState}; {@code value} is {@code null} when absent. On versioning
     *     backends an absent key reports {@code version == 0}.
     */
    default <T extends State> VersionedState<T> getVersioned(
            String userId, String sessionId, String key, Class<T> type) {
        return new VersionedState<>(get(userId, sessionId, key, type).orElse(null), UNVERSIONED);
    }

    /**
     * Compare-and-swap write of a single state value.
     *
     * <ul>
     *   <li>{@code expectedVersion == 0} — create-if-absent (fails if the key already exists).
     *   <li>{@code expectedVersion == }{@link #UNVERSIONED} — unconditional overwrite (same as
     *       {@link #save}).
     *   <li>otherwise — write only when the stored version equals {@code expectedVersion}.
     * </ul>
     *
     * <p>On success returns the new version. On conflict returns {@link #UNVERSIONED} and does not
     * write. The default implementation always calls {@link #save} and returns {@link
     * #UNVERSIONED}, preserving legacy last-writer-wins behaviour for non-versioning backends.
     *
     * @param userId nullable user identifier
     * @param sessionId session identifier; must be non-null and non-blank
     * @param key the state key
     * @param value the new state value
     * @param expectedVersion the version observed by the caller
     * @return the new version on success, or {@link #UNVERSIONED} on conflict / non-versioning
     *     backends
     */
    default long saveIfVersion(
            String userId, String sessionId, String key, State value, long expectedVersion) {
        save(userId, sessionId, key, value);
        return UNVERSIONED;
    }

    /**
     * Save an agent state after replacing inline base64 images with lightweight references.
     *
     * <p>Content-addressed image entries are persisted before the lightweight agent state. A
     * failure therefore cannot publish a state that references a payload this call failed to
     * persist; a later failure may leave harmless unreferenced payloads.
     *
     * <p>The default implementation is an ordered write, not a transaction spanning multiple
     * keys. Backends that permit {@link #delete(String, String)} to race with writes and require
     * strict atomicity should override this method with a backend-native transaction.
     */
    default void saveAgentState(String userId, String sessionId, AgentState value) {
        saveAgentStateIfVersion(userId, sessionId, value, UNVERSIONED);
    }

    /**
     * Compare-and-swap variant of {@link #saveAgentState(String, String, AgentState)}.
     *
     * <p>The returned version and conflict behavior apply to {@link #AGENT_STATE_KEY}. Image
     * payloads are immutable content-addressed entries and may be safely shared by retries.
     * Backends overriding the non-versioned variant for transactional behavior should override
     * this method as well.
     */
    default long saveAgentStateIfVersion(
            String userId, String sessionId, AgentState value, long expectedVersion) {
        Objects.requireNonNull(value, "value must not be null");
        ImagePayloadTransformer.OffloadResult offloaded =
                ImagePayloadTransformer.offload(value.getContext());

        for (Map.Entry<String, ImagePayloadState> entry : offloaded.payloads().entrySet()) {
            saveImagePayload(userId, sessionId, entry.getKey(), entry.getValue());
        }

        AgentState lightweight = value.copyWithContext(offloaded.messages());
        return saveIfVersion(userId, sessionId, AGENT_STATE_KEY, lightweight, expectedVersion);
    }

    /** Load an agent state, resolving image payloads only when {@code mode == FULL}. */
    default Optional<AgentState> getAgentState(
            String userId, String sessionId, AgentStateLoadMode mode) {
        return Optional.ofNullable(getAgentStateVersioned(userId, sessionId, mode).value());
    }

    /** Load a full, image-hydrated agent state. */
    default Optional<AgentState> getAgentState(String userId, String sessionId) {
        return getAgentState(userId, sessionId, AgentStateLoadMode.FULL);
    }

    /**
     * Load an agent state and its optimistic-concurrency version.
     *
     * <p>{@link AgentStateLoadMode#LEAN} performs only the {@link #AGENT_STATE_KEY} read. {@link
     * AgentStateLoadMode#FULL} additionally loads every referenced image payload and fails if one is
     * missing or fails the Phase 1 integrity checks.
     */
    default VersionedState<AgentState> getAgentStateVersioned(
            String userId, String sessionId, AgentStateLoadMode mode) {
        Objects.requireNonNull(mode, "mode must not be null");
        VersionedState<AgentState> stored =
                getVersioned(userId, sessionId, AGENT_STATE_KEY, AgentState.class);
        if (!stored.isPresent() || mode == AgentStateLoadMode.LEAN) {
            return stored;
        }

        AgentState hydrated =
                stored.value()
                        .copyWithContext(
                                hydrateImagePayloads(
                                        userId, sessionId, stored.value().getContext()));
        return new VersionedState<>(hydrated, stored.version());
    }

    /**
     * Restore image payload references in a detached message list.
     *
     * <p>This is the model-boundary counterpart to {@link #saveAgentState}. It reads only payloads
     * referenced by {@code messages}, leaves inline base64 and URL images unchanged, and never
     * mutates the supplied list or the cached {@link AgentState}. Callers that only inspect history
     * should keep the lightweight messages and avoid this method.
     */
    default List<Msg> hydrateImagePayloads(String userId, String sessionId, List<Msg> messages) {
        Objects.requireNonNull(messages, "messages must not be null");
        Set<String> payloadIds = ImagePayloadTransformer.referencedPayloadIds(messages);
        if (payloadIds.isEmpty()) {
            return List.copyOf(messages);
        }
        return ImagePayloadTransformer.hydrate(
                messages, getImagePayloads(userId, sessionId, payloadIds));
    }

    /**
     * Load image payloads by content identifier.
     *
     * <p>The default implementation performs one generic state read per distinct identifier.
     * Remote backends may override this method with a native batch operation. The returned map
     * must contain every requested identifier; a missing payload is treated as corrupted state.
     */
    default Map<String, ImagePayloadState> getImagePayloads(
            String userId, String sessionId, Set<String> payloadIds) {
        Objects.requireNonNull(payloadIds, "payloadIds must not be null");
        if (payloadIds.isEmpty()) {
            return Map.of();
        }
        Map<String, ImagePayloadState> payloads = new LinkedHashMap<>();
        for (String payloadId : payloadIds) {
            Objects.requireNonNull(payloadId, "payloadIds must not contain null elements");
            ImagePayloadState payload =
                    get(userId, sessionId, imagePayloadKey(payloadId), ImagePayloadState.class)
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Missing image payload: " + payloadId));
            payloads.put(payloadId, payload);
        }
        return Map.copyOf(payloads);
    }

    /** Load a full, image-hydrated agent state together with its store version. */
    default VersionedState<AgentState> getAgentStateVersioned(String userId, String sessionId) {
        return getAgentStateVersioned(userId, sessionId, AgentStateLoadMode.FULL);
    }

    private void saveImagePayload(
            String userId, String sessionId, String payloadId, ImagePayloadState payload) {
        String key = imagePayloadKey(payloadId);
        if (!supportsVersioning()) {
            Optional<ImagePayloadState> existing =
                    get(userId, sessionId, key, ImagePayloadState.class);
            if (existing.isPresent()) {
                if (!existing.get().equals(payload)) {
                    throw new IllegalStateException("Image payload collision: " + payloadId);
                }
                return;
            }
            save(userId, sessionId, key, payload);
            return;
        }
        long version = saveIfVersion(userId, sessionId, key, payload, 0L);
        if (version != UNVERSIONED) {
            return;
        }
        ImagePayloadState concurrent =
                get(userId, sessionId, key, ImagePayloadState.class)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Image payload write conflicted but no value"
                                                        + " exists: "
                                                        + payloadId));
        if (!concurrent.equals(payload)) {
            throw new IllegalStateException("Image payload collision: " + payloadId);
        }
    }

    private static String imagePayloadKey(String payloadId) {
        return IMAGE_PAYLOAD_KEY_PREFIX + payloadId;
    }

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
