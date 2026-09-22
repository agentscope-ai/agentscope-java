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

package io.agentscope.extensions.jev.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.jev.Answer;
import io.agentscope.extensions.jev.ChoiceAnswer;
import io.agentscope.extensions.jev.ChoiceQuestion;
import io.agentscope.extensions.jev.JevClient;
import io.agentscope.extensions.jev.Question;
import io.agentscope.extensions.jev.SystemOneRequest;
import io.agentscope.extensions.jev.SystemOneResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reduces the tool schema list sent to the primary model.
 *
 * <p>Always-included tools are preserved. Optional tools are ranked by Jev and only those with a
 * probability above the synthetic "none" option are kept, up to {@code maxTools}.
 */
public final class JevToolSelectionMiddleware implements MiddlewareBase {

    public static final Set<String> DEFAULT_ALWAYS_INCLUDE_TOOLS =
            Set.of("load_skill_through_path", "reset_tools", "generate_response");

    private final Function<SystemOneRequest, Mono<SystemOneResult>> jevCall;
    private final Set<String> alwaysIncludeTools;
    private final int maxTools;
    private final double confidenceThreshold;
    private final boolean failOpen;

    private JevToolSelectionMiddleware(Builder builder) {
        this.jevCall = builder.jevCall;
        this.alwaysIncludeTools = Set.copyOf(builder.alwaysIncludeTools);
        this.maxTools = builder.maxTools;
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
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        List<ToolSchema> tools = input.tools() == null ? List.of() : input.tools();
        List<ToolSchema> optionalTools =
                tools.stream()
                        .filter(tool -> !alwaysIncludeTools.contains(tool.getName()))
                        .toList();
        Set<String> optionalToolNames = new LinkedHashSet<>();
        optionalTools.forEach(tool -> optionalToolNames.add(tool.getName()));

        if (optionalTools.isEmpty() || optionalTools.size() <= maxTools) {
            return next.apply(input);
        }

        String userText = JevSelectionSupport.latestUserText(input.messages());
        if (userText.isBlank()) {
            return next.apply(input);
        }

        return selectTools(JevSelectionSupport.messagesState(input.messages()), optionalTools)
                .onErrorResume(
                        error -> {
                            if (!failOpen) {
                                return Mono.error(error);
                            }
                            return Mono.just(optionalToolNames);
                        })
                .flatMapMany(
                        selectedNames -> {
                            if (selectedNames.isEmpty()) {
                                return next.apply(input);
                            }
                            return next.apply(
                                    new ReasoningInput(
                                            input.messages(),
                                            filteredTools(tools, selectedNames),
                                            input.options()));
                        });
    }

    private Mono<Set<String>> selectTools(
            Map<String, Object> state, List<ToolSchema> optionalTools) {
        List<List<ToolSchema>> partitions =
                JevSelectionSupport.partition(
                        optionalTools, JevSelectionSupport.MAX_CANDIDATES_PER_CHOICE);
        Map<String, Question> questions = new LinkedHashMap<>();
        for (int i = 0; i < partitions.size(); i++) {
            questions.put(
                    "tools_" + i,
                    new ChoiceQuestion(
                            "Which tool, if any, is most useful for the current conversation in"
                                    + " `messages`?",
                            JevSelectionSupport.toolCriteria(partitions.get(i))));
        }

        SystemOneRequest request =
                SystemOneRequest.builder().state(state).questions(questions).build();

        return jevCall.apply(request)
                .flatMap(
                        result -> {
                            // Pick one representative from each chunk for the shortlist.
                            List<ToolSchema> shortlist = new ArrayList<>();
                            for (int i = 0; i < partitions.size(); i++) {
                                Answer answer = result.answers().get("tools_" + i);
                                if (answer instanceof ChoiceAnswer choice) {
                                    String topName = JevSelectionSupport.topName(choice);
                                    if (topName != null) {
                                        optionalTools.stream()
                                                .filter(tool -> tool.getName().equals(topName))
                                                .findFirst()
                                                .ifPresent(shortlist::add);
                                    }
                                }
                            }
                            if (shortlist.isEmpty()) {
                                return Mono.just(Set.of());
                            }
                            if (partitions.size() == 1) {
                                ChoiceAnswer answer =
                                        (ChoiceAnswer) result.answers().get("tools_0");
                                return Mono.just(
                                        new LinkedHashSet<>(
                                                JevSelectionSupport.selectedNames(
                                                        answer, maxTools, confidenceThreshold)));
                            }

                            Map<String, Question> rerank = new LinkedHashMap<>();
                            rerank.put(
                                    "tools",
                                    new ChoiceQuestion(
                                            "Which tool is most useful for the current conversation"
                                                    + " in `messages`?",
                                            JevSelectionSupport.toolCriteria(shortlist)));
                            SystemOneRequest rerankRequest =
                                    SystemOneRequest.builder()
                                            .state(state)
                                            .questions(rerank)
                                            .build();
                            return jevCall.apply(rerankRequest)
                                    .map(
                                            rerankResult -> {
                                                ChoiceAnswer answer =
                                                        (ChoiceAnswer)
                                                                rerankResult.answers().get("tools");
                                                return new LinkedHashSet<>(
                                                        JevSelectionSupport.selectedNames(
                                                                answer,
                                                                maxTools,
                                                                confidenceThreshold));
                                            });
                        });
    }

    private List<ToolSchema> filteredTools(
            List<ToolSchema> tools, Set<String> selectedOptionalTools) {
        return tools.stream()
                .filter(
                        tool ->
                                alwaysIncludeTools.contains(tool.getName())
                                        || selectedOptionalTools.contains(tool.getName()))
                .toList();
    }

    private void validate() {
        if (jevCall == null) {
            throw new IllegalArgumentException("jevCall must not be null");
        }
        if (maxTools <= 0) {
            throw new IllegalArgumentException("maxTools must be positive");
        }
        if (confidenceThreshold < 0 || confidenceThreshold > 1) {
            throw new IllegalArgumentException("confidenceThreshold must be between 0 and 1");
        }
    }

    public static final class Builder {
        private final Function<SystemOneRequest, Mono<SystemOneResult>> jevCall;
        private final Set<String> alwaysIncludeTools =
                new LinkedHashSet<>(DEFAULT_ALWAYS_INCLUDE_TOOLS);
        private int maxTools = 3;
        private double confidenceThreshold = 0.5;
        private boolean failOpen = true;

        private Builder(Function<SystemOneRequest, Mono<SystemOneResult>> jevCall) {
            this.jevCall = jevCall;
        }

        public Builder alwaysIncludeTools(Set<String> tools) {
            alwaysIncludeTools.clear();
            if (tools != null) {
                alwaysIncludeTools.addAll(tools);
            }
            return this;
        }

        public Builder maxTools(int maxTools) {
            this.maxTools = maxTools;
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

        public JevToolSelectionMiddleware build() {
            return new JevToolSelectionMiddleware(this);
        }
    }
}
