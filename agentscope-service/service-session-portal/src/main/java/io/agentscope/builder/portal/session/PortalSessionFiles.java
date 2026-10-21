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

import io.agentscope.builder.portal.persistence.PortalSessionEntity;
import io.agentscope.builder.portal.persistence.PortalSessionRepository;
import io.agentscope.builder.web.auth.InternalTokenAuthFilter;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Public file authorization and bounded streaming to the session's private runtime endpoint. */
@Service
public final class PortalSessionFiles {
    private static final List<String> RESPONSE_HEADERS =
            List.of(
                    HttpHeaders.CONTENT_TYPE,
                    HttpHeaders.CONTENT_DISPOSITION,
                    HttpHeaders.CACHE_CONTROL,
                    HttpHeaders.ETAG,
                    "X-Content-Type-Options");
    private final PortalSessionRepository sessions;
    private final PortalSessionAccess access;
    private final WebClient runtime;
    private final String internalToken;
    private final int maxBytes;
    private final Semaphore transfers;

    public PortalSessionFiles(
            PortalSessionRepository sessions,
            PortalSessionAccess access,
            @Value("${builder.data-plane-url:http://localhost:8082}") String runtimeUrl,
            @Value("${builder.internal-token:${BUILDER_INTERNAL_TOKEN:}}") String internalToken,
            @Value("${builder.agent-api.files.max-bytes:16777216}") int maxBytes,
            @Value("${builder.portal.file-transfer-slots:16}") int slots) {
        if (maxBytes < 1 || maxBytes > 64 * 1024 * 1024 || slots < 1 || slots > 256)
            throw new IllegalArgumentException("Invalid file transfer capacity");
        this.sessions = sessions;
        this.access = access;
        this.runtime = WebClient.builder().baseUrl(runtimeUrl).build();
        this.internalToken = internalToken;
        this.maxBytes = maxBytes;
        this.transfers = new Semaphore(slots);
    }

    public Mono<Void> forward(
            String session,
            String suffix,
            int offset,
            int limit,
            HttpMethod method,
            HttpHeaders headers,
            Flux<DataBuffer> body,
            ServerHttpResponse response) {
        return Mono.fromCallable(() -> authorize(session, method, headers))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(
                        owner ->
                                Mono.defer(
                                        () -> {
                                            if (!transfers.tryAcquire())
                                                return Mono.error(
                                                        new ResponseStatusException(
                                                                HttpStatus.TOO_MANY_REQUESTS,
                                                                "File transfer capacity is full"));
                                            return Mono.defer(
                                                            () ->
                                                                    send(
                                                                            session, owner, suffix,
                                                                            offset, limit, method,
                                                                            headers, body,
                                                                            response))
                                                    .timeout(Duration.ofSeconds(30))
                                                    .onErrorMap(this::unwrapStatus)
                                                    .doFinally(signal -> transfers.release());
                                        }));
    }

    /** Called only after publication has authorized the original caller for this Session. */
    public Mono<Map<String, Object>> metadata(PortalSessionEntity session, String id) {
        if (!"managed".equals(session.runtimeKind))
            return Mono.error(
                    new ResponseStatusException(
                            HttpStatus.UNPROCESSABLE_ENTITY, "unsupported_capability: files"));
        if (id == null || !id.matches("file_[a-f0-9]{64}"))
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
        return Mono.defer(
                () -> {
                    if (!transfers.tryAcquire())
                        return Mono.error(
                                new ResponseStatusException(
                                        HttpStatus.TOO_MANY_REQUESTS,
                                        "File transfer capacity is full"));
                    return Mono.defer(
                                    () ->
                                            runtime.get()
                                                    .uri(
                                                            "/api/internal/runtime/managed/sessions/{session}/files/{id}",
                                                            session.id,
                                                            id)
                                                    .header(
                                                            InternalTokenAuthFilter
                                                                    .INTERNAL_TOKEN_HEADER,
                                                            internalToken)
                                                    .header(
                                                            InternalTokenAuthFilter
                                                                    .INTERNAL_USER_HEADER,
                                                            session.ownerId)
                                                    .retrieve()
                                                    .bodyToMono(
                                                            new ParameterizedTypeReference<
                                                                    Map<String, Object>>() {}))
                            .timeout(Duration.ofSeconds(5))
                            .onErrorMap(
                                    TimeoutException.class,
                                    error ->
                                            new ResponseStatusException(
                                                    HttpStatus.SERVICE_UNAVAILABLE,
                                                    "File runtime unavailable"))
                            .onErrorMap(
                                    WebClientResponseException.class,
                                    error ->
                                            new ResponseStatusException(
                                                    error.getStatusCode(),
                                                    "File metadata unavailable"))
                            .doFinally(signal -> transfers.release());
                });
    }

    private String authorize(String session, HttpMethod method, HttpHeaders headers) {
        access.authorize(session, headers, method == HttpMethod.GET ? "read" : "interact");
        var row =
                sessions.findById(session)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!"managed".equals(row.runtimeKind))
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "unsupported_capability: files");
        if (!"ready".equals(row.state)
                && !(method == HttpMethod.GET && "checkpointing".equals(row.state)))
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Session provisioning has not completed");
        if (method == HttpMethod.POST) {
            if (!"active".equals(row.lifecycle))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Session is not active");
            if (headers.getContentLength() > maxBytes)
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
            String key = headers.getFirst("Idempotency-Key");
            String name = headers.getFirst("X-File-Name");
            if (key == null
                    || key.isBlank()
                    || key.length() > 256
                    || name == null
                    || name.isBlank()
                    || name.length() > 2048)
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Valid file name and Idempotency-Key required");
            try {
                String decoded = URLDecoder.decode(name, StandardCharsets.UTF_8);
                if (decoded.isBlank()
                        || decoded.length() > 512
                        || decoded.contains("\r")
                        || decoded.contains("\n")) throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalid) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid file name");
            }
        }
        return row.ownerId;
    }

    private Mono<Void> send(
            String session,
            String owner,
            String suffix,
            int offset,
            int limit,
            HttpMethod method,
            HttpHeaders headers,
            Flux<DataBuffer> body,
            ServerHttpResponse response) {
        var request =
                runtime.method(method)
                        .uri(
                                builder -> {
                                    var uri =
                                            builder.path(
                                                    "/api/internal/runtime/managed/sessions/{session}/files"
                                                            + suffix);
                                    if (method == HttpMethod.GET && suffix.isEmpty())
                                        uri.queryParam("offset", offset).queryParam("limit", limit);
                                    return uri.build(session);
                                })
                        .header(InternalTokenAuthFilter.INTERNAL_TOKEN_HEADER, internalToken)
                        .header(InternalTokenAuthFilter.INTERNAL_USER_HEADER, owner);
        if (method == HttpMethod.POST)
            for (String name :
                    List.of("Idempotency-Key", "X-File-Name", HttpHeaders.CONTENT_TYPE)) {
                String value = headers.getFirst(name);
                if (value != null) request.header(name, value);
            }
        WebClient.RequestHeadersSpec<?> outbound =
                method == HttpMethod.POST
                        ? request.body(BodyInserters.fromDataBuffers(bounded(body)))
                        : request;
        return outbound.exchangeToMono(
                reply -> {
                    response.setStatusCode(reply.statusCode());
                    for (String name : RESPONSE_HEADERS) {
                        var value = reply.headers().header(name);
                        if (!value.isEmpty()) response.getHeaders().put(name, value);
                    }
                    return response.writeWith(bounded(reply.bodyToFlux(DataBuffer.class)));
                });
    }

    private Throwable unwrapStatus(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && !(cause instanceof ResponseStatusException))
            cause = cause.getCause();
        return cause instanceof ResponseStatusException ? cause : error;
    }

    private Flux<DataBuffer> bounded(Flux<DataBuffer> source) {
        return Flux.defer(
                () -> {
                    var bytes = new AtomicLong();
                    return source.<DataBuffer>handle(
                                    (buffer, sink) -> {
                                        if (bytes.addAndGet(buffer.readableByteCount())
                                                > maxBytes) {
                                            DataBufferUtils.release(buffer);
                                            sink.error(
                                                    new ResponseStatusException(
                                                            HttpStatus.PAYLOAD_TOO_LARGE));
                                        } else sink.next(buffer);
                                    })
                            .doOnDiscard(DataBuffer.class, DataBufferUtils::release);
                });
    }
}
