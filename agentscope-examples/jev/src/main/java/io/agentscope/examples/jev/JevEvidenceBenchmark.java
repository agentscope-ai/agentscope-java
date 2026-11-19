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
import io.agentscope.extensions.judge.jev.JevException;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.JevJudge;
import io.agentscope.extensions.judge.jev.JevRetryPolicy;
import io.agentscope.extensions.judge.jev.SystemOneRequest;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import io.agentscope.extensions.judge.jev.Usage;
import io.agentscope.extensions.judge.jev.evaluation.Evaluator;
import io.agentscope.extensions.judge.jev.evaluation.JevEvaluator;
import io.agentscope.extensions.judge.jev.evaluation.JevTextBackend;
import io.agentscope.extensions.judge.jev.evidence.JevEvidenceProcessor;
import io.agentscope.extensions.judge.jev.evidence.JevPassage;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/** Separate retrieval and fixed-draft quality measurements; no generated-answer accuracy claim. */
public final class JevEvidenceBenchmark {
    private static final ObjectMapper JSON = new ObjectMapper();

    private JevEvidenceBenchmark() {}

    public static List<JevPassage> passages(JsonNode fixture) {
        var passages = new ArrayList<JevPassage>();
        for (var p : fixture.required("passages"))
            passages.add(
                    new JevPassage(
                            p.required("id").asText(),
                            p.required("text").asText(),
                            p.required("version").asText()));
        return List.copyOf(passages);
    }

    static List<String> strings(JsonNode values) {
        var r = new ArrayList<String>();
        values.forEach(v -> r.add(v.asText()));
        return r;
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
        boolean baseline = backend.equals("--baseline");
        Path data = Path.of(args[1]), out = Path.of(args[2]);
        if (Files.exists(out)) throw new IllegalArgumentException("output exists");
        String model = args.length > 3 ? args[3] : "none";
        Function<SystemOneRequest, Mono<SystemOneResult>> caller =
                baseline
                        ? r -> Mono.error(new AssertionError("baseline must not call model"))
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
        var failures = new java.util.concurrent.ConcurrentHashMap<String, Integer>();
        var usages = new CopyOnWriteArrayList<Usage>();
        var actualModels = new CopyOnWriteArrayList<String>();
        AtomicInteger calls = new AtomicInteger(), callErrors = new AtomicInteger();
        Function<SystemOneRequest, Mono<SystemOneResult>> measured =
                r ->
                        Mono.defer(
                                        () -> {
                                            calls.incrementAndGet();
                                            return caller.apply(r);
                                        })
                                .doOnNext(
                                        reply -> {
                                            actualModels.add(reply.model());
                                            if (reply.usage() != null) usages.add(reply.usage());
                                        })
                                .doOnError(
                                        error -> {
                                            callErrors.incrementAndGet();
                                            String kind =
                                                    error instanceof JevException jev
                                                                    && jev.getStatusCode() != null
                                                            ? "HTTP_" + jev.getStatusCode()
                                                            : error.getClass().getSimpleName();
                                            failures.merge(kind, 1, Integer::sum);
                                            if (error
                                                    instanceof
                                                    JevTextBackend.InvalidReply invalid) {
                                                actualModels.add(invalid.model());
                                                if (invalid.usage() != null)
                                                    usages.add(invalid.usage());
                                            }
                                        });
        var options =
                new JevExecution.Options(
                        baseline ? JevExecution.Mode.OFF : JevExecution.Mode.SHADOW,
                        Duration.ofSeconds(60),
                        "evidence-synthetic-v1",
                        (c, r) -> {});
        var policy = JevEvidenceProcessor.Policy.demonstration();
        var processor =
                new JevEvidenceProcessor(
                        measured, policy, JevEvidenceProcessor.Limits.defaults(), 0, options);
        var evaluator =
                new JevEvaluator(
                        new JevJudge(measured, Duration.ofSeconds(60)),
                        JevEvidenceExample.definition());
        var rows = new ArrayList<Object>();
        var times = new ArrayList<Double>();
        var retrievalTimes = new ArrayList<Double>();
        var answerTimes = new ArrayList<Double>();
        int labels = 0,
                correct = 0,
                incorrect = 0,
                abstained = 0,
                errors = 0,
                expectedRelevant = 0,
                selected = 0,
                hits = 0;
        int answerLabels = 0,
                answerCorrect = 0,
                answerIncorrect = 0,
                answerAbstained = 0,
                answerErrors = 0;
        var lines = Files.readAllLines(data);
        if (lines.isEmpty() || lines.size() > 100)
            throw new IllegalArgumentException("fixture limit");
        for (String line : lines) {
            if (line.isBlank()) continue;
            var fixture = JSON.readTree(line);
            var candidates = passages(fixture);
            var denied = strings(fixture.required("deniedIds"));
            var relevant = strings(fixture.required("goldRelevantIds"));
            String query = fixture.required("question").asText();
            long start = System.nanoTime();
            var decision =
                    processor
                            .process(
                                    null,
                                    query,
                                    candidates,
                                    (ctx, p) -> !denied.contains(p.id()),
                                    fixture.path("topK").asInt())
                            .block();
            double retrievalMillis = (System.nanoTime() - start) / 1_000_000d;
            retrievalTimes.add(retrievalMillis);
            var report = decision.value();
            List<String> delivered =
                    report == null
                            ? List.of()
                            : baseline
                                    ? report.delivered().stream().map(JevPassage::id).toList()
                                    : report.suggested().stream()
                                            .map(a -> a.passage().id())
                                            .toList();
            expectedRelevant += relevant.size();
            selected += delivered.size();
            hits += (int) delivered.stream().filter(relevant::contains).count();
            var comparisons = new LinkedHashMap<String, Object>();
            var gold = fixture.required("goldClassifications").fields();
            while (gold.hasNext()) {
                var entry = gold.next();
                labels++;
                var assessment =
                        report == null
                                ? null
                                : report.assessments().stream()
                                        .filter(a -> a.passage().id().equals(entry.getKey()))
                                        .findFirst()
                                        .orElse(null);
                String actual = assessment == null ? "ERROR" : assessment.classification().name();
                if (actual.equals("ERROR")) errors++;
                else if (actual.equals("INCONCLUSIVE") || actual.equals("UNSCREENED")) abstained++;
                else if (actual.equals(entry.getValue().asText())) correct++;
                else incorrect++;
                comparisons.put(
                        entry.getKey(),
                        Map.of("expected", entry.getValue().asText(), "actual", actual));
            }
            // Fixed trusted context is identical across backends, independently of their retrieval
            // choices.
            var supportingIds = strings(fixture.required("supportingIds"));
            if (supportingIds.stream().anyMatch(denied::contains))
                throw new IllegalArgumentException("gold context violates ACL");
            var context =
                    candidates.stream()
                            .filter(p -> supportingIds.contains(p.id()))
                            .map(JevPassage::text)
                            .toList();
            long answerStart = System.nanoTime();
            var evaluation =
                    baseline
                            ? null
                            : evaluator
                                    .evaluate(
                                            new Evaluator.Request(
                                                    fixture.path("id").asText(),
                                                    query,
                                                    context,
                                                    fixture.required("answer").asText()))
                                    .block();
            double answerMillis = (System.nanoTime() - answerStart) / 1_000_000d;
            answerTimes.add(answerMillis);
            var answerChecks = new LinkedHashMap<String, Object>();
            var answerGold = fixture.required("goldAnswer").fields();
            while (answerGold.hasNext()) {
                var entry = answerGold.next();
                answerLabels++;
                var finding =
                        evaluation == null
                                ? null
                                : evaluation.verdict().findings().get(entry.getKey());
                String actual =
                        finding == null
                                ? (evaluation != null
                                                && evaluation.verdict().status()
                                                        == JevJudge.Status.ERROR
                                        ? "ERROR"
                                        : "INCONCLUSIVE")
                                : finding.status().name();
                String expected = entry.getValue().asBoolean() ? "PASS" : "FAIL";
                if (actual.equals("ERROR")) answerErrors++;
                else if (actual.equals("INCONCLUSIVE")) answerAbstained++;
                else if (actual.equals(expected)) answerCorrect++;
                else answerIncorrect++;
                answerChecks.put(entry.getKey(), Map.of("expected", expected, "actual", actual));
            }
            double millis = (System.nanoTime() - start) / 1_000_000d;
            times.add(millis);
            var row = new LinkedHashMap<String, Object>();
            row.put("id", fixture.path("id").asText());
            row.put("status", decision.status());
            row.put("elapsedMillis", millis);
            row.put("retrievalMillis", retrievalMillis);
            row.put("answerJudgmentMillis", answerMillis);
            row.put("classificationComparisons", comparisons);
            row.put("expectedRelevantIds", relevant);
            row.put("evaluatedSelectionIds", delivered);
            row.put("selectionApplied", false);
            row.put("retrievalReport", report);
            row.put("answerComparisons", answerChecks);
            if (evaluation != null) {
                var v = evaluation.verdict();
                var answerResult = new LinkedHashMap<String, Object>();
                answerResult.put("status", v.status());
                answerResult.put("error", v.error());
                answerResult.put("findings", v.findings());
                answerResult.put("model", v.model());
                answerResult.put("usage", v.usage());
                row.put("answerResult", answerResult);
            }
            rows.add(row);
            System.out.println(
                    "Completed "
                            + fixture.path("id").asText()
                            + " in "
                            + Math.round(millis)
                            + " ms");
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("empty fixture");
        var known =
                usages.stream().filter(u -> u.inputTokens() >= 0 && u.outputTokens() >= 0).toList();
        var result = new LinkedHashMap<String, Object>();
        result.put("suite", "evidence-synthetic-v1");
        result.put("timestamp", Instant.now().toString());
        result.put("sourceCommit", "5c469ce10ee45175f56a8f5882cc93d29f72f591");
        result.put(
                "dataSha256",
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(Files.readAllBytes(data))));
        result.put("backend", backend);
        result.put("requestedModel", model);
        result.put("actualModels", actualModels.stream().distinct().toList());
        result.put("requestFailureKinds", Map.copyOf(failures));
        result.put("enableThinking", false);
        result.put("mode", baseline ? "OFF" : "SHADOW");
        result.put("policy", policy);
        result.put("cases", rows.size());
        result.put(
                "classification",
                Map.of(
                        "labels",
                        labels,
                        "correct",
                        correct,
                        "incorrect",
                        incorrect,
                        "abstained",
                        abstained,
                        "errors",
                        errors));
        result.put(
                "retrieval",
                Map.of(
                        "relevant",
                        expectedRelevant,
                        "selected",
                        selected,
                        "hits",
                        hits,
                        "precision",
                        selected == 0 ? 0 : (double) hits / selected,
                        "recall",
                        expectedRelevant == 0 ? 0 : (double) hits / expectedRelevant));
        result.put(
                "answerQuality",
                Map.of(
                        "labels",
                        answerLabels,
                        "correct",
                        answerCorrect,
                        "incorrect",
                        answerIncorrect,
                        "abstained",
                        answerAbstained,
                        "errors",
                        answerErrors));
        result.put(
                "latency",
                Map.of(
                        "combined",
                        percentiles(times),
                        "retrieval",
                        percentiles(retrievalTimes),
                        "answerJudgment",
                        percentiles(answerTimes)));
        result.put(
                "usage",
                Map.of(
                        "requests",
                        calls.get(),
                        "requestErrors",
                        callErrors.get(),
                        "responsesWithUsage",
                        known.size(),
                        "inputTokens",
                        known.stream().mapToLong(Usage::inputTokens).sum(),
                        "outputTokens",
                        known.stream().mapToLong(Usage::outputTokens).sum()));
        result.put(
                "limitations",
                "Synthetic fixed drafts with fixed trusted answer context; not generated-answer"
                        + " end-to-end quality or production calibration.");
        result.put("rows", rows);
        Files.writeString(
                out,
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n",
                StandardOpenOption.CREATE_NEW);
        System.out.println("Saved benchmark report");
    }

    static Map<String, Double> percentiles(List<Double> values) {
        var ordered = values.stream().sorted().toList();
        return Map.of(
                "p50Millis",
                ordered.get((int) Math.ceil(ordered.size() * .5) - 1),
                "p95Millis",
                ordered.get((int) Math.ceil(ordered.size() * .95) - 1));
    }
}
