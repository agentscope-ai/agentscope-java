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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Behavior tests for the RedisAgentStateStore fixes against a real single-node Redis
 * (Testcontainers), parameterized over the three supported Redis clients. Subclasses implement
 * {@link #buildStore(int, String)} to provide a Lettuce / Jedis / Redisson backed store; all 16 test
 * methods below then run once per client.
 *
 * <p>Covers what only a real Redis can prove: atomic list save (C2), atomic versioned read (H1),
 * per-key delete (H2), single/list form cleanup (M1), clearAllSessions (M2), the guarded-append
 * list decision (CN1), clear-vs-save atomicity (CN2), and state-key validation (S1).
 *
 * <p>Skipped (not failed) when Docker / the Redis image is unavailable, so the build stays green
 * on machines without Docker.
 */
@DisplayName("RedisAgentStateStore fixes (real Redis)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class RedisAgentStateStoreFixesTest {

    private static final String IMAGE = "redis:7-alpine";

    private GenericContainer<?> redis;
    private RedisAgentStateStore store;

    /** Build a store for the given Redis port using one of the three client adapters. */
    protected abstract RedisAgentStateStore buildStore(int port, String keyPrefix);

    @BeforeAll
    void startRedis() {
        try {
            redis =
                    new GenericContainer<>(IMAGE)
                            .withExposedPorts(6379)
                            .waitingFor(Wait.forListeningPort());
            redis.start();

            int port = redis.getMappedPort(6379);
            store = buildStore(port, "fixtest:" + UUID.randomUUID() + ":");
        } catch (Throwable t) {
            // Docker or image unavailable: skip the whole class rather than fail the build.
            t.printStackTrace();
            Assumptions.assumeTrue(false, "Redis Testcontainer unavailable: " + t);
        }
    }

    @AfterAll
    void stopRedis() {
        if (store != null) {
            try {
                store.clearAllSessions().block();
            } catch (RuntimeException ignored) {
                // best-effort cleanup before closing
            }
            store.close();
        }
        if (redis != null) {
            redis.stop();
        }
    }

    /** Minimal State payload. */
    record TestState(String value) implements State {}

    private static List<TestState> states(String... values) {
        List<TestState> list = new ArrayList<>();
        for (String v : values) {
            list.add(new TestState(v));
        }
        return list;
    }

    // ---- H1: atomic versioned read + plain save bumps version ----

    @Test
    @DisplayName("H1/M3: plain save bumps version; getVersioned returns matching payload+version")
    void saveAndgetVersioned_roundTrip() {
        String u = "user-1001", s = "session-001";
        store.save(u, s, "agent:profile", new TestState("v1"));
        VersionedState<TestState> v1 = store.getVersioned(u, s, "agent:profile", TestState.class);
        assertEquals("v1", v1.value().value());
        assertEquals(1L, v1.version());

        store.save(u, s, "agent:profile", new TestState("v2"));
        VersionedState<TestState> v2 = store.getVersioned(u, s, "agent:profile", TestState.class);
        assertEquals("v2", v2.value().value());
        assertEquals(2L, v2.version());
    }

    @Test
    @DisplayName("H1: saveIfVersion create-if-absent (expected=0) then conflict")
    void saveIfVersion_createAndConflict() {
        String u = "user-1001", s = "session-002";
        long created = store.saveIfVersion(u, s, "agent:profile", new TestState("first"), 0L);
        assertEquals(1L, created);
        long conflict = store.saveIfVersion(u, s, "agent:profile", new TestState("lost"), 0L);
        assertEquals(AgentStateStore.UNVERSIONED, conflict);
        assertEquals(
                "first", store.get(u, s, "agent:profile", TestState.class).orElseThrow().value());
    }

    // ---- C2/CN1: atomic list save + append/rewrite decision ----

    @Test
    @DisplayName("C2: list save round-trips through getList")
    void listSave_roundTrip() {
        String u = "user-1001", s = "session-003";
        store.save(u, s, "agent:profile", states("a", "b", "c"));
        List<TestState> got = store.getList(u, s, "agent:profile", TestState.class);
        assertEquals(List.of("a", "b", "c"), got.stream().map(TestState::value).toList());
    }

    @Test
    @DisplayName(
            "CN1: append-only growth appends the tail; middle edit forces rewrite; shrink rewrites")
    void listSave_appendThenRewriteThenShrink() {
        String u = "user-1001", s = "session-004";
        // First save (rewrite from empty)
        store.save(u, s, "agent:profile", states("a", "b", "c"));
        // Append-only growth -> append tail
        store.save(u, s, "agent:profile", states("a", "b", "c", "d"));
        assertEquals(
                List.of("a", "b", "c", "d"),
                store.getList(u, s, "agent:profile", TestState.class).stream()
                        .map(TestState::value)
                        .toList());
        // Middle edit -> full rewrite
        store.save(u, s, "agent:profile", states("a", "B", "c", "d"));
        assertEquals(
                List.of("a", "B", "c", "d"),
                store.getList(u, s, "agent:profile", TestState.class).stream()
                        .map(TestState::value)
                        .toList());
        // Shrink -> full rewrite
        store.save(u, s, "agent:profile", states("a", "B"));
        assertEquals(
                List.of("a", "B"),
                store.getList(u, s, "agent:profile", TestState.class).stream()
                        .map(TestState::value)
                        .toList());
    }

    @Test
    @DisplayName("C1 regression: editing a non-sampled middle element of a large list is persisted")
    void listSave_largeList_middleEditPersisted() {
        String u = "user-1001", s = "session-005";
        List<TestState> big = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            big.add(new TestState("m" + i));
        }
        store.save(u, s, "agent:profile", big);
        // Change an element well inside the list (under the old 5-point sampling this would be
        // missed for size > threshold).
        big.set(7, new TestState("CHANGED"));
        store.save(u, s, "agent:profile", big);
        List<String> got =
                store.getList(u, s, "agent:profile", TestState.class).stream()
                        .map(TestState::value)
                        .toList();
        assertEquals("CHANGED", got.get(7));
        assertEquals(30, got.size());
    }

    @Test
    @DisplayName("CN1 race: concurrent list saves leave one writer's complete list (no hybrid)")
    void concurrentListSaves_finalListIsOneWriters() throws InterruptedException {
        String u = "user-1001", s = "session-006";
        // Pre-seed so both rewrite and append paths can be exercised.
        store.save(u, s, "agent:profile", states("seed"));

        int writers = 8;
        List<List<TestState>> known = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
            List<TestState> list = new ArrayList<>();
            for (int i = 0; i <= w; i++) {
                list.add(new TestState("w" + w + "-" + i));
            }
            known.add(list);
        }

        CyclicBarrier barrier = new CyclicBarrier(writers);
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        for (int w = 0; w < writers; w++) {
            final List<TestState> list = known.get(w);
            pool.submit(
                    () -> {
                        try {
                            barrier.await();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                        store.save(u, s, "agent:profile", list);
                    });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        List<TestState> finalList = store.getList(u, s, "agent:profile", TestState.class);
        // Atomicity + guarded-append: the final list must be exactly one writer's full list.
        assertTrue(
                known.contains(finalList),
                "final list must equal one writer's complete list, got=" + toValues(finalList));
    }

    // ---- H2: per-key delete ----

    @Test
    @DisplayName("H2: per-key delete removes single + list forms and the tracking members")
    void perKeyDelete_removesBothForms() {
        String u = "user-1001", s = "session-007";
        store.save(u, s, "agent:profile", new TestState("v"));
        store.save(u, s, "chat:history", states("a", "b"));
        assertTrue(store.exists(u, s));
        assertTrue(store.get(u, s, "agent:profile", TestState.class).isPresent());
        assertEquals(2, store.getList(u, s, "chat:history", TestState.class).size());

        store.delete(u, s, "agent:profile");
        assertFalse(store.get(u, s, "agent:profile", TestState.class).isPresent());
        // list form untouched
        assertEquals(2, store.getList(u, s, "chat:history", TestState.class).size());
        assertTrue(store.exists(u, s));

        store.delete(u, s, "chat:history");
        assertTrue(store.getList(u, s, "chat:history", TestState.class).isEmpty());
        // session marker gone once all keys are removed
        assertFalse(store.exists(u, s));
    }

    // ---- M1: single <-> list form cleanup ----

    @Test
    @DisplayName("M1: saving a list after a single value clears the stale single value")
    void saveList_clearsStaleSingleForm() {
        String u = "user-1001", s = "session-008";
        store.save(u, s, "agent:profile", new TestState("agent:profile"));
        assertTrue(store.get(u, s, "agent:profile", TestState.class).isPresent());

        store.save(u, s, "agent:profile", states("l1", "l2"));
        // The single-value payload must be gone (no stale data behind).
        assertTrue(store.get(u, s, "agent:profile", TestState.class).isEmpty());
        assertEquals(2, store.getList(u, s, "agent:profile", TestState.class).size());
    }

    @Test
    @DisplayName("M1: saving a single value after a list clears the stale list")
    void saveSingle_clearsStaleListForm() {
        String u = "user-1001", s = "session-009";
        store.save(u, s, "agent:profile", states("l1", "l2"));
        assertEquals(2, store.getList(u, s, "agent:profile", TestState.class).size());

        store.save(u, s, "agent:profile", new TestState("agent:profile"));
        // The list must be gone (no stale data behind).
        assertTrue(store.getList(u, s, "agent:profile", TestState.class).isEmpty());
        assertEquals(
                "agent:profile",
                store.get(u, s, "agent:profile", TestState.class).orElseThrow().value());
    }

    // ---- M2: clearAllSessions ----

    @Test
    @DisplayName("M2: clearAllSessions clears every session and returns the deleted key count")
    void clearAllSessions_clearsAndCounts() {
        String u = "user-1001";
        store.save(u, "session-101", "agent:profile", new TestState("a"));
        store.save(u, "session-102", "agent:profile", states("b1", "b2"));
        store.save(u, "session-103", "agent:profile", new TestState("c"));
        assertTrue(store.exists(u, "session-101"));
        assertTrue(store.exists(u, "session-102"));
        assertTrue(store.exists(u, "session-103"));

        Integer deleted = store.clearAllSessions().block();
        assertTrue(deleted != null && deleted > 0, "should report deleted keys");
        assertFalse(store.exists(u, "session-101"));
        assertFalse(store.exists(u, "session-102"));
        assertFalse(store.exists(u, "session-103"));
    }

    // ---- CN2: clear vs save atomicity (serial contract) ----

    @Test
    @DisplayName("CN2: save-then-delete removes it; delete-then-save keeps the new save")
    void deleteAndSave_serialConsistency() {
        String u = "user-1001", s = "session-010";
        store.save(u, s, "agent:profile", new TestState("v1"));
        store.delete(u, s);
        // save happened before delete -> gone
        assertTrue(store.get(u, s, "agent:profile", TestState.class).isEmpty());
        assertFalse(store.exists(u, s));

        // delete (no-op) then save -> new save persists and is visible
        store.delete(u, s);
        store.save(u, s, "agent:profile", new TestState("v2"));
        assertEquals("v2", store.get(u, s, "agent:profile", TestState.class).orElseThrow().value());
        assertTrue(store.exists(u, s));
    }

    // ---- S1: state-key validation ----

    @Test
    @DisplayName("S1: state key ending with ':list' is rejected")
    void validateStateKey_rejectsListSuffix() {
        assertThrows(
                IllegalArgumentException.class,
                () -> store.save("user-1001", "session-101", "bad:list", new TestState("v")));
    }

    @Test
    @DisplayName("S1: state key with braces is rejected")
    void validateStateKey_rejectsBraces() {
        assertThrows(
                IllegalArgumentException.class,
                () -> store.save("user-1001", "session-101", "bad{key", new TestState("v")));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.save("user-1001", "session-101", "bad}key", new TestState("v")));
    }

    @Test
    @DisplayName("S1: blank state key is rejected")
    void validateStateKey_rejectsBlank() {
        assertThrows(
                IllegalArgumentException.class,
                () -> store.save("user-1001", "session-101", "  ", new TestState("v")));
    }

    @Test
    @DisplayName("S1: per-key delete also validates the key")
    void perKeyDelete_validatesKey() {
        assertThrows(
                IllegalArgumentException.class,
                () -> store.delete("user-1001", "session-101", "bad:list"));
    }

    @Test
    @DisplayName("S1: a normal key still works after validation is in place")
    void validateStateKey_normalKeyWorks() {
        assertDoesNotThrow(
                () -> store.save("user-1001", "session-101", "agent:profile", new TestState("ok")));
        assertEquals(
                "ok",
                store.get("user-1001", "session-101", "agent:profile", TestState.class)
                        .orElseThrow()
                        .value());
    }

    // ---- N1: userId/sessionId reserved-character validation ----
    // Without this, a userId such as "a/b" makes listSessionIds("a") leak another user's sessions,
    // and "a,b" makes the SCAN pattern parse as a glob set and return nothing.

    @Test
    @DisplayName("N1: userId containing '/' is rejected (prevents listSessionIds cross-user leak)")
    void n1_userIdWithSlashRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> store.save("tenant/1", "session-n1-1", "agent:profile", new TestState("v")));
    }

    @Test
    @DisplayName("N1: userId containing ',' is rejected (prevents SCAN glob-set misparse)")
    void n1_userIdWithCommaRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> store.save("tenant,1", "session-n1-2", "agent:profile", new TestState("v")));
    }

    @Test
    @DisplayName("N1: sessionId containing '/' is rejected")
    void n1_sessionIdWithSlashRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> store.save("user-1001", "session/n1", "agent:profile", new TestState("v")));
    }

    @Test
    @DisplayName("N1: sessionId containing ',' is rejected")
    void n1_sessionIdWithCommaRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> store.save("user-1001", "session,n1", "agent:profile", new TestState("v")));
    }

    @Test
    @DisplayName("N1: glob metacharacters in userId are rejected")
    void n1_globMetacharInUserIdRejected() {
        for (String bad : new String[] {"a*b", "a?b", "a[b", "a]b", "a\\b"}) {
            String uid = bad;
            assertThrows(
                    IllegalArgumentException.class,
                    () -> store.save(uid, "session-n1-3", "agent:profile", new TestState("v")),
                    "userId=" + uid + " should be rejected");
        }
    }

    @Test
    @DisplayName("N1: listSessionIds rejects a userId with reserved characters")
    void n1_listSessionIdsRejectsInvalidUser() {
        assertThrows(IllegalArgumentException.class, () -> store.listSessionIds("user/a"));
        assertThrows(IllegalArgumentException.class, () -> store.listSessionIds("user,a"));
    }

    // ---- N2: anonymous-user sentinel must not collide with a real userId ----

    @Test
    @DisplayName("N2: a real userId equal to the anon sentinel is rejected (no slot collision)")
    void n2_anonSentinelCollisionRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> store.save("__anon__", "session-n2-1", "agent:profile", new TestState("v")));
    }

    @Test
    @DisplayName("N2: anonymous (null) userId still works and stays isolated")
    void n2_anonymousUserIdWorks() {
        store.save((String) null, "session-n2-2", "agent:profile", new TestState("anon"));
        assertEquals(
                "anon",
                store.get((String) null, "session-n2-2", "agent:profile", TestState.class)
                        .orElseThrow()
                        .value());
    }

    // ---- N3: clearAllSessions must delete very large sessions (batched DEL, no Lua unpack limit)
    // ----

    @Test
    @DisplayName("N3: clearAllSessions deletes a session with hundreds of keys (batched DEL)")
    void n3_clearHugeSession_batchedDelete() {
        String u = "user-1001", s = "session-n3-1";
        int keyCount = 260;
        for (int i = 0; i < keyCount; i++) {
            store.save(u, s, "k" + i, new TestState("v" + i));
        }
        assertTrue(store.exists(u, s));
        // A single unpack(toDelete) of >500 keys would have thrown a Lua C-stack error before the
        // batched fix; here it must delete every data key and the marker set.
        Integer deleted = store.clearAllSessions().block();
        assertTrue(
                deleted != null && deleted >= keyCount * 2 + 1,
                "should delete at least " + (keyCount * 2 + 1) + " keys, got " + deleted);
        assertFalse(store.exists(u, s));
    }

    private static List<String> toValues(List<TestState> list) {
        List<String> out = new ArrayList<>();
        for (TestState t : list) {
            out.add(t.value());
        }
        return out;
    }
}
