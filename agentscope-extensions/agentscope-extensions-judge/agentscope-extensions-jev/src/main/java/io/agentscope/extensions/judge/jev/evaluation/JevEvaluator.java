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

package io.agentscope.extensions.judge.jev.evaluation;

import io.agentscope.extensions.judge.jev.JevJudge;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Mono;

/** Preserves the full judge result; the scalar score is the fraction of criteria that passed. */
public final class JevEvaluator implements Evaluator {
    private final JevJudge judge;
    private final JevJudge.Definition definition;

    public JevEvaluator(JevJudge judge, JevJudge.Definition definition) {
        this.judge = Objects.requireNonNull(judge);
        this.definition = Objects.requireNonNull(definition);
    }

    @Override
    public Mono<Response> evaluate(Request r) {
        return Mono.defer(
                        () ->
                                judge.judge(
                                        Map.of(
                                                "user_question",
                                                r.question(),
                                                "supporting_context",
                                                r.supportingContext(),
                                                "assistant_answer",
                                                r.answer()),
                                        definition))
                .map(
                        v ->
                                new Response(
                                        r.id(),
                                        v.status() == JevJudge.Status.PASS,
                                        (double)
                                                        v.findings().values().stream()
                                                                .filter(
                                                                        f ->
                                                                                f.status()
                                                                                        == JevJudge
                                                                                                .Status
                                                                                                .PASS)
                                                                .count()
                                                / definition.criteria().size(),
                                        v));
    }
}
