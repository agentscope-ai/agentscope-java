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
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.time.Duration;
import java.util.List;
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
                .generateOptions(GenerateOptions.builder().structuredOutputPolicy(policy).build())
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
