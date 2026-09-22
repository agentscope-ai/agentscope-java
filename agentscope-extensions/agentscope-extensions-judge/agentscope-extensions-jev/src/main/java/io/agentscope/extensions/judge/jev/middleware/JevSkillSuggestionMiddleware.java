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

package io.agentscope.extensions.judge.jev.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.extensions.judge.jev.Answer;
import io.agentscope.extensions.judge.jev.ChoiceAnswer;
import io.agentscope.extensions.judge.jev.ChoiceQuestion;
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.Question;
import io.agentscope.extensions.judge.jev.SystemOneRequest;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Suggests relevant skills to the primary model without replacing the existing skill prompt.
 *
 * <p>The middleware computes a suggestion in {@link #onAgent} and appends it in {@link
 * #onSystemPrompt}. It intentionally runs after {@code DynamicSkillMiddleware} by using a lower
 * middleware order.
 */
public final class JevSkillSuggestionMiddleware implements MiddlewareBase {

    private static final String CONTEXT_KEY =
            "io.agentscope.extensions.judge.jev.JevSkillSuggestionMiddleware.suggestion";

    private final Function<SystemOneRequest, Mono<SystemOneResult>> jevCall;
    private final List<AgentSkill> fixedSkills;
    private final List<AgentSkillRepository> repositories;
    private final SkillFilter builderFilter;
    private final int maxSuggestions;
    private final double confidenceThreshold;
    private final boolean failOpen;

    private JevSkillSuggestionMiddleware(Builder builder) {
        this.jevCall = builder.jevCall;
        this.fixedSkills = List.copyOf(builder.fixedSkills);
        this.repositories = List.copyOf(builder.repositories);
        this.builderFilter = builder.skillFilter != null ? builder.skillFilter : SkillFilter.all();
        this.maxSuggestions = builder.maxSuggestions;
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
        List<AgentSkill> skills = visibleSkills(ctx);
        String userText = JevSelectionSupport.latestUserText(input.msgs());
        if (skills.isEmpty() || userText.isBlank()) {
            ctx.put(CONTEXT_KEY, SkillSuggestion.empty());
            return next.apply(input);
        }

        return selectSkills(userText, skills)
                .onErrorResume(
                        error -> {
                            if (!failOpen) {
                                return Mono.error(error);
                            }
                            return Mono.just(SkillSuggestion.empty());
                        })
                .flatMapMany(
                        suggestion -> {
                            ctx.put(CONTEXT_KEY, suggestion);
                            return next.apply(input);
                        });
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        SkillSuggestion suggestion = ctx.get(CONTEXT_KEY);
        if (suggestion == null || suggestion.names().isEmpty()) {
            return Mono.just(currentPrompt);
        }
        String base = currentPrompt == null ? "" : currentPrompt;
        String separator = base.isEmpty() || base.endsWith("\n") ? "" : "\n";
        return Mono.just(
                base
                        + separator
                        + "\n<skill_relevance>\nRelevant to the current request: "
                        + String.join(", ", suggestion.names())
                        + ". Ignore this if it does not fit what the user actually asked"
                        + " for.\n</skill_relevance>");
    }

    private Mono<SkillSuggestion> selectSkills(String userText, List<AgentSkill> skills) {
        List<List<AgentSkill>> partitions =
                JevSelectionSupport.partition(
                        skills, JevSelectionSupport.MAX_CANDIDATES_PER_CHOICE);
        Map<String, Question> questions = new LinkedHashMap<>();
        for (int i = 0; i < partitions.size(); i++) {
            questions.put(
                    "skills_" + i,
                    new ChoiceQuestion(
                            "Which skill, if any, is most relevant to the user's request?",
                            JevSelectionSupport.skillCriteria(partitions.get(i))));
        }

        SystemOneRequest request =
                SystemOneRequest.builder()
                        .state(Map.of("userRequest", userText))
                        .questions(questions)
                        .build();

        return jevCall.apply(request)
                .flatMap(
                        result -> {
                            List<AgentSkill> shortlist = new ArrayList<>();
                            for (int i = 0; i < partitions.size(); i++) {
                                Answer answer = result.answers().get("skills_" + i);
                                if (answer instanceof ChoiceAnswer choice) {
                                    String topName = JevSelectionSupport.topName(choice);
                                    if (topName != null) {
                                        skills.stream()
                                                .filter(skill -> skill.getName().equals(topName))
                                                .findFirst()
                                                .ifPresent(shortlist::add);
                                    }
                                }
                            }
                            if (shortlist.isEmpty()) {
                                return Mono.just(SkillSuggestion.empty());
                            }
                            if (partitions.size() == 1) {
                                ChoiceAnswer answer =
                                        (ChoiceAnswer) result.answers().get("skills_0");
                                return Mono.just(
                                        new SkillSuggestion(
                                                JevSelectionSupport.selectedNames(
                                                        answer,
                                                        maxSuggestions,
                                                        confidenceThreshold),
                                                answer.confidence()));
                            }

                            Map<String, Question> rerank = new LinkedHashMap<>();
                            rerank.put(
                                    "skills",
                                    new ChoiceQuestion(
                                            "Which skill is most relevant to the user's request?",
                                            JevSelectionSupport.skillCriteria(shortlist)));
                            SystemOneRequest rerankRequest =
                                    SystemOneRequest.builder()
                                            .state(Map.of("userRequest", userText))
                                            .questions(rerank)
                                            .build();
                            return jevCall.apply(rerankRequest)
                                    .map(
                                            rerankResult -> {
                                                ChoiceAnswer answer =
                                                        (ChoiceAnswer)
                                                                rerankResult
                                                                        .answers()
                                                                        .get("skills");
                                                return new SkillSuggestion(
                                                        JevSelectionSupport.selectedNames(
                                                                answer,
                                                                maxSuggestions,
                                                                confidenceThreshold),
                                                        answer.confidence());
                                            });
                        });
    }

    private List<AgentSkill> visibleSkills(RuntimeContext ctx) {
        SkillFilter runtimeOverlay = ctx != null ? ctx.get(SkillFilter.class) : null;
        SkillFilter effectiveFilter = builderFilter.overlay(runtimeOverlay);
        Map<String, AgentSkill> byName = new LinkedHashMap<>();
        for (AgentSkill skill : fixedSkills) {
            if (effectiveFilter.isAllowed(skill.getName())) {
                byName.put(skill.getName(), skill);
            }
        }
        for (AgentSkillRepository repository : repositories) {
            try {
                List<AgentSkill> repositorySkills = repository.getAllSkills();
                if (repositorySkills != null) {
                    for (AgentSkill skill : repositorySkills) {
                        if (effectiveFilter.isAllowed(skill.getName())) {
                            byName.put(skill.getName(), skill);
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                // Fail open: a broken repository must not prevent the agent from running.
            }
        }
        return List.copyOf(byName.values());
    }

    private void validate() {
        if (jevCall == null) {
            throw new IllegalArgumentException("jevCall must not be null");
        }
        if (maxSuggestions <= 0) {
            throw new IllegalArgumentException("maxSuggestions must be positive");
        }
        if (confidenceThreshold < 0 || confidenceThreshold > 1) {
            throw new IllegalArgumentException("confidenceThreshold must be between 0 and 1");
        }
    }

    private record SkillSuggestion(List<String> names, Double confidence) {
        static SkillSuggestion empty() {
            return new SkillSuggestion(List.of(), null);
        }
    }

    public static final class Builder {
        private final Function<SystemOneRequest, Mono<SystemOneResult>> jevCall;
        private final List<AgentSkill> fixedSkills = new ArrayList<>();
        private final List<AgentSkillRepository> repositories = new ArrayList<>();
        private SkillFilter skillFilter;
        private int maxSuggestions = 3;
        private double confidenceThreshold = 0.5;
        private boolean failOpen = true;

        private Builder(Function<SystemOneRequest, Mono<SystemOneResult>> jevCall) {
            this.jevCall = jevCall;
        }

        public Builder skills(List<AgentSkill> skills) {
            if (skills != null) {
                fixedSkills.addAll(skills);
            }
            return this;
        }

        public Builder repositories(List<AgentSkillRepository> repositories) {
            if (repositories != null) {
                this.repositories.addAll(repositories);
            }
            return this;
        }

        public Builder skillFilter(SkillFilter skillFilter) {
            this.skillFilter = skillFilter;
            return this;
        }

        public Builder maxSuggestions(int maxSuggestions) {
            this.maxSuggestions = maxSuggestions;
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

        public JevSkillSuggestionMiddleware build() {
            return new JevSkillSuggestionMiddleware(this);
        }
    }
}
