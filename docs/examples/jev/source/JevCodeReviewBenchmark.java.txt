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
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.JevRetryPolicy;
import io.agentscope.extensions.judge.jev.SystemOneRequest;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import io.agentscope.extensions.judge.jev.review.JevCodeReviewer;
import io.agentscope.extensions.judge.jev.review.JevReviewInput;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/** Synthetic evidence benchmark, not a static analyzer or a calibrated merge gate. */
public final class JevCodeReviewBenchmark {
    private static final ObjectMapper JSON = new ObjectMapper();

    private JevCodeReviewBenchmark() {}

    /** Gold fields cannot enter review state: only this typed input is submitted. */
    public static JevReviewInput input(JsonNode fixture) {
        return JSON.convertValue(fixture.required("input"), JevReviewInput.class);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 5)
            throw new IllegalArgumentException(
                    "--baseline DATA OUT; --live-jev DATA OUT MODEL; --live-qwen DATA OUT MODEL"
                            + " ENDPOINT");
        String backend = args[0];
        if (!Set.of("--baseline", "--live-jev", "--live-qwen").contains(backend)
                || args.length
                        != (backend.equals("--baseline")
                                ? 3
                                : backend.equals("--live-jev") ? 4 : 5))
            throw new IllegalArgumentException("explicit benchmark backend required");
        Path data = Path.of(args[1]), out = Path.of(args[2]);
        if (Files.exists(out)) throw new IllegalArgumentException("output exists");
        String model = args.length > 3 ? args[3] : "none";
        Function<SystemOneRequest, Mono<SystemOneResult>> caller =
                backend.equals("--baseline")
                        ? r -> Mono.error(new AssertionError("baseline must not call a model"))
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
        var config = JevCodeReviewer.Config.referencePolicy();
        var reviewer =
                new JevCodeReviewer(
                        caller,
                        config,
                        new JevExecution.Options(
                                backend.equals("--baseline")
                                        ? JevExecution.Mode.OFF
                                        : JevExecution.Mode.SHADOW,
                                Duration.ofSeconds(120),
                                "review-synthetic-v1",
                                (ctx, record) -> {}));
        var rows = new ArrayList<Object>();
        var times = new ArrayList<Double>();
        var calls = new ArrayList<JevCodeReviewer.Call>();
        int labelled = 0, correct = 0, incorrect = 0, abstained = 0, errors = 0;
        int expected = 0, located = 0, mechanismMatched = 0, generated = 0, complete = 0;
        var lines = Files.readAllLines(data);
        if (lines.isEmpty() || lines.size() > 100)
            throw new IllegalArgumentException("fixture limit");
        for (String line : lines) {
            if (line.isBlank()) continue;
            JsonNode fixture = JSON.readTree(line);
            JevReviewInput evidence = input(fixture);
            long start = System.nanoTime();
            var decision = reviewer.review(null, evidence).block();
            double millis = (System.nanoTime() - start) / 1_000_000d;
            times.add(millis);
            var report = decision.value();
            if (report != null) {
                calls.addAll(report.calls());
                generated += report.findings().size();
                if (report.complete()) complete++;
            }
            var checks = new LinkedHashMap<String, Object>();
            var files = fixture.required("goldScreen").fields();
            while (files.hasNext()) {
                var file = files.next();
                var screen =
                        report == null
                                ? null
                                : report.matrix().stream()
                                        .filter(s -> s.path().equals(file.getKey()))
                                        .findFirst()
                                        .orElse(null);
                var gold = file.getValue().fields();
                while (gold.hasNext()) {
                    var label = gold.next();
                    labelled++;
                    Double probability =
                            screen == null ? null : screen.probabilities().get(label.getKey());
                    String actual;
                    if (probability == null
                            && (decision.status() == JevExecution.Status.ERROR
                                    || screen != null && screen.status().equals("ERROR"))) {
                        actual = "ERROR";
                        errors++;
                    } else if (probability == null
                            || probability > config.lowRiskThreshold()
                                    && probability < config.screenThreshold()) {
                        actual = "ABSTAIN";
                        abstained++;
                    } else {
                        boolean yes = probability >= config.screenThreshold();
                        actual = yes ? "YES" : "NO";
                        if (yes == label.getValue().asBoolean()) correct++;
                        else incorrect++;
                    }
                    var c = new LinkedHashMap<String, Object>();
                    c.put("expected", label.getValue().asBoolean() ? "YES" : "NO");
                    c.put("actual", actual);
                    c.put("probability", probability);
                    checks.put(file.getKey() + ":" + label.getKey(), c);
                }
            }
            var matches = new ArrayList<Object>();
            for (var gold : fixture.required("expectedFindings")) {
                expected++;
                var candidates =
                        report == null
                                ? List.<JevCodeReviewer.Finding>of()
                                : report.findings().stream()
                                        .filter(
                                                f ->
                                                        f.signal()
                                                                        .path()
                                                                        .equals(
                                                                                gold.path("path")
                                                                                        .asText())
                                                                && f.signal()
                                                                        .dimension()
                                                                        .equals(
                                                                                gold.path(
                                                                                                "dimension")
                                                                                        .asText()))
                                        .toList();
                boolean location =
                        candidates.stream()
                                .anyMatch(
                                        f ->
                                                f.evidence()
                                                                .id()
                                                                .equals(
                                                                        gold.path("region")
                                                                                .asText())
                                                        && f.evidence()
                                                                .side()
                                                                .name()
                                                                .equals(
                                                                        gold.path("side")
                                                                                .asText()));
                boolean mechanism =
                        candidates.stream()
                                .anyMatch(
                                        f ->
                                                f.evidence()
                                                                .id()
                                                                .equals(
                                                                        gold.path("region")
                                                                                .asText())
                                                        && f.evidence()
                                                                .side()
                                                                .name()
                                                                .equals(gold.path("side").asText())
                                                        && f.mechanism()
                                                                .equals(
                                                                        gold.path("mechanism")
                                                                                .asText()));
                if (location) located++;
                if (mechanism) mechanismMatched++;
                matches.add(
                        Map.of(
                                "expected",
                                gold,
                                "locationMatched",
                                location,
                                "mechanismMatched",
                                mechanism));
            }
            var row = new LinkedHashMap<String, Object>();
            row.put("id", fixture.path("id").asText());
            row.put("status", decision.status());
            row.put("reason", decision.reason());
            row.put("elapsedMillis", millis);
            row.put("screenComparisons", checks);
            row.put("findingComparisons", matches);
            row.put("report", report);
            rows.add(row);
            System.out.println(
                    "Completed "
                            + fixture.path("id").asText()
                            + " in "
                            + Math.round(millis)
                            + " ms");
        }
        if (times.isEmpty()) throw new IllegalArgumentException("empty fixtures");
        times.sort(Double::compare);
        var known =
                calls.stream()
                        .filter(
                                c ->
                                        c.usage() != null
                                                && c.usage().inputTokens() >= 0
                                                && c.usage().outputTokens() >= 0)
                        .toList();
        var result = new LinkedHashMap<String, Object>();
        result.put("suite", "review-synthetic-v1");
        result.put("timestamp", Instant.now().toString());
        result.put("sourceCommit", "31f89602797fb7bea007f8a480bf368bf564954e");
        result.put(
                "dataSha256",
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(Files.readAllBytes(data))));
        result.put("backend", backend);
        result.put("requestedModel", model);
        result.put(
                "actualModels",
                calls.stream()
                        .map(JevCodeReviewer.Call::model)
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .toList());
        result.put("enableThinking", false);
        result.put("config", config);
        result.put("totalBudgetMillis", 120000);
        result.put("cases", rows.size());
        result.put("completeReports", complete);
        result.put(
                "screening",
                Map.of(
                        "labels",
                        labelled,
                        "correct",
                        correct,
                        "incorrect",
                        incorrect,
                        "abstained",
                        abstained,
                        "errors",
                        errors,
                        "accuracy",
                        labelled == 0 ? 0 : (double) correct / labelled));
        result.put(
                "findings",
                Map.of(
                        "expected",
                        expected,
                        "located",
                        located,
                        "mechanismMatched",
                        mechanismMatched,
                        "generated",
                        generated,
                        "note",
                        "Unlabelled findings are not counted as false positives; severity and"
                                + " routing are reported without gold claims."));
        result.put(
                "latency",
                Map.of(
                        "p50Millis",
                        times.get((int) Math.ceil(times.size() * .5) - 1),
                        "p95Millis",
                        times.get((int) Math.ceil(times.size() * .95) - 1)));
        result.put(
                "usage",
                Map.of(
                        "requests",
                        calls.size(),
                        "responsesWithUsage",
                        known.size(),
                        "inputTokens",
                        known.stream().mapToLong(c -> c.usage().inputTokens()).sum(),
                        "outputTokens",
                        known.stream().mapToLong(c -> c.usage().outputTokens()).sum()));
        result.put("rows", rows);
        Files.writeString(
                out,
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n",
                StandardOpenOption.CREATE_NEW);
        System.out.println("Saved benchmark report");
    }
}
