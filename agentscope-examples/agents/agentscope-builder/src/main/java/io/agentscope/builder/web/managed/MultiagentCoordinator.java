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
package io.agentscope.builder.web.managed;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Multiagent fan-out over managed sessions. Each agent gets its own session; optional parallel
 * execution via {@code parallel=true}.
 */
@RestController
@RequestMapping("/api/multiagent")
public class MultiagentCoordinator {

    private static final Logger log = LoggerFactory.getLogger(MultiagentCoordinator.class);

    private final ManagedSessionService sessionService;
    private final SessionEventLog eventLog;

    public MultiagentCoordinator(ManagedSessionService sessionService, SessionEventLog eventLog) {
        this.sessionService = sessionService;
        this.eventLog = eventLog;
    }

    public record RunRequest(
            List<String> agentIds, String message, String environmentId, Boolean parallel) {}

    public record AgentRunResult(
            String agentId, String sessionId, String status, String reply, String error) {}

    public record RunResponse(List<AgentRunResult> results) {}

    @PostMapping("/run")
    public Mono<RunResponse> run(@RequestBody RunRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        validate(req);
        boolean parallel = Boolean.TRUE.equals(req.parallel());
        Flux<String> agents = Flux.fromIterable(req.agentIds());
        Flux<AgentRunResult> runs =
                parallel
                        ? agents.flatMap(
                                id ->
                                        Mono.fromCallable(
                                                        () ->
                                                                runOne(
                                                                        userId,
                                                                        id,
                                                                        req.message(),
                                                                        req.environmentId()))
                                                .subscribeOn(Schedulers.boundedElastic()),
                                Math.min(8, req.agentIds().size()))
                        : agents.concatMap(
                                id ->
                                        Mono.fromCallable(
                                                        () ->
                                                                runOne(
                                                                        userId,
                                                                        id,
                                                                        req.message(),
                                                                        req.environmentId()))
                                                .subscribeOn(Schedulers.boundedElastic()));
        return runs.collectList().map(RunResponse::new);
    }

    private AgentRunResult runOne(
            String userId, String agentId, String message, String environmentId) {
        try {
            ManagedSessionService.CreateSessionRequest create =
                    new ManagedSessionService.CreateSessionRequest(
                            agentId, environmentId, null, null, null);
            ManagedSessionDto session = sessionService.create(userId, create);
            sessionService.runTurn(
                    userId, session.id(), Map.of("text", message == null ? "" : message));
            String reply = awaitReply(userId, session.id(), 120_000L);
            ManagedSessionDto after = sessionService.get(userId, session.id());
            return new AgentRunResult(agentId, session.id(), after.status(), reply, null);
        } catch (Exception ex) {
            log.warn("Multiagent run failed: agentId={}, error={}", agentId, ex.getMessage());
            return new AgentRunResult(agentId, null, "error", null, describeError(ex));
        }
    }

    private String awaitReply(String userId, String sessionId, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String last = "";
        while (System.currentTimeMillis() < deadline) {
            List<SessionEventDto> events = eventLog.list(sessionId);
            for (int i = events.size() - 1; i >= 0; i--) {
                SessionEventDto e = events.get(i);
                if ("agent.message".equals(e.type()) && e.payload() != null) {
                    Object text = e.payload().get("text");
                    if (text != null && !String.valueOf(text).isBlank()) {
                        last = String.valueOf(text);
                        break;
                    }
                }
            }
            ManagedSessionDto after = sessionService.get(userId, sessionId);
            if (ManagedSessionService.STATUS_IDLE.equals(after.status())
                    || ManagedSessionService.STATUS_TERMINATED.equals(after.status())) {
                return last;
            }
            Thread.sleep(400L);
        }
        return last;
    }

    private static String describeError(Throwable ex) {
        if (ex instanceof ResponseStatusException rse) {
            return rse.getReason() != null ? rse.getReason() : rse.getMessage();
        }
        return ex.getMessage();
    }

    private static void validate(RunRequest req) {
        if (req == null || req.agentIds() == null || req.agentIds().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "agentIds required");
        }
        if (req.message() == null || req.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message required");
        }
    }
}
