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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.middleware.CompactionMiddleware;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class StructuredOutputContextTest {
    public static class Output {
        public String value;
    }

    @Test
    void reminderOverBudgetIsRejectedBeforeCallingProvider() {
        AtomicInteger calls = new AtomicInteger();
        List<ContextManifest> manifests = new ArrayList<>();
        ContextTokenEstimator estimator =
                input ->
                        new ContextTokenEstimator.Estimate(
                                input.messages().stream()
                                        .mapToLong(msg -> msg.getTextContent().length())
                                        .sum(),
                                "test-character-cost",
                                false);
        ReActAgent agent =
                ReActAgent.builder()
                        .name("budget")
                        .model(structuredModel(calls))
                        .modelRequestPreparer(
                                new HarnessContextBuilder(
                                        new ContextPolicy(100, 1, 1, estimator, manifests::add),
                                        null,
                                        null))
                        .build();

        assertThrows(
                ContextBudgetExceededException.class,
                () -> agent.call(message("hello"), Output.class).block());

        assertEquals(0, calls.get());
        assertEquals("budget_exceeded", manifests.get(0).validation());
        assertTrue(manifests.get(0).estimatedInputTokens() > 100);
        assertFalse(
                agent.getAgentState().getContext().stream()
                        .anyMatch(msg -> msg.getTextContent().contains("You MUST call")));
    }

    @Test
    void transientReminderDoesNotPreventHistoryCompactionFromCommitting() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger summaries = new AtomicInteger();
        Model summary =
                new Model() {
                    public String getModelName() {
                        return "summary";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        summaries.incrementAndGet();
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
        CompactionConfig config =
                CompactionConfig.builder()
                        .triggerMessages(2)
                        .keepMessages(1)
                        .keepTokens(0)
                        .flushBeforeCompact(false)
                        .build();
        HarnessContextBuilder preparer =
                new HarnessContextBuilder(
                        ContextPolicy.defaults(),
                        new CompactionMiddleware(null, summary, config),
                        null);
        ReActAgent agent =
                ReActAgent.builder()
                        .name("compact")
                        .model(structuredModel(calls))
                        .modelRequestPreparer(preparer)
                        .build();
        RuntimeContext rc = RuntimeContext.empty();
        AgentState state = agent.getAgentState(rc);
        state.contextMutable()
                .addAll(List.of(message("old request ".repeat(200)), message("continue")));

        Msg result = agent.call(List.of(message("hello")), Output.class, rc).block();

        assertEquals("ok", result.getStructuredData(Output.class).value);
        assertEquals(1, calls.get());
        assertEquals(1, summaries.get());
        assertTrue(
                state.getContext().stream()
                        .anyMatch(
                                msg ->
                                        ConversationCompactor.SUMMARY_MSG_NAME.equals(
                                                msg.getName())));
        assertFalse(
                state.getContext().stream()
                        .anyMatch(msg -> msg.getTextContent().contains("old request")));
        assertFalse(
                state.getContext().stream()
                        .anyMatch(msg -> msg.getTextContent().contains("You MUST call")));
    }

    private static Msg message(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }

    private static Model structuredModel(AtomicInteger calls) {
        return new Model() {
            public String getModelName() {
                return "structured";
            }

            public int getContextWindowSize() {
                return 10_000;
            }

            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                calls.incrementAndGet();
                return Flux.just(
                        ChatResponse.builder()
                                .content(
                                        List.of(
                                                ToolUseBlock.builder()
                                                        .id("output")
                                                        .name("generate_response")
                                                        .content(
                                                                "{\"response\":{\"value\":\"ok\"}}")
                                                        .input(
                                                                Map.of(
                                                                        "response",
                                                                        Map.of("value", "ok")))
                                                        .build()))
                                .build());
            }
        };
    }
}
