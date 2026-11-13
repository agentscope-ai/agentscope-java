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
package io.agentscope.core.journal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.ThreadKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AtomicThreadJournalTest {
    private final ThreadKey key = new ThreadKey("u", "a", "s", "main");

    private JournalEvent event(long seq, String type) {
        return new JournalEvent(
                JournalEvent.SCHEMA_VERSION, "e" + seq, seq, 1, type, "r", "t", true, "{}");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"formatVersion\":1,\"seq\":1,\"epoch\":1,\"commitId\":\"old\"}",
                "{\"formatVersion\":3,\"seq\":1,\"epoch\":1,\"commitId\":\"future\"}",
                "{\"formatVersion\":2,\"seq\":-1,\"epoch\":1}",
                "null",
                "not-json"
            })
    void unsupportedOrCorruptHeadIsRejectedWithoutWriting(String json) {
        byte[] original = json.getBytes(StandardCharsets.UTF_8);
        var storage =
                new AtomicJournalStorage() {
                    public Value read(String path) {
                        return path.equals("test/head.json") ? new Value(7, original) : null;
                    }

                    public boolean compareAndSet(String path, long version, byte[] bytes) {
                        throw new AssertionError("Invalid journal must not be mutated: " + path);
                    }
                };
        var log = new AtomicThreadJournal(storage, "test");
        assertThrows(JournalException.class, log::head);
        assertThrows(JournalException.class, () -> log.acquire("new-run", Duration.ofMinutes(2)));
        assertThrows(JournalException.class, () -> log.sealWriter("old-run"));
        assertThrows(JournalException.class, () -> log.readAfter(0, 10));
        assertArrayEquals(original, storage.read("test/head.json").bytes());
    }

    @Test
    void committedPrefixIsOrderedAndBatchReplayIsIdempotent() {
        var log = new InMemoryThreadJournalStore().open(key, null);
        var writer = log.acquire("w", Duration.ofMinutes(2));
        var batch = List.of(event(1, "run/start"), event(2, "run/end"));
        log.commit(writer, "batch", 0, batch);
        log.commit(writer, "batch", 0, batch);
        assertEquals(2, log.head().seq());
        assertEquals(ThreadJournal.FORMAT_VERSION, log.head().formatVersion());
        assertEquals(batch, log.readAfter(0, 10));
        assertEquals(List.of(batch.get(1)), log.readAfter(1, 10));
        assertThrows(
                JournalException.class,
                () -> log.commit(writer, "batch", 0, List.of(event(1, "run/stop_requested"))));
    }

    @Test
    void staleWritersAreFencedAndLeaseCannotBeStolen() {
        var log = new InMemoryThreadJournalStore().open(key, null);
        var first = log.acquire("first", Duration.ofMinutes(2));
        assertThrows(JournalException.class, () -> log.acquire("second", Duration.ofMinutes(2)));
        log.release(first);
        var second = log.acquire("second", Duration.ofMinutes(2));
        assertTrue(second.epoch() > first.epoch());
        assertThrows(
                JournalException.class,
                () -> log.commit(first, "old", 0, List.of(event(1, "run/start"))));
    }

    @Test
    void readOnlyInspectionDoesNotRepairOrAcquire() {
        var log = new InMemoryThreadJournalStore().open(key, null);
        var writer = log.acquire("w", Duration.ofMinutes(2));
        log.commit(writer, "b", 0, List.of(event(1, "run/start")));
        var head = log.head();
        assertEquals(Set.of("r"), JournalProjection.read(log).activeRuns());
        assertEquals(head, log.head());
    }

    @Test
    void unknownRequiredEventsFailRecoveryButOptionalEventsSurvive() {
        var log = new InMemoryThreadJournalStore().open(key, null);
        var writer = log.acquire("w", Duration.ofMinutes(2));
        log.commit(writer, "b", 0, List.of(event(1, "plugin/unknown")));
        assertThrows(JournalException.class, () -> JournalProjection.read(log));
    }

    @Test
    void uploadedOrphanIsInvisibleAndMissingCommittedDataFailsClosed() {
        class Storage implements AtomicJournalStorage {
            Map<String, Value> values = new HashMap<>();
            boolean failHead;

            public Value read(String key) {
                return values.get(key);
            }

            public boolean compareAndSet(String key, long version, byte[] bytes) {
                if (key.endsWith("head.json") && failHead)
                    throw new JournalException("injected failure");
                var old = read(key);
                if ((old == null ? 0 : old.version()) != version) return false;
                values.put(key, new Value(version + 1, bytes));
                return true;
            }
        }
        var storage = new Storage();
        var log = new AtomicThreadJournal(storage, "test");
        var writer = log.acquire("w", Duration.ofMinutes(2));
        storage.failHead = true;
        assertThrows(
                JournalException.class,
                () -> log.commit(writer, "b", 0, List.of(event(1, "run/start"))));
        assertTrue(log.readAfter(0, 10).isEmpty());
        storage.failHead = false;
        log.commit(writer, "b", 0, List.of(event(1, "run/start")));
        storage.values.keySet().removeIf(path -> path.contains("/commits/"));
        assertThrows(JournalException.class, () -> log.readAfter(0, 10));
    }

    @Test
    void emptyDiscoveryFallsBackToPerSegmentReads() {
        class Storage implements AtomicJournalStorage {
            final Map<String, Value> values = new HashMap<>();

            public Value read(String path) {
                return values.get(path);
            }

            public boolean compareAndSet(String path, long version, byte[] bytes) {
                var old = values.get(path);
                if ((old == null ? 0 : old.version()) != version) return false;
                values.put(path, new Value(version + 1, bytes));
                return true;
            }

            @Override
            public List<String> listPaths(String prefix) {
                // A snapshot taken while the writer was still renaming segments into place.
                return List.of();
            }
        }
        var storage = new Storage();
        var log = new AtomicThreadJournal(storage, "test");
        var writer = log.acquire("w", Duration.ofMinutes(2));
        var batch = List.of(event(1, "run/start"), event(2, "run/end"));
        log.commit(writer, "b", 0, batch);
        assertEquals(batch, log.readAfter(0, 10));
    }

    @Test
    void permanentSealFencesPausedAcquisitionAndCommitButAllowsANewRun() {
        class Storage implements AtomicJournalStorage {
            final Map<String, Value> values = new HashMap<>();
            Runnable beforeHeadRead;
            Runnable beforeHeadCas;

            public Value read(String path) {
                if (path.endsWith("head.json") && beforeHeadRead != null) {
                    Runnable callback = beforeHeadRead;
                    beforeHeadRead = null;
                    callback.run();
                }
                return values.get(path);
            }

            public boolean compareAndSet(String path, long version, byte[] bytes) {
                if (path.endsWith("head.json") && beforeHeadCas != null) {
                    Runnable callback = beforeHeadCas;
                    beforeHeadCas = null;
                    callback.run();
                }
                var old = values.get(path);
                if ((old == null ? 0 : old.version()) != version) return false;
                values.put(path, new Value(version + 1, bytes));
                return true;
            }
        }
        var storage = new Storage();
        var log = new AtomicThreadJournal(storage, "sealed");
        // The seal arrives after acquisition checked for a seal but before it reads head.
        storage.beforeHeadRead = () -> log.sealWriter("paused");
        assertThrows(JournalException.class, () -> log.acquire("paused", Duration.ofMinutes(2)));
        var fresh = log.acquire("fresh", Duration.ofMinutes(2));
        log.commit(fresh, "first", 0, List.of(event(1, "run/start")));
        storage.beforeHeadCas = () -> log.sealWriter("fresh");
        assertThrows(
                JournalException.class,
                () -> log.commit(fresh, "late", 1, List.of(event(2, "run/end"))));
        assertEquals(1, log.head().seq());
        var restarted = new AtomicThreadJournal(storage, "sealed");
        assertThrows(
                JournalException.class, () -> restarted.acquire("fresh", Duration.ofMinutes(2)));
        var resume = restarted.acquire("resume-with-new-id", Duration.ofMinutes(2));
        restarted.sealWriter("paused"); // must not revoke this unrelated writer
        restarted.commit(resume, "resumed", 1, List.of(event(2, "run/start")));
        assertEquals(2, restarted.head().seq());
    }
}
