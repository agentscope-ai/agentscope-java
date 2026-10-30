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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

import io.agentscope.core.session.AtomicSessionStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.JDBC;

/** The file-backed POSIX branch, and the SQLite-backed branch Windows delegates to. */
class LocalSessionStorageTest {
    @TempDir Path directory;

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void discoverySkipsAtomicWriteTempsAndLockFiles() throws Exception {
        var storage = new LocalSessionStorage(directory);
        assertTrue(storage.compareAndSet("commits/journal-1.json", 0, new byte[] {1, 2, 3}));
        Files.writeString(directory.resolve("commits/journal-2.tmp"), "half written");

        assertEquals(List.of("commits/journal-1.json"), storage.listPaths("commits/"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void batchReadServesTheWholeWindowsBatchOverOneSqliteConnection() throws Exception {
        var storage = new LocalSessionStorage(directory);
        var keys = new ArrayList<String>();
        for (int index = 0; index < 5; index++) {
            String key = "agents/a/commits/segment-" + index + ".json";
            assertTrue(storage.compareAndSet(key, 0, new byte[] {(byte) index}));
            keys.add(key);
        }
        var perItemReads = new HashMap<String, AtomicSessionStorage.Value>();
        for (String key : keys) perItemReads.put(key, storage.read(key));
        try (var connections = mockStatic(JDBC.class, CALLS_REAL_METHODS)) {
            var batch = storage.readAll(keys);
            connections.verify(
                    () -> JDBC.createConnection(anyString(), any(Properties.class)), times(1));
            for (int index = 0; index < 5; index++) {
                String key = keys.get(index);
                assertEquals(1, batch.get(key).version());
                assertArrayEquals(new byte[] {(byte) index}, batch.get(key).bytes());
                assertEquals(perItemReads.get(key).version(), batch.get(key).version());
                assertArrayEquals(perItemReads.get(key).bytes(), batch.get(key).bytes());
            }
            var withMissing =
                    storage.readAll(List.of(keys.get(0), "agents/a/commits/missing.json"));
            connections.verify(
                    () -> JDBC.createConnection(anyString(), any(Properties.class)), times(2));
            assertEquals(1, withMissing.size());
            assertArrayEquals(new byte[] {0}, withMissing.get(keys.get(0)).bytes());
            assertEquals(Map.of(), storage.readAll(List.of()));
            connections.verify(
                    () -> JDBC.createConnection(anyString(), any(Properties.class)), times(2));
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void batchReadMatchesPerItemFileReadsOnPosix() {
        var storage = new LocalSessionStorage(directory);
        assertTrue(storage.compareAndSet("commits/a.json", 0, new byte[] {1}));
        assertTrue(storage.compareAndSet("commits/b.json", 0, new byte[] {2}));
        var batch =
                storage.readAll(
                        List.of("commits/a.json", "commits/missing.json", "commits/b.json"));
        assertEquals(2, batch.size());
        assertEquals(1, batch.get("commits/a.json").version());
        assertArrayEquals(new byte[] {1}, batch.get("commits/a.json").bytes());
        assertEquals(1, batch.get("commits/b.json").version());
        assertArrayEquals(new byte[] {2}, batch.get("commits/b.json").bytes());
        assertEquals(Map.of(), storage.readAll(List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> storage.readAll(List.of("commits/a.json", "../outside")));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void batchReadStillRejectsAnOutOfRootKeyOnTheWindowsBackend() {
        var storage = new LocalSessionStorage(directory);
        assertTrue(storage.compareAndSet("agents/a/session.json", 0, new byte[] {1}));
        assertThrows(
                IllegalArgumentException.class,
                () -> storage.readAll(List.of("agents/a/session.json", "../outside")));
    }
}
