/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.runtime.managed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.harness.runtime.SessionServiceClient;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Native BaseStore binding. Atomic private IO executes beside its receipt in Session Service. */
public final class RemoteWorkspaceStore implements BaseStore {
    private final SessionServiceClient client;
    private final ObjectMapper mapper;

    public RemoteWorkspaceStore(SessionServiceClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    public boolean supportsAtomicSessionStorage() {
        return true;
    }

    public StoreItem get(List<String> namespace, String key) {
        return mapper.convertValue(call(List.of("get", namespace, key), null), StoreItem.class);
    }

    public List<StoreItem> search(List<String> namespace, int limit, int offset) {
        return mapper.convertValue(
                call(List.of("search", namespace, limit, offset), null),
                mapper.getTypeFactory().constructCollectionType(List.class, StoreItem.class));
    }

    public void put(List<String> namespace, String key, Map<String, Object> value) {
        call(List.of("put", namespace, key, value), null);
    }

    public boolean putIfVersion(
            List<String> namespace, String key, Map<String, Object> value, long version) {
        return call(List.of("cas", namespace, key, value, version), null).asBoolean();
    }

    public void delete(List<String> namespace, String key) {
        call(List.of("delete", namespace, key), null);
    }

    String atomic(
            Object operation, ManagedTurnExecution execution, String frame, int slot, String hash) {
        var receipt =
                Map.of(
                        "scope",
                        execution.scope(),
                        "turnId",
                        execution.turnId(),
                        "epoch",
                        execution.epoch(),
                        "token",
                        execution.guard().token(),
                        "frame",
                        frame,
                        "slot",
                        slot,
                        "requestHash",
                        hash);
        return call(operation, receipt).toString();
    }

    private JsonNode call(Object operation, Object receipt) {
        var request = new LinkedHashMap<String, Object>();
        request.put("operation", operation);
        request.put("receipt", receipt);
        return client.post("prepared/workspace", request);
    }
}
