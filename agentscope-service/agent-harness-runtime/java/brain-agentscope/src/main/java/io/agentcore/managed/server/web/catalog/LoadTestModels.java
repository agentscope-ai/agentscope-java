/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.web.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.loadtest.LoadTestModel;
import io.agentcore.managed.server.runtime.loadtest.LoadTestModel.Profile;
import io.agentscope.core.model.Model;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Operator-controlled models selected only by an explicit loadtest: name. Disabled by default. */
@Component
public final class LoadTestModels {
    private final Map<String, LoadTestModel> models;

    @Autowired
    public LoadTestModels(Environment environment, ObjectProvider<MeterRegistry> registry) {
        this(
                environment.getProperty(
                        "managed-agents.runtime.load-test-model.enabled", Boolean.class, false),
                environment.getProperty(
                        "managed-agents.runtime.load-test-model.profiles-json", "{}"),
                registry.getIfAvailable());
    }

    public LoadTestModels(boolean enabled, String profilesJson, MeterRegistry registry) {
        var configured = new LinkedHashMap<String, LoadTestModel>();
        if (enabled) {
            if (profilesJson.length() > 32768)
                throw new IllegalArgumentException("Load-test profiles too large");
            try {
                var json = new ObjectMapper();
                var profiles = json.readTree(profilesJson);
                if (!profiles.isObject() || profiles.isEmpty() || profiles.size() > 16)
                    throw new IllegalArgumentException(
                            "Enable load-test models with 1..16 named profiles");
                profiles.fields()
                        .forEachRemaining(
                                entry -> {
                                    var v = entry.getValue();
                                    if (!v.isObject())
                                        throw new IllegalArgumentException(
                                                "Profile must be an object");
                                    Map<String, Object> arguments =
                                            v.has("toolArguments")
                                                    ? json.convertValue(
                                                            v.get("toolArguments"), Map.class)
                                                    : Map.of();
                                    var profile =
                                            new Profile(
                                                    v.path("firstChunkDelayMs").asLong(300),
                                                    v.path("chunkIntervalMs").asLong(50),
                                                    v.path("chunks").asInt(40),
                                                    v.path("chunkChars").asInt(80),
                                                    v.path("toolName").asText(""),
                                                    arguments,
                                                    v.path("toolRounds").asInt(0),
                                                    v.path("parallelToolCalls").asInt(1));
                                    String name = "loadtest:" + entry.getKey();
                                    var model = new LoadTestModel(name, profile);
                                    configured.put(name, model);
                                    if (registry != null) {
                                        var stats = model.stats();
                                        gauge(registry, name, "requests", stats.requests);
                                        gauge(registry, name, "active", stats.active);
                                        gauge(registry, name, "completed", stats.completed);
                                        gauge(registry, name, "cancelled", stats.cancelled);
                                        gauge(registry, name, "errors", stats.errors);
                                        gauge(registry, name, "chunks", stats.chunks);
                                    }
                                });
            } catch (Exception invalid) {
                throw new IllegalArgumentException(
                        "Invalid operator load-test model profiles", invalid);
            }
        }
        models = Map.copyOf(configured);
    }

    public Model resolve(String name) {
        var model = models.get(name);
        if (model == null)
            throw new IllegalArgumentException(
                    "Load-test model is disabled or not configured: " + name);
        return model;
    }

    private static void gauge(
            MeterRegistry registry, String model, String metric, AtomicLong value) {
        Gauge.builder("managed.agents.loadtest.model." + metric, value, AtomicLong::doubleValue)
                .tag("model", model)
                .description("Synthetic model only; counters do not represent provider usage")
                .strongReference(true)
                .register(registry);
    }
}
