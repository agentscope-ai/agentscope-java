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
package io.agentscope.harness.agent.filesystem.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.util.ExceptionUtils;
import io.agentscope.harness.agent.filesystem.AtomicTextFile;
import io.agentscope.harness.agent.workspace.WorkspaceIndex;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

class LocalAtomicTextFileTest {
    @TempDir Path directory;

    @Test
    void missingEmptyAndIoFailureAreDifferentStates() throws Exception {
        AtomicTextFile file = new LocalAtomicTextFile(directory.resolve("nested/state.json"));
        assertNull(file.read());
        assertTrue(file.compareAndSet(null, ""));
        assertEquals("", file.read());
        assertFalse(file.compareAndSet(null, "overwrite"));
        assertTrue(file.compareAndSet("", "UTF-8 中文\n"));
        assertEquals("UTF-8 中文\n", file.read());
        assertThrows(UncheckedIOException.class, () -> new LocalAtomicTextFile(directory).read());
    }

    @Test
    void atomicWriteArtifactsStayOutOfListingsSearchAndTheWorkspaceIndex() throws Exception {
        Path target = directory.resolve("memory/entry.md");
        assertTrue(new LocalAtomicTextFile(target).compareAndSet(null, "- fact"));
        assertTrue(Files.exists(target.resolveSibling("entry.md.cas-lock")));
        Path temporary = target.resolveSibling(".memory-crash.tmp");
        Files.writeString(temporary, "- fact", StandardCharsets.UTF_8);
        Path ordinary = target.resolveSibling(".memory-not-temp.md");
        Files.writeString(ordinary, "- fact", StandardCharsets.UTF_8);
        LocalFilesystem filesystem = new LocalFilesystem(directory);
        RuntimeContext rc = RuntimeContext.empty();
        assertEquals(2, filesystem.ls(rc, "memory").entries().size());
        assertTrue(filesystem.glob(rc, "*.cas-lock", "memory").matches().isEmpty());
        assertTrue(filesystem.glob(rc, ".memory-*.tmp", "memory").matches().isEmpty());
        assertEquals(1, filesystem.glob(rc, ".memory-*.md", "memory").matches().size());
        assertTrue(filesystem.grep(rc, "fact", "memory", ".memory-*.tmp").matches().isEmpty());
        assertTrue(
                filesystem.grep(rc, "fact", "memory/.memory-crash.tmp", null).matches().isEmpty());
        try (WorkspaceIndex index = WorkspaceIndex.open(directory)) {
            index.rebuildFromDisk(directory);
            assertEquals(
                    java.util.List.of("memory/.memory-not-temp.md", "memory/entry.md"),
                    index.listByPrefix("memory/"));
            index.upsert("memory/entry.md.cas-lock", 0, null);
            assertFalse(index.exists("memory/entry.md.cas-lock"));
            index.upsert("memory/.memory-crash.tmp", 0, null);
            assertFalse(index.exists("memory/.memory-crash.tmp"));
        }
    }

    @Test
    void fileSymlinkKeepsItsTargetAndSharesConditionalUpdates() throws Exception {
        Path target = directory.resolve("target.md");
        Path link = directory.resolve("link.md");
        Files.writeString(target, "old", StandardCharsets.UTF_8);
        Files.createSymbolicLink(link, target.getFileName());
        AtomicTextFile throughLink = new LocalAtomicTextFile(link);
        AtomicTextFile direct = new LocalAtomicTextFile(target);

        assertTrue(throughLink.compareAndSet("old", "new"));
        assertTrue(Files.isSymbolicLink(link));
        assertEquals("new", direct.read());
        assertFalse(direct.compareAndSet("old", "stale"));
        assertTrue(direct.compareAndSet("new", "latest"));
        assertEquals("latest", throughLink.read());
        Files.delete(target);
        assertThrows(UncheckedIOException.class, () -> new LocalAtomicTextFile(link));
    }

    @Test
    void transientReplacementDenialRetriesWithoutChangingAtomicity() throws Exception {
        Path target = directory.toRealPath().resolve("memory.md");
        Files.writeString(target, "old", StandardCharsets.UTF_8);
        AtomicTextFile file = new LocalAtomicTextFile(target);
        try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(
                            () ->
                                    Files.move(
                                            any(Path.class),
                                            eq(target),
                                            eq(StandardCopyOption.ATOMIC_MOVE),
                                            eq(StandardCopyOption.REPLACE_EXISTING)))
                    .thenThrow(new AccessDeniedException(target.toString()))
                    .thenCallRealMethod();
            assertTrue(file.compareAndSet("old", "new"));
        }
        assertEquals("new", file.read());
    }

    @Test
    void persistentReplacementDenialPreservesTheOriginalFile() throws Exception {
        Path target = directory.toRealPath().resolve("memory.md");
        Files.writeString(target, "old", StandardCharsets.UTF_8);
        AtomicTextFile file = new LocalAtomicTextFile(target);
        try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(
                            () ->
                                    Files.move(
                                            any(Path.class),
                                            eq(target),
                                            eq(StandardCopyOption.ATOMIC_MOVE),
                                            eq(StandardCopyOption.REPLACE_EXISTING)))
                    .thenThrow(new AccessDeniedException(target.toString()));
            assertThrows(UncheckedIOException.class, () -> file.compareAndSet("old", "new"));
        }
        assertEquals("old", file.read());
        try (var files = Files.list(directory)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void interruptedReplacementRetryPreservesCancellationAndOriginalContent() throws Exception {
        Path target = directory.toRealPath().resolve("memory.md");
        Files.writeString(target, "old", StandardCharsets.UTF_8);
        AtomicTextFile file = new LocalAtomicTextFile(target);
        try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(
                            () ->
                                    Files.move(
                                            any(Path.class),
                                            eq(target),
                                            eq(StandardCopyOption.ATOMIC_MOVE),
                                            eq(StandardCopyOption.REPLACE_EXISTING)))
                    .thenAnswer(
                            invocation -> {
                                Thread.currentThread().interrupt();
                                throw new AccessDeniedException(target.toString());
                            });
            UncheckedIOException error =
                    assertThrows(
                            UncheckedIOException.class, () -> file.compareAndSet("old", "new"));
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(ExceptionUtils.containsInterruptedException(error));
        } finally {
            Thread.interrupted();
        }
        assertEquals("old", file.read());
    }

    @Test
    void independentJvmsCannotOverwriteOneAnothersConditionalUpdates() throws Exception {
        Path target = directory.resolve("counter.txt");
        Files.writeString(target, "0", StandardCharsets.UTF_8);
        Path gate = directory.resolve("gate");
        Process first =
                writer(
                        target,
                        directory.resolve("ready-1"),
                        gate,
                        directory.resolve("writer-1.log"));
        Process second =
                writer(
                        target,
                        directory.resolve("ready-2"),
                        gate,
                        directory.resolve("writer-2.log"));
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while ((!Files.exists(directory.resolve("ready-1"))
                            || !Files.exists(directory.resolve("ready-2")))
                    && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(
                    Files.exists(directory.resolve("ready-1"))
                            && Files.exists(directory.resolve("ready-2")));
            Files.writeString(gate, "go", StandardCharsets.UTF_8);
            assertTrue(first.waitFor(30, TimeUnit.SECONDS));
            assertTrue(second.waitFor(30, TimeUnit.SECONDS));
            assertEquals(0, first.exitValue(), Files.readString(directory.resolve("writer-1.log")));
            assertEquals(
                    0, second.exitValue(), Files.readString(directory.resolve("writer-2.log")));
            assertEquals("40", Files.readString(target, StandardCharsets.UTF_8));
        } finally {
            first.destroyForcibly();
            second.destroyForcibly();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void transientLockDenialRetriesOpenAndLockWithoutLosingConditionality(boolean duringLock)
            throws Exception {
        Path target = directory.toRealPath().resolve("state.json");
        Path lock = target.resolveSibling("state.json.cas-lock");
        Files.writeString(target, "old", StandardCharsets.UTF_8);
        AtomicTextFile file = new LocalAtomicTextFile(target);
        FileChannel denied = mock(FileChannel.class);
        when(denied.lock()).thenThrow(new AccessDeniedException(lock.toString()));
        AtomicInteger opens = new AtomicInteger();
        try (MockedStatic<FileChannel> channels =
                mockStatic(
                        FileChannel.class,
                        invocation -> {
                            if (invocation.getArguments().length == 2
                                    && lock.equals(invocation.getArgument(0))
                                    && opens.incrementAndGet() == 1) {
                                if (duringLock) return denied;
                                throw new AccessDeniedException(lock.toString());
                            }
                            return invocation.callRealMethod();
                        })) {
            assertFalse(file.compareAndSet("stale", "lost update"));
            assertEquals(2, opens.get());
            if (duringLock) verify(denied).close();
        }
        assertEquals("old", file.read());
        assertTrue(file.compareAndSet("old", "new"));
        assertEquals("new", file.read());
    }

    @Test
    void persistentLockOpenDenialStopsAfterBoundedRetries() throws Exception {
        Path target = directory.toRealPath().resolve("state.json");
        Path lock = target.resolveSibling("state.json.cas-lock");
        Files.writeString(target, "old", StandardCharsets.UTF_8);
        AtomicTextFile file = new LocalAtomicTextFile(target);
        AtomicInteger opens = new AtomicInteger();
        try (MockedStatic<FileChannel> channels =
                mockStatic(
                        FileChannel.class,
                        invocation -> {
                            if (invocation.getArguments().length == 2
                                    && lock.equals(invocation.getArgument(0))) {
                                opens.incrementAndGet();
                                throw new AccessDeniedException(lock.toString());
                            }
                            return invocation.callRealMethod();
                        })) {
            assertThrows(UncheckedIOException.class, () -> file.compareAndSet("old", "new"));
            assertEquals(7, opens.get());
        }
        assertEquals("old", file.read());
    }

    @Test
    void interruptedLockRetryClosesChannelAndPreservesCancellation() throws Exception {
        Path target = directory.toRealPath().resolve("state.json");
        Path lock = target.resolveSibling("state.json.cas-lock");
        Files.writeString(target, "old", StandardCharsets.UTF_8);
        AtomicTextFile file = new LocalAtomicTextFile(target);
        FileChannel denied = mock(FileChannel.class);
        when(denied.lock())
                .thenAnswer(
                        invocation -> {
                            Thread.currentThread().interrupt();
                            throw new AccessDeniedException(lock.toString());
                        });
        AtomicInteger opens = new AtomicInteger();
        try (MockedStatic<FileChannel> channels =
                mockStatic(
                        FileChannel.class,
                        invocation -> {
                            if (invocation.getArguments().length == 2
                                    && lock.equals(invocation.getArgument(0))) {
                                opens.incrementAndGet();
                                return denied;
                            }
                            return invocation.callRealMethod();
                        })) {
            UncheckedIOException error =
                    assertThrows(
                            UncheckedIOException.class, () -> file.compareAndSet("old", "new"));
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(ExceptionUtils.containsInterruptedException(error));
            verify(denied).close();
            assertEquals(1, opens.get());
        } finally {
            Thread.interrupted();
        }
        assertEquals("old", file.read());
    }

    @Test
    void otherLockFailureClosesChannelWithoutRetrying() throws Exception {
        Path target = directory.toRealPath().resolve("state.json");
        Path lock = target.resolveSibling("state.json.cas-lock");
        Files.writeString(target, "old", StandardCharsets.UTF_8);
        AtomicTextFile file = new LocalAtomicTextFile(target);
        FileChannel failed = mock(FileChannel.class);
        IOException failure = new IOException("not a sharing conflict");
        when(failed.lock()).thenThrow(failure);
        AtomicInteger opens = new AtomicInteger();
        try (MockedStatic<FileChannel> channels =
                mockStatic(
                        FileChannel.class,
                        invocation -> {
                            if (invocation.getArguments().length == 2
                                    && lock.equals(invocation.getArgument(0))) {
                                opens.incrementAndGet();
                                return failed;
                            }
                            return invocation.callRealMethod();
                        })) {
            UncheckedIOException error =
                    assertThrows(
                            UncheckedIOException.class, () -> file.compareAndSet("old", "new"));
            assertEquals(failure, error.getCause());
            verify(failed).close();
            assertEquals(1, opens.get());
        }
        assertEquals("old", file.read());
    }

    private Process writer(Path target, Path ready, Path gate, Path log) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classPath =
                System.getProperty(
                        "surefire.test.class.path", System.getProperty("java.class.path"));
        return new ProcessBuilder(
                        java,
                        "-cp",
                        classPath,
                        Writer.class.getName(),
                        target.toString(),
                        ready.toString(),
                        gate.toString())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
    }

    /** Separate processes deliberately start with the same expected content. */
    public static class Writer {
        public static void main(String[] args) throws Exception {
            AtomicTextFile file = new LocalAtomicTextFile(Path.of(args[0]));
            String initial = file.read();
            Files.writeString(Path.of(args[1]), "ready", StandardCharsets.UTF_8);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!Files.exists(Path.of(args[2]))) {
                if (System.nanoTime() > deadline) throw new IllegalStateException("gate timeout");
                Thread.sleep(10);
            }
            for (int count = 0; count < 20; ) {
                String before = count == 0 && initial != null ? initial : file.read();
                initial = null;
                if (file.compareAndSet(before, Integer.toString(Integer.parseInt(before) + 1)))
                    count++;
            }
        }
    }
}
