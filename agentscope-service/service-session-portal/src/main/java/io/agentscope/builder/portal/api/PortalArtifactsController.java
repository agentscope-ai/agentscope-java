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

import io.agentscope.builder.portal.session.PortalArtifactDownloads;
import io.agentscope.builder.portal.session.PortalArtifacts;
import io.agentscope.builder.portal.session.PortalResourceReads;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1/agent-sessions/{session}/artifacts")
public final class PortalArtifactsController {
    private final PortalArtifacts artifacts;
    private final PortalResourceReads reads;
    private final PortalArtifactDownloads downloads;

    public PortalArtifactsController(
            PortalArtifacts artifacts,
            PortalResourceReads reads,
            PortalArtifactDownloads downloads) {
        this.artifacts = artifacts;
        this.reads = reads;
        this.downloads = downloads;
    }

    @GetMapping("/{id}/content")
    public Mono<Void> content(
            @PathVariable String session,
            @PathVariable String id,
            @RequestHeader HttpHeaders headers,
            ServerHttpResponse response) {
        return downloads.download(session, null, id, headers, response);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String, Object>> publish(
            @PathVariable String session,
            @RequestHeader HttpHeaders headers,
            @RequestBody Map<String, Object> body) {
        return Mono.fromCallable(() -> artifacts.publish(session, headers, body))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(
            @PathVariable String session,
            @PathVariable String id,
            @RequestHeader HttpHeaders headers) {
        return Mono.<Void>fromRunnable(() -> artifacts.delete(session, id, headers))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping
    public Mono<Map<String, Object>> list(
            @PathVariable String session,
            @RequestHeader HttpHeaders headers,
            @RequestParam(required = false) String snapshot_id,
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "100") int limit) {
        return Mono.fromCallable(
                        () -> reads.page(session, "artifacts", snapshot_id, after, limit, headers))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}")
    public Mono<Map<String, Object>> get(
            @PathVariable String session,
            @PathVariable String id,
            @RequestHeader HttpHeaders headers,
            @RequestParam(required = false) String snapshot_id) {
        return Mono.fromCallable(
                        () -> reads.get(session, null, "artifacts", id, snapshot_id, headers))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
