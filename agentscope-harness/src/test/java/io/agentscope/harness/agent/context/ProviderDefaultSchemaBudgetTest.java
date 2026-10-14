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
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.model.ollama.OllamaChatModel;
import io.agentscope.extensions.model.ollama.options.OllamaOptions;
import io.agentscope.extensions.model.openaiofficial.OpenAIResponsesChatModel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
