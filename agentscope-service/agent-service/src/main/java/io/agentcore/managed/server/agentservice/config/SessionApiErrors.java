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
package io.agentcore.managed.server.agentservice.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.managed.ManagedStateException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Error format is scoped to the public Session protocol, including transport validation errors. */
@Component
@Order(-2)
public class SessionApiErrors implements WebExceptionHandler, WebFilter {
    private final ObjectMapper mapper;

    public SessionApiErrors(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (publicSession(exchange))
            exchange.getResponse()
                    .getHeaders()
                    .set("request-id", "req_" + UUID.randomUUID().toString().replace("-", ""));
        String path = exchange.getRequest().getPath().value();
        if (path.equals("/v1/sessions") || path.startsWith("/v1/sessions/")) {
            var headers = exchange.getRequest().getHeaders();
            if (headers.containsHeader("X-API-Key") && headers.containsHeader("Authorization"))
                return handle(
                        exchange,
                        new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "Ambiguous authentication headers"));
            String version = headers.getFirst("anthropic-version");
            String beta = headers.getFirst("anthropic-beta");
            String extension = headers.getFirst("Managed-Agents-Extensions");
            if (headers.getOrEmpty("anthropic-version").size() > 1
                    || headers.getOrEmpty("Managed-Agents-Extensions").size() > 1
                    || extension != null
                            && Arrays.stream(extension.split(",", -1))
                                    .anyMatch(
                                            value ->
                                                    !Set.of(
                                                                    "turn-inputs-v1",
                                                                    "session-queue-v1",
                                                                    "session-budgets-v1")
                                                            .contains(value.trim())))
                return handle(
                        exchange,
                        new ResponseStatusException(
                                HttpStatus.BAD_REQUEST,
                                "unsupported_capability: Unknown output extension"));
            if (version != null && !"2023-06-01".equals(version)
                    || beta != null
                            && headers.get("anthropic-beta").stream()
                                    .flatMap(value -> Arrays.stream(value.split(",", -1)))
                                    .anyMatch(
                                            value ->
                                                    !"managed-agents-2026-04-01"
                                                            .equals(value.trim()))
                    || headers.containsHeader("anthropic-workspace-id"))
                return handle(
                        exchange,
                        new ResponseStatusException(
                                HttpStatus.BAD_REQUEST,
                                "unsupported_capability: Unsupported protocol version or Workspace"
                                        + " mapping"));
            String method = exchange.getRequest().getMethod().name();
            boolean budgetExtension =
                    extension != null
                            && Arrays.stream(extension.split(","))
                                    .anyMatch(value -> "session-budgets-v1".equals(value.trim()));
            boolean allowed =
                    budgetExtension
                                    && Set.of("GET", "PUT").contains(method)
                                    && path.matches("/v1/sessions/[^/]+/budget")
                            || path.equals("/v1/sessions") && method.equals("POST")
                            || method.equals("GET")
                                    && path.matches(
                                            "/v1/sessions/[^/]+(?:/capabilities|/projection|/queue(?:/[^/]+)?|/commands(?:/[^/]+)?|/events(?:/stream)?|/turns(?:/[^/]+(?:/output|/capabilities|/inputs(?:/[^/]+)?)?)?)?")
                            || method.equals("POST") && path.matches("/v1/sessions/[^/]+/events");
            if (!allowed)
                return handle(
                        exchange,
                        new ResponseStatusException(
                                HttpStatus.BAD_REQUEST,
                                "unsupported_capability: Operation is outside the first protocol"
                                        + " release"));
            var query = exchange.getRequest().getQueryParams();
            Set<String> allowedQuery = Set.of("beta");
            if (method.equals("GET")) {
                if (path.endsWith("/events"))
                    allowedQuery =
                            Set.of(
                                    "beta",
                                    "page",
                                    "limit",
                                    "order",
                                    "types[]",
                                    "created_at[gt]",
                                    "created_at[gte]",
                                    "created_at[lt]",
                                    "created_at[lte]");
                else if (path.endsWith("/events/stream"))
                    allowedQuery = Set.of("beta", "event_deltas[]");
                else if (path.endsWith("/projection")) allowedQuery = Set.of("beta", "minSequence");
                else if (path.endsWith("/queue") || path.endsWith("/commands"))
                    allowedQuery = Set.of("beta", "after", "limit");
                else if (path.endsWith("/turns"))
                    allowedQuery =
                            Set.of("beta", "after", "limit", "order", "status", "input_event_id");
                else if (path.endsWith("/inputs"))
                    allowedQuery = Set.of("beta", "after", "limit", "status", "event_id");
                else if (path.endsWith("/output")) allowedQuery = Set.of("beta", "after", "limit");
            }
            if (path.endsWith("/events/stream")
                    && query.keySet().stream()
                            .anyMatch(key -> !Set.of("beta", "event_deltas[]").contains(key)))
                return handle(
                        exchange,
                        new ResponseStatusException(
                                HttpStatus.BAD_REQUEST,
                                "unsupported_capability: SSE resume and unknown query options are"
                                        + " unavailable"));
            for (var entry : query.entrySet())
                if (!allowedQuery.contains(entry.getKey())
                        || !Set.of("types[]", "event_deltas[]").contains(entry.getKey())
                                && entry.getValue().size() != 1
                        || "beta".equals(entry.getKey())
                                && !List.of("true").equals(entry.getValue()))
                    return handle(
                            exchange,
                            new ManagedStateException(
                                    "incompatible_query",
                                    "Unknown or repeated Session query parameter"));
            if (method.equals("POST") && path.matches("/v1/sessions/[^/]+/events"))
                return DataBufferUtils.join(exchange.getRequest().getBody(), 1048576)
                        .map(
                                buffer -> {
                                    try {
                                        byte[] content = new byte[buffer.readableByteCount()];
                                        buffer.read(content);
                                        return content;
                                    } finally {
                                        DataBufferUtils.release(buffer);
                                    }
                                })
                        .defaultIfEmpty(new byte[0])
                        .flatMap(
                                content -> {
                                    var request =
                                            new ServerHttpRequestDecorator(exchange.getRequest()) {
                                                @Override
                                                public Flux<DataBuffer> getBody() {
                                                    return Flux.defer(
                                                            () ->
                                                                    Flux.just(
                                                                            exchange.getResponse()
                                                                                    .bufferFactory()
                                                                                    .wrap(
                                                                                            content)));
                                                }
                                            };
                                    return chain.filter(exchange.mutate().request(request).build());
                                });
        }
        return chain.filter(exchange);
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        if (!publicSession(exchange) || exchange.getResponse().isCommitted())
            return Mono.error(error);
        int code =
                error instanceof ResponseStatusException status
                        ? status.getStatusCode().value()
                        : error instanceof ManagedStateException state
                                ? stateStatus(state.code())
                                : error instanceof IllegalArgumentException ? 400 : 500;
        boolean tooLarge = causedBy(error, DataBufferLimitException.class);
        boolean coordinating =
                causedBy(error, CannotAcquireLockException.class)
                        || causedBy(error, QueryTimeoutException.class)
                        || causedBy(error, TransactionTimedOutException.class);
        if (tooLarge) code = 413;
        else if (coordinating)
            code = exchange.getRequest().getMethod().name().equals("POST") ? 409 : 503;
        else if (causedBy(error, DataAccessResourceFailureException.class)) code = 503;
        String type =
                switch (code) {
                    case 401 -> "authentication_error";
                    case 403 -> "permission_error";
                    case 404 -> "not_found_error";
                    case 409 -> "conflict_error";
                    case 413 -> "request_too_large";
                    case 429 -> "rate_limit_error";
                    default -> code < 500 ? "invalid_request_error" : "api_error";
                };
        String message =
                error instanceof ResponseStatusException status && status.getReason() != null
                        ? status.getReason()
                        : code < 500 ? "Invalid request" : "Session service unavailable";
        var detail = new LinkedHashMap<String, Object>();
        detail.put("type", type);
        detail.put("message", message);
        boolean extended =
                exchange.getRequest().getHeaders().containsHeader("Managed-Agents-Extensions")
                        || Boolean.TRUE.equals(exchange.getAttribute("managed_agents.explicit"));
        if (error instanceof ManagedStateException state && extended)
            detail.put("code", state.code());
        if (message.startsWith("unsupported_capability:"))
            if (extended) detail.put("code", "unsupported_capability");
        if (extended && coordinating) detail.put("code", "request_in_progress");
        if (extended && code == 503 && !detail.containsKey("code"))
            detail.put("code", "service_unavailable");
        if (extended
                && code == 400
                && !detail.containsKey("code")
                && exchange.getRequest().getPath().value().endsWith("/events"))
            detail.put("code", "invalid_event");
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.valueOf(code));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String request = response.getHeaders().getFirst("request-id");
        if (request == null) {
            request = "req_" + UUID.randomUUID().toString().replace("-", "");
            response.getHeaders().set("request-id", request);
        }
        try {
            byte[] bytes =
                    mapper.writeValueAsBytes(
                            Map.of("type", "error", "error", detail, "request_id", request));
            return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
        } catch (JsonProcessingException failure) {
            return Mono.error(failure);
        }
    }

    private static boolean causedBy(Throwable error, Class<? extends Throwable> type) {
        for (int depth = 0; error != null && depth < 20; depth++, error = error.getCause())
            if (type.isInstance(error)) return true;
        return false;
    }

    private static boolean publicSession(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        return path.equals("/v1/sessions")
                || path.startsWith("/v1/sessions/")
                || path.startsWith("/api/internal/managed/sessions/");
    }

    private static int stateStatus(String code) {
        if (Set.of(
                        "invalid_cursor",
                        "invalid_event",
                        "incompatible_query",
                        "idempotency_key_required",
                        "unsupported_capability")
                .contains(code)) return 400;
        if ("service_unavailable".equals(code)) return 503;
        if ("admission_limited".equals(code)
                || "file_quota_exceeded".equals(code)
                || "artifact_quota_exceeded".equals(code)) return 429;
        if ("file_too_large".equals(code)) return 413;
        if ("file_invalid".equals(code)) return 400;
        if (code.endsWith("_not_found")) return 404;
        if (code.endsWith("_expired") || "file_content_removed".equals(code)) return 410;
        return 409;
    }
}
