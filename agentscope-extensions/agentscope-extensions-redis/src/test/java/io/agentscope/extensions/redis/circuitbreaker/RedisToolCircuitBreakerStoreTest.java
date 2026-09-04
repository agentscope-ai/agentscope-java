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
package io.agentscope.extensions.redis.circuitbreaker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.tool.circuitbreaker.ToolCircuitSnapshot;
import io.agentscope.extensions.redis.state.RedisClientAdapter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Client-side behaviour of {@link RedisToolCircuitBreakerStore}: key naming, argument passing and
 * the decoding of persisted circuit values.
 *
 * <p>Scope note: the two Lua scripts are executed by Redis, so a fake client cannot run them. These
 * tests cover the Java side — which keys are addressed, which arguments the scripts receive, and how
 * stored values are decoded, including values a healthy writer would never produce. The scripts'
 * server-side effects need a live Redis to verify.
 */
class RedisToolCircuitBreakerStoreTest {

    private static final String TOOL = "query_weather";

    private final RecordingRedisClient client = new RecordingRedisClient();

    // ==================== Key naming ====================

    @Test
    void keysCarryThePrefixAndDistinctSuffixes() {
        RedisToolCircuitBreakerStore store = store();

        store.recordFailure(TOOL);
        store.open(TOOL, 1_000L);

        assertEquals(
                List.of("cb:query_weather:fail", "cb:query_weather:circuit"), client.scriptKeys);
    }

    @Test
    void resetFailuresDeletesOnlyTheCounter() {
        RedisToolCircuitBreakerStore store = store();

        store.resetFailures(TOOL);

        assertEquals(List.of("cb:query_weather:fail"), client.deleted);
    }

    @Test
    void closeDeletesOnlyTheCircuitKey() {
        RedisToolCircuitBreakerStore store = store();

        store.close(TOOL);

        assertEquals(List.of("cb:query_weather:circuit"), client.deleted);
    }

    // ==================== Script arguments ====================

    @Test
    void failureScriptReceivesTheTtlInSeconds() {
        RedisToolCircuitBreakerStore store =
                new RedisToolCircuitBreakerStore(client, "cb:", Duration.ofMinutes(30));

        store.recordFailure(TOOL);

        assertEquals(List.of("1800"), client.scriptArgs.get(0));
    }

    @Test
    void openScriptReceivesTheTimestampThenTheTtl() {
        RedisToolCircuitBreakerStore store =
                new RedisToolCircuitBreakerStore(client, "cb:", Duration.ofHours(24));

        store.open(TOOL, 1_767_225_600_000L);

        assertEquals(List.of("1767225600000", "86400"), client.scriptArgs.get(0));
    }

    @Test
    void subSecondTtlIsFlooredToOneSecondSoKeysNeverPersistForever() {
        RedisToolCircuitBreakerStore store =
                new RedisToolCircuitBreakerStore(client, "cb:", Duration.ofMillis(200));

        store.recordFailure(TOOL);

        assertEquals(List.of("1"), client.scriptArgs.get(0));
    }

    // ==================== Decoding persisted state ====================

    @Test
    void snapshotDecodesGenerationAndTimestamp() {
        RedisToolCircuitBreakerStore store = store();
        client.values.put("cb:query_weather:circuit", "3:1767225600000");

        ToolCircuitSnapshot snapshot = store.snapshot(TOOL);

        assertTrue(snapshot.isOpen());
        assertEquals(3L, snapshot.generation());
        assertEquals(1_767_225_600_000L, snapshot.openedAtEpochMilli());
    }

    @Test
    void missingKeyDecodesAsClosed() {
        RedisToolCircuitBreakerStore store = store();

        assertEquals(ToolCircuitSnapshot.CLOSED, store.snapshot(TOOL));
        assertFalse(store.snapshot(TOOL).isOpen());
    }

    @Test
    void unreadableValuesFailOpenRatherThanWithholdingForever() {
        RedisToolCircuitBreakerStore store = store();
        String key = "cb:query_weather:circuit";

        for (String malformed :
                List.of("", "garbage", ":", "3:", ":1767225600000", "0:1767225600000", "3:0")) {
            client.values.put(key, malformed);
            assertEquals(
                    ToolCircuitSnapshot.CLOSED,
                    store.snapshot(TOOL),
                    "expected a closed circuit for stored value: '" + malformed + "'");
        }
    }

    @Test
    void nonNumericFailureCountReadsAsZero() {
        RedisToolCircuitBreakerStore store = store();
        client.values.put("cb:query_weather:fail", "not-a-number");

        assertEquals(0L, store.failureCount(TOOL));
    }

    @Test
    void failureCountIsReadFromTheCounterKey() {
        RedisToolCircuitBreakerStore store = store();
        client.values.put("cb:query_weather:fail", "7");

        assertEquals(7L, store.failureCount(TOOL));
    }

    // ==================== Construction ====================

    @Test
    void constructorRejectsBlankPrefixAndNonPositiveTtl() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RedisToolCircuitBreakerStore(client, "  ", Duration.ofHours(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RedisToolCircuitBreakerStore(client, "cb:", Duration.ZERO));
        assertThrows(
                NullPointerException.class,
                () -> new RedisToolCircuitBreakerStore(null, "cb:", Duration.ofHours(1)));
    }

    private RedisToolCircuitBreakerStore store() {
        return new RedisToolCircuitBreakerStore(client, "cb:", Duration.ofHours(24));
    }

    /** Fake client recording the keys and arguments each call addresses. */
    private static final class RecordingRedisClient implements RedisClientAdapter {

        private final Map<String, String> values = new HashMap<>();
        private final List<String> scriptKeys = new ArrayList<>();
        private final List<List<String>> scriptArgs = new ArrayList<>();
        private final List<String> deleted = new ArrayList<>();

        @Override
        public long evalScript(String script, List<String> keys, List<String> args) {
            scriptKeys.addAll(keys);
            scriptArgs.add(List.copyOf(args));
            return 1L;
        }

        @Override
        public String get(String key) {
            return values.get(key);
        }

        @Override
        public void deleteKeys(String... keys) {
            for (String key : keys) {
                deleted.add(key);
                values.remove(key);
            }
        }

        @Override
        public void set(String key, String value) {
            values.put(key, value);
        }

        @Override
        public boolean keyExists(String key) {
            return values.containsKey(key);
        }

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
        public Set<String> findKeysByPattern(String pattern) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
            // nothing to release
        }
    }
}
