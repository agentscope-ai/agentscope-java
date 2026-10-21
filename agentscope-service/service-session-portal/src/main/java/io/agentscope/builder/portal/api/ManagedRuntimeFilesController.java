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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.portal.session.PortalSessionAccess;
import io.agentscope.builder.runtime.protocol.RuntimeRequestContext;
import io.agentscope.builder.web.auth.InternalTokenAuthFilter;
import java.io.IOException;
import java.util.Base64;
import java.util.Map;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Trusted binary upload uses the same authorization, byte limit and transfer capacity as public files. */
@RestController
@RequestMapping("/api/internal/managed/sessions/{session}/files")
public final class ManagedRuntimeFilesController {
    public static final String CONTEXT_HEADER = "X-Managed-Runtime-Context";

    private final ObjectMapper mapper;
    private final PortalSessionAccess access;
    private final ManagedFilesController files;

    public ManagedRuntimeFilesController(
            ObjectMapper mapper, PortalSessionAccess access, ManagedFilesController files) {
        this.mapper = mapper;
        this.access = access;
        this.files = files;
    }

    @PostMapping
    public Mono<Map<String, Object>> upload(
            @PathVariable String session,
            @RequestHeader HttpHeaders transport,
            @RequestBody Flux<DataBuffer> body,
            Authentication authentication) {
        return Mono.fromCallable(() -> headers(session, transport, authentication))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(headers -> files.upload(session, headers, body));
    }

    @GetMapping("/{id}/content")
    public Mono<Void> content(
            @PathVariable String session,
            @PathVariable String id,
            @RequestHeader HttpHeaders transport,
            Authentication authentication,
            ServerHttpResponse response) {
        return Mono.fromCallable(() -> headers(session, transport, authentication))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(headers -> files.content(session, id, headers, response));
    }

    private HttpHeaders headers(
            String session, HttpHeaders transport, Authentication authentication) {
        if (authentication == null
                || authentication.getAuthorities().stream()
                        .noneMatch(
                                role ->
                                        InternalTokenAuthFilter.ROLE_INTERNAL.equals(
                                                role.getAuthority())))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        String encoded = transport.getFirst(CONTEXT_HEADER);
        if (encoded == null || encoded.length() > 4096)
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Bounded runtime context is required");
        RuntimeRequestContext context;
        try {
            context =
                    mapper.readValue(
                            Base64.getUrlDecoder().decode(encoded), RuntimeRequestContext.class);
        } catch (IOException | IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid runtime context");
        }
        var headers =
                access.runtimeHeaders(
                        session,
                        transport.getFirst(InternalTokenAuthFilter.INTERNAL_USER_HEADER),
                        context);
        for (String name :
                new String[] {
                    "X-File-Name", HttpHeaders.CONTENT_TYPE, HttpHeaders.CONTENT_LENGTH
                }) {
            String value = transport.getFirst(name);
            if (value != null) headers.set(name, value);
        }
        return headers;
    }
}
