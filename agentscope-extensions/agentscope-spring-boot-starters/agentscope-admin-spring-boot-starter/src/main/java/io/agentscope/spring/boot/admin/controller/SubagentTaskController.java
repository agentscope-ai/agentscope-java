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
package io.agentscope.spring.boot.admin.controller;

import io.agentscope.spring.boot.admin.audit.AdminAuditLogger;
import io.agentscope.spring.boot.admin.dto.SubagentTaskView;
import io.agentscope.spring.boot.admin.properties.AdminProperties;
import io.agentscope.spring.boot.admin.service.SubagentTaskOperations;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Data-plane REST for {@code BackgroundTask}s dispatched to subagents via
 * {@code TaskRepository}.
 *
 * <p>Path layout:
 *
 * <pre>
 *  GET    /v1/admin/sessions/{id}/subagent-tasks?status=RUNNING
 *  GET    /v1/admin/sessions/{id}/subagent-tasks/{taskId}
 *  DELETE /v1/admin/sessions/{id}/subagent-tasks/{taskId}        # cancel (write)
 * </pre>
 *
 * <p>When no {@code TaskRepository} bean is wired, {@code GET} returns 200 + empty list and
 * {@code DELETE} returns 404 — a deliberate fail-soft so the route is always reachable and
 * `/actuator/agentscope-doctor` can flag the missing repository as a config issue.
 */
@RestController
public class SubagentTaskController {

    private static final String OPERATOR_HEADER = "X-Agentscope-Admin-Operator";
    private static final String TOKEN_HEADER = "X-Agentscope-Admin-Token";

    private final SubagentTaskOperations ops;
    private final AdminAuditLogger audit;
    private final WriteGuard writeGuard;

    public SubagentTaskController(
            SubagentTaskOperations ops, AdminAuditLogger audit, AdminProperties properties) {
        this.ops = ops;
        this.audit = audit;
        this.writeGuard = new WriteGuard(properties);
    }

    @GetMapping(
            "${agentscope.admin.base-path:/v1/admin}/sessions/{sessionId}/subagent-tasks")
    public Mono<List<SubagentTaskView>> list(
            @PathVariable String sessionId,
            @RequestParam(value = "status", required = false) String status,
            @RequestHeader(value = OPERATOR_HEADER, required = false) String operator) {
        return ops.list(sessionId, status)
                .doOnSuccess(
                        list ->
                                audit.record(
                                        "subagent.task.list",
                                        operator,
                                        sessionId,
                                        false,
                                        "ok",
                                        Map.of("count", list.size(), "status", status == null ? "" : status)));
    }

    @GetMapping(
            "${agentscope.admin.base-path:/v1/admin}/sessions/{sessionId}/subagent-tasks/{taskId}")
    public Mono<ResponseEntity<SubagentTaskView>> get(
            @PathVariable String sessionId,
            @PathVariable String taskId,
            @RequestHeader(value = OPERATOR_HEADER, required = false) String operator) {
        return ops.get(sessionId, taskId)
                .map(
                        view -> {
                            audit.record(
                                    "subagent.task.get",
                                    operator,
                                    sessionId + "/" + taskId,
                                    false,
                                    view == null ? "not-found" : "ok",
                                    Map.of("status", view == null ? "" : view.status()));
                            return view == null
                                    ? ResponseEntity.notFound().<SubagentTaskView>build()
                                    : ResponseEntity.ok(view);
                        })
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @DeleteMapping(
            "${agentscope.admin.base-path:/v1/admin}/sessions/{sessionId}/subagent-tasks/{taskId}")
    public Mono<ResponseEntity<Map<String, Object>>> cancel(
            @PathVariable String sessionId,
            @PathVariable String taskId,
            @RequestHeader(value = OPERATOR_HEADER, required = false) String operator,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        writeGuard.check(token);
        return ops.cancel(sessionId, taskId)
                .map(
                        ok -> {
                            audit.record(
                                    "subagent.task.cancel",
                                    operator,
                                    sessionId + "/" + taskId,
                                    true,
                                    ok ? "ok" : "not-found",
                                    Map.of());
                            return ResponseEntity.status(ok ? HttpStatus.OK : HttpStatus.NOT_FOUND)
                                    .body(Map.<String, Object>of("cancelled", ok));
                        });
    }
}
