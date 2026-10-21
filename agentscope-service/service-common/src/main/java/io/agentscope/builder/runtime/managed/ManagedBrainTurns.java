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
package io.agentscope.builder.runtime.managed;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.runtime.managed.ManagedMessageBus.Channel;
import io.agentscope.builder.runtime.managed.ManagedTurnPublisher.Command;
import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

/** A callback runs one active segment; Suspend retains the original FIFO head between segments. */
public final class ManagedBrainTurns implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ManagedBrainTurns.class);
    private static final Duration RETRY_DELAY = Duration.ofMillis(500);
    private static final Set<String> TERMINAL = Set.of("succeeded", "failed", "cancelled");
    private static final Set<String> WAITING = Set.of("waiting_tool", "waiting_input");

    @FunctionalInterface
    public interface Runner {
        void run(ManagedTurnExecution execution) throws Exception;
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
    private final Semaphore capacity;
    private final Set<Thread> activeThreads = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public ManagedBrainTurns(
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

    public ManagedBrainTurns(
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
        ManagedMessageBus.positiveMillis(turnSuspend);
        this.turnSuspend = turnSuspend;
        if (concurrency < 1)
            throw new IllegalArgumentException("Harness capacity must be positive");
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
        this.capacity = new Semaphore(concurrency);
    }

    /** Normal return ACKs this command; waiting throws the transport's non-failure Suspend signal. */
    public void consume(ManagedPushDelivery delivery, Runner runner) throws Exception {
        if (closed.get() || !bus.active(delivery))
            throw new ManagedTransportException("Brain callback is no longer active");
        ManagedSuspensions.Resume resume = null;
        if (delivery.channel() == Channel.TURN_RESUMES) {
            if (mapper.readTree(delivery.message().body()).has("notificationId")) {
                var notification =
                        mapper.readValue(delivery.message().body(), ManagedToolResume.class);
                if (!notification.notificationId().equals(delivery.message().id())
                        || !notification.messageGroup().equals(delivery.message().messageGroup()))
                    throw new IllegalArgumentException("Tool Resume identity differs");
                try {
                    if (!journal.transport().reconcileLegacyResume(notification, journal))
                        throw new ManagedDeliverySuspended(RETRY_DELAY);
                } catch (DataAccessException | ManagedTransportException unavailable) {
                    throw new ManagedDeliverySuspended(RETRY_DELAY);
                }
                return;
            } else resume = decodeResume(delivery);
        }
        var command =
                resume == null
                        ? decode(delivery)
                        : new Command(
                                resume.scope(), resume.turnId(), resume.publicationSequence());
        var scope = command.scope();
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
        if (resume == null && WAITING.contains(turn.status()))
            throw new ManagedDeliverySuspended(turnSuspend);
        if (!capacity.tryAcquire()) throw new ManagedDeliverySuspended(RETRY_DELAY);
        var thread = Thread.currentThread();
        activeThreads.add(thread);
        try {
            if (closed.get()) throw new ManagedTransportException("Brain is closed");
            try {
                execute(delivery, command, resume, runner);
            } catch (ManagedStateException changed) {
                // Admission rechecks under the Session lock; these races are ordinary waiting.
                if (resume != null && journal.suspensions().settled(resume)) return;
                if (!Set.of("turn_suspended", "missing_predecessor").contains(changed.code()))
                    throw changed;
                throw new ManagedDeliverySuspended(
                        resume == null && "turn_suspended".equals(changed.code())
                                ? turnSuspend
                                : RETRY_DELAY);
            } catch (RuntimeException unavailable) {
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

    public interface Handler extends ManagedConsumerLoop.Handler {
        void resume(ManagedSuspensions.Resume resume) throws Exception;
    }

    /** The tool completion already committed a durable continuation; no second MQ receive is needed. */
    public void resume(ManagedSuspensions.Resume resume, Runner runner) throws Exception {
        if (closed.get()) throw new ManagedTransportException("Brain is closed");
        if (journal.suspensions().settled(resume)) {
            journal.suspensions().applied(resume);
            return;
        }
        if (!capacity.tryAcquire()) return;
        var thread = Thread.currentThread();
        activeThreads.add(thread);
        try {
            if (closed.get()) throw new ManagedTransportException("Brain is closed");
            execute(
                    null,
                    new Command(resume.scope(), resume.turnId(), resume.publicationSequence()),
                    resume,
                    runner);
            journal.suspensions().applied(resume);
        } catch (ManagedDeliverySuspended busy) {
            // The original segment or another replica still owns the Session execution lease.
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
                    "brain.execution.started",
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
                    delivery == null ? "MCP_BATCH_COMPLETION" : "MQ_CALLBACK",
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
                    "brain.execution.failed",
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
                    "brain.execution.released",
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
            throw new IllegalArgumentException("Brain consumes Turns and Resumes");
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
        activeThreads.forEach(Thread::interrupt);
    }
}
