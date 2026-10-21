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

import io.agentscope.extensions.judge.jev.Answer;
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.Question;
import io.agentscope.extensions.judge.jev.SystemOneRequest;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import io.agentscope.extensions.judge.jev.Usage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Packs compatible metric state, separates conflicts and preserves per-metric failure states. */
public final class JevTraceEvaluator {
    public record Limits(Duration budget, int maxQuestions, int maxStateChars) {
        public Limits {
            if (budget == null
                    || budget.isZero()
                    || budget.isNegative()
                    || maxQuestions < 1
                    || maxStateChars < 1)
                throw new IllegalArgumentException("positive trace evaluation limits required");
        }

        public static Limits defaults() {
            return new Limits(Duration.ofSeconds(3), 128, 100000);
        }
    }

    public record Entry(String id, String version, JevMetricResult result) {}

    /** Usage is per packed request, never multiplied by its metric count. Null means unavailable. */
    public record RequestUsage(String model, Usage usage) {}

    public record Report(
            String traceId,
            List<Entry> results,
            int requests,
            List<RequestUsage> usage,
            Duration elapsed) {
        public Report {
            results = List.copyOf(results);
            usage = List.copyOf(usage);
        }

        public boolean passed() {
            return !results.isEmpty()
                    && results.stream()
                            .allMatch(
                                    e ->
                                            e.result().status() == JevMetricResult.Status.DECIDED
                                                    && Boolean.TRUE.equals(e.result().passed()));
        }

        public JevMetricResult result(String id) {
            return results.stream()
                    .filter(e -> e.id().equals(id))
                    .findFirst()
                    .orElseThrow()
                    .result();
        }
    }

    private record Member(JevTraceMetric metric, Map<String, Question> questions) {}

    private static final class Group {
        final Map<String, Object> state = new LinkedHashMap<>();
        final Map<String, Question> questions = new LinkedHashMap<>();
        final List<Member> members = new ArrayList<>();
    }

    private final Function<SystemOneRequest, Mono<SystemOneResult>> caller;
    private final List<JevTraceMetric> metrics;
    private final Limits limits;

    public JevTraceEvaluator(JevClient client, List<JevTraceMetric> metrics, Limits limits) {
        this(Objects.requireNonNull(client)::systemOne, metrics, limits);
    }

    /** A caller must return a nonblocking publisher; no backend is discovered from environment. */
    public JevTraceEvaluator(
            Function<SystemOneRequest, Mono<SystemOneResult>> caller,
            List<JevTraceMetric> metrics,
            Limits limits) {
        this.caller = Objects.requireNonNull(caller);
        this.metrics = List.copyOf(metrics);
        this.limits = Objects.requireNonNull(limits);
        if (metrics.isEmpty()
                || metrics.size() > 128
                || metrics.stream().map(JevTraceMetric::id).distinct().count() != metrics.size())
            throw new IllegalArgumentException("1..128 unique metrics required");
        metrics.forEach(
                m -> {
                    requireId(m.id());
                    if (m.version() == null || m.version().isBlank())
                        throw new IllegalArgumentException("metric version required");
                });
    }

    public List<String> metricIds() {
        return metrics.stream().map(JevTraceMetric::id).toList();
    }

    public Mono<Report> evaluate(JevTrace trace) {
        Objects.requireNonNull(trace);
        return Mono.defer(
                () -> {
                    Evaluation run = new Evaluation(trace);
                    List<Group> groups = plan(trace, run.done);
                    Duration remaining =
                            limits.budget().minusNanos(System.nanoTime() - run.started);
                    if (remaining.isNegative() || remaining.isZero()) {
                        run.timeout();
                        return Mono.just(run.report());
                    }
                    return Flux.fromIterable(groups)
                            .concatMap(group -> runGroup(group, run), 1)
                            .then()
                            .timeout(remaining)
                            .onErrorResume(
                                    TimeoutException.class,
                                    error -> {
                                        run.timeout();
                                        return Mono.empty();
                                    })
                            .then(Mono.fromSupplier(run::report));
                });
    }

    private final class Evaluation {
        final JevTrace trace;
        final long started = System.nanoTime();
        final Map<String, JevMetricResult> done = new LinkedHashMap<>();
        final List<RequestUsage> usage = new ArrayList<>();
        int requests;

        Evaluation(JevTrace trace) {
            this.trace = trace;
        }

        void timeout() {
            metrics.forEach(m -> done.putIfAbsent(m.id(), JevMetricResult.error("TIMEOUT")));
        }

        Report report() {
            return new Report(
                    trace.id(),
                    metrics.stream()
                            .map(m -> new Entry(m.id(), m.version(), done.get(m.id())))
                            .toList(),
                    requests,
                    usage,
                    Duration.ofNanos(System.nanoTime() - started));
        }
    }

    private Mono<Void> runGroup(Group group, Evaluation run) {
        return Mono.defer(
                () -> {
                    run.requests++;
                    var request =
                            SystemOneRequest.builder()
                                    .state(Collections.unmodifiableMap(group.state))
                                    .questions(group.questions)
                                    .build();
                    return Mono.defer(() -> caller.apply(request))
                            .switchIfEmpty(Mono.error(new InvalidResponse()))
                            .doOnNext(response -> acceptResponse(group, request, response, run))
                            .then()
                            .onErrorResume(
                                    error -> {
                                        if (error instanceof JevTextBackend.InvalidReply invalid)
                                            run.usage.add(
                                                    new RequestUsage(
                                                            invalid.model(), invalid.usage()));
                                        String reason =
                                                error instanceof InvalidResponse
                                                                || error
                                                                        instanceof
                                                                        JevTextBackend.InvalidReply
                                                        ? "INVALID_RESPONSE"
                                                        : "BACKEND_ERROR";
                                        group.members.forEach(
                                                m ->
                                                        run.done.put(
                                                                m.metric().id(),
                                                                JevMetricResult.error(reason)));
                                        return Mono.empty();
                                    });
                });
    }

    private void acceptResponse(
            Group group, SystemOneRequest request, SystemOneResult response, Evaluation run) {
        // Even an invalid response can carry billable usage. Never fabricate zero cost.
        run.usage.add(new RequestUsage(response.model(), response.usage()));
        try {
            JevClient.validateResponse(request, response);
        } catch (RuntimeException error) {
            throw new InvalidResponse();
        }
        for (Member member : group.members) {
            Map<String, Answer> answers = new LinkedHashMap<>();
            member.questions()
                    .keySet()
                    .forEach(
                            id ->
                                    answers.put(
                                            id,
                                            response.answers()
                                                    .get(member.metric().id() + "." + id)));
            try {
                var result =
                        member.metric().reduce(Collections.unmodifiableMap(answers), run.trace);
                run.done.put(member.metric().id(), Objects.requireNonNull(result));
            } catch (RuntimeException error) {
                run.done.put(member.metric().id(), JevMetricResult.error("REDUCE_ERROR"));
            }
        }
    }

    private List<Group> plan(JevTrace trace, Map<String, JevMetricResult> done) {
        List<Group> groups = new ArrayList<>();
        for (var metric : metrics) {
            if (!trace.issues().isEmpty()) {
                done.put(metric.id(), JevMetricResult.skipped("INCOMPLETE_TRACE"));
                continue;
            }
            try {
                var pre = metric.precheck(trace);
                if (pre != null) {
                    done.put(metric.id(), pre);
                    continue;
                }
                var state = TraceData.snapshot(metric.state(trace));
                Map<String, Question> questions = new LinkedHashMap<>(metric.questions(trace));
                questions.keySet().forEach(JevTraceEvaluator::requireId);
                if (questions.size() > limits.maxQuestions()
                        || TraceData.size(state) > limits.maxStateChars()) {
                    done.put(metric.id(), JevMetricResult.skipped("EVALUATION_LIMIT"));
                    continue;
                }
                if (questions.isEmpty()) {
                    done.put(metric.id(), Objects.requireNonNull(metric.reduce(Map.of(), trace)));
                    continue;
                }
                JevClient.validateEvaluationRequest(
                        SystemOneRequest.builder().state(state).questions(questions).build());
                Group target = null;
                for (Group g : groups) {
                    if (g.questions.size() + questions.size() > limits.maxQuestions()) continue;
                    if (!state.entrySet().stream()
                            .allMatch(
                                    e ->
                                            !g.state.containsKey(e.getKey())
                                                    || Objects.equals(
                                                            g.state.get(e.getKey()), e.getValue())))
                        continue;
                    Map<String, Object> merged = new LinkedHashMap<>(g.state);
                    merged.putAll(state);
                    if (TraceData.size(merged) <= limits.maxStateChars()) {
                        target = g;
                        break;
                    }
                }
                if (target == null) {
                    target = new Group();
                    groups.add(target);
                }
                target.state.putAll(state);
                for (var q : questions.entrySet())
                    target.questions.put(metric.id() + "." + q.getKey(), q.getValue());
                target.members.add(new Member(metric, questions));
            } catch (RuntimeException e) {
                done.put(metric.id(), JevMetricResult.error("DEFINITION_ERROR"));
            }
        }
        return groups;
    }

    private static void requireId(String id) {
        if (id == null || !id.matches("[A-Za-z][A-Za-z0-9_]{0,63}"))
            throw new IllegalArgumentException(
                    "metric and question IDs must be simple identifiers");
    }

    private static final class InvalidResponse extends RuntimeException {}
}
