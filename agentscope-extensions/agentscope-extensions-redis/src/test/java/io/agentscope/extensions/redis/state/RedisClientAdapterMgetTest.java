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
package io.agentscope.extensions.redis.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RedisClientAdapter#mget(String...)} default implementation.
 *
 * <p>Verifies the backward-compatible fallback that sequentially calls {@link RedisClientAdapter#get(String)}
 * for adapters that predate the atomic MGET method.
 */
class RedisClientAdapterMgetTest {

    /** Minimal in-memory adapter to exercise the default mget implementation. */
    private static class InMemoryAdapter implements RedisClientAdapter {
        private final Map<String, String> store = new HashMap<>();

        @Override
        public void set(String key, String value) {
            store.put(key, value);
        }

        @Override
        public String get(String key) {
            return store.get(key);
        }

        // All other methods throw UnsupportedOperationException (not needed for mget test)
        @Override
        public void rightPushList(String key, String value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> rangeList(String key, long start, long end) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getListLength(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteKeys(String... keys) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addToSet(String key, String member) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<String> getSetMembers(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getSetSize(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean keyExists(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<String> findKeysByPattern(String pattern) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long evalScript(String script, List<String> keys, List<String> args) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {}
    }

    @Test
    void mgetReturnsValuesInOrder() {
        InMemoryAdapter adapter = new InMemoryAdapter();
        adapter.set("k1", "v1");
        adapter.set("k2", "v2");
        adapter.set("k3", "v3");

        List<String> result = adapter.mget("k1", "k2", "k3");
        assertEquals(List.of("v1", "v2", "v3"), result);
    }

    @Test
    void mgetReturnsNullForMissingKeys() {
        InMemoryAdapter adapter = new InMemoryAdapter();
        adapter.set("k1", "v1");

        List<String> result = adapter.mget("k1", "missing", "k1");
        assertEquals(3, result.size());
        assertEquals("v1", result.get(0));
        assertNull(result.get(1));
        assertEquals("v1", result.get(2));
    }

    @Test
    void mgetEmptyKeysReturnsEmptyList() {
        InMemoryAdapter adapter = new InMemoryAdapter();
        List<String> result = adapter.mget();
        assertEquals(0, result.size());
    }

    @Test
    void mgetSingleKey() {
        InMemoryAdapter adapter = new InMemoryAdapter();
        adapter.set("solo", "value");
        List<String> result = adapter.mget("solo");
        assertEquals(List.of("value"), result);
    }
}
