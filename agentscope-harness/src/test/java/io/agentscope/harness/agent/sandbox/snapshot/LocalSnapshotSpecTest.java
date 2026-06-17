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
package io.agentscope.harness.agent.sandbox.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalSnapshotSpecTest {

    @Test
    void findRestorable_returnsEmptyWhenNoTarFiles(@TempDir Path tempDir) throws Exception {
        LocalSnapshotSpec spec = new LocalSnapshotSpec(tempDir.toString());

        Optional<SandboxSnapshot> found = spec.findRestorable();

        assertTrue(found.isEmpty());
    }

    @Test
    void findRestorable_returnsEmptyWhenDirectoryDoesNotExist(@TempDir Path tempDir)
            throws Exception {
        LocalSnapshotSpec spec = new LocalSnapshotSpec(tempDir.resolve("nonexistent").toString());

        Optional<SandboxSnapshot> found = spec.findRestorable();

        assertTrue(found.isEmpty());
    }

    @Test
    void findRestorable_returnsSnapshotWhenTarFileExists(@TempDir Path tempDir) throws Exception {
        String sessionId = "test-session-123";
        Path tarFile = tempDir.resolve(sessionId + ".tar");
        try (OutputStream out = Files.newOutputStream(tarFile)) {
            out.write("dummy tar content".getBytes());
        }

        LocalSnapshotSpec spec = new LocalSnapshotSpec(tempDir.toString());

        Optional<SandboxSnapshot> found = spec.findRestorable();

        assertTrue(found.isPresent());
        assertEquals(sessionId, found.get().getId());
        assertEquals("local", found.get().getType());
        assertTrue(found.get().isRestorable());
    }

    @Test
    void findRestorable_returnsMostRecentlyModifiedSnapshot(@TempDir Path tempDir)
            throws Exception {
        // Create two tar files with different timestamps
        String olderSessionId = "older-session";
        Path olderTar = tempDir.resolve(olderSessionId + ".tar");
        try (OutputStream out = Files.newOutputStream(olderTar)) {
            out.write("older tar content".getBytes());
        }

        // Ensure different modification time
        Thread.sleep(50);

        String newerSessionId = "newer-session";
        Path newerTar = tempDir.resolve(newerSessionId + ".tar");
        try (OutputStream out = Files.newOutputStream(newerTar)) {
            out.write("newer tar content".getBytes());
        }

        LocalSnapshotSpec spec = new LocalSnapshotSpec(tempDir.toString());

        Optional<SandboxSnapshot> found = spec.findRestorable();

        assertTrue(found.isPresent());
        assertEquals(newerSessionId, found.get().getId());
    }

    @Test
    void findRestorable_ignoresNonTarFiles(@TempDir Path tempDir) throws Exception {
        Path txtFile = tempDir.resolve("readme.txt");
        try (OutputStream out = Files.newOutputStream(txtFile)) {
            out.write("not a tar file".getBytes());
        }

        LocalSnapshotSpec spec = new LocalSnapshotSpec(tempDir.toString());

        Optional<SandboxSnapshot> found = spec.findRestorable();

        assertTrue(found.isEmpty());
    }

    @Test
    void build_createsLocalSandboxSnapshotWithCorrectId() {
        LocalSnapshotSpec spec = new LocalSnapshotSpec("/tmp/snapshots");

        SandboxSnapshot snapshot = spec.build("my-session-id");

        assertEquals("my-session-id", snapshot.getId());
        assertEquals("local", snapshot.getType());
    }
}
