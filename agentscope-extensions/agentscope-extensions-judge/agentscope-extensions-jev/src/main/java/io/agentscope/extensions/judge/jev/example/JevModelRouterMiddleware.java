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

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.judge.jev.Answer;
import io.agentscope.extensions.judge.jev.ChoiceAnswer;
import io.agentscope.extensions.judge.jev.ChoiceQuestion;
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.Question;
import io.agentscope.extensions.judge.jev.SystemOneRequest;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Selects one of the configured models with Jev before the agent's model call.
 *
 * <p>Routing happens in two hooks: {@link #onAgent} asks Jev once per agent invocation (using the
 * latest user message) and stores the result in {@link RuntimeContext}; {@link #onModelCall}
 * replaces {@code ModelCallInput.model} on every subsequent model call in that invocation. This
 * means an intermediate tool result cannot cause the agent to switch models mid-run. When the
 * decision is unusable (no user text, confidence below threshold, Jev failure while
 * {@code failOpen} is set, or an unrecognized answer), the model configured on the agent is used
 * as the fallback.
 */
public final class JevModelRouterMiddleware implements MiddlewareBase {

    private static final String DEFAULT_INSTRUCTIONS =
            "Choose the least costly model that can complete the task.";
    private final Function<SystemOneRequest, Mono<SystemOneResult>> jevCall;
    private final Map<String, ModelChoice> choices;
    private final String instructions;
    private final double confidenceThreshold;
    private final boolean failOpen;

    private JevModelRouterMiddleware(Builder builder) {
        this.jevCall = builder.jevCall;
        this.choices = Collections.unmodifiableMap(new LinkedHashMap<>(builder.choices));
        this.instructions = builder.instructions;
        this.confidenceThreshold = builder.confidenceThreshold;
        this.failOpen = builder.failOpen;
        validate();
    }

    public static Builder builder(JevClient client) {
        Objects.requireNonNull(client, "client");
        return new Builder(client::systemOne);
    }

    static Builder builder(Function<SystemOneRequest, Mono<SystemOneResult>> jevCall) {
        return new Builder(jevCall);
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
        String userText = JevSelectionSupport.latestUserText(input.msgs());
        if (userText.isBlank()) {
            ctx.put(RoutingDecision.class, RoutingDecision.fallback());
            return next.apply(input);
        }

        return selectModel(userText)
                .onErrorResume(
                        error -> {
                            if (!failOpen) {
                                return Mono.error(error);
                            }
                            return Mono.just(RoutingDecision.fallback());
                        })
                .flatMapMany(
                        decision -> {
                            ctx.put(RoutingDecision.class, decision);
                            return next.apply(input);
                        });
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext ctx,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        RoutingDecision decision = decision(ctx);
        if (decision == null || decision.model() == null) {
            return next.apply(input);
        }
        return next.apply(
                new ModelCallInput(
                        input.messages(), input.tools(), input.options(), decision.model()));
    }

    /**
     * Returns the most recent routing decision for the runtime context.
     *
     * @param ctx the runtime context, may be {@code null}
     * @return the decision, or {@code null} when the router has not run
     */
    public static RoutingDecision decision(RuntimeContext ctx) {
        return ctx == null ? null : ctx.get(RoutingDecision.class);
    }

    private Mono<RoutingDecision> selectModel(String userText) {
        SystemOneRequest request = routingRequest(userText);

        return jevCall.apply(request).flatMap(this::selectedModel);
    }

    private SystemOneRequest routingRequest(String userText) {
        Map<String, Question> questions = new LinkedHashMap<>();
        questions.put("models_0", new ChoiceQuestion(instructions, criteria(choices)));
        return SystemOneRequest.builder()
                .state(Map.of("userRequest", userText))
                .questions(questions)
                .build();
    }

    private Mono<RoutingDecision> selectedModel(SystemOneResult result) {
        Answer answer = result.answers().get("models_0");
        if (!(answer instanceof ChoiceAnswer choice)) {
            return Mono.just(RoutingDecision.fallback());
        }
        ModelChoice modelChoice = choices.get(choice.choice());
        if (modelChoice == null || choice.confidence() == null) {
            return Mono.just(RoutingDecision.fallback());
        }
        if (choice.confidence() < confidenceThreshold) {
            return Mono.just(
                    new RoutingDecision(null, choice.probabilities(), choice.confidence()));
        }
        return Mono.just(
                new RoutingDecision(
                        modelChoice.model(), choice.probabilities(), choice.confidence()));
    }

    private Map<String, Object> criteria(Map<String, ModelChoice> candidates) {
        Map<String, Object> criteria = new LinkedHashMap<>();
        for (Map.Entry<String, ModelChoice> candidate : candidates.entrySet()) {
            criteria.put(candidate.getKey(), candidate.getValue().criteria());
        }
        return criteria;
    }

    private void validate() {
        if (jevCall == null) {
            throw new IllegalArgumentException("jevCall must not be null");
        }
        if (choices.size() < 2) {
            throw new IllegalArgumentException("at least two model choices are required");
        }
        if (choices.size() > JevSelectionSupport.MAX_CHOICE_OPTIONS) {
            throw new IllegalArgumentException(
                    "model choices must not exceed " + JevSelectionSupport.MAX_CHOICE_OPTIONS);
        }
        for (String name : choices.keySet()) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("choice names must not be blank");
            }
        }
        for (ModelChoice choice : choices.values()) {
            Objects.requireNonNull(choice, "choices must not contain null values");
        }
        if (instructions == null || instructions.isBlank()) {
            throw new IllegalArgumentException("instructions must not be blank");
        }
        if (confidenceThreshold < 0 || confidenceThreshold > 1) {
            throw new IllegalArgumentException("confidenceThreshold must be between 0 and 1");
        }
    }

    /** A model candidate and the routing criteria sent to Jev. */
    public record ModelChoice(Model model, String criteria) {
        public ModelChoice {
            Objects.requireNonNull(model, "model");
            if (criteria == null || criteria.isBlank()) {
                throw new IllegalArgumentException("criteria must not be blank");
            }
        }
    }

    /** The selected model and the calibrated values returned by Jev. */
    public record RoutingDecision(
            Model model, Map<String, Double> probabilities, Double confidence) {
        public RoutingDecision {
            probabilities =
                    probabilities == null
                            ? Map.of()
                            : Collections.unmodifiableMap(new LinkedHashMap<>(probabilities));
        }

        static RoutingDecision fallback() {
            return new RoutingDecision(null, Map.of(), null);
        }
    }

    public static final class Builder {
        private final Function<SystemOneRequest, Mono<SystemOneResult>> jevCall;
        private final Map<String, ModelChoice> choices = new LinkedHashMap<>();
        private String instructions = DEFAULT_INSTRUCTIONS;
        private double confidenceThreshold = 0;
        private boolean failOpen = true;

        private Builder(Function<SystemOneRequest, Mono<SystemOneResult>> jevCall) {
            this.jevCall = jevCall;
        }

        public Builder choice(String name, Model model, String criteria) {
            Objects.requireNonNull(name, "name");
            choices.put(name, new ModelChoice(model, criteria));
            return this;
        }

        public Builder choices(Map<String, ModelChoice> choices) {
            Objects.requireNonNull(choices, "choices");
            this.choices.putAll(choices);
            return this;
        }

        public Builder instructions(String instructions) {
            this.instructions = instructions;
            return this;
        }

        public Builder confidenceThreshold(double confidenceThreshold) {
            this.confidenceThreshold = confidenceThreshold;
            return this;
        }

        public Builder failOpen(boolean failOpen) {
            this.failOpen = failOpen;
            return this;
        }

        public JevModelRouterMiddleware build() {
            return new JevModelRouterMiddleware(this);
        }
    }
}
