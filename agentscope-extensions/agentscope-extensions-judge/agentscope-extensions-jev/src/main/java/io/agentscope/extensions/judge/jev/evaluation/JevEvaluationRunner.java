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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Fixed labelled cases, bounded concurrency and metadata-only per-case reports. No credentials required. */
public final class JevEvaluationRunner {
    public record Case(String scenario, Evaluator.Request request, boolean expectedPass) {
        public Case {
            Objects.requireNonNull(scenario);
            Objects.requireNonNull(request);
        }
    }

    public record Row(
            String scenario, String id, boolean expectedPass, Evaluator.Response response) {}

    public record Summary(
            int total,
            int correct,
            int incorrect,
            int abstained,
            int errors,
            double accuracy,
            double p50Millis,
            double p95Millis) {}

    public record Report(List<Row> rows, Summary overall, Map<String, Summary> scenarios) {
        public Report {
            rows = List.copyOf(rows);
            scenarios = Map.copyOf(scenarios);
        }
    }

    public Mono<Report> run(Evaluator evaluator, List<Case> cases, int concurrency) {
        List<Case> fixed = List.copyOf(cases);
        if (concurrency < 1
                || concurrency > 32
                || fixed.size() > 10000
                || fixed.stream().map(c -> c.request().id()).distinct().count() != fixed.size())
            throw new IllegalArgumentException(
                    "unique IDs, <=10000 cases and concurrency 1..32 required");
        return Flux.fromIterable(fixed)
                .flatMapSequential(
                        c ->
                                Mono.defer(() -> evaluator.evaluate(c.request()))
                                        .switchIfEmpty(
                                                Mono.error(
                                                        new IllegalStateException(
                                                                "empty evaluator response")))
                                        .onErrorResume(
                                                e ->
                                                        Mono.just(
                                                                new Evaluator.Response(
                                                                        c.request().id(),
                                                                        false,
                                                                        0,
                                                                        new JevJudge.Result(
                                                                                "evaluation-error",
                                                                                JevJudge.Status
                                                                                        .ERROR,
                                                                                Map.of(),
                                                                                JevJudge.ErrorCode
                                                                                        .BACKEND,
                                                                                null,
                                                                                null,
                                                                                java.time.Duration
                                                                                        .ZERO))))
                                        .map(
                                                r ->
                                                        new Row(
                                                                c.scenario(),
                                                                c.request().id(),
                                                                c.expectedPass(),
                                                                r)),
                        concurrency,
                        1)
                .collectList()
                .map(
                        rows -> {
                            Map<String, List<Row>> grouped = new LinkedHashMap<>();
                            rows.forEach(
                                    r ->
                                            grouped.computeIfAbsent(
                                                            r.scenario(), s -> new ArrayList<>())
                                                    .add(r));
                            Map<String, Summary> summaries = new LinkedHashMap<>();
                            grouped.forEach((s, r) -> summaries.put(s, summarize(r)));
                            return new Report(rows, summarize(rows), summaries);
                        });
    }

    private static Summary summarize(List<Row> rows) {
        int correct = 0, incorrect = 0, abstained = 0, errors = 0;
        List<Double> durations = new ArrayList<>();
        for (Row row : rows) {
            var v = row.response().verdict();
            durations.add(v.elapsed().toNanos() / 1_000_000d);
            switch (v.status()) {
                case ERROR -> errors++;
                case INCONCLUSIVE -> abstained++;
                default -> {
                    if ((v.status() == JevJudge.Status.PASS) == row.expectedPass()) correct++;
                    else incorrect++;
                }
            }
        }
        Collections.sort(durations);
        return new Summary(
                rows.size(),
                correct,
                incorrect,
                abstained,
                errors,
                rows.isEmpty() ? 0 : (double) correct / rows.size(),
                percentile(durations, .5),
                percentile(durations, .95));
    }

    private static double percentile(List<Double> sorted, double p) {
        return sorted.isEmpty()
                ? 0
                : sorted.get(Math.max(0, (int) Math.ceil(p * sorted.size()) - 1));
    }
}
