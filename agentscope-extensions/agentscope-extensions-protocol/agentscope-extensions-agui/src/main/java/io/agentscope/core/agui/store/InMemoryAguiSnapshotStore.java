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
package io.agentscope.core.agui.store;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory {@link AguiSnapshotStore} backed by a {@link ConcurrentHashMap}.
 *
 * <p>Retains at most {@code maxThreads} snapshots; when the limit is exceeded the snapshot with the
 * oldest {@code updatedAt} is evicted in a single pass (no external dependencies).
 */
public final class InMemoryAguiSnapshotStore implements AguiSnapshotStore {

    private final int maxThreads;
    private final ConcurrentMap<String, AguiThreadSnapshot> snapshots = new ConcurrentHashMap<>();

    /** Create a store with the default capacity of 1000 threads. */
    public InMemoryAguiSnapshotStore() {
        this(1000);
    }

    /**
     * Create a store with a fixed capacity.
     *
     * @param maxThreads the maximum number of threads to retain
     */
    public InMemoryAguiSnapshotStore(int maxThreads) {
        if (maxThreads <= 0) {
            throw new IllegalArgumentException("maxThreads must be positive");
        }
        this.maxThreads = maxThreads;
    }

    @Override
    public void save(AguiThreadSnapshot snapshot) {
        if (snapshot == null || snapshot.threadId() == null) {
            return;
        }
        snapshots.put(snapshot.threadId(), snapshot);
        evictIfOverCapacity();
    }

    @Override
    public Optional<AguiThreadSnapshot> find(String threadId) {
        if (threadId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshots.get(threadId));
    }

    @Override
    public void delete(String threadId) {
        if (threadId != null) {
            snapshots.remove(threadId);
        }
    }

    private void evictIfOverCapacity() {
        while (snapshots.size() > maxThreads) {
            Map.Entry<String, AguiThreadSnapshot> oldest = null;
            for (Map.Entry<String, AguiThreadSnapshot> entry : snapshots.entrySet()) {
                if (oldest == null
                        || entry.getValue().updatedAt() < oldest.getValue().updatedAt()) {
                    oldest = entry;
                }
            }
            if (oldest == null) {
                break;
            }
            snapshots.remove(oldest.getKey(), oldest.getValue());
        }
    }
}
