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
package io.agentscope.harness.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.skill.WorkspaceSkillRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillManageToolTest {

    @TempDir Path workspace;

    private LocalFilesystem fs;
    private WorkspaceSkillRepository mainRepo;
    private WorkspaceSkillRepository draftsRepo;
    private SkillManageTool toolDraftDefault;
    private SkillManageTool toolAutoPromote;

    @BeforeEach
    void setUp() {
        fs = new LocalFilesystem(workspace);
        mainRepo = new WorkspaceSkillRepository(fs, "skills", RuntimeContext::empty, "main");
        draftsRepo =
                new WorkspaceSkillRepository(fs, "skills/_drafts", RuntimeContext::empty, "drafts");
        toolDraftDefault = new SkillManageTool(mainRepo, draftsRepo, SkillManageConfig.defaults());
        toolAutoPromote =
                new SkillManageTool(
                        mainRepo,
                        draftsRepo,
                        SkillManageConfig.builder().autoPromote(true).build());
    }

    // ---- helpers ----

    private static ToolCallParam paramOf(Map<String, Object> input) {
        return ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder().id("tc-1").name("skill_manage").build())
                .input(input)
                .build();
    }

    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String text(ToolResultBlock r) {
        StringBuilder sb = new StringBuilder();
        if (r.getOutput() == null) {
            return "";
        }
        r.getOutput()
                .forEach(
                        b -> {
                            if (b instanceof TextBlock t) {
                                sb.append(t.getText());
                            }
                        });
        return sb.toString();
    }

    private static String validSkillMd(String name, String desc) {
        return "---\nname: " + name + "\ndescription: " + desc + "\n---\n# " + name + "\nBody.\n";
    }

    // ---- schema / name validation ----

    @Test
    void schemaHasRequiredFields() {
        Map<String, Object> schema = toolDraftDefault.getParameters();
        assertEquals("object", schema.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertNotNull(props);
        assertTrue(props.containsKey("action"));
        assertTrue(props.containsKey("name"));
        assertTrue(props.containsKey("content"));
        assertTrue(props.containsKey("file_path"));
    }

    @Test
    void rejectsInvalidName() {
        ToolResultBlock r =
                toolDraftDefault
                        .callAsync(paramOf(args("action", "create", "name", "BadName!")))
                        .block();
        assertNotNull(r);
        assertTrue(text(r).startsWith("Error:"));
    }

    // ---- create ----

    @Test
    void createInDraftMode_landsInDraftsDir() {
        ToolResultBlock r =
                toolDraftDefault
                        .callAsync(
                                paramOf(
                                        args(
                                                "action", "create",
                                                "name", "csv-sum",
                                                "content",
                                                        validSkillMd(
                                                                "csv-sum", "Sum a CSV column."))))
                        .block();
        assertNotNull(r);
        assertFalse(text(r).startsWith("Error:"), "Expected success, got: " + text(r));

        // Lives under _drafts/, NOT under skills/<name>/
        assertTrue(Files.isRegularFile(workspace.resolve("skills/_drafts/csv-sum/SKILL.md")));
        assertFalse(Files.exists(workspace.resolve("skills/csv-sum/SKILL.md")));
    }

    @Test
    void createInAutoPromoteMode_landsInMainDir() {
        ToolResultBlock r =
                toolAutoPromote
                        .callAsync(
                                paramOf(
                                        args(
                                                "action", "create",
                                                "name", "csv-sum",
                                                "content",
                                                        validSkillMd(
                                                                "csv-sum", "Sum a CSV column."))))
                        .block();
        assertFalse(text(r).startsWith("Error:"), text(r));

        assertTrue(Files.isRegularFile(workspace.resolve("skills/csv-sum/SKILL.md")));
        assertFalse(Files.exists(workspace.resolve("skills/_drafts/csv-sum/SKILL.md")));
    }

    @Test
    void createRejectsDuplicate() {
        toolDraftDefault
                .callAsync(
                        paramOf(
                                args(
                                        "action", "create",
                                        "name", "dup",
                                        "content", validSkillMd("dup", "First."))))
                .block();
        ToolResultBlock r =
                toolDraftDefault
                        .callAsync(
                                paramOf(
                                        args(
                                                "action", "create",
                                                "name", "dup",
                                                "content", validSkillMd("dup", "Second."))))
                        .block();
        assertTrue(text(r).startsWith("Error:"));
        assertTrue(text(r).contains("already exists"));
    }

    @Test
    void createRejectsFrontmatterNameMismatch() {
        ToolResultBlock r =
                toolDraftDefault
                        .callAsync(
                                paramOf(
                                        args(
                                                "action", "create",
                                                "name", "wanted-name",
                                                "content",
                                                        validSkillMd("other-name", "Wrong name."))))
                        .block();
        assertTrue(text(r).startsWith("Error:"));
        assertTrue(text(r).contains("frontmatter"));
    }

    @Test
    void createRejectsMissingFrontmatter() {
        ToolResultBlock r =
                toolDraftDefault
                        .callAsync(
                                paramOf(
                                        args(
                                                "action", "create",
                                                "name", "no-fm",
                                                "content", "# No frontmatter here\n")))
                        .block();
        assertTrue(text(r).startsWith("Error:"));
    }

    // ---- edit ----

    @Test
    void editReplacesExistingSkillMd() {
        toolDraftDefault
                .callAsync(
                        paramOf(
                                args(
                                        "action", "create",
                                        "name", "evolve",
                                        "content", validSkillMd("evolve", "Initial."))))
                .block();
        String newMd = "---\nname: evolve\ndescription: Updated.\n---\n# Evolved\nNew body.\n";
        ToolResultBlock r =
                toolDraftDefault
                        .callAsync(
                                paramOf(
                                        args(
                                                "action", "edit",
                                                "name", "evolve",
                                                "content", newMd)))
                        .block();
        assertFalse(text(r).startsWith("Error:"), text(r));
        var loaded = draftsRepo.getSkill("evolve");
        assertEquals("Updated.", loaded.getDescription());
        assertTrue(loaded.getSkillContent().contains("Evolved"));
    }

    // ---- patch ----

    @Test
    void patchSucceedsOnUniqueMatch() {
        toolDraftDefault
                .callAsync(
                        paramOf(
                                args(
                                        "action", "create",
                                        "name", "patchme",
                                        "content", validSkillMd("patchme", "Patch target."))))
                .block();
        ToolResultBlock r =
                toolDraftDefault
                        .callAsync(
                                paramOf(
                                        args(
                                                "action", "patch",
                                                "name", "patchme",
                                                "old_string", "Body.",
                                                "new_string", "Body v2.")))
                        .block();
        assertFalse(text(r).startsWith("Error:"), text(r));
        var loaded = draftsRepo.getSkill("patchme");
        assertTrue(loaded.getSkillContent().contains("Body v2."));
    }

    @Test
    void patchFailsOnAmbiguousMatch() {
        String content = "---\nname: amb\ndescription: Has duplicates.\n---\n# Amb\nFoo\nFoo\n";
        toolDraftDefault
                .callAsync(
                        paramOf(
                                args(
                                        "action", "create",
                                        "name", "amb",
                                        "content", content)))
                .block();
        ToolResultBlock r =
                toolDraftDefault
                        .callAsync(
                                paramOf(
                                        args(
                                                "action", "patch",
                                                "name", "amb",
                                                "old_string", "Foo",
                                                "new_string", "Bar")))
                        .block();
        assertTrue(text(r).startsWith("Error:"));
        assertTrue(text(r).contains("not unique"));
    }

    @Test
    void patchReplaceAllSucceedsOnAmbiguous() {
        String content = "---\nname: ramb\ndescription: Has duplicates.\n---\n# Ramb\nFoo\nFoo\n";
        toolDraftDefault
                .callAsync(
                        paramOf(
                                args(
                                        "action", "create",
                                        "name", "ramb",
                                        "content", content)))
                .block();
        ToolResultBlock r =
                toolDraftDefault
                        .callAsync(
                                paramOf(
                                        args(
                                                "action", "patch",
                                                "name", "ramb",
                                                "old_string", "Foo",
                                                "new_string", "Bar",
                                                "replace_all", Boolean.TRUE)))
                        .block();
        assertFalse(text(r).startsWith("Error:"), text(r));
        assertTrue(text(r).contains("2 replacement"));
    }

    // ---- write_file / remove_file ----

    @Test
    void writeFileSucceedsForAllowedSubdir() {
        toolDraftDefault
                .callAsync(
                        paramOf(
                                args(
                                        "action", "create",
                                        "name", "wf",
                                        "content", validSkillMd("wf", "Write file."))))
                .block();
        ToolResultBlock r =
                toolDraftDefault
                        .callAsync(
                                paramOf(
                                        args(
                                                "action", "write_file",
                                                "name", "wf",
                                                "file_path", "scripts/run.sh",
                                                "file_content", "#!/bin/sh\necho hi\n")))
                        .block();
        assertFalse(text(r).startsWith("Error:"), text(r));
        assertTrue(Files.isRegularFile(workspace.resolve("skills/_drafts/wf/scripts/run.sh")));
    }

    @Test
    void writeFileRejectsDisallowedSubdir() {
        toolDraftDefault
                .callAsync(
                        paramOf(
                                args(
                                        "action", "create",
                                        "name", "wf2",
                                        "content", validSkillMd("wf2", "Reject."))))
                .block();
        ToolResultBlock r =
                toolDraftDefault
                        .callAsync(
                                paramOf(
                                        args(
                                                "action", "write_file",
                                                "name", "wf2",
                                                "file_path", "secret/leak.sh",
                                                "file_content", "x")))
                        .block();
        assertTrue(text(r).startsWith("Error:"));
    }

    @Test
    void writeFileRejectsPathTraversal() {
        toolDraftDefault
                .callAsync(
                        paramOf(
                                args(
                                        "action", "create",
                                        "name", "trav",
                                        "content", validSkillMd("trav", "Traversal."))))
                .block();
        ToolResultBlock r =
                toolDraftDefault
                        .callAsync(
                                paramOf(
                                        args(
                                                "action", "write_file",
                                                "name", "trav",
                                                "file_path", "scripts/../../etc/passwd",
                                                "file_content", "x")))
                        .block();
        assertTrue(text(r).startsWith("Error:"));
    }

    @Test
    void removeFileSucceedsAfterWrite() {
        toolDraftDefault
                .callAsync(
                        paramOf(
                                args(
                                        "action", "create",
                                        "name", "rmf",
                                        "content", validSkillMd("rmf", "Remove file."))))
                .block();
        toolDraftDefault
                .callAsync(
                        paramOf(
                                args(
                                        "action", "write_file",
                                        "name", "rmf",
                                        "file_path", "references/note.md",
                                        "file_content", "note")))
                .block();
        ToolResultBlock r =
                toolDraftDefault
                        .callAsync(
                                paramOf(
                                        args(
                                                "action", "remove_file",
                                                "name", "rmf",
                                                "file_path", "references/note.md")))
                        .block();
        assertFalse(text(r).startsWith("Error:"), text(r));
        assertFalse(Files.exists(workspace.resolve("skills/_drafts/rmf/references/note.md")));
    }

    // ---- delete ----

    @Test
    void deleteArchivesAndKeepsRoot() {
        toolDraftDefault
                .callAsync(
                        paramOf(
                                args(
                                        "action", "create",
                                        "name", "del",
                                        "content", validSkillMd("del", "Delete me."))))
                .block();
        ToolResultBlock r =
                toolDraftDefault
                        .callAsync(paramOf(args("action", "delete", "name", "del")))
                        .block();
        assertFalse(text(r).startsWith("Error:"), text(r));
        assertFalse(Files.exists(workspace.resolve("skills/_drafts/del")));
        assertTrue(Files.isDirectory(workspace.resolve("skills/_drafts/.archive")));
    }

    @Test
    void deleteWithAbsorbedIntoNonExistentTargetFails() {
        toolDraftDefault
                .callAsync(
                        paramOf(
                                args(
                                        "action", "create",
                                        "name", "delx",
                                        "content", validSkillMd("delx", "X."))))
                .block();
        ToolResultBlock r =
                toolDraftDefault
                        .callAsync(
                                paramOf(
                                        args(
                                                "action", "delete",
                                                "name", "delx",
                                                "absorbed_into", "ghost-umbrella")))
                        .block();
        assertTrue(text(r).startsWith("Error:"));
        assertTrue(text(r).contains("does not exist"));
    }

    @Test
    void deleteWithEmptyAbsorbedIntoMeansPruning() {
        toolDraftDefault
                .callAsync(
                        paramOf(
                                args(
                                        "action", "create",
                                        "name", "prune",
                                        "content", validSkillMd("prune", "Prune."))))
                .block();
        ToolResultBlock r =
                toolDraftDefault
                        .callAsync(
                                paramOf(
                                        args(
                                                "action", "delete",
                                                "name", "prune",
                                                "absorbed_into", "")))
                        .block();
        assertFalse(text(r).startsWith("Error:"), text(r));
        assertTrue(text(r).contains("pruning"));
    }

    // ---- sidecar integration (telemetry) ----

    @org.junit.jupiter.api.Nested
    class SidecarIntegration {
        private io.agentscope.harness.agent.skill.curator.SkillUsageStore store;
        private SkillManageTool toolWithStore;
        private SkillManageTool toolAutoPromoteWithStore;

        @BeforeEach
        void setUpStore() {
            store = new io.agentscope.harness.agent.skill.curator.SkillUsageStore(fs);
            toolWithStore =
                    new SkillManageTool(mainRepo, draftsRepo, SkillManageConfig.defaults(), store);
            toolAutoPromoteWithStore =
                    new SkillManageTool(
                            mainRepo,
                            draftsRepo,
                            SkillManageConfig.builder().autoPromote(true).build(),
                            store);
        }

        @org.junit.jupiter.api.Test
        void createInDraft_recordsAgentDraftProvenance() {
            toolWithStore
                    .callAsync(
                            paramOf(
                                    args(
                                            "action", "create",
                                            "name", "csv-sum",
                                            "content", validSkillMd("csv-sum", "Sum csv."))))
                    .block();

            var rec = store.get("csv-sum").orElseThrow();
            assertEquals("agent-draft", rec.createdBy());
            assertEquals(
                    io.agentscope.harness.agent.skill.curator.SkillUsageRecord.State.DRAFT,
                    rec.state());
        }

        @org.junit.jupiter.api.Test
        void createInAutoPromote_recordsAgentProvenance() {
            toolAutoPromoteWithStore
                    .callAsync(
                            paramOf(
                                    args(
                                            "action", "create",
                                            "name", "csv-auto",
                                            "content", validSkillMd("csv-auto", "Auto."))))
                    .block();

            var rec = store.get("csv-auto").orElseThrow();
            assertEquals("agent", rec.createdBy());
            assertEquals(
                    io.agentscope.harness.agent.skill.curator.SkillUsageRecord.State.ACTIVE,
                    rec.state());
            assertNotNull(rec.promotedAt());
        }

        @org.junit.jupiter.api.Test
        void patchBumpsPatchCounter() {
            toolWithStore
                    .callAsync(
                            paramOf(
                                    args(
                                            "action", "create",
                                            "name", "p",
                                            "content", validSkillMd("p", "Patch."))))
                    .block();
            toolWithStore
                    .callAsync(
                            paramOf(
                                    args(
                                            "action", "patch",
                                            "name", "p",
                                            "old_string", "Body.",
                                            "new_string", "Body v2.")))
                    .block();

            assertEquals(1, store.get("p").orElseThrow().patchCount());
        }

        @org.junit.jupiter.api.Test
        void deleteSetsArchivedState() {
            toolWithStore
                    .callAsync(
                            paramOf(
                                    args(
                                            "action", "create",
                                            "name", "z",
                                            "content", validSkillMd("z", "Bye."))))
                    .block();
            toolWithStore.callAsync(paramOf(args("action", "delete", "name", "z"))).block();

            var rec = store.get("z").orElseThrow();
            assertEquals(
                    io.agentscope.harness.agent.skill.curator.SkillUsageRecord.State.ARCHIVED,
                    rec.state());
            assertNotNull(rec.archivedAt());
        }
    }
}
