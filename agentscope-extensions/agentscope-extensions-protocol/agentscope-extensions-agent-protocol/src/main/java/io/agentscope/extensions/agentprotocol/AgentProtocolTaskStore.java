/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.agentprotocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.formatter.DocumentReferenceRenderer;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.protocol.RemoteAgentEvent;
import io.agentscope.harness.agent.subagent.protocol.RemoteConfirmDecision;
import io.agentscope.harness.agent.subagent.protocol.RemoteEventCodec;
import io.agentscope.harness.agent.subagent.protocol.RemoteEventType;
import io.agentscope.harness.agent.subagent.protocol.RemotePendingConfirm;
import io.agentscope.harness.agent.subagent.task.TaskRecord;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Runs Agent Protocol tasks and persists their control-plane {@link TaskRecord}.
 *
 * <p>Each run resolves its {@link HarnessAgent} through the {@link AgentFactory}, which receives the
 * submission context and can therefore route per task. For concurrent task execution, return a new
 * instance per call (e.g. a Spring prototype-scoped bean); a shared instance requires the agent to be
 * configured with {@code checkRunning(false)}.
 *
 * <p>Task metadata is stored via {@link ProtocolTaskRepository} (a dedicated control-plane path), not
 * via any execution agent's workspace. Caller-supplied {@code context.attributes} reach the run
 * through its {@link RuntimeContext}; see {@link RuntimeContextCustomizer} for controlling how.
 *
 * <p>A task owns its session unless its submission names a {@code session_id}, in which case it
 * runs in that conversation instead — the mechanism behind a multi-turn exchange with one remote
 * agent, as opposed to dispatching unrelated tasks to it. Several tasks may share one session, so a
 * session admits one running turn at a time.
 *
 * <p>Everything that must be visible to another instance goes through a collaborator rather than
 * this class's own maps:
 *
 * <ul>
 *   <li>{@link ProtocolTaskRepository} — task state and results;
 *   <li>{@link AgentProtocolEventBus} — the SSE stream;
 *   <li>{@link ProtocolTaskRunRegistry} — execution ownership and cancellation;
 *   <li>{@link ProtocolTaskContextStore} — submission metadata that {@link #resume} re-reads.
 * </ul>
 *
 * <p>The registry and store defaults are in-process, which matches a single-instance deployment.
 * Submitting, resuming, cancelling and waiting may each be served by a different instance as soon as
 * all four are shared. Resuming on another instance additionally requires the agent's session state
 * to be reachable there, i.e. a shared workspace or a persistent store. The same holds for a
 * continuation: the session it reuses is read from the task record, so the repository must be shared
 * for another instance to resolve it.
 */
public final class AgentProtocolTaskStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentProtocolTaskStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Sentinel returned when the agent paused for HITL confirmation. */
    static final String AWAITING_CONFIRM_SENTINEL = "__awaiting_confirm__";

    /**
     * Error reported for a task settled by a reader because it had no live run lease. Clients treat
     * it as any other failed task; the text tells operators what happened.
     */
    static final String OWNER_LOST_ERROR =
            "owner_lost: run lease expired and no instance took the task over";

    private final AgentFactory agentFactory;
    private final ProtocolTaskRepository taskRepository;
    private final AgentProtocolEventBus eventBus;
    private final AgentProtocolProperties properties;
    private final List<RuntimeContextCustomizer> runtimeContextCustomizers;
    private final ProtocolTaskRunRegistry runRegistry;
    private final ProtocolTaskContextStore contextStore;
    private final ProtocolSessionRegistry sessionRegistry;
    private final Duration leaseTtl;
    private final Duration heartbeatInterval;
    private final long cancelCheckIntervalMs;
    private final Duration confirmTimeout;
    private final long resumeLeaseWaitMs;

    private static final long RESUME_LEASE_POLL_MS = 10L;
    private final ExecutorService executor =
            Executors.newCachedThreadPool(
                    r -> {
                        Thread t = new Thread(r);
                        t.setDaemon(true);
                        t.setName("agent-protocol-task-" + t.getId());
                        return t;
                    });

    /**
     * Renews the leases of all live runs. More than one thread because a shared registry answers
     * {@code renew} over the network: a single slow renewal must not delay the heartbeat of every
     * other task, which would let their leases expire and be taken over while still running.
     */
    private final ScheduledExecutorService leaseHeartbeat =
            Executors.newScheduledThreadPool(
                    2,
                    r -> {
                        Thread t = new Thread(r);
                        t.setDaemon(true);
                        t.setName("agent-protocol-lease-heartbeat");
                        return t;
                    });

    public AgentProtocolTaskStore(
            AgentFactory agentFactory,
            ProtocolTaskRepository taskRepository,
            AgentProtocolEventBus eventBus,
            AgentProtocolProperties properties) {
        this(agentFactory, taskRepository, eventBus, properties, List.of());
    }

    public AgentProtocolTaskStore(
            AgentFactory agentFactory,
            ProtocolTaskRepository taskRepository,
            AgentProtocolEventBus eventBus,
            AgentProtocolProperties properties,
            List<RuntimeContextCustomizer> runtimeContextCustomizers) {
        this(
                agentFactory,
                taskRepository,
                eventBus,
                properties,
                runtimeContextCustomizers,
                BaseStoreProtocolTaskRunRegistry.inMemory(),
                BaseStoreProtocolTaskContextStore.inMemory(),
                BaseStoreProtocolSessionRegistry.inMemory());
    }

    /** Keeps the process-local session registry, i.e. a single-instance deployment. */
    public AgentProtocolTaskStore(
            AgentFactory agentFactory,
            ProtocolTaskRepository taskRepository,
            AgentProtocolEventBus eventBus,
            AgentProtocolProperties properties,
            List<RuntimeContextCustomizer> runtimeContextCustomizers,
            ProtocolTaskRunRegistry runRegistry,
            ProtocolTaskContextStore contextStore) {
        this(
                agentFactory,
                taskRepository,
                eventBus,
                properties,
                runtimeContextCustomizers,
                runRegistry,
                contextStore,
                BaseStoreProtocolSessionRegistry.inMemory());
    }

    /**
     * @param runRegistry execution ownership and cancellation; must be shared across instances to
     *     coordinate {@code submit}, {@code resume} and {@code cancel}
     * @param contextStore submission metadata re-read on {@code resume}; must be shared for a
     *     {@code resume} served elsewhere to keep {@code detail} and {@code user_id}
     * @param sessionRegistry ownership record for client-named sessions; must be shared for a
     *     session opened on one instance to be verifiable on another
     */
    public AgentProtocolTaskStore(
            AgentFactory agentFactory,
            ProtocolTaskRepository taskRepository,
            AgentProtocolEventBus eventBus,
            AgentProtocolProperties properties,
            List<RuntimeContextCustomizer> runtimeContextCustomizers,
            ProtocolTaskRunRegistry runRegistry,
            ProtocolTaskContextStore contextStore,
            ProtocolSessionRegistry sessionRegistry) {
        this.agentFactory = Objects.requireNonNull(agentFactory, "agentFactory");
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository");
        this.eventBus = eventBus != null ? eventBus : new AgentProtocolTaskEventBus();
        this.properties = properties != null ? properties : new AgentProtocolProperties();
        this.runtimeContextCustomizers =
                runtimeContextCustomizers == null
                        ? List.of()
                        : List.copyOf(runtimeContextCustomizers);
        this.runRegistry = Objects.requireNonNull(runRegistry, "runRegistry");
        this.contextStore = Objects.requireNonNull(contextStore, "contextStore");
        this.sessionRegistry =
                sessionRegistry != null
                        ? sessionRegistry
                        : BaseStoreProtocolSessionRegistry.inMemory();
        this.leaseTtl = Duration.ofMillis(Math.max(1_000L, properties.getRunLeaseTtlMs()));
        this.heartbeatInterval =
                Duration.ofMillis(
                        Math.max(
                                250L,
                                Math.min(
                                        properties.getRunHeartbeatIntervalMs(),
                                        leaseTtl.toMillis() / 2)));
        // A shared registry answers over the network, so this must not run per event.
        this.cancelCheckIntervalMs = Math.max(50L, properties.getCancelCheckIntervalMs());
        this.confirmTimeout = Duration.ofMillis(Math.max(1_000L, properties.getConfirmTimeoutMs()));
        this.resumeLeaseWaitMs =
                Math.min(30_000L, Math.max(100L, properties.getResumeLeaseWaitMs()));
    }

    public AgentProtocolTaskStore(
            AgentFactory agentFactory, ProtocolTaskRepository taskRepository) {
        this(
                agentFactory,
                taskRepository,
                new AgentProtocolTaskEventBus(),
                new AgentProtocolProperties());
    }

    public AgentProtocolTaskStore(
            HarnessAgent harnessAgent, ProtocolTaskRepository taskRepository) {
        this(AgentFactory.fixed(harnessAgent), taskRepository);
    }

    public AgentProtocolEventBus eventBus() {
        return eventBus;
    }

    /**
     * Starts a background task. Idempotent while running: if a live run already owns this task — on
     * this instance or any other — it returns without starting a second one.
     *
     * @throws IllegalStateException if the task id already completed (HTTP 409)
     */
    public void submit(String taskId, String agentId, String input) {
        submit(taskId, agentId, input, null);
    }

    /**
     * Starts a background task, optionally continuing an earlier task's agent session.
     *
     * <p>{@code context.session_id} names the session the run executes in — the agent sees that
     * conversation and {@code input} as its next user turn — while {@code taskId} still identifies
     * this submission's own record, result and event stream. Omitting the field keeps every task in
     * an isolated session named after its own id, so a caller can delegate a fresh, context-free
     * task to the same agent. A session already running a turn rejects the submission with {@link
     * SessionBusyException}, and one registered to another user or agent is rejected with {@link
     * IllegalArgumentException}.
     *
     * @throws IllegalStateException if this task id already finished, or another turn still owns
     *     the session or is awaiting confirmation (HTTP 409)
     * @throws IllegalArgumentException if the session belongs to another user or agent (HTTP 400)
     */
    public void submit(String taskId, String agentId, String input, Map<String, Object> context) {
        if (isTerminal(taskId)) {
            throw new IllegalStateException("task_id already finished: " + taskId);
        }
        Optional<ProtocolTaskLease> acquired = runRegistry.tryAcquire(taskId, leaseTtl);
        if (acquired.isEmpty()) {
            // A live run owns the task; a repeated submit must not start a second one.
            return;
        }
        ProtocolTaskLease taskLease = acquired.get();
        ProtocolTaskLease sessionLease = null;
        boolean started = false;
        try {
            if (isTerminal(taskId)) {
                throw new IllegalStateException("task_id already finished: " + taskId);
            }
            ProtocolTaskContext ctx = ProtocolTaskContext.from(context);
            String sessionId = resolveSessionId(taskId, agentId, ctx);
            requireSessionNotAwaitingConfirm(sessionId);
            sessionLease = acquireSession(sessionId, taskId);
            final List<ProtocolTaskLease> runLeases = leases(taskLease, sessionLease);

            TaskRecord pending = newProtocolRecord(taskId, agentId, sessionId, ctx);
            pending.setStatus(TaskStatus.PENDING);
            persist(pending);
            // Retained only once the control-plane record exists: when the persist above fails the
            // run never starts, and an entry written first would be left behind forever.
            contextStore.save(taskId, ctx);
            // Bound before the run starts, and only here: a turn that later pauses is already the
            // session's current turn, so no pause-time write has to be relied on. A submission
            // without a client-named session gets its own id as the session, which nobody can
            // share, so there is nothing to point at and no entry to accumulate.
            if (!sessionId.equals(taskId)) {
                sessionRegistry.bindCurrentTurn(sessionId, taskId);
            }

            executor.execute(
                    () -> runAgent(taskId, agentId, input, ctx, null, runLeases, sessionId));
            started = true;
        } finally {
            if (!started) {
                contextStore.clear(taskId);
                releaseAll(taskLease, sessionLease);
            }
        }
    }

    /**
     * Resolves the session one run executes in.
     *
     * <p>A caller-named session wins: the value travels with the submission so a follow-up lands in
     * the conversation it names, without the caller having to remember a task id. The first use
     * registers the owner pair (user, agent), and every later use is verified against it — a
     * mismatch is rejected instead of silently opening an empty conversation in another namespace.
     *
     * <p>Without {@code context.session_id} the session is the task's own id, which keeps every
     * submission isolated. That is also what a client written before sessions existed gets.
     *
     * @throws IllegalArgumentException if the session belongs to another user or agent
     */
    private String resolveSessionId(String taskId, String agentId, ProtocolTaskContext ctx) {
        String requested = ctx.sessionId();
        if (requested == null || requested.isBlank()) {
            return taskId;
        }
        String sessionId = requested.trim();
        sessionRegistry.registerOrVerify(sessionId, ctx.userId(), agentId, ctx.parentAgentId());
        return sessionId;
    }

    /**
     * Claims {@code taskId} for a resume, waiting briefly while the paused run is still unwinding.
     *
     * <p>A pause is written before the run hands its leases back, so that no submission can slip
     * into the session in between. The other side of that ordering is this wait: a client that
     * reacts to the record can arrive while the previous run still holds the lease. That run is
     * finishing, not running, so waiting is correct — but only for a bounded time, and only when
     * the record really is paused. A lease held by a task that is not paused belongs to a live run
     * and fails immediately.
     *
     * @throws IllegalStateException when the task stays owned by a live run
     */
    private ProtocolTaskLease acquireForResume(String taskId) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(resumeLeaseWaitMs);
        while (true) {
            Optional<ProtocolTaskLease> acquired = runRegistry.tryAcquire(taskId, leaseTtl);
            if (acquired.isPresent()) {
                return acquired.get();
            }
            boolean unwinding =
                    taskRepository.find(taskId).map(TaskRecord::isAwaitingConfirm).orElse(false);
            if (!unwinding || System.nanoTime() >= deadline) {
                throw new IllegalStateException("task run is in progress: " + taskId);
            }
            try {
                Thread.sleep(RESUME_LEASE_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for task " + taskId, e);
            }
        }
    }

    /** Whether {@code taskId} is still paused, re-read after the run released its leases. */
    private boolean stillAwaitingConfirm(String taskId) {
        return taskRepository.find(taskId).map(TaskRecord::isAwaitingConfirm).orElse(false);
    }

    /**
     * Refuses a submission while the session's previous turn is still paused for confirmation.
     *
     * <p>A paused turn owns its session even though it holds no lease: the confirm decisions it
     * waits for were produced against the state it paused in, so letting another turn advance that
     * state first would apply them to a conversation that moved on. The caller's escape hatch is to
     * cancel the pending task, which releases the session.
     *
     * @throws SessionAwaitingConfirmException when the pause is still live
     */
    private void requireSessionNotAwaitingConfirm(String sessionId) {
        Optional<String> current = sessionRegistry.currentTurn(sessionId);
        if (current.isEmpty()) {
            return;
        }
        Optional<TaskRecord> previous = taskRepository.find(current.get());
        if (previous.isEmpty() || !previous.get().isAwaitingConfirm()) {
            // The pointer names a finished turn, or one whose record is gone: the session is free.
            return;
        }
        TaskRecord paused = previous.get();
        Instant deadline = confirmDeadline(paused);
        if (deadline != null && deadline.isAfter(Instant.now())) {
            throw new SessionAwaitingConfirmException(sessionId, paused.getTaskId(), deadline);
        }
        if (!settleConfirmTimeout(paused.getTaskId())) {
            // Someone is resuming it right now; the deadline expired but the turn still owns the
            // session until that attempt settles it.
            throw new SessionAwaitingConfirmException(sessionId, paused.getTaskId(), deadline);
        }
    }

    /** Whether {@code record} is a pause whose confirmation deadline has passed. */
    private boolean confirmExpired(TaskRecord record) {
        if (record == null || !record.isAwaitingConfirm()) {
            return false;
        }
        Instant deadline = confirmDeadline(record);
        return deadline != null && !deadline.isAfter(Instant.now());
    }

    /**
     * Deadline of the pause on {@code record}: when the record last changed plus the configured
     * timeout.
     *
     * <p>The record is the only clock that matters. A pause is written by the transition that sets
     * {@code awaitingConfirm}, so its timestamp is exactly when the client was asked — there is no
     * second store to consult, and therefore no chance of the two disagreeing.
     */
    private Instant confirmDeadline(TaskRecord record) {
        Instant base =
                record.getLastUpdatedAt() != null
                        ? record.getLastUpdatedAt()
                        : record.getCreatedAt();
        return base == null ? null : base.plus(confirmTimeout);
    }

    /**
     * Ends a paused turn whose deadline passed, unless another instance is resuming it.
     *
     * <p>The task lease is what makes this safe to attempt from a read path: a resume that already
     * holds it keeps the turn, and this call reports failure instead of racing it.
     *
     * @return {@code true} when the turn is settled (now or by an earlier caller)
     */
    private boolean settleConfirmTimeout(String taskId) {
        Optional<ProtocolTaskLease> acquired = runRegistry.tryAcquire(taskId, leaseTtl);
        if (acquired.isEmpty()) {
            return false;
        }
        ProtocolTaskLease lease = acquired.get();
        try {
            Optional<TaskRecord> found = taskRepository.find(taskId);
            if (found.isEmpty()) {
                return true;
            }
            TaskRecord record = found.get();
            if (!record.isAwaitingConfirm()) {
                // A resume won the race and already moved the record on.
                return true;
            }
            endTimedOutTurn(taskId, record);
            return true;
        } finally {
            runRegistry.release(lease);
        }
    }

    /** Writes the terminal record for a pause nobody answered, plus its event. */
    private void endTimedOutTurn(String taskId, TaskRecord record) {
        String error = confirmTimeoutError();
        update(taskId, TaskStatus.FAILED, null, error, record.getSubAgentId(), false, null);
        RemoteAgentEvent event = new RemoteAgentEvent();
        event.setType(RemoteEventType.RUN_ERROR);
        event.setAgentId(record.getSubAgentId());
        event.setError(error);
        event.setStatus("error");
        eventBus.publish(taskId, event);
        eventBus.complete(taskId);
    }

    private String confirmTimeoutError() {
        return "confirm_timeout: no /resume within " + confirmTimeout.toMillis() + " ms";
    }

    /**
     * Takes the writer slot for {@code sessionId}: one run per conversation at a time.
     *
     * <p>This is a second, coarser lease than the per-task one. The task lease answers "is this
     * submission already running", which is not enough once several task ids can share a session:
     * two concurrent turns would both hold their own task lease and interleave their writes.
     *
     * @throws SessionBusyException when another run currently owns the session
     */
    private ProtocolTaskLease acquireSession(String sessionId, String taskId) {
        Optional<ProtocolTaskLease> held = runRegistry.tryAcquire(sessionKey(sessionId), leaseTtl);
        if (held.isEmpty()) {
            throw new SessionBusyException(sessionId, taskId);
        }
        return held.get();
    }

    private static String sessionKey(String sessionId) {
        return "session:" + sessionId;
    }

    private static List<ProtocolTaskLease> leases(
            ProtocolTaskLease taskLease, ProtocolTaskLease sessionLease) {
        return sessionLease == null ? List.of(taskLease) : List.of(taskLease, sessionLease);
    }

    private void releaseAll(ProtocolTaskLease taskLease, ProtocolTaskLease sessionLease) {
        if (sessionLease != null) {
            runRegistry.release(sessionLease);
        }
        runRegistry.release(taskLease);
    }

    /** Session stored on {@code record}, falling back to its task id for pre-continuation records. */
    private static String sessionIdOf(TaskRecord record) {
        String sessionId = record.getSubSessionId();
        return sessionId != null && !sessionId.isBlank() ? sessionId : record.getTaskId();
    }

    /**
     * Resumes a task paused for HITL confirmation with the given decisions.
     *
     * <p>The {@code awaiting_confirm} record is the pause signal: the paused run persists it before
     * it stops and releases its lease, so a client may legitimately call this while that run is
     * still unwinding (a fast poll of {@link #snapshot} or the REQUIRE_CONFIRM event lands in that
     * window). The record, re-validated after the lease is taken, is therefore what decides; the
     * lease is what keeps two instances from starting the next run at the same time.
     *
     * @throws IllegalStateException if another run holds the task, or it is not awaiting confirmation
     * @throws SessionBusyException if the session still has a running turn; the task stays awaiting
     *     confirmation, so the same call succeeds once that turn finishes
     */
    public void resume(String taskId, List<RemoteConfirmDecision> decisions) {
        if (!properties.isHitlEnabled()) {
            throw new IllegalStateException("HITL is disabled on this server");
        }
        ProtocolTaskLease taskLease = acquireForResume(taskId);
        ProtocolTaskLease sessionLease = null;
        boolean started = false;
        try {
            Optional<TaskRecord> existing = taskRepository.find(taskId);
            if (existing.isEmpty() || !existing.get().isAwaitingConfirm()) {
                throw new IllegalStateException("task is not awaiting confirmation: " + taskId);
            }
            TaskRecord rec = existing.get();

            List<RemotePendingConfirm> pending =
                    rec.getPendingConfirms() != null ? rec.getPendingConfirms() : List.of();
            List<ConfirmResult> confirmResults = toConfirmResults(pending, decisions);

            String agentId = rec.getSubAgentId();
            String dummyInput = ""; // resume uses confirm metadata, not a new user prompt
            String sessionId = sessionIdOf(rec);
            if (confirmExpired(rec)) {
                // The deadline passed while the client was deciding. Settle it here rather than
                // resuming a turn nobody bounded.
                endTimedOutTurn(taskId, rec);
                throw new IllegalStateException("confirmation timed out: " + taskId);
            }
            // Claim the session before the record stops reading as awaiting_confirm: a busy session
            // is transient, so a rejected resume must leave the task resumable. Persisting first
            // would strand a RUNNING record that no run ever settles.
            sessionLease = acquireSession(sessionId, taskId);

            rec.setAwaitingConfirm(false);
            rec.setPendingConfirms(null);
            rec.setStatus(TaskStatus.RUNNING);
            persist(rec);

            ProtocolTaskContext ctx = submitContext(taskId);
            final List<ProtocolTaskLease> runLeases = leases(taskLease, sessionLease);
            executor.execute(
                    () ->
                            runAgent(
                                    taskId,
                                    agentId,
                                    dummyInput,
                                    ctx,
                                    confirmResults,
                                    runLeases,
                                    sessionId));
            started = true;
        } finally {
            if (!started) {
                releaseAll(taskLease, sessionLease);
            }
        }
    }

    /**
     * Submission metadata for {@code taskId}. A shared {@link ProtocolTaskContextStore} keeps it
     * across instances; when an implementation no longer has it (expiry, restart) the run still
     * proceeds with the documented defaults rather than failing a resume the client cannot retry
     * differently.
     */
    private ProtocolTaskContext submitContext(String taskId) {
        Optional<ProtocolTaskContext> stored = contextStore.find(taskId);
        if (stored.isEmpty()) {
            log.warn(
                    "No submit context retained for task {}; resuming with default detail and no"
                            + " user identity",
                    taskId);
            return ProtocolTaskContext.empty();
        }
        return stored.get();
    }

    private String runAgent(
            String taskId,
            String agentId,
            String input,
            ProtocolTaskContext submitCtx,
            List<ConfirmResult> confirmResults,
            List<ProtocolTaskLease> leases,
            String sessionId) {
        Thread runThread = Thread.currentThread();
        AtomicBoolean leaseLost = new AtomicBoolean(false);
        ScheduledFuture<?> heartbeat =
                leaseHeartbeat.scheduleAtFixedRate(
                        () -> renewLeases(leases, leaseLost, runThread),
                        heartbeatInterval.toMillis(),
                        heartbeatInterval.toMillis(),
                        TimeUnit.MILLISECONDS);
        runRegistry.bindCancelHandler(taskId, runThread::interrupt);
        try {
            if (runRegistry.isCancelled(taskId)) {
                log.debug("Protocol task {} was cancelled before it started", taskId);
                contextStore.clear(taskId);
                return null;
            }
            updateRunning(taskId, agentId);
            AgentRequest request =
                    new AgentRequest(
                            taskId,
                            agentId,
                            input,
                            submitCtx.userId(),
                            submitCtx.parentSessionId(),
                            confirmResults != null,
                            submitCtx.raw());
            RuntimeContext ctx = buildRuntimeContext(request, sessionId);

            Msg.Builder msgBuilder =
                    Msg.builder().role(MsgRole.USER).textContent(input != null ? input : "");
            if (confirmResults != null && !confirmResults.isEmpty()) {
                Map<String, Object> meta = new HashMap<>();
                meta.put(Msg.METADATA_CONFIRM_RESULTS, confirmResults);
                msgBuilder.metadata(meta);
            }
            Msg msg = msgBuilder.build();

            HarnessAgent agent = agentFactory.create(request);
            if (agent == null) {
                throw new IllegalStateException(
                        "AgentFactory returned no agent for agent_id=" + agentId);
            }
            AtomicReference<Msg> resultRef = new AtomicReference<>();
            String detail = submitCtx.detail();
            AtomicLong lastCancelCheck = new AtomicLong();

            Flux<AgentEvent> events = agent.streamEvents(msg, ctx);
            events.doOnNext(
                            event -> {
                                if (event instanceof AgentResultEvent resultEvent) {
                                    resultRef.set(resultEvent.getResult());
                                }
                                throwIfCancelled(taskId, lastCancelCheck);
                                if (leaseLost.get()) {
                                    throw new LeaseLostException();
                                }
                                if (!properties.isStreamingEnabled()) {
                                    return;
                                }
                                RemoteEventCodec.fromAgentEvent(event)
                                        .filter(dto -> RemoteEventCodec.matchesDetail(dto, detail))
                                        .ifPresent(
                                                dto -> {
                                                    dto.setAgentId(agentId);
                                                    eventBus.publish(taskId, dto);
                                                });
                            })
                    .blockLast(Duration.ofHours(2));

            throwIfCancelled(taskId, null);

            Msg reply = resultRef.get();
            if (reply != null
                    && reply.getGenerateReason() == GenerateReason.PERMISSION_ASKING
                    && properties.isHitlEnabled()) {
                List<RemotePendingConfirm> pending = extractPendingConfirms(reply);
                // The pause becomes visible before the leases are handed back. A submission that
                // arrives in between is then rejected twice over — by the session lease while this
                // run still holds it, and by the record once it is released — so no new turn can
                // start in a session whose pause is still being written.
                boolean paused = markRecordAwaitingConfirm(taskId, agentId, pending);
                releaseRun(taskId, leases, heartbeat);
                if (paused && stillAwaitingConfirm(taskId)) {
                    // A cancel may have landed while this run was unwinding; it already wrote the
                    // terminal record, so announcing a pause now would be stale.
                    RemoteAgentEvent require = new RemoteAgentEvent();
                    require.setType(RemoteEventType.REQUIRE_CONFIRM);
                    require.setAgentId(agentId);
                    require.setPendingConfirms(pending);
                    require.setStatus("awaiting_confirm");
                    eventBus.publish(taskId, require);
                }
                return AWAITING_CONFIRM_SENTINEL;
            }

            // The task result is a string, so a delivered document cannot travel as a block;
            // render a reference instead of storing an empty reply.
            String text = DocumentReferenceRenderer.renderMessageText(reply);
            update(taskId, TaskStatus.COMPLETED, text, null, agentId, false, null);
            publishStatus(taskId, agentId, "success", null);
            eventBus.complete(taskId);
            return text;
        } catch (Exception e) {
            if (isCancellation(taskId, e)) {
                // cancel() already wrote the terminal record and closed the stream; a run that
                // unwinds afterwards must not turn CANCELLED into FAILED.
                log.debug("Protocol task {} stopped after cancellation", taskId);
                contextStore.clear(taskId);
                eventBus.complete(taskId);
                return null;
            }
            if (leaseLost.get() || e instanceof LeaseLostException) {
                // Another instance took the task over. Stop quietly instead of writing state or
                // closing the event stream that now belongs to the new owner; a task nobody took
                // over is settled by the read-side repair in findRepaired(...).
                log.warn("Protocol task {} stopped because its run lease was taken over", taskId);
                return null;
            }
            log.warn("Protocol task {} failed", taskId, e);
            failTask(taskId, agentId, describe(e));
            throw new RuntimeException(e);
        } catch (Error e) {
            // A JVM-level failure (linkage, out of memory, stack overflow) bypasses the branch
            // above. The record still has to reach a terminal state: otherwise /wait blocks until
            // its own timeout and the id stays re-submittable while nothing is running.
            log.error("Protocol task {} failed with {}", taskId, e.toString(), e);
            failTask(taskId, agentId, describe(e));
            throw e;
        } finally {
            // The run thread returns to a pooled executor: never leak a cancellation interrupt.
            Thread.interrupted();
            releaseRun(taskId, leases, heartbeat);
        }
    }

    /** Writes the terminal FAILED record and closes the event stream for {@code taskId}. */
    private void failTask(String taskId, String agentId, String err) {
        update(taskId, TaskStatus.FAILED, null, err, agentId, false, null);
        RemoteAgentEvent error = new RemoteAgentEvent();
        error.setType(RemoteEventType.RUN_ERROR);
        error.setAgentId(agentId);
        error.setError(err);
        error.setStatus("error");
        eventBus.publish(taskId, error);
        eventBus.complete(taskId);
    }

    private static String describe(Throwable error) {
        return error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
    }

    /** Signals that this run must stop because another instance now owns the task. */
    private static final class LeaseLostException extends RuntimeException {
        LeaseLostException() {
            super("run lease was taken over", null, false, false);
        }
    }

    private void releaseRun(
            String taskId, List<ProtocolTaskLease> leases, ScheduledFuture<?> heartbeat) {
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
        if (leases == null || leases.isEmpty()) {
            runRegistry.unbindCancelHandler(taskId);
            return;
        }
        ProtocolTaskLease taskLease = leases.get(0);
        boolean stillOwner =
                runRegistry
                        .find(taskId)
                        .map(current -> current.leaseId().equals(taskLease.leaseId()))
                        .orElse(false);
        if (stillOwner) {
            // The handler slot is per task id and process-local: only the current owner may clear
            // it, or a lease that moved to another run in this JVM loses its interrupt hook.
            runRegistry.unbindCancelHandler(taskId);
        }
        for (ProtocolTaskLease lease : leases) {
            runRegistry.release(lease);
        }
    }

    /**
     * Renews the lease and, when the registry reports it as no longer held, stops the run.
     *
     * <p>Continuing after that point makes this instance a second owner: the registry may already
     * have handed the task to another instance, so both would act on the same task. Stopping is
     * therefore mandatory, not advisory — the interface contract of {@link
     * ProtocolTaskRunRegistry#renew} carries the same rule.
     *
     * <p>A registry <em>failure</em> is not ownership loss: the lease may still be ours once the
     * registry recovers, so that case only logs.
     */
    private void renewLeases(
            List<ProtocolTaskLease> leases, AtomicBoolean leaseLost, Thread runThread) {
        for (ProtocolTaskLease lease : leases) {
            try {
                if (!runRegistry.renew(lease, leaseTtl)) {
                    leaseLost.set(true);
                    log.warn(
                            "Lost the run lease {}; stopping this run so the new owner is the only"
                                    + " writer",
                            lease.taskId());
                    runThread.interrupt();
                    return;
                }
            } catch (RuntimeException e) {
                log.warn("Failed to renew the run lease {}", lease.taskId(), e);
            }
        }
    }

    /**
     * Trades an interrupt for a cooperative stop: a shared registry answers over the network, so the
     * intent is polled at most every {@code cancel-check-interval-ms} rather than per event.
     */
    private void throwIfCancelled(String taskId, AtomicLong lastCancelCheck) {
        if (lastCancelCheck != null) {
            long now = System.currentTimeMillis();
            long last = lastCancelCheck.get();
            if (now - last < cancelCheckIntervalMs || !lastCancelCheck.compareAndSet(last, now)) {
                return;
            }
        }
        if (runRegistry.isCancelled(taskId)) {
            throw new CancellationException("task cancelled: " + taskId);
        }
    }

    private boolean isCancellation(String taskId, Throwable error) {
        if (runRegistry.isCancelled(taskId)) {
            return true;
        }
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof CancellationException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds the agent's runtime context for one run: session/user identity, the caller's {@code
     * context.attributes} under {@link AgentProtocolConstants#RUNTIME_CONTEXT_ATTRIBUTES_KEY}, and
     * whatever the registered {@link RuntimeContextCustomizer}s add on top.
     *
     * <p>The session id is the one resolved at submission time, which is the task id unless the
     * submission continued another task; either way the session decides which stored conversation
     * the agent loads, so this is the single place where a continuation takes effect.
     *
     * <p>Attributes stay behind a single namespaced key by default so that no caller-supplied name
     * can shadow a key the framework reads (e.g. {@code agentId}); promoting individual attributes
     * to their own keys is an explicit opt-in through a customizer.
     */
    private RuntimeContext buildRuntimeContext(AgentRequest request, String sessionId) {
        RuntimeContext.Builder builder = RuntimeContext.builder().sessionId(sessionId);
        if (request.userId() != null) {
            builder.userId(request.userId());
        }
        Map<String, Object> attributes = request.attributes();
        if (!attributes.isEmpty()) {
            builder.put(AgentProtocolConstants.RUNTIME_CONTEXT_ATTRIBUTES_KEY, attributes);
        }
        for (RuntimeContextCustomizer customizer : runtimeContextCustomizers) {
            customizer.customize(request, builder);
        }
        return builder.build();
    }

    private void publishStatus(String taskId, String agentId, String status, String error) {
        RemoteAgentEvent evt = new RemoteAgentEvent();
        evt.setType(RemoteEventType.STATUS);
        evt.setAgentId(agentId);
        evt.setStatus(status);
        evt.setError(error);
        eventBus.publish(taskId, evt);
    }

    /**
     * Records the awaiting-confirm state on the task record.
     *
     * @return {@code false} when the task reached a terminal state meanwhile (it was cancelled while
     *     pausing), in which case the caller must not advertise a confirmation request
     */
    private boolean markRecordAwaitingConfirm(
            String taskId, String agentId, List<RemotePendingConfirm> pending) {
        Optional<TaskRecord> prev = taskRepository.find(taskId);
        if (prev.isPresent() && prev.get().getStatus().isTerminal()) {
            log.debug("Protocol task {} finished before it could pause", taskId);
            return false;
        }
        TaskRecord r = prev.orElseGet(() -> newProtocolRecord(taskId, agentId, null, null));
        r.setStatus(TaskStatus.RUNNING);
        r.setAwaitingConfirm(true);
        r.setPendingConfirms(pending);
        r.touch();
        persist(r);
        return true;
    }

    private static List<RemotePendingConfirm> extractPendingConfirms(Msg reply) {
        List<RemotePendingConfirm> pending = new ArrayList<>();
        for (ToolUseBlock block : reply.getContentBlocks(ToolUseBlock.class)) {
            if (block.getState() == ToolCallState.ASKING) {
                pending.add(
                        new RemotePendingConfirm(
                                block.getId(), block.getName(), toJsonQuiet(block.getInput())));
            }
        }
        if (pending.isEmpty()) {
            // Fall back to all tool uses if state wasn't stamped
            for (ToolUseBlock block : reply.getContentBlocks(ToolUseBlock.class)) {
                pending.add(
                        new RemotePendingConfirm(
                                block.getId(), block.getName(), toJsonQuiet(block.getInput())));
            }
        }
        return pending;
    }

    private static List<ConfirmResult> toConfirmResults(
            List<RemotePendingConfirm> pending, List<RemoteConfirmDecision> decisions) {
        Map<String, RemoteConfirmDecision> byId = new HashMap<>();
        if (decisions != null) {
            for (RemoteConfirmDecision d : decisions) {
                if (d.getToolCallId() != null) {
                    byId.put(d.getToolCallId(), d);
                }
            }
        }
        List<ConfirmResult> results = new ArrayList<>();
        for (RemotePendingConfirm p : pending) {
            RemoteConfirmDecision decision = byId.get(p.getToolCallId());
            boolean approved = decision != null && decision.isApproved();
            Map<String, Object> input = parseInputQuiet(p.getToolInputJson());
            ToolUseBlock toolCall =
                    ToolUseBlock.builder()
                            .id(p.getToolCallId())
                            .name(p.getToolName())
                            .input(input)
                            .build();
            results.add(
                    new ConfirmResult(
                            approved,
                            toolCall,
                            null,
                            decision == null ? null : decision.getReason()));
        }
        return results;
    }

    private void persist(TaskRecord r) {
        taskRepository.save(r);
    }

    /**
     * Creates a record inside the protocol control-plane bucket.
     *
     * <p>{@link TaskRecord}'s parent fields normally name the namespace a record is stored in, not
     * the caller that submitted it: {@link WorkspaceProtocolTaskRepository} fixes that namespace to
     * {@link AgentProtocolConstants#PROTOCOL_AGENT_ID} / {@link
     * AgentProtocolConstants#PROTOCOL_SESSION_ID}. With {@link
     * AgentProtocolProperties#isUseCallerParentIdentity()} enabled they instead carry the caller's
     * own ids, so a record names the agent and conversation that issued it; the stored location
     * still comes from the repository. Records rebuilt here without submission context — a task
     * whose file went missing — always fall back to the namespace.
     */
    private TaskRecord newProtocolRecord(
            String taskId, String agentId, String sessionId, ProtocolTaskContext ctx) {
        return new TaskRecord(
                taskId,
                agentId != null ? agentId : "default",
                parentAgentIdOf(ctx),
                parentSessionIdOf(ctx),
                sessionId);
    }

    private String parentAgentIdOf(ProtocolTaskContext ctx) {
        String caller = ctx != null ? ctx.parentAgentId() : null;
        return properties.isUseCallerParentIdentity() && caller != null && !caller.isBlank()
                ? caller
                : AgentProtocolConstants.PROTOCOL_AGENT_ID;
    }

    private String parentSessionIdOf(ProtocolTaskContext ctx) {
        String caller = ctx != null ? ctx.parentSessionId() : null;
        return properties.isUseCallerParentIdentity() && caller != null && !caller.isBlank()
                ? caller
                : AgentProtocolConstants.PROTOCOL_SESSION_ID;
    }

    private boolean isTerminal(String taskId) {
        Optional<TaskRecord> existing = taskRepository.find(taskId);
        return existing.isPresent() && existing.get().getStatus().isTerminal();
    }

    private void updateRunning(String taskId, String agentId) {
        update(taskId, TaskStatus.RUNNING, null, null, agentId, false, null);
    }

    private void update(
            String taskId,
            TaskStatus status,
            String result,
            String error,
            String agentId,
            boolean awaitingConfirm,
            List<RemotePendingConfirm> pendingConfirms) {
        Optional<TaskRecord> prev = taskRepository.find(taskId);
        if (status.isTerminal()
                && prev.isPresent()
                && prev.get().getStatus().isTerminal()
                && prev.get().getStatus() != status) {
            // Another actor (typically cancel, on any instance) already terminated the task; a late
            // completion must not overwrite it.
            log.debug(
                    "Protocol task {} is already {}; dropping late {} write",
                    taskId,
                    prev.get().getStatus(),
                    status);
            return;
        }
        TaskRecord r = prev.orElseGet(() -> newProtocolRecord(taskId, agentId, null, null));
        r.setSubAgentId(agentId != null ? agentId : r.getSubAgentId());
        r.setStatus(status);
        r.setAwaitingConfirm(awaitingConfirm);
        r.setPendingConfirms(pendingConfirms);
        if (result != null) {
            r.setResult(result);
        }
        if (error != null) {
            r.setErrorMessage(error);
        }
        persist(r);
        if (status.isTerminal()) {
            // The submission metadata only serves a resume, and only a task that can still be
            // resumed needs it. Clearing here makes that the rule for every terminal write,
            // including the read-side repair below, which settles a record without a run.
            contextStore.clear(taskId);
        }
    }

    /**
     * Reads the record, settling one state that no run can write: a task left {@code running} (or
     * {@code pending}) by an instance that died, with no live lease to finish it.
     *
     * <p>The repair is a read-side write rather than a background reaper: whichever instance is
     * asked first settles it, which needs no extra scheduler and no coordination beyond the
     * repository. It stays safe under concurrency because the write is idempotent and {@link #update}
     * refuses to replace a different terminal state.
     */
    private Optional<TaskRecord> findRepaired(String taskId) {
        Optional<TaskRecord> found = taskRepository.find(taskId);
        if (found.isEmpty()) {
            return found;
        }
        if (confirmExpired(found.get())) {
            // A pause that outlived its deadline ends here: the reader that notices it settles the
            // turn, which releases the session for the next submission.
            return settleConfirmTimeout(taskId) ? taskRepository.find(taskId) : found;
        }
        if (!isOrphaned(taskId, found.get())) {
            return found;
        }
        TaskRecord record = found.get();
        log.warn(
                "Protocol task {} has no live run lease and was left {}; marking it failed",
                taskId,
                record.getStatus());
        update(
                taskId,
                TaskStatus.FAILED,
                null,
                OWNER_LOST_ERROR,
                record.getSubAgentId(),
                false,
                null);
        return taskRepository.find(taskId);
    }

    /**
     * Whether a record's run is gone: it is neither terminal nor paused, it has not been touched for
     * at least a full lease TTL, and nobody holds its lease.
     *
     * <p>The TTL check runs first on purpose: it is local and cheap, while {@code find} may be a
     * network round trip on a shared registry, and a record updated within the window cannot be
     * orphaned anyway.
     */
    private boolean isOrphaned(String taskId, TaskRecord record) {
        if (record.isAwaitingConfirm()) {
            return false;
        }
        TaskStatus status = record.getStatus();
        if (status != TaskStatus.RUNNING && status != TaskStatus.PENDING) {
            return false;
        }
        Instant updated =
                record.getLastUpdatedAt() != null
                        ? record.getLastUpdatedAt()
                        : record.getCreatedAt();
        if (updated != null && !updated.plus(leaseTtl).isBefore(Instant.now())) {
            return false;
        }
        return runRegistry.find(taskId).isEmpty();
    }

    /**
     * Protocol snapshot for {@code taskId}: status, result, error and pending confirmations.
     *
     * <p>A {@code pending} or {@code running} record with a live lease reports {@code running}, so a
     * task claimed by another instance reads the same as one run locally. A record whose lease is
     * gone is settled as a failure first, so a client never waits on a run that no longer exists.
     */
    public Map<String, Object> snapshot(String taskId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("task_id", taskId);
        Optional<TaskRecord> rec = findRepaired(taskId);
        if (rec.isEmpty()) {
            m.put("status", "error");
            m.put("error", "task not found");
            return m;
        }
        TaskRecord r = rec.get();
        // The session, not this task, is the conversation: clients aggregate the turns of one
        // conversation by it, and a task id only names one turn.
        if (r.getSubSessionId() != null) {
            m.put("session_id", r.getSubSessionId());
        }
        m.put("status", mapStatus(r));
        if (r.getErrorMessage() != null) {
            m.put("error", r.getErrorMessage());
        }
        if (r.getStatus() == TaskStatus.COMPLETED && r.getResult() != null) {
            m.put("result", r.getResult());
        }
        if (r.isAwaitingConfirm() && r.getPendingConfirms() != null) {
            m.put("pending_confirms", r.getPendingConfirms());
        }
        if (r.isAwaitingConfirm()) {
            Instant deadline = confirmDeadline(r);
            if (deadline != null) {
                m.put("confirm_expires_at", deadline.toString());
            }
        }
        if (!r.isAwaitingConfirm()
                && (r.getStatus() == TaskStatus.RUNNING || r.getStatus() == TaskStatus.PENDING)
                && runRegistry.find(taskId).isPresent()) {
            m.put("status", "running");
        }
        return m;
    }

    /**
     * Blocks until the task reaches a terminal state or the timeout elapses. Does not complete while
     * awaiting confirmation — returns {@code awaiting_confirm} so the client can resume.
     *
     * <p>The stored record is the source of truth, so this works whichever instance runs the task.
     */
    public Map<String, Object> waitFor(String taskId, long timeoutMs) throws Exception {
        long deadline = timeoutMs > 0 ? System.currentTimeMillis() + timeoutMs : Long.MAX_VALUE;

        int attempt = 0;
        while (System.currentTimeMillis() < deadline) {
            Optional<TaskRecord> rec = findRepaired(taskId);
            if (rec.isEmpty()) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("status", "error");
                err.put("error", "task not found");
                return err;
            }
            TaskRecord r = rec.get();
            if (r.isAwaitingConfirm()) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("status", "awaiting_confirm");
                if (r.getPendingConfirms() != null) {
                    out.put("pending_confirms", r.getPendingConfirms());
                }
                Instant confirmBy = confirmDeadline(r);
                if (confirmBy != null) {
                    out.put("confirm_expires_at", confirmBy.toString());
                }
                return out;
            }
            if (r.getStatus().isTerminal()) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("status", mapStatus(r));
                if (r.getStatus() == TaskStatus.COMPLETED) {
                    out.put("result", r.getResult() != null ? r.getResult() : "");
                } else if (r.getStatus() == TaskStatus.FAILED) {
                    out.put("error", r.getErrorMessage());
                }
                return out;
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                break;
            }
            long sleepMs =
                    Math.min(Math.min(5_000L, remaining), 200L * (1L << Math.min(attempt++, 4)));
            Thread.sleep(sleepMs);
        }

        Map<String, Object> tout = new LinkedHashMap<>();
        tout.put("status", "error");
        tout.put("error", "wait timeout after " + timeoutMs + " ms");
        return tout;
    }

    /**
     * Requests cancellation. The intent reaches the instance that owns the run, which stops
     * cooperatively; the terminal {@code CANCELLED} record is written here, so the task already reads
     * as cancelled for any client on any instance.
     */
    public void cancel(String taskId) {
        boolean signalled = runRegistry.requestCancel(taskId);
        Optional<TaskRecord> rec = taskRepository.find(taskId);
        if (rec.isPresent()) {
            TaskRecord r = rec.get();
            r.setCancelRequested(true);
            r.setAwaitingConfirm(false);
            if (!r.getStatus().isTerminal()) {
                r.setStatus(TaskStatus.CANCELLED);
            }
            persist(r);
        }
        eventBus.complete(taskId);
        contextStore.clear(taskId);
        if (!signalled) {
            log.debug("Cancellation recorded for task {} with no live run", taskId);
        }
    }

    /** Visible for tests — whether submission metadata is still retained for {@code taskId}. */
    boolean hasSubmitContext(String taskId) {
        return contextStore.find(taskId).isPresent();
    }

    /** Visible for tests — the clamped resume lease wait actually used by {@link #resume}. */
    long resumeLeaseWaitMs() {
        return resumeLeaseWaitMs;
    }

    /**
     * Stops accepting new runs and interrupts the active ones. Leases are released by the interrupted
     * runs themselves, so another instance can pick the tasks up after the lease TTL.
     */
    @Override
    public void close() {
        leaseHeartbeat.shutdownNow();
        executor.shutdownNow();
    }

    private static String mapStatus(TaskRecord r) {
        if (r.isAwaitingConfirm()) {
            return "awaiting_confirm";
        }
        return switch (r.getStatus()) {
            case PENDING -> "pending";
            case RUNNING -> "running";
            case COMPLETED -> "success";
            case FAILED -> "error";
            case CANCELLED -> "cancelled";
        };
    }

    private static String toJsonQuiet(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseInputQuiet(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = JSON.readValue(json, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> out = new HashMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (e.getKey() != null) {
                        out.put(String.valueOf(e.getKey()), e.getValue());
                    }
                }
                return out;
            }
            return Map.of("raw", parsed);
        } catch (JsonProcessingException e) {
            return Map.of("raw", json);
        }
    }
}
