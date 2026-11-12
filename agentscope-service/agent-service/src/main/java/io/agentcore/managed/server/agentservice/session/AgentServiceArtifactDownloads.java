/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package io.agentcore.managed.server.agentservice.session;

import io.agentcore.managed.server.agentservice.persistence.AgentServiceResourceRepository;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionRepository;
import io.agentcore.managed.server.web.auth.InternalTokenAuthFilter;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Public reads use the current Agent Service projection and the backend's immutable byte binding. */
@Service
public final class AgentServiceArtifactDownloads {
    private static final long MAX_BYTES = 100L * 1024 * 1024;
    private static final List<String> HEADERS =
            List.of(
                    HttpHeaders.CONTENT_TYPE,
                    HttpHeaders.CONTENT_DISPOSITION,
                    HttpHeaders.CACHE_CONTROL,
                    HttpHeaders.ETAG,
                    "X-Content-Type-Options");
    private final AgentServiceSessionAccess access;
    private final AgentServiceSessionRepository sessions;
    private final AgentServiceResourceRepository resources;
    private final AgentServiceResourceProjection projection;
    private final AgentServiceSessionFiles files;
    private final AgentServiceRuntimeBackends backends;
    private final String token;
    private final Semaphore transfers;

    public AgentServiceArtifactDownloads(
            AgentServiceSessionAccess access,
            AgentServiceSessionRepository sessions,
            AgentServiceResourceRepository resources,
            AgentServiceResourceProjection projection,
            AgentServiceSessionFiles files,
            AgentServiceRuntimeBackends backends,
            @Value("${managed-agents.internal-token:${MANAGED_AGENTS_INTERNAL_TOKEN:}}")
                    String token,
            @Value("${managed-agents.agent-service.file-transfer-slots:16}") int slots) {
        if (slots < 1 || slots > 256)
            throw new IllegalArgumentException("Invalid artifact transfer capacity");
        this.access = access;
        this.sessions = sessions;
        this.resources = resources;
        this.projection = projection;
        this.files = files;
        this.backends = backends;
        this.token = token;
        this.transfers = new Semaphore(slots);
    }

    public Mono<Void> download(
            String session,
            String turn,
            String id,
            HttpHeaders headers,
            ServerHttpResponse response) {
        return Mono.fromCallable(() -> authorize(session, turn, id, headers))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(
                        selected -> {
                            if (selected.file != null)
                                return files.forward(
                                        session,
                                        "/" + selected.file + "/content",
                                        0,
                                        1,
                                        HttpMethod.GET,
                                        headers,
                                        Flux.empty(),
                                        response);
                            return Mono.defer(
                                    () -> {
                                        if (!transfers.tryAcquire())
                                            return Mono.error(
                                                    new ResponseStatusException(
                                                            HttpStatus.TOO_MANY_REQUESTS,
                                                            "Artifact transfer capacity is full"));
                                        return Mono.defer(
                                                        () -> send(session, id, selected, response))
                                                .timeout(Duration.ofSeconds(30))
                                                .onErrorMap(
                                                        TimeoutException.class,
                                                        error ->
                                                                new ResponseStatusException(
                                                                        HttpStatus
                                                                                .SERVICE_UNAVAILABLE,
                                                                        "Artifact runtime"
                                                                                + " unavailable"))
                                                .doFinally(signal -> transfers.release());
                                    });
                        });
    }

    private Mono<Void> send(
            String session, String id, Download selected, ServerHttpResponse response) {
        var size = new AtomicLong();
        return backends.kind(selected.kind)
                .get()
                .uri(
                        "/api/internal/runtime/sessions/{session}/turns/{turn}/artifacts/{id}/content",
                        session,
                        selected.turn,
                        id)
                .header(InternalTokenAuthFilter.INTERNAL_TOKEN_HEADER, token)
                .exchangeToMono(
                        upstream -> {
                            if (!upstream.statusCode().is2xxSuccessful())
                                return upstream.releaseBody()
                                        .then(
                                                Mono.error(
                                                        new ResponseStatusException(
                                                                upstream.statusCode(),
                                                                "Artifact content unavailable")));
                            if (upstream.headers().contentLength().orElse(0) > MAX_BYTES)
                                return upstream.releaseBody()
                                        .then(
                                                Mono.error(
                                                        new ResponseStatusException(
                                                                HttpStatus.PAYLOAD_TOO_LARGE)));
                            response.setStatusCode(upstream.statusCode());
                            for (String name : HEADERS) {
                                var values = upstream.headers().header(name);
                                if (!values.isEmpty()) response.getHeaders().put(name, values);
                            }
                            return response.writeWith(
                                    upstream.bodyToFlux(DataBuffer.class)
                                            .map(
                                                    buffer -> {
                                                        if (size.addAndGet(
                                                                        buffer.readableByteCount())
                                                                > MAX_BYTES) {
                                                            DataBufferUtils.release(buffer);
                                                            throw new ResponseStatusException(
                                                                    HttpStatus.PAYLOAD_TOO_LARGE);
                                                        }
                                                        return buffer;
                                                    })
                                            .doOnDiscard(
                                                    DataBuffer.class, DataBufferUtils::release));
                        });
    }

    private Download authorize(String session, String turn, String id, HttpHeaders headers) {
        access.authorize(session, headers, "read");
        if (id == null || id.isBlank() || id.length() > 512)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var row =
                sessions.findById(session)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var artifact =
                resources
                        .findById(projection.resourceId(session, "artifacts", id))
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (turn != null && !turn.equals(artifact.turnId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Map<String, Object> data = projection.read(artifact.data);
        if (data.get("data") instanceof Map<?, ?> payload
                && payload.get("file_id") instanceof String file
                && file.matches("file_[a-f0-9]{64}"))
            return new Download(row.runtimeKind, artifact.turnId, file);
        if ("managed".equals(row.runtimeKind) || artifact.turnId == null)
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "unsupported_capability: artifact_content");
        return new Download(row.runtimeKind, artifact.turnId, null);
    }

    private record Download(String kind, String turn, String file) {}
}
