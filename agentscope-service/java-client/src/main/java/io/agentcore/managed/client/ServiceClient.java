/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Blocking Java 17 client for the public Agent Service API. Use an application thread, never a reactive
 * event-loop thread. Persist each applied event cursor and reconnect explicitly after a timeout.
 */
public final class ServiceClient {
    private static final int JSON_LIMIT = 16 << 20;
    private static final int ERROR_LIMIT = 1 << 20;
    private static final int CONTENT_LIMIT = 100 << 20;
    private static final int UPLOAD_LIMIT = 16 << 20;
    private static final String ROOT = "/api/v1/agent-sessions";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> COMMANDS =
            Set.of("actions", "steer", "cancel", "resume", "tool-recovery", "workspace-recovery");
    private static final ScheduledThreadPoolExecutor DEADLINES = deadlines();
    private final String base;
    private final Map<String, String> credentials;
    private final Duration timeout;
    private final HttpClient http;

    private ServiceClient(Builder builder) {
        URI uri = URI.create(builder.baseUrl);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null)
            throw new IllegalArgumentException(
                    "baseUrl must be an HTTP(S) URL without credentials, query or fragment");
        if (!builder.apiKey.isBlank() && !builder.apiToken.isBlank())
            throw new IllegalArgumentException("Provide apiKey or apiToken, not both");
        if (builder.timeout.toMillis() < 1)
            throw new IllegalArgumentException("timeout must be at least one millisecond");
        base = builder.baseUrl.replaceAll("/+$", "");
        var headers = new LinkedHashMap<String, String>();
        if (!builder.apiKey.isBlank()) headers.put("X-API-Key", builder.apiKey);
        if (!builder.apiToken.isBlank()) headers.put("Authorization", "Bearer " + builder.apiToken);
        if (!builder.tenant.isBlank()) headers.put("X-Managed-Agents-Tenant", builder.tenant);
        if (!builder.namespace.isBlank())
            headers.put("X-Managed-Agents-Namespace", builder.namespace);
        credentials = Map.copyOf(headers);
        timeout = builder.timeout;
        http =
                HttpClient.newBuilder()
                        .connectTimeout(timeout)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
    }

    public static Builder builder(String baseUrl) {
        return new Builder(baseUrl);
    }

    public static final class Builder {
        private final String baseUrl;
        private String apiKey = "", apiToken = "", tenant = "", namespace = "";
        private Duration timeout = Duration.ofSeconds(30);

        private Builder(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public Builder apiKey(String value) {
            apiKey = value;
            return this;
        }

        public Builder apiToken(String value) {
            apiToken = value;
            return this;
        }

        public Builder tenant(String value) {
            tenant = value;
            return this;
        }

        public Builder namespace(String value) {
            namespace = value;
            return this;
        }

        public Builder timeout(Duration value) {
            timeout = value;
            return this;
        }

        public ServiceClient build() {
            return new ServiceClient(this);
        }
    }

    /** Managed resources and execution use the MQ / Redis protocol at /v1/sessions. */
    public ManagedSessionClient managedSessions() {
        return new ManagedSessionClient(this);
    }

    public JsonNode createSession(JsonNode target, String idempotencyKey, ObjectNode options)
            throws IOException, InterruptedException {
        ObjectNode body = options == null ? MAPPER.createObjectNode() : options.deepCopy();
        body.set("target", target);
        return request(ROOT, "POST", body, key(idempotencyKey));
    }

    public JsonNode session(String session) throws IOException, InterruptedException {
        return request(sessionPath(session), "GET", null, null);
    }

    /** Sensitive native history: enabled only for authorized human trace callers. */
    public JsonNode trace(String session, long after, Long through, int limit)
            throws IOException, InterruptedException {
        if (after < 0 || through != null && through < after || limit < 1 || limit > 100)
            throw new IllegalArgumentException("Invalid trace window");
        return request(
                sessionPath(session)
                        + "/trace"
                        + query("after", after, "through", through, "limit", limit),
                "GET",
                null,
                null);
    }

    public JsonNode checkpoints(String session, String after, String through, int limit)
            throws IOException, InterruptedException {
        if (limit < 1 || limit > 100)
            throw new IllegalArgumentException("Invalid checkpoint page limit");
        return request(
                sessionPath(session)
                        + "/checkpoints"
                        + query("after", after, "through", through, "limit", limit),
                "GET",
                null,
                null);
    }

    public JsonNode restoreCheckpoint(String session, String checkpoint, String idempotencyKey)
            throws IOException, InterruptedException {
        return request(
                sessionPath(session) + "/checkpoints/restore",
                "POST",
                MAPPER.createObjectNode().put("checkpoint_id", checkpoint),
                idempotencyKey);
    }

    public JsonNode fork(String session, String target, String checkpoint, String idempotencyKey)
            throws IOException, InterruptedException {
        return request(
                sessionPath(session) + "/fork",
                "POST",
                MAPPER.createObjectNode()
                        .put("target_session_id", target)
                        .put("checkpoint_id", checkpoint),
                idempotencyKey);
    }

    public JsonNode checkpointOperation(String session, String operation)
            throws IOException, InterruptedException {
        var receipt =
                request(
                        sessionPath(session) + "/checkpoint-operations/" + segment(operation),
                        "GET",
                        null,
                        null);
        if (!session.equals(receipt.path("source_session_id").asText())
                || !operation.equals(receipt.path("operation_id").asText()))
            throw new IOException("Checkpoint operation identity differs");
        return receipt;
    }

    public JsonNode cancelCheckpointOperation(String session, String operation)
            throws IOException, InterruptedException {
        return request(
                sessionPath(session) + "/checkpoint-operations/" + segment(operation) + "/cancel",
                "POST",
                null,
                null);
    }

    public JsonNode budget(String session) throws IOException, InterruptedException {
        return request(sessionPath(session) + "/budget", "GET", null, null);
    }

    /** Settings apply before the next model call; pass the current revision to prevent lost updates. */
    public JsonNode setBudget(String session, JsonNode limits, Long revision)
            throws IOException, InterruptedException {
        if (limits == null || !limits.isObject() || revision != null && revision < 1)
            throw new IllegalArgumentException(
                    "Budget limits and a positive revision are required");
        return request(
                sessionPath(session) + "/budget",
                "PUT",
                limits,
                null,
                revision == null ? Map.of() : Map.of("If-Match", Long.toString(revision)));
    }

    public JsonNode sessions(String after, int limit, String status)
            throws IOException, InterruptedException {
        return request(
                ROOT + query("after", after, "limit", pageLimit(limit), "status", status),
                "GET",
                null,
                null);
    }

    /** The request contains either message or input, using the Service Turn schema. */
    public JsonNode submit(String session, JsonNode input, String idempotencyKey)
            throws IOException, InterruptedException {
        return request(sessionPath(session) + "/turns", "POST", input, key(idempotencyKey));
    }

    public JsonNode submitMessage(String session, String message, String idempotencyKey)
            throws IOException, InterruptedException {
        return submit(session, MAPPER.createObjectNode().put("message", message), idempotencyKey);
    }

    public JsonNode turn(String session, String turn) throws IOException, InterruptedException {
        return request(turnPath(session, turn), "GET", null, null);
    }

    public JsonNode turns(String session, String after, int limit, String order)
            throws IOException, InterruptedException {
        return request(
                sessionPath(session)
                        + "/turns"
                        + query("after", after, "limit", pageLimit(limit), "order", order),
                "GET",
                null,
                null);
    }

    public JsonNode capabilities(String session) throws IOException, InterruptedException {
        JsonNode value = request(sessionPath(session) + "/capabilities", "GET", null, null);
        for (String field : List.of("files", "file_input", "artifacts"))
            if (!value.path("capabilities").path(field).isBoolean()) throw schema("capabilities");
        return value;
    }

    public JsonNode turnCapabilities(String session, String turn)
            throws IOException, InterruptedException {
        return request(turnPath(session, turn) + "/capabilities", "GET", null, null);
    }

    public JsonNode snapshot(String session, String turn, int limit)
            throws IOException, InterruptedException {
        JsonNode value =
                request(
                        scopePath(session, turn) + "/snapshot" + query("limit", pageLimit(limit)),
                        "GET",
                        null,
                        null);
        if (!value.path("snapshot_id").isTextual()
                || value.path("snapshot_id").asText().isBlank()
                || !value.path("as_of").isTextual()
                || !value.path("resources").isObject()) throw schema("snapshot");
        return value;
    }

    public JsonNode resources(
            String session, String turn, String kind, String after, String snapshotId, int limit)
            throws IOException, InterruptedException {
        return request(
                scopePath(session, turn)
                        + "/resources/"
                        + segment(kind)
                        + query(
                                "after",
                                after,
                                "snapshot_id",
                                snapshotId,
                                "limit",
                                pageLimit(limit)),
                "GET",
                null,
                null);
    }

    public JsonNode events(String session, String turn, String after, int limit)
            throws IOException, InterruptedException {
        return request(
                scopePath(session, turn)
                        + "/events"
                        + query("after", after, "limit", pageLimit(limit)),
                "GET",
                null,
                null);
    }

    public JsonNode command(
            String session, String turn, String kind, JsonNode payload, String idempotencyKey)
            throws IOException, InterruptedException {
        if (!COMMANDS.contains(kind))
            throw new IllegalArgumentException("Unsupported command kind");
        if ("actions".equals(kind) && (payload == null || !payload.path("answers").isArray()))
            throw new IllegalArgumentException("actions requires an answers list");
        return request(
                turnPath(session, turn) + "/" + kind,
                "POST",
                payload == null ? MAPPER.createObjectNode() : payload,
                key(idempotencyKey));
    }

    public JsonNode commandStatus(String session, String turn, String command)
            throws IOException, InterruptedException {
        JsonNode value =
                request(sessionPath(session) + "/commands/" + segment(command), "GET", null, null);
        if (!turn.equals(value.path("turn_id").asText()))
            throw new IOException("Command does not belong to this Turn");
        return value;
    }

    public JsonNode inject(String session, JsonNode input, String idempotencyKey)
            throws IOException, InterruptedException {
        return request(sessionPath(session) + "/inputs/inject", "POST", input, key(idempotencyKey));
    }

    public JsonNode usage(String session, String turn) throws IOException, InterruptedException {
        return request(scopePath(session, turn) + "/usage", "GET", null, null).path("data");
    }

    public JsonNode lifecycle(String session, String kind)
            throws IOException, InterruptedException {
        if (!Set.of("archive", "restore", "delete").contains(kind))
            throw new IllegalArgumentException("Unsupported lifecycle kind");
        return request(
                sessionPath(session) + ("delete".equals(kind) ? "" : "/" + kind),
                "delete".equals(kind) ? "DELETE" : "POST",
                "delete".equals(kind) ? null : MAPPER.createObjectNode(),
                null);
    }

    public JsonNode lifecycleCommand(String session, String command)
            throws IOException, InterruptedException {
        return request(
                sessionPath(session) + "/lifecycle-commands/" + segment(command),
                "GET",
                null,
                null);
    }

    public JsonNode files(String session, int offset, int limit)
            throws IOException, InterruptedException {
        if (offset < 0) throw new IllegalArgumentException("offset must be nonnegative");
        return request(
                sessionPath(session)
                        + "/files"
                        + query("offset", offset, "limit", pageLimit(limit)),
                "GET",
                null,
                null);
    }

    public JsonNode uploadFile(
            String session, String name, byte[] data, String contentType, String idempotencyKey)
            throws IOException, InterruptedException {
        return upload(sessionPath(session) + "/files", name, data, contentType, idempotencyKey);
    }

    JsonNode upload(
            String path, String name, byte[] data, String contentType, String idempotencyKey)
            throws IOException, InterruptedException {
        if (data.length > UPLOAD_LIMIT)
            throw new IllegalArgumentException("Upload exceeds the 16 MiB client limit");
        HttpRequest request =
                requestBuilder(path, key(idempotencyKey))
                        .header("Content-Type", contentType)
                        .header("X-File-Name", encode(name))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(data))
                        .build();
        return json(exchange(request, JSON_LIMIT));
    }

    public JsonNode file(String session, String file) throws IOException, InterruptedException {
        return request(sessionPath(session) + "/files/" + segment(file), "GET", null, null);
    }

    public byte[] fileContent(String session, String file)
            throws IOException, InterruptedException {
        return content(sessionPath(session) + "/files/" + segment(file) + "/content");
    }

    public JsonNode artifacts(
            String session, String turn, String after, String snapshotId, int limit)
            throws IOException, InterruptedException {
        return request(
                scopePath(session, turn)
                        + "/artifacts"
                        + query(
                                "after",
                                after,
                                "snapshot_id",
                                snapshotId,
                                "limit",
                                pageLimit(limit)),
                "GET",
                null,
                null);
    }

    public JsonNode artifact(String session, String turn, String artifact, String snapshotId)
            throws IOException, InterruptedException {
        return request(
                scopePath(session, turn)
                        + "/artifacts/"
                        + segment(artifact)
                        + query("snapshot_id", snapshotId),
                "GET",
                null,
                null);
    }

    public byte[] artifactContent(String session, String turn, String artifact)
            throws IOException, InterruptedException {
        return content(scopePath(session, turn) + "/artifacts/" + segment(artifact) + "/content");
    }

    public JsonNode publishArtifact(String session, JsonNode artifact, String idempotencyKey)
            throws IOException, InterruptedException {
        return request(sessionPath(session) + "/artifacts", "POST", artifact, key(idempotencyKey));
    }

    public JsonNode deleteArtifact(String session, String artifact)
            throws IOException, InterruptedException {
        return request(
                sessionPath(session) + "/artifacts/" + segment(artifact), "DELETE", null, null);
    }

    public JsonNode webhook(
            String session, String url, List<String> eventTypes, String idempotencyKey)
            throws IOException, InterruptedException {
        ObjectNode body = MAPPER.createObjectNode().put("url", url);
        if (eventTypes != null) body.set("event_types", MAPPER.valueToTree(eventTypes));
        return request(sessionPath(session) + "/webhooks", "POST", body, key(idempotencyKey));
    }

    public JsonNode webhooks(String session, String after, int limit)
            throws IOException, InterruptedException {
        return request(
                sessionPath(session)
                        + "/webhooks"
                        + query("after", after, "limit", pageLimit(limit)),
                "GET",
                null,
                null);
    }

    public JsonNode webhookDeliveries(String session, String webhook, String after, int limit)
            throws IOException, InterruptedException {
        return request(
                sessionPath(session)
                        + "/webhooks/"
                        + segment(webhook)
                        + "/deliveries"
                        + query("after", after, "limit", pageLimit(limit)),
                "GET",
                null,
                null);
    }

    public JsonNode deleteWebhook(String session, String webhook)
            throws IOException, InterruptedException {
        return request(
                sessionPath(session) + "/webhooks/" + segment(webhook), "DELETE", null, null);
    }

    public JsonNode retryWebhook(String session, String webhook)
            throws IOException, InterruptedException {
        return request(
                sessionPath(session) + "/webhooks/" + segment(webhook) + "/retry",
                "POST",
                MAPPER.createObjectNode(),
                null);
    }

    /** Each stream has a total deadline equal to timeout; close it when the consumer stops. */
    public EventStream stream(String session, String turn, String after)
            throws IOException, InterruptedException {
        return eventStream(scopePath(session, turn) + "/events/stream", after);
    }

    EventStream eventStream(String path, String after) throws IOException, InterruptedException {
        long started = System.nanoTime();
        var builder = requestBuilder(path, null).header("Accept", "text/event-stream");
        if (after != null && !after.isBlank()) builder.header("Last-Event-ID", after);
        HttpResponse<InputStream> response =
                await(
                        http.sendAsync(
                                builder.GET().build(), HttpResponse.BodyHandlers.ofInputStream()));
        EventStream stream =
                new EventStream(
                        response.body(),
                        Math.max(0, timeout.toNanos() - (System.nanoTime() - started)));
        stream.managedProtocol = path.startsWith("/v1/sessions");
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            try (stream) {
                throw apiError(response.statusCode(), readLimited(response.body(), ERROR_LIMIT));
            }
        }
        String type = response.headers().firstValue("Content-Type").orElse("");
        if (!type.split(";", 2)[0].trim().equalsIgnoreCase("text/event-stream")) {
            stream.close();
            throw new IOException("Service response is not an SSE stream");
        }
        return stream;
    }

    /** Export one bounded public NDJSON window. Advance nextCursor only after a clean EOF. */
    public EventExport exportEvents(
            String session, String turn, String after, String through, int limit)
            throws IOException, InterruptedException {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be 1..1000");
        long started = System.nanoTime();
        var request =
                requestBuilder(
                                scopePath(session, turn)
                                        + "/export"
                                        + query("after", after, "through", through, "limit", limit),
                                null)
                        .header("Accept", "application/x-ndjson")
                        .GET()
                        .build();
        var response = await(http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()));
        var stream =
                new EventStream(
                        response.body(),
                        Math.max(0, timeout.toNanos() - (System.nanoTime() - started)));
        try {
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw apiError(response.statusCode(), readLimited(response.body(), ERROR_LIMIT));
            String end = response.headers().firstValue("X-Export-Through").orElse("");
            String next = response.headers().firstValue("X-Next-Cursor").orElse("");
            String more = response.headers().firstValue("X-Has-More").orElse("");
            String rawCount = response.headers().firstValue("X-Export-Count").orElse("");
            String type = response.headers().firstValue("Content-Type").orElse("");
            if (!type.split(";", 2)[0].trim().equalsIgnoreCase("application/x-ndjson")
                    || end.isBlank()
                    || next.isBlank()
                    || !("true".equals(more) || "false".equals(more))
                    || !rawCount.matches("[0-9]{1,4}")
                    || Integer.parseInt(rawCount) > limit
                    || (through != null && !through.isBlank() && !through.equals(end))
                    || ("false".equals(more) && !next.equals(end))) throw schema("export");
            return new EventExport(
                    stream, session, end, next, "true".equals(more), Integer.parseInt(rawCount));
        } catch (IOException | RuntimeException error) {
            stream.close();
            throw error;
        }
    }

    public static final class EventExport implements AutoCloseable {
        private final EventStream stream;
        private final String session, through, next;
        private final boolean more;
        private final int expected;
        private int count;
        private String last;

        private EventExport(
                EventStream stream,
                String session,
                String through,
                String next,
                boolean more,
                int expected) {
            this.stream = stream;
            this.session = session;
            this.through = through;
            this.next = next;
            this.more = more;
            this.expected = expected;
        }

        public String throughCursor() {
            return through;
        }

        public String nextCursor() {
            return next;
        }

        public boolean hasMore() {
            return more;
        }

        public JsonNode nextEvent() throws IOException {
            try {
                JsonNode event = stream.nextExportEvent();
                if (event == null) {
                    if (count != expected || (more && (count == 0 || !next.equals(last))))
                        throw new IOException("Incomplete Service export window");
                    return null;
                }
                if (!session.equals(event.path("session_id").asText()))
                    throw schema("export event");
                if (++count > expected) throw new IOException("Service export count mismatch");
                last = event.path("cursor").asText();
                return event;
            } catch (IOException error) {
                close();
                throw error;
            }
        }

        @Override
        public void close() throws IOException {
            stream.close();
        }
    }

    public static final class EventStream implements AutoCloseable {
        private boolean managedProtocol;
        private final InputStream input;
        private final ScheduledFuture<?> deadline;
        private final AtomicBoolean expired = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();

        private EventStream(InputStream input, long remainingNanos) {
            this.input = new BufferedInputStream(input);
            deadline =
                    DEADLINES.schedule(
                            () -> {
                                expired.set(true);
                                try {
                                    this.input.close();
                                } catch (IOException ignored) {
                                }
                            },
                            remainingNanos,
                            TimeUnit.NANOSECONDS);
        }

        /** Returns null at a clean end of stream. Only complete, versioned data events are returned. */
        public JsonNode nextEvent() throws IOException {
            if (closed.get()) throw new IOException("Event stream is closed");
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            while (true) {
                byte[] line;
                try {
                    line = readLine(input);
                } catch (IOException failure) {
                    if (expired.get()) throw timeout();
                    throw failure;
                }
                if (expired.get()) throw timeout();
                if (line == null) {
                    close();
                    return null;
                }
                if (line.length == 0) {
                    if (data.size() == 0) continue;
                    if (managedProtocol) {
                        var event = MAPPER.readTree(data.toByteArray());
                        if (!event.path("id").isTextual() || !event.path("type").isTextual())
                            throw new IOException("Invalid Session event");
                        return event;
                    }
                    return parseEvent(data.toByteArray());
                }
                if (line.length >= 5
                        && line[0] == 'd'
                        && line[1] == 'a'
                        && line[2] == 't'
                        && line[3] == 'a'
                        && line[4] == ':') {
                    int offset = line.length > 5 && line[5] == ' ' ? 6 : 5;
                    int added = line.length - offset + (data.size() == 0 ? 0 : 1);
                    if (data.size() + added > ERROR_LIMIT)
                        throw new IOException("Service event exceeds the 1 MiB client limit");
                    if (data.size() > 0) data.write('\n');
                    data.write(line, offset, line.length - offset);
                }
            }
        }

        private JsonNode nextExportEvent() throws IOException {
            if (closed.get()) throw new IOException("Event stream is closed");
            byte[] line;
            try {
                line = readLine(input, true);
            } catch (IOException failure) {
                if (expired.get()) throw timeout();
                throw failure;
            }
            if (expired.get()) throw timeout();
            if (line == null) {
                close();
                return null;
            }
            return parseEvent(line);
        }

        @Override
        public void close() throws IOException {
            if (closed.compareAndSet(false, true)) {
                deadline.cancel(false);
                input.close();
            }
        }
    }

    JsonNode request(String path, String method, JsonNode body, String key)
            throws IOException, InterruptedException {
        return request(path, method, body, key, Map.of());
    }

    JsonNode request(
            String path,
            String method,
            JsonNode body,
            String key,
            Map<String, String> additionalHeaders)
            throws IOException, InterruptedException {
        return request(path, method, body, key, additionalHeaders, timeout);
    }

    JsonNode request(
            String path,
            String method,
            JsonNode body,
            String key,
            Map<String, String> additionalHeaders,
            Duration budget)
            throws IOException, InterruptedException {
        Duration bound = budget.compareTo(timeout) < 0 ? budget : timeout;
        var builder = requestBuilder(path, key).timeout(bound);
        additionalHeaders.forEach(builder::header);
        var publisher = HttpRequest.BodyPublishers.noBody();
        if (body != null) {
            byte[] bytes = MAPPER.writeValueAsBytes(body);
            if (bytes.length > JSON_LIMIT)
                throw new IllegalArgumentException("Request exceeds the 16 MiB client limit");
            builder.header("Content-Type", "application/json");
            publisher = HttpRequest.BodyPublishers.ofByteArray(bytes);
        }
        return json(exchange(builder.method(method, publisher).build(), JSON_LIMIT, bound));
    }

    byte[] content(String path) throws IOException, InterruptedException {
        return exchange(requestBuilder(path, null).GET().build(), CONTENT_LIMIT);
    }

    private HttpRequest.Builder requestBuilder(String path, String key) {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout);
        credentials.forEach(builder::header);
        if (path.startsWith("/v1/sessions"))
            builder.header("Managed-Agents-Extensions", "turn-inputs-v1");
        if (key != null && !key.isBlank()) builder.header("Idempotency-Key", key);
        return builder;
    }

    private byte[] exchange(HttpRequest request, int limit)
            throws IOException, InterruptedException {
        return exchange(request, limit, timeout);
    }

    private byte[] exchange(HttpRequest request, int limit, Duration budget)
            throws IOException, InterruptedException {
        var response =
                await(
                        http.sendAsync(
                                request,
                                info ->
                                        new LimitedBodySubscriber(
                                                info.statusCode() >= 200 && info.statusCode() < 300
                                                        ? limit
                                                        : ERROR_LIMIT)),
                        budget);
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw apiError(response.statusCode(), response.body());
        return response.body();
    }

    private <T> T await(CompletableFuture<T> pending) throws IOException, InterruptedException {
        return await(pending, timeout);
    }

    private <T> T await(CompletableFuture<T> pending, Duration budget)
            throws IOException, InterruptedException {
        try {
            return pending.get(budget.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException failure) {
            pending.cancel(true);
            throw timeout();
        } catch (InterruptedException failure) {
            pending.cancel(true);
            throw failure;
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("Service request failed", cause);
        }
    }

    private static JsonNode json(byte[] body) throws IOException {
        return body.length == 0 ? MAPPER.nullNode() : MAPPER.readTree(body);
    }

    private static ServiceAPIException apiError(int status, byte[] body) {
        JsonNode detail;
        try {
            detail = json(body);
        } catch (IOException malformed) {
            detail = MAPPER.getNodeFactory().textNode(new String(body, StandardCharsets.UTF_8));
        }
        return new ServiceAPIException(status, detail);
    }

    private static String sessionPath(String session) {
        return ROOT + "/" + segment(session);
    }

    private static String turnPath(String session, String turn) {
        return sessionPath(session) + "/turns/" + segment(turn);
    }

    private static String scopePath(String session, String turn) {
        return turn == null || turn.isBlank() ? sessionPath(session) : turnPath(session, turn);
    }

    static String segment(String value) {
        if (value == null || value.isBlank() || ".".equals(value) || "..".equals(value))
            throw new IllegalArgumentException("Invalid path identifier");
        return encode(value);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String key(String value) {
        if (value == null || value.isBlank() || value.length() > 256)
            throw new IllegalArgumentException("Idempotency-Key must contain 1 to 256 characters");
        return value;
    }

    private static int pageLimit(int value) {
        if (value < 1 || value > 100)
            throw new IllegalArgumentException("limit must be between 1 and 100");
        return value;
    }

    static String query(Object... pairs) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] == null || pairs[i + 1].toString().isBlank()) continue;
            result.append(result.length() == 0 ? '?' : '&')
                    .append(encode(pairs[i].toString()))
                    .append('=')
                    .append(encode(pairs[i + 1].toString()));
        }
        return result.toString();
    }

    private static IOException schema(String kind) {
        return new IOException(
                "Unsupported Service "
                        + kind
                        + " schema; use matching Service and client versions");
    }

    private static HttpTimeoutException timeout() {
        return new HttpTimeoutException("Service request exceeded its deadline");
    }

    private static ScheduledThreadPoolExecutor deadlines() {
        var timer =
                new ScheduledThreadPoolExecutor(
                        1,
                        runnable -> {
                            Thread thread = new Thread(runnable, "managed-agents-client-deadlines");
                            thread.setDaemon(true);
                            return thread;
                        });
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }

    private static byte[] readLine(InputStream input) throws IOException {
        return readLine(input, false);
    }

    private static JsonNode parseEvent(byte[] data) throws IOException {
        JsonNode event = MAPPER.readTree(data);
        if (event == null
                || !event.path("schema_version").isIntegralNumber()
                || event.path("schema_version").intValue() != 1
                || !event.path("id").isTextual()
                || !event.path("cursor").isTextual()
                || !event.path("type").isTextual()
                || !event.path("data").isObject()) throw schema("event");
        return event;
    }

    private static byte[] readLine(InputStream input, boolean requireTerminator)
            throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int value = input.read();
            if (value == -1) {
                if (requireTerminator && line.size() != 0)
                    throw new IOException("Incomplete Service export event");
                return null;
            }
            if (value == '\n') {
                byte[] bytes = line.toByteArray();
                return bytes.length > 0 && bytes[bytes.length - 1] == '\r'
                        ? Arrays.copyOf(bytes, bytes.length - 1)
                        : bytes;
            }
            if (line.size() >= ERROR_LIMIT)
                throw new IOException("Service event line exceeds the 1 MiB client limit");
            line.write(value);
        }
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        byte[] bytes = input.readNBytes(limit + 1);
        if (bytes.length > limit)
            throw new IOException("Service response exceeds the client limit");
        return bytes;
    }

    private static final class LimitedBodySubscriber
            implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        private LimitedBodySubscriber(int limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            value.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(
                            new IOException("Service response exceeds the client limit"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable failure) {
            result.completeExceptionally(failure);
        }

        @Override
        public void onComplete() {
            result.complete(bytes.toByteArray());
        }
    }
}
