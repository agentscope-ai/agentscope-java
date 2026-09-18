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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.workspace.WorkspaceIndex;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RemoteFilesystemSpecTest {

    private static final RuntimeContext RT = RuntimeContext.empty();

    @TempDir Path workspace;

    @Test
    void routesSharedPathsToStoreAndOthersToLocal() throws Exception {
        InMemoryStore store = new InMemoryStore();
        NamespaceFactory localNs = rc -> List.of("local-user");

        AbstractFilesystem fs =
                new RemoteFilesystemSpec(store)
                        .anonymousUserId("anon")
                        .toFilesystem(workspace, "agent-a", localNs);

        fs.uploadFiles(
                RT,
                List.of(
                        java.util.Map.entry(
                                "MEMORY.md", "hello".getBytes(StandardCharsets.UTF_8))));
        assertNotNull(
                store.get(List.of("agents", "agent-a", "users", "anon", "root"), "/MEMORY.md"));

        fs.uploadFiles(
                RT,
                List.of(
                        java.util.Map.entry(
                                "docs/notes.md", "local".getBytes(StandardCharsets.UTF_8))));
        assertTrue(Files.isRegularFile(workspace.resolve("local-user/docs/notes.md")));
    }

    @Test
    void resolvesNamespaceByRuntimeUserId() {
        InMemoryStore store = new InMemoryStore();

        AbstractFilesystem fs =
                new RemoteFilesystemSpec(store).toFilesystem(workspace, "agent-a", rc -> List.of());

        RuntimeContext rcUser1 = RuntimeContext.builder().userId("user-1").sessionId(null).build();
        fs.uploadFiles(
                rcUser1,
                List.of(java.util.Map.entry("MEMORY.md", "v1".getBytes(StandardCharsets.UTF_8))));
        assertNotNull(
                store.get(List.of("agents", "agent-a", "users", "user-1", "root"), "/MEMORY.md"));
    }

    /**
     * Integration coverage for the memory route. This is a compatibility test, not a reproducer
     * for the empty-result fast path: real indexes contain workspace-relative file paths and
     * the composite routes memory queries to "/", which is absent from those indexes.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void memoryRouteDiscoversSiblingWritesWithRealIndexes(boolean populatedTemplate)
            throws Exception {
        InMemoryStore store = new InMemoryStore();
        Path nodeA = Files.createDirectories(workspace.resolve("node-a"));
        Path nodeB = Files.createDirectories(workspace.resolve("node-b"));
        Files.createDirectories(nodeB.resolve("memory"));
        if (populatedTemplate) {
            Files.writeString(nodeB.resolve("memory/template.md"), "local template");
        }
        RuntimeContext writer = RuntimeContext.builder().userId("alice").sessionId("first").build();
        RuntimeContext reader =
                RuntimeContext.builder().userId("alice").sessionId("second").build();
        RuntimeContext otherUser =
                RuntimeContext.builder().userId("bob").sessionId("third").build();
        try (WorkspaceIndex indexA = WorkspaceIndex.open(nodeA);
                WorkspaceIndex indexB = WorkspaceIndex.open(nodeB)) {
            indexB.rebuildFromDisk(nodeB);
            assertEquals(populatedTemplate, indexB.hasPrefix("memory/"));
            assertFalse(indexB.hasPrefix("/"));
            AbstractFilesystem fsA =
                    new RemoteFilesystemSpec(store)
                            .workspaceIndex(indexA)
                            .toFilesystem(nodeA, "agent-a", rc -> List.of("local"));
            AbstractFilesystem fsB =
                    new RemoteFilesystemSpec(store)
                            .workspaceIndex(indexB)
                            .toFilesystem(nodeB, "agent-a", rc -> List.of("local"));
            assertTrue(fsA.write(writer, "memory/notes.md", "remote note").isSuccess());
            assertTrue(fsA.write(writer, "memory/sub/nested.md", "nested note").isSuccess());
            assertFalse(indexB.exists("memory/notes.md"));
            assertFalse(Files.exists(nodeB.resolve("memory/notes.md")));

            List<String> expectedFiles =
                    populatedTemplate
                            ? List.of(
                                    "memory/notes.md", "memory/sub/nested.md", "memory/template.md")
                            : List.of("memory/notes.md", "memory/sub/nested.md");
            List<String> expectedEntries =
                    populatedTemplate
                            ? List.of("memory/notes.md", "memory/sub/", "memory/template.md")
                            : List.of("memory/notes.md", "memory/sub/");
            for (String path : List.of("memory", "/memory", "memory/")) {
                GlobResult glob = fsB.glob(reader, "*.md", path);
                assertTrue(glob.isSuccess());
                assertEquals(
                        expectedFiles,
                        glob.matches().stream().map(FileInfo::path).sorted().toList());
                LsResult ls = fsB.ls(reader, path);
                assertTrue(ls.isSuccess());
                assertEquals(
                        expectedEntries,
                        ls.entries().stream().map(FileInfo::path).sorted().toList());
            }
            GlobResult isolated = fsB.glob(otherUser, "*.md", "memory");
            assertTrue(isolated.isSuccess());
            assertEquals(
                    populatedTemplate ? List.of("memory/template.md") : List.of(),
                    isolated.matches().stream().map(FileInfo::path).sorted().toList());
        }
    }

    /**
     * Mode 1 invariant: the composite filesystem produced by {@link RemoteFilesystemSpec} is
     * <b>not</b> a sandbox filesystem, so the agent builder will not register the shell execute
     * tool in this mode.
     */
    @Test
    void compositeModeIsNotASandboxFilesystem() {
        InMemoryStore store = new InMemoryStore();
        AbstractFilesystem fs =
                new RemoteFilesystemSpec(store).toFilesystem(workspace, "agent-a", rc -> List.of());

        assertFalse(
                fs instanceof AbstractSandboxFilesystem,
                "Composite (non-sandbox) filesystem must NOT be an AbstractSandboxFilesystem"
                        + " — shell execution should be unavailable in Mode 1");
        assertTrue(fs instanceof CompositeFilesystem);
    }
}
