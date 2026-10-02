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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.harness.agent.subagent.protocol.RemoteAgentEvent;
import io.agentscope.harness.agent.subagent.protocol.RemoteConfirmDecision;
import io.agentscope.harness.agent.subagent.protocol.RemotePendingConfirm;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal HTTP client for the internal AgentScope task protocol ({@code POST/GET /tasks/...}).
 *
 * <p>The client-supplied {@code taskId} is used as the remote task identifier (no separate run id).
 */
public final class AgentProtocolTaskClient {

    private static final Logger log = LoggerFactory.getLogger(AgentProtocolTaskClient.class);

    /**
     * Unknown {@code type} values deserialize to {@code null} instead of throwing, so a newer
     * server introducing a wire event type degrades to "this client ignores it" rather than
     * dropping the event as unparseable — and an event carrying a {@code payload} still decodes.
     */
    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true);

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

    /** {@code POST /tasks} with body {@code {task_id, agent_id, input}} (+ optional context). */
    public void submitTask(
            String baseUrl,
            Map<String, String> headers,
            String taskId,
            String agentId,
            String input)
            throws IOException, InterruptedException {
        submitTask(baseUrl, headers, taskId, agentId, input, null);
    }

    /**
     * {@code POST /tasks} with body {@code {task_id, agent_id, input, context?}}.
     *
     * <p>Legacy servers ignore unknown {@code context} field.
     */
    public void submitTask(
            String baseUrl,
            Map<String, String> headers,
            String taskId,
            String agentId,
            String input,
            RemoteSubmitContext context)
            throws IOException, InterruptedException {
        ObjectNode body =
                JSON.createObjectNode()
                        .put("task_id", taskId)
                        .put("agent_id", agentId)
                        .put("input", input != null ? input : "");
        if (context != null) {
            body.set("context", JSON.valueToTree(context.toMap()));
        }
        HttpRequest.Builder b =
                HttpRequest.newBuilder()
                        .uri(URI.create(join(baseUrl, "/tasks")))
                        .timeout(Duration.ofMinutes(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
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
        List<RemotePendingConfirm> pending = parsePendingConfirms(n.get("pending_confirms"));
        return new RemoteTaskStatus(st, err, pending);
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

    /**
     * {@code POST /tasks/{taskId}/resume} with body {@code {decisions: [...]}}.
     *
     * @throws IOException on HTTP error (including 404 when the server lacks HITL support)
     */
    public void resumeTask(
            String baseUrl,
            Map<String, String> headers,
            String taskId,
            List<RemoteConfirmDecision> decisions)
            throws IOException, InterruptedException {
        ObjectNode body = JSON.createObjectNode();
        ArrayNode arr = body.putArray("decisions");
        if (decisions != null) {
            for (RemoteConfirmDecision d : decisions) {
                arr.add(
                        JSON.createObjectNode()
                                .put("toolCallId", d.getToolCallId())
                                .put("approved", d.isApproved()));
            }
        }
        HttpRequest.Builder b =
                HttpRequest.newBuilder()
                        .uri(URI.create(join(baseUrl, "/tasks/" + encode(taskId) + "/resume")))
                        .timeout(Duration.ofMinutes(2))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        applyHeaders(b, headers);
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new IOException(
                    "resumeTask failed: HTTP " + resp.statusCode() + " body=" + resp.body());
        }
    }

    /**
     * Opens {@code GET /tasks/{taskId}/events} as an SSE stream and delivers parsed events to {@code
     * consumer}. Silently no-ops (returns a closed handle) when the server returns 404.
     */
    public Closeable openEventStream(
            String baseUrl,
            Map<String, String> headers,
            String taskId,
            long fromSeq,
            Consumer<RemoteAgentEvent> consumer)
            throws IOException, InterruptedException {
        Objects.requireNonNull(consumer, "consumer");
        String path = "/tasks/" + encode(taskId) + "/events";
        if (fromSeq > 0) {
            path = path + "?from_seq=" + fromSeq;
        }
        HttpRequest.Builder b =
                HttpRequest.newBuilder()
                        .uri(URI.create(join(baseUrl, path)))
                        .timeout(Duration.ofHours(3))
                        .header("Accept", "text/event-stream")
                        .GET();
        if (fromSeq > 0) {
            b.header("Last-Event-ID", String.valueOf(fromSeq));
        }
        applyHeaders(b, headers);

        AtomicBoolean closed = new AtomicBoolean(false);
        CompletableFuture<HttpResponse<InputStream>> future =
                http.sendAsync(b.build(), HttpResponse.BodyHandlers.ofInputStream());

        HttpResponse<InputStream> resp;
        try {
            resp = future.join();
        } catch (Exception e) {
            throw new IOException("openEventStream failed to connect", e);
        }
        if (resp.statusCode() == 404 || resp.statusCode() == 405) {
            try {
                resp.body().close();
            } catch (IOException ignored) {
            }
            log.debug("Remote server does not support /events for task {}", taskId);
            return () -> {};
        }
        if (resp.statusCode() >= 400) {
            String body;
            try (InputStream in = resp.body()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            throw new IOException(
                    "openEventStream failed: HTTP " + resp.statusCode() + " body=" + body);
        }

        InputStream bodyStream = resp.body();
        Thread reader =
                new Thread(
                        () -> readSse(bodyStream, consumer, closed),
                        "agent-protocol-sse-" + taskId);
        reader.setDaemon(true);
        reader.start();

        return () -> {
            if (closed.compareAndSet(false, true)) {
                try {
                    bodyStream.close();
                } catch (IOException ignored) {
                }
            }
        };
    }

    private static void readSse(
            InputStream bodyStream, Consumer<RemoteAgentEvent> consumer, AtomicBoolean closed) {
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(bodyStream, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String id = null;
            String line;
            while (!closed.get() && (line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (data.length() > 0) {
                        dispatchSseEvent(data.toString(), id, consumer);
                        data.setLength(0);
                        id = null;
                    }
                    continue;
                }
                if (line.startsWith(":")) {
                    continue;
                }
                if (line.startsWith("id:")) {
                    id = line.substring(3).trim();
                } else if (line.startsWith("data:")) {
                    String payload = line.substring(5);
                    if (payload.startsWith(" ")) {
                        payload = payload.substring(1);
                    }
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    data.append(payload);
                }
            }
            if (data.length() > 0) {
                dispatchSseEvent(data.toString(), id, consumer);
            }
        } catch (IOException e) {
            if (!closed.get()) {
                log.debug("SSE stream closed: {}", e.toString());
            }
        }
    }

    private static void dispatchSseEvent(
            String data, String id, Consumer<RemoteAgentEvent> consumer) {
        try {
            RemoteAgentEvent event = JSON.readValue(data, RemoteAgentEvent.class);
            if (id != null && !id.isBlank() && event.getSeq() <= 0) {
                try {
                    event.setSeq(Long.parseLong(id.trim()));
                } catch (NumberFormatException ignored) {
                }
            }
            consumer.accept(event);
        } catch (Exception e) {
            log.debug("Skipping unparseable SSE data: {}", e.toString());
        }
    }

    private static List<RemotePendingConfirm> parsePendingConfirms(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<RemotePendingConfirm> out = new ArrayList<>();
        for (JsonNode item : node) {
            RemotePendingConfirm p = new RemotePendingConfirm();
            if (item.hasNonNull("toolCallId")) {
                p.setToolCallId(item.get("toolCallId").asText());
            } else if (item.hasNonNull("tool_call_id")) {
                p.setToolCallId(item.get("tool_call_id").asText());
            }
            if (item.hasNonNull("toolName")) {
                p.setToolName(item.get("toolName").asText());
            } else if (item.hasNonNull("tool_name")) {
                p.setToolName(item.get("tool_name").asText());
            }
            if (item.hasNonNull("toolInputJson")) {
                p.setToolInputJson(item.get("toolInputJson").asText());
            } else if (item.hasNonNull("tool_input_json")) {
                p.setToolInputJson(item.get("tool_input_json").asText());
            }
            out.add(p);
        }
        return List.copyOf(out);
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
