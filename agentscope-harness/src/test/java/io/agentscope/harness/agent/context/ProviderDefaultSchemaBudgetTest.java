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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.formatter.JsonSchema;
import io.agentscope.core.formatter.ResponseFormat;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ModelRequestPreparer;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.gemini.GeminiChatModel;
import io.agentscope.extensions.model.ollama.OllamaChatModel;
import io.agentscope.extensions.model.ollama.options.OllamaOptions;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openaiofficial.OpenAIResponsesChatModel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Exercises the budget path with the real provider adapters. The generic regression tests use
 * custom models; these guard the adapter-level wiring that surfaces a default response schema to
 * the estimator, so a schema configured on the model cannot escape accounting.
 */
class ProviderDefaultSchemaBudgetTest {

    private static final ContextTokenEstimator BUDGET = ContextTokenEstimator.approximate();

    private static final List<Msg> MESSAGES =
            List.of(Msg.builder().role(MsgRole.USER).textContent("hello world").build());

    @Test
    void ollamaDefaultFormatSchemaIsPricedLikeAnExplicitOne() {
        Map<String, Object> schema = schema("x".repeat(4000));
        OllamaChatModel model =
                new OllamaChatModel(
                        "qwen3.5:9b",
                        "http://localhost:11434",
                        OllamaOptions.builder().format(schema).build());

        long viaDefault = estimate(null, model);
        long explicit =
                estimate(GenerateOptions.builder().responseFormat(format(schema)).build(), null);

        assertEquals(explicit, viaDefault);
    }

    @Test
    void ollamaExplicitRequestSchemaOverridesStoredDefault() {
        OllamaChatModel model =
                new OllamaChatModel(
                        "qwen3.5:9b",
                        "http://localhost:11434",
                        OllamaOptions.builder().format(schema("x".repeat(4000))).build());
        ResponseFormat small = format(schema("tiny"));
        GenerateOptions request = GenerateOptions.builder().responseFormat(small).build();

        long withExplicitOnly = estimate(request, null);
        long withModelDefault = estimate(request, model);

        assertEquals(withExplicitOnly, withModelDefault);
    }

    @Test
    void ollamaTextRequestStillPaysForTheStoredDefaultSchema() {
        OllamaChatModel model =
                new OllamaChatModel(
                        "qwen3.5:9b",
                        "http://localhost:11434",
                        OllamaOptions.builder().format(schema("x".repeat(4000))).build());
        GenerateOptions request =
                GenerateOptions.builder().responseFormat(ResponseFormat.text()).build();

        long defaultOnly = estimate(null, model);
        long withTextRequest = estimate(request, model);

        assertEquals(defaultOnly, withTextRequest);
    }

    @Test
    void ollamaBodyParamFormatOverrideDropsTheStoredDefaultSchema() {
        OllamaChatModel model =
                new OllamaChatModel(
                        "qwen3.5:9b",
                        "http://localhost:11434",
                        OllamaOptions.builder().format(schema("x".repeat(4000))).build());
        GenerateOptions request =
                GenerateOptions.builder().additionalBodyParam("format", "json").build();

        long requestOnly = estimate(request, null);
        long withModelDefault = estimate(request, model);

        assertEquals(requestOnly, withModelDefault);
    }

    @Test
    void ollamaObjectNodeRequestFormatIsPricedLikeItsMapEquivalent() {
        OllamaChatModel model =
                new OllamaChatModel(
                        "qwen3.5:9b",
                        "http://localhost:11434",
                        OllamaOptions.builder().format(schema("x".repeat(4000))).build());
        GenerateOptions viaObjectNode =
                GenerateOptions.builder().additionalBodyParam("format", objectNode("tiny")).build();
        GenerateOptions viaMap =
                GenerateOptions.builder()
                        .additionalBodyParam("format", linkedSchema("tiny"))
                        .build();

        assertEquals(estimate(viaMap, model), estimate(viaObjectNode, model));
    }

    @Test
    void ollamaObjectNodeDefaultFormatIsPricedLikeItsMapEquivalent() {
        OllamaChatModel viaObjectNode =
                new OllamaChatModel(
                        "qwen3.5:9b",
                        "http://localhost:11434",
                        OllamaOptions.builder().format(objectNode("x".repeat(4000))).build());
        OllamaChatModel viaMap =
                new OllamaChatModel(
                        "qwen3.5:9b",
                        "http://localhost:11434",
                        OllamaOptions.builder().format(linkedSchema("x".repeat(4000))).build());

        assertEquals(estimate(null, viaMap), estimate(null, viaObjectNode));
    }

    @Test
    void ollamaObjectNodeFormatBodyParamIsSentAndPricedAsTheSchema() {
        String description = "x".repeat(4000);
        ObjectNode objectSchema = objectNode(description);

        HttpTransport transport = mock(HttpTransport.class);
        when(transport.execute(any(HttpRequest.class)))
                .thenReturn(
                        HttpResponse.builder()
                                .statusCode(200)
                                .body(
                                        "{\"model\":\"qwen3.5:9b\",\"message\":{\"role\":\"assistant\",\"content\":\"{}\"},\"done\":true}")
                                .build());
        OllamaChatModel model =
                OllamaChatModel.builder().modelName("qwen3.5:9b").httpTransport(transport).build();

        GenerateOptions request =
                GenerateOptions.builder().additionalBodyParam("format", objectSchema).build();
        model.chat(MESSAGES, request);

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport).execute(captor.capture());
        JsonNode sentFormat =
                JsonUtils.getJsonCodec()
                        .fromJson(captor.getValue().getBody(), JsonNode.class)
                        .path("format");
        assertEquals("object", sentFormat.path("type").asText());
        assertEquals(description, sentFormat.path("description").asText());

        GenerateOptions viaMap =
                GenerateOptions.builder()
                        .additionalBodyParam("format", linkedSchema(description))
                        .build();
        assertEquals(estimate(viaMap, model), estimate(request, model));
    }

    @Test
    void ollamaDefaultOptionsKeepMappedFieldsAlongsideSchemaProjection() {
        OllamaChatModel model =
                new OllamaChatModel(
                        "qwen3.5:9b",
                        "http://localhost:11434",
                        OllamaOptions.builder().format(schema("tiny")).temperature(0.7).build());

        GenerateOptions defaults = model.getDefaultOptions();

        assertEquals(0.7, defaults.getTemperature().doubleValue());
        assertNotNull(defaults.getResponseFormat());
    }

    @Test
    void openAIResponsesConfiguredSchemaIsPricedLikeAnExplicitOne() {
        Map<String, Object> schema = schema("x".repeat(4000));
        OpenAIResponsesChatModel model =
                OpenAIResponsesChatModel.builder()
                        .apiKey("test-key")
                        .modelName("gpt-5")
                        .generateOptions(
                                GenerateOptions.builder().responseFormat(format(schema)).build())
                        .build();

        long viaDefault = estimate(null, model);
        long explicit =
                estimate(GenerateOptions.builder().responseFormat(format(schema)).build(), null);

        assertEquals(explicit, viaDefault);
    }

    @Test
    void openAIDefaultSchemaIsPricedLikeAnExplicitOne() {
        Map<String, Object> schema = schema("x".repeat(4000));
        OpenAIChatModel model =
                OpenAIChatModel.builder()
                        .apiKey("test-key")
                        .modelName("gpt-4.1-mini")
                        .generateOptions(
                                GenerateOptions.builder().responseFormat(format(schema)).build())
                        .build();

        long viaDefault = estimate(null, model);
        long explicit =
                estimate(GenerateOptions.builder().responseFormat(format(schema)).build(), null);

        assertEquals(explicit, viaDefault);
    }

    @Test
    void openAIRequestSchemaOverridesStoredDefault() {
        OpenAIChatModel model =
                OpenAIChatModel.builder()
                        .apiKey("test-key")
                        .modelName("gpt-4.1-mini")
                        .generateOptions(
                                GenerateOptions.builder()
                                        .responseFormat(format(schema("x".repeat(4000))))
                                        .build())
                        .build();
        GenerateOptions request =
                GenerateOptions.builder().responseFormat(format(schema("tiny"))).build();

        assertEquals(estimate(request, null), estimate(request, model));
    }

    @Test
    void openAIDefaultSchemaReachesTheWireAndIsPricedLikeAnExplicitOne() {
        String description = "x".repeat(4000);
        Map<String, Object> schema = schema(description);

        HttpTransport transport = mock(HttpTransport.class);
        when(transport.execute(any(HttpRequest.class)))
                .thenReturn(
                        HttpResponse.builder()
                                .statusCode(200)
                                .body(
                                        "{\"id\":\"c\",\"object\":\"chat.completion\",\"model\":\"gpt-4.1-mini\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}")
                                .build());
        OpenAIChatModel model =
                OpenAIChatModel.builder().apiKey("test-key").modelName("gpt-4.1-mini").stream(false)
                        .generateOptions(
                                GenerateOptions.builder().responseFormat(format(schema)).build())
                        .httpTransport(transport)
                        .build();

        StepVerifier.create(model.stream(MESSAGES, null, null)).expectNextCount(1).verifyComplete();

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport).execute(captor.capture());
        JsonNode sentBody =
                JsonUtils.getJsonCodec().fromJson(captor.getValue().getBody(), JsonNode.class);
        assertTrue(
                sentBody.toString().contains(description),
                "the model default schema must reach the wire");
        assertEquals(
                estimate(GenerateOptions.builder().responseFormat(format(schema)).build(), null),
                estimate(null, model));
    }

    @Test
    void dashScopeDefaultSchemaIsPricedLikeAnExplicitOne() {
        Map<String, Object> schema = schema("x".repeat(4000));
        DashScopeChatModel model =
                DashScopeChatModel.builder()
                        .apiKey("test-key")
                        .modelName("qwen-plus")
                        .defaultOptions(
                                GenerateOptions.builder().responseFormat(format(schema)).build())
                        .build();

        long viaDefault = estimate(null, model);
        long explicit =
                estimate(GenerateOptions.builder().responseFormat(format(schema)).build(), null);

        assertEquals(explicit, viaDefault);
    }

    @Test
    void dashScopeRequestSchemaOverridesStoredDefault() {
        DashScopeChatModel model =
                DashScopeChatModel.builder()
                        .apiKey("test-key")
                        .modelName("qwen-plus")
                        .defaultOptions(
                                GenerateOptions.builder()
                                        .responseFormat(format(schema("x".repeat(4000))))
                                        .build())
                        .build();
        GenerateOptions request =
                GenerateOptions.builder().responseFormat(format(schema("tiny"))).build();

        assertEquals(estimate(request, null), estimate(request, model));
    }

    @Test
    void anthropicDefaultSchemaIsPricedLikeAnExplicitOne() {
        Map<String, Object> schema = schema("x".repeat(4000));
        AnthropicChatModel model =
                AnthropicChatModel.builder()
                        .apiKey("test-key")
                        .modelName("claude-sonnet-4-5-20250929")
                        .defaultOptions(
                                GenerateOptions.builder().responseFormat(format(schema)).build())
                        .build();

        long viaDefault = estimate(null, model);
        long explicit =
                estimate(GenerateOptions.builder().responseFormat(format(schema)).build(), null);

        assertEquals(explicit, viaDefault);
    }

    @Test
    void anthropicRequestSchemaOverridesStoredDefault() {
        AnthropicChatModel model =
                AnthropicChatModel.builder()
                        .apiKey("test-key")
                        .modelName("claude-sonnet-4-5-20250929")
                        .defaultOptions(
                                GenerateOptions.builder()
                                        .responseFormat(format(schema("x".repeat(4000))))
                                        .build())
                        .build();
        GenerateOptions request =
                GenerateOptions.builder().responseFormat(format(schema("tiny"))).build();

        assertEquals(estimate(request, null), estimate(request, model));
    }

    @Test
    void geminiDefaultSchemaIsPricedLikeAnExplicitOne() {
        Map<String, Object> schema = schema("x".repeat(4000));
        GeminiChatModel model =
                GeminiChatModel.builder()
                        .apiKey("test-key")
                        .modelName("gemini-2.5-flash")
                        .defaultOptions(
                                GenerateOptions.builder().responseFormat(format(schema)).build())
                        .build();

        long viaDefault = estimate(null, model);
        long explicit =
                estimate(GenerateOptions.builder().responseFormat(format(schema)).build(), null);

        assertEquals(explicit, viaDefault);
    }

    @Test
    void geminiRequestSchemaOverridesStoredDefault() {
        GeminiChatModel model =
                GeminiChatModel.builder()
                        .apiKey("test-key")
                        .modelName("gemini-2.5-flash")
                        .defaultOptions(
                                GenerateOptions.builder()
                                        .responseFormat(format(schema("x".repeat(4000))))
                                        .build())
                        .build();
        GenerateOptions request =
                GenerateOptions.builder().responseFormat(format(schema("tiny"))).build();

        assertEquals(estimate(request, null), estimate(request, model));
    }

    @Test
    void realProviderDefaultSchemaIsRejectedWhenBudgetExceeded() {
        var state = AgentState.builder().build();
        List<Msg> history = List.of(Msg.builder().role(MsgRole.USER).textContent("hello").build());
        state.contextMutable().addAll(history);
        OllamaChatModel model =
                new OllamaChatModel(
                        "qwen3.5:9b",
                        "http://localhost:11434",
                        OllamaOptions.builder().format(schema("x".repeat(4000))).build());
        var policy = new ContextPolicy(100, 20, 1, BUDGET, m -> {});
        StepVerifier.create(
                        new HarnessContextBuilder(policy, null, null)
                                .prepare(
                                        mock(Agent.class),
                                        RuntimeContext.builder().agentState(state).build(),
                                        new ModelCallInput(history, List.of(), null, model),
                                        "c",
                                        ModelRequestPreparer.Purpose.REASONING))
                .expectError(ContextBudgetExceededException.class)
                .verify();
    }

    @Test
    void modelDefaultOutputCapIsReservedInTheInputLimit() {
        // Window 16000 with a model default output cap of 12000 and no request-level cap: the
        // effective limit must reserve the default, leaving exactly 16000 - 12000 - 320 = 3680.
        // A single user message costs 7 fixed tokens (5 message overhead plus "user" at
        // ceil(4 / 2.5) = 2) plus ceil(chars / 2.5), so 9182 chars price at exactly 3680 and
        // 9183 chars at 3681.
        Model model = outputCapModel(16_000, 12_000);

        assertEquals(3_680, estimateTokens("a".repeat(9_182)));
        assertEquals(3_681, estimateTokens("a".repeat(9_183)));

        assertBudgetAccepted(model, "a".repeat(9_182));
        assertBudgetRejected(
                model,
                "a".repeat(9_183),
                "Prepared model input exceeds budget: estimated=3681, limit=3680");
    }

    @Test
    void unknownWindowFallsBackToTheDefaultInputLimitAtItsExactBoundary() {
        // Unknown window (0) without default options: the reservation numbers fall back to 4096
        // and 1024, but the input limit is the fixed 64000 fallback. 159982 chars price at
        // exactly 64000 tokens and 159983 chars at 64001.
        Model model = unknownWindowModel();

        assertEquals(64_000, estimateTokens("a".repeat(159_982)));
        assertEquals(64_001, estimateTokens("a".repeat(159_983)));

        assertBudgetAccepted(model, "a".repeat(159_982));
        assertBudgetRejected(
                model,
                "a".repeat(159_983),
                "Prepared model input exceeds budget: estimated=64001, limit=64000");
    }

    private static void assertBudgetAccepted(Model model, String text) {
        StepVerifier.create(prepare(model, text)).expectNextCount(1).verifyComplete();
    }

    private static void assertBudgetRejected(Model model, String text, String expectedMessage) {
        StepVerifier.create(prepare(model, text))
                .expectErrorSatisfies(
                        error -> {
                            assertEquals(ContextBudgetExceededException.class, error.getClass());
                            assertEquals(expectedMessage, error.getMessage());
                        })
                .verify();
    }

    private static Mono<ModelCallInput> prepare(Model model, String text) {
        List<Msg> messages = List.of(Msg.builder().role(MsgRole.USER).textContent(text).build());
        AgentState state = AgentState.builder().build();
        state.contextMutable().addAll(messages);
        return new HarnessContextBuilder(new ContextPolicy(0, 0, 0, BUDGET, m -> {}), null, null)
                .prepare(
                        mock(Agent.class),
                        RuntimeContext.builder().agentState(state).build(),
                        new ModelCallInput(messages, List.of(), null, model),
                        "c",
                        ModelRequestPreparer.Purpose.REASONING);
    }

    private static long estimateTokens(String text) {
        return BUDGET.estimate(
                        new ModelCallInput(
                                List.of(Msg.builder().role(MsgRole.USER).textContent(text).build()),
                                List.of(),
                                null,
                                null))
                .tokens();
    }

    private static Model unknownWindowModel() {
        return new Model() {
            @Override
            public String getModelName() {
                return "unknown-window-model";
            }

            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                throw new UnsupportedOperationException("estimation only");
            }

            @Override
            public int getContextWindowSize() {
                return 0;
            }
        };
    }

    private static Model outputCapModel(int window, int maxTokens) {
        return new Model() {
            @Override
            public String getModelName() {
                return "output-cap-model";
            }

            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                throw new UnsupportedOperationException("estimation only");
            }

            @Override
            public int getContextWindowSize() {
                return window;
            }

            @Override
            public GenerateOptions getDefaultOptions() {
                return GenerateOptions.builder().maxTokens(maxTokens).build();
            }
        };
    }

    private static long estimate(GenerateOptions options, Model model) {
        return BUDGET.estimate(new ModelCallInput(MESSAGES, List.of(), options, model)).tokens();
    }

    private static Map<String, Object> schema(String description) {
        return Map.of("type", "object", "description", description);
    }

    private static ObjectNode objectNode(String description) {
        ObjectNode schema = new ObjectMapper().createObjectNode();
        schema.put("type", "object");
        schema.put("description", description);
        return schema;
    }

    private static Map<String, Object> linkedSchema(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("description", description);
        return schema;
    }

    private static ResponseFormat format(Map<String, Object> schema) {
        return ResponseFormat.jsonSchema(JsonSchema.builder().schema(schema).build());
    }
}
