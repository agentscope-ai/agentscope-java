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

package io.agentscope.extensions.judge.jev.application;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.NoulAnswer;
import io.agentscope.extensions.judge.jev.NoulQuestion;
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

/** Independent candidate classification shared by explicit application workflows. */
public final class JevCandidateSelector {
    public record Selection(
            List<String> selected,
            List<String> rejected,
            List<String> uncertain,
            Map<String, Double> scores) {
        public Selection {
            selected = List.copyOf(selected);
            rejected = List.copyOf(rejected);
            uncertain = List.copyOf(uncertain);
            scores = Map.copyOf(scores);
        }
    }

    private final Function<SystemOneRequest, Mono<SystemOneResult>> call;
    private final JevExecution execution;
    private final double accept;
    private final double reject;

    public JevCandidateSelector(
            JevClient client, JevExecution.Options options, double reject, double accept) {
        this(client::systemOne, options, reject, accept);
    }

    public JevCandidateSelector(
            Function<SystemOneRequest, Mono<SystemOneResult>> call,
            JevExecution.Options options,
            double reject,
            double accept) {
        this.call = Objects.requireNonNull(call);
        this.execution = new JevExecution("application-selection", options);
        if (!Double.isFinite(reject)
                || !Double.isFinite(accept)
                || reject < 0
                || accept > 1
                || reject >= accept) throw new IllegalArgumentException("invalid thresholds");
        this.accept = accept;
        this.reject = reject;
    }

    public JevExecution.Mode mode() {
        return execution.mode();
    }

    public Mono<JevExecution.Decision<Selection>> select(
            RuntimeContext ctx, Object state, String question, Map<String, ?> candidates) {
        Objects.requireNonNull(state);
        Objects.requireNonNull(question);
        Objects.requireNonNull(candidates);
        if (question.isBlank() || candidates.size() > 1024)
            throw new IllegalArgumentException("question required; max 1024 candidates");
        Map<String, Object> copy = new LinkedHashMap<>();
        candidates.forEach(
                (id, value) -> {
                    if (id == null || id.isBlank())
                        throw new IllegalArgumentException("id required");
                    copy.put(id, Objects.requireNonNull(value));
                });
        return execution.execute(
                ctx,
                () ->
                        Flux.fromIterable(copy.keySet())
                                .buffer(64)
                                .concatMap(
                                        ids -> {
                                            var request = SystemOneRequest.builder().state(state);
                                            for (int i = 0; i < ids.size(); i++)
                                                request.question(
                                                        "item_" + i,
                                                        new NoulQuestion(
                                                                Map.of(
                                                                        "question",
                                                                        question,
                                                                        "candidate",
                                                                        copy.get(ids.get(i))),
                                                                null));
                                            return Mono.defer(() -> call.apply(request.build()))
                                                    .switchIfEmpty(
                                                            Mono.error(
                                                                    new IllegalStateException(
                                                                            "empty result")))
                                                    .map(
                                                            r -> {
                                                                if (r.answers() == null
                                                                        || r.answers().size()
                                                                                != ids.size())
                                                                    throw new IllegalArgumentException(
                                                                            "invalid response");
                                                                Map<String, Double> scores =
                                                                        new LinkedHashMap<>();
                                                                for (int i = 0;
                                                                        i < ids.size();
                                                                        i++) {
                                                                    if (!(r.answers()
                                                                                            .get(
                                                                                                    "item_"
                                                                                                            + i)
                                                                                    instanceof
                                                                                    NoulAnswer n)
                                                                            || n.noul() == null
                                                                            || !Double.isFinite(
                                                                                    n.noul())
                                                                            || n.noul() < 0
                                                                            || n.noul() > 1)
                                                                        throw new IllegalArgumentException(
                                                                                "invalid score");
                                                                    scores.put(
                                                                            ids.get(i), n.noul());
                                                                }
                                                                return scores;
                                                            });
                                        })
                                .collectList()
                                .map(
                                        parts -> {
                                            Map<String, Double> scores = new LinkedHashMap<>();
                                            parts.forEach(scores::putAll);
                                            List<String> selected =
                                                    scores.entrySet().stream()
                                                            .filter(e -> e.getValue() >= accept)
                                                            .sorted(
                                                                    Map.Entry
                                                                            .<String, Double>
                                                                                    comparingByValue()
                                                                            .reversed()
                                                                            .thenComparing(
                                                                                    Map.Entry
                                                                                            ::getKey))
                                                            .map(Map.Entry::getKey)
                                                            .toList();
                                            List<String> rejected = new ArrayList<>(),
                                                    uncertain = new ArrayList<>();
                                            scores.forEach(
                                                    (id, p) -> {
                                                        if (p <= reject) rejected.add(id);
                                                        else if (p < accept) uncertain.add(id);
                                                    });
                                            return new JevExecution.Decision<>(
                                                    JevExecution.Status.DECIDED,
                                                    new Selection(
                                                            selected, rejected, uncertain, scores),
                                                    "CLASSIFIED",
                                                    Map.of(
                                                            "selected",
                                                            String.join(",", selected),
                                                            "uncertain",
                                                            String.join(",", uncertain)));
                                        }));
    }
}
