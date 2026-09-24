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

package io.agentscope.examples.documentation2.jev;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.JevRetryPolicy;
import io.agentscope.extensions.judge.jev.example.JevModelRouterMiddleware;
import io.agentscope.extensions.judge.jev.example.JevModelRouterMiddleware.RoutingDecision;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * Manual runner for {@link JevModelRouterMiddleware} against the real Jev and DashScope APIs.
 *
 * <p>Before running this manual test, add a model-provider dependency such as agentscope-extensions-model-openai.
 */
public final class JevModelRouterMiddlewareTest {

    private static final String DASHSCOPE_COMPATIBLE_BASE_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final Duration MODEL_CALL_TIMEOUT = Duration.ofSeconds(60);

    private JevModelRouterMiddlewareTest() {}

    public static void main(String[] args) {
        String jevApiKey = firstNonBlankEnv("TYPESAFE_API_KEY", "JEV_API_KEY");
        String dashscopeApiKey = firstNonBlankEnv("DASHSCOPE_API_KEY");
        if (jevApiKey == null || dashscopeApiKey == null) {
            System.err.println(
                    "Set TYPESAFE_API_KEY (or JEV_API_KEY) and DASHSCOPE_API_KEY before running"
                            + " this manual test.");
            System.exit(1);
        }

        JevClient jevClient =
                JevClient.builder()
                        .apiKey(jevApiKey)
                        .timeout(Duration.ofSeconds(8))
                        .retryPolicy(new JevRetryPolicy(1, Duration.ofMillis(500)))
                        .build();
        ModelCreationContext modelContext =
                ModelCreationContext.builder()
                        .apiKey(dashscopeApiKey)
                        .baseUrl(DASHSCOPE_COMPATIBLE_BASE_URL)
                        .stream(false)
                        .build();

        Model flash = resolve("openai:qwen3.8-flash", modelContext);
        Model balanced = resolve("openai:qwen-plus", modelContext);
        Model powerful = resolve("openai:qwen3.8-max", modelContext);

        JevModelRouterMiddleware router =
                JevModelRouterMiddleware.builder(jevClient)
                        .choice(
                                "qwen3.8-flash",
                                flash,
                                "Direct answers, extraction, and simple lookup-style requests.")
                        .choice(
                                "qwen-plus",
                                balanced,
                                "Balanced general-purpose tasks and moderate reasoning.")
                        .choice(
                                "qwen3.8-max",
                                powerful,
                                "Architecture trade-offs, complex reasoning, and high-stakes"
                                        + " decisions.")
                        .instructions("Choose the least costly model that can complete the task.")
                        .confidenceThreshold(0.30)
                        .failOpen(false)
                        .build();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("jev-router-dashscope")
                        .sysPrompt("You are a concise technical assistant. Answer in Chinese.")
                        .model(balanced)
                        .generateOptions(
                                GenerateOptions.builder().temperature(0.2).maxTokens(400).build())
                        .middleware(router)
                        .middleware(new RoutingAuditMiddleware())
                        .build();

        runPrompt(agent, "用一句话解释 ReAct agent 中 reasoning 和 acting 的区别。");
        runPrompt(agent, "设计一个复杂的企业级的多 agent 架构。");
    }

    private static Model resolve(String modelId, ModelCreationContext context) {
        try {
            return ModelRegistry.resolve(modelId, context);
        } catch (RuntimeException e) {
            System.err.println("Failed to resolve " + modelId + ": " + e.getMessage());
            System.exit(1);
            return null;
        }
    }

    private static void runPrompt(ReActAgent agent, String prompt) {
        System.out.println();
        System.out.println("=".repeat(80));
        System.out.println("Prompt: " + prompt);
        long startedAt = System.currentTimeMillis();
        try {
            Msg reply = agent.call(prompt, RuntimeContext.empty()).block(MODEL_CALL_TIMEOUT);
            System.out.println("Answer: " + (reply == null ? "" : reply.getTextContent()));
            System.out.println("Elapsed: " + (System.currentTimeMillis() - startedAt) + " ms");
        } catch (RuntimeException e) {
            System.err.println("Failed after " + (System.currentTimeMillis() - startedAt) + " ms");
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static String firstNonBlankEnv(String... names) {
        for (String name : names) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /** Prints the Jev routing decision and the model that actually receives each model call. */
    private static final class RoutingAuditMiddleware implements MiddlewareBase {

        @Override
        public int order() {
            return -1;
        }

        @Override
        public Flux<AgentEvent> onModelCall(
                Agent agent,
                RuntimeContext ctx,
                ModelCallInput input,
                Function<ModelCallInput, Flux<AgentEvent>> next) {
            RoutingDecision decision = JevModelRouterMiddleware.decision(ctx);
            Map<String, Double> probabilities =
                    decision == null ? Map.of() : decision.probabilities();
            String decisionModel =
                    decision == null || decision.model() == null
                            ? "fallback"
                            : decision.model().getModelName();
            System.out.printf(
                    "Routing decision: %s | confidence=%s | probabilities=%s%n",
                    decisionModel, decision == null ? null : decision.confidence(), probabilities);
            System.out.printf(
                    "Actual model: %s%n",
                    input.model() == null ? "<null>" : input.model().getModelName());
            return next.apply(input);
        }
    }
}
