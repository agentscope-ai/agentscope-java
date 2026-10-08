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
import io.agentscope.builder.web.managed.SessionNativeLogService;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.session.SessionProjection;
import io.agentscope.core.session.SessionRecovery;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Sensitive trace/repair surface is separately enabled and authorized, never part of public SSE. */
@RestController
@RequestMapping("/api/v1/agent-sessions/{session}/trace")
public class AgentSessionTraceController {
    private final DataSessionService sessions;
    private final SessionNativeLogService nativeLogs;
    private final boolean enabled;

    public AgentSessionTraceController(
            DataSessionService sessions,
            SessionNativeLogService nativeLogs,
            @Value("${builder.agent-api.trace-enabled:false}") boolean enabled) {
        this.sessions = sessions;
        this.nativeLogs = nativeLogs;
        this.enabled = enabled;
    }

    private SessionLog authorized(String session, Authentication auth) {
        if (!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (auth.getAuthorities().stream()
                .noneMatch(
                        a -> Set.of("ROLE_ADMIN", "ROLE_SESSION_TRACE").contains(a.getAuthority())))
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Session trace permission required");
        return nativeLogs.open(sessions.get((String) auth.getPrincipal(), session));
    }

    @GetMapping
    public Mono<Map<String, Object>> events(
            @PathVariable String session,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "100") int limit,
            Authentication auth) {
        return Mono.fromCallable(
                        () -> {
                            if (after < 0 || limit < 1 || limit > 500)
                                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                            var log = authorized(session, auth);
                            long asOf = log.head().seq();
                            if (after > asOf)
                                throw new ResponseStatusException(
                                        HttpStatus.CONFLICT, "Trace cursor exceeds durable head");
                            var batch = log.readAfter(after, limit);
                            return Map.<String, Object>of(
                                    "data",
                                    batch,
                                    "as_of_seq",
                                    asOf,
                                    "next_seq",
                                    batch.isEmpty() ? after : batch.get(batch.size() - 1).seq());
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/subagents/{child}")
    public Mono<Map<String, Object>> child(
            @PathVariable String session,
            @PathVariable String child,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "100") int limit,
            Authentication auth) {
        return Mono.fromCallable(
                        () -> {
                            authorized(session, auth);
                            if (after < 0 || limit < 1 || limit > 500)
                                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                            var log =
                                    nativeLogs.openChild(
                                            sessions.get((String) auth.getPrincipal(), session),
                                            child);
                            if (after > log.head().seq())
                                throw new ResponseStatusException(HttpStatus.CONFLICT);
                            var data = log.readAfter(after, limit);
                            return Map.<String, Object>of(
                                    "data",
                                    data,
                                    "as_of_seq",
                                    log.head().seq(),
                                    "next_seq",
                                    data.isEmpty() ? after : data.get(data.size() - 1).seq());
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/recovery")
    public Mono<SessionProjection> inspect(@PathVariable String session, Authentication auth) {
        return Mono.fromCallable(() -> SessionProjection.read(authorized(session, auth)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public record Reconcile(String reason, Map<String, ToolResultBlock> outcomes) {}

    @PostMapping("/reconcile")
    public Mono<SessionProjection> reconcile(
            @PathVariable String session, @RequestBody Reconcile input, Authentication auth) {
        return Mono.fromCallable(
                        () -> {
                            var log = authorized(session, auth);
                            if (input.outcomes() == null
                                    || input.outcomes().isEmpty()
                                    || input.reason() == null
                                    || input.reason().isBlank())
                                throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST,
                                        "Explicit outcomes and reason required");
                            SessionRecovery.reconcile(log, input.outcomes(), input.reason());
                            return SessionProjection.read(log);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
