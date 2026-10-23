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
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.tool.circuitbreaker.ToolCircuitBreaker;
import io.agentscope.core.tool.circuitbreaker.ToolCircuitBreakerConfig;
import io.agentscope.core.tool.circuitbreaker.ToolCircuitSnapshot;
import io.agentscope.extensions.redis.state.RedisClientAdapter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

/**
 * Runs the production Lua source in LuaJ. Only redis.call is supplied by the fixture; comparison,
 * branching and return values execute in Lua rather than being reimplemented in the fake adapter.
 * The fixture locks an entire script, models Redis GET/SET/DEL/PTTL and uses an adjustable expiry
 * clock. Real client transport and Redis Cluster routing are outside this test's scope.
 */
class RedisToolCircuitBreakerStoreScriptTest {

    private static final String TOOL = "weather";
    private static final String KEY = "cb:weather";
    private final LuaRedisClient client = new LuaRedisClient();
    private final RedisToolCircuitBreakerStore store =
            new RedisToolCircuitBreakerStore(client, "cb:", Duration.ofHours(24));

    @Test
    void scriptComparesMissingStateSetsExpiryAndDeletesRecoveredState() {
        ToolCircuitSnapshot failure = new ToolCircuitSnapshot(1L, 0L, 0L, null, 0L);
        assertEquals(ToolCircuitSnapshot.CLOSED, store.snapshot(TOOL));
        assertTrue(store.compareAndSet(TOOL, ToolCircuitSnapshot.CLOSED, failure));
        assertEquals(failure, store.snapshot(TOOL));
        assertEquals(Duration.ofHours(24).toMillis(), client.pttl(KEY));

        client.nowMillis += 100L;
        assertFalse(store.compareAndSet(TOOL, ToolCircuitSnapshot.CLOSED, failure));
        assertEquals(Duration.ofHours(24).toMillis() - 100L, client.pttl(KEY));
        assertTrue(store.compareAndSet(TOOL, failure, ToolCircuitSnapshot.CLOSED));
        assertNull(client.get(KEY));
        assertTrue(store.compareAndSet(TOOL, ToolCircuitSnapshot.CLOSED, failure));

        client.nowMillis += Duration.ofHours(24).toMillis();
        assertEquals(ToolCircuitSnapshot.CLOSED, store.snapshot(TOOL));
    }

    @Test
    void actualScriptAdmitsOneConcurrentCompareAndSet() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> attempts = new ArrayList<>();
            for (int i = 1; i <= 8; i++) {
                long failures = i;
                attempts.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return store.compareAndSet(
                                            TOOL,
                                            ToolCircuitSnapshot.CLOSED,
                                            new ToolCircuitSnapshot(failures, 0L, 0L, null, 0L));
                                }));
            }
            start.countDown();
            int winners = 0;
            for (Future<Boolean> attempt : attempts) {
                if (attempt.get()) {
                    winners++;
                }
            }
            assertEquals(1, winners);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void corruptAndNoncanonicalStatesCanRecordTheNextFailure() {
        ToolCircuitBreaker breaker =
                new ToolCircuitBreaker(
                        ToolCircuitBreakerConfig.builder().monitorTools(TOOL).build(),
                        store,
                        Clock.fixed(Instant.ofEpochMilli(1_000L), ZoneOffset.UTC));
        for (String raw :
                List.of(
                        "garbage",
                        "0:-1:1000::0",
                        "0:0:0::0",
                        "",
                        "v1:0:1:1000:9000:5:tok",
                        "01:0:0::0")) {
            client.set(KEY, raw);
            client.scriptCalls = 0;
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> breaker.recordFailure(TOOL));
            assertEquals(raw.equals("01:0:0::0") ? 2L : 1L, store.snapshot(TOOL).failureCount());
            assertEquals(2, client.scriptCalls, "one repair and one transition suffice for " + raw);
        }
    }

    @Test
    void repairDoesNotDeleteOrOverwriteACompetingTransition() {
        ToolCircuitSnapshot competing = new ToolCircuitSnapshot(2L, 0L, 0L, null, 0L);
        client.set(KEY, "garbage");
        client.beforeNextScript =
                () -> {
                    store.reset(TOOL);
                    assertTrue(store.compareAndSet(TOOL, ToolCircuitSnapshot.CLOSED, competing));
                };

        assertEquals(competing, store.snapshot(TOOL));
        assertEquals(competing, store.snapshot(TOOL));
        assertEquals(Duration.ofHours(24).toMillis(), client.pttl(KEY));
    }

    @Test
    void legacyMigrationPreservesRemainingExpiryAndAllowsTheNextSwap() {
        ToolCircuitSnapshot legacy = new ToolCircuitSnapshot(0L, 1L, 1_000L, "tok", 9_000L);
        client.putWithTtl(KEY, "0:1:1000:tok:9000", 1_234L);

        assertEquals(legacy, store.snapshot(TOOL));
        assertTrue(client.get(KEY).startsWith("v1:"));
        assertEquals(1_234L, client.pttl(KEY));
        assertTrue(store.compareAndSet(TOOL, legacy, ToolCircuitSnapshot.CLOSED));
        assertNull(client.get(KEY));
    }

    @Test
    void migrationAddsConfiguredExpiryToLegacyStateWithoutATtl() {
        client.set(KEY, "1:0:0::0");

        assertEquals(1L, store.snapshot(TOOL).failureCount());
        assertEquals(Duration.ofHours(24).toMillis(), client.pttl(KEY));
    }

    @Test
    void expiredLegacyStateIsNotResurrectedByMigration() {
        client.putWithTtl(KEY, "1:0:0::0", 1L);
        client.beforeNextScript = () -> client.nowMillis++;

        assertEquals(ToolCircuitSnapshot.CLOSED, store.snapshot(TOOL));
        assertNull(client.get(KEY));
        assertTrue(
                store.compareAndSet(
                        TOOL,
                        ToolCircuitSnapshot.CLOSED,
                        new ToolCircuitSnapshot(1L, 0L, 0L, null, 0L)));
    }

    @Test
    void opaqueTokensAndTenantPrefixesKeepIndependentState() {
        RedisToolCircuitBreakerStore otherTenant =
                new RedisToolCircuitBreakerStore(client, "tenant-b:cb:", Duration.ofHours(24));
        ToolCircuitSnapshot claimed =
                new ToolCircuitSnapshot(0L, 1L, 1_000L, "opaque:\n天气:🧪", 9_000L);
        assertTrue(store.compareAndSet(TOOL, ToolCircuitSnapshot.CLOSED, claimed));
        assertEquals(claimed, store.snapshot(TOOL));
        assertEquals(ToolCircuitSnapshot.CLOSED, otherTenant.snapshot(TOOL));

        otherTenant.reset(TOOL);
        assertEquals(claimed, store.snapshot(TOOL));
        assertTrue(store.compareAndSet(TOOL, claimed, ToolCircuitSnapshot.CLOSED));
    }

    private static final class LuaRedisClient implements RedisClientAdapter {

        private final Map<String, String> values = new HashMap<>();
        private final Map<String, Long> expiresAt = new HashMap<>();
        private long nowMillis;
        private int scriptCalls;
        private Runnable beforeNextScript;

        @Override
        public synchronized long evalScript(String script, List<String> keys, List<String> args) {
            if (++scriptCalls > 100) {
                throw new AssertionError("Unbounded CAS retry loop");
            }
            Runnable competingWrite = beforeNextScript;
            beforeNextScript = null;
            if (competingWrite != null) {
                competingWrite.run();
            }
            assertEquals(1, keys.size(), "Every script must remain safe for a single cluster key");
            Globals globals = JsePlatform.standardGlobals();
            globals.set("KEYS", table(keys));
            globals.set("ARGV", table(args));
            LuaTable redis = new LuaTable();
            redis.set(
                    "call",
                    new VarArgFunction() {
                        @Override
                        public Varargs invoke(Varargs command) {
                            String key = command.arg(2).checkjstring();
                            assertEquals(keys.get(0), key);
                            return switch (command.arg(1).checkjstring()) {
                                case "GET" -> {
                                    String value = LuaRedisClient.this.get(key);
                                    yield value == null ? LuaValue.FALSE : LuaValue.valueOf(value);
                                }
                                case "DEL" -> {
                                    boolean existed = LuaRedisClient.this.get(key) != null;
                                    deleteKeys(key);
                                    yield LuaValue.valueOf(existed ? 1 : 0);
                                }
                                case "PTTL" -> LuaValue.valueOf(pttl(key));
                                case "SET" -> {
                                    long ttl = command.arg(5).checklong();
                                    assertTrue(ttl > 0L);
                                    String unit = command.arg(4).checkjstring();
                                    assertTrue(unit.equals("EX") || unit.equals("PX"));
                                    putWithTtl(
                                            key,
                                            command.arg(3).checkjstring(),
                                            unit.equals("EX") ? ttl * 1_000L : ttl);
                                    yield LuaValue.valueOf("OK");
                                }
                                default -> throw new AssertionError("Unexpected Redis command");
                            };
                        }
                    });
            globals.set("redis", redis);
            return globals.load(script, "circuit-breaker.lua").call().checklong();
        }

        private static LuaTable table(List<String> values) {
            LuaTable table = new LuaTable();
            for (int i = 0; i < values.size(); i++) {
                table.set(i + 1, LuaValue.valueOf(values.get(i)));
            }
            return table;
        }

        synchronized long pttl(String key) {
            if (get(key) == null) {
                return -2L;
            }
            Long expiry = expiresAt.get(key);
            return expiry == null ? -1L : expiry - nowMillis;
        }

        synchronized void putWithTtl(String key, String value, long ttlMillis) {
            values.put(key, value);
            expiresAt.put(key, nowMillis + ttlMillis);
        }

        @Override
        public synchronized String get(String key) {
            Long expiry = expiresAt.get(key);
            if (expiry != null && nowMillis >= expiry) {
                deleteKeys(key);
            }
            return values.get(key);
        }

        @Override
        public synchronized void set(String key, String value) {
            values.put(key, value);
            expiresAt.remove(key);
        }

        @Override
        public synchronized void deleteKeys(String... keys) {
            for (String key : keys) {
                values.remove(key);
                expiresAt.remove(key);
            }
        }

        @Override
        public boolean keyExists(String key) {
            return get(key) != null;
        }

        @Override
        public void close() {}

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
    }
}
