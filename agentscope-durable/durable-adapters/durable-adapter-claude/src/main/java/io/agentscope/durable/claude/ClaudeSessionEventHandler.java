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
package io.agentscope.durable.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableRuntime;
import io.agentscope.durable.DurableSession;
import io.agentscope.durable.DurableTypes.LiveEvent;
import io.agentscope.durable.DurableTypes.PublicEvent;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.EventStreamOptions;
import io.agentscope.durable.EventStreamOptions.EventDeltaType;
import io.agentscope.durable.SessionEventStream;
import io.agentscope.durable.claude.ClaudeEventHistory.Comparison;
import io.agentscope.durable.claude.ClaudeEventHistory.Selection;
import io.agentscope.durable.store.RecordStore;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Claude Managed Agents event endpoints, mounted at /v1/sessions/ on a host HTTP server. */
public final class ClaudeSessionEventHandler implements HttpHandler {
    public static final String BETA = "managed-agents-2026-04-01";
    private static final int MAX_BODY_BYTES = 16 * 1024 * 1024;
    private static final String EVENTS = "/v1/sessions/{id}/events";

    @FunctionalInterface
    public interface Authorizer {
        /** Resolve the requested Session into an authorized namespace using the host credentials. */
        SessionKey authorize(HttpExchange exchange, String operation, String sessionId);
    }

    private record Route(String sessionId, String operation) {}

    private final DurableRuntime runtime;
    private final RecordStore batches;
    private final Authorizer authorizer;
    private final Duration streamDuration;
    private final Duration pollInterval;

    public ClaudeSessionEventHandler(
            DurableRuntime runtime, RecordStore batches, Authorizer authorizer) {
        this(runtime, batches, authorizer, Duration.ofSeconds(30), Duration.ofMillis(250));
    }

    public ClaudeSessionEventHandler(
            DurableRuntime runtime,
            RecordStore batches,
            Authorizer authorizer,
            Duration streamDuration,
            Duration pollInterval) {
        this.runtime = Objects.requireNonNull(runtime);
        this.batches = Objects.requireNonNull(batches);
        this.authorizer = Objects.requireNonNull(authorizer);
        this.streamDuration = Objects.requireNonNull(streamDuration);
        this.pollInterval = Objects.requireNonNull(pollInterval);
        if (streamDuration.toMillis() < 1 || pollInterval.toMillis() < 1)
            throw new IllegalArgumentException(
                    "Positive stream duration and poll interval required");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                var route = route(exchange);
                var key = authorizer.authorize(exchange, route.operation(), route.sessionId());
                if (key == null || !route.sessionId().equals(key.sessionId()))
                    throw new SecurityException();
                var headers = exchange.getRequestHeaders();
                var betas = headers.getOrDefault("anthropic-beta", List.of());
                if (betas.stream()
                        .flatMap(value -> List.of(value.split(",")).stream())
                        .map(String::strip)
                        .noneMatch(BETA::equals))
                    throw invalid("The " + BETA + " beta header is required");

                var session = runtime.getSession(key);
                switch (route.operation()) {
                    case "send" -> json(exchange, 200, send(exchange, session));
                    case "list" -> json(exchange, 200, list(exchange, session));
                    case "stream" -> stream(exchange, session);
                    default -> throw new IllegalStateException("Unknown route");
                }
            } catch (SecurityException denied) {
                if (exchange.getResponseCode() < 0)
                    json(exchange, 403, error("permission_error", "Access denied"));
            } catch (DurableException failure) {
                int status =
                        switch (failure.code()) {
                            case INVALID, INCOMPATIBLE -> 400;
                            case NOT_FOUND -> 404;
                            case CONFLICT, FENCED -> 409;
                            case CURSOR_EXPIRED -> 410;
                            case UNAVAILABLE -> 503;
                        };
                String type =
                        status == 404
                                ? "not_found_error"
                                : status == 503 ? "overloaded_error" : "invalid_request_error";
                if (exchange.getResponseCode() < 0)
                    json(exchange, status, error(type, failure.getMessage()));
            } catch (IllegalArgumentException failure) {
                if (exchange.getResponseCode() < 0)
                    json(exchange, 400, error("invalid_request_error", "Malformed request"));
            } catch (UncheckedIOException disconnected) {
                throw disconnected.getCause();
            } catch (RuntimeException failure) {
                if (exchange.getResponseCode() < 0)
                    json(exchange, 500, error("api_error", "Event operation failed"));
            }
        }
    }

    private static Route route(HttpExchange exchange) {
        var path = exchange.getRequestURI().getPath().split("/", -1);
        if ((path.length != 5 && path.length != 6)
                || !"v1".equals(path[1])
                || !"sessions".equals(path[2])
                || !"events".equals(path[4])
                || (path.length == 6 && !"stream".equals(path[5])))
            throw new DurableException(DurableException.Code.NOT_FOUND, "Expected " + EVENTS);
        String id = DurableJson.text(path[3], "session_id");
        String method = exchange.getRequestMethod();
        if ("GET".equals(method)) return new Route(id, path.length == 6 ? "stream" : "list");
        if ("POST".equals(method) && path.length == 5) return new Route(id, "send");
        throw invalid("Unsupported method for this event endpoint");
    }

    private JsonNode send(HttpExchange exchange, DurableSession session) throws IOException {
        query(exchange, Set.of());
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) throw invalid("Event batch exceeds 16 MiB");
        var input = DurableJson.parse(new String(bytes, StandardCharsets.UTF_8));
        if (!input.isObject() || input.size() != 1 || !input.has("events"))
            throw invalid("Expected an events envelope");
        String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        String requestId =
                key == null
                        ? UUID.randomUUID().toString()
                        : DurableJson.text(key, "Idempotency-Key");
        return new ClaudeSessionEvents(session)
                .sendBatch(batches, requestId, input.required("events"));
    }

    private static JsonNode list(HttpExchange exchange, DurableSession session) {
        var query =
                query(
                        exchange,
                        Set.of(
                                "page",
                                "limit",
                                "types",
                                "types[]",
                                "order",
                                "created_at[gt]",
                                "created_at[gte]",
                                "created_at[lt]",
                                "created_at[lte]"));
        String limit = scalar(query, "limit");
        String order = scalar(query, "order");
        if (order != null && !Set.of("asc", "desc").contains(order))
            throw invalid("Event history order must be asc or desc");
        var timeBounds = new EnumMap<Comparison, Instant>(Comparison.class);
        for (var comparison : Comparison.values()) {
            String key = "created_at[" + comparison.name().toLowerCase(Locale.ROOT) + "]";
            String value = scalar(query, key);
            if (value == null) continue;
            try {
                timeBounds.put(comparison, Instant.parse(value));
            } catch (DateTimeParseException malformed) {
                throw invalid("Invalid timestamp for " + key);
            }
        }
        return ClaudeEventHistory.list(
                session,
                scalar(query, "page"),
                limit == null ? 20 : Integer.parseInt(limit),
                new Selection("desc".equals(order), values(query, "types"), timeBounds));
    }

    private void stream(HttpExchange exchange, DurableSession session) throws IOException {
        var query = query(exchange, Set.of("event_deltas", "event_deltas[]"));
        var deltas = values(query, "event_deltas");
        if (!Set.of(ClaudeEventAdapter.AGENT_MESSAGE).containsAll(deltas))
            throw invalid("Unsupported preview event type");
        String last = exchange.getRequestHeaders().getFirst("Last-Event-ID");
        String scope = DurableJson.hash(session.key());
        var position =
                last == null || last.isBlank()
                        ? new StreamPosition(
                                scope, session.listEventsBefore(null, 1).highWatermark(), 0)
                        : StreamPosition.decode(last, scope);
        var options = EventStreamOptions.builder().after(position.cursor());
        if (!deltas.isEmpty()) options.deltas(EventDeltaType.MESSAGE);
        var adapter = new ClaudeEventAdapter(session.view().primaryThreadId());
        try (var stream = session.streamEvents(options.build())) {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, 0);
            var sink = new EventSink(exchange.getResponseBody(), adapter, deltas, position);
            sink.write(": connected\n\n");
            try {
                stream.transferTo(sink, streamDuration, pollInterval);
            } catch (DurableException failure) {
                sink.emit(null, error("api_error", failure.getMessage()));
            }
        }
    }

    private static Set<String> values(Map<String, List<String>> query, String key) {
        var values = new HashSet<>(query.getOrDefault(key, List.of()));
        values.addAll(query.getOrDefault(key + "[]", List.of()));
        return Set.copyOf(values);
    }

    private static String scalar(Map<String, List<String>> query, String key) {
        var values = query.get(key);
        if (values == null) return null;
        if (values.size() != 1) throw invalid("Expected one " + key);
        return values.get(0);
    }

    private static Map<String, List<String>> query(HttpExchange exchange, Set<String> allowed) {
        var query = new HashMap<String, List<String>>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) return query;
        for (var parameter : raw.split("&", -1)) {
            var pair = parameter.split("=", 2);
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            if (pair.length != 2 || !allowed.contains(key))
                throw invalid("Unknown or malformed query parameter");
            query.computeIfAbsent(key, ignored -> new ArrayList<>())
                    .add(URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return query;
    }

    private static void json(HttpExchange exchange, int status, JsonNode value) throws IOException {
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static JsonNode error(String type, String message) {
        return DurableJson.value(
                Map.of("type", "error", "error", Map.of("type", type, "message", message)));
    }

    private static DurableException invalid(String message) {
        return new DurableException(DurableException.Code.INVALID, message);
    }

    private record StreamPosition(String scope, String cursor, int skip) {
        String encode() {
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            DurableJson.value(this).toString().getBytes(StandardCharsets.UTF_8));
        }

        static StreamPosition decode(String token, String scope) {
            if (token.length() > 2048) throw invalid("Invalid Last-Event-ID");
            try {
                var position =
                        DurableJson.convert(
                                DurableJson.parse(
                                        new String(
                                                Base64.getUrlDecoder().decode(token),
                                                StandardCharsets.UTF_8)),
                                StreamPosition.class);
                if (!scope.equals(position.scope())
                        || position.cursor() == null
                        || position.skip() < 0) throw invalid("Invalid Last-Event-ID");
                return position;
            } catch (RuntimeException malformed) {
                throw invalid("Invalid Last-Event-ID");
            }
        }
    }

    private static final class EventSink implements SessionEventStream.Sink {
        private final OutputStream output;
        private final ClaudeEventAdapter adapter;
        private final Set<String> deltas;
        private StreamPosition position;
        private long heartbeatAt = System.nanoTime();

        EventSink(
                OutputStream output,
                ClaudeEventAdapter adapter,
                Set<String> deltas,
                StreamPosition position) {
            this.output = output;
            this.adapter = adapter;
            this.deltas = deltas;
            this.position = position;
        }

        @Override
        public void accept(PublicEvent event) {
            var items = adapter.committed(event);
            int skip = position.skip();
            if (skip > 0 && skip >= items.size()) throw invalid("Invalid Last-Event-ID offset");
            for (int index = skip; index < items.size(); index++) {
                var next =
                        index + 1 < items.size()
                                ? new StreamPosition(position.scope(), position.cursor(), index + 1)
                                : new StreamPosition(position.scope(), event.cursor(), 0);
                emit(next.encode(), items.get(index));
            }
            position = new StreamPosition(position.scope(), event.cursor(), 0);
        }

        @Override
        public void live(LiveEvent event) {
            adapter.live(event, deltas).forEach(item -> emit(null, item));
        }

        @Override
        public void idle() {
            if (System.nanoTime() - heartbeatAt >= Duration.ofSeconds(15).toNanos())
                write(": keepalive\n\n");
        }

        void emit(String id, JsonNode item) {
            String frame = id == null ? "" : "id: " + id + "\n";
            write(frame + "event: " + item.required("type").asText() + "\ndata: " + item + "\n\n");
        }

        private void write(String frame) {
            try {
                output.write(frame.getBytes(StandardCharsets.UTF_8));
                output.flush();
                heartbeatAt = System.nanoTime();
            } catch (IOException disconnected) {
                throw new UncheckedIOException(disconnected);
            }
        }
    }
}
