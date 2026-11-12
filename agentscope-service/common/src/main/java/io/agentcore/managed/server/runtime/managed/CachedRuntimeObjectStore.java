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

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/** Byte-bounded cache of immutable content, never mutable heads or uncommitted execution decisions. */
public final class CachedRuntimeObjectStore implements RuntimeObjectStore {
    private record Entry(Reference reference, byte[] content) {}

    private final RuntimeObjectStore delegate;
    private final long limit;
    private final int itemLimit;
    private final MeterRegistry metrics;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, .75f, true);
    private long bytes;

    public CachedRuntimeObjectStore(
            RuntimeObjectStore delegate, long limit, int itemLimit, MeterRegistry metrics) {
        if (limit < 0 || limit > 1024L * 1024 * 1024 || itemLimit < 1 || itemLimit > MAX_BYTES)
            throw new IllegalArgumentException("Invalid immutable cache budget");
        this.delegate = delegate;
        this.limit = limit;
        this.itemLimit = itemLimit;
        this.metrics = metrics;
        metrics.gauge(
                "managed.agents.object.store.cache.bytes",
                List.of(Tag.of("lane", "shared")),
                this,
                CachedRuntimeObjectStore::bytes);
        metrics.gauge(
                "managed.agents.object.store.cache.entries",
                List.of(Tag.of("lane", "shared")),
                this,
                CachedRuntimeObjectStore::size);
    }

    public String backendId() {
        return delegate.backendId();
    }

    public synchronized long bytes() {
        return bytes;
    }

    public synchronized int size() {
        return entries.size();
    }

    private synchronized Entry find(String key) {
        return entries.get(key);
    }

    private synchronized void remember(String key, Reference reference, byte[] content) {
        if (content.length == 0
                || content.length > itemLimit
                || content.length > limit
                || entries.containsKey(key)) return;
        while (bytes + content.length > limit && !entries.isEmpty()) {
            var first = entries.keySet().iterator();
            var entry = entries.remove(first.next());
            bytes -= entry.content().length;
        }
        entries.put(key, new Entry(reference, Arrays.copyOf(content, content.length)));
        bytes += content.length;
    }

    private void hit(String operation) {
        metrics.counter("managed.agents.object.store.cache.hits", "operation", operation)
                .increment();
    }

    public Reference put(String scope, String kind, String key, byte[] content) {
        if ("session-log".equals(kind)) return delegate.put(scope, kind, key, content);
        String name = RuntimeObjectStore.objectKey(scope, kind, key);
        var cached = find(name);
        if (cached != null) {
            if (content.length != cached.reference().size()
                    || !RuntimeObjectStore.sha256(content).equals(cached.reference().sha256()))
                throw new ManagedStateException(
                        "runtime_object_conflict", "Immutable object cannot change");
            hit("put");
            return cached.reference();
        }
        var ref = delegate.put(scope, kind, key, content);
        remember(name, ref, content);
        return ref;
    }

    public byte[] read(String scope, String kind, String key) {
        String name = RuntimeObjectStore.objectKey(scope, kind, key);
        var cached = find(name);
        if (cached != null) {
            hit("get");
            return Arrays.copyOf(cached.content(), cached.content().length);
        }
        byte[] content = delegate.read(scope, kind, key);
        remember(
                name,
                new Reference(
                        backendId(),
                        scope,
                        kind,
                        key,
                        RuntimeObjectStore.sha256(content),
                        content.length),
                content);
        return content;
    }

    @Override
    public byte[] read(Reference reference) {
        if (!backendId().equals(reference.backendId()))
            throw new ManagedStateException("object_backend_mismatch", "Object backend differs");
        String name =
                RuntimeObjectStore.objectKey(reference.scope(), reference.kind(), reference.key());
        var cached = find(name);
        if (cached != null) {
            if (reference.size() != cached.reference().size()
                    || !reference.sha256().equals(cached.reference().sha256()))
                throw new ManagedStateException(
                        "runtime_object_invalid", "Cached immutable identity differs");
            hit("get");
            return Arrays.copyOf(cached.content(), cached.content().length);
        }
        byte[] content = delegate.read(reference);
        remember(name, reference, content);
        return content;
    }

    public boolean exists(String scope, String kind, String key) {
        if (find(RuntimeObjectStore.objectKey(scope, kind, key)) != null) {
            hit("head");
            return true;
        }
        return delegate.exists(scope, kind, key);
    }

    public void close() {
        synchronized (this) {
            entries.clear();
            bytes = 0;
        }
        delegate.close();
    }
}
