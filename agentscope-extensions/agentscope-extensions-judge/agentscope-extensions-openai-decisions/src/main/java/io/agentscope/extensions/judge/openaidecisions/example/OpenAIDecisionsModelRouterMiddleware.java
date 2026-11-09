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
package io.agentscope.extensions.judge.openaidecisions.example;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.judge.openaidecisions.DecisionAnswer;
import io.agentscope.extensions.judge.openaidecisions.DecisionQuestion;
import io.agentscope.extensions.judge.openaidecisions.DecisionRequest;
import io.agentscope.extensions.judge.openaidecisions.DecisionResult;
import io.agentscope.extensions.judge.openaidecisions.OpenAIDecisionsClient;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Selects a model once per agent invocation. Disabled until explicitly enabled. */
public final class OpenAIDecisionsModelRouterMiddleware implements MiddlewareBase {
    private final Function<DecisionRequest, Mono<DecisionResult>> call;
    private final String instructions;
    private final double confidenceThreshold;
    private final boolean enabled;
    private final boolean failOpen;
    private final BiPredicate<ModelCallInput, ModelChoice> compatible;
    private final Map<String, ModelChoice> choices;

    private OpenAIDecisionsModelRouterMiddleware(Builder builder) {
        call = builder.call;
        instructions = builder.instructions;
        confidenceThreshold = builder.confidenceThreshold;
        enabled = builder.enabled;
        failOpen = builder.failOpen;
        compatible = builder.compatible;
        choices = Collections.unmodifiableMap(new LinkedHashMap<>(builder.choices));
        if (choices.size() < 2 || choices.size() > 255) {
            throw new IllegalArgumentException("require 2–255 model choices");
        }
        if (choices.keySet().stream().anyMatch(String::isBlank) || instructions.isBlank()) {
            throw new IllegalArgumentException("choice names and instructions must not be blank");
        }
        DecisionSupport.requireProbability(confidenceThreshold, "confidenceThreshold");
    }

    public static Builder builder(OpenAIDecisionsClient client) {
        return builder(Objects.requireNonNull(client, "client")::decide);
    }

    public static Builder builder(Function<DecisionRequest, Mono<DecisionResult>> call) {
        return new Builder(call);
    }

    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_AGENT, ExtensionPoint.ON_MODEL_CALL);
    }

    @Override
    public int order() {
        return 0;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.defer(
                () -> {
                    if (ctx == null) return next.apply(input);
                    ctx.put(RoutingDecision.class, RoutingDecision.fallback());
                    String text = DecisionSupport.latestUserText(input.msgs());
                    if (!enabled || text.isBlank()) return next.apply(input);
                    var options =
                            choices.entrySet().stream()
                                    .map(
                                            e ->
                                                    new DecisionQuestion.Option(
                                                            e.getKey(), e.getValue().criteria()))
                                    .toList();
                    var request =
                            DecisionRequest.builder()
                                    .input(text)
                                    .question(
                                            "models_0",
                                            new DecisionQuestion.Choice(instructions, options))
                                    .build();
                    return DecisionSupport.call(call, request)
                            .map(this::selectedModel)
                            .onErrorResume(
                                    error ->
                                            failOpen
                                                    ? Mono.just(RoutingDecision.fallback())
                                                    : Mono.error(error))
                            .flatMapMany(
                                    decision -> {
                                        ctx.put(RoutingDecision.class, decision);
                                        return next.apply(input);
                                    });
                });
    }

    private RoutingDecision selectedModel(DecisionResult result) {
        if (!(result.answers().get("models_0") instanceof DecisionAnswer.Choice choice)
                || !choices.containsKey(choice.choice())) return RoutingDecision.fallback();
        DecisionSupport.requireProbability(choice.confidence(), "confidence");
        Map<String, Double> probabilities = new LinkedHashMap<>();
        for (var entry : choice.probabilities()) {
            if (entry.value() instanceof String name) probabilities.put(name, entry.probability());
        }
        return new RoutingDecision(
                choice.confidence() >= confidenceThreshold
                        ? choices.get(choice.choice()).model()
                        : null,
                probabilities,
                choice.confidence());
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext ctx,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        return Flux.defer(
                () -> {
                    RoutingDecision decision = decision(ctx);
                    if (!enabled || decision == null || decision.model() == null)
                        return next.apply(input);
                    ModelChoice selected =
                            choices.values().stream()
                                    .filter(c -> c.model() == decision.model())
                                    .findFirst()
                                    .orElse(null);
                    boolean usable;
                    try {
                        usable = selected != null && compatible.test(input, selected);
                    } catch (RuntimeException error) {
                        usable = false;
                    }
                    if (!usable) {
                        ctx.put(RoutingDecision.class, RoutingDecision.fallback());
                        return next.apply(input);
                    }
                    return next.apply(
                            new ModelCallInput(
                                    input.messages(),
                                    input.tools(),
                                    input.options(),
                                    decision.model()));
                });
    }

    public static RoutingDecision decision(RuntimeContext ctx) {
        return ctx == null ? null : ctx.get(RoutingDecision.class);
    }

    public record ModelChoice(Model model, String criteria) {
        public ModelChoice {
            Objects.requireNonNull(model, "model");
            if (criteria == null || criteria.isBlank())
                throw new IllegalArgumentException("criteria must not be blank");
        }
    }

    public record RoutingDecision(
            Model model, Map<String, Double> probabilities, Double confidence) {
        public RoutingDecision {
            probabilities = Collections.unmodifiableMap(new LinkedHashMap<>(probabilities));
        }

        static RoutingDecision fallback() {
            return new RoutingDecision(null, Map.of(), null);
        }
    }

    public static final class Builder {
        private final Function<DecisionRequest, Mono<DecisionResult>> call;
        private final Map<String, ModelChoice> choices = new LinkedHashMap<>();
        private String instructions =
                "Choose the best model for this user request using the supplied criteria.";
        private double confidenceThreshold = 0.8;
        private boolean enabled;
        private boolean failOpen = true;
        private BiPredicate<ModelCallInput, ModelChoice> compatible =
                (input, choice) ->
                        input.tools().isEmpty()
                                && (input.options() == null
                                        || input.options().getResponseFormat() == null
                                        || choice.model().supportsNativeStructuredOutput());

        private Builder(Function<DecisionRequest, Mono<DecisionResult>> call) {
            this.call = Objects.requireNonNull(call, "call");
        }

        public Builder choice(String name, Model model, String criteria) {
            choices.put(Objects.requireNonNull(name, "name"), new ModelChoice(model, criteria));
            return this;
        }

        public Builder instructions(String value) {
            instructions = Objects.requireNonNull(value);
            return this;
        }

        public Builder confidenceThreshold(double value) {
            confidenceThreshold = value;
            return this;
        }

        public Builder enabled(boolean value) {
            enabled = value;
            return this;
        }

        public Builder failOpen(boolean value) {
            failOpen = value;
            return this;
        }

        /** The default only routes requests without tools and with compatible structured output. */
        public Builder compatible(BiPredicate<ModelCallInput, ModelChoice> value) {
            compatible = Objects.requireNonNull(value);
            return this;
        }

        public OpenAIDecisionsModelRouterMiddleware build() {
            return new OpenAIDecisionsModelRouterMiddleware(this);
        }
    }
}
