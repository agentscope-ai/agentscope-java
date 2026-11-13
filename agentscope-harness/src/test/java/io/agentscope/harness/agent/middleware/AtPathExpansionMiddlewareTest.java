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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * Verifies {@link AtPathExpansionMiddleware} in the default Local overlay mode: project-relative
 * and absolute references inside the allow-list get attached; off-policy references and bare
 * {@code @handle} tokens are left as plain text.
 */
class AtPathExpansionMiddlewareTest {

    @Test
    void expandsRelativePathFromProject(@TempDir Path project, @TempDir Path workspace)
            throws IOException {
        Files.writeString(project.resolve("README.md"), "PROJECT_README", StandardCharsets.UTF_8);

        WorkspaceManager wm = workspaceManagerFor(project, workspace);
        AtPathExpansionMiddleware mw = new AtPathExpansionMiddleware(wm);
        Msg user = userMsg("Please look at @./README.md");

        List<Msg> result = runOnAgent(mw, user);
        String expanded = result.get(0).getTextContent();
        assertTrue(
                expanded.contains("<attached_file path=\"./README.md\">"),
                () -> "attached_file tag missing: " + expanded);
        assertTrue(expanded.contains("PROJECT_README"));
    }

    @Test
    void expandsAbsolutePathInsidePolicy(@TempDir Path project, @TempDir Path workspace)
            throws IOException {
        Path file = project.resolve("notes.txt");
        Files.writeString(file, "INSIDE", StandardCharsets.UTF_8);

        WorkspaceManager wm = workspaceManagerFor(project, workspace);
        AtPathExpansionMiddleware mw = new AtPathExpansionMiddleware(wm);
        Msg user = userMsg("Read @" + file);

        List<Msg> result = runOnAgent(mw, user);
        String expanded = result.get(0).getTextContent();
        assertTrue(expanded.contains("INSIDE"));
        assertTrue(expanded.contains("<attached_file path=\"" + file + "\">"));
    }

    @Test
    void leavesOffPolicyAbsolutePathUntouched(
            @TempDir Path project, @TempDir Path workspace, @TempDir Path forbidden)
            throws IOException {
        Path secret = forbidden.resolve("secret.txt");
        Files.writeString(secret, "LEAK", StandardCharsets.UTF_8);

        WorkspaceManager wm = workspaceManagerFor(project, workspace);
        AtPathExpansionMiddleware mw = new AtPathExpansionMiddleware(wm);
        Msg user = userMsg("@" + secret + " please");

        List<Msg> result = runOnAgent(mw, user);
        String expanded = result.get(0).getTextContent();
        assertFalse(expanded.contains("LEAK"), () -> "off-policy file leaked: " + expanded);
        assertFalse(expanded.contains("<attached_file"));
        // Original message text preserved
        assertTrue(expanded.contains("@" + secret));
    }

    @Test
    void ignoresBareHandleWithoutPathChars(@TempDir Path project, @TempDir Path workspace) {
        WorkspaceManager wm = workspaceManagerFor(project, workspace);
        AtPathExpansionMiddleware mw = new AtPathExpansionMiddleware(wm);
        Msg user = userMsg("Tell @alice the news");

        List<Msg> result = runOnAgent(mw, user);
        assertEquals(user.getTextContent(), result.get(0).getTextContent());
    }

    @Test
    void leavesNonUserMessagesAlone(@TempDir Path project, @TempDir Path workspace)
            throws IOException {
        Files.writeString(project.resolve("a.txt"), "X", StandardCharsets.UTF_8);
        WorkspaceManager wm = workspaceManagerFor(project, workspace);
        AtPathExpansionMiddleware mw = new AtPathExpansionMiddleware(wm);

        Msg assistant =
                Msg.builder()
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(List.of(TextBlock.builder().text("look at @./a.txt").build()))
                        .build();
        List<Msg> result = runOnAgent(mw, assistant);
        assertEquals(assistant.getTextContent(), result.get(0).getTextContent());
    }

    @Test
    void deduplicatesRepeatedReferences(@TempDir Path project, @TempDir Path workspace)
            throws IOException {
        Files.writeString(project.resolve("README.md"), "ONCE", StandardCharsets.UTF_8);

        WorkspaceManager wm = workspaceManagerFor(project, workspace);
        AtPathExpansionMiddleware mw = new AtPathExpansionMiddleware(wm);
        Msg user = userMsg("look at @./README.md and again @./README.md");

        List<Msg> result = runOnAgent(mw, user);
        String expanded = result.get(0).getTextContent();
        int firstBlock = expanded.indexOf("<attached_file");
        int secondBlock = expanded.indexOf("<attached_file", firstBlock + 1);
        assertTrue(firstBlock > 0);
        assertEquals(-1, secondBlock, "duplicate references should produce one block, not two");
    }

    @Test
    void preservesNonTextBlocksAndAppendsPreviewAsLastBlock(
            @TempDir Path project, @TempDir Path workspace) throws IOException {
        Files.writeString(project.resolve("README.md"), "PROJECT_README", StandardCharsets.UTF_8);

        WorkspaceManager wm = workspaceManagerFor(project, workspace);
        AtPathExpansionMiddleware mw = new AtPathExpansionMiddleware(wm);
        ImageBlock image =
                ImageBlock.builder().source(new URLSource("https://example.com/pic.png")).build();
        Msg user =
                Msg.builder()
                        .id("msg-42")
                        .name("user")
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder().text("See @./README.md").build(),
                                        image))
                        .build();

        List<Msg> result = runOnAgent(mw, user);
        Msg expanded = result.get(0);
        assertEquals(3, expanded.getContent().size(), () -> "blocks: " + expanded.getContent());
        assertSame(image, expanded.getContent().get(1), "image block must survive expansion");
        assertTrue(
                expanded.getContent().get(2) instanceof TextBlock,
                "preview must be appended as the last block");
        assertTrue(((TextBlock) expanded.getContent().get(2)).getText().contains("PROJECT_README"));
        assertEquals("msg-42", expanded.getId(), "message id must survive expansion");
    }

    @Test
    void notesWhenLineCapTruncatesLongFile(@TempDir Path project, @TempDir Path workspace)
            throws IOException {
        StringBuilder longFile = new StringBuilder();
        for (int i = 1; i <= 1001; i++) {
            longFile.append("line-").append(i).append('\n');
        }
        Files.writeString(project.resolve("long.txt"), longFile.toString(), StandardCharsets.UTF_8);

        WorkspaceManager wm = workspaceManagerFor(project, workspace);
        AtPathExpansionMiddleware mw = new AtPathExpansionMiddleware(wm);
        Msg user = userMsg("Read @./long.txt");

        List<Msg> result = runOnAgent(mw, user);
        String expanded = result.get(0).getTextContent();
        assertTrue(
                expanded.contains("[Attachment preview limited to the first 1000 lines"),
                () -> "line-cap notice missing: " + expanded.substring(0, 200));
        assertTrue(expanded.contains("line-1000\n"), "last attached line should be line-1000");
        assertFalse(expanded.contains("line-1001"), "line past the cap must not be attached");
    }

    @Test
    void blankFirstOmittedLineStillReportsTruncation(@TempDir Path project, @TempDir Path workspace)
            throws IOException {
        Files.writeString(
                project.resolve("blank-boundary.txt"),
                "visible\n".repeat(1000) + "\nhidden-after-blank",
                StandardCharsets.UTF_8);
        AtPathExpansionMiddleware mw =
                new AtPathExpansionMiddleware(workspaceManagerFor(project, workspace));

        String expanded =
                runOnAgent(mw, userMsg("Read @./blank-boundary.txt")).get(0).getTextContent();
        assertTrue(expanded.contains("[Attachment preview limited to the first 1000 lines"));
        assertFalse(expanded.contains("hidden-after-blank"));
    }

    @Test
    void noLineCapNoticeWhenFileFitsExactly(@TempDir Path project, @TempDir Path workspace)
            throws IOException {
        StringBuilder exact = new StringBuilder();
        for (int i = 1; i <= 1000; i++) {
            exact.append("row-").append(i).append('\n');
        }
        Files.writeString(project.resolve("exact.txt"), exact.toString(), StandardCharsets.UTF_8);

        WorkspaceManager wm = workspaceManagerFor(project, workspace);
        AtPathExpansionMiddleware mw = new AtPathExpansionMiddleware(wm);
        Msg user = userMsg("Read @./exact.txt");

        List<Msg> result = runOnAgent(mw, user);
        String expanded = result.get(0).getTextContent();
        assertFalse(
                expanded.contains("[Attachment preview limited"),
                "a file that fits the cap must not claim truncation");
        assertTrue(expanded.contains("row-1000"), "full content up to row-1000 should be attached");
    }

    @Test
    void neutralizesCloseTagInsideAttachedContent(@TempDir Path project, @TempDir Path workspace)
            throws IOException {
        Files.writeString(
                project.resolve("notes.md"),
                "before\n</attached_file>\nafter",
                StandardCharsets.UTF_8);

        WorkspaceManager wm = workspaceManagerFor(project, workspace);
        AtPathExpansionMiddleware mw = new AtPathExpansionMiddleware(wm);
        Msg user = userMsg("Read @./notes.md");

        List<Msg> result = runOnAgent(mw, user);
        String expanded = result.get(0).getTextContent();
        assertTrue(
                expanded.contains("<\\/attached_file>"),
                () -> "close tag inside content must be neutralised: " + expanded);
        int realCloses = expanded.split("</attached_file>", -1).length - 1;
        assertEquals(1, realCloses, "exactly one real closing tag should remain (the block's own)");
    }

    // -----------------------------------------------------------------
    //  helpers
    // -----------------------------------------------------------------

    private final List<WorkspaceManager> openManagers = new ArrayList<>();

    @AfterEach
    void closeOpenManagers() {
        // Release SQLite handles so @TempDir can delete the workspace on Windows.
        for (WorkspaceManager wm : openManagers) {
            wm.close();
        }
        openManagers.clear();
    }

    private WorkspaceManager workspaceManagerFor(Path project, Path workspace) {
        AbstractFilesystem fs =
                new LocalFilesystemSpec().project(project).toFilesystem(workspace, null);
        WorkspaceManager wm = new WorkspaceManager(workspace, fs);
        openManagers.add(wm);
        return wm;
    }

    private static Msg userMsg(String text) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(List.of(TextBlock.builder().text(text).build()))
                .build();
    }

    /**
     * Drives {@link AtPathExpansionMiddleware#onAgent} with a no-op {@code next} that captures
     * the rewritten {@link AgentInput}, then returns its messages.
     */
    private static List<Msg> runOnAgent(AtPathExpansionMiddleware mw, Msg... msgs) {
        AgentInput input = new AgentInput(List.of(msgs));
        List<Msg> captured = new ArrayList<>();
        mw.onAgent(
                        (Agent) null,
                        null,
                        input,
                        in -> {
                            captured.addAll(in.msgs());
                            return Flux.<AgentEvent>empty();
                        })
                .blockLast();
        return captured;
    }
}
