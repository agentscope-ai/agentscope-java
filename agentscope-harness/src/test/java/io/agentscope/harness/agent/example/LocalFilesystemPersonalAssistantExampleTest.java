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
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * Example: Fully local personal-assistant mode using {@link LocalFilesystemWithShell}.
 *
 * <h2>Context</h2>
 * <p>This mode is ideal for a <em>personal local assistant</em> running on a developer's machine
 * or in a single-process environment:
 * <ul>
 *   <li>All file I/O goes directly to a local workspace directory on disk.</li>
 *   <li>Shell commands execute in that directory via {@code ProcessBuilder}.</li>
 *   <li>No sandbox container, no distributed store, no external dependencies.</li>
 *   <li>State persists naturally as files on disk between calls.</li>
 *   <li>Changing the {@code userId} or {@code sessionId} in the {@link RuntimeContext} does
 *       <em>not</em> redirect I/O to a different location — the workspace directory is always
 *       the same.</li>
 * </ul>
 *
 * <h2>Trade-offs</h2>
 * <ul>
 *   <li><b>Pro</b>: Zero infrastructure, instant setup, full local control.</li>
 *   <li><b>Con</b>: No isolation between users or sessions, no horizontal scaling.</li>
 * </ul>
 *
 * <p>Configure via {@link HarnessAgent.Builder#abstractFilesystem} with a
 * {@link LocalFilesystemWithShell} instance pointing at your desired workspace directory.
 */
class LocalFilesystemPersonalAssistantExampleTest {

    @TempDir Path workspace;

    /**
     * Demonstrates that files written to the workspace during one call persist to disk and are
     * readable in subsequent calls — the most fundamental characteristic of the local mode.
     */
    @Test
    void localFilesystem_filesPersistAcrossCalls() throws Exception {
        Files.createDirectories(workspace);

        // Build the agent with a LocalFilesystemWithShell backend.
        // No distributed store, no sandbox — all operations go straight to disk.
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("my-local-assistant")
                        .model(stubModel("done"))
                        .workspace(workspace.toAbsolutePath().normalize().toString())
                        .abstractFilesystem(new LocalFilesystemWithShell(workspace))
                        .build();

        // Call 1: write a note to MEMORY.md through the workspace manager
        agent.call(userMsg("first call"), ctx("session-1", "alice")).block();
        agent.getWorkspaceManager().writeUtf8WorkspaceRelative("MEMORY.md", "# Notes\n- item 1");

        // The file exists on disk after call 1
        Path memoryFile = workspace.resolve("MEMORY.md");
        assertTrue(Files.isRegularFile(memoryFile), "MEMORY.md should exist on disk after call 1");
        String content = Files.readString(memoryFile, StandardCharsets.UTF_8);
        assertTrue(content.contains("item 1"), "MEMORY.md content should be persisted on disk");

        // Call 2: same workspace, different session — file is still there
        agent.call(userMsg("second call"), ctx("session-2", "alice")).block();
        assertTrue(
                Files.isRegularFile(memoryFile), "MEMORY.md should still exist on disk in call 2");
        assertEquals(
                content,
                Files.readString(memoryFile, StandardCharsets.UTF_8),
                "MEMORY.md content should be unchanged after call 2");
    }

    /**
     * Demonstrates that changing {@code userId} or {@code sessionId} does NOT redirect I/O to a
     * different location in local mode.
     *
     * <p>This is the key distinction from sandbox/remote modes: in local mode the workspace
     * directory is fixed, so all users and sessions share the same disk location.
     */
    @Test
    void localFilesystem_workspaceIsNotPartitionedByUserOrSession() throws Exception {
        Files.createDirectories(workspace);

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("my-local-assistant")
                        .model(stubModel("done"))
                        .workspace(workspace.toAbsolutePath().normalize().toString())
                        .abstractFilesystem(new LocalFilesystemWithShell(workspace))
                        .build();

        // Alice writes during her session
        agent.call(userMsg("alice here"), ctx("session-alice", "alice")).block();
        agent.getWorkspaceManager().writeUtf8WorkspaceRelative("shared.txt", "alice was here");

        // Bob calls with a different userId — still reads the same workspace
        agent.call(userMsg("bob here"), ctx("session-bob", "bob")).block();
        Path sharedFile = workspace.resolve("shared.txt");
        assertTrue(
                Files.isRegularFile(sharedFile),
                "shared.txt written by alice should be visible in the same workspace, "
                        + "regardless of userId or sessionId");
    }

    /**
     * Demonstrates that the underlying workspace directory is a plain local filesystem path —
     * you can read and write files with standard Java I/O alongside the agent.
     */
    @Test
    void localFilesystem_directDiskAccessFromHostProcess() throws Exception {
        Files.createDirectories(workspace);

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("my-local-assistant")
                        .model(stubModel("done"))
                        .workspace(workspace.toAbsolutePath().normalize().toString())
                        .abstractFilesystem(new LocalFilesystemWithShell(workspace))
                        .build();

        // Write a file from the host process (simulating a user placing a document in the
        // workspace)
        Path doc = workspace.resolve("document.txt");
        Files.writeString(doc, "Host-written document content");

        // The agent can see the file through its workspace manager
        agent.call(userMsg("check document"), ctx("s1", "user")).block();
        String read = agent.getWorkspaceManager().readManagedWorkspaceFileUtf8("document.txt");
        assertNotNull(read, "agent should be able to read files written directly to the workspace");
        assertTrue(read.contains("Host-written"), "agent should see the host-written content");
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
