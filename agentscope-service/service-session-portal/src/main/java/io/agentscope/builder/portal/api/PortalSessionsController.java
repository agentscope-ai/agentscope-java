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

import io.agentscope.builder.portal.session.PortalLifecycles;
import io.agentscope.builder.portal.session.PortalRegistryReads;
import io.agentscope.builder.portal.session.PortalSessionAccess;
import io.agentscope.builder.portal.session.PortalSessionCreation;
import io.agentscope.builder.portal.session.PortalSessionProvisioning;
import io.agentscope.builder.portal.session.PortalTurnAdmission;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1/agent-sessions")
public class PortalSessionsController {
    private final PortalSessionCreation creation;
    private final PortalSessionProvisioning sessions;
    private final PortalSessionAccess access;
    private final PortalTurnAdmission turns;
    private final PortalRegistryReads reads;
    private final PortalLifecycles lifecycles;

    public PortalSessionsController(
            PortalSessionCreation creation,
            PortalSessionProvisioning sessions,
            PortalSessionAccess access,
            PortalTurnAdmission turns,
            PortalRegistryReads reads,
            PortalLifecycles lifecycles) {
        this.creation = creation;
        this.sessions = sessions;
        this.access = access;
        this.turns = turns;
        this.reads = reads;
        this.lifecycles = lifecycles;
    }

    @PostMapping("/{id}/archive")
    public Mono<Map<String, Object>> archive(
            @PathVariable String id, @RequestHeader HttpHeaders headers) {
        return lifecycle(id, "archived", headers);
    }

    @PostMapping("/{id}/restore")
    public Mono<Map<String, Object>> restore(
            @PathVariable String id, @RequestHeader HttpHeaders headers) {
        return lifecycle(id, "active", headers);
    }

    @DeleteMapping("/{id}")
    public Mono<Map<String, Object>> delete(
            @PathVariable String id, @RequestHeader HttpHeaders headers) {
        return lifecycle(id, "deleted", headers);
    }

    @GetMapping("/{id}/lifecycle-commands/{command}")
    public Mono<Map<String, Object>> lifecycleCommand(
            @PathVariable String id,
            @PathVariable String command,
            @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(() -> lifecycles.get(id, command, headers))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Map<String, Object>> lifecycle(String id, String desired, HttpHeaders headers) {
        return Mono.fromCallable(() -> lifecycles.stage(id, desired, null, headers))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping
    public Mono<Map<String, Object>> list(
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "active") String status,
            @RequestParam(required = false) String applicationId,
            @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(() -> reads.sessions(after, limit, status, applicationId, headers))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/turns")
    public Mono<Map<String, Object>> turns(
            @PathVariable String id,
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "asc") String order,
            @RequestParam(required = false) String status,
            @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(() -> reads.turns(id, after, limit, order, status, headers))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping
    public Mono<Map<String, Object>> create(
            @RequestBody Map<String, Object> request, @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(() -> creation.createLegacy(request, headers))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}")
    public Mono<Map<String, Object>> get(
            @PathVariable String id, @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(
                        () -> {
                            access.authorize(id, headers, "read");
                            return sessions.readyView(id);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/capabilities")
    public Mono<Map<String, Object>> capabilities(
            @PathVariable String id, @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(
                        () -> {
                            access.authorize(id, headers, "read");
                            return sessions.capabilities(id);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/{id}/turns")
    public Mono<ResponseEntity<Map<String, Object>>> turn(
            @PathVariable String id,
            @RequestBody Map<String, Object> input,
            @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(
                        () -> {
                            access.requireLegacyBackend(id, headers, "invoke");
                            return ResponseEntity.accepted().body(turns.accept(id, headers, input));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/turns/{turn}")
    public Mono<Map<String, Object>> turn(
            @PathVariable String id,
            @PathVariable String turn,
            @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(() -> turns.get(id, turn, headers))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
