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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.RoutedSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.memory.MemoryBackgroundTasks;
import io.agentscope.harness.agent.memory.MemoryConsolidator;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class MemoryMaintenanceArchiveTest {
    @TempDir Path workspace;

    private RuntimeContext context(String user) {
        return RuntimeContext.builder()
                .userId(user)
                .sessionId("synthetic-session")
                .put(
                        Clock.class,
                        Clock.fixed(Instant.parse("2026-11-19T12:00:00Z"), ZoneOffset.UTC))
                .build();
    }

    private void maintain(WorkspaceManager manager, RuntimeContext rc, int retention) {
        var middleware =
                new MemoryMaintenanceMiddleware(
                        manager,
                        null,
                        retention,
                        Duration.ZERO,
                        IsolationScope.USER,
                        (key, gap) -> true);
        middleware
                .onAgent(null, rc, new AgentInput(List.of()), input -> Flux.<AgentEvent>empty())
                .blockLast(Duration.ofSeconds(5));
        assertTrue(MemoryBackgroundTasks.awaitQuiescence(5, TimeUnit.SECONDS));
    }

    private void seed(Path dir, String name, String content) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name), content);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void archivesLedgerOnBothBackendsAndPreservesRetentionBoundary(boolean sandbox)
            throws Exception {
        AbstractFilesystem fs =
                sandbox ? new SandboxBackedFilesystem() : new LocalFilesystem(workspace, true, 10);
        try (var manager = new WorkspaceManager(workspace, fs, null)) {
            Path memory = workspace.resolve("memory");
            seed(memory, "2026-11-08.md", "expired synthetic note");
            seed(memory, "2026-11-09.md", "cutoff");
            seed(memory, "2026-11-19.md", "today");
            seed(memory, "notes.md", "not a daily ledger");
            seed(memory, ".2026-11-01.md", "hidden");
            seed(memory.resolve("archive"), "2026-11-01.md", "already archived");
            maintain(manager, context("alice"), 10);
            assertFalse(Files.exists(memory.resolve("2026-11-08.md")));
            assertEquals(
                    "expired synthetic note",
                    manager.readMemoryFileUtf8(context("alice"), "memory/archive/2026-11-08.md"));
            for (String name :
                    List.of("2026-11-09.md", "2026-11-19.md", "notes.md", ".2026-11-01.md")) {
                assertTrue(Files.exists(memory.resolve(name)), name);
            }
            assertEquals(
                    "already archived", Files.readString(memory.resolve("archive/2026-11-01.md")));
            maintain(manager, context("alice"), 10);
            assertEquals(
                    "expired synthetic note",
                    Files.readString(memory.resolve("archive/2026-11-08.md")));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void archivesOnlyTheCallingUsersDirectory(boolean sandbox) throws Exception {
        io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory namespace =
                rc -> List.of(rc.getUserId());
        AbstractFilesystem fs =
                sandbox
                        ? new SandboxBackedFilesystem()
                        : new LocalFilesystem(workspace, true, 10, namespace);
        try (var manager = new WorkspaceManager(workspace, fs, null, namespace)) {
            seed(workspace.resolve("alice/memory"), "2026-11-18.md", "alice synthetic note");
            seed(workspace.resolve("bob/memory"), "2026-11-18.md", "bob synthetic note");
            seed(workspace.resolve("memory"), "2026-11-18.md", "unscoped synthetic note");
            maintain(manager, context("alice"), 0);
            assertFalse(Files.exists(workspace.resolve("alice/memory/2026-11-18.md")));
            assertEquals(
                    "alice synthetic note",
                    manager.readMemoryFileUtf8(context("alice"), "memory/archive/2026-11-18.md"));
            assertTrue(Files.exists(workspace.resolve("bob/memory/2026-11-18.md")));
            assertFalse(Files.exists(workspace.resolve("bob/memory/archive")));
            assertTrue(Files.exists(workspace.resolve("memory/2026-11-18.md")));
        }
    }

    @Test
    void failedHostMovePreservesSourceAndTheExistingArchiveEntry() throws Exception {
        try (var manager = new WorkspaceManager(workspace, new SandboxBackedFilesystem(), null)) {
            seed(workspace.resolve("memory"), "2026-11-18.md", "preserve me");
            seed(workspace.resolve("memory"), "archive", "not a directory");
            maintain(manager, context("alice"), 0);
            assertEquals(
                    "preserve me", Files.readString(workspace.resolve("memory/2026-11-18.md")));
            assertEquals("not a directory", Files.readString(workspace.resolve("memory/archive")));
            assertFalse(
                    manager.moveMemoryFile(
                                    context("alice"),
                                    "memory/absent.md",
                                    "memory/archive/absent.md")
                            .isSuccess());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void recognizesAlreadyArchivedOnlyWhenTheSourceIsGoneAndTheArchiveIsInScope(boolean sandbox)
            throws Exception {
        io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory namespace =
                rc -> List.of(rc.getUserId());
        AbstractFilesystem fs =
                sandbox
                        ? new SandboxBackedFilesystem()
                        : new LocalFilesystem(workspace, true, 10, namespace);
        try (var manager = new WorkspaceManager(workspace, fs, null, namespace)) {
            var alice = context("alice");
            LocalDate day = LocalDate.of(2026, 11, 18);
            String name = day + ".md";
            Path memory = workspace.resolve("alice/memory");
            seed(workspace.resolve("bob/memory/archive"), name, "unrelated archive");
            seed(memory.resolve("archive/nested"), name, "nested ledger is not the archive");
            assertFalse(manager.isDailyMemoryFileArchived(alice, day));
            Path archive = memory.resolve("archive").resolve(name);
            Files.createDirectories(archive);
            assertFalse(manager.isDailyMemoryFileArchived(alice, day));
            Files.delete(archive);
            seed(memory.resolve("archive"), name, "archived by first replica");
            seed(memory, name, "source still here");
            assertFalse(manager.isDailyMemoryFileArchived(alice, day));
            Files.delete(memory.resolve(name));
            assertTrue(manager.isDailyMemoryFileArchived(alice, day));
            assertEquals("archived by first replica", Files.readString(archive));
        }
    }

    @Test
    void staleListingDoesNotRewriteArchiveOrBlockOtherLedgers() throws Exception {
        var fs =
                new LocalFilesystem(workspace, true, 10) {
                    @Override
                    public GlobResult glob(RuntimeContext rc, String pattern, String path) {
                        if (pattern.equals("*.md") && path.equals("memory")) {
                            return GlobResult.success(
                                    List.of(
                                            FileInfo.ofFile("exports/memory/2026-11-15.md", 1, 1L),
                                            FileInfo.ofFile("memory/2026-11-16.md", 1, 1L),
                                            FileInfo.ofFile("memory/2026-11-17.md", 1, 1L),
                                            FileInfo.ofFile("memory/2026-11-18.md", 1, 1L)));
                        }
                        return super.glob(rc, pattern, path);
                    }
                };
        Path memory = workspace.resolve("memory");
        seed(memory, "2026-11-15.md", "unexpected listing must not authorize this move");
        seed(memory.resolve("archive"), "2026-11-16.md", "archived by first replica");
        seed(memory, "2026-11-18.md", "next eligible ledger");
        try (var manager = new WorkspaceManager(workspace, fs, null)) {
            maintain(manager, context("alice"), 0);
            assertTrue(Files.exists(memory.resolve("2026-11-15.md")));
            assertEquals(
                    "archived by first replica",
                    Files.readString(memory.resolve("archive/2026-11-16.md")));
            assertFalse(Files.exists(memory.resolve("archive/2026-11-17.md")));
            assertEquals(
                    "next eligible ledger",
                    Files.readString(memory.resolve("archive/2026-11-18.md")));
        }
    }

    @Test
    void checksThePersistentRouteForAnAlreadyArchivedLedger() throws Exception {
        Path persistent = workspace.resolve("persistent");
        Path host = workspace.resolve("host");
        seed(host.resolve("memory/archive"), "2026-11-18.md", "not authoritative");
        Files.createDirectories(persistent);
        var routed =
                new RoutedSandboxFilesystem(
                        new SandboxBackedFilesystem(),
                        Map.of("memory/", new LocalFilesystem(persistent, true, 10)));
        try (var manager = new WorkspaceManager(host, routed, null)) {
            var rc = context("alice");
            LocalDate date = LocalDate.of(2026, 11, 18);
            assertFalse(manager.isDailyMemoryFileArchived(rc, date));
            seed(persistent.resolve("archive"), date + ".md", "authoritative archive");
            assertTrue(manager.isDailyMemoryFileArchived(rc, date));
        }
    }

    @Test
    void persistentMemoryRouteStillUsesItsNativeMove() throws Exception {
        Path persistent = workspace.resolve("persistent");
        Files.createDirectories(persistent);
        var store = new LocalFilesystem(persistent, true, 10);
        var routed =
                new RoutedSandboxFilesystem(
                        new SandboxBackedFilesystem(), Map.of("memory/", store));
        try (var manager = new WorkspaceManager(workspace.resolve("host"), routed, null)) {
            seed(persistent, "2026-11-18.md", "persistent note");
            maintain(manager, context("alice"), 0);
            assertFalse(Files.exists(persistent.resolve("2026-11-18.md")));
            assertEquals(
                    "persistent note",
                    manager.readMemoryFileUtf8(context("alice"), "memory/archive/2026-11-18.md"));
            assertFalse(Files.exists(workspace.resolve("host/memory/archive")));
        }
    }

    @Test
    void hostMoveKeepsNativeReplacementSemantics() throws Exception {
        try (var manager = new WorkspaceManager(workspace, new SandboxBackedFilesystem(), null)) {
            seed(workspace.resolve("memory"), "2026-11-18.md", "new synthetic note");
            seed(workspace.resolve("memory/archive"), "2026-11-18.md", "old synthetic note");
            WriteResult result =
                    manager.moveMemoryFile(
                            context("alice"),
                            "/memory/2026-11-18.md",
                            "/memory/archive/2026-11-18.md");
            assertTrue(result.isSuccess());
            assertEquals("memory/archive/2026-11-18.md", result.path());
            assertFalse(Files.exists(workspace.resolve("memory/2026-11-18.md")));
            assertEquals(
                    "new synthetic note",
                    Files.readString(workspace.resolve("memory/archive/2026-11-18.md")));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void perFileFailureDoesNotStopOtherLedgersAndZeroDaysKeepsToday(boolean sandbox)
            throws Exception {
        AbstractFilesystem fs =
                sandbox ? new SandboxBackedFilesystem() : new LocalFilesystem(workspace, true, 10);
        try (var manager = new WorkspaceManager(workspace, fs, null)) {
            Path memory = workspace.resolve("memory");
            seed(memory, "2026-11-17.md", "failed source");
            seed(memory, "2026-11-18.md", "successful source");
            seed(memory, "2026-11-19.md", "today");
            seed(
                    memory.resolve("archive/2026-11-17.md"),
                    "keep.txt",
                    "destination is a nonempty directory");
            maintain(manager, context("alice"), 0);
            assertEquals("failed source", Files.readString(memory.resolve("2026-11-17.md")));
            assertFalse(Files.exists(memory.resolve("2026-11-18.md")));
            assertEquals(
                    "successful source", Files.readString(memory.resolve("archive/2026-11-18.md")));
            assertEquals("today", Files.readString(memory.resolve("2026-11-19.md")));
            assertFalse(Files.exists(memory.resolve("archive/2026-11-19.md")));
        }
    }

    @Test
    void recursiveListingDoesNotReprocessArchiveOrNestedNames() {
        AbstractFilesystem fs = mock(AbstractFilesystem.class);
        var rc = context("alice");
        when(fs.glob(rc, "*.md", "memory"))
                .thenReturn(
                        GlobResult.success(
                                List.of(
                                        FileInfo.ofFile("/memory/archive/2026-11-18.md", 1, 1L),
                                        FileInfo.ofFile("/memory/notes/2026-11-18.md", 1, 1L),
                                        FileInfo.ofFile("/memory/2026-11-18.md", 1, 1L))));
        when(fs.move(rc, "memory/2026-11-18.md", "memory/archive/2026-11-18.md"))
                .thenReturn(WriteResult.ok("memory/archive/2026-11-18.md"));
        try (var manager = new WorkspaceManager(workspace, fs, null)) {
            maintain(manager, rc, 0);
            verify(fs).move(rc, "memory/2026-11-18.md", "memory/archive/2026-11-18.md");
            verify(fs, never())
                    .move(rc, "memory/notes/2026-11-18.md", "memory/archive/2026-11-18.md");
        }
    }

    @Test
    void nullFailedAndEmptyListingsStillAllowConsolidation() {
        AbstractFilesystem fs = mock(AbstractFilesystem.class);
        var rc = context("alice");
        when(fs.glob(rc, "*.md", "memory"))
                .thenReturn(
                        null,
                        GlobResult.fail("synthetic listing failure"),
                        GlobResult.success(List.of()));
        MemoryConsolidator consolidator = mock(MemoryConsolidator.class);
        when(consolidator.consolidate(rc)).thenReturn(Mono.empty());
        try (var manager = new WorkspaceManager(workspace, fs, null)) {
            for (int i = 0; i < 3; i++) {
                var middleware =
                        new MemoryMaintenanceMiddleware(
                                manager,
                                consolidator,
                                0,
                                Duration.ZERO,
                                IsolationScope.USER,
                                (key, gap) -> true);
                middleware
                        .onAgent(
                                null,
                                rc,
                                new AgentInput(List.of()),
                                input -> Flux.<AgentEvent>empty())
                        .blockLast(Duration.ofSeconds(5));
                assertTrue(MemoryBackgroundTasks.awaitQuiescence(5, TimeUnit.SECONDS));
            }
            verify(consolidator, org.mockito.Mockito.times(3)).consolidate(rc);
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "docker.it", matches = "true")
    void archivesHostLedgerAfterRealDockerSandboxRelease() throws Exception {
        var state = new io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxState();
        state.setSessionId("memory-4257-" + java.util.UUID.randomUUID());
        state.setContainerId("");
        state.setWorkspaceRoot("/workspace");
        state.setImage("alpine:latest");
        state.setContainerOwned(true);
        state.setWorkspaceSpec(new io.agentscope.harness.agent.sandbox.WorkspaceSpec());
        var sandbox = new io.agentscope.harness.agent.sandbox.impl.docker.DockerSandbox(state);
        var fs = new SandboxBackedFilesystem();
        try (var manager =
                new WorkspaceManager(workspace, fs, null, rc -> List.of(rc.getUserId()))) {
            sandbox.start();
            System.out.println("4257 Docker fixture container: " + state.getContainerId());
            fs.setSandbox(sandbox);
            byte[] marker =
                    "synthetic lifecycle marker".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            sandbox.uploadFile("/workspace/marker.txt", marker);
            org.junit.jupiter.api.Assertions.assertArrayEquals(
                    marker, sandbox.downloadFile("/workspace/marker.txt"));
            manager.writeMemoryFileUtf8(
                    context("alice"), "memory/2026-11-18.md", "alice daily note");
            manager.writeMemoryFileUtf8(context("bob"), "memory/2026-11-18.md", "bob daily note");
            sandbox.shutdown();
            fs.clearSandboxIfCurrent(sandbox);
            maintain(manager, context("alice"), 0);
            assertFalse(Files.exists(workspace.resolve("alice/memory/2026-11-18.md")));
            assertEquals(
                    "alice daily note",
                    manager.readMemoryFileUtf8(context("alice"), "memory/archive/2026-11-18.md"));
            assertEquals(
                    "bob daily note",
                    manager.readMemoryFileUtf8(context("bob"), "memory/2026-11-18.md"));
            assertFalse(Files.exists(workspace.resolve("bob/memory/archive")));
        } finally {
            fs.clearSandboxIfCurrent(sandbox);
            sandbox.shutdown();
        }
    }

    @Test
    void memoryMoveNormalizesPathsAndPreservesBackendFailureResult() {
        AbstractFilesystem fs = mock(AbstractFilesystem.class);
        var rc = context("alice");
        WriteResult failure = WriteResult.fail("synthetic backend failure");
        when(fs.move(rc, "memory/old.md", "memory/archive/old.md")).thenReturn(failure);
        try (var manager = new WorkspaceManager(workspace, fs, null)) {
            assertSame(
                    failure,
                    manager.moveMemoryFile(rc, "/memory/old.md", "/memory/archive/old.md"));
            verify(fs).move(rc, "memory/old.md", "memory/archive/old.md");
        }
    }
}
