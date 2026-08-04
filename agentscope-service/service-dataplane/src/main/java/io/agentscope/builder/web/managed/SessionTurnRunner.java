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

import io.agentscope.builder.web.api.error.ApiErrorDetail;
import io.agentscope.builder.web.api.error.ApiErrorType;
import io.agentscope.builder.web.api.error.ApiException;
import io.agentscope.builder.web.catalog.HarnessAgentBuildService;
import io.agentscope.builder.web.coord.CoordinationStore;
import io.agentscope.builder.web.coord.TurnLeaseService;
import io.agentscope.builder.web.managed.service.DeletedSessionRegistry;
import io.agentscope.builder.web.managed.service.SessionEventLog;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.middleware.TeamsMiddleware;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/** Executes a managed session turn against the harness agent and records session events. */
@Service
public class SessionTurnRunner {

    private static final Logger log = LoggerFactory.getLogger(SessionTurnRunner.class);

    private final HarnessAgentBuildService agentBuildService;
    private final DataSessionService sessionService;
    private final SessionEventLog eventLog;
    private final SessionEventMapper eventMapper;
    private final SessionEventPreviewBus previewBus;
    private final DataEnvironmentService environmentService;
    private final HandsLeaseService handsLeaseService;
    private final TurnLeaseService turnLeaseService;
    private final CoordinationStore coordinationStore;
    private final DeletedSessionRegistry deletedSessions;
    private final ConcurrentHashMap<String, Disposable> activeTurns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, HarnessAgent> activeAgents = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TurnLeaseService.TurnLease> activeTurnLeases =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SessionEventMapper.PreviewIds> previewIdsBySession =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> startedPreviewTypes =
            new ConcurrentHashMap<>();

    public SessionTurnRunner(
            HarnessAgentBuildService agentBuildService,
            @Lazy DataSessionService sessionService,
            SessionEventLog eventLog,
            SessionEventMapper eventMapper,
            SessionEventPreviewBus previewBus,
            DataEnvironmentService environmentService,
            HandsLeaseService handsLeaseService,
            TurnLeaseService turnLeaseService,
            CoordinationStore coordinationStore,
            DeletedSessionRegistry deletedSessions) {
        this.agentBuildService = agentBuildService;
        this.sessionService = sessionService;
        this.eventLog = eventLog;
        this.eventMapper = eventMapper;
        this.previewBus = previewBus;
        this.environmentService = environmentService;
        this.handsLeaseService = handsLeaseService;
        this.turnLeaseService = turnLeaseService;
        this.coordinationStore = coordinationStore;
        this.deletedSessions = deletedSessions;
    }

    /** Runs a turn asynchronously so inbound HTTP handlers can return quickly. */
    public void runTurnAsync(ManagedSessionDto session, String userMessage) {
        runTurnAsync(session, userMessage, () -> {});
    }

    /**
     * Runs a turn asynchronously, invoking {@code onAdmitted} once the turn is certain to run — the
     * lease is held by then, so a caller can record the message that triggered it only if it will
     * actually be processed.
     */
    public void runTurnAsync(ManagedSessionDto session, String userMessage, Runnable onAdmitted) {
        Msg userMsg = Msg.builder().role(MsgRole.USER).textContent(userMessage).build();
        runTurnAsync(session, List.of(userMsg), onAdmitted);
    }

    /**
     * Resumes a suspended turn with external tool results (self-hosted worker /
     * {@code user.tool_result}).
     */
    public void resumeWithToolResults(
            ManagedSessionDto session, List<ToolResultBlock> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            throw ApiException.invalidRequest(
                    "missing_tool_results", "tool results are required to resume", "payload");
        }
        Msg.Builder resumeBuilder = ToolResultMessage.builder();
        for (ToolResultBlock block : toolResults) {
            ((ToolResultMessage.Builder) resumeBuilder).result(block);
        }
        runTurnAsync(session, List.of(resumeBuilder.build()), () -> {});
    }

    private void runTurnAsync(ManagedSessionDto session, List<Msg> inputMsgs, Runnable onAdmitted) {
        // A wakeup can race teardown: the control plane may have deleted the session
        // between the wake being queued and this turn starting.
        if (deletedSessions.isDeleted(session.id())) {
            log.info("Skipping turn for deleted session {}", session.id());
            return;
        }
        TurnLeaseService.TurnLease lease =
                turnLeaseService.acquireOrConflict(
                        session.id(),
                        session.ownerId(),
                        () -> interruptLocal(session.id(), "remote"));
        activeTurnLeases.put(session.id(), lease);
        onAdmitted.run();
        sessionService.updateStatus(
                session.ownerId(), session.id(), DataSessionService.STATUS_RUNNING, null);
        Schedulers.boundedElastic()
                .schedule(
                        () -> {
                            try {
                                runTurn(session, inputMsgs, lease);
                            } catch (Exception ex) {
                                log.warn(
                                        "Managed session turn failed: sessionId={}, error={}",
                                        session.id(),
                                        ex.getMessage());
                                failTurn(session, ex, "turn_failed");
                            } finally {
                                activeTurnLeases.remove(session.id(), lease);
                                lease.close();
                                previewIdsBySession.remove(session.id());
                                startedPreviewTypes.remove(session.id());
                            }
                        });
    }

    /**
     * Cancels an in-flight turn. Prefer local cancellation when this instance owns the agent;
     * otherwise record a coordination-store interrupt ticket for the owning replica to consume on
     * its next lease heartbeat.
     */
    public void interrupt(String sessionId) {
        if (interruptLocal(sessionId, "local")) {
            return;
        }
        coordinationStore.requestTurnInterrupt(sessionId, "user.interrupt");
        eventLog.append(
                sessionId,
                SessionEventTypes.SESSION_INTERRUPTED,
                Map.of("status", "interrupt_requested"));
    }

    /**
     * Performs local turn cancellation when this JVM holds the active agent. Returns {@code true}
     * when a local turn was interrupted.
     */
    private boolean interruptLocal(String sessionId, String source) {
        HarnessAgent agent = activeAgents.get(sessionId);
        if (agent == null) {
            return false;
        }
        try {
            agent.interrupt();
        } catch (Exception ex) {
            log.warn("Harness interrupt failed for {}: {}", sessionId, ex.getMessage());
        }
        activeAgents.remove(sessionId);
        Disposable disposable = activeTurns.remove(sessionId);
        if (disposable != null) {
            disposable.dispose();
        }
        handsLeaseService.release(sessionId);
        TurnLeaseService.TurnLease lease = activeTurnLeases.remove(sessionId);
        if (lease != null) {
            lease.close();
        }
        // Consume any pending ticket so a subsequent heartbeat does not double-fire.
        coordinationStore.consumeTurnInterrupt(sessionId);
        eventLog.append(
                sessionId,
                SessionEventTypes.SESSION_INTERRUPTED,
                Map.of("status", "interrupted", "source", source));
        return true;
    }

    /**
     * Drops the footprint of a session the control plane has deleted: an in-flight turn is cancelled
     * first, then the team wakeup bindings, the cached instance with its persisted agent state, and
     * the per-session preview bookkeeping. Team teardown relies on this — otherwise a deleted member
     * keeps its wakeup registration and a later team reusing the same member name routes to the dead
     * session.
     */
    public void releaseSession(String sessionId, String ownerId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        interruptLocal(sessionId, "session-deleted");
        TeamsMiddleware.unregisterSession(sessionId);
        agentBuildService.discardSession(ownerId, sessionId);
        previewIdsBySession.remove(sessionId);
        startedPreviewTypes.remove(sessionId);
    }

    private void runTurn(
            ManagedSessionDto session, List<Msg> inputMsgs, TurnLeaseService.TurnLease lease) {
        EnvironmentDto environment = null;
        if (session.environmentId() != null) {
            environment = environmentService.get(session.ownerId(), session.environmentId());
        }
        SessionAgentBuildSpec spec =
                new SessionAgentBuildSpec(
                        session.agentVersion(),
                        session.environmentId(),
                        environment,
                        session.agentOverridesJson(),
                        session.memoryStoreIds(),
                        session.vaultIds(),
                        session.resources());

        HarnessAgent agent = agentBuildService.getOrBuildAgent(session, spec);
        if (agent == null) {
            throw new IllegalStateException("Agent not available: " + session.agentId());
        }

        RuntimeContext.Builder rcBuilder =
                RuntimeContext.builder().userId(session.ownerId()).sessionId(session.id());
        Optional<Sandbox> handsSandbox = handsLeaseService.acquire(session, environment);
        handsSandbox.ifPresent(
                sandbox ->
                        rcBuilder.put(
                                SandboxContext.class,
                                SandboxContext.builder()
                                        .externalSandbox(sandbox)
                                        .isolationScope(IsolationScope.SESSION)
                                        .build()));
        RuntimeContext rc = rcBuilder.build();

        Flux<AgentEvent> stream = agent.streamEvents(inputMsgs, rc);

        previewIdsBySession.put(session.id(), new SessionEventMapper.PreviewIds());
        startedPreviewTypes.put(session.id(), ConcurrentHashMap.newKeySet());
        activeAgents.put(session.id(), agent);
        AtomicBoolean suspended = new AtomicBoolean(false);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> errorRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        Disposable subscription =
                stream.subscribe(
                        event -> {
                            if (handleAgentEvent(session.id(), event)) {
                                suspended.set(true);
                            }
                        },
                        error -> {
                            errorRef.set(error);
                            done.countDown();
                        },
                        done::countDown);
        activeTurns.put(session.id(), subscription);
        try {
            done.await();
            Throwable error = errorRef.get();
            if (error != null) {
                failTurn(session, error, "model_call_failed");
                if (error instanceof RuntimeException re) {
                    throw re;
                }
                throw new RuntimeException(error);
            }
            if (suspended.get()) {
                sessionService.updateStatus(
                        session.ownerId(),
                        session.id(),
                        DataSessionService.STATUS_REQUIRES_ACTION,
                        Map.of("reason", "tool_suspended"));
                eventLog.append(
                        session.id(),
                        SessionEventTypes.SESSION_REQUIRES_ACTION,
                        Map.of("reason", "tool_suspended"));
            } else {
                sessionService.updateStatus(
                        session.ownerId(), session.id(), DataSessionService.STATUS_IDLE, null);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            subscription.dispose();
            failTurn(session, ie, "interrupted");
        } finally {
            activeTurns.remove(session.id());
            activeAgents.remove(session.id());
            // Keep work-queue lease for suspended turns so workers can finish pending tools.
            if (!suspended.get()) {
                handsLeaseService.release(session.id());
            }
        }
    }

    private void failTurn(ManagedSessionDto session, Throwable error, String code) {
        ApiErrorDetail detail =
                ApiErrorDetail.of(
                                ApiErrorType.API,
                                code,
                                error.getMessage() != null ? error.getMessage() : code)
                        .withSessionId(session.id())
                        .withRetryStatus("not_retrying");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", detail.toMap());
        eventLog.append(session.id(), SessionEventTypes.SESSION_ERROR, payload);
        Map<String, Object> stopReason = new LinkedHashMap<>();
        stopReason.put("error", detail.toMap());
        sessionService.updateStatus(
                session.ownerId(), session.id(), DataSessionService.STATUS_TERMINATED, stopReason);
        handsLeaseService.release(session.id());
    }

    /**
     * @return {@code true} when the event indicates {@link GenerateReason#TOOL_SUSPENDED}
     */
    private boolean handleAgentEvent(String sessionId, AgentEvent event) {
        if (event instanceof AgentResultEvent result
                && result.getResult() != null
                && result.getResult().getGenerateReason() == GenerateReason.TOOL_SUSPENDED) {
            persistSuspendedToolUses(sessionId, result.getResult());
            return true;
        }

        SessionEventMapper.PreviewIds ids =
                previewIdsBySession.computeIfAbsent(
                        sessionId, ignored -> new SessionEventMapper.PreviewIds());
        SessionEventMapper.MappingResult mapped = eventMapper.map(event, ids);
        mapped.preview()
                .ifPresent(
                        frame -> {
                            Set<String> started =
                                    startedPreviewTypes.computeIfAbsent(
                                            sessionId, ignored -> ConcurrentHashMap.newKeySet());
                            if (started.add(frame.targetType() + ":" + frame.eventId())) {
                                previewBus.emitStart(
                                        sessionId, frame.targetType(), frame.eventId());
                            }
                            // null delta = start-only announcement (e.g. tool_use begin)
                            if (frame.delta() != null) {
                                previewBus.emitDelta(
                                        sessionId,
                                        frame.targetType(),
                                        frame.eventId(),
                                        frame.delta());
                            }
                        });
        mapped.persisted()
                .ifPresent(
                        persisted ->
                                eventLog.append(
                                        sessionId,
                                        persisted.type(),
                                        persisted.payload(),
                                        persisted.eventId()));
        return false;
    }

    private void persistSuspendedToolUses(String sessionId, Msg result) {
        for (ToolUseBlock tub : result.getContentBlocks(ToolUseBlock.class)) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", tub.getId());
            payload.put("name", tub.getName());
            payload.put("input", tub.getInput() != null ? tub.getInput() : Map.of());
            payload.put("toolCallId", tub.getId());
            payload.put("toolName", tub.getName());
            payload.put("state", "pending");
            eventLog.append(sessionId, SessionEventTypes.AGENT_TOOL_USE, payload);
        }
    }

    /** Builds a {@link ToolResultBlock} from a worker/user tool_result payload. */
    public static ToolResultBlock toolResultFromPayload(Map<String, Object> payload) {
        String toolUseId = stringValue(payload.get("tool_use_id"));
        if (toolUseId == null) {
            toolUseId = stringValue(payload.get("toolUseId"));
        }
        if (toolUseId == null) {
            throw ApiException.invalidRequest(
                    "missing_tool_use_id",
                    "tool_use_id is required",
                    "events[].payload.tool_use_id");
        }
        String name = stringValue(payload.get("name"));
        if (name == null) {
            name = stringValue(payload.get("toolName"));
        }
        String content = stringValue(payload.get("content"));
        if (content == null) {
            content = stringValue(payload.get("output"));
        }
        if (content == null) {
            content = "";
        }
        boolean isError =
                Boolean.TRUE.equals(payload.get("is_error"))
                        || Boolean.TRUE.equals(payload.get("isError"));
        if (isError) {
            return ToolResultBlock.of(
                    toolUseId,
                    name,
                    TextBlock.builder().text(content).build(),
                    Map.of("error", true));
        }
        return ToolResultBlock.of(toolUseId, name, TextBlock.builder().text(content).build());
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
