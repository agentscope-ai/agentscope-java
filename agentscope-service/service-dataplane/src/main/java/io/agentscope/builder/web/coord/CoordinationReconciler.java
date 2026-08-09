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
package io.agentscope.builder.web.coord;

import io.agentscope.builder.web.managed.DataSessionService;
import io.agentscope.builder.web.managed.ManagedSessionDto;
import io.agentscope.builder.web.managed.service.SessionEventLog;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Reconciles expired turn leases and HITL tickets so multi-replica Brain fleets do not leave
 * sessions stuck in {@code running} / {@code requires_action}.
 *
 * <p>Session rows live on the control plane; orphan recovery without a local session table relies
 * on expired turn leases rather than scanning all sessions.
 */
@Component
public class CoordinationReconciler {

    private static final Logger log = LoggerFactory.getLogger(CoordinationReconciler.class);

    private final CoordinationStore coordinationStore;
    private final DataSessionService sessionService;
    private final SessionEventLog eventLog;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "coord-reconciler");
                        t.setDaemon(true);
                        return t;
                    });

    public CoordinationReconciler(
            CoordinationStore coordinationStore,
            @Lazy DataSessionService sessionService,
            SessionEventLog eventLog) {
        this.coordinationStore = coordinationStore;
        this.sessionService = sessionService;
        this.eventLog = eventLog;
    }

    @PostConstruct
    public void start() {
        scheduler.scheduleAtFixedRate(this::reconcileSafely, 15, 15, TimeUnit.SECONDS);
        scheduler.schedule(this::reconcileSafely, 5, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
    }

    private void reconcileSafely() {
        try {
            reconcileExpiredTurnLeases();
            reconcileExpiredHitlTickets();
        } catch (Exception ex) {
            log.warn("Coordination reconcile failed: {}", ex.getMessage());
        }
    }

    private void reconcileExpiredTurnLeases() {
        long now = System.currentTimeMillis();
        List<CoordinationStore.LeaseHandle> expired = coordinationStore.listExpiredTurnLeases(now);
        for (CoordinationStore.LeaseHandle lease : expired) {
            coordinationStore.releaseTurnLease(lease.sessionId(), lease.instanceId());
            closeOrphanSession(
                    lease.sessionId(),
                    lease.ownerId(),
                    "session.orphaned_after_lease_expiry",
                    Map.of(
                            "previousInstanceId",
                            lease.instanceId() == null ? "" : lease.instanceId()));
        }
    }

    private void reconcileExpiredHitlTickets() {
        long now = System.currentTimeMillis();
        for (CoordinationStore.HitlTicket ticket : coordinationStore.listExpiredHitlTickets(now)) {
            coordinationStore.resolveHitlTicket(ticket.toolUseId(), false, "timed_out");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("toolUseId", ticket.toolUseId());
            payload.put("reason", "hitl_ticket_expired");
            eventLog.append(ticket.sessionId(), "session.hitl_expired", payload);
            if (ticket.ownerId() != null) {
                sessionService.updateStatus(
                        ticket.ownerId(),
                        ticket.sessionId(),
                        DataSessionService.STATUS_IDLE,
                        payload);
            }
            coordinationStore.deleteHitlTicket(ticket.toolUseId());
        }
    }

    private void closeOrphanSession(
            String sessionId, String ownerId, String eventType, Map<String, Object> payload) {
        eventLog.append(sessionId, eventType, payload);
        String oid = ownerId;
        if (oid == null) {
            try {
                ManagedSessionDto session = sessionService.requireById(sessionId);
                oid = session.ownerId();
            } catch (Exception ex) {
                log.debug(
                        "Could not resolve owner for orphan session {}: {}",
                        sessionId,
                        ex.getMessage());
            }
        }
        if (oid != null) {
            Map<String, Object> stop = new LinkedHashMap<>(payload);
            stop.put("reconciled", true);
            sessionService.updateStatus(oid, sessionId, DataSessionService.STATUS_IDLE, stop);
            log.info("Reconciled orphan session {} via {}", sessionId, eventType);
        }
    }
}
