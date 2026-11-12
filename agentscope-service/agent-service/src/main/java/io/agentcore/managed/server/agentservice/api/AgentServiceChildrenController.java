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
package io.agentcore.managed.server.agentservice.api;

import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionRepository;
import io.agentcore.managed.server.agentservice.session.AgentServiceEventReads;
import io.agentcore.managed.server.agentservice.session.AgentServiceResourceReads;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1/agent-sessions/{root}/subagents/{child}")
public class AgentServiceChildrenController {
    private final AgentServiceSessionRepository sessions;
    private final AgentServiceEventReads events;
    private final AgentServiceResourceReads resources;

    public AgentServiceChildrenController(
            AgentServiceSessionRepository sessions,
            AgentServiceEventReads events,
            AgentServiceResourceReads resources) {
        this.sessions = sessions;
        this.events = events;
        this.resources = resources;
    }

    private void linked(String root, String child) {
        sessions.findById(child)
                .filter(row -> root.equals(row.rootSessionId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @GetMapping({"", "/snapshot"})
    public Mono<Map<String, Object>> snapshot(
            @PathVariable String root,
            @PathVariable String child,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(
                        () -> {
                            linked(root, child);
                            return resources.snapshot(child, limit, headers);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping({
        "/resources/{kind}",
        "/{kind:items|tools|turns|runs|required-actions|usage|subagents}"
    })
    public Mono<Map<String, Object>> resources(
            @PathVariable String root,
            @PathVariable String child,
            @PathVariable String kind,
            @RequestParam(name = "snapshot_id", required = false) String snapshot,
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(
                        () -> {
                            linked(root, child);
                            String selected =
                                    kind.equals("usage") ? "model_usage" : kind.replace('-', '_');
                            return resources.page(child, selected, snapshot, after, limit, headers);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/events")
    public Mono<Map<String, Object>> events(
            @PathVariable String root,
            @PathVariable String child,
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(
                        () -> {
                            linked(root, child);
                            return events.page(child, after, limit, headers);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping(value = "/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            @PathVariable String root,
            @PathVariable String child,
            @RequestParam(required = false) String after,
            @RequestHeader(value = "Last-Event-ID", required = false) String last,
            @RequestHeader HttpHeaders headers) {
        return Mono.fromRunnable(() -> linked(root, child))
                .subscribeOn(Schedulers.boundedElastic())
                .thenMany(events.stream(child, null, after, last, headers));
    }
}
