/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package io.agentcore.managed.server.agentservice.api;

import io.agentcore.managed.server.agentservice.session.AgentServiceArtifactDownloads;
import io.agentcore.managed.server.agentservice.session.AgentServiceResourceReads;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1/agent-sessions/{session}/turns/{turn}/artifacts")
public final class AgentServiceTurnArtifactsController {
    private final AgentServiceResourceReads reads;
    private final AgentServiceArtifactDownloads downloads;

    public AgentServiceTurnArtifactsController(
            AgentServiceResourceReads reads, AgentServiceArtifactDownloads downloads) {
        this.reads = reads;
        this.downloads = downloads;
    }

    @GetMapping
    public Mono<Map<String, Object>> list(
            @PathVariable String session,
            @PathVariable String turn,
            @RequestHeader HttpHeaders headers,
            @RequestParam(required = false) String snapshot_id,
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "100") int limit) {
        return Mono.fromCallable(
                        () ->
                                reads.page(
                                        session,
                                        turn,
                                        "artifacts",
                                        snapshot_id,
                                        after,
                                        limit,
                                        headers))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}")
    public Mono<Map<String, Object>> get(
            @PathVariable String session,
            @PathVariable String turn,
            @PathVariable String id,
            @RequestHeader HttpHeaders headers,
            @RequestParam(required = false) String snapshot_id) {
        return Mono.fromCallable(
                        () -> reads.get(session, turn, "artifacts", id, snapshot_id, headers))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/content")
    public Mono<Void> content(
            @PathVariable String session,
            @PathVariable String turn,
            @PathVariable String id,
            @RequestHeader HttpHeaders headers,
            ServerHttpResponse response) {
        return downloads.download(session, turn, id, headers, response);
    }
}
