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
package io.agentscope.builder.portal.session;

import io.agentscope.builder.portal.persistence.PortalSessionRepository;
import io.agentscope.builder.web.auth.InternalTokenAuthFilter;
import io.agentscope.builder.web.auth.JwtService;
import io.jsonwebtoken.JwtException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Sensitive native reads require a human trace role and ordinary current Session permission. */
@Service
public final class PortalSessionTrace {
    private final PortalSessionRepository sessions;
    private final PortalSessionAccess access;
    private final JwtService jwt;
    private final boolean enabled, localDev;
    private final String token;
    private final WebClient runtime;
    private final Semaphore reads = new Semaphore(8);

    public PortalSessionTrace(
            PortalSessionRepository sessions,
            PortalSessionAccess access,
            JwtService jwt,
            @Value("${builder.agent-api.trace-enabled:${BUILDER_SESSION_TRACE_ENABLED:false}}")
                    boolean enabled,
            @Value("${builder.local-dev:${BUILDER_LOCAL_DEV:false}}") boolean localDev,
            @Value("${builder.data-plane-url:http://localhost:8082}") String runtimeUrl,
            @Value("${builder.internal-token:${BUILDER_INTERNAL_TOKEN:}}") String token) {
        this.sessions = sessions;
        this.access = access;
        this.jwt = jwt;
        this.enabled = enabled;
        this.localDev = localDev;
        this.token = token;
        runtime =
                WebClient.builder()
                        .baseUrl(runtimeUrl)
                        .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(4 << 20))
                        .build();
    }

    private void authorize(String session, HttpHeaders headers) {
        if (!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (!localDev) {
            if (headers.getFirst("X-API-Key") != null)
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "Human trace permission required");
            String bearer = headers.getFirst(HttpHeaders.AUTHORIZATION);
            if (bearer == null || !bearer.startsWith("Bearer "))
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
            try {
                var claims = jwt.parse(bearer.substring(7));
                if (jwt.extractRoles(claims).stream()
                        .map(role -> role.toUpperCase(Locale.ROOT))
                        .noneMatch(Set.of("ADMIN", "SESSION_TRACE")::contains))
                    throw new ResponseStatusException(
                            HttpStatus.FORBIDDEN, "Session trace permission required");
            } catch (JwtException | IllegalArgumentException error) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid user token");
            }
        }
        access.authorize(session, headers, "read");
        var row =
                sessions.findById(session)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        access.authorize(row.rootSessionId == null ? session : row.rootSessionId, headers, "trace");
    }

    public Mono<Map<String, Object>> get(
            String session, long after, Long through, int limit, HttpHeaders headers) {
        if (after < 0 || through != null && through < after || limit < 1 || limit > 100)
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST));
        return Mono.fromCallable(
                        () -> {
                            authorize(session, headers);
                            var row =
                                    sessions.findById(session)
                                            .orElseThrow(
                                                    () ->
                                                            new ResponseStatusException(
                                                                    HttpStatus.NOT_FOUND));
                            if (!"managed".equals(row.runtimeKind))
                                throw new ResponseStatusException(
                                        HttpStatus.UNPROCESSABLE_ENTITY,
                                        "unsupported_capability: trace");
                            return row;
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(
                        row ->
                                Mono.defer(
                                        () -> {
                                            if (!reads.tryAcquire())
                                                return Mono.error(
                                                        new ResponseStatusException(
                                                                HttpStatus.TOO_MANY_REQUESTS,
                                                                "Trace read capacity reached"));
                                            String root =
                                                    row.rootSessionId == null
                                                            ? row.id
                                                            : row.rootSessionId;
                                            return runtime.get()
                                                    .uri(
                                                            uri -> {
                                                                var query =
                                                                        uri.path(
                                                                                        "/api/internal/runtime/managed/sessions/{id}/trace")
                                                                                .queryParam(
                                                                                        "after",
                                                                                        after)
                                                                                .queryParam(
                                                                                        "limit",
                                                                                        limit);
                                                                if (through != null)
                                                                    query.queryParam(
                                                                            "through", through);
                                                                if (row.rootSessionId != null)
                                                                    query.queryParam(
                                                                            "child", row.id);
                                                                return query.build(root);
                                                            })
                                                    .header(
                                                            InternalTokenAuthFilter
                                                                    .INTERNAL_TOKEN_HEADER,
                                                            token)
                                                    .header(
                                                            InternalTokenAuthFilter
                                                                    .INTERNAL_USER_HEADER,
                                                            row.ownerId)
                                                    .retrieve()
                                                    .bodyToMono(
                                                            new ParameterizedTypeReference<
                                                                    Map<String, Object>>() {})
                                                    .switchIfEmpty(
                                                            Mono.error(
                                                                    new IllegalStateException(
                                                                            "Empty runtime trace")))
                                                    .timeout(Duration.ofSeconds(15))
                                                    .onErrorMap(
                                                            error ->
                                                                    new ResponseStatusException(
                                                                            error
                                                                                            instanceof
                                                                                            WebClientResponseException
                                                                                                    remote
                                                                                    ? remote
                                                                                            .getStatusCode()
                                                                                    : HttpStatus
                                                                                            .SERVICE_UNAVAILABLE,
                                                                            "Trace runtime"
                                                                                + " unavailable"))
                                                    .flatMap(
                                                            view ->
                                                                    Mono.fromCallable(
                                                                                    () -> {
                                                                                        authorize(
                                                                                                session,
                                                                                                headers);
                                                                                        return view;
                                                                                    })
                                                                            .subscribeOn(
                                                                                    Schedulers
                                                                                            .boundedElastic()))
                                                    .doFinally(ignored -> reads.release());
                                        }));
    }
}
