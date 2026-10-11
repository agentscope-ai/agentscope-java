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
package io.agentscope.builder.web.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.JevGuardrail;
import io.agentscope.extensions.judge.jev.JevJudge;
import io.agentscope.extensions.judge.jev.NoulQuestion;
import io.agentscope.extensions.judge.jev.browser.JevBrowserNavigator;
import io.agentscope.extensions.judge.jev.browser.JevBrowserReadTool;
import io.agentscope.extensions.judge.jev.evaluation.JevTraceEvaluationMiddleware;
import io.agentscope.extensions.judge.jev.evaluation.JevTraceEvaluator;
import io.agentscope.extensions.judge.jev.evaluation.JevTraceMetrics;
import io.agentscope.extensions.judge.jev.evidence.JevEvidenceProcessor;
import io.agentscope.extensions.judge.jev.evidence.JevEvidenceTool;
import io.agentscope.extensions.judge.jev.example.JevAutoModeMiddleware;
import io.agentscope.extensions.judge.jev.example.JevModelRouterMiddleware;
import io.agentscope.extensions.judge.jev.example.JevToolSelectionMiddleware;
import io.agentscope.extensions.judge.jev.integration.JevResponseMiddleware;
import io.agentscope.extensions.judge.jev.review.JevCodeReviewTool;
import io.agentscope.extensions.judge.jev.review.JevCodeReviewer;
import io.agentscope.extensions.judge.jev.supervision.JevSupervisionMiddleware;
import io.agentscope.extensions.judge.jev.supervision.JevTaskSupervisor;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Opt-in JEV assembly from session overrides. No endpoint, credential or arbitrary model injection. */
@Component
public class JevServiceSupport implements AutoCloseable {
    public record TraceSink(Consumer<JevExecution.Record> accept) {}

    private final Set<String> allowedModels;
    private final java.util.function.Supplier<JevClient> clients;
    private final ThreadPoolExecutor observations =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(256),
                    task -> {
                        var t = new Thread(task, "jev-observation");
                        t.setDaemon(true);
                        return t;
                    });
    private static final ObjectMapper JSON = new ObjectMapper();

    @org.springframework.beans.factory.annotation.Autowired
    public JevServiceSupport(@Value("${agentscope.jev.allowed-models:}") String allowedModels) {
        this(allowedModels, () -> JevClient.builder().build());
    }

    JevServiceSupport(String allowedModels, java.util.function.Supplier<JevClient> clients) {
        this.clients = clients;
        this.allowedModels =
                Set.copyOf(
                        Arrays.stream(allowedModels.split(","))
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .toList());
    }

    public List<MiddlewareBase> middlewares(String overrides) {
        JsonNode root;
        try {
            root =
                    overrides == null || overrides.isBlank()
                            ? JSON.createObjectNode()
                            : JSON.readTree(overrides);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid overrides JSON");
        }
        if (root == null || !root.isObject())
            throw new IllegalArgumentException("overrides must be an object");
        JsonNode config = root.path("jev");
        if (config.isMissingNode() || config.isNull()) return List.of();
        if (!config.isObject()) throw new IllegalArgumentException("jev must be an object");
        config.fieldNames()
                .forEachRemaining(
                        k -> {
                            if (!Set.of(
                                            "tools",
                                            "guard",
                                            "routing",
                                            "content",
                                            "quality",
                                            "evaluation",
                                            "compaction",
                                            "supervision",
                                            "review",
                                            "retrieval",
                                            "browser")
                                    .contains(k))
                                throw new IllegalArgumentException("Unknown JEV purpose");
                        });
        List<MiddlewareBase> result = new ArrayList<>();
        var response = JevResponseMiddleware.builder();
        boolean responseEnabled = false;
        // Construct the client only when a purpose is explicitly enabled.
        for (String purpose :
                List.of(
                        "tools",
                        "guard",
                        "routing",
                        "content",
                        "quality",
                        "evaluation",
                        "supervision")) {
            JsonNode c = config.path(purpose);
            if (c.isMissingNode()) continue;
            if (!c.isObject()) throw new IllegalArgumentException("JEV purpose must be an object");
            if (purpose.equals("supervision")) {
                addSupervision(c, result);
                continue;
            }
            if (purpose.equals("evaluation")) {
                addEvaluation(c, result);
                continue;
            }

            if (purpose.equals("routing") && c.has("routes")) {
                addPhaseRouting(c, result);
                continue;
            }

            c.fieldNames()
                    .forEachRemaining(
                            k -> {
                                if (!Set.of(
                                                "mode",
                                                "budgetMillis",
                                                "version",
                                                "threshold",
                                                "rejectionThreshold",
                                                "maxTools",
                                                "guardedTools",
                                                "models",
                                                "blockOnReview",
                                                "maxRevisions",
                                                "criteria")
                                        .contains(k))
                                    throw new IllegalArgumentException("Unknown JEV setting");
                            });
            var mode = JevExecution.Mode.valueOf(c.path("mode").asText("OFF"));
            if (mode == JevExecution.Mode.OFF) continue;
            long budget = c.path("budgetMillis").asLong(2000);
            if (budget < 1 || budget > 30000)
                throw new IllegalArgumentException("budgetMillis must be 1..30000");
            var options =
                    new JevExecution.Options(
                            mode,
                            Duration.ofMillis(budget),
                            c.path("version").asText("v1"),
                            this::observe);
            var client = clients.get();
            switch (purpose) {
                case "tools" ->
                        result.add(
                                JevToolSelectionMiddleware.builder(client)
                                        .execution(options)
                                        .maxTools(c.path("maxTools").asInt(3))
                                        .confidenceThreshold(c.path("threshold").asDouble(0.8))
                                        .rejectionThreshold(
                                                c.path("rejectionThreshold").asDouble(0.2))
                                        .build());
                case "guard" ->
                        result.add(
                                JevAutoModeMiddleware.builder(client)
                                        .execution(options)
                                        .safetyThreshold(c.path("threshold").asDouble(0.8))
                                        .guardedTools(Set.copyOf(strings(c.path("guardedTools"))))
                                        .build());
                case "content" -> {
                    var policy = JevGuardrail.defaults(client);
                    response.guardrails(policy, policy, options)
                            .blockOnReview(c.path("blockOnReview").asBoolean(false));
                    responseEnabled = true;
                }
                case "quality" -> {
                    List<JevJudge.Criterion> criteria = new ArrayList<>();
                    if (!c.path("criteria").isArray())
                        throw new IllegalArgumentException("quality.criteria array required");
                    for (JsonNode item : c.path("criteria")) {
                        if (!item.isObject()
                                || !item.path("instructions").isTextual()
                                || item.path("instructions").asText().isBlank())
                            throw new IllegalArgumentException("criterion instructions required");
                        item.fieldNames()
                                .forEachRemaining(
                                        k -> {
                                            if (!Set.of(
                                                            "id",
                                                            "instructions",
                                                            "expected",
                                                            "failThreshold",
                                                            "passThreshold")
                                                    .contains(k))
                                                throw new IllegalArgumentException(
                                                        "Unknown quality criterion setting");
                                        });
                        criteria.add(
                                new JevJudge.Criterion(
                                        item.path("id").asText(),
                                        new NoulQuestion(item.path("instructions").asText(), null),
                                        item.path("expected").asBoolean(true),
                                        item.path("failThreshold").asDouble(.2),
                                        item.path("passThreshold").asDouble(.8)));
                    }
                    if (criteria.size() > 64)
                        throw new IllegalArgumentException("at most 64 quality criteria");
                    response.quality(
                                    new JevJudge(client, options.budget()),
                                    new JevJudge.Definition(options.version(), criteria),
                                    options)
                            .maxRevisions(c.path("maxRevisions").asInt(1));
                    responseEnabled = true;
                }
                case "routing" -> {
                    var builder =
                            JevModelRouterMiddleware.builder(client)
                                    .execution(options)
                                    .confidenceThreshold(c.path("threshold").asDouble(0.8));
                    for (String name : strings(c.path("models"))) {
                        if (!allowedModels.contains(name))
                            throw new IllegalArgumentException(
                                    "JEV route model is not operator-allowlisted");
                        builder.choice(
                                name, ModelRegistry.resolve(name), "Configured model: " + name);
                    }
                    // Tool compatibility stays conservative: tool-bearing calls use the original
                    // model.
                    result.add(builder.build());
                }
                default -> throw new IllegalArgumentException("Unknown purpose");
            }
        }
        if (responseEnabled) result.add(response.build());
        return List.copyOf(result);
    }

    /** Compaction is a Harness strategy, not an onModelCall middleware. */
    public java.util.Optional<io.agentscope.harness.agent.memory.compaction.CompactionConfig>
            compaction(
                    String overrides,
                    io.agentscope.extensions.judge.jev.context.JevContextArchive archive) {
        JsonNode c;
        try {
            c =
                    overrides == null || overrides.isBlank()
                            ? JSON.createObjectNode()
                            : JSON.readTree(overrides).path("jev").path("compaction");
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid compaction configuration");
        }
        if (c.isMissingNode()) return java.util.Optional.empty();
        if (!c.isObject()) throw new IllegalArgumentException("compaction must be an object");
        c.fieldNames()
                .forEachRemaining(
                        k -> {
                            if (!Set.of(
                                            "mode",
                                            "version",
                                            "budgetMillis",
                                            "threshold",
                                            "rejectionThreshold",
                                            "eligibleTools",
                                            "preserveRecentMessages",
                                            "maxStateTokens",
                                            "maxRequestTokens",
                                            "maxQuestions",
                                            "truncateHeadChars",
                                            "minimumReduction")
                                    .contains(k))
                                throw new IllegalArgumentException("Unknown compaction setting");
                        });
        var mode = JevExecution.Mode.valueOf(c.path("mode").asText("OFF"));
        if (mode == JevExecution.Mode.OFF) return java.util.Optional.empty();
        if (!c.path("threshold").isNumber() || !c.path("rejectionThreshold").isNumber())
            throw new IllegalArgumentException("explicit compaction thresholds required");
        long budget = c.path("budgetMillis").asLong(3000);
        int state = c.path("maxStateTokens").asInt(25000),
                request = c.path("maxRequestTokens").asInt(30000);
        if (budget < 1 || budget > 30000 || state > 25000 || request > 30000)
            throw new IllegalArgumentException("compaction budget exceeds service limits");
        var config =
                new io.agentscope.extensions.judge.jev.context.JevContextCompactor.Config(
                        c.path("rejectionThreshold").asDouble(),
                        c.path("threshold").asDouble(),
                        c.path("preserveRecentMessages").asInt(6),
                        state,
                        request,
                        c.path("maxQuestions").asInt(128),
                        c.path("truncateHeadChars").asInt(300),
                        c.path("minimumReduction").asDouble(.1),
                        5_000_000,
                        Set.copyOf(strings(c.path("eligibleTools"))),
                        Set.of());
        var strategy =
                new io.agentscope.extensions.judge.jev.context.JevContextCompactor(
                        clients.get()::systemOne,
                        config,
                        new JevExecution.Options(
                                mode,
                                Duration.ofMillis(budget),
                                c.path("version").asText("v1"),
                                this::observe),
                        archive);
        return java.util.Optional.of(
                io.agentscope.harness.agent.memory.compaction.CompactionConfig.builder()
                        .strategy(strategy)
                        .flushBeforeCompact(mode != JevExecution.Mode.ENFORCE)
                        .build());
    }

    /** Browser URLs, exclusive sessions and independent verifiers are supplied by the host. */
    public java.util.Optional<JevBrowserReadTool> browserTool(String overrides) {
        JsonNode c;
        try {
            c =
                    overrides == null || overrides.isBlank()
                            ? JSON.createObjectNode()
                            : JSON.readTree(overrides).path("jev").path("browser");
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid browser configuration");
        }
        if (c.isMissingNode()) return java.util.Optional.empty();
        if (!c.isObject()) throw new IllegalArgumentException("browser must be an object");
        c.fieldNames()
                .forEachRemaining(
                        k -> {
                            if (!Set.of(
                                            "mode",
                                            "version",
                                            "budgetMillis",
                                            "threshold",
                                            "maxSteps",
                                            "maxStale",
                                            "maxNoProgress",
                                            "maxStateChars")
                                    .contains(k))
                                throw new IllegalArgumentException("Unknown browser setting");
                        });
        var mode = JevExecution.Mode.valueOf(c.path("mode").asText("OFF"));
        if (mode == JevExecution.Mode.OFF) return java.util.Optional.empty();
        double threshold = requiredNumber(c, "threshold");
        if (threshold < 0 || threshold > 1)
            throw new IllegalArgumentException("threshold must be 0..1");
        var limits =
                new JevBrowserNavigator.Limits(
                        (int) boundedInteger(c, "maxSteps", 8, 1, 64),
                        (int) boundedInteger(c, "maxStale", 2, 0, 8),
                        (int) boundedInteger(c, "maxNoProgress", 3, 1, 8),
                        (int) boundedInteger(c, "maxStateChars", 32000, 100, 100000));
        var options =
                new JevExecution.Options(
                        mode,
                        Duration.ofMillis(boundedInteger(c, "budgetMillis", 10000, 1, 30000)),
                        c.path("version").asText("browser-v1"),
                        this::observe);
        return java.util.Optional.of(
                new JevBrowserReadTool(
                        new JevBrowserNavigator(
                                clients.get()::systemOne, threshold, limits, options)));
    }

    /** Explicit retrieval tool; sources and ACL are host-owned per-run dependencies. */
    public java.util.Optional<JevEvidenceTool> evidenceTool(String overrides) {
        JsonNode c;
        try {
            c =
                    overrides == null || overrides.isBlank()
                            ? JSON.createObjectNode()
                            : JSON.readTree(overrides).path("jev").path("retrieval");
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid retrieval configuration");
        }
        if (c.isMissingNode()) return java.util.Optional.empty();
        if (!c.isObject()) throw new IllegalArgumentException("retrieval must be an object");
        c.fieldNames()
                .forEachRemaining(
                        k -> {
                            if (!Set.of(
                                            "mode",
                                            "version",
                                            "budgetMillis",
                                            "rejectionThreshold",
                                            "injectionThreshold",
                                            "contradictionThreshold",
                                            "relevanceThreshold",
                                            "evidenceThreshold",
                                            "minimumScore",
                                            "maxCandidates",
                                            "topK",
                                            "maxPassageChars",
                                            "maxQueryChars",
                                            "maxStateChars",
                                            "maxRequests")
                                    .contains(k))
                                throw new IllegalArgumentException("Unknown retrieval setting");
                        });
        var mode = JevExecution.Mode.valueOf(c.path("mode").asText("OFF"));
        if (mode == JevExecution.Mode.OFF) return java.util.Optional.empty();
        double no = requiredNumber(c, "rejectionThreshold");
        var policy =
                new JevEvidenceProcessor.Policy(
                        new JevEvidenceProcessor.Threshold(
                                no, requiredNumber(c, "injectionThreshold")),
                        new JevEvidenceProcessor.Threshold(
                                no, requiredNumber(c, "contradictionThreshold")),
                        new JevEvidenceProcessor.Threshold(
                                no, requiredNumber(c, "relevanceThreshold")),
                        new JevEvidenceProcessor.Threshold(
                                no, requiredNumber(c, "evidenceThreshold")));
        int maxCandidates = (int) boundedInteger(c, "maxCandidates", 32, 1, 128);
        int topK = (int) boundedInteger(c, "topK", Math.min(5, maxCandidates), 1, maxCandidates);
        var limits =
                new JevEvidenceProcessor.Limits(
                        maxCandidates,
                        (int) boundedInteger(c, "maxPassageChars", 16000, 1, 64000),
                        (int) boundedInteger(c, "maxQueryChars", 8000, 1, 16000),
                        (int) boundedInteger(c, "maxStateChars", 32000, 1, 100000),
                        (int) boundedInteger(c, "maxRequests", 64, 1, 256));
        double floor = c.has("minimumScore") ? requiredNumber(c, "minimumScore") : 0;
        if (floor < 0 || floor > 1) throw new IllegalArgumentException("minimumScore must be 0..1");
        var options =
                new JevExecution.Options(
                        mode,
                        Duration.ofMillis(boundedInteger(c, "budgetMillis", 5000, 1, 30000)),
                        c.path("version").asText("evidence-v1"),
                        this::observe);
        return java.util.Optional.of(
                new JevEvidenceTool(
                        new JevEvidenceProcessor(
                                clients.get()::systemOne, policy, limits, floor, options),
                        maxCandidates,
                        topK));
    }

    /** Explicit read-only application tool; evidence lookup is supplied on each run context. */
    public java.util.Optional<JevCodeReviewTool> reviewTool(String overrides) {
        JsonNode c;
        try {
            c =
                    overrides == null || overrides.isBlank()
                            ? JSON.createObjectNode()
                            : JSON.readTree(overrides).path("jev").path("review");
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid review configuration");
        }
        if (c.isMissingNode()) return java.util.Optional.empty();
        if (!c.isObject()) throw new IllegalArgumentException("review must be an object");
        c.fieldNames()
                .forEachRemaining(
                        k -> {
                            if (!Set.of(
                                            "mode",
                                            "version",
                                            "budgetMillis",
                                            "threshold",
                                            "rejectionThreshold",
                                            "minConfidence",
                                            "routeSeverity",
                                            "blockingSeverity",
                                            "maxProfiles",
                                            "maxFollowUps",
                                            "maxRequests",
                                            "maxFiles",
                                            "maxFileChars",
                                            "maxStateChars",
                                            "maxRegions")
                                    .contains(k))
                                throw new IllegalArgumentException("Unknown review setting");
                        });
        var mode = JevExecution.Mode.valueOf(c.path("mode").asText("OFF"));
        if (mode == JevExecution.Mode.OFF) return java.util.Optional.empty();
        if (mode != JevExecution.Mode.SHADOW)
            throw new IllegalArgumentException("review supports OFF/SHADOW only");
        var config =
                new JevCodeReviewer.Config(
                        requiredNumber(c, "rejectionThreshold"),
                        requiredNumber(c, "threshold"),
                        requiredNumber(c, "minConfidence"),
                        requiredNumber(c, "routeSeverity"),
                        requiredNumber(c, "blockingSeverity"),
                        (int) boundedInteger(c, "maxProfiles", 5, 0, 10),
                        (int) boundedInteger(c, "maxFollowUps", 8, 0, 16),
                        (int) boundedInteger(c, "maxRequests", 64, 1, 128),
                        (int) boundedInteger(c, "maxFiles", 50, 1, 100),
                        (int) boundedInteger(c, "maxFileChars", 100000, 1, 100000),
                        (int) boundedInteger(c, "maxStateChars", 100000, 1, 100000),
                        (int) boundedInteger(c, "maxRegions", 128, 1, 254));
        var options =
                new JevExecution.Options(
                        mode,
                        Duration.ofMillis(boundedInteger(c, "budgetMillis", 10000, 1, 30000)),
                        c.path("version").asText("review-v1"),
                        this::observe);
        return java.util.Optional.of(
                new JevCodeReviewTool(
                        new JevCodeReviewer(clients.get()::systemOne, config, options)));
    }

    private void addSupervision(JsonNode config, List<MiddlewareBase> target) {
        config.fieldNames()
                .forEachRemaining(
                        k -> {
                            if (!Set.of(
                                            "mode",
                                            "version",
                                            "budgetMillis",
                                            "threshold",
                                            "rejectionThreshold",
                                            "minIntervalMillis",
                                            "periodicIntervalMillis",
                                            "maxAssessments",
                                            "maxConsecutiveFailures",
                                            "maxTextChars",
                                            "maxDiffChars",
                                            "maxFiles",
                                            "maxStateChars")
                                    .contains(k))
                                throw new IllegalArgumentException("Unknown supervision setting");
                        });
        var mode = JevExecution.Mode.valueOf(config.path("mode").asText("OFF"));
        if (mode == JevExecution.Mode.OFF) return;
        if (mode != JevExecution.Mode.SHADOW)
            throw new IllegalArgumentException("supervision supports OFF/SHADOW only");
        var thresholds =
                new JevTaskSupervisor.Thresholds(
                        requiredNumber(config, "rejectionThreshold"),
                        requiredNumber(config, "threshold"));
        var budget = Duration.ofMillis(boundedInteger(config, "budgetMillis", 3000, 1, 30000));
        var limits =
                new JevTaskSupervisor.Limits(
                        (int) boundedInteger(config, "maxTextChars", 12000, 1, 12000),
                        (int) boundedInteger(config, "maxDiffChars", 30000, 1, 30000),
                        (int) boundedInteger(config, "maxFiles", 100, 1, 100),
                        (int) boundedInteger(config, "maxStateChars", 100000, 1, 100000));
        var schedule =
                new JevSupervisionMiddleware.Schedule(
                        Duration.ofMillis(
                                boundedInteger(config, "minIntervalMillis", 5000, 1000, 60000)),
                        Duration.ofMillis(
                                boundedInteger(
                                        config, "periodicIntervalMillis", 30000, 1000, 300000)),
                        (int) boundedInteger(config, "maxAssessments", 60, 1, 120),
                        (int) boundedInteger(config, "maxConsecutiveFailures", 3, 1, 10));
        String version = config.path("version").asText("foreman-v1");
        var supervisor =
                new JevTaskSupervisor(
                        clients.get()::systemOne,
                        thresholds,
                        limits,
                        new JevExecution.Options(mode, budget, version, this::observe));
        target.add(
                new JevSupervisionMiddleware(
                        supervisor,
                        schedule,
                        (ctx, observation) -> {
                            var decision = observation.decision();
                            var summary = new java.util.LinkedHashMap<String, String>();
                            summary.put("runId", observation.scope().runId());
                            summary.put("assessment", Integer.toString(observation.assessment()));
                            summary.put("advice", observation.advice().name());
                            summary.put("superseded", Boolean.toString(observation.superseded()));
                            summary.put(
                                    "consecutiveFailures",
                                    Integer.toString(observation.consecutiveFailures()));
                            observe(
                                    ctx,
                                    new JevExecution.Record(
                                            "supervision.advice",
                                            version,
                                            mode,
                                            decision.status(),
                                            decision.reason(),
                                            Duration.ZERO,
                                            java.util.Map.copyOf(summary)));
                            if (decision.value() != null)
                                decision.value()
                                        .findings()
                                        .forEach(
                                                (key, finding) ->
                                                        observe(
                                                                ctx,
                                                                new JevExecution.Record(
                                                                        "supervision." + key,
                                                                        version,
                                                                        mode,
                                                                        finding.truth()
                                                                                        == JevTaskSupervisor
                                                                                                .Truth
                                                                                                .UNKNOWN
                                                                                ? JevExecution
                                                                                        .Status
                                                                                        .INCONCLUSIVE
                                                                                : JevExecution
                                                                                        .Status
                                                                                        .DECIDED,
                                                                        finding.truth().name(),
                                                                        Duration.ZERO,
                                                                        java.util.Map.of(
                                                                                "runId",
                                                                                observation
                                                                                        .scope()
                                                                                        .runId(),
                                                                                "assessment",
                                                                                Integer.toString(
                                                                                        observation
                                                                                                .assessment()),
                                                                                "probability",
                                                                                Double.toString(
                                                                                        finding
                                                                                                .probability()),
                                                                                "superseded",
                                                                                Boolean.toString(
                                                                                        observation
                                                                                                .superseded())))));
                        }));
    }

    private void addEvaluation(JsonNode config, List<MiddlewareBase> target) {
        config.fieldNames()
                .forEachRemaining(
                        k -> {
                            if (!Set.of(
                                            "mode",
                                            "budgetMillis",
                                            "version",
                                            "threshold",
                                            "rejectionThreshold",
                                            "metrics",
                                            "maxQuestions",
                                            "maxStateChars")
                                    .contains(k))
                                throw new IllegalArgumentException("Unknown evaluation setting");
                        });
        var mode = JevExecution.Mode.valueOf(config.path("mode").asText("OFF"));
        if (mode == JevExecution.Mode.OFF) return;
        if (mode != JevExecution.Mode.SHADOW)
            throw new IllegalArgumentException(
                    "trace evaluation is post-run and supports OFF/SHADOW only");
        long budget = boundedInteger(config, "budgetMillis", 3000, 1, 30000);
        int maxQuestions = (int) boundedInteger(config, "maxQuestions", 128, 1, 512);
        int maxState = (int) boundedInteger(config, "maxStateChars", 100000, 1, 1000000);
        double fail = requiredNumber(config, "rejectionThreshold");
        double pass = requiredNumber(config, "threshold");
        var metrics = JevTraceMetrics.agentMetrics(new JevTraceMetrics.Thresholds(fail, pass));
        if (config.has("metrics")) {
            var names = strings(config.path("metrics"));
            if (names.isEmpty()
                    || Set.copyOf(names).size() != names.size()
                    || !metrics.stream().map(m -> m.id()).toList().containsAll(names))
                throw new IllegalArgumentException("evaluation metrics must be unique known names");
            metrics =
                    names.stream()
                            .map(
                                    name ->
                                            JevTraceMetrics.agentMetrics(
                                                            new JevTraceMetrics.Thresholds(
                                                                    fail, pass))
                                                    .stream()
                                                    .filter(m -> m.id().equals(name))
                                                    .findFirst()
                                                    .orElseThrow())
                            .toList();
        }
        var options =
                new JevExecution.Options(
                        mode,
                        Duration.ofMillis(budget),
                        config.path("version").asText("v1"),
                        this::observe);
        var evaluator =
                new JevTraceEvaluator(
                        clients.get(),
                        metrics,
                        new JevTraceEvaluator.Limits(options.budget(), maxQuestions, maxState));
        target.add(
                new JevTraceEvaluationMiddleware(
                        evaluator, options, maxState, this::observeEvaluation));
    }

    private void addPhaseRouting(JsonNode config, List<MiddlewareBase> target) {
        config.fieldNames()
                .forEachRemaining(
                        key -> {
                            if (!Set.of(
                                            "mode",
                                            "version",
                                            "budgetMillis",
                                            "threshold",
                                            "picksEffort",
                                            "routes")
                                    .contains(key))
                                throw new IllegalArgumentException("Unknown phase routing setting");
                        });
        var mode = JevExecution.Mode.valueOf(config.path("mode").asText("OFF"));
        if (mode == JevExecution.Mode.OFF) return;
        if (!config.path("routes").isArray()
                || config.path("routes").isEmpty()
                || config.path("routes").size() > 64)
            throw new IllegalArgumentException("routing.routes must contain 1..64 routes");
        if (config.has("picksEffort") && !config.path("picksEffort").isBoolean())
            throw new IllegalArgumentException("picksEffort must be boolean");
        double threshold = requiredNumber(config, "threshold");
        if (!Double.isFinite(threshold) || threshold < 0 || threshold > 1)
            throw new IllegalArgumentException("routing threshold must be 0..1");
        var routes =
                new ArrayList<io.agentscope.extensions.judge.jev.routing.JevRouteCatalog.Route>();
        for (var route : config.path("routes")) {
            if (!route.isObject()) throw new IllegalArgumentException("route must be an object");
            route.fieldNames()
                    .forEachRemaining(
                            key -> {
                                if (!Set.of("id", "description", "models").contains(key))
                                    throw new IllegalArgumentException("Unknown route field");
                            });
            var models = strings(route.path("models"));
            if (models.stream().anyMatch(m -> !allowedModels.contains(m)))
                throw new IllegalArgumentException("JEV route model is not operator-allowlisted");
            routes.add(
                    new io.agentscope.extensions.judge.jev.routing.JevRouteCatalog.Route(
                            route.path("id").asText(), route.path("description").asText(), models));
        }
        if (routes.stream()
                        .map(io.agentscope.extensions.judge.jev.routing.JevRouteCatalog.Route::id)
                        .distinct()
                        .count()
                != routes.size()) throw new IllegalArgumentException("duplicate route id");
        var options =
                new JevExecution.Options(
                        mode,
                        Duration.ofMillis(boundedInteger(config, "budgetMillis", 2000, 1, 30000)),
                        config.path("version").asText("phase-routing-v1"),
                        this::observe);
        var configured =
                routes.stream()
                        .flatMap(r -> r.models().stream())
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var client = clients.get();
        var phase =
                new io.agentscope.extensions.judge.jev.routing.JevPhaseRouting(
                        client::systemOne,
                        routes,
                        configured,
                        threshold,
                        config.path("picksEffort").asBoolean(true),
                        options,
                        (ctx, record) -> observeRoutingCall(ctx, record, options));
        target.add(JevModelRouterMiddleware.builder(client).phaseRouting(phase).build());
    }

    private void observeRoutingCall(
            RuntimeContext ctx,
            io.agentscope.extensions.judge.jev.routing.JevPhaseRouting.CallRecord record,
            JevExecution.Options options) {
        var summary = new java.util.LinkedHashMap<String, String>();
        if (record.phase() != null) summary.put("phase", record.phase());
        if (record.suggestedModel() != null) summary.put("suggestedModel", record.suggestedModel());
        if (record.dispatchedModel() != null)
            summary.put("dispatchedModel", record.dispatchedModel());
        if (record.requestedEffort() != null)
            summary.put("requestedEffort", record.requestedEffort());
        if (record.usage() != null) {
            summary.put("inputTokens", Integer.toString(record.usage().getInputTokens()));
            summary.put("outputTokens", Integer.toString(record.usage().getOutputTokens()));
            summary.put("cachedTokens", Integer.toString(record.usage().getCachedTokens()));
            summary.put(
                    "cacheCreationTokens",
                    Integer.toString(record.usage().getCacheCreationTokens()));
        }
        if (record.estimatedUsd() != null)
            summary.put("estimatedUsd", Double.toString(record.estimatedUsd()));
        if (record.priceVersion() != null) summary.put("priceVersion", record.priceVersion());
        summary.put("callStatus", record.status());
        var status =
                record.status().equals("COMPLETED")
                        ? JevExecution.Status.DECIDED
                        : record.status().equals("CANCELLED")
                                ? JevExecution.Status.CANCELLED
                                : JevExecution.Status.ERROR;
        observe(
                ctx,
                new JevExecution.Record(
                        "routing-call",
                        options.version(),
                        options.mode(),
                        status,
                        record.reason(),
                        Duration.ofMillis(record.elapsedMillis()),
                        summary));
    }

    private static long boundedInteger(
            JsonNode config, String key, long fallback, long min, long max) {
        JsonNode value = config.path(key);
        if (value.isMissingNode()) return fallback;
        if (!value.isIntegralNumber()
                || !value.canConvertToLong()
                || value.asLong() < min
                || value.asLong() > max)
            throw new IllegalArgumentException("Invalid evaluation " + key);
        return value.asLong();
    }

    private static double requiredNumber(JsonNode config, String key) {
        if (!config.path(key).isNumber())
            throw new IllegalArgumentException("evaluation " + key + " required");
        return config.path(key).asDouble();
    }

    private void observeEvaluation(RuntimeContext ctx, JevTraceEvaluator.Report report) {
        for (var entry : report.results()) {
            var result = entry.result();
            var status =
                    switch (result.status()) {
                        case DECIDED -> JevExecution.Status.DECIDED;
                        case INCONCLUSIVE -> JevExecution.Status.INCONCLUSIVE;
                        case SKIPPED -> JevExecution.Status.SKIPPED;
                        case ERROR -> JevExecution.Status.ERROR;
                    };
            var summary = new java.util.LinkedHashMap<String, String>();
            summary.put("traceId", report.traceId());
            if (result.passed() != null) summary.put("passed", result.passed().toString());
            if (result.score() != null) summary.put("score", result.score().toString());
            if (result.label() != null) summary.put("label", result.label());
            observe(
                    ctx,
                    new JevExecution.Record(
                            "evaluation." + entry.id(),
                            entry.version(),
                            JevExecution.Mode.SHADOW,
                            status,
                            result.reason(),
                            report.elapsed(),
                            java.util.Map.copyOf(summary)));
        }
        long input = 0, output = 0;
        int known = 0;
        for (var usage : report.usage())
            if (usage.usage() != null
                    && usage.usage().inputTokens() >= 0
                    && usage.usage().outputTokens() >= 0) {
                input += usage.usage().inputTokens();
                output += usage.usage().outputTokens();
                known++;
            }
        observe(
                ctx,
                new JevExecution.Record(
                        "evaluation.usage",
                        "v1",
                        JevExecution.Mode.SHADOW,
                        JevExecution.Status.DECIDED,
                        "REQUEST_ACCOUNTING",
                        report.elapsed(),
                        java.util.Map.of(
                                "traceId",
                                report.traceId(),
                                "requests",
                                Integer.toString(report.requests()),
                                "responsesWithUsage",
                                Integer.toString(known),
                                "inputTokens",
                                Long.toString(input),
                                "outputTokens",
                                Long.toString(output))));
    }

    private static List<String> strings(JsonNode node) {
        if (!node.isArray()) throw new IllegalArgumentException("Expected string array");
        List<String> result = new ArrayList<>();
        node.forEach(
                n -> {
                    if (!n.isTextual() || n.asText().isBlank())
                        throw new IllegalArgumentException("Expected nonblank string");
                    result.add(n.asText());
                });
        return result;
    }

    private void observe(RuntimeContext ctx, JevExecution.Record record) {
        var sink = ctx == null ? null : ctx.get(TraceSink.class);
        if (sink == null) return;
        try {
            observations.execute(
                    () -> {
                        try {
                            sink.accept().accept(record);
                        } catch (RuntimeException e) {
                            org.slf4j.LoggerFactory.getLogger(JevServiceSupport.class)
                                    .warn("JEV observation persistence failed");
                        }
                    });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            org.slf4j.LoggerFactory.getLogger(JevServiceSupport.class)
                    .warn("JEV observation queue full or closed");
        }
    }

    @PreDestroy
    public void close() {
        observations.shutdown();
    }
}
