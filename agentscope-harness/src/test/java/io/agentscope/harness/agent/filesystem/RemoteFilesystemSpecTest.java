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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.store.InMemoryStore;
import io.agentscope.harness.agent.store.NamespaceFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteFilesystemSpecTest {

    private static final RuntimeContext RT = RuntimeContext.empty();

    @TempDir Path workspace;

    @Test
    void routesSharedPathsToStoreAndOthersToLocal() throws Exception {
        InMemoryStore store = new InMemoryStore();
        AtomicReference<String> userRef = new AtomicReference<>();
        NamespaceFactory localNs = () -> List.of("local-user");

        AbstractFilesystem fs =
                new RemoteFilesystemSpec(store)
                        .anonymousUserId("anon")
                        .toFilesystem(workspace, "agent-a", localNs, userRef::get);

        fs.uploadFiles(
                RT,
                List.of(
                        java.util.Map.entry(
                                "MEMORY.md", "hello".getBytes(StandardCharsets.UTF_8))));
        assertNotNull(store.get(List.of("agents", "agent-a", "users", "anon"), "/MEMORY.md"));

        fs.uploadFiles(
                RT,
                List.of(
                        java.util.Map.entry(
                                "knowledge/notes.md", "local".getBytes(StandardCharsets.UTF_8))));
        assertTrue(Files.isRegularFile(workspace.resolve("local-user/knowledge/notes.md")));
    }

    @Test
    void resolvesNamespaceByRuntimeUserId() {
        InMemoryStore store = new InMemoryStore();
        AtomicReference<String> userRef = new AtomicReference<>("user-1");

        AbstractFilesystem fs =
                new RemoteFilesystemSpec(store)
                        .toFilesystem(workspace, "agent-a", List::of, userRef::get);

        fs.uploadFiles(
                RT,
                List.of(java.util.Map.entry("MEMORY.md", "v1".getBytes(StandardCharsets.UTF_8))));
        assertNotNull(store.get(List.of("agents", "agent-a", "users", "user-1"), "/MEMORY.md"));
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
                new RemoteFilesystemSpec(store)
                        .toFilesystem(workspace, "agent-a", List::of, () -> null);

        assertFalse(
                fs instanceof AbstractSandboxFilesystem,
                "Composite (non-sandbox) filesystem must NOT be an AbstractSandboxFilesystem"
                        + " — shell execution should be unavailable in Mode 1");
        assertTrue(fs instanceof CompositeFilesystem);
    }
}
