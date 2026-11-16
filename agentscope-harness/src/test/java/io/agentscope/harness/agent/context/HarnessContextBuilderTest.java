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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ModelRequestPreparer;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.builtin.TodoTools;
import io.agentscope.core.tool.builtin.TodoTools.TodoItem;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.mcp.McpTool;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.middleware.CompactionMiddleware;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class HarnessContextBuilderTest {
    @Test
    void optionalMemoryYieldsToCurrentUserAndManifestRecordsOmission() {
        var manifests = new ArrayList<ContextManifest>();
        var policy =
                new ContextPolicy(100, 20, 1, ContextTokenEstimator.approximate(), manifests::add);
        var rc = RuntimeContext.builder().agentState(AgentState.builder().build()).build();
        rc.put(
                WorkspaceContextMaterials.class,
                new WorkspaceContextMaterials(
                        List.of(
                                new ContextItem(
                                        "memory", "workspace:MEMORY.md", "old".repeat(2000)))));
        var prepared =
                new HarnessContextBuilder(policy, null, null)
                        .prepare(
                                mock(Agent.class),
                                rc,
                                input(1000, "current request"),
                                "c",
                                ModelRequestPreparer.Purpose.REASONING)
                        .block();
        assertEquals(1, prepared.messages().size());
        assertTrue(
                manifests.get(0).transforms().contains("omitted_for_budget:workspace:MEMORY.md"));
        assertFalse(
                manifests.get(0).items().stream()
                        .anyMatch(item -> item.sourceType().equals("memory")));
    }

    @Test
    void failedCompactionPreservesStateAndStillRejectsOverBudgetRequest() {
        var state = AgentState.builder().build();
        List<Msg> history =
                List.of(
                        Msg.builder().role(MsgRole.USER).textContent("a".repeat(2000)).build(),
                        Msg.builder().role(MsgRole.ASSISTANT).textContent("investigating").build(),
                        Msg.builder().role(MsgRole.USER).textContent("continue").build());
        state.contextMutable().addAll(history);
        Model summary =
                new Model() {
                    public String getModelName() {
                        return "summary";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return Flux.error(new IllegalStateException("summary unavailable"));
                    }
                };
        var config =
                CompactionConfig.builder()
                        .triggerMessages(2)
                        .keepMessages(1)
                        .keepTokens(0)
                        .flushBeforeCompact(false)
                        .build();
        var compaction = new CompactionMiddleware(null, summary, config);
        var request = new ModelCallInput(history, List.of(), null, model(100));
        StepVerifier.create(
                        new HarnessContextBuilder(ContextPolicy.defaults(), compaction, null)
                                .prepare(
                                        mock(Agent.class),
                                        RuntimeContext.builder().agentState(state).build(),
                                        request,
                                        "c",
                                        ModelRequestPreparer.Purpose.REASONING))
                .expectError(ContextBudgetExceededException.class)
                .verify();
        assertEquals(history, state.contextMutable());
    }

    @Test
    void numericPolicyRejectsUnknownNegativeAndFractionalLimits() {
        assertEquals(1200, ContextPolicy.fromMap(Map.of("maxInputTokens", 1200)).maxInputTokens());
        assertThrows(
                IllegalArgumentException.class,
                () -> ContextPolicy.fromMap(Map.of("maxInputTokens", -1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> ContextPolicy.fromMap(Map.of("maxInputTokens", 1.5)));
        assertThrows(
                IllegalArgumentException.class, () -> ContextPolicy.fromMap(Map.of("unknown", 1)));
    }

    @Test
    void blankItemContributesNoManifestEntry() {
        var manifests = new ArrayList<ContextManifest>();
        var policy =
                new ContextPolicy(0, 0, 0, ContextTokenEstimator.approximate(), manifests::add);
        var rc = RuntimeContext.builder().agentState(AgentState.builder().build()).build();
        rc.put(
                WorkspaceContextMaterials.class,
                new WorkspaceContextMaterials(List.of(new ContextItem("memory", "blank", "   "))));
        new HarnessContextBuilder(policy, null, null)
                .prepare(
                        mock(Agent.class),
                        rc,
                        input(100000, "hello"),
                        "c",
                        ModelRequestPreparer.Purpose.REASONING)
                .block();
        assertTrue(
                manifests.get(0).items().stream()
                        .noneMatch(item -> item.sourceId().equals("blank")));
    }

    @Test
    void manifestRecordsContentCharsForEverySourceType() {
        var manifests = new ArrayList<ContextManifest>();
        var policy =
                new ContextPolicy(0, 0, 0, ContextTokenEstimator.approximate(), manifests::add);
        var rc = RuntimeContext.builder().agentState(AgentState.builder().build()).build();
        var memory = new ContextItem("memory", "workspace:MEMORY.md", "remember this fact");
        rc.put(WorkspaceContextMaterials.class, new WorkspaceContextMaterials(List.of(memory)));
        var toolSchema =
                ToolSchema.builder()
                        .name("lookup")
                        .description("look something up")
                        .parameters(Map.of())
                        .build();
        var userMsg = Msg.builder().role(MsgRole.USER).textContent("hi").build();
        var request =
                new ModelCallInput(
                        List.of(userMsg),
                        List.of(toolSchema),
                        GenerateOptions.builder().maxTokens(20).build(),
                        model(100000));
        new HarnessContextBuilder(policy, null, null)
                .prepare(
                        mock(Agent.class), rc, request, "c", ModelRequestPreparer.Purpose.REASONING)
                .block();
        var items = manifests.get(0).items();
        assertEquals(
                memory.content().length(),
                items.stream()
                        .filter(item -> item.sourceId().equals("workspace:MEMORY.md"))
                        .findFirst()
                        .orElseThrow()
                        .contentChars());
        assertEquals(
                JsonUtils.getJsonCodec().toJson(toolSchema).length(),
                items.stream()
                        .filter(item -> item.sourceId().equals("lookup"))
                        .findFirst()
                        .orElseThrow()
                        .contentChars());
        assertEquals(
                JsonUtils.getJsonCodec().toJson(userMsg.getContent()).length(),
                items.stream()
                        .filter(item -> item.sourceId().equals(userMsg.getId()))
                        .findFirst()
                        .orElseThrow()
                        .contentChars());
        assertEquals("tool_system", itemFor(items, "lookup").sourceType());
        assertEquals("message_user", itemFor(items, userMsg.getId()).sourceType());
    }

    @Test
    void toolOriginsAreSplitBetweenMcpAndFramework() {
        var manifests = new ArrayList<ContextManifest>();
        var policy =
                new ContextPolicy(0, 0, 0, ContextTokenEstimator.approximate(), manifests::add);
        var rc = RuntimeContext.builder().agentState(AgentState.builder().build()).build();
        McpTool mcp =
                new McpTool(
                        "mcp_search",
                        "search",
                        "search the web",
                        Map.of(),
                        null,
                        mock(McpClientWrapper.class),
                        null,
                        "search-server",
                        true,
                        null);
        var toolkit = new Toolkit();
        toolkit.registerAgentTool(mcp);
        var agent = mock(Agent.class);
        when(agent.getToolkit()).thenReturn(toolkit);
        var request =
                new ModelCallInput(
                        List.of(Msg.builder().role(MsgRole.USER).textContent("hi").build()),
                        List.of(toolSchema("mcp_search"), toolSchema("shell")),
                        GenerateOptions.builder().maxTokens(20).build(),
                        model(100000));
        new HarnessContextBuilder(policy, null, null)
                .prepare(agent, rc, request, "c", ModelRequestPreparer.Purpose.REASONING)
                .block();
        var items = manifests.get(0).items();
        assertEquals("tool_mcp", itemFor(items, "mcp_search").sourceType());
        assertEquals("tool_system", itemFor(items, "shell").sourceType());
    }

    @Test
    void messagesAreSplitByRole() {
        var manifests = new ArrayList<ContextManifest>();
        var policy =
                new ContextPolicy(0, 0, 0, ContextTokenEstimator.approximate(), manifests::add);
        var rc = RuntimeContext.builder().agentState(AgentState.builder().build()).build();
        Msg user = Msg.builder().role(MsgRole.USER).textContent("hello").build();
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
                                        "t", "read", TextBlock.builder().text("ok").build()))
                        .build();
        var request =
                new ModelCallInput(
                        List.of(user, call, result),
                        List.of(),
                        GenerateOptions.builder().maxTokens(20).build(),
                        model(100000));
        new HarnessContextBuilder(policy, null, null)
                .prepare(
                        mock(Agent.class), rc, request, "c", ModelRequestPreparer.Purpose.REASONING)
                .block();
        var items = manifests.get(0).items();
        assertEquals("message_user", itemFor(items, user.getId()).sourceType());
        assertEquals("message_assistant", itemFor(items, call.getId()).sourceType());
        assertEquals("message_tool", itemFor(items, result.getId()).sourceType());
    }

    @Test
    void compactionSummaryIsNotReportedAsUserMessage() {
        var manifests = new ArrayList<ContextManifest>();
        var policy =
                new ContextPolicy(0, 0, 0, ContextTokenEstimator.approximate(), manifests::add);
        var rc = RuntimeContext.builder().agentState(AgentState.builder().build()).build();
        Msg summary =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name(ConversationCompactor.SUMMARY_MSG_NAME)
                        .textContent("condensed history")
                        .build();
        var request =
                new ModelCallInput(
                        List.of(summary),
                        List.of(),
                        GenerateOptions.builder().maxTokens(20).build(),
                        model(100000));
        new HarnessContextBuilder(policy, null, null)
                .prepare(
                        mock(Agent.class), rc, request, "c", ModelRequestPreparer.Purpose.REASONING)
                .block();
        assertEquals(
                "message_summary", itemFor(manifests.get(0).items(), summary.getId()).sourceType());
    }

    @Test
    void systemPromptIsMeasuredApartFromMaterials() {
        var manifests = new ArrayList<ContextManifest>();
        var policy =
                new ContextPolicy(0, 0, 0, ContextTokenEstimator.approximate(), manifests::add);
        var rc = RuntimeContext.builder().agentState(AgentState.builder().build()).build();
        rc.put(
                WorkspaceContextMaterials.class,
                new WorkspaceContextMaterials(
                        List.of(
                                new ContextItem(
                                        "memory", "workspace:MEMORY.md", "remember this"))));
        Msg system = Msg.builder().role(MsgRole.SYSTEM).textContent("base prompt").build();
        Msg user = Msg.builder().role(MsgRole.USER).textContent("hi").build();
        var request =
                new ModelCallInput(
                        List.of(system, user),
                        List.of(),
                        GenerateOptions.builder().maxTokens(20).build(),
                        model(100000));
        new HarnessContextBuilder(policy, null, null)
                .prepare(
                        mock(Agent.class), rc, request, "c", ModelRequestPreparer.Purpose.REASONING)
                .block();
        var items = manifests.get(0).items();
        assertEquals(
                JsonUtils.getJsonCodec().toJson(system.getContent()).length(),
                itemFor(items, system.getId()).contentChars());
        assertEquals("system_prompt", itemFor(items, system.getId()).sourceType());
        assertEquals(
                1,
                items.stream().filter(item -> "system_prompt".equals(item.sourceType())).count());
        assertEquals(
                1,
                items.stream()
                        .filter(item -> "workspace:MEMORY.md".equals(item.sourceId()))
                        .count());
        assertTrue(
                items.stream().noneMatch(item -> item.sourceType().equals("workspace_materials")));
    }

    @Test
    void systemMaterialsAloneDoNotCreateASystemPromptSegment() {
        var manifests = new ArrayList<ContextManifest>();
        var policy =
                new ContextPolicy(0, 0, 0, ContextTokenEstimator.approximate(), manifests::add);
        var rc = RuntimeContext.builder().agentState(AgentState.builder().build()).build();
        var rules = ContextItem.instruction("project_rules", "harness:rules", "follow the rules");
        rc.put(WorkspaceContextMaterials.class, new WorkspaceContextMaterials(List.of(rules)));
        var request =
                new ModelCallInput(
                        List.of(Msg.builder().role(MsgRole.USER).textContent("hi").build()),
                        List.of(),
                        GenerateOptions.builder().maxTokens(20).build(),
                        model(100000));
        new HarnessContextBuilder(policy, null, null)
                .prepare(
                        mock(Agent.class), rc, request, "c", ModelRequestPreparer.Purpose.REASONING)
                .block();
        var items = manifests.get(0).items();
        assertTrue(items.stream().noneMatch(item -> "system_prompt".equals(item.sourceType())));
        assertEquals(
                1, items.stream().filter(item -> "harness:rules".equals(item.sourceId())).count());
        assertEquals("follow the rules".length(), itemFor(items, "harness:rules").contentChars());
        assertEquals(
                "follow the rules".length(),
                shareFor(ContextUsage.breakdown(manifests.get(0)), ContextUsage.SYSTEM_PROMPT)
                        .chars());
    }

    @Test
    void breakdownGroupsSegmentsAndSumsToTotal() {
        var manifests = new ArrayList<ContextManifest>();
        var policy =
                new ContextPolicy(0, 0, 0, ContextTokenEstimator.approximate(), manifests::add);
        var rc = RuntimeContext.builder().agentState(AgentState.builder().build()).build();
        rc.put(
                WorkspaceContextMaterials.class,
                new WorkspaceContextMaterials(
                        List.of(
                                new ContextItem(
                                        "memory", "workspace:MEMORY.md", "remember this"))));
        Msg user = Msg.builder().role(MsgRole.USER).textContent("hi").build();
        var request =
                new ModelCallInput(
                        List.of(user),
                        List.of(toolSchema("shell")),
                        GenerateOptions.builder().maxTokens(20).build(),
                        model(100000));
        new HarnessContextBuilder(policy, null, null)
                .prepare(
                        mock(Agent.class), rc, request, "c", ModelRequestPreparer.Purpose.REASONING)
                .block();
        var manifest = manifests.get(0);
        var shares = ContextUsage.breakdown(manifest);
        assertEquals(
                JsonUtils.getJsonCodec().toJson(user.getContent()).length(),
                shareFor(shares, ContextUsage.MESSAGES).chars());
        assertEquals(
                JsonUtils.getJsonCodec().toJson(toolSchema("shell")).length(),
                shareFor(shares, ContextUsage.SYSTEM_TOOLS).chars());
        assertEquals("remember this".length(), shareFor(shares, ContextUsage.OTHER).chars());
        assertEquals(3, shares.size());
        assertEquals(
                manifest.items().stream().mapToLong(ContextManifest.Item::contentChars).sum(),
                shares.stream().mapToLong(ContextUsage.Share::chars).sum());
        assertEquals(1.0, shares.stream().mapToDouble(ContextUsage.Share::ratio).sum(), 1e-9);
        for (int index = 1; index < shares.size(); index++) {
            assertTrue(shares.get(index - 1).chars() >= shares.get(index).chars());
        }
    }

    @Test
    void breakdownOmitsEmptyCategoriesAndHandlesMissingManifest() {
        assertTrue(ContextUsage.breakdown(null).isEmpty());
        var manifest =
                new ContextManifest(
                        "c",
                        "REASONING",
                        "test",
                        "v",
                        0,
                        0,
                        "approximate",
                        false,
                        0,
                        List.of(
                                new ContextManifest.Item("message_user", "u", "h", 5),
                                new ContextManifest.Item("memory", "m", "h", 0),
                                new ContextManifest.Item(null, "n", "h", 3),
                                new ContextManifest.Item("message_user", "neg", "h", -4)),
                        List.of(),
                        "passed");
        var shares = ContextUsage.breakdown(manifest);
        assertEquals(2, shares.size());
        assertEquals(ContextUsage.MESSAGES, shares.get(0).source());
        assertEquals(5, shares.get(0).chars());
        assertEquals(5.0 / 8.0, shares.get(0).ratio(), 1e-9);
        assertEquals(ContextUsage.OTHER, shares.get(1).source());
        assertEquals(3, shares.get(1).chars());
    }

    private ContextUsage.Share shareFor(List<ContextUsage.Share> shares, String source) {
        return shares.stream()
                .filter(share -> share.source().equals(source))
                .findFirst()
                .orElseThrow();
    }

    private ToolSchema toolSchema(String name) {
        return ToolSchema.builder()
                .name(name)
                .description("does " + name)
                .parameters(Map.of())
                .build();
    }

    private ContextManifest.Item itemFor(List<ContextManifest.Item> items, String sourceId) {
        return items.stream()
                .filter(item -> item.sourceId().equals(sourceId))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void contentCharsAreRecordedPerMaterial() {
        var manifests = new ArrayList<ContextManifest>();
        var policy =
                new ContextPolicy(0, 0, 0, ContextTokenEstimator.approximate(), manifests::add);
        var rc = RuntimeContext.builder().agentState(AgentState.builder().build()).build();
        rc.put(
                WorkspaceContextMaterials.class,
                new WorkspaceContextMaterials(
                        List.of(
                                new ContextItem("memory", "a", "x".repeat(10)),
                                new ContextItem("memory", "b", "y".repeat(20)),
                                new ContextItem("memory", "c", "z".repeat(30)))));
        new HarnessContextBuilder(policy, null, null)
                .prepare(
                        mock(Agent.class),
                        rc,
                        input(100000, "hello"),
                        "c",
                        ModelRequestPreparer.Purpose.REASONING)
                .block();
        var items = manifests.get(0).items();
        Map.of("a", 10, "b", 20, "c", 30)
                .forEach(
                        (sourceId, chars) ->
                                assertEquals(
                                        chars.intValue(),
                                        items.stream()
                                                .filter(item -> item.sourceId().equals(sourceId))
                                                .findFirst()
                                                .orElseThrow()
                                                .contentChars()));
    }

    @Test
    void itemConstructorsDefaultContentCharsToZero() {
        assertEquals(0, new ContextManifest.Item("message", "id", "hash").contentChars());
        var sized = new ContextManifest.Item("message", "id", "hash", 7);
        assertEquals(7, sized.contentChars());
        assertNull(sized.revision());
        assertNull(sized.placement());
        assertFalse(sized.required());
        assertEquals(0, sized.priority());
    }

    @Test
    void priorSevenArgConstructorStillWorksAndReportsUnknownSize() {
        var legacy =
                new ContextManifest.Item(
                        "memory", "workspace:MEMORY.md", "hash", "rev-1", "REFERENCE", true, 5);
        assertEquals("memory", legacy.sourceType());
        assertEquals("rev-1", legacy.revision());
        assertEquals("REFERENCE", legacy.placement());
        assertTrue(legacy.required());
        assertEquals(5, legacy.priority());
        assertEquals(0, legacy.contentChars());
    }

    private Model model(int window) {
        Model model = mock(Model.class);
        when(model.getContextWindowSize()).thenReturn(window);
        when(model.getModelName()).thenReturn("test");
        return model;
    }

    private ModelCallInput input(int window, String text) {
        return new ModelCallInput(
                List.of(Msg.builder().role(MsgRole.USER).textContent(text).build()),
                List.of(),
                GenerateOptions.builder().maxTokens(20).build(),
                model(window));
    }

    @Test
    void finalBudgetIncludesToolSchemasAndActualModel() {
        var builder = new HarnessContextBuilder(ContextPolicy.defaults(), null, null);
        var agent = mock(Agent.class);
        builder.prepare(
                        agent,
                        RuntimeContext.empty(),
                        input(1000, "hello"),
                        "1",
                        ModelRequestPreparer.Purpose.REASONING)
                .block();
        var original = input(100, "hello");
        var request =
                new ModelCallInput(
                        original.messages(),
                        List.of(
                                ToolSchema.builder()
                                        .name("large")
                                        .description("x".repeat(1000))
                                        .parameters(Map.of())
                                        .build()),
                        original.options(),
                        original.model());
        StepVerifier.create(
                        builder.prepare(
                                agent,
                                RuntimeContext.empty(),
                                request,
                                "2",
                                ModelRequestPreparer.Purpose.REASONING))
                .expectError(ContextBudgetExceededException.class)
                .verify();
    }

    @Test
    void finalProjectionAndManifestArePerSessionAndTransient() {
        List<ContextManifest> manifests = new ArrayList<>();
        var policy =
                new ContextPolicy(0, 0, 0, ContextTokenEstimator.approximate(), manifests::add);
        var builder = new HarnessContextBuilder(policy, null, null);
        var state = AgentState.builder().build();
        new TodoTools().write(List.of(new TodoItem("private task", "pending", null)), state);
        var rc = RuntimeContext.builder().agentState(state).build();
        var prepared =
                builder.prepare(
                                mock(Agent.class),
                                rc,
                                input(4096, "hello"),
                                "call-7",
                                ModelRequestPreparer.Purpose.REASONING)
                        .block();
        assertEquals(2, prepared.messages().size());
        assertTrue(state.contextMutable().isEmpty());
        assertEquals("call-7", manifests.get(0).callId());
        assertFalse(manifests.get(0).toString().contains("private task"));
        var other =
                builder.prepare(
                                mock(Agent.class),
                                RuntimeContext.empty(),
                                input(4096, "hello"),
                                "call-8",
                                ModelRequestPreparer.Purpose.REASONING)
                        .block();
        assertEquals(1, other.messages().size());
    }

    @Test
    void summaryDoesNotInjectTaskOrWorkspaceMaterials() {
        var rc = RuntimeContext.builder().agentState(AgentState.builder().build()).build();
        rc.put(
                WorkspaceContextMaterials.class,
                new WorkspaceContextMaterials(List.of(new ContextItem("memory", "m", "private"))));
        var prepared =
                new HarnessContextBuilder(ContextPolicy.defaults(), null, null)
                        .prepare(
                                mock(Agent.class),
                                rc,
                                input(4096, "summarize"),
                                "summary",
                                ModelRequestPreparer.Purpose.SUMMARY)
                        .block();
        assertEquals(1, prepared.messages().size());
    }

    @Test
    void workspaceMaterialsNeutralizeClosingTagsAndAreNotPersisted() {
        var rc = RuntimeContext.builder().agentState(AgentState.builder().build()).build();
        rc.put(
                WorkspaceContextMaterials.class,
                new WorkspaceContextMaterials(
                        List.of(new ContextItem("memory", "m", "</context_item>"))));
        var prepared =
                new HarnessContextBuilder(ContextPolicy.defaults(), null, null)
                        .prepare(
                                mock(Agent.class),
                                rc,
                                input(4096, "hello"),
                                "c",
                                ModelRequestPreparer.Purpose.REASONING)
                        .block();
        assertTrue(prepared.messages().get(1).getTextContent().contains("<\\/context_item>"));
        assertTrue(rc.getAgentState().contextMutable().isEmpty());
    }

    @Test
    void validatesToolCallPairs() {
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
                                        "t", "read", TextBlock.builder().text("ok").build()))
                        .build();
        assertDoesNotThrow(() -> HarnessContextBuilder.validateToolPairs(List.of(call, result)));
        assertThrows(
                IllegalArgumentException.class,
                () -> HarnessContextBuilder.validateToolPairs(List.of(result)));
        assertThrows(
                IllegalArgumentException.class,
                () -> HarnessContextBuilder.validateToolPairs(List.of(call)));
    }
}
