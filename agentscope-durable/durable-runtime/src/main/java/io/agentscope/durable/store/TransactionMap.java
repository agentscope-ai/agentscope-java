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
package io.agentscope.durable.store;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.durable.DurableJson;
import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Lazy transaction-local map. Only accessed and changed entries are written. */
public final class TransactionMap<V> extends AbstractMap<String, V> {
    private final RecordStore.Transaction tx;
    private final String prefix;
    private final Class<V> type;
    private final Map<String, V> cache = new LinkedHashMap<>();
    private final Map<String, JsonNode> originals = new LinkedHashMap<>();

    public TransactionMap(RecordStore.Transaction tx, String prefix, Class<V> type) {
        this.tx = tx;
        this.prefix = prefix;
        this.type = type;
    }

    private String part(String key) {
        return prefix + DurableJson.hash(key);
    }

    @Override
    public V get(Object key) {
        if (!(key instanceof String text)) return null;
        if (!cache.containsKey(text)) {
            JsonNode stored = tx.get(part(text));
            JsonNode value = stored == null ? null : stored.get("value");
            originals.put(text, value);
            cache.put(text, value == null ? null : DurableJson.convert(value, type));
        }
        return cache.get(text);
    }

    @Override
    public boolean containsKey(Object key) {
        return get(key) != null;
    }

    @Override
    public V put(String key, V value) {
        V old = get(key);
        cache.put(key, value);
        return old;
    }

    @Override
    public V remove(Object key) {
        V old = get(key);
        if (key instanceof String text) cache.put(text, null);
        return old;
    }

    @Override
    public Set<Entry<String, V>> entrySet() {
        String cursor = null;
        while (true) {
            var page = tx.keys(prefix, cursor, 1000);
            for (var part : page) {
                var record = tx.get(part);
                String key = record.path("key").asText();
                get(key);
                cursor = part;
            }
            if (page.size() < 1000) break;
        }
        var result = new LinkedHashSet<Entry<String, V>>();
        cache.forEach(
                (key, value) -> {
                    if (value != null) result.add(new SimpleImmutableEntry<>(key, value));
                });
        return result;
    }

    public void flush() {
        cache.forEach(
                (key, value) -> {
                    JsonNode updated = value == null ? null : DurableJson.value(value);
                    if (!Objects.equals(updated, originals.get(key))) {
                        tx.put(
                                part(key),
                                updated == null
                                        ? null
                                        : DurableJson.value(Map.of("key", key, "value", updated)));
                    }
                });
    }
}
