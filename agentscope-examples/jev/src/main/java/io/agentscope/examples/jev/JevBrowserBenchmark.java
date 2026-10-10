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

package io.agentscope.examples.jev;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.JevException;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.JevRetryPolicy;
import io.agentscope.extensions.judge.jev.SystemOneRequest;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import io.agentscope.extensions.judge.jev.Usage;
import io.agentscope.extensions.judge.jev.browser.JevBrowserNavigator;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Action;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Link;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Permit;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Receipt;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Scope;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Snapshot;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Source;
import io.agentscope.extensions.judge.jev.evaluation.JevTextBackend;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/** Fixed visible states only. No live website, browser action, generated answer or successful-task claim. */
public final class JevBrowserBenchmark {
    private JevBrowserBenchmark() {}

    static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 5)
            throw new IllegalArgumentException(
                    "--baseline DATA OUT; --live-jev DATA OUT MODEL; --live-qwen DATA OUT MODEL"
                            + " ENDPOINT");
        String backend = args[0];
        boolean baseline = backend.equals("--baseline");
        if (!Set.of("--baseline", "--live-jev", "--live-qwen").contains(backend)
                || args.length != (baseline ? 3 : backend.equals("--live-jev") ? 4 : 5))
            throw new IllegalArgumentException("explicit backend required");
        Path data = Path.of(args[1]), out = Path.of(args[2]);
        if (Files.exists(out)) throw new IllegalArgumentException("output exists");
        String model = baseline ? "none" : args[3];
        Function<SystemOneRequest, Mono<SystemOneResult>> caller =
                baseline
                        ? r -> Mono.error(new AssertionError("baseline must not ask"))
                        : backend.equals("--live-jev")
                                ? JevClient.builder()
                                                .apiKey(System.getenv("TYPESAFE_API_KEY"))
                                                .model(model)
                                                .timeout(Duration.ofSeconds(60))
                                                .retryPolicy(
                                                        new JevRetryPolicy(0, Duration.ofMillis(1)))
                                                .build()
                                        ::systemOne
                                : JevTraceBenchmark.qwenBackend(model, URI.create(args[4]));
        var usage = new CopyOnWriteArrayList<Usage>();
        var models = new CopyOnWriteArrayList<String>();
        var failures = new ConcurrentHashMap<String, Integer>();
        var calls = new AtomicInteger();
        Function<SystemOneRequest, Mono<SystemOneResult>> measured =
                r ->
                        Mono.defer(
                                        () -> {
                                            calls.incrementAndGet();
                                            return caller.apply(r);
                                        })
                                .doOnNext(
                                        reply -> {
                                            models.add(reply.model());
                                            if (reply.usage() != null) usage.add(reply.usage());
                                        })
                                .doOnError(
                                        e -> {
                                            failures.merge(
                                                    e instanceof JevException j
                                                                    && j.getStatusCode() != null
                                                            ? "HTTP_" + j.getStatusCode()
                                                            : e.getClass().getSimpleName(),
                                                    1,
                                                    Integer::sum);
                                            if (e instanceof JevTextBackend.InvalidReply invalid) {
                                                models.add(invalid.model());
                                                if (invalid.usage() != null)
                                                    usage.add(invalid.usage());
                                            }
                                        });
        var results = new ArrayList<Object>();
        var times = new ArrayList<Double>();
        int correct = 0, wrong = 0, abstain = 0, error = 0;
        for (String line : Files.readAllLines(data)) {
            if (line.isBlank()) continue;
            var row = JSON.readTree(line);
            String id = row.path("id").asText();
            var scope = new Scope("benchmark", id, "fixed");
            var links = new ArrayList<Link>();
            for (JsonNode link : row.path("links"))
                links.add(
                        new Link(
                                link.path("id").asText(),
                                link.path("label").asText(),
                                link.path("href").asText()));
            var page =
                    new Snapshot(
                            scope,
                            "fixed-v1",
                            "https://fixture.test/",
                            "Read-only fixture",
                            row.path("text").asText(),
                            links,
                            row.path("scrollUp").asBoolean(),
                            row.path("scrollDown").asBoolean(),
                            0);
            var source =
                    new Source(
                            req ->
                                    new JevBrowserSession() {
                                        public Scope scope() {
                                            return scope;
                                        }

                                        public Mono<Snapshot> observe() {
                                            return Mono.just(page);
                                        }

                                        public Mono<Receipt> execute(
                                                Permit p, Snapshot s, Action a) {
                                            return Mono.error(
                                                    new AssertionError(
                                                            "SHADOW must never execute"));
                                        }

                                        public Mono<Void> close() {
                                            return Mono.empty();
                                        }
                                    },
                            (g, p) -> Mono.error(new AssertionError("SHADOW must not verify")));
            var nav =
                    new JevBrowserNavigator(
                            measured,
                            .8,
                            JevBrowserNavigator.Limits.defaults(),
                            new JevExecution.Options(
                                    baseline ? JevExecution.Mode.OFF : JevExecution.Mode.SHADOW,
                                    Duration.ofSeconds(60),
                                    "browser-fixed-v2",
                                    (c, r) -> {}));
            var context = RuntimeContext.builder().userId("benchmark").sessionId(id).build();
            long started = System.nanoTime();
            var report =
                    nav.navigate(context, row.path("goal").asText(), source)
                            .block(Duration.ofSeconds(65));
            double ms = (System.nanoTime() - started) / 1e6;
            times.add(ms);
            var proposal = report.steps().isEmpty() ? null : report.steps().get(0).proposal();
            var action = proposal == null ? null : proposal.action();
            String outcome;
            if (action != null) {
                boolean matched =
                        action.operation().name().equals(row.path("expectedOperation").asText())
                                && Objects.equals(
                                        action.target(),
                                        row.path("expectedTarget").isNull()
                                                ? null
                                                : row.path("expectedTarget").asText());
                if (matched) {
                    correct++;
                    outcome = "CORRECT";
                } else {
                    wrong++;
                    outcome = "WRONG";
                }
            } else if (baseline || proposal != null) {
                abstain++;
                outcome = "ABSTAIN";
            } else {
                error++;
                outcome = "ERROR";
            }
            var item = new LinkedHashMap<String, Object>();
            item.put("id", id);
            item.put("expectedOperation", row.path("expectedOperation").asText());
            item.put(
                    "expectedTarget",
                    row.path("expectedTarget").isNull()
                            ? null
                            : row.path("expectedTarget").asText());
            item.put("outcome", outcome);
            item.put("elapsedMillis", ms);
            item.put("report", report);
            results.add(item);
            System.out.println("Completed " + id + " " + outcome);
        }
        var report = new LinkedHashMap<String, Object>();
        report.put("backend", backend);
        report.put("requestedModel", model);
        report.put("actualModels", models.stream().distinct().toList());
        report.put("timestamp", Instant.now().toString());
        report.put("fixtureSha256", JevBrowserSession.digest(Files.readString(data)));
        report.put("threshold", .8);
        report.put("stateFormat", "visible-links-v2");
        report.put(
                "scope",
                "fixed visible states; SHADOW actions only; no browser execution or generated"
                        + " answer");
        report.put(
                "labels",
                Map.of(
                        "total",
                        results.size(),
                        "correct",
                        correct,
                        "wrong",
                        wrong,
                        "abstain",
                        abstain,
                        "error",
                        error));
        report.put("requests", calls.get());
        report.put("responsesWithUsage", usage.size());
        report.put("requestFailureKinds", failures);
        report.put("inputTokens", usage.stream().mapToLong(Usage::inputTokens).sum());
        report.put("outputTokens", usage.stream().mapToLong(Usage::outputTokens).sum());
        report.put("p50Millis", percentile(times, .5));
        report.put("p95Millis", percentile(times, .95));
        report.put("cases", results);
        Files.writeString(
                out,
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW);
        System.out.println("Saved " + out);
    }

    static double percentile(List<Double> values, double p) {
        var sorted = values.stream().sorted().toList();
        return sorted.get(Math.max(0, (int) Math.ceil(p * sorted.size()) - 1));
    }
}
