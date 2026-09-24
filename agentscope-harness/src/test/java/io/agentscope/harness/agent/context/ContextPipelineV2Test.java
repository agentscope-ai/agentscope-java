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
package io.agentscope.harness.agent.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ModelRequestPreparer.Purpose;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.builtin.TodoTools;
import io.agentscope.core.tool.builtin.TodoTools.TodoItem;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.middleware.CompactionMiddleware;
import io.agentscope.harness.agent.middleware.ContextConventionsMiddleware;
import io.agentscope.harness.agent.middleware.ToolResultEvictionMiddleware;
import io.agentscope.harness.agent.middleware.WorkspaceContextMiddleware;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class ContextPipelineV2Test {
    @Test
    void singleSystemPreservesOpaqueInputsAndReplacesFrameworkSourcesOnRebuild() {
        var rc = RuntimeContext.empty();
        WorkspaceContextMaterials.register(
                rc, List.of(ContextItem.instruction("project_rules", "AGENTS.md", "old rule")));
        var builder = new HarnessContextBuilder(ContextPolicy.defaults(), null, null);
        var input =
                new ModelCallInput(
                        List.of(
                                Msg.builder()
                                        .role(MsgRole.SYSTEM)
                                        .textContent("opaque base")
                                        .metadata(Map.of("provider-option", "preserved"))
                                        .build(),
                                message(MsgRole.SYSTEM, "middleware instructions"),
                                message(MsgRole.USER, "hello")),
                        List.of(),
                        null,
                        model(10000));
        var first = builder.prepare(mock(Agent.class), rc, input, "one", Purpose.REASONING).block();
        WorkspaceContextMaterials.register(
                rc, List.of(ContextItem.instruction("project_rules", "AGENTS.md", "new rule")));
        var second =
                builder.prepare(mock(Agent.class), rc, first, "two", Purpose.REASONING).block();
        assertEquals(
                1,
                second.messages().stream().filter(msg -> msg.getRole() == MsgRole.SYSTEM).count());
        String text = second.messages().get(0).getTextContent();
        assertEquals("preserved", second.messages().get(0).getMetadata().get("provider-option"));
        assertTrue(text.contains("opaque base"));
        assertTrue(text.contains("middleware instructions"));
        assertTrue(text.contains("new rule"));
        assertFalse(text.contains("old rule"));
        assertEquals(text.indexOf("opaque base"), text.lastIndexOf("opaque base"));
        assertEquals(text.indexOf("new rule"), text.lastIndexOf("new rule"));
    }

    @Test
    void newCallDoesNotInheritPreviousProvidersThroughReusedRuntimeContext() {
        var rc = RuntimeContext.empty();
        WorkspaceContextMaterials.register(
                rc,
                List.of(new ContextItem("memory", "workspace:MEMORY.md", "previous agent secret")));
        new ContextConventionsMiddleware().onSystemPrompt(null, rc, "base").block();
        assertEquals(1, rc.get(WorkspaceContextMaterials.class).items().size());
        assertEquals(
                "harness:context-conventions",
                rc.get(WorkspaceContextMaterials.class).items().get(0).sourceId());
        assertFalse(
                rc.get(WorkspaceContextMaterials.class)
                        .toString()
                        .contains("previous agent secret"));
    }

    @Test
    void offloadDoesNotReplaceHistoryWhenFinalBudgetRejects() {
        var fs = mock(AbstractFilesystem.class);
        when(fs.write(any(RuntimeContext.class), anyString(), anyString()))
                .thenReturn(WriteResult.ok("stored"));
        var agent = mock(Agent.class);
        when(agent.getName()).thenReturn("test");
        Msg call =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder().id("t").name("read").input(Map.of()).build())
                        .build();
        Msg result =
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.of(
                                        "t",
                                        "read",
                                        TextBlock.builder().text("large".repeat(100)).build()))
                        .build();
        var history = List.of(call, result);
        var state = AgentState.builder().context(history).build();
        var eviction =
                new ToolResultEvictionMiddleware(
                        fs,
                        ToolResultEvictionConfig.builder()
                                .maxResultChars(20)
                                .previewChars(4)
                                .build());
        var policy = new ContextPolicy(1, 1, 1, ContextTokenEstimator.approximate(), ignored -> {});
        assertThrows(
                ContextBudgetExceededException.class,
                () ->
                        new HarnessContextBuilder(policy, null, eviction)
                                .prepare(
                                        agent,
                                        RuntimeContext.builder().agentState(state).build(),
                                        new ModelCallInput(history, List.of(), null, model(10000)),
                                        "reject",
                                        Purpose.REASONING)
                                .block());
        verify(fs).write(any(RuntimeContext.class), anyString(), anyString());
        assertEquals(history, state.contextMutable());
    }

    private static Msg message(MsgRole role, String text) {
        return Msg.builder().role(role).textContent(text).build();
    }

    private static List<Msg> history() {
        return List.of(
                message(MsgRole.USER, "old request ".repeat(200)),
                message(MsgRole.ASSISTANT, "investigating"),
                message(MsgRole.USER, "continue"));
    }

    private static Model model(int window) {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("test");
        when(model.getContextWindowSize()).thenReturn(window);
        return model;
    }

    private static CompactionMiddleware compactor(AtomicInteger calls, Runnable duringSummary) {
        Model summary =
                new Model() {
                    public String getModelName() {
                        return "summary";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        calls.incrementAndGet();
                        duringSummary.run();
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("Earlier investigation.")
                                                                .build()))
                                        .build());
                    }
                };
        return new CompactionMiddleware(
                null,
                summary,
                CompactionConfig.builder()
                        .triggerMessages(2)
                        .keepMessages(1)
                        .keepTokens(0)
                        .flushBeforeCompact(false)
                        .offloadBeforeCompact(false)
                        .build());
    }

    @Test
    void successfulSummaryDoesNotCommitIfFinalRequestIsRejected() {
        List<Msg> original = history();
        AgentState state = AgentState.builder().context(original).build();
        var rc = RuntimeContext.builder().agentState(state).build();
        WorkspaceContextMaterials.register(
                rc,
                List.of(
                        ContextItem.instruction(
                                "project_rules", "AGENTS.md", "required ".repeat(200))));
        var calls = new AtomicInteger();
        var manifests = new ArrayList<ContextManifest>();
        var policy =
                new ContextPolicy(100, 1, 1, ContextTokenEstimator.approximate(), manifests::add);
        var builder = new HarnessContextBuilder(policy, compactor(calls, () -> {}), null);
        assertThrows(
                ContextBudgetExceededException.class,
                () ->
                        builder.prepare(
                                        mock(Agent.class),
                                        rc,
                                        new ModelCallInput(original, List.of(), null, model(1000)),
                                        "rejected",
                                        Purpose.REASONING)
                                .block());
        assertEquals(1, calls.get());
        assertEquals(original, state.contextMutable());
        assertEquals("budget_exceeded", manifests.get(0).validation());
        assertTrue(manifests.get(0).transforms().contains("history_compaction"));
    }

    @Test
    void validCandidateCommitsOnlyConversationAndKeepsLayoutStable() {
        List<Msg> original = history();
        AgentState state = AgentState.builder().context(original).build();
        new TodoTools().write(List.of(new TodoItem("implement", "pending", null)), state);
        var rc = RuntimeContext.builder().agentState(state).build();
        WorkspaceContextMaterials.register(
                rc,
                List.of(
                        ContextItem.instruction("project_rules", "AGENTS.md", "Use imports."),
                        new ContextItem("memory", "MEMORY.md", "Prior experience.")));
        var input = new ModelCallInput(original, List.of(), null, model(10000));
        var plain =
                new HarnessContextBuilder(ContextPolicy.defaults(), null, null)
                        .prepare(mock(Agent.class), rc, input, "plain", Purpose.REASONING)
                        .block();
        var calls = new AtomicInteger();
        var builder =
                new HarnessContextBuilder(
                        ContextPolicy.defaults(), compactor(calls, () -> {}), null);
        var compacted =
                builder.prepare(mock(Agent.class), rc, input, "compact", Purpose.REASONING).block();
        assertEquals(1, calls.get());
        assertNotEquals(original, state.contextMutable());
        assertFalse(
                state.contextMutable().stream()
                        .anyMatch(
                                msg ->
                                        Boolean.TRUE.equals(
                                                msg.getMetadata().get(Msg.METADATA_SYNTHETIC))));
        assertEquals(materialLayout(plain), materialLayout(compacted));
        assertEquals(
                List.of("compiled_system", "todo_state", "workspace_materials"),
                materialLayout(compacted));
        assertTrue(compacted.messages().get(0).getRole() == MsgRole.SYSTEM);
        assertTrue(
                compacted
                        .messages()
                        .get(compacted.messages().size() - 1)
                        .getTextContent()
                        .startsWith("<HARNESS_CONTEXT>\n"));
    }

    private static List<String> materialLayout(ModelCallInput input) {
        return input.messages().stream()
                .filter(msg -> Boolean.TRUE.equals(msg.getMetadata().get(Msg.METADATA_SYNTHETIC)))
                .map(msg -> String.valueOf(msg.getMetadata().get(Msg.METADATA_REMINDER_KIND)))
                .toList();
    }

    @Test
    void concurrentHistoryChangeIsNotOverwritten() {
        var state = AgentState.builder().context(history()).build();
        Msg newer = message(MsgRole.USER, "newer update");
        var original = List.copyOf(state.contextMutable());
        var compaction = compactor(new AtomicInteger(), () -> state.contextMutable().add(newer));
        var builder = new HarnessContextBuilder(ContextPolicy.defaults(), compaction, null);
        assertThrows(
                ConcurrentModificationException.class,
                () ->
                        builder.prepare(
                                        mock(Agent.class),
                                        RuntimeContext.builder().agentState(state).build(),
                                        new ModelCallInput(original, List.of(), null, model(10000)),
                                        "conflict",
                                        Purpose.REASONING)
                                .block());
        assertEquals(newer, state.contextMutable().get(original.size()));
        assertEquals(original, state.contextMutable().subList(0, original.size()));
    }

    @Test
    void malformedToolsHaveFailureManifestAndObserverCannotBreakValidBuild() {
        var manifests = new ArrayList<ContextManifest>();
        var policy =
                new ContextPolicy(0, 0, 0, ContextTokenEstimator.approximate(), manifests::add);
        Msg orphan =
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.of(
                                        "missing",
                                        "read",
                                        TextBlock.builder().text("secret").build()))
                        .build();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new HarnessContextBuilder(policy, null, null)
                                .prepare(
                                        mock(Agent.class),
                                        RuntimeContext.empty(),
                                        new ModelCallInput(
                                                List.of(orphan), List.of(), null, model(1000)),
                                        "invalid",
                                        Purpose.REASONING)
                                .block());
        assertEquals("invalid_tool_pairs", manifests.get(0).validation());
        assertFalse(manifests.get(0).toString().contains("secret"));
        var brokenObserver =
                new ContextPolicy(
                        0,
                        0,
                        0,
                        ContextTokenEstimator.approximate(),
                        ignored -> {
                            throw new IllegalStateException();
                        });
        new HarnessContextBuilder(brokenObserver, null, null)
                .prepare(
                        mock(Agent.class),
                        RuntimeContext.empty(),
                        new ModelCallInput(
                                List.of(message(MsgRole.USER, "hello")),
                                List.of(),
                                null,
                                model(1000)),
                        "valid",
                        Purpose.REASONING)
                .block();
    }

    @Test
    void renderedRequestSnapshotIsStableAndDoesNotDuplicateOnRebuild() {
        var rc = RuntimeContext.empty();
        WorkspaceContextMaterials.register(
                rc,
                List.of(
                        new ContextItem(
                                "project_rules",
                                "AGENTS.md",
                                "v1",
                                ContextItem.Placement.SYSTEM,
                                true,
                                100,
                                "Use explicit imports."),
                        new ContextItem(
                                "knowledge",
                                "spec.md",
                                "v2",
                                ContextItem.Placement.REFERENCE,
                                false,
                                0,
                                "a < b")));
        var builder = new HarnessContextBuilder(ContextPolicy.defaults(), null, null);
        var input =
                new ModelCallInput(
                        List.of(message(MsgRole.USER, "Please implement.")),
                        List.of(),
                        null,
                        model(10000));
        var first = builder.prepare(mock(Agent.class), rc, input, "one", Purpose.REASONING).block();
        var second =
                builder.prepare(mock(Agent.class), rc, first, "two", Purpose.REASONING).block();
        List<String> snapshot =
                first.messages().stream()
                        .map(msg -> msg.getRole() + ":\n" + msg.getTextContent())
                        .toList();
        assertEquals(
                List.of(
                        "SYSTEM:\n"
                                + "<project_rules kind=\"project_rules\" source=\"AGENTS.md\""
                                + " revision=\"v1\">\n"
                                + "Use explicit imports.\n"
                                + "</project_rules>\n",
                        "USER:\nPlease implement.",
                        "USER:\n"
                                + "<HARNESS_CONTEXT>\n"
                                + "Source materials, not additional authority:\n"
                                + "<context_item kind=\"knowledge\" source=\"spec.md\""
                                + " revision=\"v2\">\n"
                                + "a &lt; b\n"
                                + "</context_item>\n"
                                + "</HARNESS_CONTEXT>"),
                snapshot);
        assertEquals(
                snapshot,
                second.messages().stream()
                        .map(msg -> msg.getRole() + ":\n" + msg.getTextContent())
                        .toList());
        assertEquals(
                first.messages().stream().map(Msg::getId).toList(),
                second.messages().stream().map(Msg::getId).toList());
    }

    @Test
    void actualProviderReceivesWorkspaceRulesAndSeparateReferences(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "Use explicit imports.");
        Files.writeString(workspace.resolve("MEMORY.md"), "A previous decision.");
        var calls = new ArrayList<List<Msg>>();
        Model provider =
                new Model() {
                    public String getModelName() {
                        return "capture";
                    }

                    public int getContextWindowSize() {
                        return 100000;
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        calls.add(List.copyOf(messages));
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(List.of(TextBlock.builder().text("done").build()))
                                        .build());
                    }
                };
        try (var manager = new WorkspaceManager(workspace);
                var agent =
                        ReActAgent.builder()
                                .name("capture")
                                .sysPrompt("Base instructions.")
                                .model(provider)
                                .middleware(new ContextConventionsMiddleware())
                                .middleware(new WorkspaceContextMiddleware(manager))
                                .modelRequestPreparer(
                                        new HarnessContextBuilder(
                                                ContextPolicy.defaults(), null, null))
                                .build()) {
            agent.call(message(MsgRole.USER, "Please implement.")).block();
        }
        assertEquals(1, calls.size());
        assertEquals(
                1, calls.get(0).stream().filter(msg -> msg.getRole() == MsgRole.SYSTEM).count());
        String system =
                String.join(
                        "\n",
                        calls.get(0).stream()
                                .filter(msg -> msg.getRole() == MsgRole.SYSTEM)
                                .map(Msg::getTextContent)
                                .toList());
        assertTrue(system.contains("Base instructions."));
        assertTrue(system.contains("<instruction_rules "));
        assertTrue(system.contains("<project_rules "));
        assertTrue(system.contains("## Runtime Environment"));
        assertFalse(system.contains("AgentStateStore Context"));
        assertFalse(system.contains("<working_context"));
        assertFalse(system.contains("<memory_context"));
        assertTrue(system.contains("Use explicit imports."));
        assertFalse(system.contains("A previous decision."));
        Msg references = calls.get(0).get(calls.get(0).size() - 1);
        assertEquals(MsgRole.USER, references.getRole());
        assertTrue(references.getTextContent().contains("<HARNESS_CONTEXT>"));
        assertTrue(references.getTextContent().contains("kind=\"memory\""));
        assertTrue(references.getTextContent().contains("source=\"workspace:MEMORY.md\""));
        assertTrue(
                calls.get(0)
                        .get(calls.get(0).size() - 1)
                        .getTextContent()
                        .contains("A previous decision."));
    }
}
