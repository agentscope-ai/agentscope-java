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
package io.agentscope.harness.agent.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.testing.HarnessQuiescence;
import io.agentscope.harness.agent.tool.MemoryGetTool;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * End-to-end test: default Mode 3 ({@code LocalFilesystemWithShell}) with per-user namespace
 * isolation.
 *
 * <p>When a {@code userId} is set in the {@link RuntimeContext}, the default HarnessAgent builder
 * (no explicit {@code abstractFilesystem(...)}) creates a {@code LocalFilesystemWithShell} with a
 * {@code NamespaceFactory} that prefixes all paths with the userId. This test verifies:
 *
 * <ul>
 *   <li>Runtime data (MEMORY.md, memory/, sessions, tasks) is written under
 *       {@code workspace/<userId>/}.</li>
 *   <li>Different users see isolated data.</li>
 *   <li>No duplicate data at the workspace root (no un-namespaced {@code workspace/agents/}).</li>
 *   <li>Glob/ls/grep return round-trippable paths (no double namespace on read).</li>
 * </ul>
 */
@HarnessQuiescence
class LocalFilesystemUserIsolationExampleTest {

    @TempDir Path workspace;

    /**
     * Writes MEMORY.md as alice, verifies it lands under {@code workspace/alice/MEMORY.md},
     * and that bob's namespace is empty.
     */
    @Test
    void memoryIsolation_aliceAndBobSeeOwnData() throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("AGENTS.md"), "# Test Agent\n");

        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(stubModel("done"))
                        .workspace(workspace)
                        .build()) {

            // Alice writes MEMORY.md
            agent.call(userMsg("alice here"), ctx("s1", "alice")).block();
            agent.getWorkspaceManager()
                    .writeUtf8WorkspaceRelative(
                            RuntimeContext.builder().userId("alice").build(),
                            "MEMORY.md",
                            "alice's notes");

            // Verify alice's data is under workspace/alice/
            Path aliceMemory = workspace.resolve("alice/MEMORY.md");
            assertTrue(
                    Files.isRegularFile(aliceMemory), "MEMORY.md should be under alice/ namespace");
            assertEquals("alice's notes", Files.readString(aliceMemory, StandardCharsets.UTF_8));

            // Verify no MEMORY.md at workspace root
            assertFalse(
                    Files.isRegularFile(workspace.resolve("MEMORY.md")),
                    "MEMORY.md should NOT exist at workspace root when userId isolation is active");

            // Verify bob's namespace is empty
            assertFalse(
                    Files.exists(workspace.resolve("bob/MEMORY.md")),
                    "bob's namespace should be empty");
        }
    }

    /**
     * Verifies glob results round-trip correctly: glob → read → delete.
     * The paths returned by glob must not include the namespace prefix, so that
     * passing them to read/delete re-applies the prefix correctly.
     */
    @Test
    void globRoundTrip_pathsAreRoundTrippable() throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("AGENTS.md"), "# Test\n");

        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(stubModel("done"))
                        .workspace(workspace)
                        .build()) {

            // Write files as alice
            agent.call(userMsg("hi"), ctx("s1", "alice")).block();
            AbstractFilesystem fs = agent.getWorkspaceManager().getFilesystem();
            RuntimeContext rt = RuntimeContext.builder().userId("alice").build();

            fs.uploadFiles(
                    rt,
                    List.of(
                            Map.entry("MEMORY.md", "notes".getBytes(StandardCharsets.UTF_8)),
                            Map.entry(
                                    "memory/2024-01-01.md",
                                    "daily".getBytes(StandardCharsets.UTF_8)),
                            Map.entry(
                                    "docs/readme.txt", "hello".getBytes(StandardCharsets.UTF_8))));

            // Glob for *.md at root — should find MEMORY.md, memory/2024-01-01.md
            GlobResult glob = fs.glob(rt, "*.md", "/");
            assertTrue(glob.isSuccess());
            assertFalse(glob.matches().isEmpty(), "glob should find .md files");

            for (FileInfo fi : glob.matches()) {
                String path = fi.path();
                // Path should NOT contain the namespace prefix "alice/"
                assertFalse(
                        path.startsWith("alice/"),
                        "glob result path should not contain namespace prefix: " + path);

                // Round-trip: read the path returned by glob
                ReadResult read = fs.read(rt, path, 0, 0);
                assertTrue(
                        read.isSuccess(), "read() should succeed with glob-returned path: " + path);
                assertNotNull(read.fileData(), "fileData should not be null for: " + path);
            }

            // Glob in a subdirectory
            GlobResult memGlob = fs.glob(rt, "*.md", "memory/");
            assertTrue(memGlob.isSuccess());
            assertFalse(memGlob.matches().isEmpty(), "glob in memory/ should find files");
            for (FileInfo fi : memGlob.matches()) {
                ReadResult read = fs.read(rt, fi.path(), 0, 0);
                assertTrue(read.isSuccess(), "read() should succeed for: " + fi.path());
            }
        }
    }

    /**
     * Verifies grep results round-trip correctly.
     */
    @Test
    void grepRoundTrip_pathsAreRoundTrippable() throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("AGENTS.md"), "# Test\n");

        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(stubModel("done"))
                        .workspace(workspace)
                        .build()) {

            agent.call(userMsg("hi"), ctx("s1", "alice")).block();
            AbstractFilesystem fs = agent.getWorkspaceManager().getFilesystem();
            RuntimeContext rt = RuntimeContext.empty();

            fs.uploadFiles(
                    rt,
                    List.of(
                            Map.entry(
                                    "memory/notes.md",
                                    "important finding".getBytes(StandardCharsets.UTF_8))));

            GrepResult grep = fs.grep(rt, "important", ".", "*.md");
            assertTrue(grep.isSuccess());
            assertFalse(grep.matches().isEmpty(), "grep should find the pattern");

            for (var match : grep.matches()) {
                String path = match.path();
                assertFalse(
                        path.startsWith("alice/"),
                        "grep result path should not contain namespace prefix: " + path);

                ReadResult read = fs.read(rt, path, 0, 0);
                assertTrue(
                        read.isSuccess(), "read() should succeed for grep-returned path: " + path);
            }
        }
    }

    /**
     * Verifies ls results round-trip correctly.
     */
    @Test
    void lsRoundTrip_pathsAreRoundTrippable() throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("AGENTS.md"), "# Test\n");

        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(stubModel("done"))
                        .workspace(workspace)
                        .build()) {

            agent.call(userMsg("hi"), ctx("s1", "alice")).block();
            AbstractFilesystem fs = agent.getWorkspaceManager().getFilesystem();
            RuntimeContext rt = RuntimeContext.empty();

            fs.uploadFiles(
                    rt,
                    List.of(
                            Map.entry("docs/a.txt", "aaa".getBytes(StandardCharsets.UTF_8)),
                            Map.entry("docs/b.txt", "bbb".getBytes(StandardCharsets.UTF_8))));

            var ls = fs.ls(rt, "docs");
            assertTrue(ls.isSuccess());
            assertFalse(ls.entries().isEmpty(), "ls should list files in docs/");

            for (FileInfo fi : ls.entries()) {
                String path = fi.path();
                assertFalse(
                        path.startsWith("alice/"),
                        "ls result path should not contain namespace prefix: " + path);

                if (!path.endsWith("/")) {
                    ReadResult read = fs.read(rt, path, 0, 0);
                    assertTrue(
                            read.isSuccess(),
                            "read() should succeed for ls-returned path: " + path);
                }
            }
        }
    }

    /**
     * Verifies session data is written under the user's namespace, not at workspace root.
     */
    @Test
    void sessionData_writtenUnderUserNamespace() throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("AGENTS.md"), "# Test\n");

        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(stubModel("done"))
                        .workspace(workspace)
                        .build()) {

            agent.call(userMsg("conversation start"), ctx("session-1", "alice")).block();

            // Write session data through the workspace manager
            agent.getWorkspaceManager()
                    .writeUtf8WorkspaceRelative(
                            RuntimeContext.builder().userId("alice").build(),
                            "agents/assistant/sessions/session-1.jsonl",
                            "session data");

            // Verify session data is under alice's namespace
            Path aliceSessionFile =
                    workspace.resolve("alice/agents/assistant/sessions/session-1.jsonl");
            assertTrue(
                    Files.isRegularFile(aliceSessionFile),
                    "AgentStateStore file should be under alice/ namespace");

            // Verify no session file at workspace root
            Path rootSessionFile = workspace.resolve("agents/assistant/sessions/session-1.jsonl");
            assertFalse(
                    Files.isRegularFile(rootSessionFile),
                    "AgentStateStore file should NOT exist at workspace root");
        }
    }

    /**
     * Verifies getSessionDir returns a namespace-aware path.
     */
    @Test
    void sessionDir_isNamespaceAware() throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("AGENTS.md"), "# Test\n");

        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(stubModel("done"))
                        .workspace(workspace)
                        .build()) {

            agent.call(userMsg("hi"), ctx("s1", "alice")).block();

            Path sessionDir =
                    agent.getWorkspaceManager()
                            .getSessionDir(
                                    RuntimeContext.builder().userId("alice").build(), "assistant");
            assertTrue(
                    sessionDir.toString().contains("alice"),
                    "getSessionDir should include namespace: " + sessionDir);
        }
    }

    /**
     * Verifies getMemoryDir returns a namespace-aware path.
     */
    @Test
    void memoryDir_isNamespaceAware() throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("AGENTS.md"), "# Test\n");

        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(stubModel("done"))
                        .workspace(workspace)
                        .build()) {

            agent.call(userMsg("hi"), ctx("s1", "alice")).block();

            Path memDir =
                    agent.getWorkspaceManager()
                            .getMemoryDir(RuntimeContext.builder().userId("alice").build());
            assertTrue(
                    memDir.toString().contains("alice"),
                    "getMemoryDir should include namespace: " + memDir);
        }
    }

    /**
     * Verifies that no un-namespaced duplicate user data appears at workspace root.
     *
     * <p>The default {@code AgentStateStore} is now {@code JsonFileAgentStateStore} rooted at
     * {@code ~/.agentscope/state/<agentId>/}, so {@code agent_state.json} no longer lives inside
     * the workspace. Orphan-sweep coordination uses an in-process {@code PeriodicGate}, so
     * {@code workspace/agents/} should contain no regular files after a single-user call.
     */
    @Test
    void noDuplicateDataAtWorkspaceRoot() throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("AGENTS.md"), "# Test\n");

        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(stubModel("done"))
                        .workspace(workspace)
                        .build()) {

            agent.call(userMsg("hi"), ctx("s1", "alice")).block();

            // Write memory and session data
            agent.getWorkspaceManager()
                    .writeUtf8WorkspaceRelative(
                            RuntimeContext.builder().userId("alice").build(),
                            "MEMORY.md",
                            "memory content");
            agent.getWorkspaceManager()
                    .writeUtf8WorkspaceRelative(
                            RuntimeContext.builder().userId("alice").build(),
                            "memory/note.md",
                            "note");

            // Only AGENTS.md should exist at workspace root (it's a shared config file,
            // pre-existing). Namespace-isolated runtime data should be under alice/.
            try (Stream<Path> rootEntries = Files.list(workspace)) {
                List<String> rootNames =
                        rootEntries
                                .map(p -> p.getFileName().toString())
                                .filter(n -> !n.equals("AGENTS.md") && !n.equals("alice"))
                                .toList();
                assertTrue(
                        rootNames.isEmpty(),
                        "Only AGENTS.md and alice/ should exist at workspace root, but found: "
                                + rootNames);
            }

            // agent_state.json no longer lives inside the workspace (moved to
            // ~/.agentscope/state/<agentId>/), so any regular file under agents/ is a regression.
            Path rootAgents = workspace.resolve("agents");
            if (Files.isDirectory(rootAgents)) {
                try (Stream<Path> walk = Files.walk(rootAgents)) {
                    List<String> unexpected =
                            walk.filter(Files::isRegularFile)
                                    .map(
                                            p ->
                                                    rootAgents
                                                            .relativize(p)
                                                            .toString()
                                                            .replace('\\', '/'))
                                    .toList();
                    assertTrue(
                            unexpected.isEmpty(),
                            "workspace/agents/ should contain no regular files but found: "
                                    + unexpected);
                }
            }
        }
    }

    /**
     * Reads are anchored to the caller's namespace too: naming a sibling user's directory must
     * not resolve to that user's file, neither through {@code memory_get} nor through the
     * workspace manager's read path.
     */
    @Test
    void memoryRead_doesNotCrossIntoSiblingNamespace() throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("AGENTS.md"), "# Test\n");

        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(stubModel("done"))
                        // The async memory pipeline (flush + consolidation) reuses the stub
                        // model, so its fixed "done" response would land in MEMORY.md and
                        // race these namespace assertions. These tests target isolation,
                        // not flush behaviour.
                        .disableMemoryHooks()
                        .workspace(workspace)
                        .build()) {

            agent.call(userMsg("hi"), ctx("s1", "alice")).block();
            WorkspaceManager wm = agent.getWorkspaceManager();
            wm.writeUtf8WorkspaceRelative(ctx("s1", "alice"), "MEMORY.md", "alice-private-note");

            assertEquals("alice-private-note", wm.readMemoryMd(ctx("s1", "alice")));
            assertEquals("", wm.readMemoryMd(ctx("s2", "bob")));
            assertEquals("", wm.readMemoryFileUtf8(ctx("s2", "bob"), "alice/MEMORY.md"));
            // The './' spelling reaches the same file once normalized.
            assertEquals("", wm.readMemoryFileUtf8(ctx("s2", "bob"), "./alice/MEMORY.md"));

            ToolResultBlock viaTool =
                    new MemoryGetTool(wm).memoryGet(ctx("s2", "bob"), "alice/MEMORY.md", 1, 5);
            ToolResultBlock dotted =
                    new MemoryGetTool(wm).memoryGet(ctx("s2", "bob"), "./alice/MEMORY.md", 1, 5);
            // The manager's normalization ignores leading whitespace and slashes, so this is
            // still a '..' traversal — it must fail as a tool error, not as a failed turn.
            ToolResultBlock padded =
                    new MemoryGetTool(wm).memoryGet(ctx("s2", "bob"), "  ../alice/MEMORY.md", 1, 5);
            // Every spelling fails as a tool error and never echoes the sibling's content.
            for (ToolResultBlock blocked : List.of(viaTool, dotted, padded)) {
                assertEquals(
                        ToolResultState.ERROR,
                        blocked.getState(),
                        "memory_get must not read across the namespace boundary");
                assertFalse(
                        toolErrorText(blocked).contains("alice-private-note"),
                        "memory_get must not leak alice's content in its output");
            }
        }
    }

    /**
     * A root-level {@code MEMORY.md} is either a pre-namespace leftover or an explicitly shared
     * file. An identified caller must not be served it — {@code memory_search} cannot see it
     * either, so reading it would advertise content the tool surface cannot find again — while
     * an identity-less read keeps the historical single-tenant behaviour.
     */
    @Test
    void memoryRead_ignoresUnnamespacedRootCopyForIdentifiedCaller() throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("AGENTS.md"), "# Test\n");
        Files.writeString(workspace.resolve("MEMORY.md"), "legacy-root-note");

        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(stubModel("done"))
                        // Same as above: keep the async flush/consolidation out of the race.
                        .disableMemoryHooks()
                        .workspace(workspace)
                        .build()) {

            agent.call(userMsg("hi"), ctx("s1", "alice")).block();
            WorkspaceManager wm = agent.getWorkspaceManager();

            assertEquals("", wm.readMemoryMd(ctx("s1", "alice")));
            assertEquals("", wm.readMemoryMd(ctx("s2", "bob")));
            assertFalse(
                    wm.listMemoryFilePaths(ctx("s1", "alice")).contains("MEMORY.md"),
                    "the root-level leftover must not be listed for an identified caller");
            assertEquals("legacy-root-note", wm.readMemoryMd(RuntimeContext.empty()));

            wm.writeUtf8WorkspaceRelative(ctx("s1", "alice"), "MEMORY.md", "alice-fact");
            assertEquals("alice-fact", wm.readMemoryMd(ctx("s1", "alice")));
            assertEquals("", wm.readMemoryMd(ctx("s2", "bob")));
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static RuntimeContext ctx(String sessionId, String userId) {
        return RuntimeContext.builder().sessionId(sessionId).userId(userId).build();
    }

    private static Msg userMsg(String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    /** First text block of a tool result, or the empty string. */
    private static String toolErrorText(ToolResultBlock result) {
        return result.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .findFirst()
                .orElse("");
    }

    private static Model stubModel(String text) {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
        ChatResponse chunk =
                new ChatResponse(
                        "stub-id",
                        List.of(TextBlock.builder().text(text).build()),
                        null,
                        Map.of(),
                        "stop");
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(chunk));
        return model;
    }
}
