/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
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
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.JevException;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.JevRetryPolicy;
import io.agentscope.extensions.judge.jev.SystemOneRequest;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import io.agentscope.extensions.judge.jev.Usage;
import io.agentscope.extensions.judge.jev.evaluation.JevTextBackend;
import io.agentscope.extensions.judge.jev.routing.JevPhaseRouting;
import io.agentscope.extensions.judge.jev.routing.JevRouteCatalog.Candidate;
import io.agentscope.extensions.judge.jev.routing.JevRouteCatalog.Effort;
import io.agentscope.extensions.judge.jev.routing.JevRouteCatalog.Price;
import io.agentscope.extensions.judge.jev.routing.JevRouteCatalog.Quota;
import io.agentscope.extensions.judge.jev.routing.JevRouteCatalog.Route;
import io.agentscope.extensions.judge.jev.routing.JevRouteCatalog.Snapshot;
import io.agentscope.extensions.judge.jev.routing.JevRouteCatalog.Source;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Fixed route labels only; this runner never invokes the routed generation models. */
public final class JevPhaseRoutingBenchmark {
    private JevPhaseRoutingBenchmark() {}

    public static final List<Route> ROUTES =
            List.of(
                    new Route(
                            "plan",
                            "Design, complex analysis, ambiguous requirements or difficult failure"
                                    + " diagnosis",
                            List.of("strong")),
                    new Route(
                            "execute",
                            "Implement or perform a concrete, already specified procedure using"
                                    + " available tools",
                            List.of("fast")),
                    new Route(
                            "utility",
                            "Small talk, simple factual answers, rewriting or short summaries",
                            List.of("fast")));
    static final ObjectMapper JSON = new ObjectMapper();

    public static Snapshot snapshot(JsonNode row) {
        var strong = JevPhaseRoutingExample.scriptedModel("strong", new AtomicInteger());
        var fast = JevPhaseRoutingExample.scriptedModel("fast", new AtomicInteger());
        return new Snapshot(
                List.of(
                        new Candidate(
                                "strong",
                                strong,
                                "Text reasoning and complex engineering analysis. Cannot generate"
                                        + " images or access external data without tools.",
                                128000L,
                                8000,
                                true,
                                Set.of(Effort.HIGH),
                                Quota.AVAILABLE,
                                new Price("synthetic-catalog", 10, 30, 1, 10)),
                        new Candidate(
                                "fast",
                                fast,
                                "Text generation for short, straightforward tasks and prescribed"
                                    + " procedures. Cannot generate images or access external data"
                                    + " without tools.",
                                8000L,
                                2000,
                                true,
                                Set.of(Effort.LOW),
                                Quota.AVAILABLE,
                                new Price("synthetic-catalog", .2, .4, .02, .2))),
                row.path("inputTokens").asLong(200),
                500,
                row.has("minimumEffort") ? Effort.parse(row.path("minimumEffort").asText()) : null,
                null,
                row.path("failures").asInt(0));
    }

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
        var actualModels = new CopyOnWriteArrayList<String>();
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
                                            actualModels.add(reply.model());
                                            if (reply.usage() != null) usage.add(reply.usage());
                                        })
                                .doOnError(
                                        error -> {
                                            failures.merge(
                                                    error instanceof JevException j
                                                                    && j.getStatusCode() != null
                                                            ? "HTTP_" + j.getStatusCode()
                                                            : error.getClass().getSimpleName(),
                                                    1,
                                                    Integer::sum);
                                            if (error
                                                    instanceof
                                                    JevTextBackend.InvalidReply invalid) {
                                                actualModels.add(invalid.model());
                                                if (invalid.usage() != null)
                                                    usage.add(invalid.usage());
                                            }
                                        });
        var rows = new ArrayList<Object>();
        var times = new ArrayList<Double>();
        var routeTotals = new Totals();
        var effortTotals = new Totals();
        for (String line : Files.readAllLines(data)) {
            if (line.isBlank()) continue;
            JsonNode row = JSON.readTree(line);
            String id = row.path("id").asText();
            if (id.isBlank()
                    || row.path("request").asText().isBlank()
                    || row.path("goldRoute").asText().isBlank())
                throw new IllegalArgumentException("invalid fixture");
            var received = new AtomicReference<JevExecution.Record>();
            var options =
                    new JevExecution.Options(
                            baseline ? JevExecution.Mode.OFF : JevExecution.Mode.SHADOW,
                            Duration.ofSeconds(60),
                            "phase-synthetic-v1",
                            (c, r) -> received.set(r));
            var routing =
                    new JevPhaseRouting(
                            measured,
                            ROUTES,
                            Set.of("strong", "fast"),
                            .8,
                            true,
                            options,
                            (c, r) -> {});
            var source = snapshot(row);
            var ctx =
                    RuntimeContext.builder()
                            .userId("benchmark")
                            .sessionId(id)
                            .put(Source.class, new Source((c, i) -> Mono.just(source)))
                            .build();
            var original = JevPhaseRoutingExample.scriptedModel("original", new AtomicInteger());
            var input =
                    new ModelCallInput(
                            List.of(new UserMessage(row.path("request").asText())),
                            List.of(),
                            null,
                            original);
            long start = System.nanoTime();
            routing.onAgent(
                            ctx,
                            new AgentInput(input.messages()),
                            i ->
                                    routing.onModelCall(
                                            ctx,
                                            input,
                                            next -> {
                                                if (next != input)
                                                    throw new IllegalStateException(
                                                            "shadow changed actual input");
                                                return Flux.empty();
                                            }))
                    .blockLast(Duration.ofSeconds(65));
            double elapsed = (System.nanoTime() - start) / 1e6;
            times.add(elapsed);
            var record = received.get();
            var report = routing.report(ctx);
            boolean error = record != null && record.status() == JevExecution.Status.ERROR;
            String actualRoute = report == null ? null : report.route();
            if (record != null && record.reason().equals("NO_APPLICABLE_ROUTE"))
                actualRoute = "none";
            String actualEffort = report == null ? null : report.effort();
            String routeOutcome =
                    routeTotals.add(row.path("goldRoute").asText(), actualRoute, error);
            String effortOutcome =
                    row.has("goldEffort")
                            ? effortTotals.add(row.path("goldEffort").asText(), actualEffort, error)
                            : "UNLABELLED";
            var result = new LinkedHashMap<String, Object>();
            result.put("id", id);
            result.put("elapsedMillis", elapsed);
            result.put("expectedRoute", row.path("goldRoute").asText());
            result.put("actualRoute", actualRoute);
            result.put("routeOutcome", routeOutcome);
            result.put(
                    "expectedEffort",
                    row.has("goldEffort") ? row.path("goldEffort").asText() : null);
            result.put("actualEffort", actualEffort);
            result.put("effortOutcome", effortOutcome);
            result.put("status", record == null ? "SKIPPED" : record.status().name());
            result.put("reason", record == null ? "OFF" : record.reason());
            result.put("report", report);
            result.put("applied", false);
            rows.add(result);
            System.out.println("Completed " + id + " in " + (long) elapsed + " ms");
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("suite", "phase-routing-synthetic-v1");
        result.put("timestamp", Instant.now().toString());
        result.put("sourceCommit", "75e980a8b05cf931b3f7f0de364fea905ab4ca01");
        result.put(
                "dataSha256",
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(Files.readAllBytes(data))));
        result.put("backend", backend);
        result.put("requestedModel", model);
        result.put("actualModels", actualModels.stream().distinct().toList());
        result.put("mode", baseline ? "OFF" : "SHADOW");
        result.put("confidenceThreshold", .8);
        result.put("enableThinking", false);
        result.put("cases", rows.size());
        result.put("route", routeTotals.report());
        result.put("effort", effortTotals.report());
        var sorted = times.stream().sorted().toList();
        result.put(
                "latency",
                Map.of("p50Millis", percentile(sorted, .5), "p95Millis", percentile(sorted, .95)));
        result.put(
                "usage",
                Map.of(
                        "requests",
                        calls.get(),
                        "responsesWithUsage",
                        usage.size(),
                        "inputTokens",
                        usage.stream().mapToLong(Usage::inputTokens).sum(),
                        "outputTokens",
                        usage.stream().mapToLong(Usage::outputTokens).sum()));
        result.put("requestFailureKinds", failures);
        result.put(
                "limitations",
                "Authored route/effort labels and synthetic candidate capabilities/prices."
                    + " Generation models are not called; task quality, actual generation cost and"
                    + " provider wire effort are unmeasured.");
        result.put("rows", rows);
        Files.writeString(
                out,
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result),
                StandardOpenOption.CREATE_NEW);
        System.out.println("Saved benchmark report");
    }

    private static double percentile(List<Double> sorted, double p) {
        return sorted.isEmpty()
                ? 0
                : sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * p) - 1));
    }

    private static final class Totals {
        int correct, incorrect, abstained, errors;

        String add(String expected, String actual, boolean error) {
            if (error) {
                errors++;
                return "ERROR";
            }
            if (actual == null) {
                abstained++;
                return "ABSTAINED";
            }
            if (expected.equals(actual)) {
                correct++;
                return "CORRECT";
            }
            incorrect++;
            return "INCORRECT";
        }

        Map<String, Integer> report() {
            return Map.of(
                    "labels",
                    correct + incorrect + abstained + errors,
                    "correct",
                    correct,
                    "incorrect",
                    incorrect,
                    "abstained",
                    abstained,
                    "errors",
                    errors);
        }
    }
}
