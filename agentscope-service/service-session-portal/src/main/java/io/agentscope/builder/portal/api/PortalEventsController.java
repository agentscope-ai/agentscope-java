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
package io.agentscope.builder.portal.api;

import io.agentscope.builder.portal.session.PortalEventReads;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1/agent-sessions/{id}")
public class PortalEventsController {
    private final PortalEventReads events;

    public PortalEventsController(PortalEventReads events) {
        this.events = events;
    }

    @GetMapping({"/events", "/turns/{turn}/events"})
    public Mono<Map<String, Object>> list(
            @PathVariable String id,
            @PathVariable(required = false) String turn,
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(() -> events.page(id, turn, after, limit, headers))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping({"/events/{event}", "/turns/{turn}/events/{event}"})
    public Mono<Map<String, Object>> get(
            @PathVariable String id,
            @PathVariable(required = false) String turn,
            @PathVariable String event,
            @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(() -> events.get(id, turn, event, headers))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping(
            value = {"/events/stream", "/turns/{turn}/events/stream"},
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            @PathVariable String id,
            @PathVariable(required = false) String turn,
            @RequestParam(required = false) String after,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            @RequestHeader HttpHeaders headers) {
        return events.stream(id, turn, after, lastEventId, headers);
    }

    @GetMapping(
            value = {"/export", "/turns/{turn}/export"},
            produces = MediaType.APPLICATION_NDJSON_VALUE)
    public Mono<Void> export(
            @PathVariable String id,
            @PathVariable(required = false) String turn,
            @RequestParam(required = false) String after,
            @RequestParam(required = false) String through,
            @RequestParam(defaultValue = "1000") int limit,
            @RequestHeader HttpHeaders headers,
            ServerHttpResponse response) {
        return events.export(id, turn, after, through, limit, headers, response);
    }
}
