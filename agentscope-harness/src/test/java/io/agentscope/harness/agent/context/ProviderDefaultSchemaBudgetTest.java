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
import static org.mockito.Mockito.mock;

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
import io.agentscope.core.state.AgentState;
import io.agentscope.extensions.model.ollama.OllamaChatModel;
import io.agentscope.extensions.model.ollama.options.OllamaOptions;
import io.agentscope.extensions.model.openaiofficial.OpenAIResponsesChatModel;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
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

    private static ResponseFormat format(Map<String, Object> schema) {
        return ResponseFormat.jsonSchema(JsonSchema.builder().schema(schema).build());
    }
}
