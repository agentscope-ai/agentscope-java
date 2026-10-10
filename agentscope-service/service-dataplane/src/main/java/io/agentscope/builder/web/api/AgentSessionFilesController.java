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
package io.agentscope.builder.web.api;

import io.agentscope.builder.web.managed.DataSessionService;
import io.agentscope.builder.web.managed.SessionFileService;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1/agent-sessions/{session}/files")
public final class AgentSessionFilesController {
    private final DataSessionService sessions;
    private final SessionFileService files;

    public AgentSessionFilesController(DataSessionService sessions, SessionFileService files) {
        this.sessions = sessions;
        this.files = files;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String, Object>> upload(
            @PathVariable String session,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("X-File-Name") String name,
            @RequestHeader(value = "Content-Type", defaultValue = "application/octet-stream")
                    String media,
            @RequestBody Flux<DataBuffer> body,
            Authentication auth) {
        return Mono.fromCallable(() -> sessions.get((String) auth.getPrincipal(), session))
                .subscribeOn(Schedulers.boundedElastic())
                .then(DataBufferUtils.join(body, files.maxBytes()))
                .map(
                        buffer -> {
                            try {
                                byte[] bytes = new byte[buffer.readableByteCount()];
                                buffer.read(bytes);
                                return bytes;
                            } finally {
                                DataBufferUtils.release(buffer);
                            }
                        })
                .defaultIfEmpty(new byte[0])
                .publishOn(Schedulers.boundedElastic())
                .map(
                        bytes ->
                                files.upload(
                                        session,
                                        key,
                                        URLDecoder.decode(name, StandardCharsets.UTF_8),
                                        media,
                                        bytes));
    }

    @GetMapping
    public Mono<Map<String, Object>> list(
            @PathVariable String session,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit,
            Authentication auth) {
        return Mono.fromCallable(
                        () -> {
                            sessions.get((String) auth.getPrincipal(), session);
                            return files.list(session, offset, limit);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}")
    public Mono<Map<String, Object>> get(
            @PathVariable String session, @PathVariable String id, Authentication auth) {
        return Mono.fromCallable(
                        () -> {
                            sessions.get((String) auth.getPrincipal(), session);
                            return files.get(session, id);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/content")
    public Mono<ResponseEntity<byte[]>> download(
            @PathVariable String session, @PathVariable String id, Authentication auth) {
        return Mono.fromCallable(
                        () -> {
                            sessions.get((String) auth.getPrincipal(), session);
                            var file = files.get(session, id);
                            return ResponseEntity.ok()
                                    .contentType(
                                            MediaType.parseMediaType(
                                                    (String) file.get("media_type")))
                                    .header(
                                            HttpHeaders.CONTENT_DISPOSITION,
                                            ContentDisposition.attachment()
                                                    .filename(
                                                            (String) file.get("name"),
                                                            StandardCharsets.UTF_8)
                                                    .build()
                                                    .toString())
                                    .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                                    .header("X-Content-Type-Options", "nosniff")
                                    .eTag("\"" + file.get("sha256") + "\"")
                                    .body(files.content(session, id));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
