/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for JsonFileAgentStateStore. */
@DisplayName("JsonFileAgentStateStore Tests")
class JsonFileAgentStateStoreTest {

    @Test
    @DisplayName("Should sanitize invalid Unicode when saving single state")
    void saveSanitizesInvalidUnicode(@TempDir Path tempDir) {
        JsonFileAgentStateStore store = new JsonFileAgentStateStore(tempDir);

        store.save(null, "session1", "agent_state", new TestState("before \uD800 after", 1));

        var loaded = store.get(null, "session1", "agent_state", TestState.class);
        assertTrue(loaded.isPresent());
        assertEquals("before \uFFFD after", loaded.get().value());
    }

    @Test
    @DisplayName("Should sanitize invalid Unicode when saving list state")
    void saveListSanitizesInvalidUnicode(@TempDir Path tempDir) {
        JsonFileAgentStateStore store = new JsonFileAgentStateStore(tempDir);

        store.save(
                null,
                "session1",
                "memory_messages",
                List.of(new TestState("before \uD800 after", 1)));

        List<TestState> loaded =
                store.getList(null, "session1", "memory_messages", TestState.class);
        assertEquals(1, loaded.size());
        assertEquals("before \uFFFD after", loaded.get(0).value());
    }

    @Test
    @DisplayName("Should sanitize invalid Unicode when rewriting list state")
    void rewriteListSanitizesInvalidUnicode(@TempDir Path tempDir) {
        JsonFileAgentStateStore store = new JsonFileAgentStateStore(tempDir);

        store.save(
                null,
                "session1",
                "memory_messages",
                List.of(new TestState("ok", 1), new TestState("ok2", 2)));

        // Shrink list to force rewriteEntireList(...)
        store.save(
                null,
                "session1",
                "memory_messages",
                List.of(new TestState("before \uD800 after", 1)));

        List<TestState> loaded =
                store.getList(null, "session1", "memory_messages", TestState.class);
        assertEquals(1, loaded.size());
        assertEquals("before \uFFFD after", loaded.get(0).value());
    }

    @Test
    @DisplayName("Should persist an edit to a list element the old sampler never looked at")
    void saveListPersistsEditOfAnUnsampledElement(@TempDir Path tempDir) {
        JsonFileAgentStateStore store = new JsonFileAgentStateStore(tempDir);
        List<TestState> saved = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            saved.add(new TestState("v" + i, i));
        }
        store.save(null, "session1", "memory_messages", saved);

        // A caller that keeps its own list and replaces one entry produces exactly this shape.
        // With a sampled hash the edit goes unnoticed, so nothing is written and the caller's
        // change silently disappears.
        List<TestState> edited = new ArrayList<>(saved);
        edited.set(3, new TestState("EDITED", 3));
        store.save(null, "session1", "memory_messages", edited);

        List<TestState> loaded =
                store.getList(null, "session1", "memory_messages", TestState.class);
        assertEquals(10, loaded.size());
        assertEquals("EDITED", loaded.get(3).value());
    }

    @Test
    @DisplayName("Should leave the list file alone when rebuilt messages carry the same content")
    void saveListSkipsRewriteForRebuiltEqualMessages(@TempDir Path tempDir) throws IOException {
        JsonFileAgentStateStore store = new JsonFileAgentStateStore(tempDir);
        store.save(null, "session1", "memory_messages", conversation("hi there"));

        Path listFile = findListFile(tempDir);
        FileTime untouched = FileTime.fromMillis(1_600_000_000_000L);
        Files.setLastModifiedTime(listFile, untouched);

        // Reading a session back and continuing it rebuilds every message object. The content is
        // unchanged, so nothing has to be rewritten.
        store.save(null, "session1", "memory_messages", conversation("hi there"));

        assertEquals(untouched, Files.getLastModifiedTime(listFile));
    }

    @Test
    @DisplayName("Should rewrite the list file when a rebuilt message differs")
    void saveListRewritesWhenARebuiltMessageDiffers(@TempDir Path tempDir) throws IOException {
        JsonFileAgentStateStore store = new JsonFileAgentStateStore(tempDir);
        store.save(null, "session1", "memory_messages", conversation("hi there"));

        Path listFile = findListFile(tempDir);
        FileTime untouched = FileTime.fromMillis(1_600_000_000_000L);
        Files.setLastModifiedTime(listFile, untouched);

        store.save(null, "session1", "memory_messages", conversation("hi there, again"));

        assertNotEquals(untouched, Files.getLastModifiedTime(listFile));
    }

    @Test
    @DisplayName("Should tolerate concurrent saves of the same state key")
    void saveSurvivesConcurrentWritesToSameKey(@TempDir Path tempDir) throws Exception {
        JsonFileAgentStateStore store = new JsonFileAgentStateStore(tempDir);
        int threads = 8;
        int iterations = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int threadIndex = t;
                tasks.add(
                        () -> {
                            for (int i = 0; i < iterations; i++) {
                                // Both fields encode the writer so a torn document mixing
                                // fields from two writes cannot pass the pair check below.
                                store.save(
                                        null,
                                        "session1",
                                        "agent_state",
                                        new TestState(
                                                "t" + threadIndex + "-" + i,
                                                threadIndex * 1000 + i));
                            }
                            return null;
                        });
            }
            for (Future<Void> future : pool.invokeAll(tasks)) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        var loaded = store.get(null, "session1", "agent_state", TestState.class);
        assertTrue(loaded.isPresent());
        // The winner is whichever thread moved last; it must be a complete, intact document
        // whose two fields still describe the same write.
        String[] parts = loaded.get().value().split("-");
        int writer = Integer.parseInt(parts[0].substring(1));
        int iteration = Integer.parseInt(parts[1]);
        assertEquals(writer, loaded.get().count() / 1000);
        assertEquals(iteration, loaded.get().count() % 1000);
        assertTrue(writer < threads);
        assertTrue(iteration < iterations);
        // No orphaned tmp files may survive a successful save.
        try (Stream<Path> paths = Files.walk(tempDir)) {
            assertEquals(0, paths.filter(p -> p.toString().endsWith(".tmp")).count());
        }
    }

    @Test
    @DisplayName("Should keep the list file intact under concurrent read-modify-write saves")
    void saveListSurvivesConcurrentReadModifyWrite(@TempDir Path tempDir) throws Exception {
        JsonFileAgentStateStore store = new JsonFileAgentStateStore(tempDir);
        int threads = 4;
        int iterations = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int threadIndex = t;
                tasks.add(
                        () -> {
                            for (int i = 0; i < iterations; i++) {
                                List<TestState> current =
                                        new ArrayList<>(
                                                store.getList(
                                                        null,
                                                        "session1",
                                                        "memory_messages",
                                                        TestState.class));
                                current.add(
                                        new TestState(
                                                "t" + threadIndex + "-" + i,
                                                threadIndex * 1000 + i));
                                store.save(null, "session1", "memory_messages", current);
                            }
                            return null;
                        });
            }
            for (Future<Void> future : pool.invokeAll(tasks)) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        // Last-writer-wins may drop entries when two writers build from the same snapshot —
        // that is the caller's CAS concern — but the file itself must stay parseable, with no
        // duplicated or torn lines, and every surviving pair must describe one write.
        List<TestState> loaded =
                store.getList(null, "session1", "memory_messages", TestState.class);
        assertTrue(loaded.size() <= threads * iterations);
        assertTrue(loaded.size() > 0);
        for (TestState item : loaded) {
            String[] parts = item.value().split("-");
            assertEquals(Integer.parseInt(parts[0].substring(1)), item.count() / 1000);
            assertEquals(Integer.parseInt(parts[1]), item.count() % 1000);
        }
        // No line may appear twice: a rewrite fully replaces the file and an append only adds
        // a snapshot's tail, so duplication means two writers interleaved on the same file.
        assertEquals(loaded.size(), loaded.stream().map(TestState::value).distinct().count());
        try (Stream<Path> paths = Files.walk(tempDir)) {
            assertEquals(0, paths.filter(p -> p.toString().endsWith(".tmp")).count());
        }
    }

    private static List<Msg> conversation(String reply) {
        return List.of(
                message("m-user-1", MsgRole.USER, "hello"),
                message("m-assistant-1", MsgRole.ASSISTANT, reply));
    }

    private static Msg message(String id, MsgRole role, String text) {
        return Msg.builder()
                .id(id)
                .timestamp("2026-05-08 14:00:00.000")
                .role(role)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static Path findListFile(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(path -> path.toString().endsWith(".jsonl"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no list file was written"));
        }
    }

    public record TestState(String value, int count) implements State {}
}
