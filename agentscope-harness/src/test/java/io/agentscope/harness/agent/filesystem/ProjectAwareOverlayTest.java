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
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.harness.agent.workspace.PathPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectAwareOverlayTest {

    @TempDir Path workspace;
    @TempDir Path project;

    private ProjectAwareOverlay overlay;
    private final RuntimeContext rc = RuntimeContext.empty();

    @BeforeEach
    void setUp() {
        overlay = newOverlay(null);
    }

    /**
     * Builds the overlay with the production wiring from {@code LocalFilesystemSpec}: upper and
     * projectFs share the namespace factory, the lower (read-only) project filesystem has none.
     */
    private ProjectAwareOverlay newOverlay(NamespaceFactory namespaceFactory) {
        PathPolicy policy = PathPolicy.of(project, workspace);
        LocalFilesystemWithShell upper =
                new LocalFilesystemWithShell(
                        workspace,
                        LocalFsMode.ROOTED,
                        policy,
                        120,
                        100_000,
                        null,
                        false,
                        namespaceFactory,
                        project);
        LocalFilesystem lower = new LocalFilesystem(project, true, 10, null);
        LocalFilesystem projectFs =
                new LocalFilesystem(project, LocalFsMode.ROOTED, policy, 10, namespaceFactory);
        return new ProjectAwareOverlay(
                (AbstractSandboxFilesystem) upper, lower, projectFs, workspace, namespaceFactory);
    }

    // ==================== Write routing ====================

    @Test
    void write_projectFile_landsInProjectDir() {
        WriteResult r = overlay.write(rc, "src/App.java", "public class App {}");
        assertTrue(r.isSuccess(), () -> "write failed: " + r.error());
        assertTrue(Files.exists(project.resolve("src/App.java")));
        assertFalse(Files.exists(workspace.resolve("src/App.java")));
    }

    @Test
    void write_absoluteWorkspaceSourcePath_landsInProjectDir() {
        String absPath = workspace.resolve("src/App.java").toAbsolutePath().toString();
        WriteResult r = overlay.write(rc, absPath, "public class App {}");
        assertTrue(r.isSuccess(), () -> "write failed: " + r.error());
        assertTrue(Files.exists(project.resolve("src/App.java")));
        assertFalse(Files.exists(workspace.resolve("src/App.java")));
    }

    @Test
    void write_absoluteWorkspaceMetadata_landsInWorkspace() {
        String absPath = workspace.resolve("MEMORY.md").toAbsolutePath().toString();
        WriteResult r = overlay.write(rc, absPath, "# Memory");
        assertTrue(r.isSuccess(), () -> "write failed: " + r.error());
        assertTrue(Files.exists(workspace.resolve("MEMORY.md")));
        assertFalse(Files.exists(project.resolve("MEMORY.md")));
    }

    @Test
    void write_memoryMd_landsInWorkspace() {
        WriteResult r = overlay.write(rc, "MEMORY.md", "# Memory");
        assertTrue(r.isSuccess(), () -> "write failed: " + r.error());
        assertTrue(Files.exists(workspace.resolve("MEMORY.md")));
        assertFalse(Files.exists(project.resolve("MEMORY.md")));
    }

    @Test
    void write_agentsSubpath_landsInWorkspace() {
        WriteResult r = overlay.write(rc, "agents/main/sessions/s1.json", "{}");
        assertTrue(r.isSuccess(), () -> "write failed: " + r.error());
        assertTrue(Files.exists(workspace.resolve("agents/main/sessions/s1.json")));
        assertFalse(Files.exists(project.resolve("agents/main/sessions/s1.json")));
    }

    @Test
    void write_skillsPath_landsInWorkspace() {
        WriteResult r = overlay.write(rc, "skills/my-skill/SKILL.md", "# Skill");
        assertTrue(r.isSuccess(), () -> "write failed: " + r.error());
        assertTrue(Files.exists(workspace.resolve("skills/my-skill/SKILL.md")));
    }

    @Test
    void write_plansPath_landsInWorkspace() {
        WriteResult r = overlay.write(rc, "plans/plan1.md", "# Plan");
        assertTrue(r.isSuccess(), () -> "write failed: " + r.error());
        assertTrue(Files.exists(workspace.resolve("plans/plan1.md")));
    }

    // ==================== Edit routing ====================

    @Test
    void edit_projectFile_editsInProject() throws IOException {
        Path file = project.resolve("README.md");
        Files.writeString(file, "Hello World", StandardCharsets.UTF_8);

        EditResult r = overlay.edit(rc, "README.md", "World", "AgentScope", false);
        assertTrue(r.isSuccess(), () -> "edit failed: " + r.error());
        assertEquals("Hello AgentScope", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void edit_absoluteWorkspacePath_editsProjectFile() throws IOException {
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("src/App.java"), "old impl", StandardCharsets.UTF_8);

        String absPath = workspace.resolve("src/App.java").toAbsolutePath().toString();
        EditResult r = overlay.edit(rc, absPath, "old impl", "new impl", false);
        assertTrue(r.isSuccess(), () -> "edit failed: " + r.error());
        assertEquals(
                "new impl",
                Files.readString(project.resolve("src/App.java"), StandardCharsets.UTF_8));
    }

    @Test
    void edit_memoryMd_editsInWorkspace() throws IOException {
        Path file = workspace.resolve("MEMORY.md");
        Files.writeString(file, "old memory", StandardCharsets.UTF_8);

        EditResult r = overlay.edit(rc, "MEMORY.md", "old", "new", false);
        assertTrue(r.isSuccess(), () -> "edit failed: " + r.error());
        assertEquals("new memory", Files.readString(file, StandardCharsets.UTF_8));
    }

    // ==================== Read (unchanged overlay semantics) ====================

    @Test
    void read_projectFile_visFallback() throws IOException {
        Files.writeString(project.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);

        ReadResult r = overlay.read(rc, "pom.xml", 0, 0);
        assertTrue(r.isSuccess());
        assertEquals("<project/>", r.fileData().content());
    }

    @Test
    void read_workspaceFile_takePrecedence() throws IOException {
        Files.writeString(project.resolve("AGENTS.md"), "project version", StandardCharsets.UTF_8);
        Files.writeString(
                workspace.resolve("AGENTS.md"), "workspace version", StandardCharsets.UTF_8);

        ReadResult r = overlay.read(rc, "AGENTS.md", 0, 0);
        assertTrue(r.isSuccess());
        assertEquals("workspace version", r.fileData().content());
    }

    @Test
    void read_absoluteWorkspacePath_fallsBackToProjectDir() throws IOException {
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("src/App.java"), "project impl", StandardCharsets.UTF_8);

        String absPath = workspace.resolve("src/App.java").toAbsolutePath().toString();
        ReadResult r = overlay.read(rc, absPath, 0, 0);
        assertTrue(r.isSuccess(), () -> "read failed: " + r.error());
        assertEquals("project impl", r.fileData().content());
    }

    @Test
    void read_absoluteWorkspacePath_upperTakesPrecedence() throws IOException {
        Files.createDirectories(project.resolve("src"));
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(project.resolve("src/App.java"), "project impl", StandardCharsets.UTF_8);
        Files.writeString(
                workspace.resolve("src/App.java"), "workspace impl", StandardCharsets.UTF_8);

        String absPath = workspace.resolve("src/App.java").toAbsolutePath().toString();
        ReadResult r = overlay.read(rc, absPath, 0, 0);
        assertTrue(r.isSuccess());
        assertEquals("workspace impl", r.fileData().content());
    }

    @Test
    void exists_absoluteWorkspacePath_checksProjectFallback() throws IOException {
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("src/App.java"), "impl", StandardCharsets.UTF_8);

        String existing = workspace.resolve("src/App.java").toAbsolutePath().toString();
        String missing = workspace.resolve("src/Missing.java").toAbsolutePath().toString();
        assertTrue(overlay.exists(rc, existing));
        assertFalse(overlay.exists(rc, missing));
    }

    @Test
    void downloadFiles_absoluteWorkspacePath_readsProjectFallback() throws IOException {
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("src/App.java"), "project impl", StandardCharsets.UTF_8);

        String absPath = workspace.resolve("src/App.java").toAbsolutePath().toString();
        List<FileDownloadResponse> responses = overlay.downloadFiles(rc, List.of(absPath));
        assertEquals(1, responses.size());
        FileDownloadResponse r = responses.get(0);
        assertTrue(r.isSuccess(), () -> "download failed: " + r.error());
        assertEquals("project impl", new String(r.content(), StandardCharsets.UTF_8));
    }

    @Test
    void ls_absoluteWorkspacePath_listsProjectFiles() throws IOException {
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("src/App.java"), "impl", StandardCharsets.UTF_8);

        String absPath = workspace.resolve("src").toAbsolutePath().toString();
        LsResult r = overlay.ls(rc, absPath);
        assertTrue(r.isSuccess());
        assertTrue(r.entries().stream().anyMatch(fi -> fi.path().endsWith("App.java")));
    }

    @Test
    void glob_absoluteWorkspacePath_findsProjectFiles() throws IOException {
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("src/App.java"), "impl", StandardCharsets.UTF_8);

        String absPath = workspace.resolve("src").toAbsolutePath().toString();
        GlobResult r = overlay.glob(rc, "**/*.java", absPath);
        assertTrue(r.isSuccess());
        assertTrue(r.matches().stream().anyMatch(fi -> fi.path().endsWith("App.java")));
    }

    @Test
    void grep_absoluteWorkspacePath_findsProjectFiles() throws IOException {
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("src/App.java"), "// TODO fix", StandardCharsets.UTF_8);

        String absPath = workspace.resolve("src").toAbsolutePath().toString();
        GrepResult r = overlay.grep(rc, "TODO", absPath, null);
        assertTrue(r.isSuccess());
        assertTrue(r.matches().stream().anyMatch(m -> m.line() == 1));
    }

    @Test
    void move_absoluteWorkspaceSourcePath_copiesToWorkspace() throws IOException {
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("src/A.txt"), "data", StandardCharsets.UTF_8);

        String fromAbs = workspace.resolve("src/A.txt").toAbsolutePath().toString();
        String toAbs = workspace.resolve("src/B.txt").toAbsolutePath().toString();
        WriteResult r = overlay.move(rc, fromAbs, toAbs);
        assertTrue(r.isSuccess(), () -> "move failed: " + r.error());
        assertTrue(Files.exists(workspace.resolve("src/B.txt")));
        assertEquals(
                "data", Files.readString(workspace.resolve("src/B.txt"), StandardCharsets.UTF_8));
    }

    // ==================== Delete routing ====================

    @Test
    void delete_absoluteWorkspacePath_deletesProjectFile() throws IOException {
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("src/App.java"), "impl", StandardCharsets.UTF_8);

        String absPath = workspace.resolve("src/App.java").toAbsolutePath().toString();
        WriteResult r = overlay.delete(rc, absPath);
        assertTrue(r.isSuccess(), () -> "delete failed: " + r.error());
        assertFalse(Files.exists(project.resolve("src/App.java")));
    }

    @Test
    void delete_projectFile_deletesFromProject() throws IOException {
        Path file = project.resolve("temp.txt");
        Files.writeString(file, "temp", StandardCharsets.UTF_8);

        WriteResult r = overlay.delete(rc, "temp.txt");
        assertTrue(r.isSuccess());
        assertFalse(Files.exists(file));
    }

    @Test
    void delete_workspacePath_deletesFromWorkspace() throws IOException {
        Path dir = workspace.resolve("memory");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("2024-01-01.md"), "log", StandardCharsets.UTF_8);

        WriteResult r = overlay.delete(rc, "memory/2024-01-01.md");
        assertTrue(r.isSuccess());
        assertFalse(Files.exists(dir.resolve("2024-01-01.md")));
    }

    // ==================== uploadFiles routing ====================

    @Test
    void uploadFiles_splitsByTarget() {
        List<Map.Entry<String, byte[]>> files =
                List.of(
                        Map.entry(
                                "src/Main.java", "class Main {}".getBytes(StandardCharsets.UTF_8)),
                        Map.entry("MEMORY.md", "# Mem".getBytes(StandardCharsets.UTF_8)));

        List<FileUploadResponse> results = overlay.uploadFiles(rc, files);
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(FileUploadResponse::isSuccess));

        assertTrue(Files.exists(project.resolve("src/Main.java")));
        assertTrue(Files.exists(workspace.resolve("MEMORY.md")));
        assertFalse(Files.exists(workspace.resolve("src/Main.java")));
        assertFalse(Files.exists(project.resolve("MEMORY.md")));
    }

    @Test
    void uploadFiles_absoluteWorkspacePath_landsInProjectDir() {
        String absPath = workspace.resolve("src/App.java").toAbsolutePath().toString();
        List<Map.Entry<String, byte[]>> files =
                List.of(Map.entry(absPath, "impl".getBytes(StandardCharsets.UTF_8)));

        List<FileUploadResponse> results = overlay.uploadFiles(rc, files);
        assertEquals(1, results.size());
        assertTrue(results.get(0).isSuccess());
        assertTrue(Files.exists(project.resolve("src/App.java")));
    }

    // ==================== Namespace scoping ====================

    @Nested
    class NamespacedDeployment {

        private final RuntimeContext nsRc = RuntimeContext.builder().userId("u1").build();
        private ProjectAwareOverlay nsOverlay;

        @BeforeEach
        void setUpNamespaced() {
            nsOverlay = newOverlay(rc -> List.of(rc.getUserId()));
        }

        @Test
        void write_absoluteNamespacedMemoryPath_landsInNamespacedWorkspace() {
            String absPath =
                    workspace.resolve("u1").resolve("MEMORY.md").toAbsolutePath().toString();
            WriteResult r = nsOverlay.write(nsRc, absPath, "# Memory");
            assertTrue(r.isSuccess(), () -> "write failed: " + r.error());
            assertTrue(Files.exists(workspace.resolve("u1/MEMORY.md")));
            assertFalse(Files.exists(workspace.resolve("MEMORY.md")));
            assertFalse(Files.exists(project.resolve("u1/u1/MEMORY.md")));
        }

        @Test
        void write_absoluteNamespacedSourcePath_matchesRelativeWrite() {
            String absPath =
                    workspace.resolve("u1").resolve("src/App.java").toAbsolutePath().toString();
            WriteResult r = nsOverlay.write(nsRc, absPath, "public class App {}");
            assertTrue(r.isSuccess(), () -> "write failed: " + r.error());
            // The absolute spelling lands where the relative spelling does: inside the
            // caller's namespace in the project directory.
            assertTrue(Files.exists(project.resolve("u1/src/App.java")));
            assertFalse(Files.exists(workspace.resolve("u1/src/App.java")));
        }

        @Test
        void read_absoluteNamespacedMemoryPath_findsUpperFile() {
            assertTrue(nsOverlay.write(nsRc, "MEMORY.md", "hello").isSuccess());

            String absPath =
                    workspace.resolve("u1").resolve("MEMORY.md").toAbsolutePath().toString();
            assertTrue(nsOverlay.exists(nsRc, absPath));
            ReadResult r = nsOverlay.read(nsRc, absPath, 0, 0);
            assertTrue(r.isSuccess(), () -> "read failed: " + r.error());
            assertEquals("hello", r.fileData().content());
        }

        @Test
        void edit_absolutePath_copiesProjectOriginalToWorkspace() throws IOException {
            Files.createDirectories(project.resolve("src"));
            Files.writeString(
                    project.resolve("src/App.java"), "project impl", StandardCharsets.UTF_8);

            String absPath = workspace.resolve("src/App.java").toAbsolutePath().toString();
            EditResult r = nsOverlay.edit(nsRc, absPath, "project impl", "edited impl", false);
            assertTrue(r.isSuccess(), () -> "edit failed: " + r.error());
            // Copy-on-write, same as the relative spelling: the project original stays
            // untouched and the edited copy lands in the caller's namespaced workspace.
            assertEquals(
                    "project impl",
                    Files.readString(project.resolve("src/App.java"), StandardCharsets.UTF_8));
            assertEquals(
                    "edited impl",
                    Files.readString(workspace.resolve("u1/src/App.java"), StandardCharsets.UTF_8));
        }
    }

    // ==================== isWorkspacePath ====================

    @Test
    void isWorkspacePath_classifiesCorrectly() {
        assertTrue(overlay.isWorkspacePath(rc, "MEMORY.md"));
        assertTrue(overlay.isWorkspacePath(rc, "memory/2024-01-01.md"));
        assertTrue(overlay.isWorkspacePath(rc, "AGENTS.md"));
        assertTrue(overlay.isWorkspacePath(rc, "agents/main/sessions/s.json"));
        assertTrue(overlay.isWorkspacePath(rc, "skills/my-skill/SKILL.md"));
        assertTrue(overlay.isWorkspacePath(rc, "knowledge/KNOWLEDGE.md"));
        assertTrue(overlay.isWorkspacePath(rc, "rules/rule1.md"));
        assertTrue(overlay.isWorkspacePath(rc, "tools.json"));
        assertTrue(overlay.isWorkspacePath(rc, "subagents/researcher.md"));
        assertTrue(overlay.isWorkspacePath(rc, "plans/plan.md"));
        assertTrue(overlay.isWorkspacePath(rc, ".index/workspace.db"));
        assertTrue(overlay.isWorkspacePath(rc, ".skills-cache/cached"));
        assertTrue(overlay.isWorkspacePath(rc, "large_tool_results/agent/call1"));

        assertFalse(overlay.isWorkspacePath(rc, "src/App.java"));
        assertFalse(overlay.isWorkspacePath(rc, "pom.xml"));
        assertFalse(overlay.isWorkspacePath(rc, "README.md"));
        assertFalse(overlay.isWorkspacePath(rc, "docker-compose.yml"));
    }

    @Test
    void isWorkspacePath_absoluteWorkspaceMetadata_returnsTrue() {
        String absPath = workspace.resolve("MEMORY.md").toAbsolutePath().toString();
        assertTrue(overlay.isWorkspacePath(rc, absPath));
    }

    @Test
    void isWorkspacePath_absoluteWorkspaceSourceFile_returnsFalse() {
        String absPath = workspace.resolve("src/App.java").toAbsolutePath().toString();
        assertFalse(overlay.isWorkspacePath(rc, absPath));
    }

    @Test
    void isWorkspacePath_absoluteUnderProject_returnsFalse() {
        String absPath = project.resolve("src/App.java").toAbsolutePath().toString();
        assertFalse(overlay.isWorkspacePath(rc, absPath));
    }

    @Test
    void isWorkspacePath_absoluteOutsideWorkspace_returnsFalse() {
        // A drive-root absolute path whose remainder matches a workspace prefix must not be
        // classified as workspace metadata.
        String absPath = workspace.toAbsolutePath().getRoot().resolve("MEMORY.md").toString();
        assertFalse(overlay.isWorkspacePath(rc, absPath));
    }

    // ==================== Shell execute delegates to upper ====================

    @Test
    void execute_delegatesToShellBackend() {
        var r = overlay.execute(rc, "echo hello", 10);
        assertTrue(r.output().contains("hello"));
        assertEquals(0, r.exitCode());
    }
}
