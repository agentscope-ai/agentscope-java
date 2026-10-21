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
package io.agentscope.builder.portal.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.builder.portal.persistence.PortalSessionRepository;
import io.agentscope.builder.portal.session.PortalSessionAccess;
import io.agentscope.builder.runtime.managed.ManagedFiles;
import io.agentscope.builder.runtime.managed.ManagedJournalStore;
import io.agentscope.builder.runtime.managed.ManagedScope;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Bounded file transfer uses the shared ledger, with no Portal-to-runtime forwarding. */
@RestController
@RequestMapping("/v1/sessions/{session}/files")
public final class ManagedFilesController {
    private record Caller(ManagedScope scope, String actor) {}

    private final PortalSessionRepository sessions;
    private final PortalSessionAccess access;
    private final ManagedFiles files;
    private final ObjectMapper mapper;
    private final Semaphore transfers = new Semaphore(16);

    public ManagedFilesController(
            PortalSessionRepository sessions,
            PortalSessionAccess access,
            ManagedJournalStore journal,
            ObjectMapper mapper) {
        this.sessions = sessions;
        this.access = access;
        this.files = journal.files();
        this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    @PostMapping
    public Mono<Map<String, Object>> upload(
            @PathVariable String session,
            @RequestHeader HttpHeaders headers,
            @RequestBody Flux<DataBuffer> body) {
        return Mono.defer(
                () -> {
                    if (!transfers.tryAcquire())
                        return Mono.error(
                                new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS));
                    return Mono.fromCallable(() -> authorize(session, headers, "interact"))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(
                                    caller -> {
                                        if (headers.getContentLength() > ManagedFiles.MAX_BYTES)
                                            return Mono.error(
                                                    new ResponseStatusException(
                                                            HttpStatus.PAYLOAD_TOO_LARGE));
                                        String name = headers.getFirst("X-File-Name");
                                        if (name == null || name.length() > 2048)
                                            return Mono.error(
                                                    new ResponseStatusException(
                                                            HttpStatus.BAD_REQUEST,
                                                            "File name is required"));
                                        String decoded;
                                        try {
                                            decoded =
                                                    URLDecoder.decode(name, StandardCharsets.UTF_8);
                                        } catch (IllegalArgumentException invalid) {
                                            return Mono.error(
                                                    new ResponseStatusException(
                                                            HttpStatus.BAD_REQUEST,
                                                            "Invalid file name"));
                                        }
                                        String media = headers.getFirst(HttpHeaders.CONTENT_TYPE);
                                        String contentType =
                                                media == null ? "application/octet-stream" : media;
                                        return DataBufferUtils.join(body, ManagedFiles.MAX_BYTES)
                                                .map(
                                                        buffer -> {
                                                            try {
                                                                byte[] bytes =
                                                                        new byte
                                                                                [buffer
                                                                                        .readableByteCount()];
                                                                buffer.read(bytes);
                                                                return bytes;
                                                            } finally {
                                                                DataBufferUtils.release(buffer);
                                                            }
                                                        })
                                                .defaultIfEmpty(new byte[0])
                                                .flatMap(
                                                        bytes ->
                                                                Mono.fromCallable(
                                                                                () -> {
                                                                                    var result =
                                                                                            files
                                                                                                    .upload(
                                                                                                            caller
                                                                                                                    .scope(),
                                                                                                            caller
                                                                                                                    .actor(),
                                                                                                            headers
                                                                                                                    .getFirst(
                                                                                                                            "Idempotency-Key"),
                                                                                                            decoded,
                                                                                                            contentType,
                                                                                                            bytes);

                                                                                    return result;
                                                                                })
                                                                        .subscribeOn(
                                                                                Schedulers
                                                                                        .boundedElastic()));
                                    })
                            .onErrorMap(
                                    DataBufferLimitException.class,
                                    invalid ->
                                            new ResponseStatusException(
                                                    HttpStatus.PAYLOAD_TOO_LARGE))
                            .timeout(Duration.ofSeconds(30))
                            .doFinally(signal -> transfers.release());
                });
    }

    @GetMapping
    public Mono<Map<String, Object>> list(
            @PathVariable String session,
            @RequestHeader HttpHeaders headers,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        return Mono.fromCallable(
                        () ->
                                files.list(
                                        authorize(session, headers, "read").scope(), offset, limit))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}")
    public Mono<Map<String, Object>> get(
            @PathVariable String session,
            @PathVariable String id,
            @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(
                        () -> files.metadata(authorize(session, headers, "read").scope(), id))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/content")
    public Mono<Void> content(
            @PathVariable String session,
            @PathVariable String id,
            @RequestHeader HttpHeaders headers,
            ServerHttpResponse response) {
        return Mono.defer(
                () -> {
                    if (!transfers.tryAcquire())
                        return Mono.error(
                                new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS));
                    return Mono.fromCallable(
                                    () -> {
                                        var caller = authorize(session, headers, "read");
                                        var metadata = files.metadata(caller.scope(), id);
                                        byte[] content = files.content(caller.scope(), id);
                                        response.getHeaders()
                                                .setContentType(
                                                        MediaType.parseMediaType(
                                                                (String)
                                                                        metadata.get(
                                                                                "media_type")));
                                        response.getHeaders()
                                                .setContentDisposition(
                                                        ContentDisposition.attachment()
                                                                .filename(
                                                                        (String)
                                                                                metadata.get(
                                                                                        "name"),
                                                                        StandardCharsets.UTF_8)
                                                                .build());
                                        response.getHeaders().setCacheControl("no-store");
                                        response.getHeaders()
                                                .set("X-Content-Type-Options", "nosniff");
                                        response.getHeaders().setContentLength(content.length);
                                        return content;
                                    })
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(
                                    content ->
                                            response.writeWith(
                                                    Mono.just(
                                                            response.bufferFactory()
                                                                    .wrap(content))))
                            .timeout(Duration.ofSeconds(30))
                            .doFinally(signal -> transfers.release());
                });
    }

    private Caller authorize(String id, HttpHeaders headers, String operation) {
        var actor = access.authorize(id, headers, operation);
        var session =
                sessions.findById(id)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!"managed".equals(session.runtimeKind))
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        try {
            return new Caller(
                    new ManagedScope(session.tenant, session.namespace, id),
                    mapper.writeValueAsString(actor));
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Cannot encode caller identity", invalid);
        }
    }
}
