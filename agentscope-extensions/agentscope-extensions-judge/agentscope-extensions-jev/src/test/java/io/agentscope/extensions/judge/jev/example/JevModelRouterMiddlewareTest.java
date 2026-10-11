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

package io.agentscope.extensions.judge.jev.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.judge.jev.Answer;
import io.agentscope.extensions.judge.jev.ChoiceAnswer;
import io.agentscope.extensions.judge.jev.SystemOneRequest;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import io.agentscope.extensions.judge.jev.Usage;
import io.agentscope.extensions.judge.jev.example.JevModelRouterMiddleware.RoutingDecision;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link JevModelRouterMiddleware}. Jev is mocked with a stub function; model
 * calls are verified through the {@code ModelCallInput} captured by {@code next}.
 */
class JevModelRouterMiddlewareTest {

    @Test
    void routesSelectedModelAndPreservesModelCallInput() {
        RuntimeContext ctx = RuntimeContext.empty();
        Model fallback = model("fallback");
        Model fast = model("fast");
        Model powerful = model("powerful");
        AtomicReference<SystemOneRequest> request = new AtomicReference<>();
        JevModelRouterMiddleware middleware =
                JevModelRouterMiddleware.builder(
                                capture(
                                        request,
                                        result(
                                                Map.of(
                                                        "models_0",
                                                        choice(
                                                                "fast",
                                                                Map.of(
                                                                        "fast", 0.8,
                                                                        "powerful", 0.2),
                                                                0.9)))))
                        .execution(
                                new io.agentscope.extensions.judge.jev.JevExecution.Options(
                                        io.agentscope.extensions.judge.jev.JevExecution.Mode
                                                .ENFORCE,
                                        java.time.Duration.ofSeconds(2),
                                        "test",
                                        (c, r) -> {}))
                        .compatible((i, c) -> true)
                        .choice("fast", fast, "Direct lookups and localized changes.")
                        .choice("powerful", powerful, "Architecture and high-stakes decisions.")
                        .build();

        middleware
                .onAgent(
                        null,
                        ctx,
                        new AgentInput(List.of(new UserMessage("What is 2 + 2?"))),
                        nextAgent())
                .then()
                .block();

        List<Msg> messages = List.of(new UserMessage("What is 2 + 2?"));
        List<ToolSchema> tools = List.of(tool("search", "Search the web"));
        GenerateOptions options = GenerateOptions.builder().maxTokens(100).build();
        AtomicReference<ModelCallInput> captured = new AtomicReference<>();

        middleware
                .onModelCall(
                        null,
                        ctx,
                        new ModelCallInput(messages, tools, options, fallback),
                        input -> {
                            captured.set(input);
                            return Flux.empty();
                        })
                .then()
                .block();

        assertSame(fast, captured.get().model());
        assertSame(messages, captured.get().messages());
        assertSame(tools, captured.get().tools());
        assertSame(options, captured.get().options());
        assertEquals(0.9, JevModelRouterMiddleware.decision(ctx).confidence());
        assertEquals(0.8, JevModelRouterMiddleware.decision(ctx).probabilities().get("fast"));
        assertEquals("What is 2 + 2?", ((Map<?, ?>) request.get().state()).get("userRequest"));
    }

    @Test
    void makesOneDecisionPerAgentInvocation() {
        RuntimeContext ctx = RuntimeContext.empty();
        Model fallback = model("fallback");
        Model fast = model("fast");
        Model powerful = model("powerful");
        AtomicInteger calls = new AtomicInteger();
        JevModelRouterMiddleware middleware =
                JevModelRouterMiddleware.builder(
                                request -> {
                                    calls.incrementAndGet();
                                    return Mono.just(
                                            result(
                                                    Map.of(
                                                            "models_0",
                                                            choice(
                                                                    "fast",
                                                                    Map.of(
                                                                            "fast", 0.7,
                                                                            "powerful", 0.3),
                                                                    0.9))));
                                })
                        .execution(
                                new io.agentscope.extensions.judge.jev.JevExecution.Options(
                                        io.agentscope.extensions.judge.jev.JevExecution.Mode
                                                .ENFORCE,
                                        java.time.Duration.ofSeconds(2),
                                        "test",
                                        (c, r) -> {}))
                        .compatible((i, c) -> true)
                        .choice("fast", fast, "Simple requests")
                        .choice("powerful", powerful, "Complex requests")
                        .build();

        middleware
                .onAgent(null, ctx, new AgentInput(List.of(new UserMessage("Say hi"))), nextAgent())
                .then()
                .block();

        for (int i = 0; i < 2; i++) {
            AtomicReference<ModelCallInput> captured = new AtomicReference<>();
            middleware
                    .onModelCall(
                            null,
                            ctx,
                            new ModelCallInput(List.of(), List.of(), null, fallback),
                            input -> {
                                captured.set(input);
                                return Flux.empty();
                            })
                    .then()
                    .block();
            assertSame(fast, captured.get().model());
        }

        assertEquals(1, calls.get());
    }

    @Test
    void doesNotCallJevWithoutUserText() {
        RuntimeContext ctx = RuntimeContext.empty();
        Model fallback = model("fallback");
        AtomicInteger calls = new AtomicInteger();
        JevModelRouterMiddleware middleware =
                JevModelRouterMiddleware.builder(
                                request -> {
                                    calls.incrementAndGet();
                                    return Mono.error(new IllegalStateException("should not call"));
                                })
                        .execution(
                                new io.agentscope.extensions.judge.jev.JevExecution.Options(
                                        io.agentscope.extensions.judge.jev.JevExecution.Mode
                                                .ENFORCE,
                                        java.time.Duration.ofSeconds(2),
                                        "test",
                                        (c, r) -> {}))
                        .compatible((i, c) -> true)
                        .choice("fast", model("fast"), "Simple requests")
                        .choice("powerful", model("powerful"), "Complex requests")
                        .build();

        middleware.onAgent(null, ctx, new AgentInput(List.of()), nextAgent()).then().block();
        AtomicReference<ModelCallInput> captured = new AtomicReference<>();
        middleware
                .onModelCall(
                        null,
                        ctx,
                        new ModelCallInput(List.of(), List.of(), null, fallback),
                        input -> {
                            captured.set(input);
                            return Flux.empty();
                        })
                .then()
                .block();

        assertEquals(0, calls.get());
        assertSame(fallback, captured.get().model());
        assertNull(JevModelRouterMiddleware.decision(ctx).confidence());
    }

    @Test
    void keepsFallbackBelowConfidenceThreshold() {
        RuntimeContext ctx = RuntimeContext.empty();
        Model fallback = model("fallback");
        Model fast = model("fast");
        JevModelRouterMiddleware middleware =
                JevModelRouterMiddleware.builder(
                                request ->
                                        Mono.just(
                                                result(
                                                        Map.of(
                                                                "models_0",
                                                                choice(
                                                                        "fast",
                                                                        Map.of(
                                                                                "fast", 0.6,
                                                                                "powerful", 0.4),
                                                                        0.7)))))
                        .execution(
                                new io.agentscope.extensions.judge.jev.JevExecution.Options(
                                        io.agentscope.extensions.judge.jev.JevExecution.Mode
                                                .ENFORCE,
                                        java.time.Duration.ofSeconds(2),
                                        "test",
                                        (c, r) -> {}))
                        .compatible((i, c) -> true)
                        .choice("fast", fast, "Simple requests")
                        .choice("powerful", model("powerful"), "Complex requests")
                        .confidenceThreshold(0.8)
                        .build();

        middleware
                .onAgent(null, ctx, new AgentInput(List.of(new UserMessage("Say hi"))), nextAgent())
                .then()
                .block();
        AtomicReference<ModelCallInput> captured = new AtomicReference<>();
        middleware
                .onModelCall(
                        null,
                        ctx,
                        new ModelCallInput(List.of(), List.of(), null, fallback),
                        input -> {
                            captured.set(input);
                            return Flux.empty();
                        })
                .then()
                .block();

        assertSame(fallback, captured.get().model());
        RoutingDecision decision = JevModelRouterMiddleware.decision(ctx);
        assertNull(decision.model());
        assertNull(decision.confidence());
        assertEquals(Map.of(), decision.probabilities());
    }

    @Test
    void failsOpenByDefault() {
        RuntimeContext ctx = RuntimeContext.empty();
        Model fallback = model("fallback");
        JevModelRouterMiddleware middleware =
                JevModelRouterMiddleware.builder(
                                request -> Mono.error(new IllegalStateException("offline")))
                        .execution(
                                new io.agentscope.extensions.judge.jev.JevExecution.Options(
                                        io.agentscope.extensions.judge.jev.JevExecution.Mode
                                                .ENFORCE,
                                        java.time.Duration.ofSeconds(2),
                                        "test",
                                        (c, r) -> {}))
                        .compatible((i, c) -> true)
                        .choice("fast", model("fast"), "Simple requests")
                        .choice("powerful", model("powerful"), "Complex requests")
                        .build();

        middleware
                .onAgent(null, ctx, new AgentInput(List.of(new UserMessage("Say hi"))), nextAgent())
                .then()
                .block();
        AtomicReference<ModelCallInput> captured = new AtomicReference<>();
        middleware
                .onModelCall(
                        null,
                        ctx,
                        new ModelCallInput(List.of(), List.of(), null, fallback),
                        input -> {
                            captured.set(input);
                            return Flux.empty();
                        })
                .then()
                .block();

        assertSame(fallback, captured.get().model());
    }

    @Test
    void canFailClosed() {
        RuntimeContext ctx = RuntimeContext.empty();
        JevModelRouterMiddleware middleware =
                JevModelRouterMiddleware.builder(
                                request -> Mono.error(new IllegalStateException("offline")))
                        .execution(
                                new io.agentscope.extensions.judge.jev.JevExecution.Options(
                                        io.agentscope.extensions.judge.jev.JevExecution.Mode
                                                .ENFORCE,
                                        java.time.Duration.ofSeconds(2),
                                        "test",
                                        (c, r) -> {}))
                        .compatible((i, c) -> true)
                        .choice("fast", model("fast"), "Simple requests")
                        .choice("powerful", model("powerful"), "Complex requests")
                        .build();

        StepVerifier.create(
                        middleware.onAgent(
                                null,
                                ctx,
                                new AgentInput(List.of(new UserMessage("Say hi"))),
                                nextAgent()))
                .verifyComplete();
    }

    @Test
    void rejectsTooManyModelChoices() {
        JevModelRouterMiddleware.Builder builder =
                JevModelRouterMiddleware.builder(
                        request -> Mono.error(new IllegalStateException()));
        for (int i = 0; i <= JevSelectionSupport.MAX_CHOICE_OPTIONS; i++) {
            builder.choice("model_" + i, model("model_" + i), "Candidate " + i);
        }

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, builder::build);

        assertEquals("model choices must not exceed 255", error.getMessage());
    }

    @Test
    void validatesChoiceNames() {
        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                JevModelRouterMiddleware.builder(
                                                request -> Mono.just(result(Map.of())))
                                        .choice(" ", model("fast"), "Simple requests")
                                        .choice("powerful", model("powerful"), "Complex requests")
                                        .build());

        assertEquals("choice names must not be blank", error.getMessage());
    }

    @Test
    void shadowRecordsRecommendationWithoutReplacingModel() {
        var observations =
                new java.util.ArrayList<io.agentscope.extensions.judge.jev.JevExecution.Record>();
        var ctx = RuntimeContext.empty();
        var middleware =
                JevModelRouterMiddleware.builder(
                                r ->
                                        Mono.just(
                                                result(
                                                        Map.of(
                                                                "models_0",
                                                                choice(
                                                                        "fast",
                                                                        Map.of("fast", 1.0),
                                                                        0.9)))))
                        .choice("fast", model("fast"), "simple")
                        .execution(
                                new io.agentscope.extensions.judge.jev.JevExecution.Options(
                                        io.agentscope.extensions.judge.jev.JevExecution.Mode.SHADOW,
                                        java.time.Duration.ofSeconds(1),
                                        "v1",
                                        (c, r) -> observations.add(r)))
                        .build();
        middleware
                .onAgent(null, ctx, new AgentInput(List.of(new UserMessage("hello"))), nextAgent())
                .blockLast();
        assertNull(JevModelRouterMiddleware.decision(ctx).model());
        assertEquals("fast", observations.get(0).recommendation().get("model"));
    }

    @Test
    void candidateExpirySticksToOriginalAndDoesNotLeakAcrossContexts() {
        var healthy = new java.util.concurrent.atomic.AtomicBoolean(true);
        var fast = model("fast");
        var original = model("original");
        var middleware =
                JevModelRouterMiddleware.builder(
                                r ->
                                        Mono.just(
                                                result(
                                                        Map.of(
                                                                "models_0",
                                                                choice(
                                                                        "fast",
                                                                        Map.of("fast", 1.0),
                                                                        0.9)))))
                        .choice("fast", fast, "simple")
                        .eligible((c, m) -> healthy.get())
                        .execution(
                                new io.agentscope.extensions.judge.jev.JevExecution.Options(
                                        io.agentscope.extensions.judge.jev.JevExecution.Mode
                                                .ENFORCE,
                                        java.time.Duration.ofSeconds(1),
                                        "v1",
                                        (c, r) -> {}))
                        .build();
        var ctx = RuntimeContext.empty();
        var other = RuntimeContext.empty();
        middleware
                .onAgent(null, ctx, new AgentInput(List.of(new UserMessage("hi"))), nextAgent())
                .blockLast();
        assertNull(JevModelRouterMiddleware.decision(other));
        healthy.set(false);
        middleware
                .onModelCall(
                        null,
                        ctx,
                        new ModelCallInput(List.of(), List.of(), null, original),
                        i -> {
                            assertSame(original, i.model());
                            return Flux.empty();
                        })
                .blockLast();
        healthy.set(true);
        middleware
                .onModelCall(
                        null,
                        ctx,
                        new ModelCallInput(List.of(), List.of(), null, original),
                        i -> {
                            assertSame(original, i.model());
                            return Flux.empty();
                        })
                .blockLast();
    }

    @Test
    void toolCapabilityRequiresExplicitCompatibility() {
        var middleware =
                JevModelRouterMiddleware.builder(
                                r ->
                                        Mono.just(
                                                result(
                                                        Map.of(
                                                                "models_0",
                                                                choice(
                                                                        "fast",
                                                                        Map.of("fast", 1.0),
                                                                        0.9)))))
                        .choice("fast", model("fast"), "simple")
                        .execution(
                                new io.agentscope.extensions.judge.jev.JevExecution.Options(
                                        io.agentscope.extensions.judge.jev.JevExecution.Mode
                                                .ENFORCE,
                                        java.time.Duration.ofSeconds(1),
                                        "v1",
                                        (c, r) -> {}))
                        .build();
        var ctx = RuntimeContext.empty();
        var original = model("original");
        middleware
                .onAgent(null, ctx, new AgentInput(List.of(new UserMessage("hi"))), nextAgent())
                .blockLast();
        middleware
                .onModelCall(
                        null,
                        ctx,
                        new ModelCallInput(
                                List.of(), List.of(tool("search", "search")), null, original),
                        i -> {
                            assertSame(original, i.model());
                            return Flux.empty();
                        })
                .blockLast();
    }

    @Test
    void explicitStagesHaveIndependentStickyChoices() {
        AtomicInteger calls = new AtomicInteger();
        var router =
                JevModelRouterMiddleware.builder(
                                r -> {
                                    String name = calls.incrementAndGet() == 1 ? "fast" : "strong";
                                    return Mono.just(
                                            result(
                                                    Map.of(
                                                            "models_0",
                                                            choice(
                                                                    name,
                                                                    Map.of(name, 1.0),
                                                                    0.99))));
                                })
                        .choice("fast", model("fast"), "simple")
                        .choice("strong", model("strong"), "complex")
                        .execution(
                                new io.agentscope.extensions.judge.jev.JevExecution.Options(
                                        io.agentscope.extensions.judge.jev.JevExecution.Mode
                                                .ENFORCE,
                                        java.time.Duration.ofSeconds(1),
                                        "v1",
                                        (c, r) -> {}))
                        .build();
        var stages = new io.agentscope.extensions.judge.jev.application.JevStageRouter(router);
        var parent = RuntimeContext.empty();
        var plan = stages.begin(parent, "plan", "design").block();
        var execute = stages.begin(parent, "execute", "implement").block();
        assertEquals(
                "fast", JevModelRouterMiddleware.decision(plan.context()).model().getModelName());
        assertEquals(
                "strong",
                JevModelRouterMiddleware.decision(execute.context()).model().getModelName());
        assertNull(JevModelRouterMiddleware.decision(parent));
        assertEquals(2, calls.get());
    }

    private static Function<AgentInput, Flux<AgentEvent>> nextAgent() {
        return input -> Flux.empty();
    }

    private static Function<SystemOneRequest, Mono<SystemOneResult>> capture(
            AtomicReference<SystemOneRequest> request, SystemOneResult result) {
        return value -> {
            request.set(value);
            return Mono.just(result);
        };
    }

    private static ToolSchema tool(String name, String description) {
        return ToolSchema.builder().name(name).description(description).build();
    }

    private static Model model(String name) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.empty();
            }

            @Override
            public String getModelName() {
                return name;
            }
        };
    }

    private static ChoiceAnswer choice(
            String selected, Map<String, Double> probabilities, double confidence) {
        return new ChoiceAnswer(selected, probabilities, confidence);
    }

    private static SystemOneResult result(Map<String, Answer> answers) {
        return new SystemOneResult("jev-test", answers, new Usage(1, 1));
    }
}
