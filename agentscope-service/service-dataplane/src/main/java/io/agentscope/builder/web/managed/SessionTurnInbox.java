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

import io.agentscope.builder.web.managed.service.SessionEventLog;
import io.agentscope.builder.web.persistence.jpa.SessionActionCommandEntity;
import io.agentscope.builder.web.persistence.jpa.SessionActionCommandRepository;
import io.agentscope.builder.web.persistence.jpa.SessionTurnCommandEntity;
import io.agentscope.builder.web.persistence.jpa.SessionTurnCommandRepository;
import io.agentscope.builder.web.toolbus.ToolConfirmationCoordinator;
import io.agentscope.core.session.JournalSessionLog;
import io.agentscope.core.session.SessionInteractions;
import io.agentscope.core.session.SessionRecovery;
import io.agentscope.core.util.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Durable FIFO admission. Interrupted workers require explicit resume; uncertain tools are never replayed blindly. */
@Service
public final class SessionTurnInbox {
    private final SessionTurnCommandRepository repository;
    private final TransactionTemplate tx;
    private final DataSessionService sessions;
    private final SessionTurnRunner runner;
    private final SessionEventLog events;
    private final SessionNativeLogService nativeLogs;
    private final SessionActionCommandRepository actionCommands;
    private final ToolConfirmationCoordinator confirmations;
    private final String worker = UUID.randomUUID().toString();
    private final Set<String> active = ConcurrentHashMap.newKeySet();

    public SessionTurnInbox(
            SessionTurnCommandRepository repository,
            TransactionTemplate tx,
            DataSessionService sessions,
            SessionTurnRunner runner,
            SessionEventLog events,
            SessionNativeLogService nativeLogs,
            SessionActionCommandRepository actionCommands,
            ToolConfirmationCoordinator confirmations) {
        this.repository = repository;
        this.tx = tx;
        this.sessions = sessions;
        this.runner = runner;
        this.events = events;
        this.nativeLogs = nativeLogs;
        this.actionCommands = actionCommands;
        this.confirmations = confirmations;
    }

    public record Turn(
            String id, String sessionId, String status, long createdAt, String errorCode) {}

    private static Turn view(SessionTurnCommandEntity row) {
        return new Turn(row.id, row.sessionId, row.status, row.createdAt, row.errorCode);
    }

    public Turn accept(String user, String session, String key, String message) {
        return accept(user, session, key, new AgentSessionInput(message, null).normalized());
    }

    public Turn accept(String user, String session, String key, Map<String, Object> input) {
        sessions.get(user, session);
        if (key == null || key.isBlank() || key.length() > 256)
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Idempotency-Key must contain 1..256 characters");
        String id =
                JournalSessionLog.hash(
                        (user + "\n" + session + "\n" + key).getBytes(StandardCharsets.UTF_8));
        String request = JsonUtils.getJsonCodec().toJson(input);
        try {
            return tx.execute(
                    status -> {
                        var found = repository.findById(id);
                        if (found.isPresent()) return replay(found.get(), request);
                        var row = new SessionTurnCommandEntity();
                        row.id = id;
                        row.sessionId = session;
                        row.userId = user;
                        row.requestJson = request;
                        row.status = "queued";
                        row.createdAt = System.currentTimeMillis();
                        row.updatedAt = row.createdAt;
                        repository.saveAndFlush(row);
                        var admitted =
                                events.appendCommand(
                                        session,
                                        "turn.accepted",
                                        Map.of("turn_id", id, "status", "queued"),
                                        "cmd_" + id.substring(0, 56));
                        row.admissionSeq = admitted.seq();
                        repository.save(row);
                        return view(row);
                    });
        } catch (RuntimeException error) {
            var found = repository.findById(id);
            if (found.isPresent()) return replay(found.get(), request);
            throw error;
        }
    }

    public record Answer(
            String request_id, Boolean allow, String reason, String output, Boolean is_error) {}

    public Turn answer(String user, String session, String turn, String key, List<Answer> answers) {
        var managed = sessions.get(user, session);
        if (key == null
                || key.isBlank()
                || key.length() > 256
                || answers == null
                || answers.isEmpty()
                || answers.size() > 100)
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "A key and 1..100 action answers are required");
        String id =
                JournalSessionLog.hash(
                        (user + "\n" + session + "\naction\n" + key)
                                .getBytes(StandardCharsets.UTF_8));
        String request = JsonUtils.getJsonCodec().toJson(answers);
        return tx.execute(
                status -> {
                    var row =
                            repository
                                    .lockById(turn)
                                    .orElseThrow(
                                            () ->
                                                    new ResponseStatusException(
                                                            HttpStatus.NOT_FOUND));
                    if (!row.userId.equals(user) || !row.sessionId.equals(session))
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
                    var existing = actionCommands.findById(id);
                    if (existing.isPresent()) {
                        if (!existing.get().turnId.equals(turn)
                                || !existing.get().requestJson.equals(request))
                            throw new ResponseStatusException(
                                    HttpStatus.CONFLICT,
                                    "Idempotency-Key reused with different answers");
                        return view(row);
                    }
                    if (!row.status.equals("requires_action") && !row.status.equals("running"))
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT, "Turn is not waiting for actions");
                    var pending = SessionInteractions.pending(nativeLogs.open(managed));
                    var alreadyAccepted = new HashSet<String>();
                    for (var prior : actionCommands.findByTurnIdOrderByCreatedAtAsc(turn))
                        if (!"rejected".equals(prior.deliveryStatus))
                            for (var raw :
                                    JsonUtils.getJsonCodec()
                                            .fromJson(prior.requestJson, List.class))
                                alreadyAccepted.add(
                                        String.valueOf(((Map<?, ?>) raw).get("request_id")));
                    var normalized = new ArrayList<Map<String, Object>>();
                    var seen = new HashSet<String>();
                    for (var answer : answers) {
                        var fact = pending.get(answer.request_id());
                        if (fact == null
                                || !fact.turnId().equals(turn)
                                || !seen.add(answer.request_id())
                                || alreadyAccepted.contains(answer.request_id()))
                            throw new ResponseStatusException(
                                    HttpStatus.CONFLICT,
                                    "Action is stale, duplicate or belongs to another turn");
                        if (row.status.equals("running")
                                && !"service_ticket".equals(fact.data().get("protocol")))
                            throw new ResponseStatusException(
                                    HttpStatus.CONFLICT,
                                    "Wait for the suspended turn before answering");
                        String kind = String.valueOf(fact.data().get("kind"));
                        var value = new LinkedHashMap<String, Object>();
                        value.put("request_id", answer.request_id());
                        value.put("kind", kind);
                        value.put("call", fact.data().get("call"));
                        if (kind.equals("confirmation")) {
                            if (answer.allow() == null)
                                throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST, "Confirmation requires allow");
                            value.put("allow", answer.allow());
                            value.put("reason", answer.reason());
                        } else if (kind.equals("external_execution")) {
                            if (answer.output() == null)
                                throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST,
                                        "External execution requires output");
                            value.put("output", answer.output());
                            value.put("is_error", Boolean.TRUE.equals(answer.is_error()));
                        } else
                            throw new ResponseStatusException(
                                    HttpStatus.BAD_REQUEST, "Unsupported action kind");
                        normalized.add(value);
                    }
                    var command = new SessionActionCommandEntity();
                    command.id = id;
                    command.turnId = turn;
                    command.requestJson = request;
                    command.createdAt = System.currentTimeMillis();
                    command.deliveryStatus = row.status.equals("running") ? "queued" : "applied";
                    actionCommands.saveAndFlush(command);
                    if (!row.status.equals("running")) {
                        row.continuationId = id;
                        row.continuationJson = JsonUtils.getJsonCodec().toJson(normalized);
                        row.status = "queued";
                        row.workerId = null;
                        row.leaseUntil = 0;
                        row.updatedAt = System.currentTimeMillis();
                        repository.save(row);
                        events.appendCommand(
                                session,
                                "turn.queued",
                                Map.of(
                                        "turn_id",
                                        turn,
                                        "status",
                                        "queued",
                                        "reason",
                                        "actions_accepted"),
                                null);
                    }
                    for (var answer : answers)
                        events.appendCommand(
                                session,
                                "required_action.accepted",
                                Map.of(
                                        "turn_id",
                                        turn,
                                        "request_id",
                                        answer.request_id(),
                                        "command_id",
                                        id,
                                        "status",
                                        "queued"),
                                null);
                    return view(row);
                });
    }

    private Turn replay(SessionTurnCommandEntity row, String request) {
        if (!JsonUtils.getJsonCodec()
                .fromJson(row.requestJson, Map.class)
                .equals(JsonUtils.getJsonCodec().fromJson(request, Map.class)))
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Idempotency-Key reused with different input");
        return view(row);
    }

    public List<Turn> list(String user, String session) {
        sessions.get(user, session);
        return repository.findBySessionIdOrderByCreatedAtAsc(session).stream()
                .map(SessionTurnInbox::view)
                .toList();
    }

    public Turn get(String user, String session, String id) {
        sessions.get(user, session);
        var row =
                repository
                        .findById(id)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!session.equals(row.sessionId) || !user.equals(row.userId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return view(row);
    }

    public List<Map<String, Object>> actions(String user, String session, String turn) {
        get(user, session, turn);
        var pending = SessionInteractions.pending(nativeLogs.open(sessions.get(user, session)));
        return actionCommands.findByTurnIdOrderByCreatedAtAsc(turn).stream()
                .map(
                        command -> {
                            var answers =
                                    JsonUtils.getJsonCodec()
                                            .fromJson(command.requestJson, List.class);
                            var ids =
                                    answers.stream()
                                            .map(
                                                    raw ->
                                                            String.valueOf(
                                                                    ((Map<?, ?>) raw)
                                                                            .get("request_id")))
                                            .toList();
                            String state =
                                    command.deliveryStatus.equals("rejected")
                                            ? "rejected"
                                            : ids.stream().noneMatch(pending::containsKey)
                                                    ? "resolved"
                                                    : "accepted";
                            return Map.<String, Object>of(
                                    "command_id",
                                    command.id,
                                    "turn_id",
                                    turn,
                                    "request_ids",
                                    ids,
                                    "status",
                                    state,
                                    "created_at",
                                    command.createdAt);
                        })
                .toList();
    }

    public Turn resume(String user, String session, String id) {
        return resume(user, session, id, null);
    }

    public Turn resume(String user, String session, String id, String key) {
        sessions.get(user, session);
        if (key != null && (key.isBlank() || key.length() > 256))
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Idempotency-Key must contain 1..256 characters");
        String eventId =
                key == null
                        ? null
                        : JournalSessionLog.hash(
                                (user + "\n" + session + "\nresume\n" + key)
                                        .getBytes(StandardCharsets.UTF_8));
        return tx.execute(
                status -> {
                    var row =
                            repository
                                    .lockById(id)
                                    .orElseThrow(
                                            () ->
                                                    new ResponseStatusException(
                                                            HttpStatus.NOT_FOUND));
                    if (!row.sessionId.equals(session) || !row.userId.equals(user))
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
                    if (eventId != null) {
                        var receipt = events.findByEventId(eventId);
                        if (receipt.isPresent()) {
                            if (!id.equals(receipt.get().payload().get("turn_id")))
                                throw new ResponseStatusException(
                                        HttpStatus.CONFLICT,
                                        "Idempotency-Key reused for another resumed turn");
                            return view(row);
                        }
                    }
                    if (!row.status.equals("interrupted")
                            && !row.status.equals("failed")
                            && !(row.status.equals("requires_action")
                                    && SessionInteractions.pending(
                                                    nativeLogs.open(sessions.get(user, session)))
                                            .isEmpty()))
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT,
                                "Only an interrupted, failed, or paused turn without unanswered"
                                        + " actions can be resumed");
                    row.status = "queued";
                    events.appendCommand(
                            session,
                            "turn.queued",
                            Map.of("turn_id", id, "status", "queued", "reason", "explicit_resume"),
                            eventId);
                    row.errorCode = null;
                    row.workerId = null;
                    row.leaseUntil = 0;
                    row.updatedAt = System.currentTimeMillis();
                    repository.save(row);
                    return view(row);
                });
    }

    public Turn cancel(String user, String session, String id) {
        sessions.get(user, session);
        return tx.execute(
                status -> {
                    var row =
                            repository
                                    .lockById(id)
                                    .orElseThrow(
                                            () ->
                                                    new ResponseStatusException(
                                                            HttpStatus.NOT_FOUND));
                    if (!row.sessionId.equals(session) || !row.userId.equals(user))
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
                    if (Set.of("requires_action", "interrupted", "failed").contains(row.status)) {
                        SessionRecovery.cancelPending(
                                nativeLogs.open(sessions.get(user, session)), id);
                        nativeLogs.refresh(session);
                    }
                    if (Set.of("queued", "requires_action", "interrupted", "failed")
                            .contains(row.status)) row.status = "cancelled";
                    else if (row.status.equals("running")) row.status = "cancel_requested";
                    else return view(row);
                    row.updatedAt = System.currentTimeMillis();
                    repository.save(row);
                    events.appendCommand(
                            row.sessionId,
                            "turn." + row.status,
                            Map.of("turn_id", id, "status", row.status),
                            null);
                    return view(row);
                });
    }

    @Scheduled(fixedDelayString = "${builder.agent-api.inbox-poll-ms:1000}")
    public void dispatch() {
        long now = System.currentTimeMillis();
        deliverConfirmations();
        for (String id : List.copyOf(active))
            tx.executeWithoutResult(
                    status -> {
                        var row = repository.lockById(id).orElse(null);
                        if (row != null
                                && worker.equals(row.workerId)
                                && Set.of("running", "cancel_requested").contains(row.status)) {
                            if (row.status.equals("cancel_requested"))
                                runner.cancelDurableTurn(row.sessionId, row.id);
                            row.leaseUntil = now + 60000;
                            repository.save(row);
                        }
                    });
        for (var candidate :
                repository.findTop100ByStatusInAndLeaseUntilLessThanOrderByCreatedAtAsc(
                        List.of("running", "cancel_requested"), now)) {
            tx.executeWithoutResult(
                    status -> {
                        var row = repository.lockById(candidate.id).orElseThrow();
                        if (!row.status.equals("running") && !row.status.equals("cancel_requested"))
                            return;
                        if (worker.equals(row.workerId) && active.contains(row.id)) {
                            if (row.status.equals("cancel_requested"))
                                runner.cancelDurableTurn(row.sessionId, row.id);
                            row.leaseUntil = now + 60000;
                            repository.save(row);
                        } else if (row.leaseUntil < now) {
                            String completed = recoverOutcome(row);
                            row.status = completed == null ? "interrupted" : completed;
                            row.errorCode = completed == null ? "worker_lost" : null;
                            repository.save(row);
                            events.appendCommand(
                                    row.sessionId,
                                    "turn." + row.status,
                                    Map.of(
                                            "turn_id",
                                            row.id,
                                            "status",
                                            row.status,
                                            "reason",
                                            "worker_lost",
                                            "requires_explicit_resume",
                                            completed == null),
                                    null);
                        }
                    });
        }
        for (var candidate : repository.findDispatchable(PageRequest.of(0, 100))) {
            if (active.size() >= 32) return;
            var claimed =
                    tx.execute(
                            status -> {
                                var row = repository.lockById(candidate.id).orElseThrow();
                                if (!row.status.equals("queued")) return null;
                                row.status = "running";
                                row.nativeStartSeq = -1;
                                row.workerId = worker;
                                row.leaseUntil = System.currentTimeMillis() + 60000;
                                row.updatedAt = System.currentTimeMillis();
                                repository.save(row);
                                events.appendCommand(
                                        row.sessionId,
                                        "turn.running",
                                        Map.of("turn_id", row.id, "status", "running"),
                                        null);
                                return row;
                            });
            if (claimed == null) continue;
            active.add(claimed.id);
            try {
                var session = sessions.get(claimed.userId, claimed.sessionId);
                long sourceSeq = nativeLogs.open(session).head().seq();
                tx.executeWithoutResult(
                        status -> {
                            var row = repository.lockById(claimed.id).orElseThrow();
                            if (!worker.equals(row.workerId))
                                throw new IllegalStateException("Command ownership changed");
                            row.nativeStartSeq = sourceSeq;
                            repository.save(row);
                        });
                var input = JsonUtils.getJsonCodec().fromJson(claimed.requestJson, Map.class);
                if (claimed.continuationJson == null)
                    runner.runDurableTurnAsync(
                            session,
                            AgentSessionInput.messages(input, claimed.id),
                            claimed.id,
                            (status, error) -> finish(claimed.id, status, error));
                else
                    runner.resumeDurableTurnAsync(
                            session,
                            claimed.continuationId,
                            claimed.continuationJson,
                            claimed.id,
                            (status, error) -> finish(claimed.id, status, error));
            } catch (ResponseStatusException error) {
                if (error.getStatusCode().value() == 409) requeue(claimed.id);
                else finish(claimed.id, "failed", error);
            } catch (RuntimeException error) {
                finish(claimed.id, "failed", error);
            }
        }
    }

    private void deliverConfirmations() {
        for (var command : actionCommands.findTop100ByDeliveryStatusOrderByCreatedAtAsc("queued")) {
            var turn = repository.findById(command.turnId).orElse(null);
            if (turn == null) continue;
            boolean waiting = false;
            var rejectedIds = new HashSet<String>();
            var answers = JsonUtils.getJsonCodec().fromJson(command.requestJson, List.class);
            for (var raw : answers) {
                var answer = (Map<?, ?>) raw;
                var result =
                        confirmations.resolvePersonal(
                                turn.sessionId,
                                String.valueOf(answer.get("request_id")),
                                Boolean.TRUE.equals(answer.get("allow")),
                                (String) answer.get("reason"));
                if (result == ToolConfirmationCoordinator.DecisionResult.NOT_FOUND
                        && System.currentTimeMillis() - command.createdAt < 60000) {
                    waiting = true;
                    continue;
                }
                if (result != ToolConfirmationCoordinator.DecisionResult.RESOLVED
                        && result != ToolConfirmationCoordinator.DecisionResult.IDEMPOTENT)
                    rejectedIds.add(String.valueOf(answer.get("request_id")));
            }
            if (waiting) continue;
            // Fence competing workers and commit delivery outcome with its public facts.
            tx.executeWithoutResult(
                    status -> {
                        var locked = repository.lockById(command.turnId).orElse(null);
                        var current = actionCommands.findById(command.id).orElse(null);
                        if (locked == null
                                || current == null
                                || !"queued".equals(current.deliveryStatus)) return;
                        var pending =
                                SessionInteractions.pending(
                                        nativeLogs.open(sessions.get(turn.userId, turn.sessionId)));
                        current.deliveryStatus = rejectedIds.isEmpty() ? "applied" : "rejected";
                        actionCommands.save(current);
                        for (String requestId : rejectedIds) {
                            var data = new LinkedHashMap<String, Object>();
                            data.put("turn_id", turn.id);
                            data.put("command_id", command.id);
                            data.put("request_id", requestId);
                            data.put("status", "rejected");
                            data.put("reason", "ticket_not_resolvable");
                            var fact = pending.get(requestId);
                            data.put("pending", fact != null);
                            if (fact != null) {
                                data.put("kind", fact.data().get("kind"));
                                data.put("tool_call", fact.data().get("call"));
                            }
                            events.appendCommand(
                                    turn.sessionId,
                                    "required_action.rejected",
                                    data,
                                    "rej_"
                                            + JournalSessionLog.hash(
                                                            (command.id + ":" + requestId)
                                                                    .getBytes(
                                                                            StandardCharsets.UTF_8))
                                                    .substring(0, 56));
                        }
                    });
        }
    }

    private String recoverOutcome(SessionTurnCommandEntity row) {
        if (row.nativeStartSeq < 0) return null;
        var session = sessions.get(row.userId, row.sessionId);
        var log = nativeLogs.open(session);
        long cursor = row.nativeStartSeq, upper = log.head().seq();
        String outcome = null;
        while (cursor < upper) {
            var batch = log.readAfter(cursor, 256);
            if (batch.isEmpty()) break;
            for (var fact : batch) {
                if (fact.seq() > upper) break;
                if (row.id.equals(fact.turnId())) {
                    if (fact.type().equals("run/start")) outcome = null;
                    if (fact.type().equals("run/end"))
                        outcome = String.valueOf(fact.data().get("status"));
                }
                cursor = fact.seq();
            }
        }
        if (outcome == null) return null;
        nativeLogs.refresh(row.sessionId);
        return outcome.equals("suspended") ? "requires_action" : outcome;
    }

    private void requeue(String id) {
        active.remove(id);
        tx.executeWithoutResult(
                status -> {
                    var row = repository.lockById(id).orElseThrow();
                    if (!worker.equals(row.workerId)) return;
                    row.status = row.status.equals("cancel_requested") ? "cancelled" : "queued";
                    row.workerId = null;
                    row.leaseUntil = 0;
                    repository.save(row);
                    events.appendCommand(
                            row.sessionId,
                            "turn." + row.status,
                            Map.of(
                                    "turn_id",
                                    row.id,
                                    "status",
                                    row.status,
                                    "reason",
                                    "admission_retry"),
                            null);
                });
    }

    private void finish(String id, String outcome, Throwable error) {
        try {
            var completed = repository.findById(id).orElseThrow();
            nativeLogs.refresh(completed.sessionId);
            tx.executeWithoutResult(
                    status -> {
                        var row = repository.lockById(id).orElseThrow();
                        if (!worker.equals(row.workerId)
                                || (!row.status.equals("running")
                                        && !row.status.equals("cancel_requested"))) return;
                        String effective = outcome;
                        if (outcome.equals("completed")) {
                            String recorded = recoverOutcome(row);
                            if (recorded != null) effective = recorded;
                        }
                        if (outcome.equals("cancelled")) {
                            effective = recoverOutcome(row);
                            if (effective == null) {
                                row.status = "cancel_requested";
                                row.workerId = null;
                                row.leaseUntil = System.currentTimeMillis() + 5000;
                                repository.save(row);
                                return;
                            }
                        }
                        row.status = effective;
                        row.updatedAt = System.currentTimeMillis();
                        row.leaseUntil = 0;
                        row.errorCode = error == null ? null : error.getClass().getSimpleName();
                        repository.save(row);
                        events.appendCommand(
                                row.sessionId,
                                "turn." + row.status,
                                Map.of("turn_id", id, "status", row.status),
                                null);
                    });
        } finally {
            active.remove(id);
        }
    }
}
