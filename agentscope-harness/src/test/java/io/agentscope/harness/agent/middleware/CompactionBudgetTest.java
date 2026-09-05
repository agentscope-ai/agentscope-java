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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.formatter.JsonSchema;
import io.agentscope.core.formatter.ResponseFormat;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.memory.compaction.TokenCounterUtil;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;

class CompactionBudgetTest {
    @Test
    void summaryFailureAfterPruningDoesNotMutateOriginalHistory() {
        Fixture f = new Fixture();
        Msg tool =
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.builder()
                                        .id("call")
                                        .name("search")
                                        .output(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("x".repeat(5_000))
                                                                .build()))
                                        .build())
                        .build();
        ReasoningInput original =
                new ReasoningInput(List.of(tool, user("latest")), List.of(), null);
        when(f.summaryModel.stream(anyList(), isNull(), isNull()))
                .thenReturn(Flux.error(new IllegalStateException("summary failed")));
        CompactionConfig cfg =
                base(1).prune(
                                CompactionConfig.PruneConfig.builder()
                                        .protectTokens(0)
                                        .minimumTokens(1)
                                        .maxOutputChars(100)
                                        .build())
                        .build();
        assertSame(original, f.run(original, cfg));
        assertEquals(original.messages(), f.state.getContext());
        assertEquals(
                "x".repeat(5_000),
                ((TextBlock) tool.getFirstContentBlock(ToolResultBlock.class).getOutput().get(0))
                        .getText());
        verify(f.summaryModel).stream(anyList(), isNull(), isNull());
    }

    @Test
    void longReasoningHistoryTriggersCompaction() {
        Fixture f = new Fixture();
        Msg reasoning =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ThinkingBlock.builder().thinking("分析".repeat(40_000)).build(),
                                TextBlock.builder().text("answer").build())
                        .build();
        ReasoningInput next =
                f.run(
                        new ReasoningInput(List.of(reasoning, user("latest")), List.of(), null),
                        config(70_000));
        assertEquals(ConversationCompactor.SUMMARY_MSG_NAME, next.messages().get(0).getName());
    }

    @Test
    void chineseHistoryTriggersBeforeSeventyThousandTokens() {
        Fixture f = new Fixture();
        List<Msg> messages = List.of(user("中文".repeat(40_000)), user("latest"));
        ReasoningInput next = f.run(new ReasoningInput(messages, List.of(), null), config(70_000));
        assertEquals(ConversationCompactor.SUMMARY_MSG_NAME, next.messages().get(0).getName());
        assertSame(messages.get(1), next.messages().get(1));
        assertEquals(next.messages(), f.state.getContext());
    }

    @Test
    void asciiHistoryBelowThresholdRemainsUnchanged() {
        Fixture f = new Fixture();
        ReasoningInput original =
                new ReasoningInput(
                        List.of(user("a".repeat(80_000)), user("latest")), List.of(), null);
        assertSame(original, f.run(original, config(70_000)));
        verify(f.summaryModel, never()).stream(anyList(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"system", "tools", "responseSchema"})
    void promptOverheadTriggersCompactionAndIsPreserved(String source) {
        Fixture f = new Fixture();
        Msg system =
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .textContent(source.equals("system") ? "规则".repeat(800) : "system")
                        .build();
        List<ToolSchema> tools =
                List.of(
                        ToolSchema.builder()
                                .name("lookup")
                                .description(
                                        source.equals("tools") ? "说明".repeat(800) : "description")
                                .parameters(Map.of("type", "object"))
                                .build());
        GenerateOptions options =
                GenerateOptions.builder()
                        .responseFormat(
                                ResponseFormat.jsonSchema(
                                        JsonSchema.builder()
                                                .name("answer")
                                                .schema(
                                                        Map.of(
                                                                "type",
                                                                "string",
                                                                "description",
                                                                source.equals("responseSchema")
                                                                        ? "格式".repeat(800)
                                                                        : "format"))
                                                .build()))
                        .build();
        ReasoningInput original =
                new ReasoningInput(
                        List.of(system, user("history"), user("latest")), tools, options);
        ReasoningInput next = f.run(original, config(1_000));
        assertSame(system, next.messages().get(0));
        assertEquals(ConversationCompactor.SUMMARY_MSG_NAME, next.messages().get(1).getName());
        assertSame(tools, next.tools());
        assertSame(options, next.options());
        assertEquals(next.messages().subList(1, next.messages().size()), f.state.getContext());
    }

    @Test
    void thresholdEqualityTriggersAndOverheadDoesNotConsumeTailBudget() {
        Fixture f = new Fixture();
        Msg system = Msg.builder().role(MsgRole.SYSTEM).textContent("规则".repeat(300)).build();
        List<Msg> conversation = List.of(user("history".repeat(100)), user("latest"));
        ReasoningInput original =
                new ReasoningInput(
                        List.of(system, conversation.get(0), conversation.get(1)), List.of(), null);
        int threshold = TokenCounterUtil.calculateToken(original.messages());
        CompactionConfig cfg =
                base(threshold)
                        .keepTokens(TokenCounterUtil.calculateToken(List.of(conversation.get(1))))
                        .build();
        ReasoningInput next = f.run(original, cfg);
        assertEquals(ConversationCompactor.SUMMARY_MSG_NAME, next.messages().get(1).getName());
        assertSame(conversation.get(1), next.messages().get(2));
    }

    @Test
    void dynamicThresholdUsesReasoningWindowRatherThanSummaryWindow() {
        Fixture f = new Fixture();
        when(f.reasoningModel.getContextWindowSize()).thenReturn(2_000);
        when(f.summaryModel.getContextWindowSize()).thenReturn(1_000_000);
        ReasoningInput next =
                f.run(
                        new ReasoningInput(
                                List.of(user("中".repeat(1_900)), user("latest")), List.of(), null),
                        base(0).reserved(200).build());
        assertEquals(ConversationCompactor.SUMMARY_MSG_NAME, next.messages().get(0).getName());
    }

    @Test
    void pruningBelowThresholdReachesDownstreamAndStateWithoutSummary() {
        Fixture f = new Fixture();
        Msg tool =
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .id("tool-msg")
                        .name("search")
                        .metadata(Map.of("marker", "keep"))
                        .usage(new ChatUsage(1, 2, 0.1))
                        .content(
                                ToolResultBlock.builder()
                                        .id("call")
                                        .name("search")
                                        .output(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("x".repeat(5_000))
                                                                .build()))
                                        .build())
                        .build();
        List<Msg> messages = List.of(tool, user("latest"));
        CompactionConfig cfg =
                base(1_000)
                        .prune(
                                CompactionConfig.PruneConfig.builder()
                                        .protectTokens(0)
                                        .minimumTokens(1)
                                        .maxOutputChars(100)
                                        .build())
                        .build();
        ReasoningInput next = f.run(new ReasoningInput(messages, List.of(), null), cfg);
        Msg pruned = next.messages().get(0);
        assertTrue(
                pruned.getFirstContentBlock(ToolResultBlock.class).getOutput().get(0)
                                instanceof TextBlock text
                        && text.getText().length() < 200);
        assertIdentityAndMetadata(tool, pruned);
        assertEquals(next.messages(), f.state.getContext());
        assertSame(messages.get(1), next.messages().get(1));
        verify(f.summaryModel, never()).stream(anyList(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(ints = {1_000, 1})
    void argumentTruncationIsKeptEvenWhenSummaryIsSkipped(int trigger) {
        Fixture f = new Fixture();
        Msg call =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .id("call-msg")
                        .name("agent")
                        .metadata(Map.of("marker", "keep"))
                        .usage(new ChatUsage(1, 2, 0.1))
                        .content(
                                ToolUseBlock.builder()
                                        .id("call")
                                        .name("write_file")
                                        .input(Map.of("content", "x".repeat(5_000)))
                                        .build())
                        .build();
        List<Msg> messages = List.of(call, user("latest"));
        CompactionConfig cfg =
                base(trigger)
                        // Force cutoff=0 for trigger=1 to cover the other no-summary branch.
                        .keepMessages(trigger == 1 ? 20 : 1)
                        .truncateArgs(
                                CompactionConfig.TruncateArgsConfig.builder()
                                        .triggerMessages(1)
                                        .keepMessages(1)
                                        .maxArgLength(100)
                                        .build())
                        .build();
        ReasoningInput next = f.run(new ReasoningInput(messages, List.of(), null), cfg);
        Msg truncated = next.messages().get(0);
        String content =
                (String)
                        truncated
                                .getFirstContentBlock(ToolUseBlock.class)
                                .getInput()
                                .get("content");
        assertTrue(content.length() < 100);
        assertIdentityAndMetadata(call, truncated);
        assertEquals(next.messages(), f.state.getContext());
        verify(f.summaryModel, never()).stream(anyList(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"error", "empty", "blank"})
    void unsuccessfulSummaryPreservesOriginalInputAndState(String failure) {
        Fixture f = new Fixture();
        Flux<ChatResponse> response =
                switch (failure) {
                    case "error" ->
                            Flux.error(new IllegalStateException("context_length_exceeded"));
                    case "empty" -> Flux.empty();
                    default ->
                            Flux.just(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("   ")
                                                                    .build()))
                                            .build());
                };
        when(f.summaryModel.stream(anyList(), isNull(), isNull())).thenReturn(response);
        ReasoningInput original =
                new ReasoningInput(
                        List.of(user("important history"), user("latest")), List.of(), null);
        assertSame(original, f.run(original, config(1)));
        assertEquals(original.messages(), f.state.getContext());
    }

    private static void assertIdentityAndMetadata(Msg original, Msg processed) {
        assertEquals(original.getId(), processed.getId());
        assertEquals(original.getName(), processed.getName());
        assertEquals(original.getRole(), processed.getRole());
        assertEquals(original.getTimestamp(), processed.getTimestamp());
        assertEquals(original.getMetadata(), processed.getMetadata());
        assertEquals(original.getUsage(), processed.getUsage());
    }

    private static CompactionConfig.Builder base(int threshold) {
        return CompactionConfig.builder()
                .triggerMessages(0)
                .triggerTokens(threshold)
                .keepMessages(1)
                .keepTokens(0)
                .flushBeforeCompact(false)
                .offloadBeforeCompact(false)
                .prune(null)
                .summaryPrompt("Summarize: {messages}");
    }

    private static CompactionConfig config(int threshold) {
        return base(threshold).build();
    }

    private static Msg user(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }

    private static class Fixture {
        final Model summaryModel = mock(Model.class);
        final Model reasoningModel = mock(Model.class);
        final ReActAgent agent = mock(ReActAgent.class);
        final AgentState state = AgentState.builder().build();
        final RuntimeContext context = RuntimeContext.builder().agentState(state).build();

        Fixture() {
            when(agent.getModel()).thenReturn(reasoningModel);
            when(agent.getName()).thenReturn("budget-agent");
            when(summaryModel.stream(anyList(), isNull(), isNull()))
                    .thenReturn(
                            Flux.just(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("summary")
                                                                    .build()))
                                            .build()));
        }

        ReasoningInput run(ReasoningInput input, CompactionConfig config) {
            state.contextMutable()
                    .addAll(
                            input.messages().stream()
                                    .filter(msg -> msg.getRole() != MsgRole.SYSTEM)
                                    .toList());
            AtomicReference<ReasoningInput> nextInput = new AtomicReference<>();
            new CompactionMiddleware(null, summaryModel, config)
                    .onReasoning(
                            agent,
                            context,
                            input,
                            next -> {
                                nextInput.set(next);
                                return Flux.empty();
                            })
                    .blockLast();
            return nextInput.get();
        }
    }
}
