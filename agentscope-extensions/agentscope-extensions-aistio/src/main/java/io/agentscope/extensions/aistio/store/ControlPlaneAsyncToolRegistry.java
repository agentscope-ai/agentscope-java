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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.extensions.aistio.transport.ControlPlaneHttpClient;
import io.agentscope.harness.agent.bus.AsyncToolRecord;
import io.agentscope.harness.agent.bus.AsyncToolRegistry;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Control-plane hosted {@link AsyncToolRegistry} over {@code /api/v1/dp/async-tools/*}.
 */
public final class ControlPlaneAsyncToolRegistry implements AsyncToolRegistry {

    private static final ObjectMapper MAPPER = ControlPlaneHttpClient.mapper();

    private final ControlPlaneHttpClient http;
    private final String agentName;
    private final String namespace;

    ControlPlaneAsyncToolRegistry(ControlPlaneHttpClient http, String agentName, String namespace) {
        this.http = Objects.requireNonNull(http, "http");
        this.agentName = Objects.requireNonNull(agentName, "agentName");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
    }

    @Override
    public Mono<Void> register(AsyncToolRecord record) {
        return Mono.<Void>fromRunnable(
                        () -> {
                            try {
                                Objects.requireNonNull(record, "record");
                                Map<String, Object> body = tenantBody();
                                body.put("id", record.id());
                                body.put("sessionId", record.sessionId());
                                body.put("toolName", record.toolName());
                                body.put("toolCallId", record.toolCallId());
                                body.put("status", record.status());
                                if (record.createdAt() != null) {
                                    body.put("createdAt", record.createdAt().toString());
                                }
                                ControlPlaneHttpClient.Response resp =
                                        http.send("POST", "/api/v1/dp/async-tools", body);
                                requireOk(resp, "register");
                            } catch (RuntimeException e) {
                                throw e;
                            } catch (Exception e) {
                                throw wrap(e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> complete(String id, String result) {
        return Mono.<Void>fromRunnable(
                        () -> {
                            try {
                                Map<String, Object> body = tenantBody();
                                body.put("result", result == null ? "" : result);
                                ControlPlaneHttpClient.Response resp =
                                        http.send(
                                                "POST",
                                                "/api/v1/dp/async-tools/"
                                                        + encPath(id)
                                                        + "/complete",
                                                body);
                                requireOk(resp, "complete");
                            } catch (RuntimeException e) {
                                throw e;
                            } catch (Exception e) {
                                throw wrap(e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> fail(String id, String error) {
        return Mono.<Void>fromRunnable(
                        () -> {
                            try {
                                Map<String, Object> body = tenantBody();
                                body.put("error", error == null ? "" : error);
                                ControlPlaneHttpClient.Response resp =
                                        http.send(
                                                "POST",
                                                "/api/v1/dp/async-tools/" + encPath(id) + "/fail",
                                                body);
                                requireOk(resp, "fail");
                            } catch (RuntimeException e) {
                                throw e;
                            } catch (Exception e) {
                                throw wrap(e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<List<AsyncToolRecord>> findStale(String sessionId, Duration ttl) {
        return Mono.fromCallable(
                        () -> {
                            long ttlSeconds = ttl == null ? 0L : Math.max(0L, ttl.toSeconds());
                            String path =
                                    "/api/v1/dp/async-tools/stale?"
                                            + "agentName="
                                            + enc(agentName)
                                            + "&namespace="
                                            + enc(namespace)
                                            + "&sessionId="
                                            + enc(sessionId)
                                            + "&ttlSeconds="
                                            + ttlSeconds;
                            ControlPlaneHttpClient.Response resp = http.send("GET", path, null);
                            requireOk(resp, "findStale");
                            JsonNode root = MAPPER.readTree(resp.body());
                            JsonNode records = root.path("records");
                            List<AsyncToolRecord> out = new ArrayList<>();
                            if (records.isArray()) {
                                for (JsonNode n : records) {
                                    out.add(parseRecord(n));
                                }
                            }
                            return out;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> markTimeout(String id) {
        return Mono.<Void>fromRunnable(
                        () -> {
                            try {
                                Map<String, Object> body = tenantBody();
                                ControlPlaneHttpClient.Response resp =
                                        http.send(
                                                "POST",
                                                "/api/v1/dp/async-tools/"
                                                        + encPath(id)
                                                        + "/timeout",
                                                body);
                                requireOk(resp, "markTimeout");
                            } catch (RuntimeException e) {
                                throw e;
                            } catch (Exception e) {
                                throw wrap(e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, Object> tenantBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agentName", agentName);
        body.put("namespace", namespace);
        return body;
    }

    private static AsyncToolRecord parseRecord(JsonNode n) {
        String id = text(n, "id", "recordId");
        String sessionId = n.path("sessionId").asText();
        String toolName = n.path("toolName").asText(null);
        String toolCallId = n.path("toolCallId").asText(null);
        String status = n.path("status").asText(AsyncToolRecord.RUNNING);
        Instant createdAt = null;
        if (n.hasNonNull("createdAt")) {
            createdAt = Instant.parse(n.get("createdAt").asText());
        }
        return new AsyncToolRecord(id, sessionId, toolName, toolCallId, status, createdAt);
    }

    private static String text(JsonNode n, String primary, String fallback) {
        if (n.hasNonNull(primary)) {
            return n.get(primary).asText();
        }
        return n.path(fallback).asText();
    }

    private static void requireOk(ControlPlaneHttpClient.Response resp, String op) {
        if (resp.status() >= 200 && resp.status() < 300) {
            return;
        }
        throw new RuntimeException(
                "control-plane async-tools "
                        + op
                        + " failed: HTTP "
                        + resp.status()
                        + " "
                        + resp.body());
    }

    private static RuntimeException wrap(Exception e) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        return new RuntimeException(
                "control-plane async-tools request failed: " + e.getMessage(), e);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String encPath(String id) {
        return enc(id).replace("+", "%20");
    }
}
