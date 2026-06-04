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
package io.agentscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.SimpleSessionKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * Phase 0 regression: the default {@code HarnessAgent} {@code AgentStateStore} is now {@link
 * io.agentscope.core.state.JsonFileAgentStateStore} rooted at {@code ~/.agentscope/state/<agentId>/}, NOT
 * a workspace-scoped session.
 *
 * <p>Each test points the {@code agentscope.state.home} system property at a fresh {@link
 * TempDir} so we can both (a) assert state lands at the expected location and (b) avoid sharing
 * state across tests / polluting the surefire-shared {@code target/test-state-home/}.
 */
class JsonSessionDefaultLocationTest {

    @TempDir Path stateHome;

    @TempDir Path workspace;

    private String previousStateHome;

    @BeforeEach
    void overrideStateHome() {
        previousStateHome = System.getProperty("agentscope.state.home");
        System.setProperty("agentscope.state.home", stateHome.toString());
    }

    @AfterEach
    void restoreStateHome() {
        if (previousStateHome != null) {
            System.setProperty("agentscope.state.home", previousStateHome);
        } else {
            System.clearProperty("agentscope.state.home");
        }
    }

    @Test
    void defaultSession_writesUnderStateHome_notInsideWorkspace() throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("AGENTS.md"), "# Test\n");

        String agentName = "assistant-" + UUID.randomUUID();
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name(agentName)
                        .model(stubModel("done"))
                        .workspace(workspace)
                        .build();

        RuntimeContext rc =
                RuntimeContext.builder()
                        .sessionId("s1")
                        .sessionKey(SimpleSessionKey.of("s1"))
                        .build();
        agent.call(userMsg("hi"), rc).block();

        Path stateRoot = stateHome.resolve(agentName);
        assertTrue(
                Files.isDirectory(stateRoot),
                "Default AgentStateStore should root state at <agentscope.state.home>/<agentId>/,"
                        + " missing: "
                        + stateRoot);

        // agent_state.json must exist somewhere under the state root (JsonFileAgentStateStore may
        // url-encode the session id into a subdirectory name).
        long agentStateCount;
        try (Stream<Path> walk = Files.walk(stateRoot)) {
            agentStateCount =
                    walk.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().equals("agent_state.json"))
                            .count();
        }
        assertTrue(agentStateCount >= 1, "agent_state.json should be persisted under " + stateRoot);

        // Inverse assertion: state must NOT leak into workspace. The pre-Phase-0 layout was
        // <workspace>/agents/<agentId>/context/<sessionId>/agent_state.json — verify it's gone.
        Path contextDir = workspace.resolve("agents/" + agentName + "/context");
        assertFalse(
                Files.isDirectory(contextDir),
                "Phase 0 regression: agent_state must not live inside workspace, found: "
                        + contextDir);
    }

    @Test
    void perUserPartitioning_viaPerInstanceSessionKey() throws Exception {
        // Phase 0 design clarification: per-call RuntimeContext.sessionKey does NOT change
        // where AgentState is persisted — ReActAgent pins sessionKey at build time. The
        // supported pattern for per-user isolation is to construct one HarnessAgent per user,
        // each with its own .sessionKey(...).
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("AGENTS.md"), "# Test\n");

        String agentName = "shared-" + UUID.randomUUID();
        HarnessAgent agentAlice =
                HarnessAgent.builder()
                        .name(agentName)
                        .sessionKey(SimpleSessionKey.of("users/alice/s1"))
                        .model(stubModel("done"))
                        .workspace(workspace)
                        .build();
        HarnessAgent agentBob =
                HarnessAgent.builder()
                        .name(agentName)
                        .sessionKey(SimpleSessionKey.of("users/bob/s1"))
                        .model(stubModel("done"))
                        .workspace(workspace)
                        .build();

        agentAlice.call(userMsg("alice"), RuntimeContext.builder().userId("alice").build()).block();
        agentBob.call(userMsg("bob"), RuntimeContext.builder().userId("bob").build()).block();

        // After Commit 1 the layout is <root>/<userId or __anon__>/<sessionId>/. ReActAgent in
        // this transitional commit still passes userId=null (per-call userId routing arrives in
        // Commit 2), so all sessions land under __anon__/. We just need 2 distinct sessionId
        // subdirs under that anon namespace.
        Path stateRoot = stateHome.resolve(agentName);
        Path anonRoot = stateRoot.resolve("__anon__");
        assertTrue(
                Files.isDirectory(anonRoot),
                "Anonymous namespace root should exist under " + stateRoot);
        try (Stream<Path> children = Files.list(anonRoot)) {
            long sessionDirs = children.filter(Files::isDirectory).count();
            assertTrue(
                    sessionDirs >= 2,
                    "Expected per-SessionKey partitioning under "
                            + anonRoot
                            + " but found "
                            + sessionDirs
                            + " session dir(s)");
        }
    }

    @Test
    void defaultSession_independentOfWorkspaceLifecycle() throws Exception {
        // The motivation for Phase 0: state is a PREREQUISITE for restoring the workspace,
        // so it must outlive a workspace wipe. Simulate by writing state, then deleting the
        // workspace directory, and verifying state files survive.
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("AGENTS.md"), "# Test\n");

        String agentName = "wipe-" + UUID.randomUUID();
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name(agentName)
                        .model(stubModel("done"))
                        .workspace(workspace)
                        .build();
        agent.call(
                        userMsg("hi"),
                        RuntimeContext.builder()
                                .sessionId("s1")
                                .sessionKey(SimpleSessionKey.of("s1"))
                                .build())
                .block();

        Path stateRoot = stateHome.resolve(agentName);
        long stateFilesBefore;
        try (Stream<Path> walk = Files.walk(stateRoot)) {
            stateFilesBefore = walk.filter(Files::isRegularFile).count();
        }
        assertTrue(stateFilesBefore > 0, "Pre-condition: state should have been written");

        // Wipe the workspace, leaving state intact.
        try (Stream<Path> walk = Files.walk(workspace)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(
                            p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (java.io.IOException ignored) {
                                    // best-effort
                                }
                            });
        }

        long stateFilesAfter;
        try (Stream<Path> walk = Files.walk(stateRoot)) {
            stateFilesAfter = walk.filter(Files::isRegularFile).count();
        }
        assertEquals(
                stateFilesBefore,
                stateFilesAfter,
                "State should survive workspace wipe (Phase 0 motivation)");
    }

    private static Msg userMsg(String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
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
