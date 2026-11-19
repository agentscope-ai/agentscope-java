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
package io.agentscope.extensions.agentprotocol;

import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link ProtocolSessionRegistry} keeping two records per session under a {@link BaseStore}: the
 * owner record at {@code [agent-protocol, session-registry]} and the pointer to the current turn at
 * {@code [agent-protocol, session-current]}.
 *
 * <p>First use is written with create-if-absent semantics when the store supports compare-and-set,
 * and reverts to a read-back write when it does not. The read-back is what makes the fallback safe
 * enough: the value that ends up stored decides, so a race is detected rather than silently
 * overwritten by the faster writer.
 */
public final class BaseStoreProtocolSessionRegistry implements ProtocolSessionRegistry {

    private static final List<String> NAMESPACE = List.of("agent-protocol", "session-registry");
    private static final List<String> CURRENT = List.of("agent-protocol", "session-current");

    private static final String USER_ID = "user_id";
    private static final String AGENT_ID = "agent_id";
    private static final String CREATED_BY = "created_by";
    private static final String CREATED_AT = "created_at";
    private static final String CURRENT_TASK_ID = "task_id";
    private static final String BOUND_AT = "bound_at";

    private final BaseStore store;

    public BaseStoreProtocolSessionRegistry(BaseStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /** Process-local registry, equivalent to a single-instance deployment. */
    public static BaseStoreProtocolSessionRegistry inMemory() {
        return new BaseStoreProtocolSessionRegistry(new InMemoryStore());
    }

    @Override
    public void registerOrVerify(
            String sessionId, String userId, String agentId, String createdBy) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        StoreItem existing = store.get(NAMESPACE, sessionId);
        if (existing != null) {
            verify(existing, sessionId, userId, agentId);
            return;
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put(USER_ID, userId);
        record.put(AGENT_ID, agentId);
        record.put(CREATED_BY, createdBy);
        record.put(CREATED_AT, Instant.now().toString());

        if (store.putIfVersion(NAMESPACE, sessionId, record, 0L)) {
            return;
        }
        // Either another instance registered it first, or the store has no atomic create. Both
        // cases are settled by reading back what is actually stored.
        StoreItem raced = store.get(NAMESPACE, sessionId);
        if (raced != null) {
            verify(raced, sessionId, userId, agentId);
            return;
        }
        store.put(NAMESPACE, sessionId, record);
        StoreItem written = store.get(NAMESPACE, sessionId);
        if (written != null) {
            verify(written, sessionId, userId, agentId);
        }
    }

    @Override
    public void bindCurrentTurn(String sessionId, String taskId) {
        if (sessionId == null || sessionId.isBlank() || taskId == null || taskId.isBlank()) {
            return;
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put(CURRENT_TASK_ID, taskId);
        value.put(BOUND_AT, Instant.now().toString());
        // Overwrite on purpose: the session lease has already serialized submissions, and the
        // newest
        // turn is by definition the one that owns the conversation.
        store.put(CURRENT, sessionId, value);
    }

    @Override
    public Optional<String> currentTurn(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        StoreItem item = store.get(CURRENT, sessionId);
        if (item == null || item.value() == null) {
            return Optional.empty();
        }
        Object taskId = item.value().get(CURRENT_TASK_ID);
        if (taskId == null || String.valueOf(taskId).isBlank()) {
            return Optional.empty();
        }
        return Optional.of(String.valueOf(taskId));
    }

    private static void verify(StoreItem stored, String sessionId, String userId, String agentId) {
        Map<String, Object> value = stored.value();
        if (value == null) {
            return;
        }
        if (!Objects.equals(value.get(USER_ID), userId)
                || !Objects.equals(value.get(AGENT_ID), agentId)) {
            throw new IllegalArgumentException(
                    "session_owner_mismatch: session "
                            + sessionId
                            + " belongs to another user or agent");
        }
    }
}
