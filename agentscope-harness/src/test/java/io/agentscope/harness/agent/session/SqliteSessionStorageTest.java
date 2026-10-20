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
package io.agentscope.harness.agent.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.session.JournalSessionLog;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionKey;
import io.agentscope.core.session.SessionLogException;
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

class SqliteSessionStorageTest {
    @TempDir Path directory;

    @Test
    void readingAnUnwrittenNamespaceDoesNotCreateStorage() {
        Path root = directory.resolve("runtime");
        var storage = new SqliteSessionStorage(root);
        assertNull(storage.read("agents/a/session.json"));
        assertTrue(storage.listPaths("agents/").isEmpty());
        assertFalse(Files.exists(root));
    }

    @Test
    void independentInstancesPreserveVersionsAndRejectStaleWriters() {
        var first = new SqliteSessionStorage(directory);
        var second = new SqliteSessionStorage(directory);
        String key = "agents/a/sessions/session.json";
        assertFalse(first.compareAndSet(key, 1, new byte[] {0}));
        assertNull(second.read(key));
        assertTrue(first.compareAndSet(key, 0, new byte[] {1, 2}));
        assertFalse(second.compareAndSet(key, 0, new byte[] {9}));
        assertTrue(second.compareAndSet(key, 1, new byte[] {3, 4}));
        assertFalse(first.compareAndSet(key, 1, new byte[] {9}));
        var restored = new SqliteSessionStorage(directory).read(key);
        assertEquals(2, restored.version());
        assertArrayEquals(new byte[] {3, 4}, restored.bytes());
    }

    @Test
    void discoveryTreatsPrefixesLiterallyAndReturnsSortedKeys() {
        var storage = new SqliteSessionStorage(directory);
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
                                        return new SqliteSessionStorage(directory)
                                                .compareAndSet(
                                                        "session", expected, new byte[] {value});
                                    }));
                }
                start.countDown();
                int successes = 0;
                for (var attempt : attempts) if (attempt.get(10, TimeUnit.SECONDS)) successes++;
                assertEquals(1, successes);
                assertEquals(
                        version + 1, new SqliteSessionStorage(directory).read("session").version());
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void journalEventsAndWriterOwnershipSurviveNewConnections() {
        var key = new SessionKey("user", "agent", "session");
        var first = new JournalSessionLog(new SqliteSessionStorage(directory), key.storagePath());
        var writer = first.acquire("owner", Duration.ofMinutes(2));
        var event = new SessionEvent(1, "event", 1, 1, "run/start", "run", "turn", true, "{}");
        first.commit(writer, "batch", 0, List.of(event));
        var restored =
                new JournalSessionLog(new SqliteSessionStorage(directory), key.storagePath());
        assertEquals(List.of(event), restored.readAfter(0, 10));
        assertThrows(
                SessionLogException.class, () -> restored.acquire("other", Duration.ofMinutes(2)));
        first.release(writer);
        var next = restored.acquire("other", Duration.ofMinutes(2));
        restored.release(next);
    }

    @Test
    void corruptDatabaseReportsFailureInsteadOfAFalseVersionConflict() throws Exception {
        Files.writeString(directory.resolve("journal.sqlite3"), "not a database");
        var storage = new SqliteSessionStorage(directory);
        assertThrows(SessionLogException.class, () -> storage.read("session"));
        assertThrows(
                SessionLogException.class,
                () -> storage.compareAndSet("session", 0, new byte[] {1}));
        assertThrows(SessionLogException.class, () -> storage.listPaths(""));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void localStorageOnWindowsUsesTheTransactionalBackendAndRetainsPathGuards() {
        var storage = new LocalSessionStorage(directory);
        assertTrue(storage.compareAndSet("agents/a/session.json", 0, new byte[] {1}));
        assertTrue(Files.exists(directory.resolve("journal.sqlite3")));
        assertEquals(List.of("agents/a/session.json"), storage.listPaths("agents/"));
        assertThrows(
                IllegalArgumentException.class,
                () -> storage.compareAndSet("../outside", 0, new byte[] {1}));
        assertArrayEquals(
                new byte[] {1},
                new LocalSessionStorage(directory).read("agents/a/session.json").bytes());
    }
}
