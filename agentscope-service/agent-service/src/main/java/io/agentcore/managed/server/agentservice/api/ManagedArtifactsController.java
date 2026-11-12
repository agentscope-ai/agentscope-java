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
package io.agentcore.managed.server.agentservice.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionRepository;
import io.agentcore.managed.server.agentservice.session.AgentServiceSessionAccess;
import io.agentcore.managed.server.agentservice.session.ManagedSessionTurnsApi;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/v1/sessions/{session}/artifacts")
public final class ManagedArtifactsController {
    private record Caller(ManagedScope scope, String actor) {}

    private final AgentServiceSessionRepository sessions;
    private final AgentServiceSessionAccess access;
    private final ManagedJournalStore journal;
    private final ManagedSessionTurnsApi queries;
    private final ManagedFilesController downloads;
    private final ObjectMapper mapper;

    public ManagedArtifactsController(
            AgentServiceSessionRepository sessions,
            AgentServiceSessionAccess access,
            ManagedJournalStore journal,
            ManagedSessionTurnsApi queries,
            ManagedFilesController downloads,
            ObjectMapper mapper) {
        this.sessions = sessions;
        this.access = access;
        this.journal = journal;
        this.queries = queries;
        this.downloads = downloads;
        this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    @PostMapping
    public Mono<Map<String, Object>> publish(
            @PathVariable String session,
            @RequestHeader HttpHeaders headers,
            @RequestBody Map<String, Object> body) {
        return Mono.fromCallable(
                        () -> {
                            var caller = authorize(session, headers, "interact");
                            var result =
                                    journal.artifacts()
                                            .publish(
                                                    caller.scope(),
                                                    caller.actor(),
                                                    headers.getFirst("Idempotency-Key"),
                                                    body);

                            return result;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping
    public Mono<Map<String, Object>> list(
            @PathVariable String session,
            @RequestHeader HttpHeaders headers,
            @RequestParam(required = false) String snapshot_id,
            @RequestParam(required = false) String after,
            @RequestParam(required = false) String turn,
            @RequestParam(defaultValue = "100") int limit) {
        return Mono.fromCallable(
                        () -> queries.artifacts(session, turn, snapshot_id, after, limit, headers))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}")
    public Mono<Map<String, Object>> get(
            @PathVariable String session,
            @PathVariable String id,
            @RequestHeader HttpHeaders headers,
            @RequestParam(required = false) String snapshot_id) {
        return Mono.fromCallable(
                        () ->
                                journal.artifacts()
                                        .metadata(
                                                authorize(session, headers, "read").scope(),
                                                id,
                                                snapshot_id))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{id}")
    public Mono<Void> delete(
            @PathVariable String session,
            @PathVariable String id,
            @RequestHeader HttpHeaders headers) {
        return Mono.fromRunnable(
                        () -> {
                            var caller = authorize(session, headers, "interact");
                            journal.artifacts().delete(caller.scope(), id);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @GetMapping("/{id}/content")
    public Mono<Void> content(
            @PathVariable String session,
            @PathVariable String id,
            @RequestHeader HttpHeaders headers,
            ServerHttpResponse response) {
        return get(session, id, headers, null)
                .flatMap(
                        artifact -> {
                            if (artifact.get("file_id") instanceof String file)
                                return downloads.content(session, file, headers, response);
                            return Mono.error(
                                    new ResponseStatusException(
                                            HttpStatus.UNPROCESSABLE_ENTITY,
                                            "External URI artifact content is available at its"
                                                    + " URI"));
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
