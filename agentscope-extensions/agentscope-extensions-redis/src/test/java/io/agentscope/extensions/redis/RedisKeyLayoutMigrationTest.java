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
package io.agentscope.extensions.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.extensions.redis.RedisKeyLayoutMigration.StateStoreMigrationReport;
import io.agentscope.extensions.redis.RedisKeyLayoutMigration.StoreMigrationReport;
import io.agentscope.extensions.redis.state.RedisClientAdapter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.ScanIteration;
import redis.clients.jedis.UnifiedJedis;

/**
 * Unit tests for {@link RedisKeyLayoutMigration} against an in-memory adapter (AgentStateStore
 * path) and a mocked Jedis client (RedisStore path). No Docker required.
 */
@DisplayName("RedisKeyLayoutMigration")
class RedisKeyLayoutMigrationTest {

    private static final String PREFIX = "agentscope:session:";

    // ------------------------------------------------------------------
    // In-memory RedisClientAdapter
    // ------------------------------------------------------------------

    /** Functional in-memory adapter; findKeysByPattern only supports the trailing-star form. */
    private static class FakeAdapter implements RedisClientAdapter {
        final Map<String, String> strings = new HashMap<>();
        final Map<String, List<String>> lists = new HashMap<>();
        final Map<String, Set<String>> sets = new HashMap<>();
        final Set<String> deleted = new LinkedHashSet<>();

        /** Seed a legacy (un-tagged) session slot with whatever parts are non-null. */
        @SuppressWarnings("Var")
        void seed(
                String markerKey,
                Set<String> markerMembers,
                String payloadKey,
                String payload,
                String versionKey,
                String version,
                String listKey,
                List<String> items,
                String hashKey,
                String hash) {
            sets.put(markerKey, new LinkedHashSet<>(markerMembers));
            if (payload != null) {
                strings.put(payloadKey, payload);
            }
            if (version != null) {
                strings.put(versionKey, version);
            }
            if (items != null) {
                lists.put(listKey, new ArrayList<>(items));
            }
            if (hash != null) {
                strings.put(hashKey, hash);
            }
        }

        Set<String> allKeys() {
            Set<String> out = new LinkedHashSet<>();
            out.addAll(strings.keySet());
            out.addAll(lists.keySet());
            out.addAll(sets.keySet());
            return out;
        }

        @Override
        public void set(String key, String value) {
            strings.put(key, value);
        }

        @Override
        public String get(String key) {
            return strings.get(key);
        }

        @Override
        public void rightPushList(String key, String value) {
            lists.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }

        @Override
        public List<String> rangeList(String key, long start, long end) {
            List<String> l = lists.get(key);
            return l == null ? List.of() : new ArrayList<>(l);
        }

        @Override
        public long getListLength(String key) {
            List<String> l = lists.get(key);
            return l == null ? 0 : l.size();
        }

        @Override
        public void deleteKeys(String... keys) {
            for (String k : keys) {
                deleted.add(k);
                strings.remove(k);
                lists.remove(k);
                sets.remove(k);
            }
        }

        @Override
        public void addToSet(String key, String member) {
            sets.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(member);
        }

        @Override
        public Set<String> getSetMembers(String key) {
            Set<String> s = sets.get(key);
            return s == null ? Set.of() : new LinkedHashSet<>(s);
        }

        @Override
        public long getSetSize(String key) {
            Set<String> s = sets.get(key);
            return s == null ? 0 : s.size();
        }

        @Override
        public boolean keyExists(String key) {
            return allKeys().contains(key);
        }

        @Override
        public Set<String> findKeysByPattern(String pattern) {
            String prefix =
                    pattern.endsWith("*") ? pattern.substring(0, pattern.length() - 1) : pattern;
            Set<String> out = new LinkedHashSet<>();
            for (String k : allKeys()) {
                if (k.startsWith(prefix)) {
                    out.add(k);
                }
            }
            return out;
        }

        @Override
        public long evalScript(String script, List<String> keys, List<String> args) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {}
    }

    /** Standard legacy session: payload + version + list + hash, tracked by an un-tagged marker. */
    private static void seedLegacySession(FakeAdapter a, String slot) {
        a.seed(
                PREFIX + slot + ":_keys",
                // marker members: plain key "agent:profile" and list-form "agent:chat:list"
                Set.of("agent:profile", "agent:chat:list"),
                PREFIX + slot + ":agent:profile",
                "{\"value\":\"v1\"}",
                PREFIX + slot + ":agent:profile:ver",
                "3",
                PREFIX + slot + ":agent:chat:list",
                List.of("x", "y"),
                PREFIX + slot + ":agent:chat:list:_hash",
                "abc");
    }

    // ------------------------------------------------------------------
    // AgentStateStore migration
    // ------------------------------------------------------------------

    @Test
    @DisplayName("findLegacyStateKeys returns only un-tagged keys under the prefix")
    void findLegacyStateKeysFiltersTagged() {
        FakeAdapter a = new FakeAdapter();
        a.set(PREFIX + "u1/s1:agent:profile", "{\"value\":\"legacy\"}");
        a.set(PREFIX + "{u2/s2}:agent:profile", "{\"value\":\"modern\"}");

        Set<String> legacy = RedisKeyLayoutMigration.findLegacyStateKeys(a, PREFIX);

        assertEquals(Set.of(PREFIX + "u1/s1:agent:profile"), legacy);
    }

    @Test
    @DisplayName("dry run reports counts but writes and deletes nothing")
    void dryRunCopiesNothing() {
        FakeAdapter a = new FakeAdapter();
        seedLegacySession(a, "u1/s1");

        StateStoreMigrationReport report =
                RedisKeyLayoutMigration.migrateAgentStateStore(a, PREFIX, true);

        assertTrue(report.dryRun());
        assertEquals(1, report.slotsDiscovered());
        assertEquals(1, report.slotsMigrated());
        // payload + version + 2 list items + list hash
        assertEquals(5, report.keysCopied());
        assertEquals(0, report.legacyKeysDeleted());
        assertTrue(report.skippedSlots().isEmpty());
        assertEquals(0, report.orphanKeysTotal());
        assertTrue(a.deleted.isEmpty());
        // nothing written in the tagged layout
        for (String key : a.allKeys()) {
            assertFalse(key.contains("{u1/s1}"), "dry run must not write tagged keys: " + key);
        }
    }

    @Test
    @DisplayName("migration copies payloads, versions, lists and marker; deletes legacy keys")
    void migrateCopiesKeysAndDeletesLegacy() {
        FakeAdapter a = new FakeAdapter();
        seedLegacySession(a, "u1/s1");

        StateStoreMigrationReport report =
                RedisKeyLayoutMigration.migrateAgentStateStore(a, PREFIX, false);

        assertFalse(report.dryRun());
        assertEquals(1, report.slotsDiscovered());
        assertEquals(1, report.slotsMigrated());
        assertEquals(5, report.keysCopied());
        assertEquals(5, report.legacyKeysDeleted());
        assertTrue(report.skippedSlots().isEmpty());
        assertEquals(0, report.orphanKeysTotal());

        String tag = PREFIX + "{u1/s1}";
        assertEquals("{\"value\":\"v1\"}", a.get(tag + ":agent:profile"));
        assertEquals("3", a.get(tag + ":agent:profile:ver"));
        assertEquals(List.of("x", "y"), a.rangeList(tag + ":agent:chat:list", 0, -1));
        assertEquals("abc", a.get(tag + ":agent:chat:list:_hash"));
        assertEquals(Set.of("agent:profile", "agent:chat:list"), a.getSetMembers(tag + ":_keys"));

        // every legacy key of the slot is gone
        for (String legacy :
                List.of(
                        PREFIX + "u1/s1:_keys",
                        PREFIX + "u1/s1:agent:profile",
                        PREFIX + "u1/s1:agent:profile:ver",
                        PREFIX + "u1/s1:agent:chat:list",
                        PREFIX + "u1/s1:agent:chat:list:_hash")) {
            assertTrue(a.deleted.contains(legacy), "legacy key not deleted: " + legacy);
        }
    }

    @Test
    @DisplayName("slots the new API cannot address are skipped and left untouched")
    void skipsSlotsTheNewApiRejects() {
        FakeAdapter a = new FakeAdapter();
        // session id containing '{' — rejected by the new API
        seedLegacySession(a, "u1/s{2}");
        // user id containing a glob metacharacter — rejected by the new API
        a.seed(
                PREFIX + "a*b/s9:_keys",
                Set.of("k"),
                PREFIX + "a*b/s9:k",
                "{}",
                null,
                null,
                null,
                null,
                null,
                null);

        StateStoreMigrationReport report =
                RedisKeyLayoutMigration.migrateAgentStateStore(a, PREFIX, false);

        assertEquals(2, report.slotsDiscovered());
        assertEquals(0, report.slotsMigrated());
        assertEquals(2, report.skippedSlots().size());
        // order of the skipped set is not contractual — assert membership by prefix
        assertTrue(
                report.skippedSlots().stream().anyMatch(s -> s.startsWith("u1/s{2}")),
                "session-id slot with '{' not skipped: " + report.skippedSlots());
        assertTrue(
                report.skippedSlots().stream().anyMatch(s -> s.startsWith("a*b/s9")),
                "glob userId slot not skipped: " + report.skippedSlots());
        // skipped slot keys are neither migrated nor deleted
        assertTrue(a.deleted.isEmpty());
        assertTrue(a.keyExists(PREFIX + "u1/s{2}:_keys"));
        assertTrue(a.keyExists(PREFIX + "a*b/s9:k"));
        // ... and they are not reported as orphans either
        assertEquals(0, report.orphanKeysTotal());
    }

    @Test
    @DisplayName("legacy keys no marker tracks are reported as orphans and never touched")
    void orphansAreReportedNotTouched() {
        FakeAdapter a = new FakeAdapter();
        seedLegacySession(a, "u1/s1");
        // orphan payload (marker deleted out of band) + orphan list (partial write)
        a.set(PREFIX + "ghost/s:agent:x", "{}");
        a.lists.put(PREFIX + "ghost/s:agent:y:list", new ArrayList<>(List.of("z")));

        StateStoreMigrationReport report =
                RedisKeyLayoutMigration.migrateAgentStateStore(a, PREFIX, false);

        assertEquals(1, report.slotsMigrated());
        assertEquals(2, report.orphanKeysTotal());
        assertEquals(
                Set.of(PREFIX + "ghost/s:agent:x", PREFIX + "ghost/s:agent:y:list"),
                Set.copyOf(report.orphanKeysSample()));
        assertTrue(a.keyExists(PREFIX + "ghost/s:agent:x"));
        assertTrue(a.keyExists(PREFIX + "ghost/s:agent:y:list"));
    }

    // ---- Reserved state-key names: slots carrying one are skipped entirely and left untouched
    // ----

    @Test
    @DisplayName(
            "slots whose members the new validateStateKey rejects are skipped and left untouched")
    void skipsSlotsWithReservedStateKeyNames() {
        FakeAdapter a = new FakeAdapter();
        // One slot per reserved form; the effective state key of a list-form member is its base.
        a.seed(
                PREFIX + "u1/s1:_keys",
                Set.of("agent:ver"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        a.seed(
                PREFIX + "u2/s2:_keys",
                Set.of("agent:list:_hash"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        a.seed(
                PREFIX + "u3/s3:_keys",
                Set.of("_keys"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        a.seed(
                PREFIX + "u4/s4:_keys",
                Set.of("x:list:list"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        a.seed(
                PREFIX + "u5/s5:_keys",
                Set.of("a{"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        a.seed(PREFIX + "u6/s6:_keys", Set.of(""), null, null, null, null, null, null, null, null);
        // '}' without '{' exercises the closing-brace half of the reserved-character check
        a.seed(
                PREFIX + "u7/s7:_keys",
                Set.of("a}"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        seedLegacySession(a, "ok/s9");

        StateStoreMigrationReport report =
                RedisKeyLayoutMigration.migrateAgentStateStore(a, PREFIX, false);

        assertEquals(8, report.slotsDiscovered());
        // only the well-formed slot migrates
        assertEquals(1, report.slotsMigrated());
        assertEquals(7, report.skippedSlots().size());
        for (String slot :
                new String[] {"u1/s1", "u2/s2", "u3/s3", "u4/s4", "u5/s5", "u6/s6", "u7/s7"}) {
            assertTrue(
                    report.skippedSlots().stream().anyMatch(s -> s.startsWith(slot)),
                    "slot " + slot + " must be reported as skipped: " + report.skippedSlots());
        }
        // skipped slots keep every key untouched; only the migrated slot's legacy keys may be
        // deleted
        for (String key : a.deleted) {
            assertTrue(
                    key.startsWith(PREFIX + "ok/s9"),
                    "only the migrated slot's keys may be deleted: " + key);
        }
        for (String slot :
                new String[] {"u1/s1", "u2/s2", "u3/s3", "u4/s4", "u5/s5", "u6/s6", "u7/s7"}) {
            assertTrue(a.keyExists(PREFIX + slot + ":_keys"), "marker must remain: " + slot);
        }
        assertEquals(0, report.orphanKeysTotal());
        // the valid slot migrated
        assertTrue(a.keyExists(PREFIX + "{ok/s9}:agent:profile"));
    }

    // ------------------------------------------------------------------
    // RedisStore migration (mocked UnifiedJedis)
    // ------------------------------------------------------------------

    private static final String STORE_PREFIX = "agentscope:store:";

    private static ScanIteration scanReturning(Set<String> keys) {
        ScanIteration scan = mock(ScanIteration.class);
        doAnswer(
                        inv -> {
                            Collection<String> c = inv.getArgument(0);
                            c.addAll(keys);
                            return c;
                        })
                .when(scan)
                .collect(anyCollection());
        return scan;
    }

    @Test
    @DisplayName("RedisStore: namespaces copy to tagged item/idx keys; legacy keys deleted")
    void migrateRedisStoreCopiesAndDeletes() {
        UnifiedJedis jedis = mock(UnifiedJedis.class);
        // NOTE: build the ScanIteration mock BEFORE when(...): a stubbing nested inside
        // thenReturn(...) triggers Mockito's UnfinishedStubbing.
        ScanIteration scan =
                scanReturning(
                        Set.of(
                                STORE_PREFIX + "idx:ns1",
                                STORE_PREFIX + "idx:",
                                STORE_PREFIX + "idx:{ns2}"));
        when(jedis.scanIteration(anyInt(), anyString())).thenReturn(scan);
        when(jedis.zrange(STORE_PREFIX + "idx:ns1", 0L, -1L)).thenReturn(List.of("k1", "k2"));
        when(jedis.zrange(STORE_PREFIX + "idx:", 0L, -1L)).thenReturn(List.of("r1"));
        when(jedis.hgetAll(STORE_PREFIX + "item:ns1\0k1")).thenReturn(Map.of("value", "a"));
        // index entry whose item hash is missing (torn write window): skipped, membership kept
        when(jedis.hgetAll(STORE_PREFIX + "item:ns1\0k2")).thenReturn(Map.of());
        when(jedis.hgetAll(STORE_PREFIX + "item:\0r1")).thenReturn(Map.of("value", "r"));

        StoreMigrationReport report =
                RedisKeyLayoutMigration.migrateRedisStore(jedis, STORE_PREFIX, false);

        assertFalse(report.dryRun());
        assertEquals(2, report.namespacesDiscovered());
        assertEquals(2, report.namespacesMigrated());
        assertEquals(2, report.itemsCopied());
        // idx:ns1 + item k1 + item k2 + idx: (root) + item r1
        assertEquals(5, report.legacyKeysDeleted());
        assertTrue(report.skippedNamespaces().isEmpty());

        verify(jedis).hset(STORE_PREFIX + "item:{ns1}\0k1", Map.of("value", "a"));
        verify(jedis).zadd(STORE_PREFIX + "idx:{ns1}", 0.0, "k1");
        // k2 had no item hash, but its index membership is preserved
        verify(jedis).zadd(STORE_PREFIX + "idx:{ns1}", 0.0, "k2");
        verify(jedis).hset(STORE_PREFIX + "item:{_root_}\0r1", Map.of("value", "r"));
        verify(jedis).zadd(STORE_PREFIX + "idx:{_root_}", 0.0, "r1");
        verify(jedis).del(STORE_PREFIX + "idx:ns1");
        verify(jedis).del(STORE_PREFIX + "item:ns1\0k1");
        verify(jedis).del(STORE_PREFIX + "item:ns1\0k2");
        verify(jedis).del(STORE_PREFIX + "idx:");
        verify(jedis).del(STORE_PREFIX + "item:\0r1");
        // already-tagged namespaces are not migrated
        verify(jedis, never()).zadd(eq(STORE_PREFIX + "idx:{ns2}"), anyDouble(), anyString());
    }

    @Test
    @DisplayName("RedisStore dry run reads only; skips namespaces with braces")
    void migrateRedisStoreDryRunAndSkips() {
        UnifiedJedis jedis = mock(UnifiedJedis.class);
        ScanIteration scan =
                scanReturning(
                        Set.of(
                                STORE_PREFIX + "idx:a{b",
                                STORE_PREFIX + "idx:_root_",
                                STORE_PREFIX + "idx:ns"));
        when(jedis.scanIteration(anyInt(), anyString())).thenReturn(scan);
        when(jedis.zrange(STORE_PREFIX + "idx:ns", 0L, -1L)).thenReturn(List.of("k"));
        when(jedis.hgetAll(STORE_PREFIX + "item:ns\0k")).thenReturn(Map.of("value", "a"));

        StoreMigrationReport report =
                RedisKeyLayoutMigration.migrateRedisStore(jedis, STORE_PREFIX, true);

        assertTrue(report.dryRun());
        assertEquals(3, report.namespacesDiscovered());
        assertEquals(1, report.namespacesMigrated());
        assertEquals(2, report.skippedNamespaces().size());
        assertTrue(
                report.skippedNamespaces().stream().anyMatch(s -> s.startsWith("a{b")),
                "a{b must be skipped: " + report.skippedNamespaces());
        assertTrue(
                report.skippedNamespaces().stream().anyMatch(s -> s.startsWith("_root_")),
                "_root_ must be skipped: " + report.skippedNamespaces());
        // only the migratable namespace contributes copies; the skipped ones are never read
        assertEquals(1, report.itemsCopied());
        assertEquals(0, report.legacyKeysDeleted());
        verify(jedis, never()).hset(anyString(), anyMap());
        verify(jedis, never()).zadd(anyString(), anyDouble(), anyString());
        verify(jedis, never()).del(anyString());
    }
}
