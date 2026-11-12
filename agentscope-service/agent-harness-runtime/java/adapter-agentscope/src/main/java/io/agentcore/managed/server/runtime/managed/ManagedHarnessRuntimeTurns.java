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
package io.agentcore.managed.server.runtime.managed;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Channel;
import io.agentcore.managed.server.runtime.managed.ManagedTurnPublisher.Command;
import io.agentscope.core.shutdown.AgentShuttingDownException;
import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/** Initial callbacks run a segment; Resume callbacks ACK after durable, capacity-bound handoff. */
public final class ManagedHarnessRuntimeTurns implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ManagedHarnessRuntimeTurns.class);
    private static final Duration RETRY_DELAY = Duration.ofMillis(500);
    private static final Set<String> TERMINAL = Set.of("succeeded", "failed", "cancelled");
    private static final Set<String> WAITING = Set.of("waiting_tool", "waiting_input");

    @FunctionalInterface
    public interface Runner {
        void run(ManagedTurnExecution execution) throws Exception;
    }

    /** Completion owns the execution lease; no worker waits for a model or preparation future. */
    @FunctionalInterface
    public interface AsyncRunner extends Runner {
        Mono<Void> runAsync(
                ManagedTurnExecution execution, Scheduler scheduler, Mono<Throwable> interrupted);

        default void run(ManagedTurnExecution execution) {
            ManagedExecutionAwait.run(
                    stop -> runAsync(execution, Schedulers.boundedElastic(), stop));
        }
    }

    private final ManagedMessageBus bus;
    private final ManagedLeaseStore leases;
    private final ManagedAdmissionStore admission;
    private final ManagedJournalStore journal;
    private final ManagedRuntimeWriter commits;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService renewals;
    private final Duration ttl;
    private final Duration turnSuspend;
    private final Duration renewalPeriod;
    private final ManagedExecutionCapacity capacity;
    private final ManagedResumeExecutions resumes;
    private final ManagedReceiveCapacity receiveCapacity;
    private final ManagedReceiveCapacity initialReceiveCapacity;
    private final ManagedReceiveCapacity resumeReceiveCapacity;
    private final ManagedResumeBackoff backoff = new ManagedResumeBackoff();
    private final Set<Thread> activeThreads = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public ManagedHarnessRuntimeTurns(
            ManagedMessageBus bus,
            ManagedLeaseStore leases,
            ManagedAdmissionStore admission,
            ManagedJournalStore journal,
            ManagedRuntimeWriter commits,
            ObjectMapper mapper,
            ScheduledExecutorService renewals,
            Duration ttl,
            Duration renewalPeriod,
            int concurrency) {
        this(
                bus,
                leases,
                admission,
                journal,
                commits,
                mapper,
                renewals,
                ttl,
                renewalPeriod,
                concurrency,
                Duration.ofSeconds(60));
    }

    public ManagedHarnessRuntimeTurns(
            ManagedMessageBus bus,
            ManagedLeaseStore leases,
            ManagedAdmissionStore admission,
            ManagedJournalStore journal,
            ManagedRuntimeWriter commits,
            ObjectMapper mapper,
            ScheduledExecutorService renewals,
            Duration ttl,
            Duration renewalPeriod,
            int concurrency,
            Duration turnSuspend) {
        this(
                bus,
                leases,
                admission,
                journal,
                commits,
                mapper,
                renewals,
                ttl,
                renewalPeriod,
                concurrency,
                turnSuspend,
                64);
    }

    public ManagedHarnessRuntimeTurns(
            ManagedMessageBus bus,
            ManagedLeaseStore leases,
            ManagedAdmissionStore admission,
            ManagedJournalStore journal,
            ManagedRuntimeWriter commits,
            ObjectMapper mapper,
            ScheduledExecutorService renewals,
            Duration ttl,
            Duration renewalPeriod,
            int concurrency,
            Duration turnSuspend,
            int handoffCapacity) {
        ManagedMessageBus.positiveMillis(turnSuspend);
        this.turnSuspend = turnSuspend;
        if (concurrency < 0)
            throw new IllegalArgumentException("Harness capacity cannot be negative");
        if (ManagedMessageBus.positiveMillis(ttl)
                < ManagedMessageBus.positiveMillis(renewalPeriod) * 3)
            throw new IllegalArgumentException("Renewal period must leave two retry windows");
        this.bus = bus;
        this.leases = leases;
        this.admission = admission;
        this.journal = journal;
        this.commits = commits;
        this.mapper = mapper;
        this.renewals = renewals;
        this.ttl = ttl;
        this.renewalPeriod = renewalPeriod;
        this.capacity = ManagedExecutionCapacity.of(concurrency);
        this.receiveCapacity =
                new ManagedReceiveCapacity(
                        capacity,
                        concurrency == 0
                                ? handoffCapacity
                                : Math.min(concurrency, handoffCapacity));
        this.initialReceiveCapacity = receiveCapacity.lane("initial");
        this.resumeReceiveCapacity = receiveCapacity.lane("resume");
        this.resumes =
                new ManagedResumeExecutions(
                        leases,
                        admission,
                        journal,
                        commits,
                        renewals,
                        ttl,
                        renewalPeriod,
                        capacity,
                        concurrency);
    }

    public ManagedReceiveCapacity receiveCapacity() {
        return receiveCapacity;
    }

    public ManagedReceiveCapacity receiveCapacity(Channel channel) {
        return channel == Channel.SESSION_READY ? initialReceiveCapacity : resumeReceiveCapacity;
    }

    /** Resume success hands off to a durable claim or delayed outbox; neither needs this receipt. */
    public void consume(ManagedPushDelivery delivery, Runner runner) throws Exception {
        if (delivery.channel() == Channel.SESSION_READY) {
            consumeReady(delivery, runner);
            return;
        }
        if (delivery.channel() != Channel.TURN_RESUMES) {
            consumeOnce(delivery, runner);
            return;
        }
        String key = delivery.message().messageGroup() + ":" + delivery.message().id();
        try {
            consumeOnce(delivery, runner);
            backoff.clear(key);
        } catch (ManagedDeliverySuspended waiting) {
            if (mapper.readTree(delivery.message().body()).has("notificationId")) throw waiting;
            var delay = backoff.next(key, waiting.delay());
            // Persist the same immutable Resume intent, then release the old receipt by ACK.
            // If this transaction fails, propagate: neither an uncertain claim nor an unrecorded
            // retry is acknowledged. Receive admission bounds this exceptional path.
            journal.suspensions().defer(decodeResume(delivery), delay);
            ManagedRuntimeLog.info(
                    log,
                    "harness_runtime.resume.deferred",
                    null,
                    null,
                    "resume_id",
                    delivery.message().id(),
                    "retry_ms",
                    delay.toMillis());
        }
    }

    private void consumeReady(ManagedPushDelivery delivery, Runner runner) throws Exception {
        if (closed.get() || !bus.active(delivery))
            throw new ManagedTransportException("Runtime receipt no longer active");
        var ready = mapper.readValue(delivery.message().body(), ManagedSessionQueue.Ready.class);
        if (!ready.notificationId().equals(delivery.message().id())
                || !ready.scope().messageGroup().equals(delivery.message().messageGroup()))
            throw new IllegalArgumentException("Ready envelope identity differs");
        admission.queue().validate(ready);
        String id = admission.queue().candidate(ready.scope());
        if (id == null) return;
        var turn = admission.turn(ready.scope(), id);
        try {
            resumes.consumeInitial(ready.scope(), turn, runner, delivery.handoff());
            backoff.clear(ready.scope().messageGroup());
        } catch (ManagedDeliverySuspended
                | RejectedExecutionException
                | DataAccessException
                | ManagedTransportException busy) {
            admission.queue().defer(ready, backoff.next(ready.scope().messageGroup(), RETRY_DELAY));
        } catch (ManagedStateException race) {
            if (!Set.of(
                            "turn_suspended",
                            "missing_predecessor",
                            "execution_busy",
                            "worker_lease_expired")
                    .contains(race.code())) throw race;
            admission.queue().defer(ready, backoff.next(ready.scope().messageGroup(), RETRY_DELAY));
        }
    }

    private void consumeOnce(ManagedPushDelivery delivery, Runner runner) throws Exception {
        if (closed.get() || !bus.active(delivery))
            throw new ManagedTransportException("HarnessRuntime callback is no longer active");
        ManagedSuspensions.Resume resume = null;
        if (delivery.channel() == Channel.TURN_RESUMES) resume = decodeResume(delivery);
        var command =
                resume == null
                        ? decode(delivery)
                        : new Command(
                                resume.scope(), resume.turnId(), resume.publicationSequence());
        var scope = command.scope();
        if (resume == null && admission.queue().enabled(scope))
            throw new ManagedStateException(
                    "scheduler_mode_mismatch",
                    "DB queued Turns cannot be dispatched through the legacy Turn channel");
        var turn = admission.turn(scope, command.turnId());
        if (turn.publicationSequence() == null
                || turn.publicationSequence() != command.publicationSequence())
            throw new ManagedStateException(
                    "publication_mismatch", "MQ command differs from publication ledger");
        if (resume == null
                && delivery.message().depth()
                        != ManagedTurnLanes.depth(admission.session(scope).configuration(), mapper))
            throw new ManagedStateException(
                    "turn_depth_mismatch", "Turn depth differs from frozen Session configuration");
        if (resume == null && !"queued".equals(turn.deliveryStatus()))
            admission.confirmPublication(scope, command.turnId(), delivery.brokerMessageId());
        if (TERMINAL.contains(turn.status())) return;
        if (resume != null && journal.suspensions().settled(resume)) return;
        if (resume != null) {
            try {
                resumes.consume(resume, turn, runner, delivery.handoff());
            } catch (ManagedStateException changed) {
                if (journal.suspensions().settled(resume)) return;
                if (!Set.of(
                                "turn_suspended",
                                "missing_predecessor",
                                "resume_not_ready",
                                "execution_busy",
                                "worker_lease_expired")
                        .contains(changed.code())) throw changed;
                throw new ManagedDeliverySuspended(RETRY_DELAY);
            } catch (RejectedExecutionException
                    | DataAccessException
                    | ManagedTransportException unavailable) {
                throw new ManagedDeliverySuspended(RETRY_DELAY);
            }
            return;
        }
        if (resume == null && WAITING.contains(turn.status()))
            throw new ManagedDeliverySuspended(turnSuspend);
        if (!capacity.tryAcquire()) throw new ManagedDeliverySuspended(RETRY_DELAY);
        var thread = Thread.currentThread();
        activeThreads.add(thread);
        try {
            if (closed.get()) throw new ManagedTransportException("HarnessRuntime is closed");
            try {
                execute(delivery, command, resume, runner);
            } catch (ManagedStateException changed) {
                // Admission rechecks under the Session lock; these races are ordinary waiting.
                if (resume != null && journal.suspensions().settled(resume)) return;
                if (!Set.of(
                                "turn_suspended",
                                "missing_predecessor",
                                "resume_not_ready",
                                "execution_busy",
                                "worker_lease_expired")
                        .contains(changed.code())) throw changed;
                throw new ManagedDeliverySuspended(
                        resume == null && "turn_suspended".equals(changed.code())
                                ? turnSuspend
                                : RETRY_DELAY);
            } catch (RuntimeException unavailable) {
                if (shuttingDown(unavailable)) throw new ManagedDeliverySuspended(turnSuspend);
                if (ManagedToolPublicationPending.contains(unavailable))
                    throw new ManagedDeliverySuspended(Duration.ofSeconds(1));
                throw unavailable;
            }
        } finally {
            activeThreads.remove(thread);
            capacity.release();
        }
        if (resume != null && journal.suspensions().settled(resume)) return;
        var after = admission.turn(scope, command.turnId());
        if (TERMINAL.contains(after.status())) return;
        throw new ManagedDeliverySuspended(
                resume == null && WAITING.contains(after.status()) ? turnSuspend : RETRY_DELAY);
    }

    private static boolean shuttingDown(Throwable failure) {
        for (int depth = 0; failure != null && depth < 32; depth++, failure = failure.getCause())
            if (failure instanceof AgentShuttingDownException
                    || failure instanceof RejectedExecutionException) return true;
        return false;
    }

    public interface Handler extends ManagedConsumerLoop.Handler {
        default CompletionStage<Void> prepare(ManagedScope scope, String turnId) {
            return CompletableFuture.completedFuture(null);
        }

        default void advance(ManagedScope scope, String turnId) throws Exception {
            throw new UnsupportedOperationException("Session queue handler is not installed");
        }
    }

    /** Durable queue scanner owns retry; Redis + the DB epoch fence each execution slice. */
    public void advance(ManagedScope scope, String turnId, Runner runner) throws Exception {
        if (closed.get()) throw new ManagedTransportException("HarnessRuntime is closed");
        if (!admission.queue().enabled(scope))
            throw new ManagedStateException("scheduler_mode_mismatch", "Not a DB queue");
        if (!capacity.tryAcquire()) return;
        var thread = Thread.currentThread();
        activeThreads.add(thread);
        try {
            if (closed.get()) throw new ManagedTransportException("HarnessRuntime is closed");
            if (!turnId.equals(admission.queue().candidate(scope))) return;
            var turn = admission.turn(scope, turnId);
            execute(null, new Command(scope, turnId, turn.publicationSequence()), null, runner);
        } finally {
            activeThreads.remove(thread);
            capacity.release();
        }
    }

    private void execute(
            ManagedPushDelivery delivery,
            Command command,
            ManagedSuspensions.Resume resume,
            Runner runner)
            throws Exception {
        var scope = command.scope();
        if (TERMINAL.contains(admission.turn(scope, command.turnId()).status())) return;
        var executionThread = Thread.currentThread();
        var interruptedByGuard = new AtomicBoolean();
        var executing = new AtomicBoolean(true);
        Runnable onLost =
                () -> {
                    if (executing.get()) {
                        interruptedByGuard.set(true);
                        executionThread.interrupt();
                    }
                };
        final var acquired =
                (delivery == null
                                ? ManagedExecutionGuard.acquireContinuation(
                                        scope, leases, ttl, System::nanoTime, onLost)
                                : ManagedExecutionGuard.acquire(
                                        scope,
                                        bus,
                                        leases,
                                        delivery,
                                        ttl,
                                        System::nanoTime,
                                        onLost))
                        .orElseThrow(() -> new ManagedDeliverySuspended(RETRY_DELAY));
        ScheduledFuture<?> renewal = null;
        ManagedTurnExecution execution = null;
        try {
            renewal =
                    renewals.scheduleWithFixedDelay(
                            () -> {
                                try {
                                    acquired.renew();
                                } catch (RuntimeException lost) {
                                    /* Guard stops the old attempt. */
                                }
                            },
                            renewalPeriod.toMillis(),
                            renewalPeriod.toMillis(),
                            TimeUnit.MILLISECONDS);
            acquired.check();
            long epoch =
                    resume == null
                            ? admission.activate(
                                    scope,
                                    command.turnId(),
                                    command.publicationSequence(),
                                    acquired.token())
                            : admission.activateResume(resume, acquired.token());
            if (TERMINAL.contains(admission.turn(scope, command.turnId()).status())) return;
            execution =
                    new ManagedTurnExecution(
                            scope, command.turnId(), epoch, acquired, admission, journal, commits);
            ManagedRuntimeLog.checkpoint(
                    log,
                    "harness_runtime.execution.started",
                    scope,
                    command.turnId(),
                    execution.head().checkpoint(),
                    "epoch",
                    epoch,
                    "consumer_id",
                    delivery == null ? null : delivery.consumerId(),
                    "delivery_id",
                    delivery == null ? null : delivery.deliveryId(),
                    "channel",
                    delivery == null ? null : delivery.channel().name(),
                    "trigger",
                    delivery == null ? "SESSION_QUEUE" : "MQ_CALLBACK",
                    "resume_id",
                    resume == null ? null : resume.resumeId(),
                    "suspension_id",
                    resume == null ? null : resume.suspensionId());
            runner.run(execution);
            if (!execution.handedOff())
                throw new ManagedStateException(
                        "turn_unfinished", "Runner returned without committing a durable handoff");
        } catch (Exception | Error failure) {
            ManagedRuntimeLog.checkpoint(
                    log,
                    "harness_runtime.execution.failed",
                    scope,
                    command.turnId(),
                    execution == null ? null : execution.head().checkpoint(),
                    "delivery_id",
                    delivery == null ? null : delivery.deliveryId(),
                    "consumer_id",
                    delivery == null ? null : delivery.consumerId(),
                    "failure_type",
                    failure.getClass().getSimpleName());
            throw failure;
        } finally {
            executing.set(false);
            if (renewal != null) renewal.cancel(false);
            acquired.close();
            ManagedRuntimeLog.checkpoint(
                    log,
                    "harness_runtime.execution.released",
                    scope,
                    command.turnId(),
                    execution == null ? null : execution.head().checkpoint(),
                    "epoch",
                    execution == null ? null : execution.epoch(),
                    "delivery_id",
                    delivery == null ? null : delivery.deliveryId(),
                    "consumer_id",
                    delivery == null ? null : delivery.consumerId(),
                    "handed_off",
                    execution != null && execution.handedOff(),
                    "evicts_agent_cache",
                    false);
            if (interruptedByGuard.get()) Thread.interrupted();
        }
    }

    private ManagedSuspensions.Resume decodeResume(ManagedPushDelivery delivery) {
        try {
            var resume =
                    mapper.readValue(delivery.message().body(), ManagedSuspensions.Resume.class);
            if (!resume.resumeId().equals(delivery.message().id())
                    || !resume.scope().messageGroup().equals(delivery.message().messageGroup()))
                throw new IllegalArgumentException("Resume message identity differs");
            return resume;
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Invalid resume command", invalid);
        }
    }

    private Command decode(ManagedPushDelivery delivery) {
        if (delivery.channel() != Channel.TURNS)
            throw new IllegalArgumentException("HarnessRuntime consumes Turns and Resumes");
        try {
            var command = mapper.readValue(delivery.message().body(), Command.class);
            if (!command.turnId().equals(delivery.message().id())
                    || !command.scope().messageGroup().equals(delivery.message().messageGroup()))
                throw new IllegalArgumentException("Turn message identity differs");
            return command;
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Invalid Turn command", invalid);
        }
    }

    @Override
    public void close() {
        closed.set(true);
        resumes.close();
        activeThreads.forEach(Thread::interrupt);
    }
}
