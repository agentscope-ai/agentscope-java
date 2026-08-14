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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
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
    void sharedFallbackDoesNotAllowTraversalOutsideKnowledge() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path project = Files.createDirectories(tempDir.resolve("project"));
        Files.writeString(workspace.resolve("MEMORY.md"), "shared root secret");

        AbstractFilesystem filesystem =
                new LocalFilesystemSpec()
                        .project(project)
                        .toFilesystem(workspace, rc -> List.of(rc.getUserId()));
        RuntimeContext alice = RuntimeContext.builder().userId("alice").build();

        String traversalPath = "knowledge/../MEMORY.md";
        assertThrows(IllegalArgumentException.class, () -> filesystem.exists(alice, traversalPath));
        assertThrows(
                IllegalArgumentException.class,
                () -> filesystem.read(alice, traversalPath, 0, 100));
    }

    @Test
    void localOverlayKeepsShellCapability() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path project = Files.createDirectories(tempDir.resolve("project"));

        AbstractFilesystem filesystem =
                new LocalFilesystemSpec().project(project).toFilesystem(workspace, rc -> List.of());

        assertTrue(filesystem instanceof AbstractSandboxFilesystem);
    }

    @Test
    void knowledgeListingAndSearchMergeAllThreeLayers() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path project = Files.createDirectories(tempDir.resolve("project"));
        Files.createDirectories(project.resolve("knowledge"));
        Files.writeString(project.resolve("knowledge/project.md"), "needle from project");
        Files.createDirectories(workspace.resolve("knowledge"));
        Files.writeString(workspace.resolve("knowledge/shared.md"), "needle from shared");
        Files.createDirectories(workspace.resolve("alice/knowledge"));
        Files.writeString(workspace.resolve("alice/knowledge/personal.md"), "needle from alice");

        AbstractFilesystem filesystem =
                new LocalFilesystemSpec()
                        .project(project)
                        .toFilesystem(workspace, rc -> List.of(rc.getUserId()));
        RuntimeContext alice = RuntimeContext.builder().userId("alice").build();

        LsResult ls = filesystem.ls(alice, "knowledge");
        GlobResult glob = filesystem.glob(alice, "*.md", "knowledge");
        GrepResult grep = filesystem.grep(alice, "needle", "knowledge", "*.md");

        assertTrue(ls.isSuccess());
        assertPathsContain(
                ls.entries(),
                "knowledge/project.md",
                "knowledge/shared.md",
                "knowledge/personal.md");
        assertTrue(glob.isSuccess());
        assertPathsContain(
                glob.matches(),
                "knowledge/project.md",
                "knowledge/shared.md",
                "knowledge/personal.md");
        assertTrue(grep.isSuccess());
        Set<String> grepPaths =
                grep.matches().stream().map(match -> match.path()).collect(Collectors.toSet());
        assertTrue(grepPaths.stream().anyMatch(path -> path.endsWith("knowledge/project.md")));
        assertTrue(grepPaths.stream().anyMatch(path -> path.endsWith("knowledge/shared.md")));
        assertTrue(grepPaths.stream().anyMatch(path -> path.endsWith("knowledge/personal.md")));
    }

    @Test
    void downloadsResolveKnowledgeInLayerOrderAndKeepRegularOverlayBehavior() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path project = Files.createDirectories(tempDir.resolve("project"));
        Files.createDirectories(project.resolve("knowledge"));
        Files.writeString(project.resolve("knowledge/project.txt"), "project");
        Files.createDirectories(project.resolve("docs"));
        Files.writeString(project.resolve("docs/regular.txt"), "regular");
        Files.createDirectories(workspace.resolve("knowledge"));
        Files.writeString(workspace.resolve("knowledge/shared.txt"), "shared");
        Files.createDirectories(workspace.resolve("alice/knowledge"));
        Files.writeString(workspace.resolve("alice/knowledge/personal.txt"), "personal");

        AbstractFilesystem filesystem =
                new LocalFilesystemSpec()
                        .project(project)
                        .toFilesystem(workspace, rc -> List.of(rc.getUserId()));
        RuntimeContext alice = RuntimeContext.builder().userId("alice").build();

        List<FileDownloadResponse> downloads =
                filesystem.downloadFiles(
                        alice,
                        List.of(
                                "knowledge/personal.txt",
                                "knowledge/shared.txt",
                                "knowledge/project.txt",
                                "docs/regular.txt"));

        assertEquals(4, downloads.size());
        assertEquals("personal", content(downloads.get(0)));
        assertEquals("shared", content(downloads.get(1)));
        assertEquals("project", content(downloads.get(2)));
        assertEquals("regular", content(downloads.get(3)));
    }

    @Test
    void sharedKnowledgeMoveCopiesToIsolatedLayerAndDeleteCannotRemoveBaseline() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path project = Files.createDirectories(tempDir.resolve("project"));
        Files.createDirectories(workspace.resolve("knowledge"));
        Files.writeString(workspace.resolve("knowledge/reference.md"), "shared reference");
        Files.createDirectories(workspace.resolve("alice/knowledge"));
        Files.writeString(workspace.resolve("alice/knowledge/personal.md"), "personal reference");

        AbstractFilesystem filesystem =
                new LocalFilesystemSpec()
                        .project(project)
                        .toFilesystem(workspace, rc -> List.of(rc.getUserId()));
        RuntimeContext alice = RuntimeContext.builder().userId("alice").build();

        WriteResult sharedDelete = filesystem.delete(alice, "knowledge/reference.md");
        WriteResult move = filesystem.move(alice, "knowledge/reference.md", "knowledge/copied.md");
        WriteResult personalDelete = filesystem.delete(alice, "knowledge/personal.md");

        assertFalse(sharedDelete.isSuccess());
        assertTrue(move.isSuccess());
        assertEquals(
                "shared reference",
                Files.readString(workspace.resolve("alice/knowledge/copied.md")));
        assertEquals(
                "shared reference", Files.readString(workspace.resolve("knowledge/reference.md")));
        assertTrue(personalDelete.isSuccess());
        assertFalse(Files.exists(workspace.resolve("alice/knowledge/personal.md")));
    }

    @Test
    void localOverlayDelegatesIdAndShellExecution() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path project = Files.createDirectories(tempDir.resolve("project"));
        AbstractFilesystem filesystem =
                new LocalFilesystemSpec().project(project).toFilesystem(workspace, rc -> List.of());
        AbstractSandboxFilesystem sandbox = (AbstractSandboxFilesystem) filesystem;

        assertFalse(sandbox.id().isBlank());
        var result = sandbox.execute(RuntimeContext.empty(), "printf local-overlay", 10);
        assertEquals(0, result.exitCode());
        assertEquals("local-overlay", result.output());
    }

    private static void assertContent(ReadResult result, String expected) {
        assertTrue(result.isSuccess());
        assertEquals(expected, result.fileData().content());
    }

    private static void assertPathsContain(List<FileInfo> entries, String... expectedPaths) {
        Set<String> paths = entries.stream().map(FileInfo::path).collect(Collectors.toSet());
        for (String expectedPath : expectedPaths) {
            assertTrue(paths.stream().anyMatch(path -> path.endsWith(expectedPath)));
        }
    }

    private static String content(FileDownloadResponse response) {
        assertTrue(response.isSuccess());
        return new String(response.content(), StandardCharsets.UTF_8);
    }
}
