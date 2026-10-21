/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.hands;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.builder.runtime.managed.ManagedExecutionTargets;
import io.agentscope.builder.runtime.managed.ManagedHandsStore;
import io.agentscope.builder.runtime.managed.ManagedJournalStore;
import io.agentscope.builder.runtime.managed.ManagedLeaseStore;
import io.agentscope.builder.runtime.managed.ManagedSandboxBindings;
import io.agentscope.builder.runtime.managed.ManagedToolResult;
import io.agentscope.builder.runtime.managed.ManagedToolResultPublisher;
import io.agentscope.builder.runtime.managed.ManagedTransportException;
import io.agentscope.builder.runtime.managed.ManagedWorkerProtocol;
import io.agentscope.builder.runtime.managed.RedisManagedInfrastructure;
import io.agentscope.builder.web.auth.InternalTokenAuthFilter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/** Any Hands replica handles claim/renew/result through Redis tokens and immutable DB facts. */
@Service
public final class ManagedWorkerService {
    private static final Logger log = LoggerFactory.getLogger(ManagedWorkerService.class);
    public static final String ENVIRONMENT_KEY = "X-Builder-Environment-Key";
    private static final Duration TTL = Duration.ofSeconds(60);
    private final ManagedExecutionTargets catalog;
    private final ManagedSandboxBindings bindings;
    private final ManagedHandsStore journal;
    private final RedisManagedInfrastructure redis;
    private final ManagedToolResultPublisher results;
    private final ObjectMapper mapper;
    private final WebClient control;
    private final Semaphore claims = new Semaphore(32);
    private final Semaphore callbacks = new Semaphore(64);

    public ManagedWorkerService(
            ManagedExecutionTargets catalog,
            ManagedHandsStore journal,
            RedisManagedInfrastructure redis,
            ManagedToolResultPublisher results,
            ObjectMapper mapper,
            DataSource source,
            @Value("${builder.control-plane-url:http://localhost:8081}") String controlUrl,
            @Value("${builder.internal-token:${BUILDER_INTERNAL_TOKEN:}}") String token) {
        this.catalog = catalog;
        this.bindings = new ManagedSandboxBindings(source);
        this.journal = journal;
        this.redis = redis;
        this.results = results;
        this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        control =
                WebClient.builder()
                        .baseUrl(controlUrl)
                        .defaultHeader(InternalTokenAuthFilter.INTERNAL_TOKEN_HEADER, token)
                        .build();
    }

    public List<ManagedWorkerProtocol.Assignment> claim(
            String environment, String key, ManagedWorkerProtocol.Claim request) {
        if (!claims.tryAcquire()) throw limited();
        try {
            authenticate(environment, key);
            var result = new ArrayList<ManagedWorkerProtocol.Assignment>();
            for (var ref : journal.pendingTools(256, false)) {
                var candidate = journal.tool(ref.scope(), ref.taskId()).orElseThrow();
                if (candidate.name().startsWith("mcp.")
                        || candidate.name().equals("sandbox.cleanup")) continue;
                var resolved = catalog.resolve(ref.scope());
                var env = resolved.environment();
                if (env == null
                        || !"self_hosted".equals(env.type())
                        || !environment.equals(env.id())) continue;
                var acquired = redis.acquire(ref.scope().key("tool:" + ref.taskId()), TTL);
                if (acquired.isEmpty()) continue;
                var token = acquired.get();
                try {
                    var claim = journal.claimTool(ref.scope(), ref.taskId(), token.token());
                    var task = claim.task();
                    if (task.result() != null
                            || ("result_pending".equals(task.status())
                                    && !journal.toolEvents().unfinished(ref.scope(), task.id()))) {
                        redis.release(token);
                        continue;
                    }
                    journal.toolEvents()
                            .open(
                                    ref.scope(),
                                    task,
                                    token.token(),
                                    "sandbox.exec".equals(task.name()));
                    var config = bindings.borrow(ref.scope(), resolved);
                    var lease =
                            new ManagedWorkerProtocol.Lease(
                                    ref.scope(),
                                    task.id(),
                                    task.executionId(),
                                    token.token(),
                                    request.workerId());
                    result.add(
                            new ManagedWorkerProtocol.Assignment(
                                    lease,
                                    new ManagedWorkerProtocol.Task(
                                            task.id(),
                                            task.turnId(),
                                            task.name(),
                                            task.arguments(),
                                            task.processHandle()),
                                    claim.firstLaunch(),
                                    Map.copyOf(config),
                                    resolved.definitionFiles() == null
                                            ? Map.of()
                                            : resolved.definitionFiles()));
                    if (task.name().startsWith("workspace.") || result.size() >= request.limit())
                        break;
                } catch (RuntimeException failure) {
                    redis.release(token);
                    throw failure;
                }
            }
            return List.copyOf(result);
        } finally {
            claims.release();
        }
    }

    public Map<String, Object> renew(
            String environment, String key, ManagedWorkerProtocol.Lease lease) {
        if (!callbacks.tryAcquire()) throw limited();
        try {
            authenticate(environment, key);
            var task = task(environment, lease);
            var token =
                    new ManagedLeaseStore.Lease(
                            lease.scope().key("tool:" + lease.taskId()), lease.token());
            if (!lease.token().equals(task.workerToken()) || !redis.renew(token, TTL))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Worker lease lost");
            var result = new LinkedHashMap<String, Object>();
            result.put("ttl_seconds", TTL.toSeconds());
            result.put("cancel_requested", journal.cancelRequested(lease.scope(), task));
            result.put("status", task.status());
            journal.toolEvents()
                    .cursor(lease.scope(), task.id())
                    .ifPresent(cursor -> result.put("output_cursor", cursor));
            if (task.result() != null) result.put("result", task.result());
            return result;
        } finally {
            callbacks.release();
        }
    }

    public Map<String, Object> started(
            String environment, String key, ManagedWorkerProtocol.Lease lease) {
        authenticate(environment, key);
        var current = task(environment, lease);
        var token =
                new ManagedLeaseStore.Lease(
                        lease.scope().key("tool:" + lease.taskId()), lease.token());
        if (!redis.owns(token) || !lease.token().equals(current.workerToken()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Worker lease lost");
        journal.recordToolHandle(lease.scope(), lease.taskId(), lease.token(), lease.executionId());
        return Map.of("status", "running");
    }

    public Map<String, Object> unknown(
            String environment, String key, ManagedWorkerProtocol.Lease lease) {
        if (!callbacks.tryAcquire()) throw limited();
        try {
            authenticate(environment, key);
            var current = task(environment, lease);
            var token =
                    new ManagedLeaseStore.Lease(
                            lease.scope().key("tool:" + lease.taskId()), lease.token());
            if (!redis.owns(token) || !lease.token().equals(current.workerToken()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Worker lease lost");
            journal.markToolUnknown(lease.scope(), lease.taskId(), lease.token());
            return Map.of("status", "unknown");
        } finally {
            callbacks.release();
        }
    }

    public Object output(String environment, String key, ManagedWorkerProtocol.Output body) {
        if (!callbacks.tryAcquire()) throw limited();
        try {
            authenticate(environment, key);
            var lease = body.lease();
            var current = task(environment, lease);
            if (!redis.owns(
                    new ManagedLeaseStore.Lease(
                            lease.scope().key("tool:" + lease.taskId()), lease.token())))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Worker lease lost");
            var cursor =
                    journal.toolEvents()
                            .capture(lease.scope(), current, lease.token(), body.page());
            results.flush(lease.scope(), current.id());
            return cursor;
        } finally {
            callbacks.release();
        }
    }

    /** A confirmed original result remains valid after a Worker/Brain takeover. */
    public Map<String, Object> result(
            String environment, String key, ManagedWorkerProtocol.Result body) {
        if (!claims.tryAcquire()) throw limited();
        try {
            authenticate(environment, key);
            var lease = body.lease();
            task(environment, lease);
            String canonical;
            try {
                canonical =
                        mapper.writeValueAsString(mapper.readValue(body.result(), Object.class));
            } catch (Exception invalid) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Worker result must be JSON");
            }
            var result =
                    new ManagedToolResult(
                            lease.scope(),
                            lease.taskId(),
                            lease.executionId(),
                            body.status(),
                            canonical);
            results.capture(result);
            try {
                results.publishStored(lease.scope(), lease.taskId());
            } catch (ManagedTransportException unavailable) {
                log.debug("Captured Worker result awaits MQ publication: task={}", lease.taskId());
            }
            redis.release(
                    new ManagedLeaseStore.Lease(
                            lease.scope().key("tool:" + lease.taskId()), lease.token()));
            return Map.of("status", "captured");
        } finally {
            claims.release();
        }
    }

    private ManagedJournalStore.ToolTask task(
            String environment, ManagedWorkerProtocol.Lease lease) {
        var resolved = catalog.resolve(lease.scope());
        var env = resolved.environment();
        if (env == null || !environment.equals(env.id()) || !"self_hosted".equals(env.type()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Environment scope differs");
        var task =
                journal.tool(lease.scope(), lease.taskId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!lease.executionId().equals(task.executionId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Original execution differs");
        return task;
    }

    void authenticate(String environment, String key) {
        if (environment == null || environment.isBlank() || environment.length() > 256)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Environment id");
        if (key == null || key.isBlank() || key.length() > 4096)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Environment key required");
        try {
            var verified =
                    control.post()
                            .uri("/api/internal/environments/{id}/verify-key", environment)
                            .bodyValue(Map.of("key", key))
                            .retrieve()
                            .bodyToMono(Map.class)
                            .block(Duration.ofSeconds(3));
            if (verified == null || !Boolean.TRUE.equals(verified.get("ok")))
                throw new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Invalid Environment key");
        } catch (WebClientResponseException failure) {
            throw new ResponseStatusException(
                    failure.getStatusCode(), "Environment verification rejected");
        }
    }

    private static ResponseStatusException limited() {
        return new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS, "Hands HTTP capacity reached");
    }
}
