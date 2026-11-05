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
package io.agentscope.harness.agent.session;

import io.agentscope.core.session.AtomicSessionStorage;
import io.agentscope.core.session.SessionLogException;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.BulkReadableStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Reuses the workspace's distributed store and resolved namespace, with a private runtime partition. */
public final class StoreSessionStorage implements AtomicSessionStorage {
    private final BaseStore store;
    private final List<String> namespace;

    public StoreSessionStorage(BaseStore store, List<String> namespace) {
        if (!store.supportsAtomicSessionStorage())
            throw new UnsupportedOperationException(
                    "Workspace store does not declare atomic session storage: "
                            + store.getClass().getName());
        this.store = store;
        var scoped = new ArrayList<>(namespace);
        scoped.add("__agentscope_session_log_v1__");
        this.namespace = List.copyOf(scoped);
    }

    @Override
    public List<String> listPaths(String prefix) {
        var paths = new TreeSet<String>();
        final int pageSize = 256;
        for (int offset = 0; ; offset += pageSize) {
            var page = store.search(namespace, pageSize, offset);
            for (var item : page) if (item.key().startsWith(prefix)) paths.add(item.key());
            if (page.size() < pageSize) return List.copyOf(paths);
        }
    }

    @Override
    public Map<String, Value> readAll(Collection<String> paths) {
        var wanted = new HashSet<>(paths);
        if (wanted.isEmpty()) return Map.of();
        if (wanted.size() == 1) return mergeMissing(new HashMap<>(), wanted);
        if (store instanceof BulkReadableStore bulk) {
            var result = new HashMap<String, Value>();
            for (var entry : bulk.getAll(namespace, wanted).entrySet())
                result.put(entry.getKey(), toValue(entry.getValue()));
            return result;
        }
        return readWithSearch(wanted);
    }

    private Map<String, Value> readWithSearch(Collection<String> wanted) {
        var result = new HashMap<String, Value>();
        final int pageSize = 256;
        try {
            for (int offset = 0; ; offset += pageSize) {
                var page = store.search(namespace, pageSize, offset);
                for (var item : page)
                    if (wanted.contains(item.key()) && !result.containsKey(item.key()))
                        result.put(item.key(), toValue(item));
                if (result.size() == wanted.size()) return result;
                // A short page is not proof that the listing ended: stores such as Redis may
                // skip stale index entries, so keep paging until a page is actually empty.
                if (page.isEmpty()) return mergeMissing(result, wanted);
            }
        } catch (UnsupportedOperationException ignored) {
            return mergeMissing(new HashMap<>(), wanted);
        }
    }

    private Map<String, Value> mergeMissing(Map<String, Value> result, Collection<String> wanted) {
        for (String path : wanted) {
            if (result.containsKey(path)) continue;
            var value = read(path);
            if (value != null) result.put(path, value);
        }
        return result;
    }

    @Override
    public Value read(String path) {
        var item = store.get(namespace, path);
        return item == null ? null : toValue(item);
    }

    private static Value toValue(StoreItem item) {
        if (item.version() < 1)
            throw new SessionLogException("Workspace store returned an unversioned journal object");
        Object value = item.value().get("bytes");
        if (!(value instanceof String encoded))
            throw new SessionLogException("Invalid journal object");
        return new Value(item.version(), Base64.getDecoder().decode(encoded));
    }

    @Override
    public boolean compareAndSet(String path, long expected, byte[] bytes) {
        return store.putIfVersion(
                namespace,
                path,
                Map.of("bytes", Base64.getEncoder().encodeToString(bytes)),
                expected);
    }
}
