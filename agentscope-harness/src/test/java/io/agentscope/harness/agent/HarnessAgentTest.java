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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.session.Session;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.filesystem.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.RemoteFilesystemSpec;
import io.agentscope.harness.agent.hook.SubagentsHook.SubagentEntry;
import io.agentscope.harness.agent.sandbox.SandboxDistributedOptions;
import io.agentscope.harness.agent.sandbox.filesystem.DockerFilesystemSpec;
import io.agentscope.harness.agent.store.InMemoryStore;
import io.agentscope.harness.agent.workspace.WorkspaceConstants;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

/**
 * Tests for {@link HarnessAgent} workspace wiring: {@code AGENTS.md} context and subagent
 * discovery ({@code subagents/*.md}).
 */
class HarnessAgentTest {

    @TempDir Path workspace;

    @Test
    void workspaceAgentsMd_readableViaWorkspaceManager() throws Exception {
        Files.createDirectories(workspace);
        String marker = "persona-marker-unique-agents-md-42";
        Files.writeString(
                workspace.resolve(WorkspaceConstants.AGENTS_MD), "# Test\n" + marker + "\n");

        Model model = stubModel("ok");
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .build();

        assertTrue(agent.getWorkspaceManager().readAgentsMd().contains(marker));
    }

    @Test
    void workspaceAgentsMd_injectedIntoMessagesSeenByModel() throws Exception {
        Files.createDirectories(workspace);
        String marker = "injected-via-workspace-context-99";
        Files.writeString(workspace.resolve(WorkspaceConstants.AGENTS_MD), marker);

        Model model = stubModel("assistant-done");
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .build();

        agent.call(userText("hi"), RuntimeContext.builder().sessionId("s1").build()).block();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Msg>> captor = ArgumentCaptor.forClass(List.class);
        verify(model, atLeast(1)).stream(captor.capture(), any(), any());
        String combined =
                captor.getAllValues().stream()
                        .map(HarnessAgentTest::joinAllText)
                        .filter(s -> s.contains("<agents_context>"))
                        .findFirst()
                        .orElse("");
        assertTrue(
                combined.contains("<agents_context>"),
                "expected workspace hook to wrap AGENTS.md in agents_context");
        assertTrue(
                combined.contains(marker), "model should see AGENTS.md body in injected context");
    }

    @Test
    void subagentMarkdown_registersIdsAndSubagentTools() throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve(WorkspaceConstants.AGENTS_MD), "# workspace\n");
        String specId = "markdown-subagent-id-77";
        Path subagents = workspace.resolve("subagents");
        Files.createDirectories(subagents);
        Files.writeString(
                subagents.resolve("from-md.md"),
                """
                ---
                name: %s
                description: From subagents/*.md for tests
                ---
                You only reply OK.
                """
                        .formatted(specId));

        Model model = stubModel("done");
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("main")
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .build();

        List<String> toolNames =
                agent.getDelegate().getToolkit().getToolSchemas().stream()
                        .map(ToolSchema::getName)
                        .collect(Collectors.toList());
        assertTrue(
                toolNames.contains("agent_spawn"), "subagent support should register agent_spawn");
        assertTrue(
                toolNames.contains("task_output"),
                "subagent async path should register task_output");

        agent.call(userText("go"), RuntimeContext.builder().sessionId("s2").build()).block();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Msg>> captor = ArgumentCaptor.forClass(List.class);
        verify(model, atLeast(1)).stream(captor.capture(), any(), any());
        String combined =
                captor.getAllValues().stream()
                        .map(HarnessAgentTest::joinAllText)
                        .filter(s -> s.contains("## Subagents"))
                        .findFirst()
                        .orElse("");
        assertTrue(
                combined.contains("## Subagents"), "subagent hook should inject Subagents section");
        assertTrue(
                combined.contains("`" + specId + "`"),
                "Markdown subagent id should appear in prompt");
        assertTrue(
                combined.contains("general-purpose"),
                "built-in general-purpose entry should be listed");
    }

    @Test
    void subagentsDir_loadsMarkdownSpecs() throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve(WorkspaceConstants.AGENTS_MD), "# w\n");
        Path subagents = workspace.resolve("subagents");
        Files.createDirectories(subagents);
        String mdId = "md-frontmatter-agent-88";
        Files.writeString(
                subagents.resolve("helper.md"),
                """
                ---
                name: %s
                description: Loaded from subagents/*.md
                maxIters: 3
                ---

                You are a test subagent from markdown.
                """
                        .formatted(mdId));

        List<SubagentEntry> entries =
                HarnessAgent.builder()
                        .model(stubModel("x"))
                        .workspace(workspace)
                        .buildSubagentEntries(workspace);

        List<String> names = entries.stream().map(SubagentEntry::name).collect(Collectors.toList());
        assertTrue(names.contains("general-purpose"));
        assertTrue(
                names.contains(mdId), "subagents/*.md with front matter should produce an entry");
    }

    @Test
    void remoteFilesystemSpec_sharesMemoryMdInNonsandboxMode() throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve(WorkspaceConstants.AGENTS_MD), "# Test\n");
        InMemoryStore store = new InMemoryStore();

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("agent-a")
                        .model(stubModel("ok"))
                        .workspace(workspace)
                        .filesystem(new RemoteFilesystemSpec(store))
                        .session(mock(Session.class))
                        .build();

        agent.getWorkspaceManager().writeUtf8WorkspaceRelative("MEMORY.md", "shared-memory");

        assertTrue(
                store.get(List.of("agents", "agent-a", "users", "_default"), "/MEMORY.md") != null);
    }

    @Test
    void sandboxFilesystemMode_fileToolsUseSandboxBackendByDefault() throws Exception {
        Files.createDirectories(workspace);
        Path localTarget = workspace.resolve("should-not-be-local.txt");

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("agent")
                        .model(stubModel("ok"))
                        .workspace(workspace)
                        .filesystem(new DockerFilesystemSpec())
                        .sandboxDistributed(
                                SandboxDistributedOptions.builder()
                                        .requireDistributed(false)
                                        .build())
                        .build();
        Map<String, Object> writeInput =
                Map.of("path", "/should-not-be-local.txt", "content", "sandbox-only");

        ToolResultBlock result =
                agent.getDelegate()
                        .getToolkit()
                        .callTool(
                                ToolCallParam.builder()
                                        .toolUseBlock(
                                                ToolUseBlock.builder()
                                                        .id("call-write")
                                                        .name("write_file")
                                                        .input(writeInput)
                                                        .content(
                                                                JsonUtils.getJsonCodec()
                                                                        .toJson(writeInput))
                                                        .build())
                                        .input(writeInput)
                                        .build())
                        .block();

        String text = joinToolResultText(result);
        assertTrue(
                text.contains("No active sandbox") || text.contains("sandbox filesystem"),
                "file tool should use the sandbox proxy outside a sandbox call context: " + text);
        assertTrue(
                Files.notExists(localTarget),
                "sandbox file tool must not fall back to the local workspace");
    }

    @Test
    void generalPurposeSubagent_inheritsProjectWorkspaceForFileTools() throws Exception {
        Files.createDirectories(workspace);
        Path projectWorkspace = Files.createTempDirectory("agentscope-project-workspace");
        Path projectTarget = projectWorkspace.resolve("from-subagent.txt");
        Path stateWorkspaceTarget = workspace.resolve("from-subagent.txt");

        List<SubagentEntry> entries =
                HarnessAgent.builder()
                        .name("main")
                        .model(stubModel("ok"))
                        .workspace(workspace)
                        .projectWorkspace(projectWorkspace)
                        .buildSubagentEntries(workspace);

        Agent createdAgent =
                entries.stream()
                        .filter(entry -> entry.name().equals("general-purpose"))
                        .findFirst()
                        .orElseThrow()
                        .factory()
                        .create();
        assertTrue(createdAgent instanceof HarnessAgent);
        HarnessAgent subagent = (HarnessAgent) createdAgent;

        Map<String, Object> writeInput =
                Map.of("path", "/from-subagent.txt", "content", "subagent-project");
        ToolResultBlock result = callTool(subagent, "write_file", writeInput);

        assertTrue(
                joinToolResultText(result).contains("Written to /from-subagent.txt"),
                "subagent file tool should write successfully: " + joinToolResultText(result));
        assertTrue(
                Files.readString(projectTarget).contains("subagent-project"),
                "subagent file tool should use inherited projectWorkspace");
        assertTrue(
                Files.notExists(stateWorkspaceTarget),
                "subagent file tool must not write into the Harness state workspace");
    }

    private static Msg userText(String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static String joinAllText(List<Msg> msgs) {
        return msgs.stream().map(Msg::getTextContent).collect(Collectors.joining("\n"));
    }

    private static ToolResultBlock callTool(
            HarnessAgent agent, String toolName, Map<String, Object> input) {
        return agent.getDelegate()
                .getToolkit()
                .callTool(
                        ToolCallParam.builder()
                                .toolUseBlock(
                                        ToolUseBlock.builder()
                                                .id("call-" + toolName)
                                                .name(toolName)
                                                .input(input)
                                                .content(JsonUtils.getJsonCodec().toJson(input))
                                                .build())
                                .input(input)
                                .build())
                .block();
    }

    private static String joinToolResultText(ToolResultBlock result) {
        if (result == null) {
            return "";
        }
        return result.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .collect(Collectors.joining("\n"));
    }

    private static Model stubModel(String assistantText) {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
        ChatResponse chunk =
                new ChatResponse(
                        "stub-id",
                        List.of(TextBlock.builder().text(assistantText).build()),
                        null,
                        Map.of(),
                        "stop");
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(chunk));
        return model;
    }
}
