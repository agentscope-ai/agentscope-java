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
package io.agentscope.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.util.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class JournalSessionLogTest {
    private final SessionKey key = new SessionKey("u", "a", "s");

    private SessionEvent event(long seq, String type) {
        return new SessionEvent(1, "e" + seq, seq, 1, type, "r", "t", true, "{}");
    }

    @Test
    void unsupportedShadowHistoryCannotBecomeAuthoritative() {
        assertThrows(
                SessionLogException.class,
                () -> new SessionHeader(1, "shadow", "adapter_chunks", 0).validate());
    }

    @Test
    void committedPrefixIsOrderedAndBatchReplayIsIdempotent() {
        var log = new InMemorySessionLogStore().open(key, null);
        var writer = log.acquire("w", Duration.ofMinutes(2));
        var batch = List.of(event(1, "run/start"), event(2, "run/end"));
        log.commit(writer, "batch", 0, batch);
        log.commit(writer, "batch", 0, batch);
        assertEquals(2, log.head().seq());
        assertEquals(batch, log.readAfter(0, 10));
        assertEquals(List.of(batch.get(1)), log.readAfter(1, 10));
        assertThrows(
                SessionLogException.class,
                () -> log.commit(writer, "batch", 0, List.of(event(1, "turn/start"))));
    }

    @Test
    void staleWritersAreFencedAndLeaseCannotBeStolen() {
        var log = new InMemorySessionLogStore().open(key, null);
        var first = log.acquire("first", Duration.ofMinutes(2));
        assertThrows(SessionLogException.class, () -> log.acquire("second", Duration.ofMinutes(2)));
        log.release(first);
        var second = log.acquire("second", Duration.ofMinutes(2));
        assertTrue(second.epoch() > first.epoch());
        assertThrows(
                SessionLogException.class,
                () -> log.commit(first, "old", 0, List.of(event(1, "run/start"))));
    }

    @Test
    void readOnlyInspectionDoesNotRepairOrAcquire() {
        var log = new InMemorySessionLogStore().open(key, null);
        var writer = log.acquire("w", Duration.ofMinutes(2));
        log.commit(writer, "b", 0, List.of(event(1, "run/start")));
        var head = log.head();
        assertEquals(Set.of("r"), SessionProjection.read(log).activeRuns());
        assertEquals(head, log.head());
    }

    @Test
    void defaultReadSessionFallsBackToReadAndCannotEscapeOrCrossThreads() throws Exception {
        var storage = new TestStorage();
        storage.put("value", new byte[] {1, 2});
        var escaped = new AtomicReference<AtomicSessionStorage.ReadSession>();

        var executor = Executors.newSingleThreadExecutor();
        try {
            storage.withReadSession(
                    reader -> {
                        escaped.set(reader);
                        assertEquals(2, reader.read("value").bytes().length);
                        var crossThread = executor.submit(() -> reader.read("value"));
                        var error = assertThrows(ExecutionException.class, crossThread::get);
                        assertTrue(error.getCause() instanceof IllegalStateException);
                        return null;
                    });
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, storage.readsContaining("value"));
        assertThrows(IllegalStateException.class, () -> escaped.get().read("value"));
    }

    @Test
    void defaultReadSessionClosesAfterCallbackFailure() {
        var storage = new TestStorage();
        var escaped = new AtomicReference<AtomicSessionStorage.ReadSession>();

        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                storage.withReadSession(
                                        reader -> {
                                            escaped.set(reader);
                                            throw new IllegalArgumentException("injected");
                                        }));

        assertEquals("injected", error.getMessage());
        assertThrows(IllegalStateException.class, () -> escaped.get().read("value"));
    }

    @Test
    void scanCachesRawCommitRowsAcrossRepeatedIterations() {
        var storage = new TestStorage();
        var log = createLog(storage, "cached", 6);
        storage.resetReads();

        Iterable<SessionEvent> scan = log.scan(0, 6);
        assertEquals(6, storage.readsContaining("/commits/"));

        List<SessionEvent> first = collect(scan);
        List<SessionEvent> second = collect(scan);

        assertEquals(first, second);
        assertEquals(6, first.size());
        assertEquals(6, storage.readsContaining("/commits/"));
    }

    @Test
    void emptyScanDoesNotReadCommitRows() {
        var storage = new TestStorage();
        var log = new JournalSessionLog(storage, "empty");

        Iterable<SessionEvent> scan = log.scan(0, 0);

        assertTrue(collect(scan).isEmpty());
        assertTrue(collect(scan).isEmpty());
        assertEquals(0, storage.readsContaining("/commits/"));
    }

    @Test
    void scanCacheIncludesExactByteBoundaryAndFallsBackAfterOverflow() {
        var exactStorage = new TestStorage();
        var exact =
                seededTwoCommitLog(
                        exactStorage, "exact", JournalSessionLog.SCAN_COMMIT_CACHE_MAX_BYTES);
        exactStorage.resetReads();

        Iterable<SessionEvent> exactScan = exact.scan(0, 2);
        assertEquals(List.of(event(1, "old"), event(2, "new")), collect(exactScan));
        assertEquals(List.of(event(1, "old"), event(2, "new")), collect(exactScan));
        assertEquals(4, exactStorage.readsContaining("/commits/"));

        var overflowStorage = new TestStorage();
        var overflow =
                seededTwoCommitLog(
                        overflowStorage,
                        "overflow",
                        JournalSessionLog.SCAN_COMMIT_CACHE_MAX_BYTES + 1);
        overflowStorage.resetReads();

        Iterable<SessionEvent> overflowScan = overflow.scan(0, 2);
        assertEquals(List.of(event(1, "old"), event(2, "new")), collect(overflowScan));
        assertEquals(List.of(event(1, "old"), event(2, "new")), collect(overflowScan));
        assertEquals(6, overflowStorage.readsContaining("/commits/"));

        overflowStorage.remove("overflow/commits/old.json");
        assertThrows(SessionLogException.class, () -> collect(overflowScan));
    }

    @Test
    void cachedCommitRowsCaptureScanCreationStateButNewScansSeeMutation() {
        var storage = new TestStorage();
        var log = createLog(storage, "captured", 1);
        SessionEvent original = event(1, "type-1");
        Iterable<SessionEvent> captured = log.scan(0, 1);
        String commitPath = storage.pathContaining("/commits/");

        SessionEvent tampered = event(1, "tampered");
        storage.overwrite(
                commitPath,
                commitBytes("batch-1", null, List.of(tampered), hashEvents(List.of(original))));

        assertEquals(List.of(original), collect(captured));
        assertEquals(List.of(original), collect(captured));
        SessionLogException error =
                assertThrows(SessionLogException.class, () -> collect(log.scan(0, 1)));
        assertTrue(error.getMessage().contains("checksum mismatch"));
    }

    @Test
    void cachedCommitRowsStillLoadAndValidateBlobsOnEveryIteration() {
        var storage = new TestStorage();
        var log = new JournalSessionLog(storage, "blob");
        var writer = log.acquire("w", Duration.ofMinutes(2));
        String payload = "{\"value\":\"" + "x".repeat(70 * 1024) + "\"}";
        var large = new SessionEvent(1, "large", 1, 1, "blob", "r", "t", true, payload);
        log.commit(writer, "large-batch", 0, List.of(large));
        Iterable<SessionEvent> scan = log.scan(0, 1);
        String blobPath = storage.pathContaining("/blobs/");

        assertEquals(List.of(large), collect(scan));
        storage.remove(blobPath);
        SessionLogException missing = assertThrows(SessionLogException.class, () -> collect(scan));
        assertTrue(missing.getMessage().contains("Missing or corrupt committed payload blob"));

        storage.overwrite(blobPath, "corrupt".getBytes(StandardCharsets.UTF_8));
        SessionLogException corrupt = assertThrows(SessionLogException.class, () -> collect(scan));
        assertTrue(corrupt.getMessage().contains("Missing or corrupt committed payload blob"));
    }

    @Test
    void cachedCommitRowsSupportConcurrentIteratorsWithoutAdditionalReads() throws Exception {
        var storage = new TestStorage();
        var log = createLog(storage, "concurrent", 6);
        storage.resetReads();
        Iterable<SessionEvent> scan = log.scan(0, 6);
        List<SessionEvent> expected =
                List.of(
                        event(1, "type-1"),
                        event(2, "type-2"),
                        event(3, "type-3"),
                        event(4, "type-4"),
                        event(5, "type-5"),
                        event(6, "type-6"));

        var executor = Executors.newFixedThreadPool(4);
        try {
            var tasks = new ArrayList<java.util.concurrent.Callable<List<SessionEvent>>>();
            for (int i = 0; i < 8; i++) tasks.add(() -> collect(scan));
            for (var result : executor.invokeAll(tasks)) assertEquals(expected, result.get());
        } finally {
            executor.shutdownNow();
        }

        assertEquals(6, storage.readsContaining("/commits/"));
    }

    @Test
    void scanUsesOneReadSessionAndIgnoresUnreachableCommitRows() {
        var storage = new TestStorage();
        var log = createLog(storage, "reachable", 3);
        storage.put("reachable/commits/orphan.json", "not-json".getBytes(StandardCharsets.UTF_8));
        storage.resetReads();

        assertEquals(3, collect(log.scan(0, 3)).size());

        assertEquals(1, storage.readSessions());
        assertEquals(3, storage.readsContaining("/commits/"));
        assertEquals(0, storage.readsContaining("orphan"));
    }

    @Test
    void unknownRequiredEventsFailRecoveryButOptionalEventsSurvive() {
        var log = new InMemorySessionLogStore().open(key, null);
        var writer = log.acquire("w", Duration.ofMinutes(2));
        log.commit(writer, "b", 0, List.of(event(1, "plugin/unknown")));
        assertThrows(SessionLogException.class, () -> SessionProjection.read(log));
    }

    @Test
    void uploadedOrphanIsInvisibleAndMissingCommittedDataFailsClosed() {
        class Storage implements AtomicSessionStorage {
            Map<String, Value> values = new HashMap<>();
            boolean failHead;

            public Value read(String key) {
                return values.get(key);
            }

            public boolean compareAndSet(String key, long version, byte[] bytes) {
                if (key.endsWith("head.json") && failHead)
                    throw new SessionLogException("injected failure");
                var old = read(key);
                if ((old == null ? 0 : old.version()) != version) return false;
                values.put(key, new Value(version + 1, bytes));
                return true;
            }
        }
        var storage = new Storage();
        var log = new JournalSessionLog(storage, "test");
        var writer = log.acquire("w", Duration.ofMinutes(2));
        storage.failHead = true;
        assertThrows(
                SessionLogException.class,
                () -> log.commit(writer, "b", 0, List.of(event(1, "run/start"))));
        assertTrue(log.readAfter(0, 10).isEmpty());
        storage.failHead = false;
        log.commit(writer, "b", 0, List.of(event(1, "run/start")));
        storage.values.keySet().removeIf(path -> path.contains("/commits/"));
        assertThrows(SessionLogException.class, () -> log.readAfter(0, 10));
    }

    @Test
    void permanentSealFencesPausedAcquisitionAndCommitButAllowsANewRun() {
        class Storage implements AtomicSessionStorage {
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
        var log = new JournalSessionLog(storage, "sealed");
        // The seal arrives after acquisition checked for a seal but before it reads head.
        storage.beforeHeadRead = () -> log.sealWriter("paused");
        assertThrows(SessionLogException.class, () -> log.acquire("paused", Duration.ofMinutes(2)));
        var fresh = log.acquire("fresh", Duration.ofMinutes(2));
        log.commit(fresh, "first", 0, List.of(event(1, "run/start")));
        storage.beforeHeadCas = () -> log.sealWriter("fresh");
        assertThrows(
                SessionLogException.class,
                () -> log.commit(fresh, "late", 1, List.of(event(2, "run/end"))));
        assertEquals(1, log.head().seq());
        var restarted = new JournalSessionLog(storage, "sealed");
        assertThrows(
                SessionLogException.class, () -> restarted.acquire("fresh", Duration.ofMinutes(2)));
        var resume = restarted.acquire("resume-with-new-id", Duration.ofMinutes(2));
        restarted.sealWriter("paused"); // must not revoke this unrelated writer
        restarted.commit(resume, "resumed", 1, List.of(event(2, "run/start")));
        assertEquals(2, restarted.head().seq());
    }

    private JournalSessionLog createLog(TestStorage storage, String prefix, int commits) {
        var log = new JournalSessionLog(storage, prefix);
        var writer = log.acquire("w", Duration.ofMinutes(2));
        for (int seq = 1; seq <= commits; seq++)
            log.commit(writer, "batch-" + seq, seq - 1, List.of(event(seq, "type-" + seq)));
        return log;
    }

    private JournalSessionLog seededTwoCommitLog(
            TestStorage storage, String prefix, int newestCommitBytes) {
        SessionEvent oldEvent = event(1, "old");
        SessionEvent newEvent = event(2, "new");
        byte[] oldCommit = commitBytes("old-batch", null, List.of(oldEvent), null);
        byte[] newCommit = sizedCommitBytes("old", List.of(newEvent), newestCommitBytes);
        storage.put(prefix + "/commits/old.json", oldCommit);
        storage.put(prefix + "/commits/new.json", newCommit);
        storage.put(prefix + "/head.json", jsonBytes(new SessionLog.Head(2, 0, null, 0, "new")));
        return new JournalSessionLog(storage, prefix);
    }

    private static byte[] sizedCommitBytes(
            String parent, List<SessionEvent> events, int targetBytes) {
        byte[] base = commitBytes("", parent, events, null);
        if (base.length > targetBytes) throw new IllegalArgumentException("targetBytes");
        byte[] result = commitBytes("x".repeat(targetBytes - base.length), parent, events, null);
        assertEquals(targetBytes, result.length);
        return result;
    }

    private static byte[] commitBytes(
            String batchId, String parent, List<SessionEvent> events, String expectedHash) {
        String hash = expectedHash == null ? hashEvents(events) : expectedHash;
        var commit =
                new JournalSessionLog.Commit(
                        batchId,
                        parent,
                        events.get(0).seq(),
                        events.get(events.size() - 1).seq(),
                        hash,
                        events,
                        Map.of());
        return jsonBytes(commit);
    }

    private static String hashEvents(List<SessionEvent> events) {
        return JournalSessionLog.hash(jsonBytes(events));
    }

    private static byte[] jsonBytes(Object value) {
        return JsonUtils.getJsonCodec().toJson(value).getBytes(StandardCharsets.UTF_8);
    }

    private static List<SessionEvent> collect(Iterable<SessionEvent> events) {
        var result = new ArrayList<SessionEvent>();
        events.forEach(result::add);
        return List.copyOf(result);
    }

    private static final class TestStorage implements AtomicSessionStorage {
        private final Map<String, Value> values = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> reads = new ConcurrentHashMap<>();
        private final AtomicInteger readSessions = new AtomicInteger();

        @Override
        public Value read(String path) {
            reads.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
            return values.get(path);
        }

        @Override
        public <T> T withReadSession(Function<ReadSession, T> operation) {
            readSessions.incrementAndGet();
            return AtomicSessionStorage.super.withReadSession(operation);
        }

        @Override
        public synchronized boolean compareAndSet(String path, long expectedVersion, byte[] bytes) {
            Value current = values.get(path);
            if ((current == null ? 0 : current.version()) != expectedVersion) return false;
            values.put(path, new Value(expectedVersion + 1, bytes));
            return true;
        }

        void put(String path, byte[] bytes) {
            values.put(path, new Value(1, bytes));
        }

        void overwrite(String path, byte[] bytes) {
            Value current = values.get(path);
            values.put(path, new Value(current == null ? 1 : current.version() + 1, bytes));
        }

        void remove(String path) {
            values.remove(path);
        }

        String pathContaining(String fragment) {
            return values.keySet().stream()
                    .filter(path -> path.contains(fragment))
                    .findFirst()
                    .orElseThrow();
        }

        void resetReads() {
            reads.clear();
            readSessions.set(0);
        }

        int readSessions() {
            return readSessions.get();
        }

        int readsContaining(String fragment) {
            return reads.entrySet().stream()
                    .filter(entry -> entry.getKey().contains(fragment))
                    .mapToInt(entry -> entry.getValue().get())
                    .sum();
        }
    }
}
