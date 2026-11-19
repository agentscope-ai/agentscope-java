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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link ProtocolTaskContextStore} keeping one entry per task under {@code [agent-protocol,
 * task-context-store]} of a {@link BaseStore}.
 *
 * <p>All five fields are stored flat and {@code raw} as a nested map. Reading rebuilds the record
 * through its canonical constructor rather than re-deriving it from {@code raw}: a submission whose
 * parsed {@code user_id} differs from the map — or a map that lost the key — would otherwise have the
 * stored value overwritten by the derivation.
 */
public final class BaseStoreProtocolTaskContextStore implements ProtocolTaskContextStore {

    private static final List<String> NAMESPACE = List.of("agent-protocol", "task-context-store");

    private static final String USER_ID = "user_id";
    private static final String PARENT_SESSION_ID = "parent_session_id";
    private static final String PARENT_AGENT_ID = "parent_agent_id";
    private static final String DETAIL = "detail";
    private static final String RAW = "raw";

    private final BaseStore store;

    public BaseStoreProtocolTaskContextStore(BaseStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /** Process-local store, equivalent to a single-instance deployment. */
    public static BaseStoreProtocolTaskContextStore inMemory() {
        return new BaseStoreProtocolTaskContextStore(new InMemoryStore());
    }

    @Override
    public void save(String taskId, ProtocolTaskContext context) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        store.put(NAMESPACE, taskId, toMap(Objects.requireNonNull(context, "context")));
    }

    @Override
    public Optional<ProtocolTaskContext> find(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Optional.empty();
        }
        StoreItem item = store.get(NAMESPACE, taskId);
        return item == null || item.value() == null
                ? Optional.empty()
                : Optional.of(fromMap(item.value()));
    }

    @Override
    public void clear(String taskId) {
        if (taskId != null && !taskId.isBlank()) {
            store.delete(NAMESPACE, taskId);
        }
    }

    private static Map<String, Object> toMap(ProtocolTaskContext context) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put(USER_ID, context.userId());
        value.put(PARENT_SESSION_ID, context.parentSessionId());
        value.put(PARENT_AGENT_ID, context.parentAgentId());
        value.put(DETAIL, context.detail());
        value.put(RAW, context.raw());
        return value;
    }

    @SuppressWarnings("unchecked")
    private static ProtocolTaskContext fromMap(Map<String, Object> value) {
        Object raw = value.get(RAW);
        return new ProtocolTaskContext(
                string(value.get(USER_ID)),
                string(value.get(PARENT_SESSION_ID)),
                string(value.get(PARENT_AGENT_ID)),
                detail(value.get(DETAIL)),
                raw instanceof Map<?, ?> map ? (Map<String, Object>) map : null);
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** Absent or blank detail means the documented default, as on the submission path. */
    private static String detail(Object value) {
        String detail = string(value);
        return detail == null || detail.isBlank() ? "status" : detail;
    }
}
