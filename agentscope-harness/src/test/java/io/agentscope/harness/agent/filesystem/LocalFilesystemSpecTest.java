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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFilesystemSpecTest {

    @TempDir Path tempDir;

    @Test
    void isolatedKnowledgeOverridesSharedKnowledgeAndFallsBackWhenMissing() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path project = Files.createDirectories(tempDir.resolve("project"));
        Files.createDirectories(workspace.resolve("knowledge"));
        Files.writeString(workspace.resolve("knowledge/shared.md"), "shared baseline");
        Files.writeString(workspace.resolve("knowledge/overridden.md"), "shared version");
        Files.createDirectories(workspace.resolve("alice/knowledge"));
        Files.writeString(workspace.resolve("alice/knowledge/overridden.md"), "alice version");

        AbstractFilesystem filesystem =
                new LocalFilesystemSpec()
                        .project(project)
                        .toFilesystem(workspace, rc -> List.of(rc.getUserId()));
        RuntimeContext alice = RuntimeContext.builder().userId("alice").build();

        assertContent(filesystem.read(alice, "knowledge/shared.md", 0, 100), "shared baseline");
        assertContent(filesystem.read(alice, "knowledge/overridden.md", 0, 100), "alice version");
        assertTrue(filesystem.exists(alice, "knowledge/shared.md"));
    }

    @Test
    void editingSharedKnowledgeCopiesItIntoIsolatedWorkspace() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path project = Files.createDirectories(tempDir.resolve("project"));
        Files.createDirectories(workspace.resolve("knowledge"));
        Files.writeString(workspace.resolve("knowledge/reference.md"), "shared text");

        AbstractFilesystem filesystem =
                new LocalFilesystemSpec()
                        .project(project)
                        .toFilesystem(workspace, rc -> List.of(rc.getUserId()));
        RuntimeContext alice = RuntimeContext.builder().userId("alice").build();

        EditResult result =
                filesystem.edit(alice, "knowledge/reference.md", "shared", "personal", false);

        assertTrue(result.isSuccess());
        assertEquals("shared text", Files.readString(workspace.resolve("knowledge/reference.md")));
        assertEquals(
                "personal text",
                Files.readString(workspace.resolve("alice/knowledge/reference.md")));
    }

    @Test
    void sharedFallbackDoesNotExposeOtherUserNamespaces() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path project = Files.createDirectories(tempDir.resolve("project"));
        Files.createDirectories(workspace.resolve("bob/knowledge"));
        Files.writeString(workspace.resolve("bob/knowledge/private.md"), "bob secret");

        AbstractFilesystem filesystem =
                new LocalFilesystemSpec()
                        .project(project)
                        .toFilesystem(workspace, rc -> List.of(rc.getUserId()));
        RuntimeContext alice = RuntimeContext.builder().userId("alice").build();

        assertFalse(filesystem.exists(alice, "bob/knowledge/private.md"));
        assertFalse(filesystem.read(alice, "bob/knowledge/private.md", 0, 100).isSuccess());
    }

    @Test
    void localOverlayKeepsShellCapability() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path project = Files.createDirectories(tempDir.resolve("project"));

        AbstractFilesystem filesystem =
                new LocalFilesystemSpec().project(project).toFilesystem(workspace, rc -> List.of());

        assertTrue(filesystem instanceof AbstractSandboxFilesystem);
    }

    private static void assertContent(ReadResult result, String expected) {
        assertTrue(result.isSuccess());
        assertEquals(expected, result.fileData().content());
    }
}
