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
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.JevRetryPolicy;
import io.agentscope.extensions.judge.jev.SystemOneRequest;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import io.agentscope.extensions.judge.jev.context.JevContextCompactor;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactionStrategy;
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

/** Explicit SHADOW-only comparison; semantic labels are authored fixtures, never model input. */
public final class JevContextBenchmark {
    private static final ObjectMapper JSON = new ObjectMapper();

    private JevContextBenchmark() {}

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
        var measured =
                (Function<SystemOneRequest, Mono<SystemOneResult>>)
                        (r ->
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
                                                        input.addAndGet(
                                                                reply.usage().inputTokens());
                                                        output.addAndGet(
                                                                reply.usage().outputTokens());
                                                    }
                                                }));
        var config =
                new JevContextCompactor.Config(
                        .2,
                        .8,
                        1,
                        25000,
                        30000,
                        128,
                        100,
                        .01,
                        1_000_000,
                        Set.of("read"),
                        Set.of());
        var compactor =
                new JevContextCompactor(
                        measured,
                        config,
                        new JevExecution.Options(
                                JevExecution.Mode.SHADOW,
                                Duration.ofSeconds(60),
                                "context-synthetic-v1",
                                (ctx, r) -> {}),
                        null);
        List<Object> rows = new ArrayList<>();
        List<Double> times = new ArrayList<>();
        int labelled = 0,
                correct = 0,
                wrong = 0,
                abstained = 0,
                errors = 0,
                pinned = 0,
                pinnedCorrect = 0;
        var lines = Files.readAllLines(data);
        if (lines.size() > 100) throw new IllegalArgumentException("fixture limit");
        for (String line : lines) {
            if (line.isBlank()) continue;
            var fixture = JSON.readTree(line);
            String id = fixture.path("id").asText();
            List<Msg> messages = messages(fixture);
            long started = System.nanoTime();
            var decision =
                    mode.equals("--baseline")
                            ? null
                            : compactor
                                    .plan(
                                            new ConversationCompactionStrategy.Request(
                                                    RuntimeContext.builder()
                                                            .userId("benchmark")
                                                            .sessionId(id)
                                                            .build(),
                                                    messages,
                                                    "benchmark",
                                                    id,
                                                    100000))
                                    .block();
            double millis = (System.nanoTime() - started) / 1_000_000d;
            times.add(millis);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("status", decision == null ? "BASELINE" : decision.status().name());
            row.put("reason", decision == null ? "KEEP_ORIGINAL" : decision.reason());
            row.put("elapsedMillis", millis);
            Map<String, Object> pairs = new LinkedHashMap<>();
            int index = 0;
            for (JsonNode tool : fixture.path("tools")) {
                String key = "t" + (++index);
                String expected = tool.path("expected").asText();
                var plan = decision == null ? null : decision.value();
                var score = plan == null ? null : plan.scores().get(key);
                boolean control =
                        !tool.path("name").asText("read").equals("read")
                                || !tool.path("state").asText("SUCCESS").equals("SUCCESS");
                String action =
                        decision == null
                                ? "KEEP"
                                : plan == null ? "NO_RESULT" : plan.decisions().get(key).name();
                boolean uncertain = score != null && score.reason().equals("UNCERTAIN");
                if (control) {
                    pinned++;
                    if (action.equals(expected)) pinnedCorrect++;
                } else {
                    labelled++;
                    if (action.equals("NO_RESULT")) errors++;
                    else if (uncertain) abstained++;
                    else if (action.equals(expected)) correct++;
                    else wrong++;
                }
                Map<String, Object> pair = new LinkedHashMap<>();
                pair.put("expected", expected);
                pair.put("actual", action);
                pair.put("pinned", control);
                pair.put("scores", score);
                pairs.put(key, pair);
            }
            row.put("pairs", pairs);
            rows.add(row);
            System.out.println("Completed " + id + " in " + Math.round(millis) + " ms");
        }
        times.sort(Double::compare);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("suite", "context-synthetic-v1");
        report.put("sourceCommit", "e3f262a7f4d42bd8dd32ced30d26176f7cb545b0");
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
        report.put("config", config);
        report.put("labelledSemanticPairs", labelled);
        report.put("correct", correct);
        report.put("incorrect", wrong);
        report.put("abstained", abstained);
        report.put("errors", errors);
        report.put("accuracy", labelled == 0 ? 0 : (double) correct / labelled);
        report.put("pinned", pinned);
        report.put("pinnedCorrect", pinnedCorrect);
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
    }

    public static List<Msg> messages(JsonNode fixture) {
        List<Msg> result = new ArrayList<>();
        String id = fixture.path("id").asText();
        result.add(
                Msg.builder()
                        .id(id + "-first")
                        .timestamp("2026-09-25 00:00:00.000")
                        .role(MsgRole.USER)
                        .textContent(fixture.path("goal").asText())
                        .build());
        for (var tool : fixture.path("tools")) {
            String toolId = tool.path("id").asText(), name = tool.path("name").asText("read");
            result.add(
                    Msg.builder()
                            .id(id + "-call-" + toolId)
                            .timestamp("2026-09-25 00:00:00.000")
                            .role(MsgRole.ASSISTANT)
                            .content(
                                    ToolUseBlock.builder()
                                            .id(toolId)
                                            .name(name)
                                            .input(Map.of("path", tool.path("path").asText()))
                                            .build())
                            .build());
            result.add(
                    Msg.builder()
                            .id(id + "-result-" + toolId)
                            .timestamp("2026-09-25 00:00:00.000")
                            .role(MsgRole.TOOL)
                            .content(
                                    ToolResultBlock.builder()
                                            .id(toolId)
                                            .name(name)
                                            .state(
                                                    ToolResultState.valueOf(
                                                            tool.path("state").asText("SUCCESS")))
                                            .output(
                                                    TextBlock.builder()
                                                            .text(
                                                                    tool.path("result")
                                                                            .asText()
                                                                            .repeat(400))
                                                            .build())
                                            .build())
                            .build());
        }
        result.add(
                Msg.builder()
                        .id(id + "-last")
                        .timestamp("2026-09-25 00:00:00.000")
                        .role(MsgRole.USER)
                        .textContent(fixture.path("next").asText())
                        .build());
        return List.copyOf(result);
    }
}
