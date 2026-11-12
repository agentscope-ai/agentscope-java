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

package io.agentscope.durable.agentscope;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.HarnessContext;
import io.agentscope.durable.SessionResources;
import io.agentscope.durable.SessionResources.Operation;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Session-private Harness workspace, including atomic IO receipts and checkpoint/fork data. */
public final class DurableWorkspaceStore implements BaseStore {
    private final HarnessContext context;
    private final Supplier<String> identities;
    private final Supplier<String> timestamp;

    public DurableWorkspaceStore(HarnessContext context) {
        this(context, () -> UUID.randomUUID().toString(), () -> null);
    }

    DurableWorkspaceStore(
            HarnessContext context, Supplier<String> identities, Supplier<String> timestamp) {
        this.context = context;
        this.identities = identities;
        this.timestamp = timestamp;
    }

    public boolean supportsAtomicJournalStorage() {
        return true;
    }

    private JsonNode call(
            Operation operation,
            List<String> namespace,
            String key,
            Map<String, Object> value,
            long version,
            int limit,
            int offset) {
        String time = timestamp.get();
        // Harness FileData currently stamps Instant.now(). Freeze its generated metadata during
        // finalization, so the same logical write retains its receipt after process recovery.
        if (time != null
                && value != null
                && value.containsKey("content")
                && value.containsKey("encoding")
                && value.containsKey("modified_at")) {
            var stable = new LinkedHashMap<>(value);
            if (Objects.equals(value.get("created_at"), value.get("modified_at")))
                stable.put("created_at", time);
            stable.put("modified_at", time);
            value = stable;
        }
        return context.resource(
                new SessionResources.Request(
                        identities.get(),
                        operation,
                        namespace,
                        key,
                        DurableJson.value(value),
                        version,
                        limit,
                        offset));
    }

    @SuppressWarnings("unchecked")
    private StoreItem item(JsonNode node) {
        if (node == null || node.isNull()) return null;
        var value = DurableJson.convert(node, SessionResources.Value.class);
        return new StoreItem(
                value.key(), DurableJson.convert(value.value(), Map.class), value.version());
    }

    public StoreItem get(List<String> namespace, String key) {
        return item(call(Operation.GET, namespace, key, null, 0, 0, 0));
    }

    public void put(List<String> namespace, String key, Map<String, Object> value) {
        call(Operation.PUT, namespace, key, value, 0, 0, 0);
    }

    public boolean putIfVersion(
            List<String> namespace, String key, Map<String, Object> value, long expectedVersion) {
        return call(Operation.CAS, namespace, key, value, expectedVersion, 0, 0).asBoolean();
    }

    public void delete(List<String> namespace, String key) {
        call(Operation.DELETE, namespace, key, null, 0, 0, 0);
    }

    public List<StoreItem> search(List<String> namespace, int limit, int offset) {
        var items = new ArrayList<StoreItem>();
        for (var value : call(Operation.SEARCH, namespace, null, null, 0, limit, offset))
            items.add(item(value));
        return List.copyOf(items);
    }
}
