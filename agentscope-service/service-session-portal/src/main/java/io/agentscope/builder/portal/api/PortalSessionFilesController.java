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
package io.agentscope.builder.portal.api;

import io.agentscope.builder.portal.session.PortalSessionFiles;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
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

@RestController
@RequestMapping("/api/v1/agent-sessions/{session}/files")
public final class PortalSessionFilesController {
    private final PortalSessionFiles files;

    public PortalSessionFilesController(PortalSessionFiles files) {
        this.files = files;
    }

    @PostMapping
    public Mono<Void> upload(
            @PathVariable String session,
            @RequestHeader HttpHeaders headers,
            @RequestBody Flux<DataBuffer> body,
            ServerHttpResponse response) {
        return files.forward(session, "", 0, 0, HttpMethod.POST, headers, body, response);
    }

    @GetMapping
    public Mono<Void> list(
            @PathVariable String session,
            @RequestHeader HttpHeaders headers,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit,
            ServerHttpResponse response) {
        if (offset < 0 || limit < 1 || limit > 100)
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page"));
        return files.forward(
                session, "", offset, limit, HttpMethod.GET, headers, Flux.empty(), response);
    }

    @GetMapping({"/{id}", "/{id}/content"})
    public Mono<Void> get(
            @PathVariable String session,
            @PathVariable String id,
            @RequestHeader HttpHeaders headers,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        if (!id.matches("file_[a-f0-9]{64}"))
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
        String suffix =
                "/" + id + (request.getPath().value().endsWith("/content") ? "/content" : "");
        return files.forward(
                session, suffix, 0, 0, HttpMethod.GET, headers, Flux.empty(), response);
    }
}
