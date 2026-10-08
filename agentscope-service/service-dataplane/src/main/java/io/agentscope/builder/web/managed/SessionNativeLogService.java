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
import io.agentscope.builder.control.ControlPlaneClient.ManagedExecutionScope;
import io.agentscope.builder.control.ManagedEventOutbox;
import io.agentscope.builder.web.catalog.HarnessAgentBuildService;
import io.agentscope.builder.web.managed.service.DeletedSessionRegistry;
import io.agentscope.builder.web.managed.service.SessionEventLog;
import io.agentscope.builder.web.managed.service.SessionEventScope;
import io.agentscope.builder.web.persistence.jpa.SessionExportSourceEntity;
import io.agentscope.builder.web.persistence.jpa.SessionExportSourceRepository;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionExportSink;
import io.agentscope.core.session.SessionKey;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.session.SessionLogExporter;
import io.agentscope.core.session.SessionLogStore;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Repairable committed-fact outbox. This never builds an agent or executes a model/tool. */
@Service
public class SessionNativeLogService {
    private final SessionLogStore store;
    private final ManagedEventOutbox outbox;
    private final SessionEventLog events;
    private final SessionExportSourceRepository sources;
    private final DeletedSessionRegistry deleted;
    private int page;
    private static final Logger LOG = LoggerFactory.getLogger(SessionNativeLogService.class);

    public SessionNativeLogService(
            SessionLogStore store,
            SessionEventLog events,
            SessionExportSourceRepository sources,
            DeletedSessionRegistry deleted,
            ManagedEventOutbox outbox) {
        this.store = store;
        this.events = events;
        this.sources = sources;
        this.deleted = deleted;
        this.outbox = outbox;
    }

    public void beginAdmission(
            ManagedSessionDto session, String admission, ManagedExecutionScope scope) {
        register(session);
        outbox.beginAdmission(session.id(), admission, ControlPlaneClient.eventScope(scope));
    }

    public void bindRun(ManagedSessionDto session, String admission, String run) {
        outbox.bindRun(admission, run, open(session).head().seq());
    }

    public void renewAdmission(String admission) {
        outbox.renewAdmission(admission);
    }

    public void finishAdmission(String admission) {
        outbox.finishAdmission(admission);
    }

    /** Called only for terminal CP Attempts; expired execution writers cannot append again. */
    public Map<String, Object> mirrorStatus(String session, String attempt) {
        boolean finished =
                outbox.freezeAttemptIfFinished(
                        session,
                        attempt,
                        admissions -> {
                            long now = System.currentTimeMillis();
                            SessionLog log = null;
                            var source = sources.findById(session).orElse(null);
                            if (source != null) {
                                var context =
                                        RuntimeContext.builder()
                                                .userId(source.userId)
                                                .sessionId(session)
                                                .build();
                                log =
                                        store.open(
                                                new SessionKey(
                                                        source.userId, source.agentId, session),
                                                context);
                            }
                            var head = log == null ? null : log.head();
                            for (var admission : admissions) {
                                if (!admission.finished && admission.leaseUntil > now) return false;
                                if (head != null
                                        && admission.runId != null
                                        && admission.runId.equals(head.owner())
                                        && head.leaseUntil() > now) return false;
                            }
                            // Fence even a writer that has not acquired yet. A paused
                            // bind->subscribe
                            // continuation must not start after a terminal readiness response.
                            if (log != null) {
                                for (var admission : admissions) {
                                    if (admission.runId != null) log.sealWriter(admission.runId);
                                }
                            }
                            return true;
                        });
        if (finished) {
            // Each exported event must commit its SQL transaction before its native export
            // cursor advances. Do not nest this export inside the terminal fence transaction.
            refresh(session);
            outbox.sealAttempt(session, attempt);
        }
        var status = outbox.status(session, attempt);
        return Map.of(
                "pending",
                status.pending(),
                "last_enqueued_seq",
                status.lastEnqueuedSeq(),
                "last_delivered_seq",
                status.lastDeliveredSeq(),
                "execution_finished",
                finished,
                "ready",
                finished && status.pending() == 0);
    }

    public void register(ManagedSessionDto session) {
        if (sources.existsById(session.id())) return;
        var source = new SessionExportSourceEntity();
        source.sessionId = session.id();
        source.userId = session.ownerId();
        source.agentId = HarnessAgentBuildService.sessionLogAgentId(session);
        sources.save(source);
    }

    public SessionExportSink sink(String session) {
        boolean child =
                sources.findById(session)
                        .map(source -> source.parentSessionId != null)
                        .orElse(false);
        return new SessionExportSink() {
            public String name() {
                return "agentscope-service-public-v1";
            }

            public void accept(SessionEvent event) {
                var projected = CommittedSessionEventProjector.project(event);
                if (child
                        && Set.of(
                                        "turn/start",
                                        "turn/resumed",
                                        "turn/completed",
                                        "turn/failed",
                                        "turn/cancelled",
                                        "turn/interrupted",
                                        "turn/suspended")
                                .contains(event.type())) {
                    String status =
                            switch (event.type()) {
                                case "turn/start", "turn/resumed" -> "running";
                                case "turn/suspended" -> "requires_action";
                                default -> event.type().substring(5);
                            };
                    projected =
                            Optional.of(
                                    new CommittedSessionEventProjector.Event(
                                            "turn." + status,
                                            Map.of(
                                                    "turn_id",
                                                    event.turnId(),
                                                    "status",
                                                    status,
                                                    "source",
                                                    Map.of("native_event_id", event.eventId()))));
                }
                projected.ifPresent(
                        p -> {
                            if (child)
                                events.appendIdempotentLocal(
                                        session, p.type(), p.payload(), event.eventId());
                            else
                                events.appendIdempotentScoped(
                                        session,
                                        p.type(),
                                        p.payload(),
                                        event.eventId(),
                                        event.executionRunId() == null
                                                ? SessionEventScope.NONE
                                                : outbox.scopeForRun(
                                                                session, event.executionRunId())
                                                        .forNativeFact());
                        });
            }
        };
    }

    public SessionLog open(ManagedSessionDto session) {
        var context =
                RuntimeContext.builder().userId(session.ownerId()).sessionId(session.id()).build();
        return store.open(
                new SessionKey(
                        session.ownerId(),
                        HarnessAgentBuildService.sessionLogAgentId(session),
                        session.id()),
                context);
    }

    public SessionLog openChild(ManagedSessionDto parent, String childSession) {
        var parentLog = open(parent);
        String childAgent = null;
        for (var event : parentLog.scan(0, parentLog.head().seq()))
            if (event.type().equals("subagent/spawned")
                    && childSession.equals(event.data().get("childSessionId")))
                childAgent = (String) event.data().get("childLogAgentId");
        if (childAgent == null)
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Child session is not linked to this parent");
        return store.open(
                new SessionKey(parent.ownerId(), childAgent, childSession),
                RuntimeContext.builder().userId(parent.ownerId()).sessionId(childSession).build());
    }

    /** Resolve only descendants recorded in the owner's native logs, never arbitrary session IDs. */
    public Map<String, SessionLog> descendants(ManagedSessionDto parent) {
        var result = new LinkedHashMap<String, SessionLog>();
        var pending = new ArrayDeque<SessionLog>();
        pending.add(open(parent));
        while (!pending.isEmpty()) {
            var log = pending.removeFirst();
            for (var event : log.scan(0, log.head().seq())) {
                if (!event.type().equals("subagent/spawned")) continue;
                String id = (String) event.data().get("childSessionId");
                String agent = (String) event.data().get("childLogAgentId");
                if (id == null || agent == null || id.equals(parent.id()) || result.containsKey(id))
                    continue;
                if (result.size() >= 256)
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT, "Child traversal limit exceeded");
                var source = sources.findById(id).orElseGet(SessionExportSourceEntity::new);
                if (source.sessionId != null
                        && (!parent.ownerId().equals(source.userId)
                                || !agent.equals(source.agentId)))
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT, "Child identity conflict");
                source.sessionId = id;
                source.userId = parent.ownerId();
                source.agentId = agent;
                source.parentSessionId = parent.id();
                sources.save(source);
                var child =
                        store.open(
                                new SessionKey(parent.ownerId(), agent, id),
                                RuntimeContext.builder()
                                        .userId(parent.ownerId())
                                        .sessionId(id)
                                        .build());
                result.put(id, child);
                pending.add(child);
                refresh(id);
            }
        }
        return result;
    }

    public void refreshDescendant(ManagedSessionDto parent, String child) {
        if (!descendants(parent).containsKey(child))
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Child session is not linked to this parent");
    }

    public void refresh(String session) {
        sources.findById(session).ifPresent(this::drain);
    }

    private void drain(SessionExportSourceEntity source) {
        if (deleted.isDeleted(source.sessionId)) return;
        var context =
                RuntimeContext.builder().userId(source.userId).sessionId(source.sessionId).build();
        var log =
                store.open(
                        new SessionKey(source.userId, source.agentId, source.sessionId), context);
        new SessionLogExporter(log.inbox(), sink(source.sessionId)).drain();
        new SessionLogExporter(log, sink(source.sessionId)).drain();
    }

    @Scheduled(fixedDelayString = "${builder.agent-api.export-poll-ms:5000}")
    public synchronized void repair() {
        var batch = sources.findAll(PageRequest.of(page, 100, Sort.by("sessionId")));
        for (var source : batch) {
            try {
                drain(source);
            } catch (RuntimeException error) {
                LOG.warn("Session {} export pending retry: {}", source.sessionId, error.toString());
            }
        }
        page = batch.hasNext() ? page + 1 : 0;
    }
}
