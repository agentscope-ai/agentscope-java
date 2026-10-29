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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Discovery on the file-backed POSIX branch. Windows runs the SQLite backend instead. */
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
}
