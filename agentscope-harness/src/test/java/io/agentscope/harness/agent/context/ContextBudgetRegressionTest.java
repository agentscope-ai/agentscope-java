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
import io.agentscope.core.message.VideoBlock;
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
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * Guards the invariant that the compaction trigger and the final request budget derive their token
 * cost from the same accounting. If these diverge, a conversation that the trigger accepts can be
 * rejected by the final budget check (or vice versa).
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
        ResponseFormat format =
                ResponseFormat.jsonSchema(
                        JsonSchema.builder()
                                .name("Answer")
                                .schema(Map.of("type", "object", "description", "x".repeat(400)))
                                .build());

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
    void triggerAndBudgetAgreeWithTopLevelMediaTypes() {
        List<Msg> messages =
                List.of(
                        Msg.builder().role(MsgRole.USER).textContent("see media").build(),
                        mediaMessage(
                                ImageBlock.builder()
                                        .source(
                                                new URLSource(
                                                        "https://example.com/a.png", "image/png"))
                                        .build()),
                        mediaMessage(
                                AudioBlock.builder()
                                        .source(new Base64Source("audio/mp3", "YXVkaW8="))
                                        .build()),
                        mediaMessage(
                                VideoBlock.builder()
                                        .source(new Base64Source("video/mp4", "dmlkZW8="))
                                        .build()),
                        mediaMessage(
                                DataBlock.builder()
                                        .id("doc-1")
                                        .name("report.pdf")
                                        .source(new Base64Source("application/pdf", "cGRm"))
                                        .build()));

        long trigger = TokenCounterUtil.calculateToken(messages);
        long budget = BUDGET.estimate(new ModelCallInput(messages, List.of(), null, null)).tokens();

        assertEquals(trigger, budget);
        var estimate =
                TokenAccountingService.defaults()
                        .estimate(new TokenAccountingService.TokenSurface(messages, List.of()));
        assertEquals(4 * MediaTokenEstimator.DEFAULT_MEDIA_TOKENS, estimate.mediaTokens());
    }

    @Test
    void triggerAndBudgetAgreeWithNestedToolMedia() {
        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        ToolUseBlock.builder()
                                                .id("call-1")
                                                .name("read_media")
                                                .input(Map.of())
                                                .build())
                                .build(),
                        Msg.builder()
                                .role(MsgRole.TOOL)
                                .content(
                                        ToolResultBlock.builder()
                                                .id("call-1")
                                                .name("read_media")
                                                .output(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("ok")
                                                                        .build(),
                                                                AudioBlock.builder()
                                                                        .source(
                                                                                new Base64Source(
                                                                                        "audio/mp3",
                                                                                        "YXVkaW8="))
                                                                        .build(),
                                                                VideoBlock.builder()
                                                                        .source(
                                                                                new Base64Source(
                                                                                        "video/mp4",
                                                                                        "dmlkZW8="))
                                                                        .build(),
                                                                DataBlock.builder()
                                                                        .id("doc-2")
                                                                        .name("r.bin")
                                                                        .source(
                                                                                new Base64Source(
                                                                                        "application/octet-stream",
                                                                                        "Ymlu"))
                                                                        .build()))
                                                .build())
                                .build());

        long trigger = TokenCounterUtil.calculateToken(messages);
        long budget = BUDGET.estimate(new ModelCallInput(messages, List.of(), null, null)).tokens();

        assertEquals(trigger, budget);
        var estimate =
                TokenAccountingService.defaults()
                        .estimate(new TokenAccountingService.TokenSurface(messages, List.of()));
        assertEquals(3 * MediaTokenEstimator.DEFAULT_MEDIA_TOKENS, estimate.mediaTokens());
    }

    @Test
    void unknownWindowFallsBackToTheSixtyFourKInputLimit() {
        var policy = new ContextPolicy(0, 0, 0, BUDGET, m -> {});
        Model windowless =
                new Model() {
                    @Override
                    public String getModelName() {
                        return "unknown-window-model";
                    }

                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        throw new UnsupportedOperationException("estimation only");
                    }
                };

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

    private static Msg mediaMessage(ContentBlock block) {
        return Msg.builder().role(MsgRole.USER).content(block).build();
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
        return new Model() {
            @Override
            public String getModelName() {
                return "default-format-model";
            }

            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                throw new UnsupportedOperationException("estimation only");
            }

            @Override
            public GenerateOptions getDefaultOptions() {
                return GenerateOptions.builder().responseFormat(format).build();
            }
        };
    }
}
