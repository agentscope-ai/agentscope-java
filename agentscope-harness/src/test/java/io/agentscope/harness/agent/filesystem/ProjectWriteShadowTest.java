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
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.filesystem.util.FilesystemUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reads ask the workspace layer first, so a project write under a workspace key is invisible. */
class ProjectWriteShadowTest {

    private static final String RELATIVE = "src/App.java";

    @TempDir Path project;

    @TempDir Path workspace;

    @BeforeEach
    void seedProject() throws Exception {
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve(RELATIVE), "project-v1");
    }

    private AbstractFilesystem filesystem(boolean projectWritable) {
        return new LocalFilesystemSpec()
                .project(project)
                .projectWritable(projectWritable)
                .toFilesystem(workspace, rc -> List.of(rc.getUserId()));
    }

    private static RuntimeContext alice() {
        return RuntimeContext.builder().userId("alice").build();
    }

    /** A workspace copy of a project key wins the read, so writing the project would vanish. */
    @Test
    void projectWrite_refusesWhenAWorkspaceCopyHoldsTheKey() throws Exception {
        assertTrue(filesystem(false).write(alice(), RELATIVE, "mine").isSuccess());
        assertTrue(Files.isRegularFile(workspace.resolve("alice/" + RELATIVE)));

        WriteResult refused = filesystem(true).write(alice(), RELATIVE, "project-edit");
        assertFalse(refused.isSuccess(), "the write would land where the read never looks");
        assertTrue(refused.error().contains(RELATIVE), refused.error());
        assertTrue(refused.error().contains("workspace copy"), refused.error());
        assertEquals("project-v1", Files.readString(project.resolve(RELATIVE)));
    }

    /**
     * A workspace absolute spelling folds to the same relative key, so it answers the same
     * refusal as that key: the spelling may not route around the shadow check.
     */
    @Test
    void workspaceAbsoluteSpelling_isRefusedLikeItsRelativeKey() throws Exception {
        assertTrue(filesystem(false).write(alice(), RELATIVE, "mine").isSuccess());
        String spelling = workspace.resolve("alice/" + RELATIVE).toString();

        WriteResult refused = filesystem(true).write(alice(), spelling, "next");
        assertFalse(refused.isSuccess());
        assertTrue(refused.error().contains("workspace copy"), refused.error());
        assertEquals("mine", fs_read(filesystem(true), RELATIVE));
    }

    private static String fs_read(AbstractFilesystem fs, String key) {
        return fs.read(alice(), key, 0, 100).fileData().content();
    }

    /**
     * An absolute spelling names project content; the workspace layer only answers for it through
     * the shared path policy, and that answer must not count as a shadowing workspace copy.
     */
    @Test
    void absoluteProjectSpelling_reachesTheProjectLayer() throws Exception {
        AbstractFilesystem fs = filesystem(true);
        String absolute = project.resolve(RELATIVE).toString();

        assertTrue(
                fs.edit(alice(), absolute, "project-v1", "project-v2", false).isSuccess(),
                "an absolute project path is project content, not a workspace copy");
        assertEquals("project-v2", Files.readString(project.resolve(RELATIVE)));
        assertTrue(
                fs.move(alice(), absolute, project.resolve("src/Moved.java").toString())
                        .isSuccess());
        assertFalse(Files.exists(project.resolve(RELATIVE)));
        assertTrue(Files.isRegularFile(project.resolve("src/Moved.java")));
    }

    /**
     * The refusal tells the caller to delete the workspace copy first, and delete must do that:
     * removing the project copy underneath would leave the read still returning the workspace
     * file, so the next write would be refused again.
     */
    @Test
    void delete_removesTheWorkspaceCopyFirstSoTheWriteCanProceed() throws Exception {
        assertTrue(filesystem(false).write(alice(), RELATIVE, "mine").isSuccess());
        Files.createDirectories(project.resolve("alice/src"));
        Files.writeString(project.resolve("alice/" + RELATIVE), "project-copy");

        AbstractFilesystem fs = filesystem(true);
        assertFalse(fs.write(alice(), RELATIVE, "next").isSuccess());

        assertTrue(fs.delete(alice(), RELATIVE).isSuccess());
        assertFalse(
                Files.exists(workspace.resolve("alice/" + RELATIVE)),
                "delete removed the project copy the read never returns");
        assertEquals("project-copy", fs.read(alice(), RELATIVE, 0, 100).fileData().content());
        assertTrue(
                fs.edit(alice(), RELATIVE, "project-copy", "next", false).isSuccess(),
                "with the workspace copy gone the project key is writable again");
        assertEquals("next", fs.read(alice(), RELATIVE, 0, 100).fileData().content());
    }

    /**
     * The workspace copy of a metadata file is the caller's own customization, and it is what
     * reads return; a newer project copy is a fallback the caller has overridden, not a conflict.
     */
    @Test
    void metadataEdit_keepsWorkingWhenTheProjectFileChanges() throws Exception {
        Files.writeString(project.resolve("AGENTS.md"), "team-v1");
        AbstractFilesystem fs = filesystem(true);

        assertTrue(fs.edit(alice(), "AGENTS.md", "team-v1", "mine-v1", false).isSuccess());
        Files.writeString(project.resolve("AGENTS.md"), "team-v2");
        Files.setLastModifiedTime(
                project.resolve("AGENTS.md"),
                FileTime.fromMillis(System.currentTimeMillis() + 5_000));

        assertTrue(fs.edit(alice(), "AGENTS.md", "mine-v1", "mine-v2", false).isSuccess());
        assertEquals("mine-v2", fs.read(alice(), "AGENTS.md", 0, 100).fileData().content());
    }

    /** Upload and move write the same bytes by other means, so they answer the same refusal. */
    @Test
    void uploadAndMove_refuseWhenAWorkspaceCopyHoldsTheKey() throws Exception {
        assertTrue(filesystem(false).write(alice(), RELATIVE, "mine").isSuccess());
        Files.createDirectories(project.resolve("alice/src"));
        Path other = project.resolve("alice/src/Other.java");
        Files.writeString(other, "project-other");
        AbstractFilesystem fs = filesystem(true);
        byte[] bytes = "uploaded".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        FileUploadResponse uploaded =
                fs.uploadFiles(alice(), List.of(Map.entry(RELATIVE, bytes))).get(0);
        assertFalse(uploaded.isSuccess(), "the upload would land where the read never looks");
        assertFalse(
                fs.move(alice(), "src/Other.java", RELATIVE).isSuccess(),
                "the move would land where the read never looks");
        assertEquals("project-other", Files.readString(other));
    }

    /** A leading space is part of the name, not padding: the two files must stay distinct. */
    @Test
    void spacePrefixedFileName_readsItsOwnFile() throws Exception {
        Files.writeString(project.resolve(" report.txt"), "spaced");
        Files.writeString(project.resolve("report.txt"), "plain");

        // projectWritable(true) is the path that routes through ProjectAwareOverlay's keys.
        AbstractFilesystem fs = filesystem(true);
        assertEquals("spaced", fs.read(alice(), " report.txt", 0, 100).fileData().content());
        assertEquals("plain", fs.read(alice(), "report.txt", 0, 100).fileData().content());

        assertTrue(fs.write(alice(), " report.txt", "written").isSuccess());
        assertEquals("written", fs.read(alice(), " report.txt", 0, 100).fileData().content());
        assertEquals("plain", fs.read(alice(), "report.txt", 0, 100).fileData().content());
    }

    /** The layers spell one entry differently; it must still be listed once, from the upper copy. */
    @Test
    void ls_listsAnEntryOnceAndPrefersTheUpperCopy() throws Exception {
        Files.writeString(project.resolve("shared.md"), "from-project");
        Files.createDirectories(workspace.resolve("alice"));
        Files.writeString(workspace.resolve("alice/shared.md"), "from-workspace");

        LsResult listed = filesystem(false).ls(alice(), "");
        assertTrue(listed.isSuccess(), listed::error);
        List<FileInfo> shared =
                listed.entries().stream()
                        .filter(entry -> "shared.md".equals(FilesystemUtils.entryKey(entry.path())))
                        .toList();
        assertEquals(1, shared.size(), () -> "listed once per spelling: " + listed.entries());
        assertEquals(
                "from-workspace".length(),
                shared.get(0).size(),
                "the workspace copy has to win the merge");
    }
}
