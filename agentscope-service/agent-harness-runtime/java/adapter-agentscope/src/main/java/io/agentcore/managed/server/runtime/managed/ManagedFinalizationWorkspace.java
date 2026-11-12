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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Private Harness IO is a recovery fact, never a best-effort session-log record. */
final class ManagedFinalizationWorkspace implements BaseStore {
    private final ManagedTurnExecution execution;
    private final String frame;
    private final BaseStore delegate;
    private final IntSupplier slots;
    private final ObjectMapper mapper;

    ManagedFinalizationWorkspace(
            ManagedTurnExecution execution,
            String frame,
            BaseStore delegate,
            IntSupplier slots,
            ObjectMapper mapper) {
        this.execution = execution;
        this.frame = frame;
        this.delegate = delegate;
        this.slots = slots;
        this.mapper = mapper;
    }

    public boolean supportsAtomicSessionStorage() {
        return delegate.supportsAtomicSessionStorage();
    }

    public StoreItem get(List<String> namespace, String key) {
        String value = read(List.of("get", namespace, key), () -> delegate.get(namespace, key));
        return JsonUtils.getJsonCodec().fromJson(value, StoreItem.class);
    }

    public List<StoreItem> search(List<String> namespace, int limit, int offset) {
        String value =
                read(
                        List.of("search", namespace, limit, offset),
                        () -> delegate.search(namespace, limit, offset));
        List<?> rows = JsonUtils.getJsonCodec().fromJson(value, List.class);
        return rows.stream()
                .map(row -> JsonUtils.getJsonCodec().convertValue(row, StoreItem.class))
                .toList();
    }

    public void put(List<String> namespace, String key, Map<String, Object> value) {
        write(
                List.of("put", namespace, key, fileContent(value)),
                List.of("put", namespace, key, value),
                () -> {
                    delegate.put(namespace, key, value);
                    return true;
                });
    }

    public boolean putIfVersion(
            List<String> namespace, String key, Map<String, Object> value, long version) {
        return write(
                List.of("cas", namespace, key, fileContent(value), version),
                List.of("cas", namespace, key, value, version),
                () -> delegate.putIfVersion(namespace, key, value, version));
    }

    public void delete(List<String> namespace, String key) {
        write(
                List.of("delete", namespace, key),
                List.of("delete", namespace, key),
                () -> {
                    delegate.delete(namespace, key);
                    return true;
                });
    }

    private String read(Object request, Supplier<Object> action) {
        execution.check();
        if (delegate instanceof RemoteWorkspaceStore remote)
            return remote.atomic(request, execution, frame, slots.getAsInt(), hash(request));
        return execution
                .journal()
                .finalizationRead(
                        execution.owner(),
                        frame,
                        slots.getAsInt(),
                        hash(request),
                        () -> JsonUtils.getJsonCodec().toJson(action.get()));
    }

    private boolean write(Object request, Object operation, Supplier<Boolean> action) {
        execution.check();
        if (delegate instanceof RemoteWorkspaceStore remote)
            return Boolean.parseBoolean(
                    remote.atomic(operation, execution, frame, slots.getAsInt(), hash(request)));
        if (!(delegate instanceof ManagedTransactionalWorkspaceStore transactional)
                || !transactional.participatesIn(execution.journal()))
            throw new ManagedStateException(
                    "workspace_not_transactional",
                    "Finalization requires workspace writes in the execution metadata transaction");
        return Boolean.parseBoolean(
                execution
                        .journal()
                        .finalizationWrite(
                                execution.owner(),
                                frame,
                                slots.getAsInt(),
                                hash(request),
                                () -> String.valueOf(action.get())));
    }

    private String hash(Object request) {
        try {
            Object canonical =
                    mapper.readValue(JsonUtils.getJsonCodec().toJson(request), Object.class);
            return ManagedScope.fingerprint(
                    mapper.writer()
                            .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                            .writeValueAsString(canonical));
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Cannot encode private storage operation", invalid);
        }
    }

    private Map<String, Object> fileContent(Map<String, Object> value) {
        var content = new LinkedHashMap<>(value);
        // RemoteFilesystem regenerates envelope timestamps when replaying the same file write.
        content.remove("created_at");
        content.remove("modified_at");
        return content;
    }
}
