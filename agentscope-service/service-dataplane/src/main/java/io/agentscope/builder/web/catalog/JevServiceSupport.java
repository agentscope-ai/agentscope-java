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
import io.agentscope.extensions.judge.jev.example.JevAutoModeMiddleware;
import io.agentscope.extensions.judge.jev.example.JevModelRouterMiddleware;
import io.agentscope.extensions.judge.jev.example.JevToolSelectionMiddleware;
import io.agentscope.extensions.judge.jev.integration.JevResponseMiddleware;
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
public class JevServiceSupport {
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
                            if (!Set.of("tools", "guard", "routing", "content", "quality")
                                    .contains(k))
                                throw new IllegalArgumentException("Unknown JEV purpose");
                        });
        List<MiddlewareBase> result = new ArrayList<>();
        var response = JevResponseMiddleware.builder();
        boolean responseEnabled = false;
        // Construct the client only when a purpose is explicitly enabled.
        for (String purpose : List.of("tools", "guard", "routing", "content", "quality")) {
            JsonNode c = config.path(purpose);
            if (c.isMissingNode()) continue;
            if (!c.isObject()) throw new IllegalArgumentException("JEV purpose must be an object");
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
