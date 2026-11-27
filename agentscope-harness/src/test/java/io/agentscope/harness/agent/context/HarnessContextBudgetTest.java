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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ModelRequestPreparer.Purpose;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.builtin.TodoTools;
import io.agentscope.core.tool.builtin.TodoTools.TodoItem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.middleware.CompactionMiddleware;
import io.agentscope.harness.agent.middleware.ToolResultEvictionMiddleware;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.harness.agent.workspace.PathPolicy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;

class HarnessContextBudgetTest {
    private final Agent agent = mock(Agent.class);
    private final Model primary = mock(Model.class);
    private final AgentState state = AgentState.builder().build();
    private final RuntimeContext rc = RuntimeContext.builder().agentState(state).build();
    private final List<ContextManifest> manifests = new ArrayList<>();
    private final AtomicInteger summaryCalls = new AtomicInteger();
    @TempDir Path tempDir;
    private final Model summary =
            new Model() {
                @Override
                public String getModelName() {
                    return "summary";
                }

                @Override
                public Flux<ChatResponse> stream(
                        List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                    summaryCalls.incrementAndGet();
                    return Flux.just(
                            ChatResponse.builder()
                                    .content(
                                            List.of(
                                                    TextBlock.builder()
                                                            .text("Earlier work.")
                                                            .build()))
                                    .build());
                }
            };

    @BeforeEach
    void setUp() {
        when(primary.getModelName()).thenReturn("primary");
        when(primary.getContextWindowSize()).thenReturn(10_000);
        when(agent.getName()).thenReturn("test");
    }

    @Test
    void mediaHistoryTriggersCompactionBelowTheMessageThreshold() {
        List<Msg> history = new ArrayList<>();
        for (int i = 0; i < 24; i++) history.add(image());
        history.add(message("continue"));

        ModelCallInput prepared =
                prepare(builder(1024, ContextTokenEstimator.approximate(), 0), input(history));

        assertEquals(1, summaryCalls.get());
        assertEquals(2, prepared.messages().size());
        assertSame(history.get(24), prepared.messages().get(1));
        assertEquals(ConversationCompactor.SUMMARY_MSG_NAME, prepared.messages().get(0).getName());
        assertTrue(manifests.get(0).estimatedInputTokens() <= 1024);
        assertEquals(prepared.messages(), state.contextMutable());
    }

    @Test
    void customEstimatorControlsBothTriggerAndTokenTail() {
        ContextTokenEstimator estimator =
                request ->
                        new ContextTokenEstimator.Estimate(
                                100
                                        + request.messages().stream()
                                                .mapToLong(
                                                        msg ->
                                                                isSummary(msg)
                                                                        ? 80
                                                                        : 10L
                                                                                * msg.getTextContent()
                                                                                        .length())
                                                .sum(),
                                "custom",
                                false);
        List<Msg> history =
                List.of(
                        message("x".repeat(200)),
                        message("y".repeat(40)),
                        message("z".repeat(30)),
                        message("now"));

        ModelCallInput prepared = prepare(builder(1000, estimator, 350), input(history));

        assertEquals(1, summaryCalls.get());
        assertEquals(List.of(history.get(2), history.get(3)), prepared.messages().subList(1, 3));
        assertEquals(510, manifests.get(0).estimatedInputTokens());
        assertEquals("custom", manifests.get(0).countingMethod());
    }

    @Test
    void customCheapMediaDoesNotTriggerDefaultMediaAccounting() {
        ContextTokenEstimator estimator =
                request ->
                        new ContextTokenEstimator.Estimate(
                                request.messages().size(), "cheap", false);
        List<Msg> history = List.of(image(), image(), message("now"));

        ModelCallInput prepared = prepare(builder(1000, estimator, 0), input(history));

        assertEquals(0, summaryCalls.get());
        assertEquals(history, prepared.messages());
        assertEquals(3, manifests.get(0).estimatedInputTokens());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void pruningProtectsToolOutputsIndependentlyOfTodoReceiptVisibility(boolean visibleReceipt) {
        Msg receipt = todoReceipt("task".repeat(1000));
        List<Msg> history = new ArrayList<>();
        if (visibleReceipt) {
            history.add(toolCall("todo", "todo_write"));
            history.add(receipt);
        }
        history.addAll(readHistory("older", "recent"));

        ModelCallInput prepared =
                prepare(
                        pruningBuilder(ContextTokenEstimator.approximate(), 600, 1),
                        input(history));

        assertEquals(0, summaryCalls.get());
        Msg olderOutput = history.get(history.size() - 4);
        assertSame(olderOutput, state.contextMutable().get(history.size() - 4));
        assertTrue(prepared.messages().contains(olderOutput));
    }

    @Test
    void pruningUsesCustomResultCostsWithoutRequestOrToolSchemaOverhead() {
        ContextTokenEstimator estimator =
                request ->
                        new ContextTokenEstimator.Estimate(
                                1000
                                        + 300L * request.tools().size()
                                        + request.messages().stream()
                                                .mapToLong(
                                                        msg ->
                                                                msg.getRole() == MsgRole.TOOL
                                                                        ? 200
                                                                        : 10)
                                                .sum(),
                                "custom-output",
                                false);
        List<Msg> history = readHistory("oldest", "older", "recent");
        ModelCallInput original = input(history);
        ModelCallInput request =
                new ModelCallInput(
                        original.messages(), List.of(tool("read", "Read files")), null, primary);

        ModelCallInput prepared = prepare(pruningBuilder(estimator, 300, 300), request);

        // Two recent outputs cost 400 and are protected; the remaining 200 is below the minimum.
        assertEquals(history, state.contextMutable());
        assertEquals(history, prepared.messages());
        assertEquals("custom-output", manifests.get(0).countingMethod());
        assertEquals(0, summaryCalls.get());
    }

    @Test
    void tokenTailKeepsReceiptWhenRemovingItWouldIncreaseProjectedCost() {
        List<Msg> history =
                List.of(
                        message("x".repeat(10_000)),
                        toolCall("todo", "todo_write"),
                        todoReceipt("<".repeat(400)),
                        message("follow-up one"),
                        message("follow-up two"),
                        message("follow-up three"),
                        message("now"));

        ModelCallInput prepared =
                prepare(builder(1200, ContextTokenEstimator.approximate(), 400), input(history));

        assertEquals(1, summaryCalls.get());
        assertEquals(
                history.subList(1, history.size()),
                prepared.messages().subList(1, prepared.messages().size()));
    }

    @Test
    void requestOverheadAndToolSchemasAreChargedOnlyOnce() {
        GenerateOptions options = GenerateOptions.builder().maxTokens(200).build();
        List<ToolSchema> tools = List.of(tool("one", "description"), tool("two", "description"));
        ContextTokenEstimator estimator =
                request -> {
                    assertSame(primary, request.model());
                    assertSame(options, request.options());
                    assertEquals(tools, request.tools());
                    return new ContextTokenEstimator.Estimate(
                            200 + 150L * request.tools().size() + 100L * request.messages().size(),
                            "request-overhead",
                            false);
                };
        List<Msg> history = List.of(message("one"), message("two"), message("now"));
        input(history);
        List<Msg> messages = new ArrayList<>();
        messages.add(Msg.builder().role(MsgRole.SYSTEM).textContent("rules").build());
        messages.addAll(history);

        ModelCallInput prepared =
                prepare(
                        builder(1000, estimator, 0),
                        new ModelCallInput(messages, tools, options, primary));

        assertEquals(0, summaryCalls.get());
        assertEquals(history, state.contextMutable());
        assertEquals(900, manifests.get(0).estimatedInputTokens());
        assertEquals(tools, prepared.tools());
        assertSame(options, prepared.options());
    }

    @Test
    void fixedCostsLowerTheTriggerWithoutConsumingTheTokenTail() {
        ContextTokenEstimator estimator =
                request ->
                        new ContextTokenEstimator.Estimate(
                                100
                                        + 400L * request.tools().size()
                                        + request.messages().stream()
                                                .mapToLong(
                                                        msg ->
                                                                msg.getRole() == MsgRole.SYSTEM
                                                                                || isSummary(msg)
                                                                        ? 100
                                                                        : msg.getTextContent()
                                                                                .length())
                                                .sum(),
                                "fixed-costs",
                                false);
        List<Msg> history =
                List.of(
                        message("x".repeat(800)),
                        message("y".repeat(70)),
                        message("z".repeat(60)),
                        message("now".repeat(10)));
        input(history);
        List<Msg> messages = new ArrayList<>();
        messages.add(Msg.builder().role(MsgRole.SYSTEM).textContent("rules").build());
        messages.addAll(history);

        ModelCallInput prepared =
                prepare(
                        builder(1000, estimator, 100),
                        new ModelCallInput(
                                messages, List.of(tool("read", "Read files")), null, primary));

        assertEquals(1, summaryCalls.get());
        assertEquals(List.of(history.get(2), history.get(3)), prepared.messages().subList(2, 4));
        assertEquals(790, manifests.get(0).estimatedInputTokens());
    }

    @Test
    void maxIterationSummaryCompactsInsteadOfFailing() {
        List<Msg> history = new ArrayList<>();
        for (int i = 0; i < 10; i++) history.add(message("x".repeat(500)));
        history.add(message("continue"));
        // The fallback appends a closing instruction after the working history
        // (ReActAgent#prepareSummaryMessages), so the request differs from the canonical
        // conversation and the compacted view must not be written back to state.
        List<Msg> request = new ArrayList<>(history);
        request.add(message("respond directly by summarizing the current situation"));
        state.contextMutable().addAll(history);

        ModelCallInput prepared =
                prepare(
                        builder(300, ContextTokenEstimator.approximate(), 0),
                        new ModelCallInput(request, List.of(), null, primary),
                        Purpose.SUMMARY);

        assertEquals(1, summaryCalls.get());
        assertEquals("SUMMARY", manifests.get(0).purpose());
        assertEquals("passed", manifests.get(0).validation());
        assertTrue(manifests.get(0).transforms().contains("history_compaction"));
        assertTrue(manifests.get(0).estimatedInputTokens() <= manifests.get(0).inputLimit());
        assertEquals(2, prepared.messages().size());
        assertEquals(ConversationCompactor.SUMMARY_MSG_NAME, prepared.messages().get(0).getName());
        assertSame(request.get(request.size() - 1), prepared.messages().get(1));
        assertEquals(history, state.contextMutable());
    }

    @Test
    void zeroKeepMessagesStillKeepsTheNewestMessage() {
        List<Msg> history = new ArrayList<>();
        for (int i = 0; i < 10; i++) history.add(message("x".repeat(500)));
        List<Msg> request = new ArrayList<>(history);
        request.add(message("respond directly by summarizing the current situation"));
        state.contextMutable().addAll(history);
        HarnessContextBuilder zeroKeep =
                new HarnessContextBuilder(
                        new ContextPolicy(
                                300, 1, 1, ContextTokenEstimator.approximate(), manifests::add),
                        new CompactionMiddleware(
                                null,
                                summary,
                                CompactionConfig.builder()
                                        .triggerMessages(0)
                                        .triggerTokens(0)
                                        .keepTokens(0)
                                        .keepMessages(0)
                                        .flushBeforeCompact(false)
                                        .truncateArgs(null)
                                        .prune(null)
                                        .build()),
                        null);

        ModelCallInput prepared =
                prepare(
                        zeroKeep,
                        new ModelCallInput(request, List.of(), null, primary),
                        Purpose.SUMMARY);

        assertEquals(1, summaryCalls.get());
        assertEquals("passed", manifests.get(0).validation());
        assertEquals(2, prepared.messages().size());
        assertSame(request.get(request.size() - 1), prepared.messages().get(1));
    }

    @Test
    void summaryPurposeStillOffloadsLargeToolResults() throws IOException {
        ToolResultEvictionMiddleware eviction =
                new ToolResultEvictionMiddleware(
                        new LocalFilesystem(
                                tempDir, LocalFsMode.ROOTED, PathPolicy.empty(), 10, null),
                        ToolResultEvictionConfig.builder()
                                .maxResultChars(20)
                                .previewChars(4)
                                .build());
        HarnessContextBuilder builder =
                new HarnessContextBuilder(
                        new ContextPolicy(
                                4000, 1, 1, ContextTokenEstimator.approximate(), manifests::add),
                        null,
                        eviction);

        prepare(builder, input(readHistory("a")), Purpose.SUMMARY);

        assertEquals("passed", manifests.get(0).validation());
        assertTrue(manifests.get(0).transforms().contains("tool_result_offload"));
    }

    @Test
    void summaryPurposeIrreducibleHistoryStillRejects() {
        List<Msg> history = List.of(image());
        ModelCallInput request = input(history);

        assertThrows(
                ContextBudgetExceededException.class,
                () ->
                        prepare(
                                builder(1000, ContextTokenEstimator.approximate(), 200),
                                request,
                                Purpose.SUMMARY));

        assertEquals("budget_exceeded", manifests.get(0).validation());
    }

    @Test
    void irreducibleMediaTailDoesNotReplaceHistory() {
        List<Msg> history = List.of(image());
        ModelCallInput request = input(history);

        assertThrows(
                ContextBudgetExceededException.class,
                () -> prepare(builder(1000, ContextTokenEstimator.approximate(), 200), request));

        assertEquals(0, summaryCalls.get());
        assertEquals(history, state.contextMutable());
        assertEquals("budget_exceeded", manifests.get(0).validation());
    }

    @Test
    void overBudgetSummaryDoesNotReplaceHistory() {
        List<Msg> history = List.of(message("x".repeat(2000)), message("now"));
        ModelCallInput request = input(history);

        assertThrows(
                ContextBudgetExceededException.class,
                () -> prepare(builder(100, ContextTokenEstimator.approximate(), 0), request));

        assertEquals(1, summaryCalls.get());
        assertEquals(history, state.contextMutable());
        assertEquals("budget_exceeded", manifests.get(0).validation());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void oversizedInstructionsOrToolSetStillRejectWithoutReplacingHistory(boolean largeTools) {
        List<Msg> history = List.of(message("x".repeat(2000)), message("now"));
        input(history);
        List<Msg> messages = new ArrayList<>();
        messages.add(
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .textContent(largeTools ? "rules" : "rules".repeat(1000))
                        .build());
        messages.addAll(history);
        List<ToolSchema> tools = new ArrayList<>();
        if (largeTools) {
            for (int i = 0; i < 20; i++) tools.add(tool("tool" + i, "schema".repeat(100)));
        }
        ModelCallInput request = new ModelCallInput(messages, tools, null, primary);

        assertThrows(
                ContextBudgetExceededException.class,
                () -> prepare(builder(1000, ContextTokenEstimator.approximate(), 0), request));

        assertEquals(history, state.contextMutable());
        assertEquals("budget_exceeded", manifests.get(0).validation());
    }

    @Test
    void outputOptionsReduceTheBudgetBeforeCompaction() {
        when(primary.getContextWindowSize()).thenReturn(2000);
        List<Msg> history = List.of(message("x".repeat(2600)), message("now"));
        ModelCallInput original = input(history);
        GenerateOptions options = GenerateOptions.builder().maxTokens(1000).build();
        ModelCallInput request =
                new ModelCallInput(original.messages(), List.of(), options, primary);

        prepare(builder(0, ContextTokenEstimator.approximate(), 0), request);

        assertEquals(1, summaryCalls.get());
        assertEquals(999, manifests.get(0).inputLimit());
        assertTrue(manifests.get(0).estimatedInputTokens() <= 999);
    }

    @Test
    void learnedWindowReplacesTheUnknownWindowInputFallback() {
        when(primary.getContextWindowSize()).thenReturn(0);
        LearnedContextWindows windows = new LearnedContextWindows();
        windows.recordOverflow(
                primary, new IllegalStateException("maximum context length is 2000 tokens"));
        ContextTokenEstimator estimator = ContextTokenEstimator.approximate();

        HarnessContextBuilder unknown =
                new HarnessContextBuilder(
                        new ContextPolicy(0, 0, 0, estimator, manifests::add), null, null);
        prepare(unknown, input(List.of(message("hello"))));

        state.contextMutable().clear();
        HarnessContextBuilder learned =
                new HarnessContextBuilder(
                        new ContextPolicy(0, 0, 0, estimator, manifests::add),
                        null,
                        null,
                        ContextSources.empty(),
                        ContextSelectionPolicy.defaults(),
                        windows);
        prepare(learned, input(List.of(message("hello"))));

        assertEquals(64_000, manifests.get(0).inputLimit());
        // 2000 - min(4096, 2000 / 8) - 2000 / 50
        assertEquals(1_710, manifests.get(1).inputLimit());
    }

    private HarnessContextBuilder builder(
            int cap, ContextTokenEstimator estimator, int keepTokens) {
        CompactionConfig config =
                CompactionConfig.builder()
                        .triggerMessages(0)
                        .triggerTokens(0)
                        .keepTokens(keepTokens)
                        .keepMessages(1)
                        .flushBeforeCompact(false)
                        .truncateArgs(null)
                        .prune(null)
                        .build();
        return new HarnessContextBuilder(
                new ContextPolicy(cap, 1, 1, estimator, manifests::add),
                new CompactionMiddleware(null, summary, config),
                null);
    }

    private HarnessContextBuilder pruningBuilder(
            ContextTokenEstimator estimator, int protectTokens, int minimumTokens) {
        CompactionConfig config =
                CompactionConfig.builder()
                        .triggerMessages(0)
                        .flushBeforeCompact(false)
                        .prune(
                                CompactionConfig.PruneConfig.builder()
                                        .protectTokens(protectTokens)
                                        .minimumTokens(minimumTokens)
                                        .maxOutputChars(100)
                                        .excludedTools(Set.of("todo_write"))
                                        .build())
                        .build();
        return new HarnessContextBuilder(
                new ContextPolicy(8000, 1, 1, estimator, manifests::add),
                new CompactionMiddleware(null, summary, config),
                null);
    }

    private ModelCallInput input(List<Msg> history) {
        state.contextMutable().addAll(history);
        return new ModelCallInput(history, List.of(), null, primary);
    }

    private ModelCallInput prepare(HarnessContextBuilder builder, ModelCallInput input) {
        return prepare(builder, input, Purpose.REASONING);
    }

    private ModelCallInput prepare(
            HarnessContextBuilder builder, ModelCallInput input, Purpose purpose) {
        return builder.prepare(agent, rc, input, "budget", purpose).block();
    }

    private static boolean isSummary(Msg msg) {
        return ConversationCompactor.SUMMARY_MSG_NAME.equals(msg.getName());
    }

    private static Msg message(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }

    private static Msg image() {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(
                        ImageBlock.builder()
                                .source(new URLSource("https://example.com/pic.png"))
                                .build())
                .build();
    }

    private static Msg toolCall(String id, String name) {
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(ToolUseBlock.builder().id(id).name(name).input(Map.of()).build())
                .build();
    }

    private static List<Msg> readHistory(String... ids) {
        List<Msg> history = new ArrayList<>();
        for (String id : ids) {
            history.add(toolCall(id, "read"));
            history.add(
                    Msg.builder()
                            .role(MsgRole.TOOL)
                            .content(
                                    ToolResultBlock.of(
                                            id,
                                            "read",
                                            TextBlock.builder().text("x".repeat(1000)).build()))
                            .build());
        }
        history.add(message("continue"));
        return history;
    }

    private Msg todoReceipt(String subject) {
        ToolResultBlock todo =
                new TodoTools().write(List.of(new TodoItem(subject, "pending", null)), state);
        return Msg.builder()
                .role(MsgRole.TOOL)
                .content(
                        new ToolResultBlock(
                                "todo",
                                "todo_write",
                                todo.getOutput(),
                                todo.getMetadata(),
                                todo.getState(),
                                todo.getExecutionDetails()))
                .build();
    }

    private static ToolSchema tool(String name, String description) {
        return ToolSchema.builder()
                .name(name)
                .description(description)
                .parameters(Map.of())
                .build();
    }
}
