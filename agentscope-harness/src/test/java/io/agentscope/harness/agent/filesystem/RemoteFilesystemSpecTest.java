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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
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
}
