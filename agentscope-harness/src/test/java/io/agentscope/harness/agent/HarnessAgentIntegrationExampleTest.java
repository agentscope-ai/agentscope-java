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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.hook.SubagentsHook.SubagentEntry;
import io.agentscope.harness.agent.workspace.WorkspaceConstants;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

/**
 * Integration-style examples for {@link HarnessAgent}: a realistic workspace on disk, full builder
 * wiring, and an end-to-end {@link #call(Msg, RuntimeContext)} path.
 *
 * <p>Example workspace layout (what the tests materialize under {@link TempDir}):
 *
 * <pre>
 * workspace/
 * ├── AGENTS.md              # persona / local rules
 * ├── MEMORY.md              # optional long-term scratch (loaded into &lt;memory_context&gt;)
 * ├── knowledge/
 * │   └── KNOWLEDGE.md       # optional domain summary
 * └── subagents/
 *     ├── helper.md          # YAML front matter + body as sys prompt
 *     └── reviewer.md        # second spec example
 * </pre>
 *
 * <p>These tests use a stub {@link Model} (no API keys). Tag {@code integration} lets you filter
 * them in the IDE or via JUnit Platform if you add {@code groups} later.
 */
@Tag("integration")
class HarnessAgentIntegrationExampleTest {

    @TempDir Path workspace;

    /**
     * Materializes the layout above, builds the main agent, runs one turn, and asserts the stub
     * reply. The model capture shows that session, subagent docs, and workspace files reached the
     * LLM message list.
     */
    @Test
    void example_fullWorkspace_singleTurn_seesSessionSubagentsAndWorkspaceContext()
            throws Exception {
        String agentsPersona = "INTEGRATION_AGENTS_PERSONA_001";
        String memoryNote = "INTEGRATION_MEMORY_NOTE_002";
        String knowledgeLine = "INTEGRATION_KNOWLEDGE_LINE_003";
        String helperSubId = "integration-helper-sub";
        String reviewerSubId = "integration-md-sub";

        Files.createDirectories(workspace);
        Files.writeString(
                workspace.resolve(WorkspaceConstants.AGENTS_MD),
                "# Agent\n\n" + agentsPersona + "\n");
        Files.writeString(workspace.resolve(WorkspaceConstants.MEMORY_MD), memoryNote);

        Path knowledgeDir = workspace.resolve(WorkspaceConstants.KNOWLEDGE_DIR);
        Files.createDirectories(knowledgeDir);
        Files.writeString(knowledgeDir.resolve(WorkspaceConstants.KNOWLEDGE_MD), knowledgeLine);

        Path subagentsDir = workspace.resolve("subagents");
        Files.createDirectories(subagentsDir);
        Files.writeString(
                subagentsDir.resolve("helper.md"),
                """
                ---
                name: %s
                description: First markdown-defined helper for integration example
                ---
                Reply with YAML_OK only.
                """
                        .formatted(helperSubId));
        Files.writeString(
                subagentsDir.resolve("reviewer.md"),
                """
                ---
                name: %s
                description: Second markdown-defined helper for integration example
                maxIters: 5
                ---

                You only reply MD_OK.
                """
                        .formatted(reviewerSubId));

        Model model = stubModel("integration-main-reply");
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("integration-main")
                        .description("integration example main agent")
                        .sysPrompt("You are the main agent in an integration test.")
                        .model(model)
                        .workspace(workspace)
                        .build();

        Msg reply =
                agent.call(
                                userText("Run the integration scenario."),
                                RuntimeContext.builder().sessionId("integration-session-1").build())
                        .block();

        assertTrue(reply.getTextContent().contains("integration-main-reply"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Msg>> captor = ArgumentCaptor.forClass(List.class);
        verify(model, atLeast(1)).stream(captor.capture(), any(), any());
        String combined =
                captor.getAllValues().stream()
                        .map(HarnessAgentIntegrationExampleTest::joinAllText)
                        .filter(s -> s.contains("## Session Context"))
                        .findFirst()
                        .orElse("");

        assertTrue(
                combined.contains("## Session Context"),
                "Session context should be injected; model saw: "
                        + captor.getAllValues().stream()
                                .map(HarnessAgentIntegrationExampleTest::joinAllText)
                                .toList());
        // Current WorkspaceContextHook uses markdown (##) guidance + XML <loaded_context> blocks
        assertTrue(
                combined.contains("## Domain Knowledge") || combined.contains("## Workspace"),
                "expected workspace guidance sections");
        assertTrue(combined.contains("`AGENTS.md`") || combined.contains("agents_context"));
        assertTrue(
                combined.contains(agentsPersona), "AGENTS.md should appear under workspace hook");
        assertTrue(combined.contains("memory_context") || combined.contains("MEMORY.md"));
        assertTrue(combined.contains(memoryNote));
        assertTrue(
                combined.contains("domain_knowledge_context") || combined.contains("KNOWLEDGE.md"));
        assertTrue(combined.contains(knowledgeLine));
        assertTrue(
                combined.contains("## Subagents") || combined.contains("Subagents:"),
                "subagent list should be injected into the system prompt");
        assertTrue(combined.contains("`" + helperSubId + "`"));
        assertTrue(combined.contains("`" + reviewerSubId + "`"));
    }

    /**
     * Uses the same workspace discovery as the builder, obtains a {@link SubagentEntry} for a
     * markdown-defined id under {@code subagents/}, runs {@code factory().create()} and {@link
     * Agent#call(List)} to prove the delegated {@link HarnessAgent} is wired with the spec name
     * and prompt.
     */
    @Test
    void example_subagentFactory_markdownSpec_runsChildHarnessAgent() throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve(WorkspaceConstants.AGENTS_MD), "# root\n");

        String childId = "integration-child-spawn";
        Path subagentsDir = workspace.resolve("subagents");
        Files.createDirectories(subagentsDir);
        Files.writeString(
                subagentsDir.resolve("child.md"),
                """
                ---
                name: %s
                description: Child agent for factory integration
                ---
                Child system prompt marker INTEGRATION_CHILD_SYS
                """
                        .formatted(childId));

        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
        ChatResponse childChunk =
                new ChatResponse(
                        "child-id",
                        List.of(TextBlock.builder().text("integration-child-reply").build()),
                        null,
                        Map.of(),
                        "stop");
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(childChunk));

        List<SubagentEntry> entries =
                HarnessAgent.builder()
                        .model(model)
                        .workspace(workspace)
                        .buildSubagentEntries(workspace);

        SubagentEntry child =
                entries.stream()
                        .filter(e -> childId.equals(e.name()))
                        .findFirst()
                        .orElseThrow(
                                () -> new AssertionError("missing subagent entry: " + childId));

        Agent sub = child.factory().create();
        assertInstanceOf(HarnessAgent.class, sub);
        assertEquals(childId, sub.getName());

        Msg subReply = sub.call(List.of(userText("task for child"))).block();
        assertTrue(subReply.getTextContent().contains("integration-child-reply"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Msg>> captor = ArgumentCaptor.forClass(List.class);
        verify(model, atLeast(1)).stream(captor.capture(), any(), any());
        boolean childSysSeen =
                captor.getAllValues().stream()
                        .map(HarnessAgentIntegrationExampleTest::joinAllText)
                        .anyMatch(s -> s.contains("INTEGRATION_CHILD_SYS"));
        assertTrue(
                childSysSeen,
                "child HarnessAgent should use spec sysPrompt in its system prompt bundle");
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
