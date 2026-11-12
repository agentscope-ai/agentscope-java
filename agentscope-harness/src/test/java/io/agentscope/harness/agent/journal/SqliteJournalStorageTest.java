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
package io.agentscope.harness.agent.journal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.ThreadKey;
import io.agentscope.core.journal.AtomicThreadJournal;
import io.agentscope.core.journal.JournalEvent;
import io.agentscope.core.journal.JournalException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class SqliteJournalStorageTest {
    @TempDir Path directory;

    @Test
    void readingAnUnwrittenNamespaceDoesNotCreateStorage() {
        Path root = directory.resolve("runtime");
        var storage = new SqliteJournalStorage(root);
        assertNull(storage.read("agents/a/session.json"));
        assertTrue(storage.listPaths("agents/").isEmpty());
        assertFalse(Files.exists(root));
    }

    @Test
    void independentInstancesPreserveVersionsAndRejectStaleWriters() {
        var first = new SqliteJournalStorage(directory);
        var second = new SqliteJournalStorage(directory);
        String key = "agents/a/sessions/session.json";
        assertFalse(first.compareAndSet(key, 1, new byte[] {0}));
        assertNull(second.read(key));
        assertTrue(first.compareAndSet(key, 0, new byte[] {1, 2}));
        assertFalse(second.compareAndSet(key, 0, new byte[] {9}));
        assertTrue(second.compareAndSet(key, 1, new byte[] {3, 4}));
        assertFalse(first.compareAndSet(key, 1, new byte[] {9}));
        var restored = new SqliteJournalStorage(directory).read(key);
        assertEquals(2, restored.version());
        assertArrayEquals(new byte[] {3, 4}, restored.bytes());
    }

    @Test
    void discoveryTreatsPrefixesLiterallyAndReturnsSortedKeys() {
        var storage = new SqliteJournalStorage(directory);
        for (String path : List.of("a%/z", "ab/other", "a%/b"))
            assertTrue(storage.compareAndSet(path, 0, new byte[] {1}));
        assertEquals(List.of("a%/b", "a%/z"), storage.listPaths("a%/"));
    }

    @Test
    @Timeout(20)
    void concurrentConnectionsAdmitExactlyOneWriterForEachVersion() throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        try {
            for (int version = 0; version < 3; version++) {
                long expected = version;
                var start = new CountDownLatch(1);
                var attempts = new ArrayList<Future<Boolean>>();
                for (int writer = 0; writer < 8; writer++) {
                    byte value = (byte) writer;
                    attempts.add(
                            pool.submit(
                                    () -> {
                                        start.await();
                                        return new SqliteJournalStorage(directory)
                                                .compareAndSet(
                                                        "session", expected, new byte[] {value});
                                    }));
                }
                start.countDown();
                int successes = 0;
                for (var attempt : attempts) if (attempt.get(10, TimeUnit.SECONDS)) successes++;
                assertEquals(1, successes);
                assertEquals(
                        version + 1, new SqliteJournalStorage(directory).read("session").version());
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void journalEventsAndWriterOwnershipSurviveNewConnections() {
        var key = new ThreadKey("user", "agent", "session", "main");
        var first = new AtomicThreadJournal(new SqliteJournalStorage(directory), key.storagePath());
        var writer = first.acquire("owner", Duration.ofMinutes(2));
        var event =
                new JournalEvent(
                        JournalEvent.SCHEMA_VERSION,
                        "event",
                        1,
                        1,
                        "run/start",
                        "run",
                        "turn",
                        true,
                        "{}");
        first.commit(writer, "batch", 0, List.of(event));
        var restored =
                new AtomicThreadJournal(new SqliteJournalStorage(directory), key.storagePath());
        assertEquals(List.of(event), restored.readAfter(0, 10));
        assertThrows(
                JournalException.class, () -> restored.acquire("other", Duration.ofMinutes(2)));
        first.release(writer);
        var next = restored.acquire("other", Duration.ofMinutes(2));
        restored.release(next);
    }

    @Test
    void corruptDatabaseReportsFailureInsteadOfAFalseVersionConflict() throws Exception {
        Files.writeString(directory.resolve("journal.sqlite3"), "not a database");
        var storage = new SqliteJournalStorage(directory);
        assertThrows(JournalException.class, () -> storage.read("session"));
        assertThrows(
                JournalException.class, () -> storage.compareAndSet("session", 0, new byte[] {1}));
        assertThrows(JournalException.class, () -> storage.listPaths(""));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void localStorageOnWindowsUsesTheTransactionalBackendAndRetainsPathGuards() {
        var storage = new LocalJournalStorage(directory);
        assertTrue(storage.compareAndSet("agents/a/session.json", 0, new byte[] {1}));
        assertTrue(Files.exists(directory.resolve("journal.sqlite3")));
        assertEquals(List.of("agents/a/session.json"), storage.listPaths("agents/"));
        assertThrows(
                IllegalArgumentException.class,
                () -> storage.compareAndSet("../outside", 0, new byte[] {1}));
        assertArrayEquals(
                new byte[] {1},
                new LocalJournalStorage(directory).read("agents/a/session.json").bytes());
    }
}
