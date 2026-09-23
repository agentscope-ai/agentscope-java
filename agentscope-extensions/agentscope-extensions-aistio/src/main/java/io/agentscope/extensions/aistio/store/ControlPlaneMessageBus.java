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
package io.agentscope.extensions.aistio.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.extensions.aistio.transport.ControlPlaneHttpClient;
import io.agentscope.harness.agent.bus.BusEntry;
import io.agentscope.harness.agent.bus.MessageBus;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Control-plane hosted {@link MessageBus} over {@code /api/v1/dp/bus/*}.
 *
 * <p>Mode D ({@link #publish}/{@link #subscribe}) is implemented as a bounded replay log with a
 * 2s client-side poll.
 */
public final class ControlPlaneMessageBus implements MessageBus {

    private static final ObjectMapper MAPPER = ControlPlaneHttpClient.mapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final int PUBLISH_MAX_LEN = 100;
    private static final Duration SUBSCRIBE_POLL = Duration.ofSeconds(2);

    private final ControlPlaneHttpClient http;
    private final String agentName;
    private final String namespace;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    ControlPlaneMessageBus(ControlPlaneHttpClient http, String agentName, String namespace) {
        this.http = Objects.requireNonNull(http, "http");
        this.agentName = Objects.requireNonNull(agentName, "agentName");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
    }

    @Override
    public Mono<String> queuePush(String key, Map<String, Object> payload) {
        return Mono.fromCallable(
                        () -> {
                            ensureOpen();
                            Map<String, Object> body = tenantBody();
                            body.put("key", key);
                            body.put("payload", payload == null ? Map.of() : payload);
                            ControlPlaneHttpClient.Response resp =
                                    http.send("POST", "/api/v1/dp/bus/queue/push", body);
                            requireOk(resp, "queuePush");
                            return MAPPER.readTree(resp.body()).path("entryId").asText();
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<List<BusEntry>> queueDrain(String key, int maxCount) {
        return Mono.fromCallable(
                        () -> {
                            ensureOpen();
                            Map<String, Object> body = tenantBody();
                            body.put("key", key);
                            body.put("maxCount", maxCount);
                            ControlPlaneHttpClient.Response resp =
                                    http.send("POST", "/api/v1/dp/bus/queue/drain", body);
                            requireOk(resp, "queueDrain");
                            return parseEntries(resp.body());
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> queueDelete(String key) {
        return Mono.<Void>fromRunnable(
                        () -> {
                            try {
                                ensureOpen();
                                Map<String, Object> body = tenantBody();
                                body.put("key", key);
                                ControlPlaneHttpClient.Response resp =
                                        http.send("POST", "/api/v1/dp/bus/queue/delete", body);
                                if (resp.status() != 204
                                        && (resp.status() < 200 || resp.status() >= 300)) {
                                    throw new RuntimeException(
                                            "control-plane bus queueDelete failed: HTTP "
                                                    + resp.status()
                                                    + " "
                                                    + resp.body());
                                }
                            } catch (RuntimeException e) {
                                throw e;
                            } catch (Exception e) {
                                throw wrap(e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Boolean> queuePeek(String key) {
        return Mono.fromCallable(
                        () -> {
                            ensureOpen();
                            String path =
                                    "/api/v1/dp/bus/queue/peek?"
                                            + tenantQuery()
                                            + "&key="
                                            + enc(key);
                            ControlPlaneHttpClient.Response resp = http.send("GET", path, null);
                            requireOk(resp, "queuePeek");
                            return MAPPER.readTree(resp.body()).path("exists").asBoolean(false);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<String> logAppend(String key, Map<String, Object> payload, int maxLen) {
        return Mono.fromCallable(
                        () -> {
                            ensureOpen();
                            Map<String, Object> body = tenantBody();
                            body.put("key", key);
                            body.put("payload", payload == null ? Map.of() : payload);
                            body.put("maxLen", maxLen);
                            ControlPlaneHttpClient.Response resp =
                                    http.send("POST", "/api/v1/dp/bus/log/append", body);
                            requireOk(resp, "logAppend");
                            return MAPPER.readTree(resp.body()).path("entryId").asText();
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<List<BusEntry>> logRead(String key, String since, int maxCount) {
        return Mono.fromCallable(
                        () -> {
                            ensureOpen();
                            StringBuilder path =
                                    new StringBuilder("/api/v1/dp/bus/log/read?")
                                            .append(tenantQuery())
                                            .append("&key=")
                                            .append(enc(key))
                                            .append("&maxCount=")
                                            .append(maxCount);
                            if (since != null) {
                                path.append("&since=").append(enc(since));
                            }
                            ControlPlaneHttpClient.Response resp =
                                    http.send("GET", path.toString(), null);
                            requireOk(resp, "logRead");
                            return parseEntries(resp.body());
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> logTrim(String key) {
        return Mono.<Void>fromRunnable(
                        () -> {
                            try {
                                ensureOpen();
                                Map<String, Object> body = tenantBody();
                                body.put("key", key);
                                ControlPlaneHttpClient.Response resp =
                                        http.send("POST", "/api/v1/dp/bus/log/trim", body);
                                if (resp.status() != 204
                                        && (resp.status() < 200 || resp.status() >= 300)) {
                                    throw new RuntimeException(
                                            "control-plane bus logTrim failed: HTTP "
                                                    + resp.status()
                                                    + " "
                                                    + resp.body());
                                }
                            } catch (RuntimeException e) {
                                throw e;
                            } catch (Exception e) {
                                throw wrap(e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> publish(String key, Map<String, Object> payload) {
        return logAppend(key, payload, PUBLISH_MAX_LEN).then();
    }

    @Override
    public Flux<Map<String, Object>> subscribe(String key) {
        AtomicReference<String> cursor = new AtomicReference<>(null);
        return Flux.interval(SUBSCRIBE_POLL)
                .takeUntil(t -> closed.get())
                .concatMap(
                        tick ->
                                logRead(key, cursor.get(), 50)
                                        .flatMapMany(
                                                entries -> {
                                                    List<Map<String, Object>> payloads =
                                                            new ArrayList<>();
                                                    for (BusEntry e : entries) {
                                                        cursor.set(e.entryId());
                                                        payloads.add(e.payload());
                                                    }
                                                    return Flux.fromIterable(payloads);
                                                })
                                        .onErrorResume(err -> Flux.empty()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public void close() {
        closed.set(true);
    }

    private Map<String, Object> tenantBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agentName", agentName);
        body.put("namespace", namespace);
        return body;
    }

    private String tenantQuery() {
        return "agentName=" + enc(agentName) + "&namespace=" + enc(namespace);
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("ControlPlaneMessageBus is closed");
        }
    }

    private static List<BusEntry> parseEntries(String body) throws Exception {
        JsonNode root = MAPPER.readTree(body);
        JsonNode entries = root.path("entries");
        List<BusEntry> out = new ArrayList<>();
        if (entries.isArray()) {
            for (JsonNode n : entries) {
                String entryId = n.path("entryId").asText();
                Map<String, Object> payload = MAPPER.convertValue(n.path("payload"), MAP_TYPE);
                if (payload == null) {
                    payload = Map.of();
                }
                out.add(new BusEntry(entryId, payload));
            }
        }
        return out;
    }

    private static void requireOk(ControlPlaneHttpClient.Response resp, String op) {
        if (resp.status() >= 200 && resp.status() < 300) {
            return;
        }
        throw new RuntimeException(
                "control-plane bus " + op + " failed: HTTP " + resp.status() + " " + resp.body());
    }

    private static RuntimeException wrap(Exception e) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        return new RuntimeException("control-plane bus request failed: " + e.getMessage(), e);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
