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
package io.agentscope.builder.portal.api;

import io.agentscope.builder.portal.session.ManagedSessionTurnsApi;
import io.agentscope.builder.portal.session.PortalCheckpointWrites;
import io.agentscope.builder.portal.session.PortalSessionAccess;
import io.agentscope.builder.portal.session.PortalSessionBudgets;
import io.agentscope.builder.portal.session.PortalSessionCheckpoints;
import io.agentscope.builder.portal.session.SessionApi;
import io.agentscope.builder.runtime.protocol.RuntimeRequestContext;
import io.agentscope.builder.web.auth.InternalTokenAuthFilter;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Trusted service calls share the native business API and retain the original Actor and Scope. */
@RestController
@RequestMapping("/api/internal/managed/sessions/{session}")
public final class ManagedRuntimeController {
    public record Request(
            RuntimeRequestContext context,
            String operation,
            Map<String, Object> payload,
            String after,
            String order,
            String status,
            Integer limit,
            String inputId,
            String snapshotId,
            String resourceKind,
            String desired,
            String receiptId,
            String resourceId,
            String expectedRevision,
            Integer offset,
            String through) {}

    private final PortalSessionAccess access;
    private final ManagedSessionTurnsApi turns;
    private final SessionApi sessions;
    private final PortalSessionBudgets budgets;
    private final ManagedArtifactsController artifacts;
    private final ManagedFilesController files;
    private final PortalSessionCheckpoints checkpoints;
    private final PortalCheckpointWrites checkpointWrites;

    public ManagedRuntimeController(
            PortalSessionAccess access,
            ManagedSessionTurnsApi turns,
            SessionApi sessions,
            PortalSessionBudgets budgets,
            ManagedArtifactsController artifacts,
            ManagedFilesController files,
            PortalSessionCheckpoints checkpoints,
            PortalCheckpointWrites checkpointWrites) {
        this.access = access;
        this.turns = turns;
        this.sessions = sessions;
        this.budgets = budgets;
        this.artifacts = artifacts;
        this.files = files;
        this.checkpoints = checkpoints;
        this.checkpointWrites = checkpointWrites;
    }

    @PostMapping("/request")
    public Mono<Map<String, Object>> request(
            @PathVariable String session,
            @RequestBody Request request,
            @RequestHeader(InternalTokenAuthFilter.INTERNAL_USER_HEADER) String owner,
            Authentication authentication) {
        return Mono.defer(() -> invoke(session, owner, request, authentication))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Map<String, Object>> invoke(
            String session, String owner, Request request, Authentication authentication) {
        if (authentication == null
                || authentication.getAuthorities().stream()
                        .noneMatch(
                                role ->
                                        InternalTokenAuthFilter.ROLE_INTERNAL.equals(
                                                role.getAuthority())))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if (request.context() == null || request.operation() == null)
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Runtime context and operation are required");
        var headers = access.runtimeHeaders(session, owner, request.context());
        var payload = request.payload() == null ? Map.<String, Object>of() : request.payload();
        return switch (request.operation()) {
            case "restore_checkpoint" ->
                    Mono.fromCallable(
                            () -> checkpointWrites.stage(session, session, payload, headers));
            case "fork_checkpoint" ->
                    Mono.fromCallable(
                            () ->
                                    checkpointWrites.stage(
                                            session,
                                            required(
                                                    payload.get("target_session_id")
                                                                    instanceof String target
                                                            ? target
                                                            : null,
                                                    "target_session_id"),
                                            payload,
                                            headers));
            case "checkpoint_operation" ->
                    Mono.fromCallable(
                            () ->
                                    checkpointWrites.get(
                                            session,
                                            required(request.receiptId(), "receiptId"),
                                            headers));
            case "cancel_checkpoint_operation" ->
                    Mono.fromCallable(
                            () ->
                                    checkpointWrites.cancel(
                                            session,
                                            required(request.receiptId(), "receiptId"),
                                            headers));
            case "checkpoints" ->
                    checkpoints.list(
                            session,
                            request.after(),
                            request.through(),
                            request.limit() == null ? 20 : request.limit(),
                            headers);
            case "checkpoint" ->
                    checkpoints.metadata(
                            session, required(request.resourceId(), "resourceId"), headers);
            case "budget" -> budgets.get(session, headers);
            case "configure_budget" ->
                    Mono.fromCallable(
                            () ->
                                    budgets.configure(
                                            session, payload, request.expectedRevision(), headers));
            case "publish_artifact" -> artifacts.publish(session, headers, payload);
            case "artifact" ->
                    artifacts.get(
                            session,
                            required(request.resourceId(), "resourceId"),
                            headers,
                            request.snapshotId());
            case "delete_artifact" ->
                    artifacts
                            .delete(session, required(request.resourceId(), "resourceId"), headers)
                            .thenReturn(
                                    Map.of(
                                            "artifact_id",
                                            request.resourceId(),
                                            "status",
                                            "unpublished"));
            case "files" ->
                    files.list(
                            session,
                            headers,
                            request.offset() == null ? 0 : request.offset(),
                            request.limit() == null ? 50 : request.limit());
            case "file" ->
                    files.get(session, required(request.resourceId(), "resourceId"), headers);
            default -> Mono.fromCallable(() -> invokeQuery(session, request, headers));
        };
    }

    private Map<String, Object> invokeQuery(String session, Request request, HttpHeaders headers) {
        String turn = request.context().turnId();
        int limit = request.limit() == null ? 100 : request.limit();
        var payload = request.payload() == null ? Map.<String, Object>of() : request.payload();
        return switch (request.operation()) {
            case "session" -> sessions.get(session, headers);
            case "submit_turn" -> turns.submit(session, payload, headers);
            case "turn" -> turns.turn(session, required(turn, "turnId"), headers);
            case "turns" ->
                    turns.turns(
                            session,
                            request.after(),
                            request.order() == null ? "desc" : request.order(),
                            request.status(),
                            limit,
                            headers);
            case "submit_input" -> turns.input(session, required(turn, "turnId"), payload, headers);
            case "input" ->
                    turns.input(
                            session,
                            required(turn, "turnId"),
                            required(request.inputId(), "inputId"),
                            headers);
            case "inputs" ->
                    turns.inputs(
                            session, required(turn, "turnId"), request.after(), limit, headers);
            case "events" -> turns.events(session, request.after(), limit, headers);
            case "snapshot" ->
                    turn == null
                            ? turns.snapshot(session, limit, headers)
                            : turns.snapshot(session, turn, limit, headers);
            case "resources" ->
                    turns.resources(
                            session,
                            required(request.snapshotId(), "snapshotId"),
                            required(request.resourceKind(), "resourceKind"),
                            request.after(),
                            limit,
                            headers);
            case "resource_page" ->
                    turns.resourcePage(
                            session,
                            turn,
                            request.snapshotId(),
                            required(request.resourceKind(), "resourceKind"),
                            request.after(),
                            limit,
                            headers);
            case "artifacts" ->
                    turns.artifacts(
                            session, turn, request.snapshotId(), request.after(), limit, headers);
            case "capabilities" ->
                    turn == null
                            ? turns.capabilities(session, headers)
                            : turns.capabilities(session, turn, headers);
            case "usage" -> turns.usage(session, turn, headers);
            case "tool_tasks" ->
                    turns.tools(session, required(turn, "turnId"), request.after(), limit, headers);
            case "lifecycle" ->
                    sessions.lifecycle(session, required(request.desired(), "desired"), headers);
            case "lifecycle_receipt" ->
                    sessions.lifecycleReceipt(
                            session, required(request.receiptId(), "receiptId"), headers);
            default ->
                    throw new ResponseStatusException(
                            HttpStatus.UNPROCESSABLE_ENTITY,
                            "Unsupported Managed runtime operation");
        };
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " is required");
        return value;
    }
}
