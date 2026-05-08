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
package io.agentscope.harness.agent.filesystem;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.store.InMemoryStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies delete / move / exists on all {@link AbstractFilesystem} implementations.
 *
 * <p>LocalFilesystem is tested in non-virtual mode; paths are workspace-relative (no leading '/').
 * RemoteFilesystem uses leading-slash keys matching its internal convention.
 */
class FilesystemDeleteMoveExistsTest {

    private static final RuntimeContext RT = RuntimeContext.empty();

    // ================================================================
    // LocalFilesystem — non-virtual mode, relative paths (no leading '/')
    // ================================================================

    @Test
    void local_exists_true(@TempDir Path tmp) throws Exception {
        LocalFilesystem fs = new LocalFilesystem(tmp);
        Files.writeString(tmp.resolve("file.txt"), "hello");

        assertTrue(fs.exists(RT, "file.txt"));
    }

    @Test
    void local_exists_false(@TempDir Path tmp) {
        LocalFilesystem fs = new LocalFilesystem(tmp);
        assertFalse(fs.exists(RT, "nonexistent.txt"));
    }

    @Test
    void local_delete_file(@TempDir Path tmp) throws Exception {
        LocalFilesystem fs = new LocalFilesystem(tmp);
        Path f = Files.writeString(tmp.resolve("del.txt"), "data");

        WriteResult result = fs.delete(RT, "del.txt");
        assertTrue(result.isSuccess());
        assertFalse(Files.exists(f));
    }

    @Test
    void local_delete_idempotent(@TempDir Path tmp) {
        LocalFilesystem fs = new LocalFilesystem(tmp);
        WriteResult result = fs.delete(RT, "ghost.txt");
        assertTrue(result.isSuccess(), "deleting nonexistent should succeed (idempotent)");
    }

    @Test
    void local_delete_directory_recursive(@TempDir Path tmp) throws Exception {
        LocalFilesystem fs = new LocalFilesystem(tmp);
        Path dir = tmp.resolve("subdir");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("a.txt"), "a");
        Files.writeString(dir.resolve("b.txt"), "b");

        WriteResult result = fs.delete(RT, "subdir");
        assertTrue(result.isSuccess());
        assertFalse(Files.exists(dir));
    }

    @Test
    void local_move_file(@TempDir Path tmp) throws Exception {
        LocalFilesystem fs = new LocalFilesystem(tmp);
        Files.writeString(tmp.resolve("src.txt"), "content");

        WriteResult result = fs.move(RT, "src.txt", "dst.txt");
        assertTrue(result.isSuccess());
        assertFalse(Files.exists(tmp.resolve("src.txt")));
        assertTrue(Files.exists(tmp.resolve("dst.txt")));
    }

    @Test
    void local_move_missingSource(@TempDir Path tmp) {
        LocalFilesystem fs = new LocalFilesystem(tmp);
        WriteResult result = fs.move(RT, "missing.txt", "dst.txt");
        assertFalse(result.isSuccess());
    }

    // ================================================================
    // RemoteFilesystem — keys follow leading-slash convention
    // ================================================================

    private static InMemoryStore storeWith(String path, String content) {
        InMemoryStore s = new InMemoryStore();
        s.put(List.of("ns"), path, Map.of("content", content, "encoding", "utf-8"));
        return s;
    }

    @Test
    void store_exists_true() {
        InMemoryStore s = storeWith("/file.txt", "hello");
        RemoteFilesystem fs = new RemoteFilesystem(s, List.of("ns"));

        assertTrue(fs.exists(RT, "/file.txt"));
    }

    @Test
    void store_exists_false() {
        RemoteFilesystem fs = new RemoteFilesystem(new InMemoryStore(), List.of("ns"));
        assertFalse(fs.exists(RT, "/nope.txt"));
    }

    @Test
    void store_delete_file() {
        InMemoryStore s = storeWith("/file.txt", "hello");
        RemoteFilesystem fs = new RemoteFilesystem(s, List.of("ns"));

        WriteResult result = fs.delete(RT, "/file.txt");
        assertTrue(result.isSuccess());
        assertNull(s.get(List.of("ns"), "/file.txt"));
    }

    @Test
    void store_delete_idempotent() {
        RemoteFilesystem fs = new RemoteFilesystem(new InMemoryStore(), List.of("ns"));
        WriteResult result = fs.delete(RT, "/ghost.txt");
        assertTrue(result.isSuccess());
    }

    @Test
    void store_move_file() {
        InMemoryStore s = storeWith("/src.txt", "data");
        RemoteFilesystem fs = new RemoteFilesystem(s, List.of("ns"));

        WriteResult result = fs.move(RT, "/src.txt", "/dst.txt");
        assertTrue(result.isSuccess());
        assertNull(s.get(List.of("ns"), "/src.txt"));
        assertNotNull(s.get(List.of("ns"), "/dst.txt"));
    }

    @Test
    void store_move_missingSource() {
        RemoteFilesystem fs = new RemoteFilesystem(new InMemoryStore(), List.of("ns"));
        WriteResult result = fs.move(RT, "/missing.txt", "/dst.txt");
        assertFalse(result.isSuccess());
    }

    // ================================================================
    // CompositeFilesystem — routed operations
    // ================================================================

    @Test
    void composite_exists_routedToStore(@TempDir Path tmp) {
        InMemoryStore s = storeWith("/MEMORY.md", "mem");
        RemoteFilesystem storeFsys = new RemoteFilesystem(s, List.of("ns"));
        LocalFilesystem local = new LocalFilesystem(tmp);

        CompositeFilesystem fs = new CompositeFilesystem(local, Map.of("MEMORY.md", storeFsys));

        assertTrue(fs.exists(RT, "MEMORY.md"));
        assertFalse(fs.exists(RT, "notExist.txt"));
    }

    @Test
    void composite_delete_routedToStore(@TempDir Path tmp) {
        InMemoryStore s = storeWith("/MEMORY.md", "mem");
        RemoteFilesystem storeFsys = new RemoteFilesystem(s, List.of("ns"));
        LocalFilesystem local = new LocalFilesystem(tmp);

        CompositeFilesystem fs = new CompositeFilesystem(local, Map.of("MEMORY.md", storeFsys));

        WriteResult result = fs.delete(RT, "MEMORY.md");
        assertTrue(result.isSuccess());
        assertNull(s.get(List.of("ns"), "/MEMORY.md"));
    }

    // ================================================================
    // CompositeFilesystem — cross-backend move (store → local)
    // ================================================================

    @Test
    void composite_move_crossBackend_storeToLocal(@TempDir Path tmp) throws Exception {
        InMemoryStore s = new InMemoryStore();
        List<String> ns = List.of("ns");
        s.put(ns, "/2025-01-01.md", Map.of("content", "diary", "encoding", "utf-8"));
        RemoteFilesystem storeFsys = new RemoteFilesystem(s, ns);
        LocalFilesystem local = new LocalFilesystem(tmp);

        CompositeFilesystem fs = new CompositeFilesystem(local, Map.of("memory/", storeFsys));

        // Move from store-routed path to a local-only path
        WriteResult result = fs.move(RT, "memory/2025-01-01.md", "archive/2025-01-01.md");
        assertTrue(result.isSuccess(), "cross-backend move should succeed");
        // Source removed from store
        assertNull(s.get(ns, "/2025-01-01.md"), "source should be deleted from store");
        // Destination written to local disk
        assertTrue(
                Files.exists(tmp.resolve("archive/2025-01-01.md")),
                "destination should appear on local disk");
    }
}
