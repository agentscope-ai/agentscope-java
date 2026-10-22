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
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.judge.openaidecisions.DecisionAnswer;
import io.agentscope.extensions.judge.openaidecisions.DecisionQuestion;
import io.agentscope.extensions.judge.openaidecisions.DecisionRequest;
import io.agentscope.extensions.judge.openaidecisions.DecisionResult;
import io.agentscope.extensions.judge.openaidecisions.OpenAIDecisionsClient;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Independently scores tool relevance, preserving essential tools. Explicitly opt in to filtering. */
public final class OpenAIDecisionsToolSelectionMiddleware implements MiddlewareBase {
    private final Function<DecisionRequest, Mono<DecisionResult>> call;
    private final Set<String> alwaysIncludeTools;
    private final int maxTools;
    private final double confidenceThreshold;
    private final double rejectionThreshold;
    private final int maxContextMessages;
    private final int maxContextChars;
    private final boolean enabled;
    private final boolean failOpen;

    private OpenAIDecisionsToolSelectionMiddleware(Builder b) {
        call = b.call;
        alwaysIncludeTools = Set.copyOf(b.alwaysIncludeTools);
        maxTools = b.maxTools;
        confidenceThreshold = b.confidenceThreshold;
        rejectionThreshold = b.rejectionThreshold;
        maxContextMessages = b.maxContextMessages;
        maxContextChars = b.maxContextChars;
        enabled = b.enabled;
        failOpen = b.failOpen;
        DecisionSupport.requireProbability(confidenceThreshold, "confidenceThreshold");
        DecisionSupport.requireProbability(rejectionThreshold, "rejectionThreshold");
        if (maxTools <= 0
                || maxContextMessages <= 0
                || maxContextChars <= 0
                || rejectionThreshold >= confidenceThreshold
                || alwaysIncludeTools.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException(
                    "require positive limits, nonblank tool names and rejection < selection");
        }
    }

    public static Builder builder(OpenAIDecisionsClient client) {
        return builder(Objects.requireNonNull(client, "client")::decide);
    }

    public static Builder builder(Function<DecisionRequest, Mono<DecisionResult>> call) {
        return new Builder(call);
    }

    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_REASONING);
    }

    @Override
    public int order() {
        return 0;
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        return Flux.defer(
                () -> {
                    if (!enabled || DecisionSupport.latestUserText(input.messages()).isBlank())
                        return next.apply(input);
                    List<ToolSchema> tools = input.tools() == null ? List.of() : input.tools();
                    Set<String> requiredTools = new HashSet<>(alwaysIncludeTools);
                    if (input.options() != null
                            && input.options().getToolChoice()
                                    instanceof ToolChoice.Specific specific) {
                        requiredTools.add(specific.toolName());
                    }
                    List<ToolSchema> optional =
                            tools.stream()
                                    .filter(t -> !requiredTools.contains(t.getName()))
                                    .toList();
                    if (optional.isEmpty()) return next.apply(input);
                    String evidence =
                            DecisionSupport.json(
                                    Map.of(
                                            "messages",
                                            DecisionSupport.recentMessages(
                                                    input.messages(),
                                                    maxContextMessages,
                                                    maxContextChars)));
                    return select(input, optional, evidence, requiredTools)
                            .onErrorResume(error -> failOpen ? Mono.just(input) : Mono.error(error))
                            .flatMapMany(next);
                });
    }

    private Mono<ReasoningInput> select(
            ReasoningInput input,
            List<ToolSchema> tools,
            String evidence,
            Set<String> requiredTools) {
        return Flux.range(0, (tools.size() - 1) / DecisionSupport.MAX_QUESTIONS + 1)
                .concatMap(
                        batchIndex -> {
                            int start = batchIndex * DecisionSupport.MAX_QUESTIONS;
                            List<ToolSchema> batch =
                                    tools.subList(
                                            start,
                                            Math.min(
                                                    start + DecisionSupport.MAX_QUESTIONS,
                                                    tools.size()));
                            var request = DecisionRequest.builder().input(evidence);
                            for (int i = 0; i < batch.size(); i++) {
                                ToolSchema tool = batch.get(i);
                                request.question(
                                        "tool_" + i,
                                        new DecisionQuestion.Predicate(
                                                "Is this tool applicable to the user's current"
                                                        + " request, given the conversation? Tool: "
                                                        + DecisionSupport.json(
                                                                Map.of(
                                                                        "name",
                                                                        tool.getName(),
                                                                        "description",
                                                                        Objects.toString(
                                                                                tool
                                                                                        .getDescription(),
                                                                                "")))));
                            }
                            return DecisionSupport.call(call, request.build())
                                    .map(
                                            result -> {
                                                Map<String, Double> scores = new LinkedHashMap<>();
                                                for (int i = 0; i < batch.size(); i++) {
                                                    if (!(result.answers().get("tool_" + i)
                                                            instanceof
                                                            DecisionAnswer.Predicate answer)) {
                                                        throw new IllegalStateException(
                                                                "Tool relevance was not answered");
                                                    }
                                                    DecisionSupport.requireProbability(
                                                            answer.probability(), "relevance");
                                                    scores.put(
                                                            batch.get(i).getName(),
                                                            answer.probability());
                                                }
                                                return scores;
                                            });
                        })
                .collectList()
                .map(
                        batches -> {
                            Map<String, Double> scores = new LinkedHashMap<>();
                            batches.forEach(scores::putAll);
                            if (scores.values().stream()
                                    .anyMatch(
                                            p ->
                                                    p > rejectionThreshold
                                                            && p < confidenceThreshold)) {
                                return input;
                            }
                            Set<String> selected =
                                    scores.entrySet().stream()
                                            .filter(e -> e.getValue() >= confidenceThreshold)
                                            .sorted(
                                                    Map.Entry.<String, Double>comparingByValue()
                                                            .reversed())
                                            .limit(maxTools)
                                            .map(Map.Entry::getKey)
                                            .collect(Collectors.toSet());
                            return new ReasoningInput(
                                    input.messages(),
                                    input.tools().stream()
                                            .filter(
                                                    t ->
                                                            requiredTools.contains(t.getName())
                                                                    || selected.contains(
                                                                            t.getName()))
                                            .toList(),
                                    input.options());
                        });
    }

    public static final class Builder {
        private final Function<DecisionRequest, Mono<DecisionResult>> call;
        private Set<String> alwaysIncludeTools =
                Set.of("load_skill_through_path", "reset_tools", "generate_response");
        private int maxTools = 3;
        private double confidenceThreshold = 0.8;
        private double rejectionThreshold = 0.2;
        private int maxContextMessages = 8;
        private int maxContextChars = 8000;
        private boolean enabled;
        private boolean failOpen = true;

        private Builder(Function<DecisionRequest, Mono<DecisionResult>> call) {
            this.call = Objects.requireNonNull(call, "call");
        }

        public Builder alwaysIncludeTools(Set<String> value) {
            alwaysIncludeTools = Set.copyOf(value);
            return this;
        }

        public Builder maxTools(int value) {
            maxTools = value;
            return this;
        }

        public Builder confidenceThreshold(double value) {
            confidenceThreshold = value;
            return this;
        }

        public Builder rejectionThreshold(double value) {
            rejectionThreshold = value;
            return this;
        }

        public Builder maxContextMessages(int value) {
            maxContextMessages = value;
            return this;
        }

        public Builder maxContextChars(int value) {
            maxContextChars = value;
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

        public OpenAIDecisionsToolSelectionMiddleware build() {
            return new OpenAIDecisionsToolSelectionMiddleware(this);
        }
    }
}
