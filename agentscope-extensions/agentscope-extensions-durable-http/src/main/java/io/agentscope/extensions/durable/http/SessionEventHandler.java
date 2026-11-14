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

package io.agentscope.extensions.durable.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableRuntime;
import io.agentscope.durable.DurableTypes.EventFilter;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.EventStreamOptions;
import io.agentscope.durable.EventStreamOptions.EventDeltaType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;

/**
 * Reliable committed SSE plus best-effort live presentation signals. Only committed events carry a
 * reconnect cursor. Connections are bounded; closing one never cancels a Turn.
 */
public final class SessionEventHandler implements HttpHandler {
    private final DurableRuntime runtime;
    private final DurableRuntimeHandler.Authorizer authorizer;
    private final Duration connectionDuration;
    private final Duration pollInterval;

    public SessionEventHandler(
            DurableRuntime runtime, DurableRuntimeHandler.Authorizer authorizer) {
        this(runtime, authorizer, Duration.ofSeconds(30), Duration.ofMillis(250));
    }

    public SessionEventHandler(
            DurableRuntime runtime,
            DurableRuntimeHandler.Authorizer authorizer,
            Duration connectionDuration,
            Duration pollInterval) {
        this.runtime = Objects.requireNonNull(runtime);
        this.authorizer = Objects.requireNonNull(authorizer);
        if (connectionDuration.toMillis() < 1 || pollInterval.toMillis() < 1)
            throw new IllegalArgumentException("Positive SSE durations required");
        this.connectionDuration = connectionDuration;
        this.pollInterval = pollInterval;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        boolean streaming = false;
        try (exchange) {
            try {
                if (!"GET".equals(exchange.getRequestMethod()))
                    throw new DurableException(DurableException.Code.INVALID, "GET required");
                var query = new HashMap<String, String>();
                String raw = exchange.getRequestURI().getRawQuery();
                if (raw != null)
                    for (var part : raw.split("&")) {
                        var pair = part.split("=", 2);
                        String name = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                        if (pair.length != 2
                                || !Set.of(
                                                "namespace",
                                                "sessionId",
                                                "cursor",
                                                "threadId",
                                                "turnId",
                                                "runId")
                                        .contains(name)
                                || query.putIfAbsent(
                                                name,
                                                URLDecoder.decode(pair[1], StandardCharsets.UTF_8))
                                        != null)
                            throw new DurableException(
                                    DurableException.Code.INVALID, "Invalid SSE query");
                    }
                var session = new SessionKey(query.get("namespace"), query.get("sessionId"));
                authorizer.authorize(exchange, "events", session.namespace(), session);
                var handle = runtime.getSession(session);
                var filter =
                        new EventFilter(
                                query.get("threadId"), query.get("turnId"), query.get("runId"));
                String cursor = exchange.getRequestHeaders().getFirst("Last-Event-ID");
                if (cursor == null || cursor.isBlank()) cursor = query.get("cursor");
                var stream =
                        handle.streamEvents(
                                filter,
                                EventStreamOptions.builder()
                                        .after(cursor)
                                        .deltas(EventDeltaType.values())
                                        .build());
                exchange.getResponseHeaders()
                        .set("Content-Type", "text/event-stream; charset=utf-8");
                exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                exchange.sendResponseHeaders(200, 0);
                streaming = true;
                try (stream) {
                    stream.transferTo(
                            new SseEventWriter(exchange.getResponseBody()),
                            connectionDuration,
                            pollInterval);
                }
            } catch (DurableException error) {
                if (!streaming) DurableHttp.error(exchange, error);
            } catch (UncheckedIOException error) {
                throw error.getCause();
            } catch (RuntimeException error) {
                if (!streaming) DurableHttp.error(exchange, error);
            }
        }
    }
}
