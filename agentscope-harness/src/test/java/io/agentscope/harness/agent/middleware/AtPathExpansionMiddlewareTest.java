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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
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
    void expandsPathWhenAgentAlreadyHasRuntimeContext(
            @TempDir Path project, @TempDir Path workspace) throws IOException {
        Files.writeString(project.resolve("README.md"), "WITH_CONTEXT", StandardCharsets.UTF_8);

        WorkspaceManager wm = workspaceManagerFor(project, workspace);
        AtPathExpansionMiddleware mw = new AtPathExpansionMiddleware(wm);
        Agent agent = mock(Agent.class);
        when(agent.getRuntimeContext())
                .thenReturn(RuntimeContext.builder().sessionId("ctx-session").build());
        Msg user = userMsg("Open @./README.md");

        List<Msg> result = runOnAgent(mw, agent, user);

        assertTrue(result.get(0).getTextContent().contains("WITH_CONTEXT"));
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
        return runOnAgent(mw, null, msgs);
    }

    private static List<Msg> runOnAgent(AtPathExpansionMiddleware mw, Agent agent, Msg... msgs) {
        AgentInput input = new AgentInput(List.of(msgs));
        List<Msg> captured = new ArrayList<>();
        mw.onAgent(
                        agent,
                        input,
                        in -> {
                            captured.addAll(in.msgs());
                            return Flux.<AgentEvent>empty();
                        })
                .blockLast();
        return captured;
    }
}
