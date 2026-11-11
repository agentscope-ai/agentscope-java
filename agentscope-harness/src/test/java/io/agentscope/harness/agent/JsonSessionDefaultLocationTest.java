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
import io.agentscope.harness.agent.testing.HarnessQuiescence;
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

/** Native history defaults to the workspace; explicit legacy snapshots retain their old location. */
@HarnessQuiescence
class JsonSessionDefaultLocationTest {

    @TempDir Path stateHome;

    @TempDir Path workspace;

    private String previousStateHome;
    private HarnessAgent agent;

    @BeforeEach
    void overrideStateHome() {
        previousStateHome = System.getProperty("agentscope.state.home");
        System.setProperty("agentscope.state.home", stateHome.toString());
    }

    @AfterEach
    void restoreStateHome() {
        try {
            if (agent != null) {
                agent.close();
            }
        } finally {
            if (previousStateHome != null) {
                System.setProperty("agentscope.state.home", previousStateHome);
            } else {
                System.clearProperty("agentscope.state.home");
            }
        }
    }

    @Test
    void defaultSession_writesOnlyNativeJournalUnderWorkspace() throws Exception {
        String agentName = "assistant-" + UUID.randomUUID();
        agent =
                HarnessAgent.builder()
                        .name(agentName)
                        .model(stubModel("done"))
                        .workspace(workspace)
                        .disableMemoryHooks()
                        .build();
        RuntimeContext rc = RuntimeContext.builder().sessionId("s1").build();
        agent.call(userMsg("hi"), rc).block();
        assertTrue(agent.sessionLog(rc).head().seq() > 0);
        assertEquals(2, agent.sessionTranscript(rc).messages().size());
        assertFalse(
                Files.exists(stateHome.resolve(agentName)),
                "Native logging must not write legacy state");
        try (Stream<Path> files = Files.walk(workspace)) {
            assertFalse(
                    files.anyMatch(path -> path.toString().endsWith(".jsonl")),
                    "Native logging must not duplicate conversation into legacy JSONL files");
        }
    }

    @Test
    void perUserPartitioning_viaSharedAgentRoutedByRuntimeContext() throws Exception {
        // Commit 2 design: ReActAgent reads (userId, sessionId) from the per-call
        // RuntimeContext and persists state under <stateHome>/<agentId>/<userId>/<sessionId>/.
        // A single shared agent instance can therefore serve many users without any per-user
        // builder boilerplate — the in-memory state cache + persistence slot both follow the
        // RuntimeContext.
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("AGENTS.md"), "# Test\n");

        String agentName = "shared-" + UUID.randomUUID();
        agent =
                HarnessAgent.builder()
                        .legacySessionHistory(true)
                        .name(agentName)
                        .model(stubModel("done"))
                        .workspace(workspace)
                        .build();

        agent.call(
                        userMsg("alice"),
                        RuntimeContext.builder().userId("alice").sessionId("s1").build())
                .block();
        agent.call(userMsg("bob"), RuntimeContext.builder().userId("bob").sessionId("s1").build())
                .block();

        Path stateRoot = stateHome.resolve(agentName);
        Path aliceSession = stateRoot.resolve("alice/s1");
        Path bobSession = stateRoot.resolve("bob/s1");
        assertTrue(
                Files.isDirectory(aliceSession),
                "alice's session dir should exist under " + stateRoot);
        assertTrue(
                Files.isDirectory(bobSession), "bob's session dir should exist under " + stateRoot);
        assertTrue(
                Files.isRegularFile(aliceSession.resolve("agent_state.json")),
                "alice's agent_state.json should exist");
        assertTrue(
                Files.isRegularFile(bobSession.resolve("agent_state.json")),
                "bob's agent_state.json should exist");
    }

    @Test
    void defaultSession_independentOfWorkspaceLifecycle() throws Exception {
        // State is a PREREQUISITE for restoring the workspace,
        // so it must outlive a workspace wipe. Simulate by writing state, then deleting the
        // workspace directory, and verifying state files survive.
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("AGENTS.md"), "# Test\n");

        String agentName = "wipe-" + UUID.randomUUID();
        agent =
                HarnessAgent.builder()
                        .legacySessionHistory(true)
                        .name(agentName)
                        .model(stubModel("done"))
                        .workspace(workspace)
                        .build();
        agent.call(userMsg("hi"), RuntimeContext.builder().sessionId("s1").sessionId("s1").build())
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
