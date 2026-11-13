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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

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
        // Push the prefix into the store query: a shared namespace may hold the journals of every
        // conversation, and paging it client-side would transfer all of their values.
        return store.listKeys(namespace, prefix);
    }

    @Override
    public Value read(String path) {
        var item = store.get(namespace, path);
        if (item == null) return null;
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
