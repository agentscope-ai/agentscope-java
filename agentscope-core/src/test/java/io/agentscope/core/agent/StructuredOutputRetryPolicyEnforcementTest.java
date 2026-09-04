/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.FailedAttempt;
import io.agentscope.core.formatter.StructuredOutputRetryPolicy;
import io.agentscope.core.formatter.StructuredOutputValidationException;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.test.SampleTools;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Enforcement tests for {@link StructuredOutputRetryPolicy} knobs on the native
 * structured-output path: maxAttempts exhaustion (with conversation rollback),
 * tokenBudget early stop, and the onFailedAttempt listener.
 */
class StructuredOutputRetryPolicyEnforcementTest {

    static final class Answer {
        public int answer;
    }

    /**
     * Returns schema-invalid JSON (answer is a string, schema wants a number) for
     * structured calls, plain text otherwise; records every model input so tests
     * can assert both attempt counts and the surviving conversation state.
     */
    static class SwitchingModel implements Model {
        final List<List<Msg>> calls = new CopyOnWriteArrayList<>();
        final ChatUsage usage;

        SwitchingModel(ChatUsage usage) {
            this.usage = usage;
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls.add(List.copyOf(messages));
            boolean structured = options != null && options.getResponseFormat() != null;
            String text = structured ? "{\"answer\": \"wrong\"}" : "ok";
            return Flux.just(
                    ChatResponse.builder()
                            .id("m" + calls.size())
                            .content(List.of(TextBlock.builder().text(text).build()))
                            .usage(usage)
                            .build());
        }

        @Override
        public String getModelName() {
            return "switching-model";
        }

        @Override
        public boolean supportsNativeStructuredOutput() {
            return true;
        }
    }

    private static Msg user(String text) {
        return Msg.builderForRole(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static ReActAgent agent(Model model, StructuredOutputRetryPolicy policy) {
        return ReActAgent.builder()
                .name("agent")
                .sysPrompt("test")
                .model(model)
                .structuredOutputPolicy(policy)
                .build();
    }

    private static String flattenedInput(List<Msg> input) {
        StringBuilder sb = new StringBuilder();
        input.forEach(m -> m.getContent().forEach(b -> sb.append(b.toString()).append('\n')));
        return sb.toString();
    }

    @Test
    @DisplayName("maxAttempts exhaustion fails closed, accumulates attempts, rolls back the call")
    void exhaustionThrowsWithAttemptsAndRollsBackConversation() {
        SwitchingModel model = new SwitchingModel(new ChatUsage(10, 20, 0));
        List<FailedAttempt> observed = new CopyOnWriteArrayList<>();
        ReActAgent agent =
                agent(
                        model,
                        StructuredOutputRetryPolicy.builder()
                                .maxAttempts(2)
                                .onFailedAttempt(observed::add)
                                .build());

        StructuredOutputValidationException ex =
                assertThrows(
                        StructuredOutputValidationException.class,
                        () ->
                                agent.call(user("answer"), Answer.class)
                                        .block(Duration.ofSeconds(10)));

        assertEquals(2, model.calls.size(), "exactly maxAttempts model calls");
        assertEquals(2, ex.getFailedAttempts().size(), "attempts accumulated on the exception");
        assertEquals(2, observed.size(), "listener observed every failed attempt");
        assertEquals(FailedAttempt.Kind.VALIDATION_ERROR, observed.get(0).kind());
        assertTrue(observed.get(0).rawOutput().contains("wrong"));
        assertEquals(10L, observed.get(0).promptTokens());
        assertEquals(20L, observed.get(0).completionTokens());
        assertTrue(
                !observed.get(0).validationErrors().isEmpty(),
                "validation errors recorded for a schema violation");

        // Conversation rollback: a follow-up plain call must see no trace of the failed
        // structured call (neither its user message, attempts, nor correction turns).
        Msg followUp = agent.call(user("follow-up")).block(Duration.ofSeconds(10));
        assertEquals("ok", followUp.getTextContent());
        List<Msg> lastInput = model.calls.get(model.calls.size() - 1);
        assertEquals(2, lastInput.size(), "only system prompt + the follow-up user message");
        String flattened = flattenedInput(lastInput);
        assertTrue(
                !flattened.contains("wrong") && !flattened.contains("JSON Schema"),
                "failed attempts leaked into the surviving conversation: " + flattened);
    }

    @Test
    @DisplayName("tokenBudget stops retrying before maxAttempts is reached")
    void tokenBudgetStopsRetryEarly() {
        // 50 tokens per attempt, budget 100: attempt 1 cumulative 50 < 100 -> retry;
        // attempt 2 cumulative 100 >= 100 -> stop. maxAttempts(5) is never reached.
        SwitchingModel model = new SwitchingModel(new ChatUsage(30, 20, 0));
        ReActAgent agent =
                agent(
                        model,
                        StructuredOutputRetryPolicy.builder()
                                .maxAttempts(5)
                                .tokenBudget(100L)
                                .build());

        StructuredOutputValidationException ex =
                assertThrows(
                        StructuredOutputValidationException.class,
                        () ->
                                agent.call(user("answer"), Answer.class)
                                        .block(Duration.ofSeconds(10)));

        assertEquals(2, model.calls.size(), "budget (not maxAttempts) stopped the retries");
        assertEquals(2, ex.getFailedAttempts().size());
    }

    @Test
    @DisplayName("successful retry keeps residue during correction but cleans it from the session")
    void successfulRetryCleansResidueFromConversation() {
        // Structured call 1: invalid; structured call 2+: conforming; plain calls: "ok".
        SwitchingModel model =
                new SwitchingModel(new ChatUsage(10, 20, 0)) {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        calls.add(List.copyOf(messages));
                        boolean structured = options != null && options.getResponseFormat() != null;
                        boolean firstStructuredAttempt =
                                structured
                                        && messages.stream()
                                                .noneMatch(
                                                        m ->
                                                                "structured_output_correction"
                                                                        .equals(m.getName()));
                        String text =
                                structured
                                        ? (firstStructuredAttempt
                                                ? "{\"answer\": \"wrong\"}"
                                                : "{\"answer\": 7}")
                                        : "ok";
                        ChatUsage attemptUsage =
                                firstStructuredAttempt ? usage : new ChatUsage(30, 40, 0);
                        return Flux.just(
                                ChatResponse.builder()
                                        .id("m" + calls.size())
                                        .content(List.of(TextBlock.builder().text(text).build()))
                                        .usage(attemptUsage)
                                        .build());
                    }
                };
        ReActAgent agent =
                agent(model, StructuredOutputRetryPolicy.builder().maxAttempts(3).build());

        Msg result = agent.call(user("answer"), Answer.class).block(Duration.ofSeconds(10));
        assertEquals("{\"answer\": 7}", result.getTextContent());
        assertEquals(40, result.getChatUsage().getInputTokens());
        assertEquals(60, result.getChatUsage().getOutputTokens());

        // During the retry, the model still sees the failed attempt + correction
        // (it needs them to self-correct)...
        String retryInput = flattenedInput(model.calls.get(1));
        assertTrue(retryInput.contains("wrong"), "retry round must see the failed attempt");
        assertTrue(
                retryInput.contains("JSON Schema"),
                "retry round must see the correction turn: " + retryInput);

        // ...but after success the residue is removed from the conversation:
        // a follow-up plain call sees only the original turns and the final answer.
        Msg followUp = agent.call(user("follow-up")).block(Duration.ofSeconds(10));
        assertEquals("ok", followUp.getTextContent());
        String surviving = flattenedInput(model.calls.get(model.calls.size() - 1));
        assertTrue(!surviving.contains("wrong"), "failed attempt leaked: " + surviving);
        assertTrue(
                !surviving.contains("JSON Schema")
                        && !surviving.contains("structured_output_correction"),
                "correction turn leaked: " + surviving);
        assertTrue(surviving.contains("{\"answer\": 7}"), "final answer must survive");
    }

    @Test
    @DisplayName("retry budget survives tool-call iterations: no reset at iteration boundaries")
    void retryBudgetSurvivesToolCallIterations() {
        // Sequence on the native structured path:
        //   call 1: invalid JSON                      -> failure 1
        //   call 2: tool call (add) interleaved        -> no final message, not validated
        //   call 3: invalid JSON again after the tool  -> failure 2 -> exhausted (maxAttempts=2)
        // A per-iteration attempt counter would treat call 3 as attempt 1 of a fresh
        // budget and allow a fourth call; the call-scoped budget must stop here.
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SampleTools());
        SwitchingModel model =
                new SwitchingModel(new ChatUsage(10, 20, 0)) {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        calls.add(List.copyOf(messages));
                        boolean structured = options != null && options.getResponseFormat() != null;
                        if (!structured) {
                            return Flux.just(textResponse("ok"));
                        }
                        boolean hasToolResults =
                                messages.stream().anyMatch(m -> m.getRole() == MsgRole.TOOL);
                        boolean hasCorrection =
                                messages.stream()
                                        .anyMatch(
                                                m ->
                                                        "structured_output_correction"
                                                                .equals(m.getName()));
                        if (!hasCorrection) {
                            return Flux.just(textResponse("{\"answer\": \"wrong\"}"));
                        }
                        if (!hasToolResults) {
                            return Flux.just(
                                    ChatResponse.builder()
                                            .id("tool_round")
                                            .content(
                                                    List.of(
                                                            ToolUseBlock.builder()
                                                                    .id("call_add")
                                                                    .name("add")
                                                                    .input(
                                                                            Map.of(
                                                                                    "a", 2,
                                                                                    "b", 3))
                                                                    .content("{\"a\":2,\"b\":3}")
                                                                    .build()))
                                            .usage(usage)
                                            .build());
                        }
                        return Flux.just(textResponse("{\"answer\": \"still-wrong\"}"));
                    }

                    private ChatResponse textResponse(String text) {
                        return ChatResponse.builder()
                                .id("m" + calls.size())
                                .content(List.of(TextBlock.builder().text(text).build()))
                                .usage(usage)
                                .build();
                    }
                };
        List<FailedAttempt> observed = new CopyOnWriteArrayList<>();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("agent")
                        .sysPrompt("test")
                        .model(model)
                        .toolkit(toolkit)
                        .structuredOutputPolicy(
                                StructuredOutputRetryPolicy.builder()
                                        .maxAttempts(2)
                                        .onFailedAttempt(observed::add)
                                        .build())
                        .build();

        StructuredOutputValidationException ex =
                assertThrows(
                        StructuredOutputValidationException.class,
                        () ->
                                agent.call(user("answer"), Answer.class)
                                        .block(Duration.ofSeconds(10)));

        assertEquals(3, model.calls.size(), "two validation failures + one tool round only");
        assertEquals(2, observed.size(), "budget counted across the tool-call iteration");
        assertEquals(2, ex.getFailedAttempts().size());
        // The tool really executed between the two failed attempts.
        assertTrue(
                model.calls.get(2).stream().anyMatch(m -> m.getRole() == MsgRole.TOOL),
                "tool result must be part of the third round's input");
    }

    @Test
    @DisplayName("unparseable output is reported as a PARSE_ERROR attempt")
    void parseFailureReportsParseErrorKind() {
        SwitchingModel model =
                new SwitchingModel(new ChatUsage(10, 20, 0)) {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        calls.add(List.copyOf(messages));
                        return Flux.just(
                                ChatResponse.builder()
                                        .id("m" + calls.size())
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("I cannot answer in JSON.")
                                                                .build()))
                                        .usage(usage)
                                        .build());
                    }
                };
        List<FailedAttempt> observed = new CopyOnWriteArrayList<>();
        ReActAgent agent =
                agent(
                        model,
                        StructuredOutputRetryPolicy.builder()
                                .maxAttempts(1)
                                .onFailedAttempt(observed::add)
                                .build());

        assertThrows(
                StructuredOutputValidationException.class,
                () -> agent.call(user("answer"), Answer.class).block(Duration.ofSeconds(10)));

        assertEquals(1, observed.size());
        FailedAttempt attempt = observed.get(0);
        assertEquals(FailedAttempt.Kind.PARSE_ERROR, attempt.kind());
        assertNotNull(attempt.parseErrorMessage());
        assertTrue(attempt.validationErrors().isEmpty());
        assertTrue(attempt.rawOutput().contains("I cannot answer in JSON."));
    }
}
