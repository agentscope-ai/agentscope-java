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
package io.agentscope.harness.agent.subagent.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal HTTP client for the internal AgentScope task protocol ({@code POST/GET /tasks/...}).
 *
 * <p>The client-supplied {@code taskId} is used as the remote task identifier (no separate run id).
 */
public final class AgentProtocolTaskClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;

    public AgentProtocolTaskClient() {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(30))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build());
    }

    public AgentProtocolTaskClient(HttpClient http) {
        this.http = Objects.requireNonNull(http, "http");
    }

    /** {@code POST /tasks} with body {@code {task_id, agent_id, input}}. */
    public void submitTask(
            String baseUrl,
            Map<String, String> headers,
            String taskId,
            String agentId,
            String input)
            throws IOException, InterruptedException {
        String body =
                JSON.writeValueAsString(
                        JSON.createObjectNode()
                                .put("task_id", taskId)
                                .put("agent_id", agentId)
                                .put("input", input != null ? input : ""));
        HttpRequest.Builder b =
                HttpRequest.newBuilder()
                        .uri(URI.create(join(baseUrl, "/tasks")))
                        .timeout(Duration.ofMinutes(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        applyHeaders(b, headers);
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new IOException(
                    "submitTask failed: HTTP " + resp.statusCode() + " body=" + resp.body());
        }
    }

    /** {@code GET /tasks/{taskId}}. */
    public RemoteTaskStatus getStatus(String baseUrl, Map<String, String> headers, String taskId)
            throws IOException, InterruptedException {
        HttpRequest.Builder b =
                HttpRequest.newBuilder()
                        .uri(URI.create(join(baseUrl, "/tasks/" + encode(taskId))))
                        .timeout(Duration.ofMinutes(2))
                        .GET();
        applyHeaders(b, headers);
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 404) {
            return new RemoteTaskStatus("error", "task not found");
        }
        if (resp.statusCode() >= 400) {
            return new RemoteTaskStatus("error", "HTTP " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode n = JSON.readTree(resp.body());
        String st = textOrEmpty(n, "status");
        String err = n.hasNonNull("error") ? n.get("error").asText() : null;
        return new RemoteTaskStatus(st, err);
    }

    /**
     * {@code GET /tasks/{taskId}/wait?timeout_seconds=<n>} — blocks until the server completes
     * the task. The HTTP read timeout is set to {@code timeoutSeconds + 60} seconds to give the
     * server time to respond.
     */
    public String waitForResult(
            String baseUrl, Map<String, String> headers, String taskId, long timeoutSeconds)
            throws IOException, InterruptedException {
        String path =
                "/tasks/" + encode(taskId) + "/wait?timeout_seconds=" + Math.max(1, timeoutSeconds);
        HttpRequest.Builder b =
                HttpRequest.newBuilder()
                        .uri(URI.create(join(baseUrl, path)))
                        .timeout(Duration.ofSeconds(timeoutSeconds + 60))
                        .GET();
        applyHeaders(b, headers);
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new IOException(
                    "waitForResult failed: HTTP " + resp.statusCode() + " body=" + resp.body());
        }
        JsonNode n = JSON.readTree(resp.body());
        if (n.hasNonNull("result")) {
            return n.get("result").asText();
        }
        return "";
    }

    /** {@code POST /tasks/{taskId}/cancel}. */
    public void cancelTask(String baseUrl, Map<String, String> headers, String taskId)
            throws IOException, InterruptedException {
        HttpRequest.Builder b =
                HttpRequest.newBuilder()
                        .uri(URI.create(join(baseUrl, "/tasks/" + encode(taskId) + "/cancel")))
                        .timeout(Duration.ofMinutes(2))
                        .POST(HttpRequest.BodyPublishers.noBody());
        applyHeaders(b, headers);
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new IOException(
                    "cancelTask failed: HTTP " + resp.statusCode() + " body=" + resp.body());
        }
    }

    private static void applyHeaders(HttpRequest.Builder b, Map<String, String> headers) {
        if (headers == null) {
            return;
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                b.header(e.getKey(), e.getValue());
            }
        }
    }

    private static String join(String baseUrl, String path) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + path;
    }

    private static String encode(String taskId) {
        return URLEncoder.encode(taskId, StandardCharsets.UTF_8);
    }

    private static String textOrEmpty(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asText() : "";
    }
}
