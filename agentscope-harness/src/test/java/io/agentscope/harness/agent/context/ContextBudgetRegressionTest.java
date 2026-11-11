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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.formatter.JsonSchema;
import io.agentscope.core.formatter.ResponseFormat;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ModelRequestPreparer;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.memory.compaction.TokenCounterUtil;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * Guards the invariant that the compaction trigger and the final request budget derive their token
 * cost from the same accounting. If these diverge, a conversation the trigger accepts can be
 * rejected by the final budget check (or vice versa).
 *
 * <p>Also pins the CJK-aware text ratio and response-format pricing, which are the two surfaces
 * where the estimator must stay conservative without changing any other cost component.
 */
class ContextBudgetRegressionTest {

    private static final ContextTokenEstimator BUDGET = ContextTokenEstimator.approximate();

    @Test
    void triggerAndBudgetAgreeOnPlainConversation() {
        List<Msg> messages =
                List.of(
                        Msg.builder().role(MsgRole.USER).textContent("hello world").build(),
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .textContent("a".repeat(1000))
                                .build());

        long trigger = TokenCounterUtil.calculateToken(messages);
        long budget = BUDGET.estimate(new ModelCallInput(messages, List.of(), null, null)).tokens();

        assertEquals(trigger, budget);
    }

    @Test
    void triggerAndBudgetAgreeWithToolCallAndResult() {
        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        ToolUseBlock.builder()
                                                .id("call-1")
                                                .name("search")
                                                .input(Map.of("q", "x"))
                                                .build())
                                .build(),
                        Msg.builder()
                                .role(MsgRole.TOOL)
                                .content(
                                        ToolResultBlock.builder()
                                                .id("call-1")
                                                .name("search")
                                                .output(TextBlock.builder().text("result").build())
                                                .build())
                                .build());

        long trigger = TokenCounterUtil.calculateToken(messages);
        long budget = BUDGET.estimate(new ModelCallInput(messages, List.of(), null, null)).tokens();

        assertEquals(trigger, budget);
    }

    @Test
    void triggerAndBudgetAgreeWithTopLevelMediaTypes() {
        List<Msg> messages =
                List.of(
                        Msg.builder().role(MsgRole.USER).textContent("see media").build(),
                        mediaMessage(ImageBlock.builder().source(urlSource()).build()),
                        mediaMessage(
                                AudioBlock.builder().source(base64Source("audio/mp3")).build()),
                        mediaMessage(
                                DataBlock.builder()
                                        .source(base64Source("application/pdf"))
                                        .build()));

        long trigger = TokenCounterUtil.calculateToken(messages);
        long budget = BUDGET.estimate(new ModelCallInput(messages, List.of(), null, null)).tokens();

        assertEquals(trigger, budget);
    }

    @Test
    void cjkTextCostsOneTokenPerCharacter() {
        long tokens = textTokens("中文中文");

        assertEquals(4, tokens);
    }

    @Test
    void supplementaryCodePointsCostTwoTokensEach() {
        long tokens = textTokens("😀😀");

        assertEquals(4, tokens);
    }

    @Test
    void mixedTextIsNeverBelowTheUniformBaseline() {
        String value = "abc中文😀";

        assertTrue(textTokens(value) >= Math.ceil(value.length() / 2.5));
    }

    @Test
    void toolSchemasRaiseOnlyTheBudgetPath() {
        List<Msg> messages = List.of(Msg.builder().role(MsgRole.USER).textContent("hello").build());
        ToolSchema tool =
                ToolSchema.builder()
                        .name("search")
                        .description("search")
                        .parameters(Map.of("type", "object"))
                        .build();

        long trigger = TokenCounterUtil.calculateToken(messages);
        long withTools =
                BUDGET.estimate(new ModelCallInput(messages, List.of(tool), null, null)).tokens();

        assertTrue(withTools > trigger);
    }

    @Test
    void responseFormatRaisesOnlyTheBudgetPath() {
        List<Msg> messages = List.of(Msg.builder().role(MsgRole.USER).textContent("hello").build());
        ResponseFormat format = responseFormat(400);

        long trigger = TokenCounterUtil.calculateToken(messages);
        long without =
                BUDGET.estimate(new ModelCallInput(messages, List.of(), null, null)).tokens();
        long with =
                BUDGET.estimate(
                                new ModelCallInput(
                                        messages,
                                        List.of(),
                                        GenerateOptions.builder().responseFormat(format).build(),
                                        null))
                        .tokens();

        assertEquals(trigger, without, "the trigger path never carries a response format");
        assertTrue(with > without, "the final budget path must price the schema");
    }

    @Test
    void modelDefaultResponseFormatIsPricedLikeAnExplicitOne() {
        List<Msg> messages = List.of(Msg.builder().role(MsgRole.USER).textContent("hello").build());
        ResponseFormat format = responseFormat(400);

        long explicit =
                BUDGET.estimate(
                                new ModelCallInput(
                                        messages,
                                        List.of(),
                                        GenerateOptions.builder().responseFormat(format).build(),
                                        null))
                        .tokens();
        long viaDefault =
                BUDGET.estimate(
                                new ModelCallInput(
                                        messages, List.of(), null, modelWithDefaultFormat(format)))
                        .tokens();

        assertEquals(explicit, viaDefault, "model-level default schema must reach the budget path");
    }

    @Test
    void explicitResponseFormatOverridesModelDefaultForBudget() {
        List<Msg> messages = List.of(Msg.builder().role(MsgRole.USER).textContent("hello").build());
        ResponseFormat small = responseFormat(10);

        long smallOnly =
                BUDGET.estimate(
                                new ModelCallInput(
                                        messages,
                                        List.of(),
                                        GenerateOptions.builder().responseFormat(small).build(),
                                        null))
                        .tokens();
        long overridden =
                BUDGET.estimate(
                                new ModelCallInput(
                                        messages,
                                        List.of(),
                                        GenerateOptions.builder().responseFormat(small).build(),
                                        modelWithDefaultFormat(responseFormat(4000))))
                        .tokens();

        assertEquals(
                smallOnly, overridden, "an explicit request schema wins over the model default");
    }

    @Test
    void modelDefaultMaxTokensShrinksTheInputLimit() {
        List<Msg> messages = List.of(Msg.builder().role(MsgRole.USER).textContent("hello").build());
        AgentState state = AgentState.builder().build();
        state.contextMutable().addAll(messages);
        long withoutDefault = inputLimit(state, messages, new FixedModel(null, 100_000));
        long withDefault =
                inputLimit(
                        state,
                        messages,
                        new FixedModel(
                                GenerateOptions.builder().maxTokens(10_000).build(), 100_000));

        assertTrue(
                withDefault < withoutDefault,
                "a default maxTokens never present in request options must still reserve output");
    }

    @Test
    void unknownWindowFallsBackToTheSixtyFourKInputLimit() {
        var policy = new ContextPolicy(0, 0, 0, BUDGET, m -> {});
        Model windowless = modelWithDefaultMaxTokens(null);

        var withinLimit = AgentState.builder().build();
        List<Msg> fits =
                List.of(Msg.builder().role(MsgRole.USER).textContent("a".repeat(120_000)).build());
        withinLimit.contextMutable().addAll(fits);
        StepVerifier.create(
                        new HarnessContextBuilder(policy, null, null)
                                .prepare(
                                        mock(Agent.class),
                                        RuntimeContext.builder().agentState(withinLimit).build(),
                                        new ModelCallInput(fits, List.of(), null, windowless),
                                        "c",
                                        ModelRequestPreparer.Purpose.REASONING))
                .expectNextCount(1)
                .verifyComplete();

        var overLimit = AgentState.builder().build();
        List<Msg> exceeds =
                List.of(Msg.builder().role(MsgRole.USER).textContent("a".repeat(200_000)).build());
        overLimit.contextMutable().addAll(exceeds);
        StepVerifier.create(
                        new HarnessContextBuilder(policy, null, null)
                                .prepare(
                                        mock(Agent.class),
                                        RuntimeContext.builder().agentState(overLimit).build(),
                                        new ModelCallInput(exceeds, List.of(), null, windowless),
                                        "c",
                                        ModelRequestPreparer.Purpose.REASONING))
                .expectError(ContextBudgetExceededException.class)
                .verify();
    }

    private static long inputLimit(AgentState state, List<Msg> messages, Model model) {
        AtomicReference<ContextManifest> manifest = new AtomicReference<>();
        new HarnessContextBuilder(new ContextPolicy(0, 0, 0, BUDGET, manifest::set), null, null)
                .prepare(
                        mock(Agent.class),
                        RuntimeContext.builder().agentState(state).build(),
                        new ModelCallInput(messages, List.of(), null, model),
                        "c",
                        ModelRequestPreparer.Purpose.REASONING)
                .block();
        return manifest.get().inputLimit();
    }

    private static long textTokens(String text) {
        List<Msg> messages = List.of(Msg.builder().role(MsgRole.USER).textContent(text).build());
        return BUDGET.estimate(new ModelCallInput(messages, List.of(), null, null)).tokens() - 12;
    }

    private static Msg mediaMessage(ContentBlock block) {
        return Msg.builder().role(MsgRole.USER).content(block).build();
    }

    private static URLSource urlSource() {
        return new URLSource("https://example.com/a.png", "image/png");
    }

    private static Base64Source base64Source(String mediaType) {
        return new Base64Source(mediaType, "YQ==");
    }

    private static ResponseFormat responseFormat(int descriptionLength) {
        return ResponseFormat.jsonSchema(
                JsonSchema.builder()
                        .name("Answer")
                        .schema(
                                Map.of(
                                        "type",
                                        "object",
                                        "description",
                                        "x".repeat(descriptionLength)))
                        .build());
    }

    private static Model modelWithDefaultFormat(ResponseFormat format) {
        return new FixedModel(GenerateOptions.builder().responseFormat(format).build());
    }

    private static Model modelWithDefaultMaxTokens(Integer maxTokens) {
        return new FixedModel(
                maxTokens == null ? null : GenerateOptions.builder().maxTokens(maxTokens).build());
    }

    /** Estimation-only model exposing provider-style default options. */
    private static final class FixedModel implements Model {
        private final GenerateOptions defaults;
        private final int window;

        private FixedModel(GenerateOptions defaults) {
            this(defaults, 0);
        }

        private FixedModel(GenerateOptions defaults, int window) {
            this.defaults = defaults;
            this.window = window;
        }

        @Override
        public int getContextWindowSize() {
            return window;
        }

        @Override
        public String getModelName() {
            return "fixed-model";
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            throw new UnsupportedOperationException("estimation only");
        }

        @Override
        public GenerateOptions getDefaultOptions() {
            return defaults;
        }
    }
}
