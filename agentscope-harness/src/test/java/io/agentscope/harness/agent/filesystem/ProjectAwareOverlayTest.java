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
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectAwareOverlayTest {

    @TempDir Path workspace;
    @TempDir Path project;

    private ProjectAwareOverlay overlay;
    private final RuntimeContext rc = RuntimeContext.empty();

    @BeforeEach
    void setUp() {
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
                        null,
                        project);
        LocalFilesystem lower = new LocalFilesystem(project, true, 10, null);
        LocalFilesystem projectFs =
                new LocalFilesystem(project, LocalFsMode.ROOTED, policy, 10, null);
        overlay =
                new ProjectAwareOverlay(
                        (AbstractSandboxFilesystem) upper, lower, projectFs, workspace);
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

    // ==================== Delete routing ====================

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

    // ==================== isWorkspacePath ====================

    @Test
    void isWorkspacePath_classifiesCorrectly() {
        assertTrue(overlay.isWorkspacePath("MEMORY.md"));
        assertTrue(overlay.isWorkspacePath("memory/2024-01-01.md"));
        assertTrue(overlay.isWorkspacePath("AGENTS.md"));
        assertTrue(overlay.isWorkspacePath("agents/main/sessions/s.json"));
        assertTrue(overlay.isWorkspacePath("skills/my-skill/SKILL.md"));
        assertTrue(overlay.isWorkspacePath("knowledge/KNOWLEDGE.md"));
        assertTrue(overlay.isWorkspacePath("rules/rule1.md"));
        assertTrue(overlay.isWorkspacePath("tools.json"));
        assertTrue(overlay.isWorkspacePath("subagents/researcher.md"));
        assertTrue(overlay.isWorkspacePath("plans/plan.md"));
        assertTrue(overlay.isWorkspacePath(".index/workspace.db"));
        assertTrue(overlay.isWorkspacePath(".skills-cache/cached"));
        assertTrue(overlay.isWorkspacePath("large_tool_results/agent/call1"));

        assertFalse(overlay.isWorkspacePath("src/App.java"));
        assertFalse(overlay.isWorkspacePath("pom.xml"));
        assertFalse(overlay.isWorkspacePath("README.md"));
        assertFalse(overlay.isWorkspacePath("docker-compose.yml"));
    }

    @Test
    void isWorkspacePath_absoluteUnderWorkspace_returnsTrue() {
        String absPath = workspace.resolve("anything.txt").toAbsolutePath().toString();
        assertTrue(overlay.isWorkspacePath(absPath));
    }

    @Test
    void isWorkspacePath_absoluteUnderProject_returnsFalse() {
        String absPath = project.resolve("src/App.java").toAbsolutePath().toString();
        assertFalse(overlay.isWorkspacePath(absPath));
    }

    // ==================== Shell execute delegates to upper ====================

    @Test
    void execute_delegatesToShellBackend() {
        var r = overlay.execute(rc, "echo hello", 10);
        assertTrue(r.output().contains("hello"));
        assertEquals(0, r.exitCode());
    }

    @Test
    void getWorkspaceRoot_delegatesToShellBackend() {
        String root = overlay.getWorkspaceRoot();
        assertTrue(root.contains(workspace.toAbsolutePath().toString()));
    }
}
