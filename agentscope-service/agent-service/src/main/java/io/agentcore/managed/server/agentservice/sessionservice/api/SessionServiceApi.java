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
package io.agentcore.managed.server.agentservice.sessionservice.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import io.agentcore.managed.server.agentservice.sessionservice.store.HarnessProtocolStore;
import io.agentcore.managed.server.agentservice.sessionservice.store.HarnessRunConfiguration;
import io.agentcore.managed.server.agentservice.sessionservice.store.SessionServiceStore;
import io.agentcore.managed.server.agentservice.sessionservice.store.SessionServiceStore.PreparedRequest;
import io.agentcore.managed.server.agentservice.sessionservice.store.SessionServiceStore.Query;
import io.agentcore.managed.server.agentservice.sessionservice.store.SessionServiceStore.ResumeDeferral;
import io.agentcore.managed.server.agentservice.sessionservice.store.SessionServiceStore.ToolPublication;
import io.agentcore.managed.server.agentservice.sessionservice.store.SessionServiceWorkspace;
import io.agentcore.managed.server.runtime.managed.ManagedAdmissionStore;
import io.agentcore.managed.server.runtime.managed.ManagedBudgetStore;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import io.agentcore.managed.server.runtime.managed.ManagedStateException;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Internal service routes. Blocking persistence is confined to bounded workers. */
@RestController
@RequestMapping("/internal/session-service/v0")
public final class SessionServiceApi {
    private final SessionServiceStore store;
    private final HarnessProtocolStore protocol;
    private final SessionServiceWorkspace workspace;
    private final HarnessRunConfiguration configurations;
    private final ManagedBudgetStore budgets;
    private final ManagedAdmissionStore admission;

    public SessionServiceApi(
            SessionServiceStore store,
            HarnessProtocolStore protocol,
            SessionServiceWorkspace workspace,
            HarnessRunConfiguration configurations,
            ManagedBudgetStore budgets,
            ManagedAdmissionStore admission) {
        this.store = store;
        this.protocol = protocol;
        this.workspace = workspace;
        this.configurations = configurations;
        this.budgets = budgets;
        this.admission = admission;
    }

    @PostMapping({
        "/executions/{action}",
        "/objects/{action}",
        "/tools/{action}",
        "/recovery/{action}",
        "/commits/{action}",
        "/inputs/{action}",
        "/deliveries/{action}",
        "/models/{action}",
        "/previews/{action}"
    })
    public Mono<Object> operation(
            @PathVariable String action,
            @RequestBody JsonNode request,
            ServerWebExchange exchange) {
        String operation =
                exchange.getRequest()
                        .getPath()
                        .value()
                        .substring("/internal/session-service/v0/".length());
        return Mono.fromCallable(
                        () ->
                                Set.of("executions/claim", "models/reserve").contains(operation)
                                        ? budgets.observeDenial(
                                                mapperScope(request),
                                                request.path("turnId").asText(""),
                                                () -> (Object) protocol.invoke(operation, request))
                                        : (Object) protocol.invoke(operation, request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/executions/configuration")
    public Mono<Object> configuration(@RequestBody JsonNode request) {
        return Mono.fromCallable(
                        () -> {
                            protocol.invoke(
                                    "inputs/read",
                                    request); // Requires the current execution fence.
                            var scope = mapperScope(request);
                            var configuration =
                                    configurations.resolve(
                                            scope, request.required("turnId").asText());
                            protocol.invoke("inputs/read", request);
                            return (Object) configuration;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ManagedScope mapperScope(JsonNode request) {
        var value = request.required("scope");
        return new ManagedScope(
                value.required("tenant").asText(),
                value.required("namespace").asText(),
                value.required("sessionId").asText());
    }

    @PostMapping("/commits")
    public Mono<Object> boundary(@RequestBody JsonNode request) {
        return Mono.fromCallable(
                        () ->
                                budgets.observeDenial(
                                        mapperScope(request),
                                        request.path("turnId").asText(""),
                                        () -> (Object) protocol.invoke("commits", request)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public record Read(ManagedScope scope, String turnId, String commitId, long after, int limit) {}

    @PostMapping("/prepared/objects")
    public Mono<Object> object(@RequestBody JsonNode request) {
        return Mono.fromCallable(() -> store.object(request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/prepared/model")
    public Mono<Object> model(@RequestBody JsonNode request) {
        return Mono.fromCallable(
                        () ->
                                budgets.observeDenial(
                                        mapperScope(request),
                                        request.path("turnId").asText(""),
                                        () -> store.reserve(request, budgets, admission)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/prepared/workspace")
    public Mono<JsonNode> workspace(@RequestBody JsonNode request) {
        return Mono.fromCallable(() -> workspace.execute(request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/prepared/query")
    public Mono<Object> query(@RequestBody Query request) {
        return Mono.fromCallable(
                        () -> {
                            var value = store.query(request);
                            return value == null ? NullNode.instance : value;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/prepared/resume-defer")
    public Mono<Object> deferResume(@RequestBody ResumeDeferral request) {
        return Mono.fromCallable(() -> store.deferResume(request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/prepared/tool-publication")
    public Mono<Object> publishTool(@RequestBody ToolPublication request) {
        return Mono.fromCallable(() -> (Object) store.publishTool(request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/prepared/commits")
    public Mono<Object> commit(@RequestBody PreparedRequest request) {
        return Mono.fromCallable(
                        () ->
                                budgets.observeDenial(
                                        request.plan().boundary().scope(),
                                        request.plan().boundary().turnId(),
                                        () -> (Object) store.commit(request)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/prepared/receipt")
    public Mono<Object> receipt(@RequestBody Read request) {
        return Mono.fromCallable(
                        () -> {
                            var receipt = store.receipt(request.scope(), request.commitId());
                            if (receipt == null)
                                throw new ManagedStateException(
                                        "receipt_not_found", "Commit not found");
                            return (Object) receipt;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/prepared/head")
    public Mono<Object> head(@RequestBody Read request) {
        return Mono.fromCallable(() -> store.head(request.scope()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/prepared/recovery")
    public Mono<Object> recovery(@RequestBody Read request) {
        return Mono.fromCallable(
                        () ->
                                (Object)
                                        store.recovery(
                                                request.scope(),
                                                request.turnId(),
                                                request.after(),
                                                request.limit()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @ExceptionHandler(ManagedStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(ManagedStateException error) {
        return ResponseEntity.status(
                        "receipt_not_found".equals(error.code())
                                ? HttpStatus.NOT_FOUND
                                : HttpStatus.CONFLICT)
                .body(
                        error(
                                error.code(),
                                Set.of(
                                                "execution_busy",
                                                "capacity_exceeded",
                                                "temporarily_unavailable")
                                        .contains(error.code())));
    }

    private Map<String, Object> error(String code, boolean retryable) {
        return Map.of(
                "protocolVersion", "0.1.0", "code", code, "message", code, "retryable", retryable);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> unavailable(DataAccessException failure) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(error("temporarily_unavailable", true));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(error("invalid_request", false));
    }
}
