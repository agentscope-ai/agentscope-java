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
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * Client-side behaviour of {@link RedisToolCircuitBreakerStore}: key layout, the arguments handed to
 * the compare-and-set script, and the encoding of persisted state.
 *
 * <p>Scope note: the Lua script is executed by Redis, so a fake client cannot run it. These tests
 * cover the Java side — which key is addressed, what the script receives, and how stored values are
 * encoded and decoded, including values a healthy writer would never produce. The script's
 * server-side effect needs a live Redis to verify.
 */
class RedisToolCircuitBreakerStoreTest {

    private static final String TOOL = "query_weather";
    private static final String KEY = "cb:query_weather";

    private final RecordingRedisClient client = new RecordingRedisClient();

    // ==================== Key layout ====================

    @Test
    void allStateLivesUnderOneKeySoClusterNeedsNoHashTag() {
        RedisToolCircuitBreakerStore store = store();

        store.snapshot(TOOL);
        store.compareAndSet(TOOL, ToolCircuitSnapshot.CLOSED, new ToolCircuitSnapshot(1L, 1_000L));
        store.reset(TOOL);

        assertEquals(List.of(KEY), client.reads);
        assertEquals(List.of(List.of(KEY)), client.scriptKeys);
        assertEquals(List.of(KEY), client.deleted);
    }

    @Test
    void defaultConstructorUsesTheDocumentedPrefix() {
        RedisToolCircuitBreakerStore store = new RedisToolCircuitBreakerStore(client);

        store.reset(TOOL);

        assertEquals(List.of("agentscope:tool-cb:query_weather"), client.deleted);
    }

    // ==================== Compare-and-set arguments ====================

    @Test
    void closedIsEncodedAsTheAbsentKeyOnBothSidesOfTheSwap() {
        RedisToolCircuitBreakerStore store = store();

        store.compareAndSet(TOOL, ToolCircuitSnapshot.CLOSED, new ToolCircuitSnapshot(2L, 5_000L));

        // Expected "" makes "missing" and "closed" compare equal; update carries the new state.
        assertEquals(List.of("", "0:2:5000::0", "86400"), client.scriptArgs.get(0));
    }

    @Test
    void updatingToClosedRequestsDeletionViaAnEmptyUpdate() {
        RedisToolCircuitBreakerStore store = store();
        ToolCircuitSnapshot open = new ToolCircuitSnapshot(3L, 7_000L);

        store.compareAndSet(TOOL, open, ToolCircuitSnapshot.CLOSED);

        assertEquals(List.of("0:3:7000::0", "", "86400"), client.scriptArgs.get(0));
    }

    @Test
    void probeClaimIsCarriedInTheEncodedValue() {
        RedisToolCircuitBreakerStore store = store();
        ToolCircuitSnapshot claimed = new ToolCircuitSnapshot(0L, 4L, 1_000L, "tok", 9_000L);

        store.compareAndSet(TOOL, ToolCircuitSnapshot.CLOSED, claimed);

        assertEquals(List.of("", "0:4:1000:tok:9000", "86400"), client.scriptArgs.get(0));
    }

    @Test
    void ttlIsPassedInSecondsAndFlooredToOne() {
        new RedisToolCircuitBreakerStore(client, "cb:", Duration.ofMinutes(30))
                .compareAndSet(TOOL, ToolCircuitSnapshot.CLOSED, new ToolCircuitSnapshot(1L, 1L));
        assertEquals("1800", client.scriptArgs.get(0).get(2));

        client.scriptArgs.clear();
        new RedisToolCircuitBreakerStore(client, "cb:", Duration.ofMillis(200))
                .compareAndSet(TOOL, ToolCircuitSnapshot.CLOSED, new ToolCircuitSnapshot(1L, 1L));
        assertEquals("1", client.scriptArgs.get(0).get(2));
    }

    @Test
    void scriptResultDecidesWhetherTheSwapCommitted() {
        RedisToolCircuitBreakerStore store = store();

        client.scriptResult = 1L;
        assertTrue(
                store.compareAndSet(
                        TOOL, ToolCircuitSnapshot.CLOSED, new ToolCircuitSnapshot(1L, 1_000L)));

        client.scriptResult = 0L;
        assertFalse(
                store.compareAndSet(
                        TOOL, ToolCircuitSnapshot.CLOSED, new ToolCircuitSnapshot(1L, 1_000L)));
    }

    // ==================== Decoding ====================

    @Test
    void snapshotDecodesEveryField() {
        RedisToolCircuitBreakerStore store = store();
        client.values.put(KEY, "2:3:1767225600000:tok:1767225660000");

        ToolCircuitSnapshot snapshot = store.snapshot(TOOL);

        assertEquals(2L, snapshot.failureCount());
        assertEquals(3L, snapshot.generation());
        assertEquals(1_767_225_600_000L, snapshot.openedAtEpochMilli());
        assertEquals("tok", snapshot.probeToken());
        assertEquals(1_767_225_660_000L, snapshot.probeLeaseUntilEpochMilli());
        assertTrue(snapshot.isOpen());
    }

    @Test
    void anEmptyProbeFieldDecodesToNoClaim() {
        RedisToolCircuitBreakerStore store = store();
        client.values.put(KEY, "0:1:1000::0");

        ToolCircuitSnapshot snapshot = store.snapshot(TOOL);

        assertNull(snapshot.probeToken());
        assertFalse(snapshot.hasActiveProbe(0L));
    }

    @Test
    void missingKeyDecodesAsClosed() {
        assertEquals(ToolCircuitSnapshot.CLOSED, store().snapshot(TOOL));
    }

    @Test
    void encodingRoundTripsThroughDecoding() {
        RedisToolCircuitBreakerStore store = store();
        ToolCircuitSnapshot original = new ToolCircuitSnapshot(2L, 3L, 1_000L, "tok", 9_000L);
        store.compareAndSet(TOOL, ToolCircuitSnapshot.CLOSED, original);

        // Feed the encoding the store just produced back through the read path.
        client.values.put(KEY, client.scriptArgs.get(0).get(1));

        assertEquals(original, store.snapshot(TOOL));
    }

    @Test
    void unreadableValuesFailOpenRatherThanWithholdingForever() {
        RedisToolCircuitBreakerStore store = store();

        for (String malformed :
                List.of(
                        "",
                        "garbage",
                        "0:1:1000",
                        "0:1:1000::0:extra",
                        "x:1:1000::0",
                        "0:-1:1000::0",
                        "0:1:-5::0")) {
            client.values.put(KEY, malformed);
            assertEquals(
                    ToolCircuitSnapshot.CLOSED,
                    store.snapshot(TOOL),
                    "expected a closed circuit for stored value: '" + malformed + "'");
        }
    }

    // ==================== Construction ====================

    @Test
    void constructorRejectsInvalidArguments() {
        assertThrows(
                NullPointerException.class,
                () -> new RedisToolCircuitBreakerStore(null, "cb:", Duration.ofHours(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RedisToolCircuitBreakerStore(client, "  ", Duration.ofHours(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RedisToolCircuitBreakerStore(client, null, Duration.ofHours(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RedisToolCircuitBreakerStore(client, "cb:", Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RedisToolCircuitBreakerStore(client, "cb:", null));
    }

    private RedisToolCircuitBreakerStore store() {
        return new RedisToolCircuitBreakerStore(client, "cb:", Duration.ofHours(24));
    }

    /** Fake client recording the key and arguments each call addresses. */
    private static final class RecordingRedisClient implements RedisClientAdapter {

        private final Map<String, String> values = new HashMap<>();
        private final List<String> reads = new ArrayList<>();
        private final List<List<String>> scriptKeys = new ArrayList<>();
        private final List<List<String>> scriptArgs = new ArrayList<>();
        private final List<String> deleted = new ArrayList<>();
        private long scriptResult = 1L;

        @Override
        public long evalScript(String script, List<String> keys, List<String> args) {
            scriptKeys.add(List.copyOf(keys));
            scriptArgs.add(List.copyOf(args));
            return scriptResult;
        }

        @Override
        public String get(String key) {
            reads.add(key);
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
