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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.skill.WorkspaceSkillRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression tests for issue #2688: {@code WorkspaceSkillRepository} resolved its context from the
 * agent's single shared "active" RuntimeContext supplier. Under concurrent calls (or after the
 * owning call completed) that supplier returns another user's context — or none at all — so
 * skill_manage operations wrote to, read from, or archived the wrong user's namespace.
 *
 * <p>Each test drives the tool with the per-call context carried on {@link ToolCallParam} (the
 * authoritative, concurrency-safe channel) while the legacy supplier is pinned to a different
 * user, which is exactly the state the racy supplier can be observed in on a shared agent
 * instance.
 */
class SkillManageToolCallScopedContextTest {

    @TempDir Path workspace;

    private LocalFilesystem fs;
    private AtomicReference<RuntimeContext> activeRc;
    private WorkspaceSkillRepository mainRepo;
    private WorkspaceSkillRepository draftsRepo;
    private SkillManageTool tool;

    @BeforeEach
    void setUp() {
        // Mirrors HarnessAgent wiring: USER isolation namespaces writes under <userId>/, and the
        // repository's legacy supplier reads the agent's single shared activeRc field.
        fs = new LocalFilesystem(workspace, false, 64, IsolationScope.USER.toNamespaceFactory());
        activeRc = new AtomicReference<>();
        mainRepo = new WorkspaceSkillRepository(fs, "skills", activeRc::get, "workspace-writable");
        draftsRepo =
                new WorkspaceSkillRepository(
                        fs, "skills/_drafts", activeRc::get, "workspace-drafts");
        tool =
                new SkillManageTool(
                        mainRepo,
                        draftsRepo,
                        SkillManageConfig.builder().autoPromote(true).build());
    }

    // ---- helpers ----

    private static RuntimeContext userCtx(String userId, String sessionId) {
        return RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
    }

    private static ToolCallParam param(Map<String, Object> input, RuntimeContext ctx) {
        return ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder().id("tc-1").name("skill_manage").build())
                .input(input)
                .runtimeContext(ctx)
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

    private ToolResultBlock call(Map<String, Object> input, RuntimeContext ctx) {
        return tool.callAsync(param(input, ctx)).block();
    }

    private static String skillMd(String name, String body) {
        return "---\nname: "
                + name
                + "\ndescription: Test skill "
                + name
                + "\n---\n# "
                + name
                + "\n"
                + body
                + "\n";
    }

    private void seedUserSkill(String userId, String name, String body) throws IOException {
        Files.createDirectories(workspace.resolve(userId + "/skills/" + name));
        Files.writeString(
                workspace.resolve(userId + "/skills/" + name + "/SKILL.md"), skillMd(name, body));
    }

    // ---- tests ----

    @Test
    void createWritesUnderToolCallUserEvenWhenSupplierPointsAtAnotherUser() throws IOException {
        RuntimeContext alice = userCtx("alice", "s-a");
        activeRc.set(userCtx("bob", "s-b")); // bob's concurrent call owns the shared supplier

        ToolResultBlock r =
                call(
                        args(
                                "action",
                                "create",
                                "name",
                                "demo",
                                "content",
                                skillMd("demo", "Body.")),
                        alice);

        assertFalse(text(r).startsWith("Error:"), () -> "create failed: " + text(r));
        assertTrue(
                Files.exists(workspace.resolve("alice/skills/demo/SKILL.md")),
                "skill must be created under the tool-call user's namespace");
        assertFalse(
                Files.exists(workspace.resolve("bob/skills/demo/SKILL.md")),
                "skill must NOT leak into another user's namespace");
        assertFalse(
                Files.exists(workspace.resolve("skills/demo/SKILL.md")),
                "skill must NOT escape user isolation into the shared root");
    }

    @Test
    void createInDraftsModeAlsoRoutesToToolCallUser() throws IOException {
        // autoPromote=false (default config): creates land in the drafts repo instead of main,
        // which is a second WorkspaceSkillRepository instance wired to the same racy supplier.
        SkillManageTool draftsTool =
                new SkillManageTool(mainRepo, draftsRepo, SkillManageConfig.defaults());
        RuntimeContext alice = userCtx("alice", "s-a");
        activeRc.set(userCtx("bob", "s-b"));

        ToolResultBlock r =
                draftsTool
                        .callAsync(
                                param(
                                        args(
                                                "action",
                                                "create",
                                                "name",
                                                "demo",
                                                "content",
                                                skillMd("demo", "Body.")),
                                        alice))
                        .block();

        assertFalse(text(r).startsWith("Error:"), () -> "create failed: " + text(r));
        assertTrue(
                Files.exists(workspace.resolve("alice/skills/_drafts/demo/SKILL.md")),
                "draft create must land under the tool-call user's namespace");
        assertFalse(
                Files.exists(workspace.resolve("bob/skills/_drafts/demo/SKILL.md")),
                "draft create must NOT leak into another user's namespace");
    }

    @Test
    void createStillRoutesToCallUserWhenSupplierContextIsCleared() throws IOException {
        // afterAgentExecution() clears the shared activeRc; detached/out-of-call code then sees an
        // empty context from the supplier. The tool-call context must still win.
        RuntimeContext alice = userCtx("alice", "s-a");
        activeRc.set(null);

        ToolResultBlock r =
                call(
                        args(
                                "action",
                                "create",
                                "name",
                                "demo",
                                "content",
                                skillMd("demo", "Body.")),
                        alice);

        assertFalse(text(r).startsWith("Error:"), () -> "create failed: " + text(r));
        assertTrue(Files.exists(workspace.resolve("alice/skills/demo/SKILL.md")));
        assertFalse(Files.exists(workspace.resolve("skills/demo/SKILL.md")));
    }

    @Test
    void deleteArchivesCallersSkillAndLeavesOtherUsersCopyIntact() throws IOException {
        seedUserSkill("alice", "shared-name", "Alice body.");
        seedUserSkill("bob", "shared-name", "Bob body.");
        RuntimeContext alice = userCtx("alice", "s-a");
        activeRc.set(userCtx("bob", "s-b"));

        ToolResultBlock r = call(args("action", "delete", "name", "shared-name"), alice);

        assertFalse(text(r).startsWith("Error:"), () -> "delete failed: " + text(r));
        assertFalse(
                Files.exists(workspace.resolve("alice/skills/shared-name/SKILL.md")),
                "the caller's own skill should be archived");
        assertTrue(
                Files.exists(workspace.resolve("bob/skills/shared-name/SKILL.md")),
                "another user's same-named skill must NOT be archived by this call");
    }

    @Test
    void patchReadsAndWritesTheCallersCopyOnly() throws IOException {
        seedUserSkill("alice", "demo", "Replace me.");
        seedUserSkill("bob", "demo", "Replace me.");
        RuntimeContext alice = userCtx("alice", "s-a");
        activeRc.set(userCtx("bob", "s-b"));

        ToolResultBlock r =
                call(
                        args(
                                "action",
                                "patch",
                                "name",
                                "demo",
                                "old_string",
                                "Replace me.",
                                "new_string",
                                "Patched."),
                        alice);

        assertFalse(text(r).startsWith("Error:"), () -> "patch failed: " + text(r));
        assertTrue(
                Files.readString(workspace.resolve("alice/skills/demo/SKILL.md"))
                        .contains("Patched."),
                "the caller's copy should be patched");
        assertFalse(
                Files.readString(workspace.resolve("bob/skills/demo/SKILL.md"))
                        .contains("Patched."),
                "another user's copy must stay untouched");
    }

    // ---- security-scan rollback paths ----
    //
    // Rollback restores the pre-write state, so final disk contents alone cannot prove which
    // namespace the (blocked) write and its rollback targeted. A recording filesystem pins down
    // the stronger invariant: every operation the action performed — including the rollback —
    // resolved against the tool-call user's context, never another user's.

    @Test
    void editRollbackNeverTouchesAnotherUsersNamespace() throws IOException {
        seedUserSkill("alice", "demo", "Alice body.");
        seedUserSkill("bob", "demo", "Bob body.");
        RecordingFilesystem recordingFs =
                new RecordingFilesystem(workspace, IsolationScope.USER.toNamespaceFactory());
        WorkspaceSkillRepository recMain =
                new WorkspaceSkillRepository(recordingFs, "skills", activeRc::get, "rec-main");
        SkillManageTool recTool =
                new SkillManageTool(
                        recMain, draftsRepo, SkillManageConfig.builder().autoPromote(true).build());

        RuntimeContext alice = userCtx("alice", "s-a");
        activeRc.set(userCtx("bob", "s-b"));
        recordingFs.recordings.clear();

        // DANGEROUS body ("rm -rf /" is a CRITICAL scanner rule) forces the blocked-write path.
        ToolResultBlock r =
                recTool.callAsync(
                                param(
                                        args(
                                                "action",
                                                "edit",
                                                "name",
                                                "demo",
                                                "content",
                                                skillMd("demo", "Run rm -rf / to reset.")),
                                        alice))
                        .block();

        assertTrue(text(r).startsWith("Error:"), "the DANGEROUS edit should be blocked");
        String aliceFile = Files.readString(workspace.resolve("alice/skills/demo/SKILL.md"));
        assertTrue(
                aliceFile.contains("Alice body.") && !aliceFile.contains("rm -rf"),
                "the caller's original content must be restored by the rollback");
        String bobFile = Files.readString(workspace.resolve("bob/skills/demo/SKILL.md"));
        assertTrue(
                bobFile.contains("Bob body.") && !bobFile.contains("rm -rf"),
                "another user's copy must stay untouched");
        assertFalse(
                recordingFs.recordings.isEmpty(),
                "the blocked edit (write + rollback) must have performed filesystem operations");
        assertTrue(
                recordingFs.recordings.stream().allMatch(u -> "alice".equals(u)),
                "every filesystem operation (including rollback) must use the tool-call user's"
                        + " context, but saw: "
                        + recordingFs.recordings);
    }

    @Test
    void writeFileRollbackNeverTouchesAnotherUsersNamespace() throws IOException {
        seedUserSkill("alice", "demo", "Alice body.");
        seedUserSkill("bob", "demo", "Bob body.");
        RecordingFilesystem recordingFs =
                new RecordingFilesystem(workspace, IsolationScope.USER.toNamespaceFactory());
        WorkspaceSkillRepository recMain =
                new WorkspaceSkillRepository(recordingFs, "skills", activeRc::get, "rec-main");
        SkillManageTool recTool =
                new SkillManageTool(
                        recMain, draftsRepo, SkillManageConfig.builder().autoPromote(true).build());

        RuntimeContext alice = userCtx("alice", "s-a");
        activeRc.set(userCtx("bob", "s-b"));
        recordingFs.recordings.clear();

        ToolResultBlock r =
                recTool.callAsync(
                                param(
                                        args(
                                                "action",
                                                "write_file",
                                                "name",
                                                "demo",
                                                "file_path",
                                                "references/notes.md",
                                                "file_content",
                                                "Run rm -rf / to reset."),
                                        alice))
                        .block();

        assertTrue(text(r).startsWith("Error:"), "the DANGEROUS write_file should be blocked");
        assertFalse(
                Files.exists(workspace.resolve("alice/skills/demo/references/notes.md")),
                "the rolled-back new file must be removed from the caller's namespace");
        assertFalse(
                recordingFs.recordings.isEmpty(),
                "the blocked write_file (write + rollback) must have performed filesystem"
                        + " operations");
        assertTrue(
                recordingFs.recordings.stream().allMatch(u -> "alice".equals(u)),
                "every filesystem operation (including rollback) must use the tool-call user's"
                        + " context, but saw: "
                        + recordingFs.recordings);
    }

    /** {@link LocalFilesystem} that records which user id every operation resolved against. */
    private static final class RecordingFilesystem extends LocalFilesystem {

        final List<String> recordings = new CopyOnWriteArrayList<>();

        RecordingFilesystem(Path root, NamespaceFactory namespaceFactory) {
            super(root, false, 64, namespaceFactory);
        }

        private void record(RuntimeContext rc) {
            recordings.add(rc != null && rc.getUserId() != null ? rc.getUserId() : "<empty>");
        }

        @Override
        public GlobResult glob(RuntimeContext runtimeContext, String pattern, String path) {
            record(runtimeContext);
            return super.glob(runtimeContext, pattern, path);
        }

        @Override
        public ReadResult read(
                RuntimeContext runtimeContext, String filePath, int offset, int limit) {
            record(runtimeContext);
            return super.read(runtimeContext, filePath, offset, limit);
        }

        @Override
        public List<FileUploadResponse> uploadFiles(
                RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
            record(runtimeContext);
            return super.uploadFiles(runtimeContext, files);
        }

        @Override
        public WriteResult delete(RuntimeContext runtimeContext, String path) {
            record(runtimeContext);
            return super.delete(runtimeContext, path);
        }

        @Override
        public WriteResult move(RuntimeContext runtimeContext, String fromPath, String toPath) {
            record(runtimeContext);
            return super.move(runtimeContext, fromPath, toPath);
        }
    }
}
