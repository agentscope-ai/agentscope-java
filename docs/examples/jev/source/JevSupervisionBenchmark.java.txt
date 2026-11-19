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
import io.agentscope.extensions.judge.jev.evaluation.JevTextBackend;
import io.agentscope.extensions.judge.jev.supervision.JevTaskSupervisor;
import io.agentscope.extensions.judge.jev.supervision.SupervisionEvidence;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/** Fixed synthetic observations; gold check/advice labels are never included in model state. */
public final class JevSupervisionBenchmark {
    private static final ObjectMapper JSON = new ObjectMapper();

    private JevSupervisionBenchmark() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 5)
            throw new IllegalArgumentException(
                    "--baseline DATA OUT; --live-jev DATA OUT MODEL; --live-qwen DATA OUT MODEL"
                            + " ENDPOINT");
        String mode = args[0];
        if (!Set.of("--baseline", "--live-jev", "--live-qwen").contains(mode)
                || args.length
                        != (mode.equals("--baseline") ? 3 : mode.equals("--live-jev") ? 4 : 5))
            throw new IllegalArgumentException("explicit benchmark backend required");
        Path data = Path.of(args[1]), out = Path.of(args[2]);
        if (Files.exists(out)) throw new IllegalArgumentException("output exists");
        String model = args.length > 3 ? args[3] : "none";
        Function<SystemOneRequest, Mono<SystemOneResult>> caller =
                mode.equals("--baseline")
                        ? r -> Mono.error(new AssertionError("baseline must not call a model"))
                        : mode.equals("--live-jev")
                                ? JevClient.builder()
                                                .apiKey(System.getenv("TYPESAFE_API_KEY"))
                                                .model(model)
                                                .timeout(Duration.ofSeconds(60))
                                                .retryPolicy(
                                                        new JevRetryPolicy(0, Duration.ofMillis(1)))
                                                .build()
                                        ::systemOne
                                : JevTraceBenchmark.qwenBackend(model, URI.create(args[4]));
        AtomicInteger requests = new AtomicInteger(), known = new AtomicInteger();
        AtomicLong input = new AtomicLong(), output = new AtomicLong();
        List<String> actualModels = new ArrayList<>();
        Function<SystemOneRequest, Mono<SystemOneResult>> measured =
                r ->
                        Mono.defer(
                                        () -> {
                                            requests.incrementAndGet();
                                            return caller.apply(r);
                                        })
                                .doOnNext(
                                        reply -> {
                                            actualModels.add(reply.model());
                                            if (reply.usage() != null) {
                                                known.incrementAndGet();
                                                input.addAndGet(reply.usage().inputTokens());
                                                output.addAndGet(reply.usage().outputTokens());
                                            }
                                        })
                                .doOnError(
                                        JevTextBackend.InvalidReply.class,
                                        error -> {
                                            if (error.usage() != null) {
                                                known.incrementAndGet();
                                                input.addAndGet(error.usage().inputTokens());
                                                output.addAndGet(error.usage().outputTokens());
                                            }
                                        });
        var supervisor =
                new JevTaskSupervisor(
                        measured,
                        new JevTaskSupervisor.Thresholds(.2, .8),
                        JevTaskSupervisor.Limits.defaults(),
                        new JevExecution.Options(
                                JevExecution.Mode.SHADOW,
                                Duration.ofSeconds(60),
                                "supervision-synthetic-v1",
                                (ctx, record) -> {}));
        List<Object> rows = new ArrayList<>();
        List<Double> times = new ArrayList<>();
        int labelled = 0, correct = 0, wrong = 0, abstained = 0, errors = 0, adviceCorrect = 0;
        var lines = Files.readAllLines(data);
        if (lines.isEmpty() || lines.size() > 100)
            throw new IllegalArgumentException("fixture limit");
        for (String line : lines) {
            if (line.isBlank()) continue;
            var fixture = JSON.readTree(line);
            var observation = snapshot(fixture);
            long start = System.nanoTime();
            var decision =
                    mode.equals("--baseline")
                            ? null
                            : supervisor.observe(null, () -> Mono.just(observation)).block();
            double millis = (System.nanoTime() - start) / 1_000_000d;
            times.add(millis);
            var report = decision == null ? null : decision.value();
            var row = new LinkedHashMap<String, Object>();
            row.put("id", fixture.path("id").asText());
            row.put("status", decision == null ? "BASELINE" : decision.status().name());
            row.put("reason", decision == null ? "NO_SUPERVISION" : decision.reason());
            row.put("elapsedMillis", millis);
            var comparisons = new LinkedHashMap<String, Object>();
            var gold = fixture.path("gold").fields();
            while (gold.hasNext()) {
                var entry = gold.next();
                labelled++;
                var finding = report == null ? null : report.findings().get(entry.getKey());
                String actual = finding == null ? "NO_JUDGMENT" : finding.truth().name();
                if (finding == null
                        && decision != null
                        && decision.status() == JevExecution.Status.ERROR) errors++;
                else if (finding == null || finding.truth() == JevTaskSupervisor.Truth.UNKNOWN)
                    abstained++;
                else if (actual.equals(entry.getValue().asText())) correct++;
                else wrong++;
                comparisons.put(
                        entry.getKey(),
                        Map.of("expected", entry.getValue().asText(), "actual", actual));
            }
            String advice =
                    decision == null
                            ? "CONTINUE"
                            : report == null ? "MANUAL_REVIEW" : report.selected().advice().name();
            if (advice.equals(fixture.path("expectedAdvice").asText())) adviceCorrect++;
            row.put("checks", comparisons);
            row.put("expectedAdvice", fixture.path("expectedAdvice").asText());
            row.put("advice", advice);
            row.put("findings", report == null ? null : report.findings());
            row.put("proposals", report == null ? null : report.proposals());
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
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("suite", "supervision-synthetic-v1");
        report.put("sourceCommit", "ba91849e7088f072db491c739e4c81fd9a4c8154");
        report.put("timestamp", Instant.now().toString());
        report.put(
                "dataSha256",
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(Files.readAllBytes(data))));
        report.put("backend", mode);
        report.put("requestedModel", model);
        report.put("actualModels", actualModels.stream().distinct().toList());
        report.put("enableThinking", false);
        report.put("mode", "SHADOW");
        report.put("thresholds", Map.of("no", .2, "yes", .8));
        report.put("labelledChecks", labelled);
        report.put("correct", correct);
        report.put("incorrect", wrong);
        report.put("abstained", abstained);
        report.put("errors", errors);
        report.put("accuracy", labelled == 0 ? 0 : (double) correct / labelled);
        report.put("adviceCorrect", adviceCorrect);
        report.put("cases", rows.size());
        report.put(
                "latency",
                Map.of(
                        "p50Millis",
                        times.get((int) Math.ceil(times.size() * .5) - 1),
                        "p95Millis",
                        times.get((int) Math.ceil(times.size() * .95) - 1)));
        report.put(
                "usage",
                Map.of(
                        "requests",
                        requests.get(),
                        "responsesWithUsage",
                        known.get(),
                        "inputTokens",
                        input.get(),
                        "outputTokens",
                        output.get()));
        report.put("rows", rows);
        Files.writeString(
                out,
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW);
        System.out.println("Saved benchmark report");
    }

    /** Strictly select evidence fields; never pass gold/expectedAdvice to the evaluator. */
    public static JevTaskSupervisor.Snapshot snapshot(JsonNode fixture) {
        String id = fixture.path("id").asText();
        var scope = new JevTaskSupervisor.Scope("benchmark", id, "benchmark-" + id);
        var e = fixture.path("evidence");
        SupervisionEvidence.Verification verification = null;
        if (e.path("verification").isObject()) {
            var v = e.path("verification");
            verification =
                    new SupervisionEvidence.Verification(
                            scope.userId(),
                            scope.sessionId(),
                            scope.runId(),
                            v.path("revision").asText(),
                            "synthetic-host-verifier",
                            v.path("passed").asBoolean(),
                            v.path("summary").asText());
        }
        List<String> files = new ArrayList<>();
        e.path("changedFiles").forEach(f -> files.add(f.asText()));
        List<String> events = new ArrayList<>();
        fixture.path("events").forEach(f -> events.add(f.asText()));
        return new JevTaskSupervisor.Snapshot(
                scope,
                1,
                fixture.path("task").asText(),
                fixture.path("output").asText(),
                events,
                fixture.path("active").asBoolean(),
                fixture.path("failed").asBoolean(),
                false,
                fixture.path("elapsedMillis").asLong(1000),
                new SupervisionEvidence(
                        e.path("revision").asText(),
                        e.path("status").asText(),
                        e.path("diff").asText(),
                        files,
                        e.path("instructions").asText(),
                        e.path("documentationRequired").asBoolean(),
                        verification));
    }
}
