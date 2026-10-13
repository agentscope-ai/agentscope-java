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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Fixed trace datasets with separate metric/scenario denominators and request-level accounting. */
public final class JevTraceRunner {
    public record Expected(Boolean passed, String label) {
        public Expected {
            if ((passed == null) == (label == null))
                throw new IllegalArgumentException("exactly one expected value required");
        }
    }

    public record Case(String scenario, JevTrace trace, Map<String, Expected> expected) {
        public Case {
            if (scenario == null || scenario.isBlank())
                throw new IllegalArgumentException("scenario required");
            Objects.requireNonNull(trace);
            expected = Map.copyOf(expected);
        }
    }

    public record Row(
            String scenario,
            String traceId,
            Map<String, Expected> expected,
            JevTraceEvaluator.Report report) {
        public Row {
            expected = Map.copyOf(expected);
        }
    }

    public record Summary(
            int total,
            int labelled,
            int correct,
            int incorrect,
            int inconclusive,
            int skipped,
            int errors,
            int unscored,
            double accuracy) {}

    public record Latency(double p50Millis, double p95Millis) {}

    public record Usage(
            int requests, int responsesWithUsage, long inputTokens, long outputTokens) {}

    public record Report(
            List<Row> rows,
            Map<String, Summary> metrics,
            Map<String, Map<String, Summary>> scenarios,
            Latency latency,
            Usage usage,
            Duration elapsed) {
        public Report {
            rows = List.copyOf(rows);
            metrics = Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
            Map<String, Map<String, Summary>> copy = new LinkedHashMap<>();
            scenarios.forEach(
                    (k, v) -> copy.put(k, Collections.unmodifiableMap(new LinkedHashMap<>(v))));
            scenarios = Collections.unmodifiableMap(copy);
        }
    }

    public Mono<Report> run(JevTraceEvaluator evaluator, List<Case> cases, int concurrency) {
        Objects.requireNonNull(evaluator);
        List<Case> fixed = List.copyOf(cases);
        if (concurrency < 1
                || concurrency > 32
                || fixed.size() > 10000
                || fixed.stream().map(c -> c.trace().id()).distinct().count() != fixed.size())
            throw new IllegalArgumentException(
                    "<=10000 unique trace IDs and concurrency 1..32 required");
        for (var c : fixed)
            if (!evaluator.metricIds().containsAll(c.expected().keySet()))
                throw new IllegalArgumentException("expectations must refer to configured metrics");
        return Mono.defer(
                () -> {
                    long start = System.nanoTime();
                    return Flux.fromIterable(fixed)
                            .flatMapSequential(
                                    c ->
                                            evaluator
                                                    .evaluate(c.trace())
                                                    .map(
                                                            r ->
                                                                    new Row(
                                                                            c.scenario(),
                                                                            c.trace().id(),
                                                                            c.expected(),
                                                                            r)),
                                    concurrency,
                                    1)
                            .collectList()
                            .map(rows -> report(rows, Duration.ofNanos(System.nanoTime() - start)));
                });
    }

    private static Report report(List<Row> rows, Duration elapsed) {
        Map<String, List<Row>> grouped = new LinkedHashMap<>();
        rows.forEach(r -> grouped.computeIfAbsent(r.scenario(), k -> new ArrayList<>()).add(r));
        Map<String, Map<String, Summary>> scenarios = new LinkedHashMap<>();
        grouped.forEach((k, v) -> scenarios.put(k, summarize(v)));
        List<Double> times =
                rows.stream()
                        .map(r -> r.report().elapsed().toNanos() / 1_000_000d)
                        .sorted()
                        .toList();
        int requests = 0, known = 0;
        long input = 0, output = 0;
        for (var row : rows) {
            requests += row.report().requests();
            for (var call : row.report().usage())
                if (call.usage() != null
                        && call.usage().inputTokens() >= 0
                        && call.usage().outputTokens() >= 0) {
                    known++;
                    input += call.usage().inputTokens();
                    output += call.usage().outputTokens();
                }
        }
        return new Report(
                rows,
                summarize(rows),
                scenarios,
                new Latency(percentile(times, .5), percentile(times, .95)),
                new Usage(requests, known, input, output),
                elapsed);
    }

    private static Map<String, Summary> summarize(List<Row> rows) {
        Map<String, int[]> counts = new LinkedHashMap<>();
        for (var row : rows)
            for (var entry : row.report().results()) {
                int[] c = counts.computeIfAbsent(entry.id(), k -> new int[8]);
                c[0]++;
                Expected expected = row.expected().get(entry.id());
                if (expected != null) c[1]++;
                var result = entry.result();
                switch (result.status()) {
                    case INCONCLUSIVE -> c[4]++;
                    case SKIPPED -> c[5]++;
                    case ERROR -> c[6]++;
                    case DECIDED -> {
                        if (expected != null) {
                            Object actual =
                                    expected.label() != null ? result.label() : result.passed();
                            Object target =
                                    expected.label() != null ? expected.label() : expected.passed();
                            if (actual == null) c[7]++;
                            else c[Objects.equals(actual, target) ? 2 : 3]++;
                        }
                    }
                }
            }
        Map<String, Summary> result = new LinkedHashMap<>();
        counts.forEach(
                (k, c) ->
                        result.put(
                                k,
                                new Summary(
                                        c[0],
                                        c[1],
                                        c[2],
                                        c[3],
                                        c[4],
                                        c[5],
                                        c[6],
                                        c[7],
                                        c[1] == 0 ? 0 : (double) c[2] / c[1])));
        return result;
    }

    private static double percentile(List<Double> values, double p) {
        return values.isEmpty() ? 0 : values.get((int) Math.ceil(p * values.size()) - 1);
    }
}
