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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.journal.JournalException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs({OS.LINUX, OS.MAC})
class LocalJournalStorageTest {
    @TempDir Path directory;

    @Test
    void completedWritesDoNotRetainLocksForEveryObject() throws Exception {
        var storage = new LocalJournalStorage(directory);
        for (int i = 0; i < 32; i++) {
            String path = "commits/" + i + ".json";
            assertTrue(storage.compareAndSet(path, 0, new byte[] {1}));
            assertFalse(storage.compareAndSet(path, 0, new byte[] {2}));
        }
        assertEquals(0, retainedLocks());
    }

    @Test
    void failedWriteReleasesItsLockRegistration() throws Exception {
        Files.write(directory.resolve("broken.json"), new byte[] {1});
        var storage = new LocalJournalStorage(directory);
        assertThrows(
                JournalException.class,
                () -> storage.compareAndSet("broken.json", 0, new byte[] {2}));
        assertEquals(0, retainedLocks());
    }

    @Test
    @Timeout(60)
    void independentInstancesKeepCasAtomicAcrossLockReuse() throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        try {
            for (int version = 0; version < 20; version++) {
                long expected = version;
                var start = new CountDownLatch(1);
                var attempts = new ArrayList<Future<Boolean>>();
                for (int writer = 0; writer < 8; writer++) {
                    byte value = (byte) writer;
                    attempts.add(
                            pool.submit(
                                    () -> {
                                        start.await();
                                        return new LocalJournalStorage(directory)
                                                .compareAndSet(
                                                        "head.json", expected, new byte[] {value});
                                    }));
                }
                start.countDown();
                int successes = 0;
                for (var attempt : attempts) if (attempt.get(10, TimeUnit.SECONDS)) successes++;
                assertEquals(1, successes);
                assertEquals(
                        version + 1,
                        new LocalJournalStorage(directory).read("head.json").version());
                assertEquals(0, retainedLocks());
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private long retainedLocks() throws Exception {
        // Inspect only this test's root; other journal tests may use the process-wide registry.
        var field = LocalJournalStorage.class.getDeclaredField("LOCKS");
        field.setAccessible(true);
        var locks = (Map<?, ?>) field.get(null);
        Path root = directory.toRealPath();
        return locks.keySet().stream().filter(key -> ((Path) key).startsWith(root)).count();
    }
}
