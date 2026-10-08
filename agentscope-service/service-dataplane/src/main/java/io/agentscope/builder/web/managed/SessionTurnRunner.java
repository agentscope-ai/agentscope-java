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
import io.agentscope.builder.web.catalog.JevServiceSupport;
import io.agentscope.builder.web.coord.CoordinationStore;
import io.agentscope.builder.web.coord.TurnLeaseService;
import io.agentscope.builder.web.managed.service.DeletedSessionRegistry;
import io.agentscope.builder.web.managed.service.SessionEventLog;
import io.agentscope.builder.web.toolbus.ToolConfirmationCoordinator;
import io.agentscope.core.agent.AgentRun;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.session.SessionExecution;
import io.agentscope.core.session.SessionExportSink;
import io.agentscope.core.session.SessionInbox;
import io.agentscope.core.session.SessionInteractions;
import io.agentscope.core.session.SessionModelPolicy;
import io.agentscope.core.session.SessionRecorder;
import io.agentscope.core.session.SessionTurns;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.middleware.TeamsMiddleware;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.tools.McpConnectionException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final SessionEventPreviewBus previewBus;
    private final DataEnvironmentService environmentService;
    private final HandsLeaseService handsLeaseService;
    private final TurnLeaseService turnLeaseService;
    private final CoordinationStore coordinationStore;
    private final DeletedSessionRegistry deletedSessions;
    private final ControlPlaneClient controlPlaneClient;
    private final ToolConfirmationCoordinator confirmationCoordinator;
    private final AgentRunRegistry runRegistry;
    private final ConcurrentHashMap<String, AgentRun<AgentEvent>> activeTurns =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TurnLeaseService.TurnLease> activeTurnLeases =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CountDownLatch> activeTurnDone =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> interruptedTurns =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> turnMutexes = new ConcurrentHashMap<>();

    public SessionTurnRunner(
            HarnessAgentBuildService agentBuildService,
            @Lazy DataSessionService sessionService,
            SessionEventLog eventLog,
            SessionEventPreviewBus previewBus,
            DataEnvironmentService environmentService,
            HandsLeaseService handsLeaseService,
            TurnLeaseService turnLeaseService,
            CoordinationStore coordinationStore,
            DeletedSessionRegistry deletedSessions,
            ControlPlaneClient controlPlaneClient,
            @Lazy ToolConfirmationCoordinator confirmationCoordinator,
            AgentRunRegistry runRegistry) {
        this.agentBuildService = agentBuildService;
        this.sessionService = sessionService;
        this.eventLog = eventLog;
        this.previewBus = previewBus;
        this.environmentService = environmentService;
        this.handsLeaseService = handsLeaseService;
        this.turnLeaseService = turnLeaseService;
        this.coordinationStore = coordinationStore;
        this.deletedSessions = deletedSessions;
        this.controlPlaneClient = controlPlaneClient;
        this.confirmationCoordinator = confirmationCoordinator;
        this.runRegistry = runRegistry;
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

    private SessionNativeLogService nativeLogs;

    @Autowired private SessionBudgetService budgets;

    @Autowired
    public void setNativeLogs(SessionNativeLogService nativeLogs) {
        this.nativeLogs = nativeLogs;
    }

    private final ConcurrentHashMap<String, String> publicTurns = new ConcurrentHashMap<>();

    public void cancelDurableTurn(String session, String turnId) {
        synchronized (turnMutex(session)) {
            if (turnId.equals(publicTurns.get(session)))
                interruptLocalLocked(session, "public_turn_cancelled");
        }
    }

    private record DurableTurn(
            String turnId,
            BiConsumer<String, Throwable> finished,
            AtomicReference<String> outcome) {}

    public void runDurableTurnAsync(
            ManagedSessionDto session,
            String message,
            String turnId,
            BiConsumer<String, Throwable> finished) {
        runDurableTurnAsync(
                session,
                List.of(UserMessage.builder().id("command_" + turnId).textContent(message).build()),
                turnId,
                finished);
    }

    public void runDurableTurnAsync(
            ManagedSessionDto session,
            List<Msg> inputs,
            String turnId,
            BiConsumer<String, Throwable> finished) {
        runTurnAsync(
                session,
                inputs,
                () -> {},
                new DurableTurn(turnId, finished, new AtomicReference<>("completed")));
    }

    public void resumeDurableTurnAsync(
            ManagedSessionDto session,
            String commandId,
            String json,
            String turnId,
            BiConsumer<String, Throwable> finished) {
        var answers = JsonUtils.getJsonCodec().fromJson(json, List.class);
        var confirmations = new ArrayList<ConfirmResult>();
        var results = new ArrayList<ToolResultBlock>();
        for (Object raw : answers) {
            var answer = (Map<?, ?>) raw;
            var call =
                    JsonUtils.getJsonCodec().convertValue(answer.get("call"), ToolUseBlock.class);
            if ("confirmation".equals(answer.get("kind")))
                confirmations.add(
                        new ConfirmResult(
                                Boolean.TRUE.equals(answer.get("allow")),
                                call,
                                null,
                                (String) answer.get("reason")));
            else
                results.add(
                        ToolResultBlock.of(
                                call.getId(),
                                call.getName(),
                                TextBlock.builder().text((String) answer.get("output")).build(),
                                Map.of("error", Boolean.TRUE.equals(answer.get("is_error")))));
        }
        var inputs = new ArrayList<Msg>();
        if (!confirmations.isEmpty())
            inputs.add(
                    UserMessage.builder()
                            .id("confirmation_" + commandId)
                            .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, confirmations))
                            .build());
        if (!results.isEmpty()) {
            var builder = ToolResultMessage.builder().id("result_" + commandId);
            for (var result : results) builder.result(result);
            inputs.add(builder.build());
        }
        runTurnAsync(
                session,
                inputs,
                () -> {},
                new DurableTurn(turnId, finished, new AtomicReference<>("completed")));
    }

    private void runTurnAsync(ManagedSessionDto session, List<Msg> inputMsgs, Runnable onAdmitted) {
        runTurnAsync(session, inputMsgs, onAdmitted, null);
    }

    private void runTurnAsync(
            ManagedSessionDto session,
            List<Msg> inputMsgs,
            Runnable onAdmitted,
            DurableTurn command) {
        // A wakeup can race teardown: the control plane may have deleted the session
        // between the wake being queued and this turn starting.
        if (deletedSessions.isDeleted(session.id())) {
            if (command != null)
                command.finished()
                        .accept("cancelled", new IllegalStateException("Session deleted"));
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
                                if (request.fenceToken() != null
                                        && request.fenceToken().startsWith("run:")) {
                                    AgentRun<AgentEvent> run = activeTurns.get(session.id());
                                    if (run != null
                                            && request.fenceToken().equals("run:" + run.runId())
                                            && runRegistry
                                                    .find(
                                                            session.ownerId(),
                                                            session.id(),
                                                            run.runId())
                                                    .isPresent()) {
                                        if (activeTurnLeases.get(session.id()) == expectedLease) {
                                            interruptLocalLocked(session.id(), request.reason());
                                        } else {
                                            // An old heartbeat can race lease replacement. Preserve
                                            // a valid request for the replacement's own heartbeat.
                                            requeueInterrupt(session.id(), request);
                                        }
                                    }
                                    // A stale run ticket never falls back to session interruption.
                                } else if (request.fenceToken() == null) {
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
            if (command != null) publicTurns.put(session.id(), command.turnId());
            ManagedExecutionScope admittedScope = executionScope;
            // The event outbox is asynchronous. Its running event cannot serve as a
            // barrier for a fast task.complete or child-delegation tool call.
            controlPlaneClient.startManagedExecution(session.id(), admittedScope);
            if (nativeLogs != null)
                nativeLogs.beginAdmission(session, lease.coordinationId(), admittedScope);
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
                                    finishMirrorAdmission(lease.coordinationId());
                                    if (command != null)
                                        publicTurns.remove(session.id(), command.turnId());
                                    if (command != null)
                                        command.finished().accept("cancelled", null);
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
                                Throwable commandFailure = null;
                                try {
                                    runTurn(
                                            session,
                                            inputMsgs,
                                            lease,
                                            interrupted,
                                            admittedScope,
                                            command);
                                } catch (CorePermissionConfirmationException ex) {
                                    commandFailure = ex;
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
                                    commandFailure = ex;
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
                                    finishMirrorAdmission(lease.coordinationId());
                                    if (command != null)
                                        publicTurns.remove(session.id(), command.turnId());
                                    if (command != null)
                                        command.finished()
                                                .accept(
                                                        commandFailure != null
                                                                ? "failed"
                                                                : interrupted.get()
                                                                        ? "cancelled"
                                                                        : command.outcome().get(),
                                                        commandFailure);
                                }
                            });
        } catch (RuntimeException ex) {
            if (command != null) publicTurns.remove(session.id(), command.turnId());
            activeTurnLeases.remove(session.id(), lease);
            interruptedTurns.remove(session.id(), interrupted);
            controlPlaneClient.endManagedExecution(session.id(), executionScope);
            lease.close();
            finishMirrorAdmission(lease.coordinationId());
            throw ex;
        }
    }

    private void finishMirrorAdmission(String admission) {
        if (nativeLogs == null) return;
        try {
            nativeLogs.finishAdmission(admission);
        } catch (RuntimeException error) {
            log.warn("Mirror admission close pending lease expiry: {}", admission, error);
        }
    }

    void heartbeatManagedExecution(
            String sessionId,
            ManagedExecutionScope expectedScope,
            TurnLeaseService.TurnLease expectedLease) {
        try {
            if (nativeLogs != null) nativeLogs.renewAdmission(expectedLease.coordinationId());
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
     * Cancels an exact run after the caller has authorized access to the session. The registry
     * additionally checks local ownership. Remote tickets carry the runId as a fence, never a
     * session-wide fallback, so a late request cannot cancel the next turn.
     */
    public void interruptRun(String ownerId, String sessionId, String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId is required");
        }
        synchronized (turnMutex(sessionId)) {
            AgentRun<AgentEvent> current = activeTurns.get(sessionId);
            if (current != null) {
                if (current.runId().equals(runId)
                        && runRegistry.find(ownerId, sessionId, runId).isPresent()) {
                    interruptLocalLocked(sessionId, "run.interrupt");
                }
                return;
            }
        }
        coordinationStore.requestFencedTurnInterrupt(sessionId, "run.interrupt", "run:" + runId);
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
        ManagedExecutionScope interruptedScope =
                controlPlaneClient.managedExecutionScope(sessionId);
        // Establish cancellation before releasing a blocked confirmation future. Otherwise the
        // middleware can observe false and advance the agent between future completion and stream
        // disposal.
        AtomicBoolean interrupted = interruptedTurns.get(sessionId);
        if (interrupted != null) {
            interrupted.set(true);
        }
        AgentRun<AgentEvent> run = activeTurns.remove(sessionId);
        if (run != null) {
            run.cancel();
        }
        confirmationCoordinator.cancelSession(sessionId, source);
        CountDownLatch done = activeTurnDone.remove(sessionId);
        if (done != null) {
            done.countDown();
        }
        boolean active =
                interrupted != null
                        || run != null
                        || done != null
                        || activeTurnLeases.containsKey(sessionId);
        if (!active) {
            return false;
        }
        appendTurnEvent(
                sessionId,
                SessionEventTypes.SESSION_INTERRUPTED,
                Map.of("status", "interrupted", "source", source),
                null,
                interruptedScope);
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
    }

    private void runTurn(
            ManagedSessionDto session,
            List<Msg> inputMsgs,
            TurnLeaseService.TurnLease lease,
            AtomicBoolean interrupted,
            ManagedExecutionScope executionScope,
            DurableTurn command) {
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
        String turnId = command == null ? resolveTurnId(session, inputMsgs) : command.turnId();
        rcBuilder.put(SessionRecorder.TURN_ID_KEY, turnId);
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
        var jevRunId = new AtomicReference<String>();
        rcBuilder.put(
                JevServiceSupport.TraceSink.class,
                new JevServiceSupport.TraceSink(
                        record -> {
                            String runId = jevRunId.get();
                            if (runId == null) return;
                            appendTurnEvent(
                                    session.id(),
                                    "jev.decision",
                                    Map.of(
                                            "run_id",
                                            runId,
                                            "purpose",
                                            record.purpose(),
                                            "version",
                                            record.version(),
                                            "mode",
                                            record.mode().name(),
                                            "status",
                                            record.status().name(),
                                            "reason",
                                            record.reason(),
                                            "elapsed_ms",
                                            record.elapsed().toMillis(),
                                            "recommendation",
                                            record.recommendation()),
                                    null,
                                    executionScope);
                        }));
        if (nativeLogs != null) {
            nativeLogs.register(session);
            rcBuilder.put(SessionExportSink.CONTEXT_KEY, nativeLogs.sink(session.id()));
            if (command != null) {
                var log = nativeLogs.open(session);
                var latest = SessionTurns.read(log).latest();
                boolean answering =
                        inputMsgs.stream()
                                .anyMatch(
                                        message ->
                                                message.getMetadata()
                                                                .containsKey(
                                                                        Msg
                                                                                .METADATA_CONFIRM_RESULTS)
                                                        || message.hasContentBlocks(
                                                                ToolResultBlock.class));
                String kind =
                        latest != null && turnId.equals(latest.turnId()) && !latest.ended()
                                ? answering ? "respond" : "resume"
                                : "submit";
                var execution =
                        new SessionInbox.Command(
                                UUID.randomUUID().toString(), kind, turnId, inputMsgs, List.of());
                rcBuilder.put(SessionExecution.CONTEXT_KEY, new SessionExecution(log, execution));
            }
        }
        if (budgets != null) rcBuilder.put(SessionModelPolicy.CONTEXT_KEY, budgets.policy(session));
        RuntimeContext rc = rcBuilder.build();

        AtomicBoolean suspended = new AtomicBoolean(false);
        AtomicReference<GenerateReason> resultReason = new AtomicReference<>();
        AtomicBoolean corePermissionAsking = new AtomicBoolean(false);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
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
                        if (event instanceof AgentResultEvent result
                                && result.getResult() != null
                                && (event.getSource() == null || event.getSource().isBlank())) {
                            resultReason.set(result.getResult().getGenerateReason());
                            suspended.set(
                                    "suspended"
                                            .equals(SessionRecorder.outcome(resultReason.get())));
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
        AgentRun<AgentEvent> run = agent.prepareRun(inputMsgs, rc);
        jevRunId.set(run.runId());
        if (nativeLogs != null) nativeLogs.bindRun(session, lease.coordinationId(), run.runId());
        synchronized (turnMutex(session.id())) {
            // A stale turn may finish agent construction after a replacement was admitted. Never
            // let its late registrations overwrite the replacement's cancellation handles.
            if (interrupted.get()
                    || activeTurnLeases.get(session.id()) != lease
                    || interruptedTurns.get(session.id()) != interrupted) {
                handsLeaseService.release(session.id(), lease.coordinationId());
                return;
            }
            activeTurnDone.put(session.id(), done);
            runRegistry.register(session.ownerId(), session.id(), run);
            activeTurns.put(session.id(), run);
        }
        // Register the handle before subscribing, so cancellation during setup is not lost.
        // Subscribe outside the mutex: confirmation middleware can block awaiting a decision.
        try {
            appendTurnEvent(
                    session.id(),
                    SessionEventTypes.SESSION_RUN_STARTED,
                    executionCorrelation(run.runId(), turnId, executionScope),
                    null,
                    executionScope);
            run.stream().subscribe(subscription);
        } catch (RuntimeException ex) {
            activeTurns.remove(session.id(), run);
            activeTurnDone.remove(session.id(), done);
            run.cancel();
            subscription.dispose();
            handsLeaseService.release(session.id(), lease.coordinationId());
            throw ex;
        }
        if (interrupted.get()) {
            run.cancel();
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
            // Native export is asynchronous during generation. Flush committed output before
            // status_idle so the control-plane Endpoint collector sees the final answer first.
            if (nativeLogs != null) nativeLogs.refresh(session.id());
            String outcome = SessionRecorder.outcome(resultReason.get());
            if (command != null) command.outcome().set(outcome);
            if ("failed".equals(outcome))
                throw new IllegalStateException(
                        "Agent turn ended unsuccessfully: " + resultReason.get());
            if (corePermissionAsking.get() && command == null) {
                throw new CorePermissionConfirmationException(
                        "Core PermissionEngine returned PERMISSION_ASKING without a durable service"
                            + " HITL ticket; configure the tool with permissionPolicy=always_ask to"
                            + " use resumable AgentScope Service approval");
            }
            if (suspended.get() || corePermissionAsking.get()) {
                if (command != null) command.outcome().set("requires_action");
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
            if (command != null) command.outcome().set("interrupted");
            run.cancel();
            subscription.dispose();
            failTurn(session, ie, "interrupted", executionScope, lease.coordinationId());
        } finally {
            activeTurns.remove(session.id(), run);
            activeTurnDone.remove(session.id(), done);
            // Keep work-queue lease for suspended turns so workers can finish pending tools.
            if (!suspended.get()) {
                handsLeaseService.release(session.id(), lease.coordinationId());
            }
        }
    }

    private String resolveTurnId(ManagedSessionDto session, List<Msg> inputs) {
        var ids = new HashSet<String>();
        for (Msg input : inputs)
            for (var block : input.getContent())
                if (block instanceof ToolResultBlock result) ids.add(result.getId());
        if (ids.isEmpty()) return UUID.randomUUID().toString();
        if (nativeLogs == null)
            throw new IllegalStateException("Native session log is required for tool continuation");
        return SessionInteractions.continuationTurn(nativeLogs.open(session), ids);
    }

    static Map<String, Object> executionCorrelation(
            String runId, String turnId, ManagedExecutionScope scope) {
        var data = new LinkedHashMap<String, Object>();
        data.put("run_id", runId);
        data.put("turn_id", turnId);
        if (scope != null) data.put("coordination", scope.correlation());
        return data;
    }

    private void failTurn(
            ManagedSessionDto session,
            Throwable error,
            String code,
            ManagedExecutionScope executionScope,
            String handsOwnerId) {
        var mcpFailure =
                error instanceof McpConnectionException ? (McpConnectionException) error : null;
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

    static boolean isCorePermissionAsking(AgentEvent event) {
        return (event.getSource() == null || event.getSource().isBlank())
                && event instanceof AgentResultEvent result
                && result.getResult() != null
                && result.getResult().getGenerateReason() == GenerateReason.PERMISSION_ASKING;
    }

    private static final class CorePermissionConfirmationException extends RuntimeException {

        private CorePermissionConfirmationException(String message) {
            super(message);
        }
    }

    private SessionEventDto appendTurnEvent(
            String sessionId,
            String type,
            Map<String, Object> payload,
            String eventId,
            ManagedExecutionScope executionScope) {
        return eventLog.appendScoped(
                sessionId, type, payload, eventId, ControlPlaneClient.eventScope(executionScope));
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
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }
}
