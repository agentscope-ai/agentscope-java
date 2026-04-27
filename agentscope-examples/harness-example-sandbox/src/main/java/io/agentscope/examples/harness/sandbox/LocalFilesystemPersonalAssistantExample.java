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
package io.agentscope.examples.harness.sandbox;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import io.agentscope.examples.harness.sandbox.support.FixedReplyModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.LocalFilesystemWithShell;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Example: local personal-assistant mode using {@link LocalFilesystemWithShell} — direct disk I/O
 * and {@link ProcessBuilder} shell in one workspace, no container or distributed store.
 */
public final class LocalFilesystemPersonalAssistantExample {

    public static void main(String[] args) throws Exception {
        Model model = FixedReplyModel.done();
        Path workspace = Files.createTempDirectory("harness-local-fs-example-");
        System.out.println("Workspace: " + workspace.toAbsolutePath());

        demonstrateFilesPersistAcrossCalls(workspace, model);
        demonstrateSharedWorkspaceForAllUsersAndSessions(workspace, model);
        demonstrateHostWrittenFileVisibleToAgent(workspace, model);

        System.out.println("Local filesystem example finished successfully.");
    }

    static void demonstrateFilesPersistAcrossCalls(Path workspace, Model model) throws Exception {
        Files.createDirectories(workspace);
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("my-local-assistant")
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystemWithShell(workspace))
                        .build();

        agent.call(userMsg("first call"), ctx("session-1", "alice")).block();
        agent.getWorkspaceManager().writeUtf8WorkspaceRelative("MEMORY.md", "# Notes\n- item 1");

        Path memoryFile = workspace.resolve("MEMORY.md");
        if (!Files.isRegularFile(memoryFile)) {
            throw new IllegalStateException("MEMORY.md should exist on disk after first call");
        }
        String content = Files.readString(memoryFile, StandardCharsets.UTF_8);
        if (!content.contains("item 1")) {
            throw new IllegalStateException("MEMORY.md should contain persisted item");
        }

        agent.call(userMsg("second call"), ctx("session-2", "alice")).block();
        if (!Files.isRegularFile(memoryFile)) {
            throw new IllegalStateException("MEMORY.md should still exist after second call");
        }
        if (!content.equals(Files.readString(memoryFile, StandardCharsets.UTF_8))) {
            throw new IllegalStateException("MEMORY.md content should be unchanged");
        }
        System.out.println("[local] files persist across calls: OK");
    }

    static void demonstrateSharedWorkspaceForAllUsersAndSessions(Path workspace, Model model)
            throws Exception {
        Files.createDirectories(workspace);
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("my-local-assistant")
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystemWithShell(workspace))
                        .build();

        agent.call(userMsg("alice here"), ctx("session-alice", "alice")).block();
        agent.getWorkspaceManager().writeUtf8WorkspaceRelative("shared.txt", "alice was here");
        agent.call(userMsg("bob here"), ctx("session-bob", "bob")).block();
        if (!Files.isRegularFile(workspace.resolve("shared.txt"))) {
            throw new IllegalStateException(
                    "local workspace is not partitioned by user or session");
        }
        System.out.println("[local] same workspace for all user/session context values: OK");
    }

    static void demonstrateHostWrittenFileVisibleToAgent(Path workspace, Model model)
            throws Exception {
        Files.createDirectories(workspace);
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("my-local-assistant")
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystemWithShell(workspace))
                        .build();

        Path doc = workspace.resolve("document.txt");
        Files.writeString(doc, "Host-written document content");
        agent.call(userMsg("check document"), ctx("s1", "user")).block();
        String read = agent.getWorkspaceManager().readManagedWorkspaceFileUtf8("document.txt");
        if (read == null || !read.contains("Host-written")) {
            throw new IllegalStateException("agent should read host-written file");
        }
        System.out.println("[local] host file visible to workspace manager: OK");
    }

    private static RuntimeContext ctx(String sessionId, String userId) {
        return RuntimeContext.builder().sessionId(sessionId).userId(userId).build();
    }

    private static Msg userMsg(String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }
}
