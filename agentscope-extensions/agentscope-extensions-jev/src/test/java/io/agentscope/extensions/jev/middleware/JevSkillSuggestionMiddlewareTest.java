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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.extensions.jev.Answer;
import io.agentscope.extensions.jev.ChoiceAnswer;
import io.agentscope.extensions.jev.ChoiceQuestion;
import io.agentscope.extensions.jev.SystemOneRequest;
import io.agentscope.extensions.jev.SystemOneResult;
import io.agentscope.extensions.jev.Usage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class JevSkillSuggestionMiddlewareTest {

    @Test
    void appendsSuggestedSkillsToSystemPrompt() {
        AtomicReference<AgentInput> captured = new AtomicReference<>();
        RuntimeContext ctx = RuntimeContext.empty();
        AgentSkill skill = skill("powerpoint", "Create PowerPoint decks");
        Function<SystemOneRequest, Mono<SystemOneResult>> jevCall =
                request ->
                        Mono.just(
                                result(
                                        Map.of(
                                                "skills_0",
                                                choice(
                                                        "powerpoint",
                                                        Map.of(
                                                                "powerpoint",
                                                                0.8,
                                                                JevSelectionSupport.NONE_OPTION,
                                                                0.2),
                                                        0.9))));

        JevSkillSuggestionMiddleware middleware =
                JevSkillSuggestionMiddleware.builder(jevCall).skills(List.of(skill)).build();

        middleware
                .onAgent(
                        null,
                        ctx,
                        new AgentInput(List.of(new UserMessage("Build a deck"))),
                        input -> {
                            captured.set(input);
                            return reactor.core.publisher.Flux.empty();
                        })
                .then()
                .block();

        assertEquals("Build a deck", captured.get().msgs().get(0).getTextContent());

        String prompt = middleware.onSystemPrompt(null, ctx, "base prompt").block();
        assertTrue(prompt.contains("base prompt"));
        assertTrue(prompt.contains("<skill_relevance>"));
        assertTrue(prompt.contains("powerpoint"));
    }

    @Test
    void doesNotAppendWhenNoneWins() {
        RuntimeContext ctx = RuntimeContext.empty();
        AgentSkill skill = skill("powerpoint", "Create PowerPoint decks");
        Function<SystemOneRequest, Mono<SystemOneResult>> jevCall =
                request ->
                        Mono.just(
                                result(
                                        Map.of(
                                                "skills_0",
                                                choice(
                                                        JevSelectionSupport.NONE_OPTION,
                                                        Map.of(
                                                                "powerpoint",
                                                                0.2,
                                                                JevSelectionSupport.NONE_OPTION,
                                                                0.8),
                                                        0.9))));

        JevSkillSuggestionMiddleware middleware =
                JevSkillSuggestionMiddleware.builder(jevCall).skills(List.of(skill)).build();

        middleware
                .onAgent(
                        null,
                        ctx,
                        new AgentInput(List.of(new UserMessage("Say hi"))),
                        input -> reactor.core.publisher.Flux.empty())
                .then()
                .block();

        String prompt = middleware.onSystemPrompt(null, ctx, "base prompt").block();
        assertEquals("base prompt", prompt);
    }

    @Test
    void failsOpenWhenJevFails() {
        RuntimeContext ctx = RuntimeContext.empty();
        AgentSkill skill = skill("powerpoint", "Create PowerPoint decks");
        JevSkillSuggestionMiddleware middleware =
                JevSkillSuggestionMiddleware.builder(
                                request -> Mono.error(new IllegalStateException("offline")))
                        .skills(List.of(skill))
                        .build();

        middleware
                .onAgent(
                        null,
                        ctx,
                        new AgentInput(List.of(new UserMessage("Build a deck"))),
                        input -> reactor.core.publisher.Flux.empty())
                .then()
                .block();

        String prompt = middleware.onSystemPrompt(null, ctx, "base prompt").block();
        assertEquals("base prompt", prompt);
    }

    @Test
    void respectsSkillFilterWhenSelectingVisibleSkills() {
        RuntimeContext ctx = RuntimeContext.empty();
        AgentSkill visible = skill("powerpoint", "Create PowerPoint decks");
        AgentSkill hidden = skill("apple-notes", "Manage Apple Notes");
        AtomicReference<SystemOneRequest> captured = new AtomicReference<>();
        Function<SystemOneRequest, Mono<SystemOneResult>> jevCall =
                request -> {
                    captured.set(request);
                    return Mono.just(
                            result(
                                    Map.of(
                                            "skills_0",
                                            choice(
                                                    "powerpoint",
                                                    Map.of(
                                                            "powerpoint",
                                                            0.8,
                                                            JevSelectionSupport.NONE_OPTION,
                                                            0.2),
                                                    0.9))));
                };

        JevSkillSuggestionMiddleware middleware =
                JevSkillSuggestionMiddleware.builder(jevCall)
                        .skills(List.of(visible, hidden))
                        .skillFilter(SkillFilter.only("powerpoint"))
                        .build();

        middleware
                .onAgent(
                        null,
                        ctx,
                        new AgentInput(List.of(new UserMessage("Build a deck"))),
                        input -> reactor.core.publisher.Flux.empty())
                .then()
                .block();

        ChoiceQuestion question = (ChoiceQuestion) captured.get().questions().get("skills_0");
        assertTrue(question.criteria().containsKey("powerpoint"));
        assertTrue(!question.criteria().containsKey("apple-notes"));
    }

    @Test
    void chunksAndReranksLargeSkillRosters() {
        List<AgentSkill> skills = new ArrayList<>();
        for (int i = 0; i < 255; i++) {
            skills.add(skill("skill_" + i, "Skill " + i));
        }
        AtomicInteger calls = new AtomicInteger();
        List<SystemOneRequest> requests = new ArrayList<>();

        Function<SystemOneRequest, Mono<SystemOneResult>> jevCall =
                request -> {
                    requests.add(request);
                    if (calls.incrementAndGet() == 1) {
                        Map<String, Answer> answers = new LinkedHashMap<>();
                        answers.put(
                                "skills_0",
                                choice(
                                        "skill_0",
                                        Map.of(
                                                "skill_0",
                                                0.7,
                                                JevSelectionSupport.NONE_OPTION,
                                                0.3),
                                        0.9));
                        answers.put(
                                "skills_1",
                                choice(
                                        "skill_254",
                                        Map.of(
                                                "skill_254",
                                                0.7,
                                                JevSelectionSupport.NONE_OPTION,
                                                0.3),
                                        0.9));
                        return Mono.just(result(answers));
                    }
                    return Mono.just(
                            result(
                                    Map.of(
                                            "skills",
                                            choice(
                                                    "skill_0",
                                                    Map.of(
                                                            "skill_0",
                                                            0.8,
                                                            "skill_254",
                                                            0.1,
                                                            JevSelectionSupport.NONE_OPTION,
                                                            0.1),
                                                    0.95))));
                };

        RuntimeContext ctx = RuntimeContext.empty();
        JevSkillSuggestionMiddleware middleware =
                JevSkillSuggestionMiddleware.builder(jevCall)
                        .skills(skills)
                        .maxSuggestions(1)
                        .build();

        middleware
                .onAgent(
                        null,
                        ctx,
                        new AgentInput(List.of(new UserMessage("Use skill 0"))),
                        input -> reactor.core.publisher.Flux.empty())
                .then()
                .block();

        String prompt = middleware.onSystemPrompt(null, ctx, "base").block();
        assertEquals(2, requests.size());
        assertEquals(2, requests.get(0).questions().size());
        assertEquals(1, requests.get(1).questions().size());
        assertTrue(prompt.contains("skill_0"));
    }

    private static AgentSkill skill(String name, String description) {
        return new AgentSkill(name, description, "content", Map.of());
    }

    private static ChoiceAnswer choice(
            String selected, Map<String, Double> probabilities, double confidence) {
        return new ChoiceAnswer(selected, probabilities, confidence);
    }

    private static SystemOneResult result(Map<String, Answer> answers) {
        return new SystemOneResult("jev-test", answers, new Usage(1, 1));
    }
}
