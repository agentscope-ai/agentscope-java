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
import io.agentscope.extensions.judge.jev.JevRetryPolicy;
import io.agentscope.extensions.judge.jev.SystemOneRequest;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import io.agentscope.extensions.judge.jev.evaluation.JevTextBackend;
import io.agentscope.extensions.judge.jev.evaluation.JevTrace;
import io.agentscope.extensions.judge.jev.evaluation.JevTraceEvaluator;
import io.agentscope.extensions.judge.jev.evaluation.JevTraceMetrics;
import io.agentscope.extensions.judge.jev.evaluation.JevTraceRunner;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import java.util.function.Function;
import reactor.core.publisher.Mono;

/** Explicit opt-in benchmark; no live calls in tests or when using --offline/--baseline. */
public final class JevTraceBenchmark {
    private static final ObjectMapper JSON = new ObjectMapper();

    private JevTraceBenchmark() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3 && args.length != 4 && args.length != 5)
            throw new IllegalArgumentException(
                    "usage: --offline|--baseline DATA OUTPUT; --live-jev DATA OUTPUT MODEL;"
                            + " --live-qwen DATA OUTPUT MODEL ENDPOINT");
        String mode = args[0];
        if (!List.of("--offline", "--baseline", "--live-jev", "--live-qwen").contains(mode))
            throw new IllegalArgumentException("explicit backend required");
        if ((mode.equals("--live-qwen") && args.length != 5)
                || (mode.equals("--live-jev") && args.length != 4))
            throw new IllegalArgumentException(
                    "live backend requires an explicit model and Qwen requires an endpoint");
        String model = args.length > 3 ? args[3] : "offline";
        Path data = Path.of(args[1]), output = Path.of(args[2]);
        if (Files.exists(output))
            throw new IllegalArgumentException("output already exists; select a new path");
        List<JevTraceRunner.Case> cases = load(data);
        Function<SystemOneRequest, Mono<SystemOneResult>> caller =
                JevTraceEvaluationExample::syntheticAnswers;
        if (mode.equals("--live-jev"))
            caller =
                    JevClient.builder()
                                    .apiKey(requiredEnv("TYPESAFE_API_KEY"))
                                    .model(model)
                                    .timeout(Duration.ofSeconds(60))
                                    .retryPolicy(new JevRetryPolicy(0, Duration.ofMillis(1)))
                                    .build()
                            ::systemOne;
        if (mode.equals("--live-qwen")) caller = qwenBackend(model, URI.create(args[4]));
        var metrics =
                new ArrayList<>(
                        JevTraceMetrics.agentMetrics(new JevTraceMetrics.Thresholds(.2, .8)));
        var match = JevTraceMetrics.trajectoryMatch(JevTraceMetrics.MatchMode.STRICT, false);
        if (mode.equals("--baseline")) {
            metrics.clear();
            cases =
                    cases.stream()
                            .map(
                                    c ->
                                            new JevTraceRunner.Case(
                                                    c.scenario(),
                                                    c.trace(),
                                                    Map.of(
                                                            "trajectory_match",
                                                            c.expected().get("trajectory_match"))))
                            .toList();
        }
        metrics.add(match);
        var evaluator =
                new JevTraceEvaluator(
                        caller,
                        metrics,
                        new JevTraceEvaluator.Limits(Duration.ofSeconds(60), 128, 100000));
        var report = new JevTraceRunner().run(evaluator, cases, 1).block();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("suite", "trace-synthetic-v1");
        result.put("sourceCommit", "e9fb26aff4a4410580776fb35deec85b4ad9c308");
        result.put(
                "dataSha256",
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(Files.readAllBytes(data))));
        result.put("timestamp", Instant.now().toString());
        result.put("backend", mode);
        result.put("requestedModel", model);
        result.put(
                "probabilities",
                mode.equals("--live-qwen")
                        ? "self-reported, uncalibrated"
                        : mode.equals("--live-jev")
                                ? "backend-reported"
                                : "synthetic or deterministic");
        if (mode.equals("--live-qwen")) result.put("enableThinking", false);
        result.put("thresholds", Map.of("fail", .2, "pass", .8));
        result.put("budgetMillis", 60000);
        result.put("metrics", report.metrics());
        result.put("scenarios", report.scenarios());
        result.put("latency", report.latency());
        result.put("usage", report.usage());
        result.put("wallMillis", report.elapsed().toMillis());
        result.put(
                "rows",
                report.rows().stream()
                        .map(
                                row -> {
                                    Map<String, Object> r = new LinkedHashMap<>();
                                    r.put("scenario", row.scenario());
                                    r.put("id", row.traceId());
                                    r.put("expected", row.expected());
                                    r.put("results", row.report().results());
                                    r.put("requests", row.report().requests());
                                    r.put("usage", row.report().usage());
                                    r.put(
                                            "elapsedMillis",
                                            row.report().elapsed().toNanos() / 1_000_000d);
                                    return r;
                                })
                        .toList());
        Files.writeString(
                output,
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n",
                StandardOpenOption.CREATE_NEW);
        System.out.println(
                "Saved "
                        + report.rows().size()
                        + " cases to "
                        + output
                        + "; requests="
                        + report.usage().requests());
    }

    @SuppressWarnings("unchecked")
    public static List<JevTraceRunner.Case> load(Path path) throws Exception {
        List<JevTraceRunner.Case> cases = new ArrayList<>();
        for (String line : Files.readAllLines(path)) {
            if (line.isBlank()) continue;
            JsonNode row = JSON.readTree(line);
            Map<String, JevTraceRunner.Expected> expected = new LinkedHashMap<>();
            row.path("expected")
                    .fields()
                    .forEachRemaining(
                            e ->
                                    expected.put(
                                            e.getKey(),
                                            new JevTraceRunner.Expected(
                                                    e.getValue().has("passed")
                                                            ? e.getValue()
                                                                    .get("passed")
                                                                    .booleanValue()
                                                            : null,
                                                    e.getValue().has("label")
                                                            ? e.getValue().get("label").textValue()
                                                            : null)));
            cases.add(
                    new JevTraceRunner.Case(
                            row.path("scenario").asText(),
                            new JevTrace(
                                    row.path("id").asText(),
                                    JSON.convertValue(row.get("fields"), Map.class)),
                            expected));
        }
        return List.copyOf(cases);
    }

    /** Explicit HTTP transport shared by benchmark examples; thinking is disabled. */
    public static Function<SystemOneRequest, Mono<SystemOneResult>> qwenBackend(
            String model, URI endpoint) {
        if (!"https".equals(endpoint.getScheme()) || endpoint.getUserInfo() != null)
            throw new IllegalArgumentException(
                    "HTTPS endpoint without embedded credentials required");
        String key = requiredEnv("DASHSCOPE_API_KEY");
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return new JevTextBackend(
                model,
                body ->
                        Mono.fromFuture(
                                        () ->
                                                http.sendAsync(
                                                        HttpRequest.newBuilder(endpoint)
                                                                .timeout(Duration.ofSeconds(60))
                                                                .header(
                                                                        "Content-Type",
                                                                        "application/json")
                                                                .header(
                                                                        "Authorization",
                                                                        "Bearer " + key)
                                                                .POST(
                                                                        HttpRequest.BodyPublishers
                                                                                .ofString(
                                                                                        qwenBody(
                                                                                                body)))
                                                                .build(),
                                                        HttpResponse.BodyHandlers.ofString()))
                                .map(
                                        response -> {
                                            if (response.statusCode() < 200
                                                    || response.statusCode() >= 300)
                                                throw new IllegalStateException(
                                                        "HTTP status " + response.statusCode());
                                            return response.body();
                                        }));
    }

    private static String qwenBody(String body) {
        try {
            var json = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(body);
            json.put("enable_thinking", false);
            return JSON.writeValueAsString(json);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("cannot serialize Qwen benchmark request");
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank())
            throw new IllegalStateException(name + " is required");
        return value;
    }
}
