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

import io.agentscope.builder.web.coord.CoordinationStore;
import io.agentscope.builder.web.managed.DataSessionService;
import io.agentscope.builder.web.managed.EnvironmentWorkQueue;
import io.agentscope.builder.web.managed.ManagedSessionDto;
import io.agentscope.builder.web.managed.selfhosted.SessionInputStager;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

/**
 * REST surface for out-of-process Environment Workers on {@code self_hosted} environments.
 * Workers long-poll {@link #poll}, acknowledge with {@link #ack}, heartbeat, and {@link #stop}.
 * Tool execution is event-driven (pending tools + tool-results) — see {@link
 * SelfHostedWorkerController}.
 *
 * <p>Worker ops authenticate via {@code X-Builder-Environment-Key}; list/stats/get require a user
 * JWT.
 */
@RestController
@RequestMapping("/api/environments/{id}")
public class WorkerEnvironmentController {

    private final EnvironmentWorkQueue workQueue;
    private final DataSessionService sessionService;

    public WorkerEnvironmentController(
            EnvironmentWorkQueue workQueue, DataSessionService sessionService) {
        this.workQueue = workQueue;
        this.sessionService = sessionService;
    }

    /** Request body for ack: optional worker id and workspace directory. */
    public record AckRequest(String workerId, String workDir) {}

    /** Request body for optional work-item metadata update. */
    public record UpdateWorkRequest(String workerId, String workDir) {}

    /**
     * Long-poll for the next work item. Returns 204 when nothing is claimed within {@code
     * timeoutMs}.
     */
    @GetMapping("/work/poll")
    public Mono<ResponseEntity<EnvironmentWorkQueue.WorkItem>> poll(
            @PathVariable("id") String environmentId,
            @RequestParam("workerId") String workerId,
            @RequestParam(name = "timeoutMs", defaultValue = "25000") long timeoutMs) {
        return Mono.fromCallable(
                () -> {
                    try {
                        Optional<EnvironmentWorkQueue.WorkItem> item =
                                workQueue.poll(environmentId, workerId, timeoutMs);
                        return item.map(this::withSessionMetadata)
                                .map(ResponseEntity::ok)
                                .orElseGet(() -> ResponseEntity.noContent().build());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new ResponseStatusException(
                                HttpStatus.SERVICE_UNAVAILABLE, "Poll interrupted");
                    }
                });
    }

    private EnvironmentWorkQueue.WorkItem withSessionMetadata(EnvironmentWorkQueue.WorkItem item) {
        try {
            ManagedSessionDto session = sessionService.requireById(item.sessionId());
            Map<String, Object> metadata =
                    SessionInputStager.metadataFromResources(session.resources());
            return item.withMetadata(metadata);
        } catch (Exception ex) {
            return item;
        }
    }

    /** Lists work items for the environment, optionally filtered by {@code state}. */
    @GetMapping("/work")
    public Mono<List<EnvironmentWorkQueue.WorkItem>> listWork(
            @PathVariable("id") String environmentId,
            @RequestParam(value = "state", required = false) String state,
            Authentication auth) {
        requireUserAuth(auth);
        return Mono.fromCallable(() -> workQueue.list(environmentId, state));
    }

    /** Returns per-status counts and oldest queued age for the environment. */
    @GetMapping("/work/stats")
    public Mono<CoordinationStore.WorkStats> workStats(
            @PathVariable("id") String environmentId, Authentication auth) {
        requireUserAuth(auth);
        return Mono.fromCallable(() -> workQueue.stats(environmentId));
    }

    /** Returns a single work item by id. */
    @GetMapping("/work/{workId}")
    public Mono<EnvironmentWorkQueue.WorkItem> getWork(
            @PathVariable("id") String environmentId,
            @PathVariable("workId") String workId,
            Authentication auth) {
        requireUserAuth(auth);
        return Mono.fromCallable(
                () ->
                        workQueue
                                .get(workId)
                                .orElseThrow(
                                        () ->
                                                new ResponseStatusException(
                                                        HttpStatus.NOT_FOUND,
                                                        "Unknown work item: " + workId)));
    }

    /**
     * Acknowledges a polled work item and marks the row active. Under the event-driven
     * self-hosted model Brain does not create a local sandbox — the worker executes tools
     * out-of-process and posts {@code user.tool_result}.
     */
    @PostMapping("/work/{workId}/ack")
    public Mono<Void> ack(
            @PathVariable("id") String environmentId,
            @PathVariable("workId") String workId,
            @RequestBody(required = false) AckRequest req) {
        return Mono.fromRunnable(
                () -> {
                    EnvironmentWorkQueue.WorkItem item =
                            workQueue
                                    .get(workId)
                                    .orElseThrow(
                                            () ->
                                                    new ResponseStatusException(
                                                            HttpStatus.NOT_FOUND,
                                                            "Unknown work item: " + workId));
                    String workerId =
                            req != null && req.workerId() != null && !req.workerId().isBlank()
                                    ? req.workerId()
                                    : item.claimedBy() != null ? item.claimedBy() : "remote-worker";
                    String workDir =
                            req != null && req.workDir() != null && !req.workDir().isBlank()
                                    ? req.workDir()
                                    : defaultWorkDir(item.sessionId()).toString();
                    workQueue.ack(workId, workerId, workDir);
                });
    }

    /** Bumps {@code updated_at} on an active work item (lease heartbeat). */
    @PostMapping("/work/{workId}/heartbeat")
    public Mono<Void> heartbeat(
            @PathVariable("id") String environmentId, @PathVariable("workId") String workId) {
        return Mono.fromRunnable(() -> workQueue.heartbeat(workId));
    }

    /** Marks a work item stopped once the session turn has released the sandbox. */
    @PostMapping("/work/{workId}/stop")
    public Mono<Void> stop(
            @PathVariable("id") String environmentId, @PathVariable("workId") String workId) {
        return Mono.fromRunnable(() -> workQueue.stop(workId));
    }

    /** Optional metadata update on an existing work item. */
    @PostMapping("/work/{workId}")
    public Mono<Void> updateWork(
            @PathVariable("id") String environmentId,
            @PathVariable("workId") String workId,
            @RequestBody(required = false) UpdateWorkRequest req) {
        return Mono.fromRunnable(
                () -> {
                    EnvironmentWorkQueue.WorkItem item =
                            workQueue
                                    .get(workId)
                                    .orElseThrow(
                                            () ->
                                                    new ResponseStatusException(
                                                            HttpStatus.NOT_FOUND,
                                                            "Unknown work item: " + workId));
                    String workerId =
                            req != null && req.workerId() != null
                                    ? req.workerId()
                                    : item.claimedBy();
                    String workDir =
                            req != null && req.workDir() != null ? req.workDir() : item.workDir();
                    workQueue.ack(workId, workerId, workDir);
                });
    }

    private static void requireUserAuth(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        String principal = String.valueOf(auth.getPrincipal());
        if (principal.startsWith("env:")) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Environment worker credentials cannot list work");
        }
    }

    private static Path defaultWorkDir(String sessionId) {
        return Paths.get(System.getProperty("java.io.tmpdir"), "agentscope-hands", sessionId);
    }
}
