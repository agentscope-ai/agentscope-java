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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link RemoteFilesystem} writes are protected by CAS:
 *
 * <ul>
 *   <li>{@code write} is atomic create-if-absent — duplicate writes fail without lost data;
 *   <li>{@code edit} survives concurrent edits by retrying on version mismatch (no lost updates).
 * </ul>
 *
 * <p>{@code uploadFiles} is intentionally last-write-wins (snapshot push semantics used by
 * session mirror, audit-log rotation, and {@code WorkspaceManager.writeUtf8WorkspaceRelative}).
 * This contract is covered by {@link #uploadFiles_overwritesExistingFile_lastWriteWins} and
 * {@link #uploadFiles_repeatedOnSamePath_returnsSuccessEachTime}.
 */
class RemoteFilesystemCASTest {

    private static final RuntimeContext CTX = RuntimeContext.empty();

    private RemoteFilesystem newFs(InMemoryStore store) {
        return new RemoteFilesystem(store, List.of("test"));
    }

    @Test
    void write_isAtomicCreateOnly() {
        InMemoryStore store = new InMemoryStore();
        RemoteFilesystem fs = newFs(store);

        WriteResult first = fs.write(CTX, "/notes.md", "hello");
        assertTrue(first.isSuccess());

        WriteResult second = fs.write(CTX, "/notes.md", "should not overwrite");
        assertFalse(second.isSuccess());
        assertTrue(second.error().contains("already exists"));

        // Original content preserved.
        var read = fs.read(CTX, "/notes.md", 0, 0);
        assertTrue(read.isSuccess());
        assertEquals("hello", read.fileData().content());
    }

    @Test
    void write_concurrentCreates_onlyOneWins() throws Exception {
        InMemoryStore store = new InMemoryStore();
        RemoteFilesystem fs = newFs(store);
        int writers = 32;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();

        for (int i = 0; i < writers; i++) {
            final int id = i;
            pool.submit(
                    () -> {
                        try {
                            start.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        WriteResult r = fs.write(CTX, "/race.md", "writer-" + id);
                        if (r.isSuccess()) {
                            succeeded.incrementAndGet();
                        }
                    });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(1, succeeded.get(), "exactly one concurrent create must win");
    }

    @Test
    void edit_concurrentAppends_noLostUpdates() throws Exception {
        InMemoryStore store = new InMemoryStore();
        RemoteFilesystem fs = newFs(store);
        // Seed with an empty marker the editors will append after.
        assertTrue(fs.write(CTX, "/log.md", "BEGIN\n").isSuccess());

        int writers = 8;
        int linesPerWriter = 25;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);

        for (int i = 0; i < writers; i++) {
            final int id = i;
            pool.submit(
                    () -> {
                        try {
                            start.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        for (int j = 0; j < linesPerWriter; j++) {
                            String line = "w" + id + "-l" + j + "\n";
                            // Append by replacing the trailing BEGIN sentinel with line+BEGIN.
                            // Bounded retry inside edit() handles version conflicts.
                            EditResult r =
                                    fs.edit(CTX, "/log.md", "BEGIN\n", line + "BEGIN\n", false);
                            // Under heavy contention some writers may exhaust retries; that is the
                            // documented failure mode. We only require no silent data loss.
                            if (!r.isSuccess()) {
                                // retry until success — test exercises end-to-end semantics
                                while (!r.isSuccess()) {
                                    r = fs.edit(CTX, "/log.md", "BEGIN\n", line + "BEGIN\n", false);
                                }
                            }
                        }
                    });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        var read = fs.read(CTX, "/log.md", 0, 0);
        assertTrue(read.isSuccess());
        String content = read.fileData().content();
        // Every line that any writer attempted should appear in the final content.
        for (int i = 0; i < writers; i++) {
            for (int j = 0; j < linesPerWriter; j++) {
                String marker = "w" + i + "-l" + j;
                assertTrue(
                        content.contains(marker),
                        () ->
                                "missing append "
                                        + marker
                                        + " — content lost under concurrency:\n"
                                        + content);
            }
        }
    }

    @Test
    void uploadFiles_overwritesExistingFile_lastWriteWins() {
        InMemoryStore store = new InMemoryStore();
        RemoteFilesystem fs = newFs(store);

        List<FileUploadResponse> first =
                fs.uploadFiles(
                        CTX, List.of(Map.entry("/foo.md", "v1".getBytes(StandardCharsets.UTF_8))));
        assertEquals(1, first.size());
        assertTrue(first.get(0).isSuccess(), () -> "first upload: " + first.get(0).error());

        List<FileUploadResponse> second =
                fs.uploadFiles(
                        CTX, List.of(Map.entry("/foo.md", "v2".getBytes(StandardCharsets.UTF_8))));
        assertEquals(1, second.size());
        assertTrue(
                second.get(0).isSuccess(),
                () ->
                        "second upload must succeed (last-write-wins); got error: "
                                + second.get(0).error());

        var read = fs.read(CTX, "/foo.md", 0, 0);
        assertTrue(read.isSuccess());
        assertEquals("v2", read.fileData().content(), "second upload must replace first");
    }

    @Test
    void uploadFiles_repeatedOnSamePath_returnsSuccessEachTime() {
        InMemoryStore store = new InMemoryStore();
        RemoteFilesystem fs = newFs(store);
        for (int i = 0; i < 5; i++) {
            String content = "iter-" + i;
            List<FileUploadResponse> resp =
                    fs.uploadFiles(
                            CTX,
                            List.of(
                                    Map.entry(
                                            "/log.md", content.getBytes(StandardCharsets.UTF_8))));
            assertEquals(1, resp.size());
            assertTrue(
                    resp.get(0).isSuccess(),
                    () -> "iteration " + content + " must succeed; got: " + resp.get(0).error());
        }
        var read = fs.read(CTX, "/log.md", 0, 0);
        assertTrue(read.isSuccess());
        assertEquals("iter-4", read.fileData().content());
    }

    @Test
    void downloadFiles_decodesWrappedBase64Content() {
        InMemoryStore store = new InMemoryStore();
        RemoteFilesystem fs = newFs(store);
        byte[] expected = new byte[] {1, 2, 3, 4, 5, 6};
        String wrapped = Base64.getMimeEncoder(4, new byte[] {'\n'}).encodeToString(expected);

        store.put(
                List.of("test"),
                "/bin/data.bin",
                Map.of(
                        "content", wrapped,
                        "encoding", "base64",
                        "created_at", "2026-01-01T00:00:00Z",
                        "modified_at", "2026-01-01T00:00:00Z"));

        var responses = fs.downloadFiles(CTX, List.of("/bin/data.bin"));
        assertEquals(1, responses.size());
        assertTrue(responses.get(0).isSuccess(), () -> "got: " + responses.get(0).error());
        assertArrayEquals(expected, responses.get(0).content());
    }

    @Test
    void edit_returnsConflictAfterExhaustingRetries() {
        // Simulate an adversarial store where every CAS fails.
        InMemoryStore alwaysConflict =
                new InMemoryStore() {
                    @Override
                    public boolean putIfVersion(
                            List<String> namespace,
                            String key,
                            Map<String, Object> value,
                            long expectedVersion) {
                        // Allow create-if-absent (used by seed write below) but reject all
                        // version-based CAS so edit() will exhaust its retries.
                        if (expectedVersion == 0L) {
                            return super.putIfVersion(namespace, key, value, expectedVersion);
                        }
                        return false;
                    }
                };
        RemoteFilesystem fs = newFs(alwaysConflict);
        assertTrue(fs.write(CTX, "/x.md", "abc").isSuccess());

        EditResult r = fs.edit(CTX, "/x.md", "a", "z", false);
        assertFalse(r.isSuccess());
        assertNotNull(r.error());
        assertTrue(r.error().contains("Edit conflict"), () -> "got: " + r.error());
    }
}
