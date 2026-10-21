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
package io.agentscope.builder.portal.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.portal.persistence.PortalEventEntity;
import io.agentscope.builder.portal.persistence.PortalEventRepository;
import io.agentscope.builder.portal.persistence.PortalInteractionEntity;
import io.agentscope.builder.portal.persistence.PortalInteractionRepository;
import io.agentscope.builder.portal.persistence.PortalResourceRepository;
import io.agentscope.builder.portal.persistence.PortalSessionEntity;
import io.agentscope.builder.portal.persistence.PortalSessionRepository;
import io.agentscope.builder.portal.persistence.PortalTurnRepository;
import io.agentscope.builder.runtime.protocol.InteractionCommand;
import io.agentscope.builder.runtime.protocol.RuntimeAgentSignal;
import io.agentscope.builder.runtime.protocol.RuntimePayloadDigest;
import io.agentscope.builder.runtime.protocol.RuntimeRequestContext;
import io.agentscope.builder.runtime.protocol.TurnCommand;
import io.agentscope.builder.web.managed.SessionToolResultContent;
import io.agentscope.builder.web.managed.service.VaultCrypto;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Durable control admission keeps cancellation requests distinct from actual execution stops. */
@Service
public class PortalTurnControl {
    @Value("${builder.user-files-enabled:${BUILDER_USER_FILES_ENABLED:true}}")
    private boolean userFilesEnabled = true;

    private final PortalSessionRepository sessions;
    private final PortalTurnRepository turns;
    private final PortalInteractionRepository interactions;
    private final PortalEventRepository events;
    private final PortalResourceRepository resources;
    private final PortalSessionAccess access;
    private final PortalInputAdmission commands;
    private final PortalTurnAdmission admission;
    private final PortalResourceProjection projection;
    private final PortalEventNotifications notifications;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final VaultCrypto crypto;

    public PortalTurnControl(
            PortalSessionRepository sessions,
            PortalTurnRepository turns,
            PortalInteractionRepository interactions,
            PortalEventRepository events,
            PortalResourceRepository resources,
            PortalSessionAccess access,
            PortalInputAdmission commands,
            PortalTurnAdmission admission,
            PortalResourceProjection projection,
            PortalEventNotifications notifications,
            TransactionTemplate tx,
            ObjectMapper mapper,
            VaultCrypto crypto) {
        this.sessions = sessions;
        this.turns = turns;
        this.interactions = interactions;
        this.events = events;
        this.resources = resources;
        this.access = access;
        this.commands = commands;
        this.admission = admission;
        this.projection = projection;
        this.notifications = notifications;
        this.tx = tx;
        this.mapper = mapper;
        this.crypto = crypto;
    }

    public PortalInteractionEntity cancel(String sessionId, String turnId, HttpHeaders headers) {
        return stage(sessionId, turnId, "cancel", headers, List.of());
    }

    public Map<String, Object> capabilities(String sessionId, String turnId, HttpHeaders headers) {
        access.authorize(sessionId, headers, "read");
        var session =
                sessions.findById(sessionId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var turn =
                turns.findById(turnId)
                        .filter(row -> sessionId.equals(row.sessionId))
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var pending =
                resources.page(
                        sessionId,
                        "required_actions",
                        turnId,
                        "",
                        Long.MAX_VALUE,
                        PageRequest.of(0, 1));
        boolean action =
                !pending.isEmpty()
                        && projection.read(pending.get(0).data).get("data")
                                instanceof Map<?, ?> data
                        && Set.of("confirmation", "signal")
                                .contains(String.valueOf(data.get("kind")));
        boolean managed = "managed".equals(session.runtimeKind);
        boolean hosted = "hosted-runtime".equals(session.runtimeKind);
        boolean graph = Set.of("team", "workflow").contains(session.runtimeKind);
        boolean ready = "ready".equals(session.state) && "active".equals(session.lifecycle);
        var supported = new LinkedHashMap<String, Boolean>();
        supported.put("cancel", managed || hosted || graph);
        supported.put("steer", managed);
        supported.put("inject", managed);
        supported.put("resume", managed);
        supported.put("actions", managed || (hosted || graph) && action);
        supported.put("tool_retry", managed);
        supported.put("workspace_recover", managed);
        var available = new ArrayList<String>();
        if (ready) {
            if (supported.get("cancel")
                    && Set.of("queued", "running", "requires_action").contains(turn.status))
                available.add("cancel");
            if (managed) {
                available.add("inject");
                if ("running".equals(turn.status)) available.add("steer");
                if (Set.of("failed", "interrupted").contains(turn.status)) available.add("resume");
                if ("requires_action".equals(turn.status)) available.add("tool_retry");
                available.add("workspace_recover");
            }
            if (supported.get("actions")
                    && !pending.isEmpty()
                    && ("requires_action".equals(turn.status)
                            || graph && "running".equals(turn.status)
                            || managed && Set.of("failed", "interrupted").contains(turn.status)))
                available.add("actions");
        }
        return Map.of(
                "runtime_kind",
                session.runtimeKind,
                "configuration_revision",
                session.revision,
                "available_commands",
                available,
                "supported_commands",
                supported,
                "checkpoint_restore",
                false,
                "files",
                managed && userFilesEnabled,
                "file_input",
                managed && userFilesEnabled,
                "artifacts",
                userFilesEnabled,
                "action_scope",
                graph
                        ? "root_actions"
                        : hosted
                                ? "root_confirmation"
                                : managed ? "runtime_actions" : "unsupported");
    }

    public PortalInteractionEntity resume(String sessionId, String turnId, HttpHeaders headers) {
        return stage(sessionId, turnId, "resume", headers, List.of());
    }

    public PortalInteractionEntity actions(
            String sessionId,
            String turnId,
            HttpHeaders headers,
            List<Map<String, Object>> answers) {
        if (answers == null || answers.isEmpty() || answers.size() > 100)
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "1..100 action answers are required");
        return stage(sessionId, turnId, "actions", headers, answers);
    }

    public PortalInteractionEntity retryTool(
            String sessionId, String turnId, HttpHeaders headers, Map<String, Object> request) {
        if (!Boolean.TRUE.equals(request.get("confirm_side_effects"))
                || !(request.get("task_id") instanceof String task)
                || task.length() != 64
                || !(request.get("authorization_generation") instanceof Number generation)
                || generation.longValue() < 1)
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unknown tool retry requires its generation and explicit side-effect"
                            + " confirmation");
        return stage(
                sessionId,
                turnId,
                "tool_retry",
                headers,
                List.of(
                        Map.of(
                                "task_id",
                                task,
                                "authorization_generation",
                                generation.longValue(),
                                "confirm_side_effects",
                                true)));
    }

    public PortalInteractionEntity recoverWorkspace(
            String sessionId, String turnId, HttpHeaders headers, Map<String, Object> request) {
        if (!(request.get("task_id") instanceof String task)
                || task.length() != 64
                || !(request.get("authorization_generation") instanceof Number generation)
                || generation.longValue() < 1
                || !(request.get("workspace_generation") instanceof Number workspace)
                || workspace.longValue() < 1
                || !Boolean.TRUE.equals(request.get("confirm_side_effects"))
                || !(request.get("mode") instanceof String mode)
                || !Set.of("reconnect", "restore", "discard").contains(mode))
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Workspace recovery requires expected generations and explicit confirmation");
        var selected = new LinkedHashMap<String, Object>();
        for (String name :
                List.of(
                        "task_id",
                        "authorization_generation",
                        "workspace_generation",
                        "mode",
                        "provider_id",
                        "confirm_side_effects",
                        "confirm_provider_idle",
                        "confirm_provider_absent",
                        "confirm_data_loss"))
            if (request.get(name) != null) selected.put(name, request.get(name));
        if ("reconnect".equals(mode) && !Boolean.TRUE.equals(selected.get("confirm_provider_idle"))
                || !"reconnect".equals(mode)
                        && (!Boolean.TRUE.equals(selected.get("confirm_provider_absent"))
                                || !Boolean.TRUE.equals(selected.get("confirm_data_loss"))))
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Confirm provider idle or absent and any discarded file changes");
        return stage(sessionId, turnId, "workspace_recover", headers, List.of(selected));
    }

    private PortalInteractionEntity stage(
            String sessionId,
            String turnId,
            String kind,
            HttpHeaders headers,
            List<Map<String, Object>> answers) {
        return stage(sessionId, turnId, kind, headers, answers, null);
    }

    /** Private runtime route: the courier supplies task proof, never a human actor. */
    public PortalInteractionEntity agentSignal(RuntimeAgentSignal request) {
        var turn =
                turns.findById(request.turnId())
                        .filter(row -> request.sessionId().equals(row.sessionId))
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        try {
            var original = mapper.readValue(crypto.decrypt(turn.command), TurnCommand.class);
            var actor = new LinkedHashMap<String, Object>(original.context().actor());
            actor.put("agent_task", request.taskId());
            actor.put("agent_attempt", request.attemptId());
            actor.put("agent_fence", request.taskFence());
            actor.put("agent_ref", request.agentRef());
            var headers = new HttpHeaders();
            headers.set("Idempotency-Key", request.idempotencyKey());
            var answer = new LinkedHashMap<String, Object>();
            answer.put("request_id", request.requestId());
            answer.put("value", request.value());
            return stage(
                    request.sessionId(),
                    request.turnId(),
                    "actions",
                    headers,
                    List.of(answer),
                    actor,
                    request);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot read original Turn actor", error);
        }
    }

    private PortalInteractionEntity stage(
            String sessionId,
            String turnId,
            String kind,
            HttpHeaders headers,
            List<Map<String, Object>> answers,
            Map<String, Object> machineActor) {
        return stage(sessionId, turnId, kind, headers, answers, machineActor, null);
    }

    /** Dispatches a persisted public event with its original, currently authorized principal. */
    public PortalInteractionEntity stageActor(
            String sessionId,
            String turnId,
            String kind,
            Map<String, Object> actor,
            String key,
            List<Map<String, Object>> answers) {
        if (!Set.of("cancel", "resume", "actions").contains(kind)
                || "actions".equals(kind)
                        && (answers == null || answers.isEmpty() || answers.size() > 100))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid event command");
        var headers = new HttpHeaders();
        headers.set("Idempotency-Key", key);
        return stage(sessionId, turnId, kind, headers, answers, actor, null);
    }

    private PortalInteractionEntity stage(
            String sessionId,
            String turnId,
            String kind,
            HttpHeaders headers,
            List<Map<String, Object>> answers,
            Map<String, Object> machineActor,
            RuntimeAgentSignal agentSignal) {
        var actor = machineActor == null ? access.authenticate(sessionId, headers) : machineActor;
        var session = sessions.findById(sessionId).orElseThrow();
        String key = headers.getFirst("Idempotency-Key");
        if (key == null || key.isBlank()) key = UUID.randomUUID().toString();
        if (key.length() > 256)
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Idempotency-Key exceeds 256 characters");
        String id =
                RuntimePayloadDigest.of(
                        mapper,
                        Map.of(
                                "tenant",
                                session.tenant,
                                "namespace",
                                session.namespace,
                                "session",
                                sessionId,
                                "turn",
                                turnId,
                                "kind",
                                kind,
                                "key",
                                key,
                                "principal",
                                actor.containsKey("agent_task")
                                        ? "agent:"
                                                + actor.get("agent_task")
                                                + ":"
                                                + actor.get("agent_attempt")
                                                + ":"
                                                + actor.get("agent_fence")
                                        : actor.containsKey("hands_task")
                                                ? "hands:"
                                                        + actor.get("hands_environment")
                                                        + ":"
                                                        + actor.get("hands_task")
                                                : String.valueOf(actor.get("key_hash")).isBlank()
                                                        ? "user:" + actor.get("user")
                                                        : "application:" + session.applicationId));
        return tx.execute(
                ignored -> {
                    var current = sessions.lockById(sessionId).orElseThrow();
                    var authorized =
                            machineActor == null
                                    ? access.authenticate(sessionId, headers)
                                    : machineActor;
                    var target =
                            turns.lockById(turnId)
                                    .filter(turn -> turn.sessionId.equals(sessionId))
                                    .orElseThrow(
                                            () ->
                                                    new ResponseStatusException(
                                                            HttpStatus.NOT_FOUND));
                    var existing = interactions.findById(id);
                    if (existing.isPresent()) {
                        var original = commands.command(existing.get());
                        access.authorizeInteraction(current, original, authorized);
                        if (Set.of("actions", "tool_retry", "workspace_recover").contains(kind)
                                && !RuntimePayloadDigest.of(mapper, answers)
                                        .equals(
                                                RuntimePayloadDigest.of(
                                                        mapper,
                                                        original.payload()
                                                                .get("requested_answers"))))
                            throw new ResponseStatusException(
                                    HttpStatus.CONFLICT,
                                    "Idempotency-Key was already used with different answers");
                        return existing.get();
                    }
                    if (agentSignal != null
                            && (!Set.of("team", "workflow").contains(current.runtimeKind)
                                    || target.executionGeneration
                                            != agentSignal.executionGeneration()
                                    || !Objects.equals(
                                            target.executionFence, agentSignal.executionFence())
                                    || target.grantUntil <= System.currentTimeMillis()
                                    || !"active".equals(target.reservationState)))
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT,
                                "Agent signal execution grant is no longer current");
                    if (Set.of("tool_retry", "workspace_recover").contains(kind))
                        access.authorizeActor(current, authorized, "interact");
                    else if (!"actions".equals(kind))
                        access.authorizeActor(
                                current, authorized, "cancel".equals(kind) ? "cancel" : "invoke");
                    if (!"workspace_recover".equals(kind)
                            && Set.of("completed", "cancelled").contains(target.status))
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT, "Turn has already ended");
                    if ("resume".equals(kind)
                            && !Set.of("failed", "interrupted").contains(target.status))
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT,
                                "Only a failed or interrupted Turn can resume");
                    if (!"workspace_recover".equals(kind)
                            && "cancel_requested".equals(target.status))
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT, "Cancellation has already been requested");
                    if (!"workspace_recover".equals(kind)
                            && interactions.countByTurnIdAndKindAndNativeStatusIn(
                                            turnId, "cancel", List.of("queued", "runtime_accepted"))
                                    > 0)
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT, "Cancellation is already queued");
                    if ("actions".equals(kind)
                            && interactions.countByTurnIdAndKindAndNativeStatusIn(
                                            turnId, "signal", List.of("queued", "runtime_accepted"))
                                    > 0)
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT,
                                "A signal command is already pending for this Turn");
                    boolean graph = Set.of("team", "workflow").contains(current.runtimeKind);
                    if (!"managed".equals(current.runtimeKind)
                            && !(("hosted-runtime".equals(current.runtimeKind) || graph)
                                    && Set.of("cancel", "actions").contains(kind)))
                        throw new ResponseStatusException(
                                HttpStatus.UNPROCESSABLE_ENTITY,
                                "Backend Turn controls are not connected");
                    var payload = new LinkedHashMap<String, Object>();
                    payload.put("target_command_id", turnId);
                    payload.put("expected_generation", target.executionGeneration);
                    payload.put("target_activation_command_id", target.activationCommandId);
                    payload.put("source_execution_fence", target.executionFence);
                    String commandKind = kind;
                    if (Set.of("tool_retry", "workspace_recover").contains(kind)) {
                        if ("tool_retry".equals(kind) && !"requires_action".equals(target.status))
                            throw new ResponseStatusException(
                                    HttpStatus.CONFLICT, "Turn is not waiting for tools");
                        payload.put("requested_answers", answers);
                        payload.putAll(answers.get(0));
                    }
                    if ("actions".equals(kind)) {
                        if (!Set.of("requires_action", "failed", "interrupted")
                                        .contains(target.status)
                                && !(graph && "running".equals(target.status)))
                            throw new ResponseStatusException(
                                    HttpStatus.CONFLICT, "Turn is not waiting for actions");
                        var normalized = normalizeAnswers(current, turnId, authorized, answers);
                        commandKind =
                                normalized.stream()
                                                .anyMatch(
                                                        answer ->
                                                                "signal".equals(answer.get("kind")))
                                        ? "signal"
                                        : normalized.stream()
                                                        .anyMatch(
                                                                answer ->
                                                                        "confirmation"
                                                                                .equals(
                                                                                        answer.get(
                                                                                                "kind")))
                                                ? "approval"
                                                : "tool_result";
                        payload.put("requested_answers", answers);
                        payload.put("answers", normalized);
                        String actionCommandKind = commandKind;
                        if (("hosted-runtime".equals(current.runtimeKind) || graph)
                                && normalized.stream()
                                        .anyMatch(
                                                answer ->
                                                        answer.containsKey("child_session_id")
                                                                || !("confirmation"
                                                                                        .equals(
                                                                                                answer
                                                                                                        .get(
                                                                                                                "kind"))
                                                                                && "approval"
                                                                                        .equals(
                                                                                                actionCommandKind)
                                                                        || graph
                                                                                && "signal"
                                                                                        .equals(
                                                                                                answer
                                                                                                        .get(
                                                                                                                "kind"))
                                                                                && "signal"
                                                                                        .equals(
                                                                                                actionCommandKind))))
                            throw new ResponseStatusException(
                                    HttpStatus.UNPROCESSABLE_ENTITY,
                                    "Backend actions require root confirmations or one batch of"
                                            + " signals");
                    }
                    var row = new PortalInteractionEntity();
                    row.id = id;
                    row.sessionId = sessionId;
                    row.turnId = turnId;
                    row.kind = commandKind;
                    row.payloadDigest = RuntimePayloadDigest.of(mapper, payload);
                    row.acceptUntil = System.currentTimeMillis() + 30000;
                    row.command =
                            crypto.encrypt(
                                    json(
                                            new InteractionCommand(
                                                    new RuntimeRequestContext(
                                                            1,
                                                            current.tenant,
                                                            current.namespace,
                                                            current.applicationId,
                                                            sessionId,
                                                            turnId,
                                                            id,
                                                            UUID.randomUUID().toString(),
                                                            authorized,
                                                            Long.toString(
                                                                    access.policyVersion(current)),
                                                            row.acceptUntil),
                                                    commandKind,
                                                    payload)));
                    row.deliveryStatus = "pending";
                    row.nativeStatus = "queued";
                    row.createdAt = System.currentTimeMillis();
                    row.updatedAt = row.createdAt;
                    interactions.saveAndFlush(row);
                    if ("resume".equals(kind) || "actions".equals(kind)) {
                        admission.reserveResume(current, target);
                        target.activationCommandId = id;
                    } else if ("cancel".equals(kind) && "active".equals(target.reservationState))
                        target.status = "cancel_requested";
                    target.updatedAt = row.createdAt;
                    turns.saveAndFlush(target);
                    var event = new PortalEventEntity();
                    event.id =
                            RuntimePayloadDigest.of(
                                    mapper,
                                    Map.of("type", "turn." + kind + "_requested", "command", id));
                    event.sessionId = sessionId;
                    event.turnId = turnId;
                    event.seq = ++current.nextEventSequence;
                    event.type =
                            "workspace_recover".equals(kind)
                                    ? "workspace.recovery_requested"
                                    : "tool_retry".equals(kind)
                                            ? "tool.retry_requested"
                                            : "cancel".equals(kind)
                                                    ? "turn.cancel_requested"
                                                    : "turn.queued";
                    event.data =
                            json(
                                    Map.of(
                                            "turn_id",
                                            turnId,
                                            "status",
                                            target.status,
                                            "command_id",
                                            id,
                                            "reason",
                                            kind + "_requested"));
                    event.createdAt = row.createdAt;
                    events.saveAndFlush(event);
                    projection.apply(current, event);
                    notifications.committed(sessionId);
                    current.updatedAt = row.createdAt;
                    sessions.saveAndFlush(current);
                    return row;
                });
    }

    private List<Map<String, Object>> normalizeAnswers(
            PortalSessionEntity session,
            String turn,
            Map<String, Object> actor,
            List<Map<String, Object>> answers) {
        var normalized = new ArrayList<Map<String, Object>>();
        var seen = new HashSet<String>();
        String nativeTarget = null;
        for (var answer : answers) {
            if (answer == null
                    || !Set.of(
                                    "request_id",
                                    "allow",
                                    "reason",
                                    "output",
                                    "content",
                                    "is_error",
                                    "child_session_id",
                                    "value")
                            .containsAll(answer.keySet())
                    || !(answer.get("request_id") instanceof String id)
                    || id.isBlank()
                    || id.length() > 512)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid action answer");
            String nativeSession = session.id;
            if (answer.containsKey("child_session_id")) {
                if (!(answer.get("child_session_id") instanceof String child))
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "Invalid child Session");
                sessions.findById(child)
                        .filter(row -> session.id.equals(row.rootSessionId))
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
                nativeSession = child;
            }
            if (nativeTarget != null && !nativeTarget.equals(nativeSession))
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "One native Session per action command is required");
            nativeTarget = nativeSession;
            String targetSession = nativeSession;
            var resource =
                    resources
                            .findById(projection.resourceId(targetSession, "required_actions", id))
                            .filter(
                                    row ->
                                            row.sessionId.equals(targetSession)
                                                    && "required_actions".equals(row.kind))
                            .orElseThrow(
                                    () ->
                                            new ResponseStatusException(
                                                    HttpStatus.CONFLICT,
                                                    "Action is no longer pending"));
            var pending = projection.read(resource.data);
            if (!(pending.get("data") instanceof Map<?, ?> data))
                throw new IllegalStateException("Pending action projection is malformed");
            boolean sameTurn =
                    targetSession.equals(session.id)
                            ? turn.equals(data.get("turn_id"))
                            : data.get("runtime_source") instanceof Map<?, ?> source
                                    && turn.equals(source.get("root_turn_id"));
            if (!sameTurn || !seen.add(id))
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "Action is duplicate or belongs to another Turn");
            String actionKind = String.valueOf(data.get("kind"));
            var value = new LinkedHashMap<String, Object>();
            value.put("request_id", id);
            value.put("kind", actionKind);
            value.put("call", data.get("tool_call"));
            if (!targetSession.equals(session.id)) value.put("child_session_id", targetSession);
            if (data.get("tool_call") instanceof Map<?, ?> call
                    && Set.of("agent_spawn", "agent_send").contains(call.get("name")))
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "Child execution results are produced by runtime");
            if ("confirmation".equals(actionKind)) {
                if (actor.containsKey("hands_task") || actor.containsKey("agent_task"))
                    throw new ResponseStatusException(
                            HttpStatus.FORBIDDEN, "Machine callers cannot approve tools");
                access.authorizeActor(session, actor, "approve");
                if (!(answer.get("allow") instanceof Boolean)
                        || answer.containsKey("output")
                        || answer.containsKey("content")
                        || answer.containsKey("is_error")
                        || answer.containsKey("value")
                        || answer.get("reason") != null
                                && !(answer.get("reason") instanceof String))
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "Confirmation requires allow and optional reason");
                value.put("allow", answer.get("allow"));
                value.put("reason", answer.get("reason"));
            } else if ("signal".equals(actionKind)) {
                access.authorizeActor(
                        session, actor, actor.containsKey("agent_task") ? "invoke" : "interact");
                if (!Set.of("team", "workflow").contains(session.runtimeKind)
                        || actor.containsKey("hands_task")
                        || !answer.containsKey("value")
                        || !Set.of("request_id", "value").containsAll(answer.keySet()))
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "Signal requires a value and its pending request_id");
                try {
                    if (mapper.writeValueAsBytes(answer.get("value")).length > 65536)
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "Signal value exceeds 64 KiB");
                } catch (JsonProcessingException error) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "Invalid signal value", error);
                }
                value.put("value", answer.get("value"));
            } else if ("external_execution".equals(actionKind)) {
                access.authorizeActor(
                        session, actor, actor.containsKey("hands_task") ? "invoke" : "interact");
                if (answer.containsKey("allow")
                        || answer.containsKey("reason")
                        || answer.containsKey("value")
                        || answer.get("is_error") != null
                                && !(answer.get("is_error") instanceof Boolean))
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "External execution requires bounded output and optional is_error");
                SessionToolResultContent.blocks(answer);
                if (answer.containsKey("content")) value.put("content", answer.get("content"));
                else value.put("output", answer.get("output"));
                value.put("is_error", Boolean.TRUE.equals(answer.get("is_error")));
            } else
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Unsupported action kind");
            normalized.add(value);
        }
        return normalized;
    }

    public Map<String, Object> get(String session, String id, HttpHeaders headers) {
        return commands.get(session, id, headers);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot encode control command", error);
        }
    }
}
