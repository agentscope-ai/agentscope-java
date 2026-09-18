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
import io.agentscope.builder.web.api.error.ApiErrorDetail;
import io.agentscope.builder.web.api.error.ApiErrorType;
import io.agentscope.builder.web.api.error.ApiException;
import io.agentscope.builder.web.catalog.HarnessAgentBuildService;
import io.agentscope.builder.web.coord.CoordinationStore;
import io.agentscope.builder.web.coord.TurnLeaseService;
import io.agentscope.builder.web.managed.service.DeletedSessionRegistry;
import io.agentscope.builder.web.managed.service.SessionEventLog;
import io.agentscope.builder.web.toolbus.ToolConfirmationCoordinator;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Disposable;
import reactor.core.publisher.BaseSubscriber;
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
    private final ControlPlaneClient controlPlaneClient;
    private final ToolConfirmationCoordinator confirmationCoordinator;
    private final ConcurrentHashMap<String, Disposable> activeTurns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, HarnessAgent> activeAgents = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TurnLeaseService.TurnLease> activeTurnLeases =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, java.util.concurrent.CountDownLatch> activeTurnDone =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> interruptedTurns =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> turnMutexes = new ConcurrentHashMap<>();
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
            DeletedSessionRegistry deletedSessions,
            ControlPlaneClient controlPlaneClient,
            @Lazy ToolConfirmationCoordinator confirmationCoordinator) {
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
        this.controlPlaneClient = controlPlaneClient;
        this.confirmationCoordinator = confirmationCoordinator;
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
        AtomicReference<ManagedExecutionScope> admittedScopeRef = new AtomicReference<>();
        AtomicReference<TurnLeaseService.TurnLease> admittedLeaseRef = new AtomicReference<>();
        TurnLeaseService.TurnLease lease =
                turnLeaseService.acquireOrConflictFenced(
                        session.id(),
                        session.ownerId(),
                        request -> {
                            synchronized (turnMutex(session.id())) {
                                boolean localLeaseLost =
                                        request.fenceToken() == null
                                                && "turn_lease_lost".equals(request.reason());
                                TurnLeaseService.TurnLease expectedLease = admittedLeaseRef.get();
                                if (expectedLease == null) {
                                    if (!localLeaseLost) {
                                        requeueInterrupt(session.id(), request);
                                    }
                                    return;
                                }
                                ManagedExecutionScope expectedScope = admittedScopeRef.get();
                                if (request.fenceToken() == null) {
                                    boolean interruptedExpected =
                                            interruptExpected(
                                                    session.id(),
                                                    expectedScope,
                                                    expectedLease,
                                                    request.reason());
                                    // turn_lease_lost is synthesized locally for one exact lease.
                                    // Never turn a stale A signal into a durable unfenced request
                                    // that a replacement B could consume.
                                    if (!interruptedExpected && !localLeaseLost) {
                                        requeueInterrupt(session.id(), request);
                                    }
                                } else if (expectedScope == null) {
                                    // Session resolve is still establishing the immutable managed
                                    // scope. Requeue rather than destructively consume the abort.
                                    requeueInterrupt(session.id(), request);
                                } else if (request.fenceToken()
                                        .equals(scopeFenceToken(expectedScope))) {
                                    interruptExpected(
                                            session.id(),
                                            expectedScope,
                                            expectedLease,
                                            request.reason());
                                }
                            }
                        });
        admittedLeaseRef.set(lease);
        ManagedExecutionScope executionScope = null;
        AtomicBoolean interrupted = new AtomicBoolean(false);
        try {
            synchronized (turnMutex(session.id())) {
                // Holding the new exclusive lease proves any ticket left by an older turn is
                // stale. Purge it before resolving/building the new Attempt so toolUseId may be
                // reused safely. Establish all local turn identity while holding the same mutex
                // used by fenced cancellation, closing the admission/abort TOCTOU window.
                interruptLocalLocked(session.id(), "new_turn_admitted");
                executionScope = controlPlaneClient.beginManagedExecution(session.id());
                admittedScopeRef.set(executionScope);
                activeTurnLeases.put(session.id(), lease);
                interruptedTurns.put(session.id(), interrupted);
            }
            ManagedExecutionScope admittedScope = executionScope;
            onAdmitted.run();
            sessionService.updateStatus(
                    session.ownerId(),
                    session.id(),
                    DataSessionService.STATUS_RUNNING,
                    null,
                    admittedScope);
            Schedulers.boundedElastic()
                    .schedule(
                            () -> {
                                if (interrupted.get()) {
                                    activeTurnLeases.remove(session.id(), lease);
                                    interruptedTurns.remove(session.id(), interrupted);
                                    controlPlaneClient.endManagedExecution(
                                            session.id(), admittedScope);
                                    lease.close();
                                    return;
                                }
                                Disposable heartbeat =
                                        Schedulers.boundedElastic()
                                                .schedulePeriodically(
                                                        () ->
                                                                heartbeatManagedExecution(
                                                                        session.id(),
                                                                        admittedScope,
                                                                        lease),
                                                        10,
                                                        10,
                                                        TimeUnit.SECONDS);
                                try {
                                    runTurn(session, inputMsgs, lease, interrupted, admittedScope);
                                } catch (CorePermissionConfirmationException ex) {
                                    log.warn(
                                            "Managed session reached an unresumable Core permission"
                                                    + " prompt: sessionId={}, error={}",
                                            session.id(),
                                            ex.getMessage());
                                    failTurn(
                                            session,
                                            ex,
                                            "core_permission_confirmation_unavailable",
                                            admittedScope,
                                            lease.coordinationId());
                                } catch (Exception ex) {
                                    log.warn(
                                            "Managed session turn failed: sessionId={}, error={}",
                                            session.id(),
                                            ex.getMessage());
                                    failTurn(
                                            session,
                                            ex,
                                            "turn_failed",
                                            admittedScope,
                                            lease.coordinationId());
                                } finally {
                                    heartbeat.dispose();
                                    activeTurnLeases.remove(session.id(), lease);
                                    interruptedTurns.remove(session.id(), interrupted);
                                    controlPlaneClient.endManagedExecution(
                                            session.id(), admittedScope);
                                    lease.close();
                                }
                            });
        } catch (RuntimeException ex) {
            activeTurnLeases.remove(session.id(), lease);
            interruptedTurns.remove(session.id(), interrupted);
            controlPlaneClient.endManagedExecution(session.id(), executionScope);
            lease.close();
            throw ex;
        }
    }

    void heartbeatManagedExecution(
            String sessionId,
            ManagedExecutionScope expectedScope,
            TurnLeaseService.TurnLease expectedLease) {
        try {
            controlPlaneClient.heartbeatManagedExecution(sessionId, expectedScope);
        } catch (RuntimeException ex) {
            if (isPermanentManagedHeartbeatFailure(ex)) {
                interruptExpected(
                        sessionId, expectedScope, expectedLease, "managed-heartbeat-stale");
                return;
            }
            log.warn(
                    "Managed execution heartbeat failed: sessionId={}, error={}",
                    sessionId,
                    ex.getMessage());
        }
    }

    /**
     * Handles a fenced abort locally or queues it for the replica holding the matching turn lease.
     * A stale request is intentionally idempotent and can never interrupt a newer scope.
     */
    public void abortManagedAttempt(
            String sessionId,
            String agentTaskId,
            String attemptId,
            long dispatchGeneration,
            String turnId,
            String reason) {
        ManagedExecutionScope requested =
                new ManagedExecutionScope(null, agentTaskId, attemptId, dispatchGeneration, turnId);
        ManagedExecutionScope current = controlPlaneClient.managedExecutionScope(sessionId);
        TurnLeaseService.TurnLease lease = activeTurnLeases.get(sessionId);
        if (sameFence(current, requested) && lease != null) {
            interruptExpected(
                    sessionId,
                    current,
                    lease,
                    reason != null && !reason.isBlank() ? reason : "managed_attempt_abort");
            return;
        }
        coordinationStore.requestFencedTurnInterrupt(
                sessionId,
                reason != null && !reason.isBlank() ? reason : "managed_attempt_abort",
                scopeFenceToken(requested));
    }

    private boolean interruptExpected(
            String sessionId,
            ManagedExecutionScope expectedScope,
            TurnLeaseService.TurnLease expectedLease,
            String source) {
        synchronized (turnMutex(sessionId)) {
            if (expectedScope == null
                    ? controlPlaneClient.managedExecutionScope(sessionId) != null
                    : !sameFence(
                            controlPlaneClient.managedExecutionScope(sessionId), expectedScope)) {
                return false;
            }
            if (expectedLease == null || activeTurnLeases.get(sessionId) != expectedLease) {
                return false;
            }
            return interruptLocalLocked(sessionId, source);
        }
    }

    private void requeueInterrupt(
            String sessionId, CoordinationStore.TurnInterruptRequest request) {
        if (request.fenceToken() != null) {
            coordinationStore.requestFencedTurnInterrupt(
                    sessionId, request.reason(), request.fenceToken());
        } else {
            coordinationStore.requestTurnInterrupt(sessionId, request.reason());
        }
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
        synchronized (turnMutex(sessionId)) {
            return interruptLocalLocked(sessionId, source);
        }
    }

    private boolean interruptLocalLocked(String sessionId, String source) {
        // Establish cancellation before releasing a blocked confirmation future. Otherwise the
        // middleware can observe false and advance the agent between future completion and stream
        // disposal.
        AtomicBoolean interrupted = interruptedTurns.get(sessionId);
        if (interrupted != null) {
            interrupted.set(true);
        }
        Disposable disposable = activeTurns.remove(sessionId);
        if (disposable != null) {
            disposable.dispose();
        }
        HarnessAgent agent = activeAgents.remove(sessionId);
        if (agent != null) {
            try {
                agent.interrupt();
            } catch (Exception ex) {
                log.warn("Harness interrupt failed for {}: {}", sessionId, ex.getMessage());
            }
        }
        confirmationCoordinator.cancelSession(sessionId, source);
        java.util.concurrent.CountDownLatch done = activeTurnDone.remove(sessionId);
        if (done != null) {
            done.countDown();
        }
        boolean active =
                interrupted != null
                        || disposable != null
                        || agent != null
                        || done != null
                        || activeTurnLeases.containsKey(sessionId);
        if (!active) {
            return false;
        }
        eventLog.append(
                sessionId,
                SessionEventTypes.SESSION_INTERRUPTED,
                Map.of("status", "interrupted", "source", source));
        TurnLeaseService.TurnLease lease = activeTurnLeases.remove(sessionId);
        if (lease != null) {
            handsLeaseService.release(sessionId, lease.coordinationId());
            lease.close();
        }
        return true;
    }

    private Object turnMutex(String sessionId) {
        return turnMutexes.computeIfAbsent(sessionId, ignored -> new Object());
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
            ManagedSessionDto session,
            List<Msg> inputMsgs,
            TurnLeaseService.TurnLease lease,
            AtomicBoolean interrupted,
            ManagedExecutionScope executionScope) {
        if (interrupted.get()) {
            return;
        }
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
        if (interrupted.get()) {
            return;
        }

        RuntimeContext.Builder rcBuilder =
                RuntimeContext.builder()
                        .userId(session.ownerId())
                        .sessionId(session.id())
                        .put(
                                ManagedSessionIdentity.class,
                                new ManagedSessionIdentity(session.id()));
        if (executionScope != null) {
            rcBuilder.put(
                    ManagedTurnContext.class,
                    new ManagedTurnContext(executionScope, lease.coordinationId()));
        }
        Optional<Sandbox> handsSandbox =
                handsLeaseService.acquire(session, environment, lease.coordinationId());
        handsSandbox.ifPresent(
                sandbox ->
                        rcBuilder.put(
                                SandboxContext.class,
                                SandboxContext.builder()
                                        .externalSandbox(sandbox)
                                        .isolationScope(IsolationScope.SESSION)
                                        .build()));
        RuntimeContext rc = rcBuilder.build();

        SessionEventMapper.PreviewIds previewIds = new SessionEventMapper.PreviewIds();
        Set<String> startedPreviews = ConcurrentHashMap.newKeySet();
        AtomicBoolean suspended = new AtomicBoolean(false);
        AtomicBoolean corePermissionAsking = new AtomicBoolean(false);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> errorRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        BaseSubscriber<AgentEvent> subscription =
                new BaseSubscriber<>() {
                    @Override
                    protected void hookOnSubscribe(Subscription subscription) {
                        requestUnbounded();
                    }

                    @Override
                    protected void hookOnNext(AgentEvent event) {
                        if (isCorePermissionAsking(event)) {
                            corePermissionAsking.set(true);
                        }
                        if (handleAgentEvent(session.id(), event, executionScope)) {
                            suspended.set(true);
                        }
                    }

                    @Override
                    protected void hookOnError(Throwable error) {
                        errorRef.set(error);
                        done.countDown();
                    }

                    @Override
                    protected void hookOnComplete() {
                        done.countDown();
                    }
                };
        synchronized (turnMutex(session.id())) {
            // A stale turn may finish agent construction after a replacement was admitted. Never
            // let its late registrations overwrite the replacement's cancellation handles.
            if (interrupted.get()
                    || activeTurnLeases.get(session.id()) != lease
                    || interruptedTurns.get(session.id()) != interrupted) {
                handsLeaseService.release(session.id(), lease.coordinationId());
                return;
            }
            previewIdsBySession.put(session.id(), previewIds);
            startedPreviewTypes.put(session.id(), startedPreviews);
            activeAgents.put(session.id(), agent);
            activeTurnDone.put(session.id(), done);
            activeTurns.put(session.id(), subscription);
        }
        // The subscriber itself was registered under the turn mutex. If cancellation won before
        // this call, BaseSubscriber cancels immediately from onSubscribe and no middleware/tool
        // demand is issued. subscribe() is intentionally outside the mutex because always_ask may
        // synchronously block until a human decision.
        try {
            agent.streamEvents(inputMsgs, rc).subscribe(subscription);
        } catch (RuntimeException ex) {
            activeTurns.remove(session.id(), subscription);
            activeAgents.remove(session.id(), agent);
            activeTurnDone.remove(session.id(), done);
            persistRemainingThinking(session.id(), previewIds, executionScope);
            previewIdsBySession.remove(session.id(), previewIds);
            startedPreviewTypes.remove(session.id(), startedPreviews);
            subscription.dispose();
            handsLeaseService.release(session.id(), lease.coordinationId());
            throw ex;
        }
        if (interrupted.get()) {
            subscription.dispose();
            done.countDown();
        }
        try {
            done.await();
            if (interrupted.get()) {
                return;
            }
            Throwable error = errorRef.get();
            if (error != null) {
                if (error instanceof RuntimeException re) {
                    throw re;
                }
                throw new RuntimeException(error);
            }
            if (corePermissionAsking.get()) {
                throw new CorePermissionConfirmationException(
                        "Core PermissionEngine returned PERMISSION_ASKING without a durable service"
                            + " HITL ticket; configure the tool with permissionPolicy=always_ask to"
                            + " use resumable AgentScope Service approval");
            }
            if (suspended.get()) {
                sessionService.updateStatus(
                        session.ownerId(),
                        session.id(),
                        DataSessionService.STATUS_REQUIRES_ACTION,
                        Map.of("reason", "tool_suspended"),
                        executionScope);
                appendTurnEvent(
                        session.id(),
                        SessionEventTypes.SESSION_REQUIRES_ACTION,
                        Map.of("reason", "tool_suspended"),
                        null,
                        executionScope);
            } else {
                sessionService.updateStatus(
                        session.ownerId(),
                        session.id(),
                        DataSessionService.STATUS_IDLE,
                        null,
                        executionScope);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            subscription.dispose();
            failTurn(session, ie, "interrupted", executionScope, lease.coordinationId());
        } finally {
            activeTurns.remove(session.id(), subscription);
            activeAgents.remove(session.id(), agent);
            activeTurnDone.remove(session.id(), done);
            persistRemainingThinking(session.id(), previewIds, executionScope);
            previewIdsBySession.remove(session.id(), previewIds);
            startedPreviewTypes.remove(session.id(), startedPreviews);
            // Keep work-queue lease for suspended turns so workers can finish pending tools.
            if (!suspended.get()) {
                handsLeaseService.release(session.id(), lease.coordinationId());
            }
        }
    }

    private void failTurn(
            ManagedSessionDto session,
            Throwable error,
            String code,
            ManagedExecutionScope executionScope,
            String handsOwnerId) {
        var mcpFailure =
                error instanceof io.agentscope.harness.agent.tools.McpConnectionException
                        ? (io.agentscope.harness.agent.tools.McpConnectionException) error
                        : null;
        if (mcpFailure != null) code = "mcp_connection_failed_error";
        ApiErrorDetail detail =
                ApiErrorDetail.of(
                                ApiErrorType.API,
                                code,
                                error.getMessage() != null ? error.getMessage() : code)
                        .withSessionId(session.id())
                        .withRetryStatus(mcpFailure == null ? "not_retrying" : "next_turn");
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, Object> errorDetails = detail.toMap();
        if (mcpFailure != null) errorDetails.put("mcp_server_name", mcpFailure.getServerName());
        payload.put("error", errorDetails);
        appendTurnEvent(
                session.id(), SessionEventTypes.SESSION_ERROR, payload, null, executionScope);
        Map<String, Object> stopReason = new LinkedHashMap<>();
        stopReason.put("error", detail.toMap());
        sessionService.updateStatus(
                session.ownerId(),
                session.id(),
                mcpFailure == null
                        ? DataSessionService.STATUS_TERMINATED
                        : DataSessionService.STATUS_IDLE,
                stopReason,
                executionScope);
        handsLeaseService.release(session.id(), handsOwnerId);
    }

    /**
     * @return {@code true} when the event indicates {@link GenerateReason#TOOL_SUSPENDED}
     */
    private boolean handleAgentEvent(
            String sessionId, AgentEvent event, ManagedExecutionScope executionScope) {
        SessionEventMapper.PreviewIds ids =
                previewIdsBySession.computeIfAbsent(
                        sessionId, ignored -> new SessionEventMapper.PreviewIds());
        SessionEventMapper.MappingResult mapped = eventMapper.map(event, ids);
        mapped.preceding()
                .forEach(
                        persisted ->
                                appendTurnEvent(
                                        sessionId,
                                        persisted.type(),
                                        persisted.payload(),
                                        persisted.eventId(),
                                        executionScope));
        if (event instanceof AgentResultEvent result
                && result.getResult() != null
                && result.getResult().getGenerateReason() == GenerateReason.TOOL_SUSPENDED) {
            persistSuspendedToolUses(sessionId, result.getResult(), executionScope);
            return true;
        }

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
                                appendTurnEvent(
                                        sessionId,
                                        persisted.type(),
                                        persisted.payload(),
                                        persisted.eventId(),
                                        executionScope));
        return false;
    }

    private void persistRemainingThinking(
            String sessionId, SessionEventMapper.PreviewIds ids, ManagedExecutionScope scope) {
        try {
            ids.consumeThinking()
                    .ifPresent(
                            event ->
                                    appendTurnEvent(
                                            sessionId,
                                            event.type(),
                                            event.payload(),
                                            event.eventId(),
                                            scope));
        } catch (RuntimeException error) {
            log.warn("Could not persist remaining thinking for session {}", sessionId, error);
        }
    }

    static boolean isCorePermissionAsking(AgentEvent event) {
        return event instanceof AgentResultEvent result
                && result.getResult() != null
                && result.getResult().getGenerateReason() == GenerateReason.PERMISSION_ASKING;
    }

    private static final class CorePermissionConfirmationException extends RuntimeException {

        private CorePermissionConfirmationException(String message) {
            super(message);
        }
    }

    private void persistSuspendedToolUses(
            String sessionId, Msg result, ManagedExecutionScope executionScope) {
        for (ToolUseBlock tub : result.getContentBlocks(ToolUseBlock.class)) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", tub.getId());
            payload.put("name", tub.getName());
            payload.put("input", tub.getInput() != null ? tub.getInput() : Map.of());
            payload.put("toolCallId", tub.getId());
            payload.put("toolName", tub.getName());
            payload.put("state", "pending");
            appendTurnEvent(
                    sessionId, SessionEventTypes.AGENT_TOOL_USE, payload, null, executionScope);
        }
    }

    private SessionEventDto appendTurnEvent(
            String sessionId,
            String type,
            Map<String, Object> payload,
            String eventId,
            ManagedExecutionScope executionScope) {
        if (executionScope == null) {
            return eventLog.append(sessionId, type, payload, eventId);
        }
        SessionEventDto event = eventLog.appendLocal(sessionId, type, payload, eventId);
        try {
            controlPlaneClient.appendSessionEvent(event, executionScope);
        } catch (RuntimeException ex) {
            log.warn(
                    "Scoped session event mirror failed: sessionId={}, eventId={}, error={}",
                    sessionId,
                    event.id(),
                    ex.getMessage());
        }
        return event;
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

    private static boolean isPermanentManagedHeartbeatFailure(Throwable error) {
        HttpStatusCode status = null;
        if (error instanceof WebClientResponseException response) {
            status = response.getStatusCode();
        } else if (error instanceof ResponseStatusException response) {
            status = response.getStatusCode();
        }
        return status != null
                && (status.value() == 404 || status.value() == 409 || status.value() == 410);
    }

    private static boolean sameFence(
            ManagedExecutionScope current, ManagedExecutionScope expected) {
        return current != null
                && expected != null
                && same(current.agentTaskId(), expected.agentTaskId())
                && same(current.attemptId(), expected.attemptId())
                && current.dispatchGeneration() == expected.dispatchGeneration()
                && same(current.turnId(), expected.turnId());
    }

    private static String scopeFenceToken(ManagedExecutionScope scope) {
        String value =
                String.valueOf(scope.agentTaskId())
                        + "\u0000"
                        + scope.attemptId()
                        + "\u0000"
                        + scope.dispatchGeneration()
                        + "\u0000"
                        + scope.turnId();
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }
}
