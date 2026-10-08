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

import io.agentscope.builder.web.catalog.HarnessAgentBuildService;
import io.agentscope.builder.web.persistence.jpa.SessionTurnCommandRepository;
import io.agentscope.core.session.JournalSessionLog;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionInbox;
import io.agentscope.core.session.SessionInteractions;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.session.SessionProjection;
import io.agentscope.core.session.SessionTurns;
import io.agentscope.core.util.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Opaque checkpoint handles; no raw prompts or credentials are returned to ordinary clients. */
@Service
public final class SessionCheckpointService {
    private final DataSessionService sessions;
    private final SessionNativeLogService logs;
    private final SessionTurnCommandRepository turns;
    private final HarnessAgentBuildService agents;

    public SessionCheckpointService(
            DataSessionService sessions,
            SessionNativeLogService logs,
            SessionTurnCommandRepository turns,
            HarnessAgentBuildService agents) {
        this.sessions = sessions;
        this.logs = logs;
        this.turns = turns;
        this.agents = agents;
    }

    private static boolean checkpoint(SessionEvent event) {
        return Set.of("state/checkpoint", "migration/baseline", "state/restored")
                .contains(event.type());
    }

    public Map<String, Object> list(String user, String session, String after, int limit) {
        if (limit < 1 || limit > 1000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var log = logs.open(sessions.get(user, session));
        var result = new ArrayList<Map<String, Object>>();
        boolean collecting = after == null, more = false;
        String next = after;
        for (var event : log.scan(0, log.head().seq())) {
            if (!checkpoint(event)) continue;
            String id = "cp_" + event.eventId();
            if (!collecting) {
                if (id.equals(after)) collecting = true;
                continue;
            }
            if (result.size() == limit) {
                more = true;
                break;
            }
            var item = new LinkedHashMap<String, Object>();
            item.put("checkpoint_id", id);
            item.put("created_at", event.occurredAt());
            item.put("turn_id", event.turnId());
            item.put("run_id", event.executionRunId());
            item.put("reason", event.data().get("reason"));
            result.add(item);
            next = id;
        }
        if (!collecting)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown checkpoint cursor");
        var response = new LinkedHashMap<String, Object>();
        response.put("data", result);
        response.put("next_cursor", next);
        response.put("has_more", more);
        return response;
    }

    private void requireSettled(String session, SessionLog log) {
        if (turns.findBySessionIdOrderByCreatedAtAsc(session).stream()
                .anyMatch(turn -> !Set.of("completed", "cancelled").contains(turn.status)))
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Finish or cancel pending turns before changing session state");
        var state = SessionProjection.read(log);
        if (!state.uncertainToolCalls().isEmpty()
                || !state.activeRuns().isEmpty()
                || !SessionInteractions.pending(log).isEmpty())
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Reconcile execution and resolve interactions first");
        var applied = SessionTurns.read(log).applied();
        var inbox = new SessionInbox(log).snapshot();
        if (inbox.commands().stream()
                .anyMatch(
                        command ->
                                Set.of("steer", "inject").contains(command.kind())
                                        && !applied.contains(command.id())
                                        && !inbox.rejected().containsKey(command.id())))
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Pending session input must be consumed before restore");
    }

    public Map<String, Object> restore(
            String user,
            String sourceId,
            String targetId,
            String checkpointId,
            String key,
            String reason) {
        if (key == null
                || key.isBlank()
                || key.length() > 256
                || checkpointId == null
                || reason == null
                || reason.isBlank())
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Key, checkpoint_id and reason are required");
        var source = sessions.get(user, sourceId);
        var target = sessions.get(user, targetId);
        if (!source.agentId().equals(target.agentId()))
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Fork target must use the same Agent");
        var sourceLog = logs.open(source);
        var targetLog = logs.open(target);
        boolean fork = !sourceId.equals(targetId);
        String operation =
                JournalSessionLog.hash(
                        (user + "\n" + targetId + "\ncheckpoint\n" + key)
                                .getBytes(StandardCharsets.UTF_8));
        // Writer leases fence execution while selecting and committing the state change.
        var sourceWriter = sourceLog.acquire(UUID.randomUUID().toString(), Duration.ofMinutes(2));
        SessionLog.Writer targetWriter = null;
        try {
            if (fork)
                targetWriter =
                        targetLog.acquire(UUID.randomUUID().toString(), Duration.ofMinutes(2));
            for (var event : targetLog.scan(0, targetLog.head().seq()))
                if (operation.equals(event.data().get("operation_id"))) {
                    if (!checkpointId.equals(event.data().get("checkpoint_id"))
                            || !reason.equals(event.data().get("reason"))
                            || !sourceId.equals(event.data().get("source_session_id")))
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT,
                                "Checkpoint key reused with different request");
                    return receipt(sourceId, targetId, checkpointId, operation);
                }
            requireSettled(sourceId, sourceLog);
            if (fork) {
                requireSettled(targetId, targetLog);
                if (targetLog.head().seq() != 0)
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT, "Fork destination must be empty");
            }
            SessionEvent selected = null;
            var uncertain = new HashSet<String>();
            for (var event : sourceLog.scan(0, sourceLog.head().seq())) {
                if (event.type().equals("tool/dispatch"))
                    uncertain.add((String) event.data().get("toolCallId"));
                if (event.type().equals("action/end") && event.data().get("result") != null)
                    uncertain.remove((String) event.data().get("toolCallId"));
                if (event.type().equals("recovery/applied")
                        && event.data().get("resolvedToolCallIds") instanceof List<?> ids)
                    ids.forEach(uncertain::remove);
                if (("cp_" + event.eventId()).equals(checkpointId) && checkpoint(event)) {
                    selected = event;
                    break;
                }
            }
            if (selected == null)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Checkpoint not found");
            if (!uncertain.isEmpty())
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "Selected checkpoint has unresolved dispatched tools");
            var state =
                    new LinkedHashMap<String, Object>(
                            JsonUtils.getJsonCodec()
                                    .fromJson(
                                            (String) selected.data().get("stateJson"), Map.class));
            state.put("session_id", targetId);
            state.put("user_id", user);
            var data = new LinkedHashMap<String, Object>();
            data.put("stateJson", JsonUtils.getJsonCodec().toJson(state));
            data.put("coverage", "baseline_only");
            data.put("source_session_id", sourceId);
            data.put("checkpoint_id", checkpointId);
            data.put("operation_id", operation);
            data.put("reason", reason);
            data.put("projectorVersion", 1);
            long seq = targetLog.head().seq();
            var writer = fork ? targetWriter : sourceWriter;
            targetLog.commit(
                    writer,
                    "restore-" + operation,
                    seq,
                    List.of(
                            new SessionEvent(
                                    1,
                                    "restore_" + operation.substring(0, 56),
                                    seq + 1,
                                    System.currentTimeMillis(),
                                    fork ? "migration/baseline" : "state/restored",
                                    null,
                                    null,
                                    true,
                                    JsonUtils.getJsonCodec().toJson(data))));
            logs.register(target);
            logs.refresh(targetId);
            agents.discardSession(user, targetId);
            return receipt(sourceId, targetId, checkpointId, operation);
        } finally {
            if (targetWriter != null) targetLog.release(targetWriter);
            sourceLog.release(sourceWriter);
        }
    }

    private Map<String, Object> receipt(
            String source, String target, String checkpoint, String operation) {
        return Map.of(
                "source_session_id",
                source,
                "session_id",
                target,
                "checkpoint_id",
                checkpoint,
                "operation_id",
                operation,
                "status",
                "completed");
    }
}
