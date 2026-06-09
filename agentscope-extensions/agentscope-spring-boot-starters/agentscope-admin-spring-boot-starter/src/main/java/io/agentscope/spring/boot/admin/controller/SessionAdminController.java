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

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.spring.boot.admin.audit.AdminAuditLogger;
import io.agentscope.spring.boot.admin.dto.AgentTaskView;
import io.agentscope.spring.boot.admin.dto.CompactRequest;
import io.agentscope.spring.boot.admin.dto.CompactResponse;
import io.agentscope.spring.boot.admin.dto.MessageView;
import io.agentscope.spring.boot.admin.dto.PlanModeView;
import io.agentscope.spring.boot.admin.properties.AdminProperties;
import io.agentscope.spring.boot.admin.service.SessionOperations;
import jakarta.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Data-plane REST surface. Route prefix is derived from
 * {@code agentscope.admin.base-path} (default {@code /v1/admin}).
 *
 * <p>Verbs follow Google AIP-136 (resource:action) so OpenAPI generators and CLIs can map them to
 * idiomatic command names — e.g. {@code POST /v1/admin/sessions/{id}:compact} surfaces as
 * {@code session compact <id>} in a downstream CLI.
 */
@RestController
public class SessionAdminController {

    private static final String OPERATOR_HEADER = "X-Agentscope-Admin-Operator";
    private static final String TOKEN_HEADER = "X-Agentscope-Admin-Token";

    private final SessionOperations ops;
    private final ObjectProvider<AgentStateStore> sessions;
    private final AdminAuditLogger audit;
    private final WriteGuard writeGuard;

    public SessionAdminController(
            SessionOperations ops,
            ObjectProvider<AgentStateStore> sessions,
            AdminAuditLogger audit,
            AdminProperties properties) {
        this.ops = ops;
        this.sessions = sessions;
        this.audit = audit;
        this.writeGuard = new WriteGuard(properties);
    }

    @GetMapping("${agentscope.admin.base-path:/v1/admin}/sessions")
    public Mono<List<String>> listSessions(
            @RequestHeader(value = OPERATOR_HEADER, required = false) String operator) {
        AgentStateStore stateStore = sessions.getIfAvailable();
        return ops.listSessions(stateStore)
                .doOnSuccess(
                        list ->
                                audit.record(
                                        "session.list",
                                        operator,
                                        "*",
                                        false,
                                        "ok",
                                        Map.of("count", list.size())));
    }

    @GetMapping("${agentscope.admin.base-path:/v1/admin}/sessions/{sessionId}/messages")
    public Mono<List<MessageView>> messages(
            @PathVariable String sessionId,
            @RequestHeader(value = OPERATOR_HEADER, required = false) String operator) {
        return ops.listMessages(sessionId)
                .doOnSuccess(
                        list ->
                                audit.record(
                                        "session.messages",
                                        operator,
                                        sessionId,
                                        false,
                                        "ok",
                                        Map.of("count", list.size())));
    }

    @GetMapping(
            path = "${agentscope.admin.base-path:/v1/admin}/sessions/{sessionId}/state",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<String> stateDump(
            @PathVariable String sessionId,
            @RequestHeader(value = OPERATOR_HEADER, required = false) String operator) {
        return ops.dumpStateJson(sessionId)
                .doOnSuccess(
                        json ->
                                audit.record(
                                        "session.state",
                                        operator,
                                        sessionId,
                                        false,
                                        "ok",
                                        Map.of("bytes", json.length())));
    }

    @GetMapping(
            path = "${agentscope.admin.base-path:/v1/admin}/sessions/{sessionId}:export",
            produces = "text/markdown")
    public Mono<ResponseEntity<String>> export(
            @PathVariable String sessionId,
            @RequestHeader(value = OPERATOR_HEADER, required = false) String operator) {
        return ops.exportMarkdown(sessionId)
                .map(
                        md -> {
                            audit.record(
                                    "session.export",
                                    operator,
                                    sessionId,
                                    false,
                                    "ok",
                                    Map.of("bytes", md.length()));
                            return ResponseEntity.ok()
                                    .header(
                                            "Content-Disposition",
                                            "attachment; filename=session-" + sessionId + ".md")
                                    .body(md);
                        });
    }

    @PostMapping("${agentscope.admin.base-path:/v1/admin}/sessions/{sessionId}:compact")
    public Mono<CompactResponse> compact(
            @PathVariable String sessionId,
            @RequestBody(required = false) @Nullable CompactRequest request,
            @RequestHeader(value = OPERATOR_HEADER, required = false) String operator,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        writeGuard.check(token);
        return ops.compact(sessionId, request)
                .doOnSuccess(
                        resp -> {
                            Map<String, Object> attrs = new HashMap<>();
                            attrs.put("messages_before", resp.messagesBefore());
                            attrs.put("messages_after", resp.messagesAfter());
                            attrs.put("summary_len_before", resp.summaryLengthBefore());
                            attrs.put("summary_len_after", resp.summaryLengthAfter());
                            audit.record("session.compact", operator, sessionId, true, "ok", attrs);
                        });
    }

    @PostMapping("${agentscope.admin.base-path:/v1/admin}/sessions/{sessionId}:abort")
    public Mono<ResponseEntity<Void>> abort(
            @PathVariable String sessionId,
            @RequestHeader(value = OPERATOR_HEADER, required = false) String operator,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        writeGuard.check(token);
        return ops.abort(sessionId)
                .doOnSuccess(
                        v ->
                                audit.record(
                                        "session.abort", operator, sessionId, true, "ok", Map.of()))
                .thenReturn(ResponseEntity.accepted().<Void>build());
    }

    @PostMapping("${agentscope.admin.base-path:/v1/admin}/sessions/{sessionId}:undo")
    public Mono<SessionOperations.UndoResult> undo(
            @PathVariable String sessionId,
            @RequestHeader(value = OPERATOR_HEADER, required = false) String operator,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        writeGuard.check(token);
        return ops.undo(sessionId)
                .doOnSuccess(
                        r ->
                                audit.record(
                                        "session.undo",
                                        operator,
                                        sessionId,
                                        true,
                                        r.restored() ? "ok" : "noop",
                                        Map.of(
                                                "undo_depth", r.undoDepth(),
                                                "redo_depth", r.redoDepth())));
    }

    @PostMapping("${agentscope.admin.base-path:/v1/admin}/sessions/{sessionId}:redo")
    public Mono<SessionOperations.UndoResult> redo(
            @PathVariable String sessionId,
            @RequestHeader(value = OPERATOR_HEADER, required = false) String operator,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        writeGuard.check(token);
        return ops.redo(sessionId)
                .doOnSuccess(
                        r ->
                                audit.record(
                                        "session.redo",
                                        operator,
                                        sessionId,
                                        true,
                                        r.restored() ? "ok" : "noop",
                                        Map.of(
                                                "undo_depth", r.undoDepth(),
                                                "redo_depth", r.redoDepth())));
    }

    // ------------------------------------------------------------------------
    // Plan mode
    // ------------------------------------------------------------------------

    @GetMapping("${agentscope.admin.base-path:/v1/admin}/sessions/{sessionId}/plan")
    public Mono<PlanModeView> plan(
            @PathVariable String sessionId,
            @RequestHeader(value = OPERATOR_HEADER, required = false) String operator) {
        return ops.planState(sessionId)
                .doOnSuccess(
                        v ->
                                audit.record(
                                        "session.plan",
                                        operator,
                                        sessionId,
                                        false,
                                        "ok",
                                        Map.of(
                                                "plan_active",
                                                v.planActive(),
                                                "middleware",
                                                v.planMiddlewareEnabled())));
    }

    @PostMapping("${agentscope.admin.base-path:/v1/admin}/sessions/{sessionId}:enter-plan-mode")
    public Mono<PlanModeView> enterPlanMode(
            @PathVariable String sessionId,
            @RequestHeader(value = OPERATOR_HEADER, required = false) String operator,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        writeGuard.check(token);
        return ops.enterPlanMode(sessionId)
                .doOnSuccess(
                        v ->
                                audit.record(
                                        "session.enter_plan_mode",
                                        operator,
                                        sessionId,
                                        true,
                                        v.planMiddlewareEnabled() ? "ok" : "ok-no-middleware",
                                        Map.of("plan_active", v.planActive())));
    }

    @PostMapping("${agentscope.admin.base-path:/v1/admin}/sessions/{sessionId}:exit-plan-mode")
    public Mono<PlanModeView> exitPlanMode(
            @PathVariable String sessionId,
            @RequestHeader(value = OPERATOR_HEADER, required = false) String operator,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        writeGuard.check(token);
        return ops.exitPlanMode(sessionId)
                .doOnSuccess(
                        v ->
                                audit.record(
                                        "session.exit_plan_mode",
                                        operator,
                                        sessionId,
                                        true,
                                        "ok",
                                        Map.of("plan_active", v.planActive())));
    }

    // ------------------------------------------------------------------------
    // Per-session agent task list (in-AgentState todo work items)
    // ------------------------------------------------------------------------

    @GetMapping("${agentscope.admin.base-path:/v1/admin}/sessions/{sessionId}/tasks")
    public Mono<List<AgentTaskView>> agentTasks(
            @PathVariable String sessionId,
            @RequestHeader(value = OPERATOR_HEADER, required = false) String operator) {
        return ops.listAgentTasks(sessionId)
                .doOnSuccess(
                        list ->
                                audit.record(
                                        "session.tasks",
                                        operator,
                                        sessionId,
                                        false,
                                        "ok",
                                        Map.of("count", list.size())));
    }
}
