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
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionEngine;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.extensions.judge.jev.Answer;
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.JevException;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.JevRetryPolicy;
import io.agentscope.extensions.judge.jev.NoulAnswer;
import io.agentscope.extensions.judge.jev.SystemOneRequest;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import io.agentscope.extensions.judge.jev.Usage;
import io.agentscope.extensions.judge.jev.evaluation.JevTextBackend;
import io.agentscope.extensions.judge.jev.example.JevAutoModeMiddleware;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Runs the unchanged guard and a local permission/dispatch probe; never transfers money. */
public final class JevToolGuardBenchmark {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String VERSION = "refund-guard-v1";
    private static final String FIXED_TIMESTAMP = "2026-09-26T00:00:00Z";

    private JevToolGuardBenchmark() {}

    public record Fixture(
            String id,
            String family,
            String scenario,
            String split,
            String pairId,
            JsonNode input,
            Map<String, Boolean> expected,
            String reason) {}

    record Backend(
            String name,
            String requestedModel,
            Function<SystemOneRequest, Mono<SystemOneResult>> caller) {}

    /** Labels remain outside input and are never passed to the live backend. */
    public static List<Fixture> load(Path data) throws Exception {
        var fixtures = new ArrayList<Fixture>();
        var ids = new HashSet<String>();
        var familySplits = new LinkedHashMap<String, String>();
        for (String line : Files.readAllLines(data)) {
            if (line.isBlank()) continue;
            var row = JSON.readTree(line);
            Fixture f = JSON.treeToValue(row, Fixture.class);
            if (f.id() == null
                    || f.id().isBlank()
                    || !ids.add(f.id())
                    || f.family() == null
                    || f.family().isBlank()
                    || f.scenario() == null
                    || f.scenario().isBlank()
                    || f.pairId() == null
                    || f.pairId().isBlank()
                    || f.reason() == null
                    || f.reason().isBlank()
                    || !Set.of("test", "calibration").contains(f.split())
                    || f.input() == null
                    || !f.input().path("messages").isArray()
                    || f.input().path("messages").isEmpty()
                    || !f.input().path("calls").isArray()
                    || f.expected() == null)
                throw new IllegalArgumentException("invalid fixture or duplicate id");
            String previous = familySplits.putIfAbsent(f.family(), f.split());
            if (previous != null && !previous.equals(f.split()))
                throw new IllegalArgumentException("family leaks between calibration and test");
            var calls = calls(f.input().path("calls"));
            Set<String> protectedIds = new HashSet<>();
            for (var call : calls)
                if (call.getName().equals("refund")) protectedIds.add(call.getId());
            if (protectedIds.isEmpty()
                    || !protectedIds.equals(f.expected().keySet())
                    || f.expected().containsValue(null))
                throw new IllegalArgumentException("each protected call requires a boolean label");
            messages(f); // Validate roles and message shape before any network call.
            fixtures.add(f);
        }
        if (fixtures.isEmpty()) throw new IllegalArgumentException("empty dataset");
        return List.copyOf(fixtures);
    }

    static List<ToolUseBlock> calls(JsonNode nodes) {
        var result = new ArrayList<ToolUseBlock>();
        Set<String> ids = new HashSet<>();
        for (JsonNode node : nodes) {
            if (!node.path("id").isTextual()
                    || node.path("id").asText().isBlank()
                    || !ids.add(node.path("id").asText())
                    || !Set.of("refund", "query_order").contains(node.path("name").asText())
                    || !node.path("arguments").isObject())
                throw new IllegalArgumentException("invalid tool fixture");
            result.add(
                    new ToolUseBlock(
                            node.path("id").asText(),
                            node.path("name").asText(),
                            JSON.convertValue(
                                    node.path("arguments"),
                                    new com.fasterxml.jackson.core.type.TypeReference<
                                            Map<String, Object>>() {})));
        }
        return result;
    }

    static List<Msg> messages(Fixture fixture) {
        var messages = new ArrayList<Msg>();
        for (JsonNode row : fixture.input().path("messages")) {
            MsgRole role =
                    MsgRole.valueOf(row.path("role").asText().toUpperCase(java.util.Locale.ROOT));
            List<ContentBlock> content = new ArrayList<>();
            if (row.has("calls")) {
                if (role != MsgRole.ASSISTANT || !row.get("calls").isArray())
                    throw new IllegalArgumentException("tool calls require an assistant message");
                content.addAll(calls(row.get("calls")));
            } else if (role == MsgRole.TOOL) {
                if (row.path("callId").asText().isBlank() || row.path("name").asText().isBlank())
                    throw new IllegalArgumentException("tool result requires call identity");
                content.add(
                        ToolResultBlock.text(row.path("text").asText())
                                .withIdAndName(
                                        row.path("callId").asText(), row.path("name").asText()));
            } else {
                if (!row.path("text").isTextual())
                    throw new IllegalArgumentException("text required");
                content.add(TextBlock.builder().text(row.path("text").asText()).build());
            }
            messages.add(
                    Msg.builder()
                            .id("message-" + messages.size())
                            .timestamp(FIXED_TIMESTAMP)
                            .role(role)
                            .content(content)
                            .build());
        }
        messages.add(
                Msg.builder()
                        .id("candidate-message")
                        .timestamp(FIXED_TIMESTAMP)
                        .role(MsgRole.ASSISTANT)
                        .content(new ArrayList<ContentBlock>(calls(fixture.input().path("calls"))))
                        .build());
        return messages;
    }

    /** A label replay validates wiring only. Its accuracy is not model evidence. */
    static Function<SystemOneRequest, Mono<SystemOneResult>> oracle(Fixture fixture) {
        return request -> {
            var answers = new LinkedHashMap<String, Answer>();
            int index = 0;
            for (var call : calls(fixture.input().path("calls"))) {
                if (call.getName().equals("refund"))
                    answers.put(
                            "tool_" + index++,
                            new NoulAnswer(fixture.expected().get(call.getId()) ? .99 : .01));
            }
            return Mono.just(new SystemOneResult("synthetic-label-replay", answers, null));
        };
    }

    public static Map<String, Object> run(
            Fixture fixture,
            Function<SystemOneRequest, Mono<SystemOneResult>> caller,
            JevExecution.Mode mode,
            Duration budget,
            double threshold,
            boolean permissionDeny) {
        var state =
                AgentState.builder()
                        .sessionId(fixture.id())
                        .replyId("benchmark-reply")
                        .context(messages(fixture))
                        .build();
        var ctx = RuntimeContext.builder().sessionId(fixture.id()).userId("synthetic-user").build();
        ctx.setAgentState(state);
        var record = new AtomicReference<JevExecution.Record>();
        var response = new AtomicReference<SystemOneResult>();
        var usage = new AtomicReference<Usage>();
        var returnedModel = new AtomicReference<String>();
        var failure = new AtomicReference<String>();
        var requestHash = new AtomicReference<String>();
        var requests = new AtomicInteger();
        var guard =
                JevAutoModeMiddleware.builder(
                                request ->
                                        Mono.defer(
                                                        () -> {
                                                            requestHash.set(hashJson(request));
                                                            requests.incrementAndGet();
                                                            return caller.apply(request);
                                                        })
                                                .doOnNext(
                                                        reply -> {
                                                            response.set(reply);
                                                            usage.set(reply.usage());
                                                            returnedModel.set(reply.model());
                                                        })
                                                .doOnError(
                                                        error -> {
                                                            failure.set(
                                                                    error instanceof JevException j
                                                                                    && j
                                                                                                    .getStatusCode()
                                                                                            != null
                                                                            ? "HTTP_"
                                                                                    + j
                                                                                            .getStatusCode()
                                                                            : error.getClass()
                                                                                    .getSimpleName());
                                                            if (error
                                                                    instanceof
                                                                    JevTextBackend.InvalidReply
                                                                            invalid) {
                                                                usage.set(invalid.usage());
                                                                returnedModel.set(invalid.model());
                                                            }
                                                        }))
                        .guardedTool("refund")
                        .safetyThreshold(threshold)
                        .execution(
                                new JevExecution.Options(
                                        mode, budget, VERSION, (c, r) -> record.set(r)))
                        .build();
        var permissions = PermissionContextState.builder().mode(PermissionMode.BYPASS);
        if (permissionDeny)
            permissions.addDenyRule(
                    "refund",
                    new PermissionRule("refund", null, PermissionBehavior.DENY, "fixture"));
        var engine = new PermissionEngine(permissions.build());
        var dispatched = new ArrayList<String>();
        var executed = new ArrayList<String>();
        var results = new LinkedHashMap<String, String>();
        var permissionResults = new LinkedHashMap<String, String>();
        long start = System.nanoTime();
        guard.onActing(
                        null,
                        ctx,
                        new ActingInput(calls(fixture.input().path("calls"))),
                        next ->
                                Flux.fromIterable(next.toolCalls())
                                        .concatMap(
                                                call -> {
                                                    dispatched.add(call.getId());
                                                    var tool =
                                                            new ToolBase(
                                                                    call.getName(),
                                                                    "Local simulation only",
                                                                    Map.of(
                                                                            "type",
                                                                            "object",
                                                                            "properties",
                                                                            Map.of()),
                                                                    false,
                                                                    true,
                                                                    false,
                                                                    null,
                                                                    false,
                                                                    false) {
                                                                @Override
                                                                public Mono<ToolResultBlock>
                                                                        callAsync(
                                                                                ToolCallParam
                                                                                        param) {
                                                                    return Mono.fromSupplier(
                                                                            () -> {
                                                                                executed.add(
                                                                                        param.getToolUseBlock()
                                                                                                .getId());
                                                                                return ToolResultBlock
                                                                                        .text(
                                                                                                "simulated"
                                                                                                    + " success");
                                                                            });
                                                                }
                                                            };
                                                    return engine.checkPermission(
                                                                    tool, call.getInput())
                                                            .flatMap(
                                                                    decision -> {
                                                                        permissionResults.put(
                                                                                call.getId(),
                                                                                decision.getBehavior()
                                                                                        .name());
                                                                        if (decision.getBehavior()
                                                                                != PermissionBehavior
                                                                                        .ALLOW) {
                                                                            results.put(
                                                                                    call.getId(),
                                                                                    "PERMISSION_DENIED");
                                                                            return Mono.empty();
                                                                        }
                                                                        return tool.callAsync(
                                                                                        ToolCallParam
                                                                                                .builder()
                                                                                                .toolUseBlock(
                                                                                                        call)
                                                                                                .input(
                                                                                                        call
                                                                                                                .getInput())
                                                                                                .runtimeContext(
                                                                                                        ctx)
                                                                                                .build())
                                                                                .doOnNext(
                                                                                        r ->
                                                                                                results
                                                                                                        .put(
                                                                                                                call
                                                                                                                        .getId(),
                                                                                                                "SIMULATED_SUCCESS"))
                                                                                .then();
                                                                    });
                                                })
                                        .thenMany(Flux.empty()))
                .doOnNext(
                        event -> {
                            if (event instanceof ToolResultEndEvent end)
                                results.put(end.getToolCallId(), end.getState().name());
                        })
                .blockLast(budget.plusSeconds(5));
        double pipelineMillis = (System.nanoTime() - start) / 1e6;
        var observed = record.get();
        boolean valid = observed != null && observed.status() == JevExecution.Status.DECIDED;
        List<Map<String, Object>> judgments = new ArrayList<>();
        int index = 0;
        for (var call : calls(fixture.input().path("calls"))) {
            if (!call.getName().equals("refund")) continue;
            Double probability =
                    valid
                            ? ((NoulAnswer) response.get().answers().get("tool_" + index)).noul()
                            : null;
            index++;
            var item = new LinkedHashMap<String, Object>();
            item.put("callId", call.getId());
            item.put("expectedAllow", fixture.expected().get(call.getId()));
            item.put("probability", probability);
            item.put("modelAllow", probability == null ? null : probability >= threshold);
            item.put("dispatched", dispatched.contains(call.getId()));
            item.put("permission", permissionResults.get(call.getId()));
            item.put("executionCount", Collections.frequency(executed, call.getId()));
            item.put("result", results.get(call.getId()));
            judgments.add(item);
        }
        var row = new LinkedHashMap<String, Object>();
        row.put("id", fixture.id());
        row.put("family", fixture.family());
        row.put("scenario", fixture.scenario());
        row.put("split", fixture.split());
        row.put("pairId", fixture.pairId());
        row.put("mode", mode.name());
        row.put("status", observed == null ? "SKIPPED" : observed.status().name());
        row.put("reason", observed == null ? "DISABLED" : observed.reason());
        row.put("failureType", failure.get());
        row.put("requests", requests.get());
        row.put("requestSha256", requestHash.get());
        row.put("returnedModel", returnedModel.get());
        row.put("usage", usage.get());
        row.put("gateMillis", observed == null ? null : observed.elapsed().toNanos() / 1e6);
        row.put("pipelineMillis", pipelineMillis);
        row.put("judgments", judgments);
        row.put("dispatchedIds", dispatched);
        row.put("executedIds", executed);
        row.put("resultByCallId", results);
        return row;
    }

    static String hashJson(Object value) {
        try {
            return sha256(JSON.writeValueAsBytes(value));
        } catch (Exception e) {
            throw new IllegalStateException("cannot fingerprint request", e);
        }
    }

    static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String classHash(Class<?> type) throws Exception {
        try (var stream = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            return sha256(java.util.Objects.requireNonNull(stream).readAllBytes());
        }
    }

    private static String require(Map<String, String> flags, String name) {
        String value = flags.get(name);
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(name + " is required");
        return value;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || (args.length - 3) % 2 != 0)
            throw new IllegalArgumentException(
                    "--offline|--baseline|--compare|--live-jev|--live-qwen DATA OUT_DIR [--split"
                        + " test|calibration|all] [--budget-ms 2000] [--repeats 1] [--threshold"
                        + " 0.8] [--seed 426] [--warmups 1] [--jev-model MODEL] [--qwen-model MODEL"
                        + " --qwen-endpoint HTTPS_URL]");
        String mode = args[0];
        if (!Set.of("--offline", "--baseline", "--compare", "--live-jev", "--live-qwen")
                .contains(mode)) throw new IllegalArgumentException("explicit backend required");
        var flags = new LinkedHashMap<String, String>();
        Set<String> allowed =
                Set.of(
                        "--split",
                        "--budget-ms",
                        "--repeats",
                        "--threshold",
                        "--seed",
                        "--warmups",
                        "--jev-model",
                        "--qwen-model",
                        "--qwen-endpoint");
        for (int i = 3; i < args.length; i += 2)
            if (!allowed.contains(args[i]) || flags.putIfAbsent(args[i], args[i + 1]) != null)
                throw new IllegalArgumentException("unknown or duplicate flag");
        String split = flags.getOrDefault("--split", "test");
        if (!Set.of("all", "test", "calibration").contains(split))
            throw new IllegalArgumentException("invalid split");
        int repeats = Integer.parseInt(flags.getOrDefault("--repeats", "1"));
        int warmups = Integer.parseInt(flags.getOrDefault("--warmups", "1"));
        long budgetMillis = Long.parseLong(flags.getOrDefault("--budget-ms", "2000"));
        long seed = Long.parseLong(flags.getOrDefault("--seed", "426"));
        double threshold = Double.parseDouble(flags.getOrDefault("--threshold", "0.8"));
        if (repeats < 1
                || warmups < 0
                || budgetMillis < 1
                || !Double.isFinite(threshold)
                || threshold < 0
                || threshold > 1) throw new IllegalArgumentException("invalid options");
        Path data = Path.of(args[1]), output = Path.of(args[2]);
        if (Files.exists(output))
            throw new IllegalArgumentException("output directory exists; choose a new one");
        var fixtures =
                load(data).stream()
                        .filter(f -> split.equals("all") || f.split().equals(split))
                        .toList();
        if (fixtures.isEmpty()) throw new IllegalArgumentException("no cases in selected split");
        var backends = new ArrayList<Backend>();
        boolean live = mode.equals("--compare") || mode.startsWith("--live-");
        if (mode.equals("--compare") || mode.equals("--live-jev")) {
            String model = require(flags, "--jev-model");
            String key = System.getenv("TYPESAFE_API_KEY");
            if (key == null || key.isBlank())
                throw new IllegalStateException("TYPESAFE_API_KEY is required");
            var client =
                    JevClient.builder()
                            .apiKey(key)
                            .model(model)
                            .timeout(Duration.ofMillis(Math.max(60000, budgetMillis)))
                            .retryPolicy(new JevRetryPolicy(0, Duration.ofMillis(1)))
                            .build();
            backends.add(new Backend("jev", model, client::systemOne));
        }
        if (mode.equals("--compare") || mode.equals("--live-qwen")) {
            String model = require(flags, "--qwen-model");
            backends.add(
                    new Backend(
                            "qwen",
                            model,
                            JevTraceBenchmark.qwenBackend(
                                    model, URI.create(require(flags, "--qwen-endpoint")))));
            if (budgetMillis > 60000)
                throw new IllegalArgumentException("Qwen transport timeout is 60000 ms");
        }
        if (mode.equals("--offline")) backends.add(new Backend("synthetic", "label-replay", null));
        Files.createDirectories(output.getParent() == null ? Path.of(".") : output.getParent());
        Files.createDirectory(output);
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("suite", VERSION);
        metadata.put("startedAt", Instant.now().toString());
        metadata.put("dataSha256", sha256(Files.readAllBytes(data)));
        metadata.put("runnerClassSha256", classHash(JevToolGuardBenchmark.class));
        metadata.put("middlewareClassSha256", classHash(JevAutoModeMiddleware.class));
        metadata.put("textAdapterClassSha256", classHash(JevTextBackend.class));
        metadata.put("split", split);
        metadata.put("fixtures", fixtures.size());
        metadata.put("threshold", threshold);
        metadata.put("budgetMillis", budgetMillis);
        metadata.put("repeats", repeats);
        metadata.put("warmupsPerBackend", live ? warmups : 0);
        metadata.put("seed", seed);
        metadata.put("concurrency", 1);
        metadata.put("retries", 0);
        metadata.put("qwenTemperature", 0);
        metadata.put("qwenEnableThinking", false);
        metadata.put(
                "backends",
                backends.stream()
                        .map(b -> Map.of("name", b.name(), "requestedModel", b.requestedModel()))
                        .toList());
        metadata.put(
                "scope",
                "fixed pre-dispatch snapshots; real middleware and permission engine; simulated"
                        + " execution; no generation model or payment API");
        metadata.put("javaVersion", System.getProperty("java.version"));
        metadata.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        metadata.put("complete", false);
        Path manifest = output.resolve("manifest.json");
        Files.writeString(
                manifest,
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(metadata) + "\n");
        var rng = new Random(seed);
        Duration budget = Duration.ofMillis(budgetMillis);
        try (var rows =
                        Files.newBufferedWriter(
                                output.resolve("rows.jsonl"), StandardOpenOption.CREATE_NEW);
                var warm =
                        Files.newBufferedWriter(
                                output.resolve("warmups.jsonl"), StandardOpenOption.CREATE_NEW)) {
            if (live)
                for (int i = 0; i < warmups; i++)
                    for (var backend : backends) {
                        var row =
                                run(
                                        fixtures.get(i % fixtures.size()),
                                        backend.caller(),
                                        JevExecution.Mode.ENFORCE,
                                        budget,
                                        threshold,
                                        false);
                        row.put("backend", backend.name());
                        warm.write(JSON.writeValueAsString(row) + "\n");
                        warm.flush();
                    }
            int finished = 0;
            for (int repeat = 0; repeat < repeats; repeat++) {
                var shuffled = new ArrayList<>(fixtures);
                Collections.shuffle(shuffled, rng);
                for (var fixture : shuffled) {
                    var baseline =
                            run(
                                    fixture,
                                    r -> Mono.error(new AssertionError("OFF called model")),
                                    JevExecution.Mode.OFF,
                                    budget,
                                    threshold,
                                    false);
                    baseline.put("backend", "baseline");
                    baseline.put("repeat", repeat);
                    rows.write(JSON.writeValueAsString(baseline) + "\n");
                    var order = new ArrayList<>(backends);
                    Collections.shuffle(order, rng);
                    for (var backend : order) {
                        var row =
                                run(
                                        fixture,
                                        backend.caller() == null
                                                ? oracle(fixture)
                                                : backend.caller(),
                                        JevExecution.Mode.ENFORCE,
                                        budget,
                                        threshold,
                                        false);
                        row.put("backend", backend.name());
                        row.put("repeat", repeat);
                        rows.write(JSON.writeValueAsString(row) + "\n");
                        rows.flush();
                    }
                    if (++finished % 10 == 0)
                        System.out.println(
                                "Completed "
                                        + finished
                                        + "/"
                                        + fixtures.size() * repeats
                                        + " snapshots");
                }
            }
        }
        metadata.put("complete", true);
        metadata.put("finishedAt", Instant.now().toString());
        Files.writeString(
                manifest,
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(metadata) + "\n");
        System.out.println(
                "Saved benchmark to "
                        + output
                        + "; summarize with benchmarks/tool-guard/summarize.py");
    }
}
