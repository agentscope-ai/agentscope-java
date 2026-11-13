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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    // ==================== Bug reproduction: host/app read-write asymmetry (#3245)
    // ====================

    @Test
    void sharedLocalWorkspaceSeesHostWrittenFiles() throws Exception {
        InMemoryStore store = new InMemoryStore();
        NamespaceFactory localNs = rc -> List.of("local-user");

        // The host application writes to the workspace root with plain java.nio; with the
        // default namespaced backend these files were invisible to read_file/list_files.
        Path uploaded = workspace.resolve("uploads/ff17dbe6/result.md");
        Files.createDirectories(uploaded.getParent());
        Files.writeString(uploaded, "host content");

        AbstractFilesystem fs =
                new RemoteFilesystemSpec(store)
                        .sharedLocalWorkspace(true)
                        .toFilesystem(workspace, "agent-a", localNs);

        ReadResult read = fs.read(RT, "uploads/ff17dbe6/result.md", 0, 0);
        assertTrue(read.isSuccess(), () -> "host-written file must be readable: " + read.error());
        assertTrue(read.fileData().content().contains("host content"));

        LsResult ls = fs.ls(RT, "uploads/ff17dbe6");
        assertTrue(ls.isSuccess(), () -> "uploads dir must be listable: " + ls.error());

        // Agent writes land where the host expects them — no {userId}/ prefix directory.
        assertTrue(fs.write(RT, "out/agent.md", "from agent").isSuccess());
        assertTrue(Files.isRegularFile(workspace.resolve("out/agent.md")));
        assertFalse(Files.exists(workspace.resolve("local-user")));
    }

    @Test
    void sharedLocalWorkspaceBlocksTraversal() {
        InMemoryStore store = new InMemoryStore();
        AbstractFilesystem fs =
                new RemoteFilesystemSpec(store)
                        .sharedLocalWorkspace(true)
                        .toFilesystem(workspace, "agent-a", rc -> List.of());

        assertThrows(SecurityException.class, () -> fs.read(RT, "../secrets.txt", 0, 0));
    }

    @Test
    void sharedRoutesKeepStoreNamespaceWithSharedLocalWorkspace() {
        InMemoryStore store = new InMemoryStore();
        AbstractFilesystem fs =
                new RemoteFilesystemSpec(store)
                        .sharedLocalWorkspace(true)
                        .toFilesystem(workspace, "agent-a", rc -> List.of());

        RuntimeContext rcUser1 = RuntimeContext.builder().userId("user-1").build();
        fs.uploadFiles(
                rcUser1, List.of(Map.entry("MEMORY.md", "v1".getBytes(StandardCharsets.UTF_8))));

        assertNotNull(
                store.get(List.of("agents", "agent-a", "users", "user-1", "root"), "/MEMORY.md"),
                "shared routes must keep their per-user store namespaces");
    }

    // ==================== Bug reproduction: root listing/grep escape (#3253)
    // ====================

    @Test
    void rootListingAndGrepAnchorAtTheCallersNamespace() throws Exception {
        InMemoryStore store = new InMemoryStore();
        NamespaceFactory localNs = rc -> List.of("local-user");

        // The caller's own tree, written through the framework (lands namespaced).
        // A sibling tenant's directory must NOT leak into the caller's root listing.
        Files.createDirectories(workspace.resolve("other-user"));
        Files.writeString(workspace.resolve("other-user/secret.md"), "tenant secret");

        AbstractFilesystem fs =
                new RemoteFilesystemSpec(store).toFilesystem(workspace, "agent-a", localNs);
        assertTrue(fs.write(RT, "notes/needle.md", "haystack needle").isSuccess());

        // Blank and whitespace join the root spellings: they must never slip past the
        // anchor and expose the bare workspace/sibling tenants (#3253 review follow-up).
        for (String root : new String[] {".", "/", null, "", "   "}) {
            LsResult ls = fs.ls(RT, root);
            assertTrue(ls.isSuccess(), () -> "root listing '" + root + "': " + ls.error());
            for (FileInfo fi : ls.entries()) {
                assertFalse(
                        fi.path().replaceFirst("^/", "").startsWith("other-user"),
                        "root listing must not expose a sibling tenant: " + fi.path());
                assertFalse(
                        fi.path().contains(".."),
                        "root listing must not escape the workspace: " + fi.path());
            }
        }
        assertTrue(
                fs.ls(RT, ".").entries().stream()
                        .anyMatch(fi -> fi.path().replaceFirst("^/", "").startsWith("notes")),
                "the caller's own tree must remain visible in the root listing");

        // All root spellings walk the same tree: the caller's namespaced root.
        for (String root : new String[] {".", "/", null, "", "   "}) {
            GrepResult grep = fs.grep(RT, "needle", root, null);
            assertTrue(grep.isSuccess(), () -> "root grep '" + root + "' failed: " + grep.error());
            assertEquals(
                    1,
                    grep.matches().size(),
                    "root grep '" + root + "' must see only the caller's tree");
        }

        // Anchor equivalence: ls("/"), ls(null), ls(""), and ls("  ") all return the
        // identical entry set as ls(".") — every root spelling shares the namespaced
        // aggregate anchor (#3253 review follow-up).
        List<String> dotPaths =
                fs.ls(RT, ".").entries().stream().map(FileInfo::path).sorted().toList();
        for (String root : new String[] {"/", null, "", "   "}) {
            List<String> rootPaths =
                    fs.ls(RT, root).entries().stream().map(FileInfo::path).sorted().toList();
            assertEquals(
                    dotPaths, rootPaths, "ls('" + root + "') must match ls('.') entry-for-entry");
        }
    }

    @Test
    void sharedModeRootListingStaysInsideTheWorkspace() throws Exception {
        InMemoryStore store = new InMemoryStore();
        Files.createDirectories(workspace.resolve("uploads"));
        // A file just outside the workspace: the shared-mode root listing must never see it.
        Path outsideMarker =
                workspace.getParent().resolve("outside-marker-" + System.nanoTime() + ".txt");
        Files.writeString(outsideMarker, "outside");

        AbstractFilesystem fs =
                new RemoteFilesystemSpec(store)
                        .sharedLocalWorkspace(true)
                        .toFilesystem(workspace, "agent-a", rc -> List.of("local-user"));

        LsResult ls = fs.ls(RT, ".");
        assertTrue(ls.isSuccess(), () -> "root listing failed: " + ls.error());
        assertTrue(
                ls.entries().stream()
                        .anyMatch(fi -> fi.path().replaceFirst("^/", "").startsWith("uploads")),
                "shared workspace content must remain visible");
        for (FileInfo fi : ls.entries()) {
            assertFalse(fi.path().contains(".."), "listing entry escaped: " + fi.path());
            assertFalse(
                    fi.path().contains(outsideMarker.getFileName().toString()),
                    "listing entry escaped the workspace: " + fi.path());
        }
    }
}
