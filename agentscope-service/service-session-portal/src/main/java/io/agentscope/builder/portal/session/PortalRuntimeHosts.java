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
package io.agentscope.builder.portal.session;

import io.agentscope.builder.web.auth.InternalTokenAuthFilter;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/** Machine protocol adapter. The runtime validates the original Host and Attempt fences. */
@Service
public class PortalRuntimeHosts {
    private static final ParameterizedTypeReference<Map<String, Object>> JSON =
            new ParameterizedTypeReference<>() {};
    private static final Set<String> CONTROL =
            Set.of(
                    "heartbeat",
                    "state",
                    "renew",
                    "credentials",
                    "observe",
                    "complete",
                    "fail",
                    "cancelled",
                    "physical-stop");
    private final WebClient runtime;
    private final String internalToken;
    private final Semaphore calls;
    private final Semaphore controls;

    public PortalRuntimeHosts(
            @Value(
                            "${builder.orchestration-runtime-url:${builder.control-plane-url:http://localhost:8081}}")
                    String runtimeUrl,
            @Value("${builder.internal-token:${BUILDER_INTERNAL_TOKEN:}}") String internalToken,
            @Value("${builder.portal.runtime-host-slots:64}") int callSlots,
            @Value("${builder.portal.runtime-host-control-slots:16}") int controlSlots) {
        if (callSlots < 1 || callSlots > 4096 || controlSlots < 1 || controlSlots > 1024)
            throw new IllegalArgumentException("Invalid Runtime Host transport concurrency");
        this.runtime =
                WebClient.builder()
                        .baseUrl(runtimeUrl)
                        .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                        .build();
        this.internalToken = internalToken;
        this.calls = new Semaphore(callSlots);
        this.controls = new Semaphore(controlSlots);
    }

    public Mono<ResponseEntity<Map<String, Object>>> call(
            String authorization, Map<String, Object> body, String operation, String... path) {
        return call(authorization, null, body, operation, path);
    }

    public Mono<ResponseEntity<Map<String, Object>>> call(
            String authorization,
            String attemptCredential,
            Map<String, Object> body,
            String operation,
            String... path) {
        return forward(
                authorization,
                attemptCredential,
                body,
                operation,
                "/api/internal/runtime/runtime-hosts",
                path);
    }

    public Mono<ResponseEntity<Map<String, Object>>> collaboration(
            String authorization, Map<String, Object> body) {
        return forward(
                authorization,
                null,
                body,
                "collaboration",
                "/api/internal/runtime/mcp",
                "collaboration");
    }

    private Mono<ResponseEntity<Map<String, Object>>> forward(
            String authorization,
            String attemptCredential,
            Map<String, Object> body,
            String operation,
            String prefix,
            String... path) {
        return forward(
                HttpMethod.POST,
                authorization,
                attemptCredential,
                null,
                body,
                operation,
                prefix,
                path);
    }

    public Mono<ResponseEntity<Map<String, Object>>> approval(
            HttpMethod method, String taskCredential, Map<String, Object> body, String... path) {
        if (taskCredential == null || taskCredential.isBlank() || taskCredential.length() > 8192)
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return forward(
                method,
                "Bearer " + taskCredential,
                null,
                taskCredential,
                body,
                "observe",
                "/api/internal/runtime/agent-tasks",
                path);
    }

    public Mono<ResponseEntity<Map<String, Object>>> task(
            HttpMethod method, String taskCredential, Map<String, Object> body, String... path) {
        if (taskCredential == null || taskCredential.isBlank() || taskCredential.length() > 8192)
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return forward(
                method,
                "Bearer " + taskCredential,
                null,
                taskCredential,
                body,
                "collaboration",
                "/api/internal/runtime",
                path);
    }

    private Mono<ResponseEntity<Map<String, Object>>> forward(
            HttpMethod method,
            String authorization,
            String attemptCredential,
            String taskCredential,
            Map<String, Object> body,
            String operation,
            String prefix,
            String... path) {
        return Mono.defer(
                () -> {
                    if (authorization == null
                            || !authorization.startsWith("Bearer ")
                            || authorization.substring(7).isBlank()
                            || authorization.length() > 8192)
                        return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
                    if (internalToken.isBlank())
                        return Mono.error(
                                new ResponseStatusException(
                                        HttpStatus.SERVICE_UNAVAILABLE,
                                        "Runtime service credential is unavailable"));
                    if (attemptCredential != null && attemptCredential.length() > 8192)
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST));
                    Semaphore slots = CONTROL.contains(operation) ? controls : calls;
                    return Mono.using(
                            () -> {
                                if (!slots.tryAcquire())
                                    throw new ResponseStatusException(
                                            HttpStatus.TOO_MANY_REQUESTS,
                                            "Runtime Host transport capacity exhausted");
                                return true;
                            },
                            ignored ->
                                    runtime.method(method)
                                            .uri(
                                                    builder ->
                                                            builder.path(prefix)
                                                                    .pathSegment(path)
                                                                    .build())
                                            .header(HttpHeaders.AUTHORIZATION, authorization)
                                            .headers(
                                                    headers -> {
                                                        if (attemptCredential != null)
                                                            headers.set(
                                                                    "X-Execution-Attempt-Token",
                                                                    attemptCredential);
                                                        if (taskCredential != null)
                                                            headers.set(
                                                                    "X-Agent-Task-Token",
                                                                    taskCredential);
                                                    })
                                            .header(
                                                    InternalTokenAuthFilter.INTERNAL_TOKEN_HEADER,
                                                    internalToken)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .body(
                                                    body == null
                                                            ? BodyInserters.empty()
                                                            : BodyInserters.fromValue(body))
                                            .exchangeToMono(response -> response.toEntity(JSON))
                                            .map(
                                                    response ->
                                                            ResponseEntity.status(
                                                                            response
                                                                                    .getStatusCode())
                                                                    .contentType(
                                                                            MediaType
                                                                                    .APPLICATION_JSON)
                                                                    .body(response.getBody()))
                                            .timeout(Duration.ofSeconds(10))
                                            .onErrorMap(
                                                    error ->
                                                            error instanceof ResponseStatusException
                                                                    ? error
                                                                    : new ResponseStatusException(
                                                                            HttpStatus
                                                                                    .SERVICE_UNAVAILABLE,
                                                                            "Runtime is"
                                                                                + " unavailable",
                                                                            error)),
                            ignored -> slots.release());
                });
    }
}
