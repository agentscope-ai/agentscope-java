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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

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
    @DisplayName("Should tolerate concurrent saves and reads of one key across instances")
    void saveSurvivesConcurrentWritesToSameKey(@TempDir Path tempDir) throws Exception {
        List<JsonFileAgentStateStore> stores = twoStoresOnSameRoot(tempDir);
        int threads = 8;
        int iterations = 50;
        int readers = 4;
        AtomicBoolean writing = new AtomicBoolean(true);
        ExecutorService pool = Executors.newFixedThreadPool(threads + readers);
        try {
            // Readers run while the writers replace the file: on Windows an unlocked read
            // handle makes the replace fail, and no reader may ever see a torn document.
            List<Future<Void>> readerResults = new ArrayList<>();
            for (int r = 0; r < readers; r++) {
                JsonFileAgentStateStore store = stores.get(r % stores.size());
                readerResults.add(
                        pool.submit(
                                () -> {
                                    while (writing.get()) {
                                        store.get(null, "session1", "agent_state", TestState.class)
                                                .ifPresent(
                                                        JsonFileAgentStateStoreTest
                                                                ::assertConsistentPair);
                                    }
                                    return null;
                                }));
            }
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int threadIndex = t;
                JsonFileAgentStateStore store = stores.get(t % stores.size());
                tasks.add(
                        () -> {
                            for (int i = 0; i < iterations; i++) {
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
            try {
                for (Future<Void> future : pool.invokeAll(tasks)) {
                    future.get();
                }
            } finally {
                writing.set(false);
            }
            for (Future<Void> reader : readerResults) {
                reader.get();
            }
        } finally {
            pool.shutdownNow();
        }

        // The winner is whichever thread moved last; it must be a complete, intact document.
        var loaded = stores.get(0).get(null, "session1", "agent_state", TestState.class);
        assertTrue(loaded.isPresent());
        assertConsistentPair(loaded.get());
        assertNoTmpFiles(tempDir);
    }

    @Test
    @DisplayName("Should keep the list file intact under concurrent read-modify-write saves")
    void saveListSurvivesConcurrentReadModifyWrite(@TempDir Path tempDir) throws Exception {
        List<JsonFileAgentStateStore> stores = twoStoresOnSameRoot(tempDir);
        int threads = 4;
        int iterations = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int threadIndex = t;
                JsonFileAgentStateStore store = stores.get(t % stores.size());
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
                stores.get(0).getList(null, "session1", "memory_messages", TestState.class);
        assertTrue(loaded.size() <= threads * iterations);
        assertTrue(loaded.size() > 0);
        loaded.forEach(JsonFileAgentStateStoreTest::assertConsistentPair);
        // No line may appear twice: a rewrite fully replaces the file and an append only adds
        // a snapshot's tail, so duplication means two writers interleaved on the same file.
        assertEquals(loaded.size(), loaded.stream().map(TestState::value).distinct().count());
        assertNoTmpFiles(tempDir);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sessionOperations")
    @DisplayName("Should hold session operations of another instance until a save finishes")
    void sessionOperationsWaitForAnInFlightSave(
            Consumer<JsonFileAgentStateStore> operation, @TempDir Path tempDir) throws Exception {
        List<JsonFileAgentStateStore> stores = twoStoresOnSameRoot(tempDir);
        JsonFileAgentStateStore holder = stores.get(0);
        TestState first = new TestState("m0", 0);
        holder.save(null, "session1", "agent_state", new TestState("a", 1));
        holder.save(null, "session1", "memory_messages", List.of(first));

        BlockingState blocking = new BlockingState();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Appended items are serialized under the session lock, so this save holds it
            // until the blocking item is released.
            Future<?> held =
                    pool.submit(
                            () ->
                                    holder.save(
                                            null,
                                            "session1",
                                            "memory_messages",
                                            List.<State>of(first, blocking)));
            assertTrue(blocking.entered.await(10, TimeUnit.SECONDS));

            Future<?> pending = pool.submit(() -> operation.accept(stores.get(1)));
            assertThrows(TimeoutException.class, () -> pending.get(200, TimeUnit.MILLISECONDS));

            blocking.release.countDown();
            held.get(10, TimeUnit.SECONDS);
            pending.get(10, TimeUnit.SECONDS);
        } finally {
            blocking.release.countDown();
            pool.shutdownNow();
        }
    }

    static Stream<Named<Consumer<JsonFileAgentStateStore>>> sessionOperations() {
        return Stream.of(
                Named.of("get", s -> s.get(null, "session1", "agent_state", TestState.class)),
                Named.of(
                        "getList",
                        s -> s.getList(null, "session1", "memory_messages", TestState.class)),
                Named.of(
                        "save",
                        s -> s.save(null, "session1", "agent_state", new TestState("b", 2))),
                Named.of(
                        "save list",
                        s ->
                                s.save(
                                        null,
                                        "session1",
                                        "memory_messages",
                                        List.of(new TestState("m1", 1)))),
                Named.of("delete key", s -> s.delete(null, "session1", "memory_messages")),
                Named.of("delete session", s -> s.delete(null, "session1")),
                Named.of("exists", s -> s.exists(null, "session1")),
                Named.of("clearAllSessions", s -> s.clearAllSessions().block()));
    }

    @Test
    @DisplayName("Should leave the list untouched when an appended item fails to serialize")
    void failedAppendLeavesListUntouched(@TempDir Path tempDir) throws IOException {
        JsonFileAgentStateStore store = new JsonFileAgentStateStore(tempDir);
        TestState a = new TestState("a", 1);
        TestState b = new TestState("b", 2);
        TestState c = new TestState("c", 3);
        store.save(null, "session1", "memory_messages", List.of(a, b));
        Path listFile = findListFile(tempDir);
        Path hashFile = listFile.resolveSibling("memory_messages.hash");
        byte[] listBefore = Files.readAllBytes(listFile);
        byte[] hashBefore = Files.readAllBytes(hashFile);

        // c serializes fine, so writing item by item would leave it behind on its own.
        assertThrows(
                RuntimeException.class,
                () ->
                        store.save(
                                null,
                                "session1",
                                "memory_messages",
                                List.<State>of(a, b, c, new UnserializableState())));

        assertArrayEquals(listBefore, Files.readAllBytes(listFile));
        assertArrayEquals(hashBefore, Files.readAllBytes(hashFile));
        assertEquals(
                List.of(a, b), store.getList(null, "session1", "memory_messages", TestState.class));
        assertNoTmpFiles(tempDir);

        store.save(null, "session1", "memory_messages", List.of(a, b, c));
        assertEquals(
                List.of(a, b, c),
                store.getList(null, "session1", "memory_messages", TestState.class));
    }

    @Test
    @DisplayName("Should leave the list untouched and no tmp file when a rewrite fails")
    void failedRewriteLeavesListUntouched(@TempDir Path tempDir) throws IOException {
        JsonFileAgentStateStore store = new JsonFileAgentStateStore(tempDir);
        List<TestState> saved = List.of(new TestState("a", 1), new TestState("b", 2));
        store.save(null, "session1", "memory_messages", saved);
        Path listFile = findListFile(tempDir);
        byte[] before = Files.readAllBytes(listFile);

        // The first item differs, so this rewrites the list and fails after the tmp file
        // already holds that item.
        assertThrows(
                RuntimeException.class,
                () ->
                        store.save(
                                null,
                                "session1",
                                "memory_messages",
                                List.<State>of(new TestState("x", 9), new UnserializableState())));

        assertArrayEquals(before, Files.readAllBytes(listFile));
        assertEquals(saved, store.getList(null, "session1", "memory_messages", TestState.class));
        assertNoTmpFiles(tempDir);
    }

    @Test
    @DisplayName("Should delete every file of a key and keep the other keys")
    void deleteKeyRemovesStateListAndHash(@TempDir Path tempDir) throws IOException {
        JsonFileAgentStateStore store = new JsonFileAgentStateStore(tempDir);
        TestState kept = new TestState("kept", 3);
        store.save(null, "session1", "k", new TestState("single", 1));
        store.save(null, "session1", "k", List.of(new TestState("item", 2)));
        store.save(null, "session1", "other", kept);

        store.delete(null, "session1", "k");

        assertEquals(Optional.empty(), store.get(null, "session1", "k", TestState.class));
        assertEquals(List.of(), store.getList(null, "session1", "k", TestState.class));
        assertEquals(Optional.of(kept), store.get(null, "session1", "other", TestState.class));
        try (Stream<Path> files = Files.list(tempDir.resolve("__anon__").resolve("session1"))) {
            assertEquals(
                    List.of("other.json"),
                    files.map(path -> path.getFileName().toString()).toList());
        }
    }

    // Two instances on one root, the second spelled differently: they must share session locks.
    private static List<JsonFileAgentStateStore> twoStoresOnSameRoot(Path root) {
        return List.of(
                new JsonFileAgentStateStore(root), new JsonFileAgentStateStore(root.resolve(".")));
    }

    // Both fields encode the same write, so a document torn between two writes fails the check.
    private static void assertConsistentPair(TestState state) {
        String[] parts = state.value().split("-");
        assertEquals(Integer.parseInt(parts[0].substring(1)), state.count() / 1000);
        assertEquals(Integer.parseInt(parts[1]), state.count() % 1000);
    }

    private static void assertNoTmpFiles(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            assertEquals(List.of(), paths.filter(p -> p.toString().endsWith(".tmp")).toList());
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

    /** Blocks while being serialized until released. */
    public static final class BlockingState implements State {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        public String getValue() throws InterruptedException {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return "blocked";
        }
    }

    /** Fails while being serialized. */
    public static final class UnserializableState implements State {
        public String getValue() {
            throw new IllegalStateException("not serializable");
        }
    }
}
