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

import io.agentscope.builder.control.ControlPlaneClient;
import io.agentscope.builder.control.SessionResolveResult;
import io.agentscope.builder.web.managed.service.ManagedJsonHelper;
import io.agentscope.builder.web.managed.service.SessionEventLog;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Data-plane session service: status transitions, override merges and turn execution for sessions
 * that were created (and version-pinned) by the control plane.
 *
 * <p>Session rows live in the control-plane schema. This service reads and patches them via {@link
 * ControlPlaneClient} and keeps the local {@link SessionEventLog} for transcript / SSE fan-out.
 */
@Service
public class DataSessionService {

    private static final Logger log = LoggerFactory.getLogger(DataSessionService.class);

    /** @deprecated use {@link SessionStatuses#CREATED} */
    public static final String STATUS_CREATED = SessionStatuses.CREATED;

    /** @deprecated use {@link SessionStatuses#RUNNING} */
    public static final String STATUS_RUNNING = SessionStatuses.RUNNING;

    /** @deprecated use {@link SessionStatuses#IDLE} */
    public static final String STATUS_IDLE = SessionStatuses.IDLE;

    /** @deprecated use {@link SessionStatuses#REQUIRES_ACTION} */
    public static final String STATUS_REQUIRES_ACTION = SessionStatuses.REQUIRES_ACTION;

    /** @deprecated use {@link SessionStatuses#TERMINATED} */
    public static final String STATUS_TERMINATED = SessionStatuses.TERMINATED;

    /** @deprecated use {@link SessionStatuses#RESCHEDULED} */
    public static final String STATUS_RESCHEDULED = SessionStatuses.RESCHEDULED;

    /** @deprecated use {@link SessionStatuses#ARCHIVED} */
    public static final String STATUS_ARCHIVED = SessionStatuses.ARCHIVED;

    private final ControlPlaneClient controlPlaneClient;
    private final SessionEventLog eventLog;
    private final ManagedJsonHelper jsonHelper;
    private final SessionTurnRunner turnRunner;

    public DataSessionService(
            ControlPlaneClient controlPlaneClient,
            SessionEventLog eventLog,
            ManagedJsonHelper jsonHelper,
            SessionTurnRunner turnRunner) {
        this.controlPlaneClient = controlPlaneClient;
        this.eventLog = eventLog;
        this.jsonHelper = jsonHelper;
        this.turnRunner = turnRunner;
    }

    /** Returns a session owned by the caller. */
    public ManagedSessionDto get(String ownerId, String sessionId) {
        ManagedSessionDto session = requireById(sessionId);
        if (!ownerId.equals(session.ownerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Session access denied");
        }
        return session;
    }

    /** Looks up a session by id without owner scoping (worker / internal use). */
    public ManagedSessionDto requireById(String sessionId) {
        return controlPlaneClient.resolveSession(sessionId).session();
    }

    /** Updates session status and optional stop reason metadata via the control plane. */
    public ManagedSessionDto updateStatus(
            String ownerId, String sessionId, String status, Map<String, Object> stopReason) {
        if (SessionStatuses.isLifecycleStatus(status)
                && !SessionStatuses.TERMINATED.equals(status)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Lifecycle status '" + status + "' is owned by the control plane");
        }
        ManagedSessionDto current = get(ownerId, sessionId);
        controlPlaneClient.patchSessionRuntime(sessionId, status, stopReason, ownerId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        if (stopReason != null) {
            payload.put("stopReason", stopReason);
        }
        eventLog.append(sessionId, "session.status_" + status, payload);
        long now = System.currentTimeMillis();
        return new ManagedSessionDto(
                current.id(),
                current.ownerId(),
                current.agentId(),
                current.agentOwnerId(),
                current.agentVersion(),
                current.agentRefType(),
                current.agentOverridesJson(),
                current.environmentId(),
                current.externalKey(),
                current.memoryStoreIds(),
                current.vaultIds(),
                current.resources(),
                status,
                stopReason,
                current.createdAt(),
                now,
                current.archivedAt());
    }

    /**
     * Merges session-scoped agent overrides. Persistence of overrides requires a control-plane API;
     * for fast-dev this logs a warning, appends a local event, and returns the current session
     * unchanged.
     */
    public ManagedSessionDto mergeAgentOverrides(
            String ownerId, String sessionId, Map<String, Object> patch) {
        ManagedSessionDto session = get(ownerId, sessionId);
        log.warn(
                "mergeAgentOverrides is not persisted via control plane yet; session={},"
                        + " patchKeys={}",
                sessionId,
                patch != null ? patch.keySet() : null);
        Map<String, Object> current =
                AgentOverrideMerger.merge(jsonHelper.readMap(session.agentOverridesJson()), patch);
        eventLog.append(sessionId, SessionEventTypes.SESSION_UPDATED, Map.of("overrides", current));
        return session;
    }

    /** Runs one harness turn for the session asynchronously. */
    public void runTurn(String ownerId, String sessionId, Map<String, Object> messagePayload) {
        runTurn(ownerId, sessionId, messagePayload, () -> {});
    }

    /**
     * Runs one harness turn asynchronously, invoking {@code onAdmitted} once the turn is certain to
     * run.
     */
    public void runTurn(
            String ownerId,
            String sessionId,
            Map<String, Object> messagePayload,
            Runnable onAdmitted) {
        ManagedSessionDto session = get(ownerId, sessionId);
        String userMessage = extractUserMessage(messagePayload);
        // Turn lease is acquired inside runTurnAsync on this thread; CONFLICT throws before
        // status flips to running.
        turnRunner.runTurnAsync(session, userMessage, onAdmitted);
    }

    /** Resolves the full control-plane session payload (snapshot, mounts, environment). */
    public SessionResolveResult resolve(String sessionId) {
        return controlPlaneClient.resolveSession(sessionId);
    }

    static String extractUserMessage(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return "";
        }
        for (String key : List.of("text", "message", "content")) {
            Object value = payload.get(key);
            if (value != null) {
                String text = String.valueOf(value).trim();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return String.valueOf(payload);
    }
}
