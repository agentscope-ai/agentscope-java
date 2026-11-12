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

import io.agentcore.managed.server.runtime.managed.ManagedHarnessRuntimeTurns.AsyncRunner;
import io.agentcore.managed.server.runtime.managed.ManagedHarnessRuntimeTurns.Runner;
import io.agentcore.managed.server.runtime.managed.ManagedSuspensions.Resume;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/** Capacity-bound durable Resume ownership; preparation waits for broker-confirmed ACK. */
final class ManagedResumeExecutions implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ManagedResumeExecutions.class);
    private final ManagedLeaseStore leases;
    private final ManagedAdmissionStore admission;
    private final ManagedJournalStore journal;
    private final ManagedRuntimeWriter commits;
    private final ScheduledExecutorService renewals;
    private final Duration ttl;
    private final Duration period;
    private final ManagedExecutionCapacity capacity;
    private final ExecutorService workers;
    private final Scheduler scheduler;
    private final Set<Attempt> attempts = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    // Preparation commonly arrives wrapped in ExecutionException. Log safe types/codes only;
    // raw transport messages can contain credentials or endpoints.
    static String failureCode(Throwable failure) {
        for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause())
            if (failure instanceof ManagedStateException state) return state.code();
        return null;
    }

    static String rootCauseType(Throwable failure) {
        for (int depth = 0; failure.getCause() != null && depth < 16; depth++)
            failure = failure.getCause();
        return failure.getClass().getSimpleName();
    }

    ManagedResumeExecutions(
            ManagedLeaseStore leases,
            ManagedAdmissionStore admission,
            ManagedJournalStore journal,
            ManagedRuntimeWriter commits,
            ScheduledExecutorService renewals,
            Duration ttl,
            Duration period,
            ManagedExecutionCapacity capacity,
            int concurrency) {
        this(
                leases,
                admission,
                journal,
                commits,
                renewals,
                ttl,
                period,
                capacity,
                executionWorkers(concurrency));
    }

    static ExecutorService executionWorkers(int concurrency) {
        if (concurrency < 0)
            throw new IllegalArgumentException("Execution limit cannot be negative");
        // Java 17: no fixed worker ceiling or task backlog when admission is explicitly disabled.
        if (concurrency == 0) return Executors.newCachedThreadPool();
        return new ThreadPoolExecutor(
                concurrency,
                concurrency,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(concurrency));
    }

    ManagedResumeExecutions(
            ManagedLeaseStore leases,
            ManagedAdmissionStore admission,
            ManagedJournalStore journal,
            ManagedRuntimeWriter commits,
            ScheduledExecutorService renewals,
            Duration ttl,
            Duration period,
            Semaphore capacity,
            ExecutorService workers) {
        this(
                leases,
                admission,
                journal,
                commits,
                renewals,
                ttl,
                period,
                ManagedExecutionCapacity.bounded(capacity),
                workers);
    }

    ManagedResumeExecutions(
            ManagedLeaseStore leases,
            ManagedAdmissionStore admission,
            ManagedJournalStore journal,
            ManagedRuntimeWriter commits,
            ScheduledExecutorService renewals,
            Duration ttl,
            Duration period,
            ManagedExecutionCapacity capacity,
            ExecutorService workers) {
        this.leases = leases;
        this.admission = admission;
        this.journal = journal;
        this.commits = commits;
        this.renewals = renewals;
        this.ttl = ttl;
        this.period = period;
        this.capacity = capacity;
        this.workers = workers;
        this.scheduler = Schedulers.fromExecutorService(workers);
    }

    private record ReadyExecution(
            ManagedScope scope, String turnId, long publicationSequence, Resume continuation) {
        String resumeId() {
            return continuation == null ? "initial_" + turnId : continuation.resumeId();
        }
    }

    void consumeInitial(
            ManagedScope scope,
            ManagedAdmissionStore.Turn turn,
            Runner runner,
            ManagedDeliveryHandoff handoff)
            throws Exception {
        consumeReady(
                new ReadyExecution(scope, turn.id(), turn.publicationSequence(), null),
                turn,
                runner,
                handoff);
    }

    void consume(Resume resume, ManagedAdmissionStore.Turn turn, Runner runner) throws Exception {
        consume(resume, turn, runner, ManagedDeliveryHandoff.direct());
    }

    void consume(
            Resume resume,
            ManagedAdmissionStore.Turn turn,
            Runner runner,
            ManagedDeliveryHandoff handoff)
            throws Exception {
        consumeReady(
                new ReadyExecution(
                        resume.scope(), resume.turnId(), resume.publicationSequence(), resume),
                turn,
                runner,
                handoff);
    }

    private void consumeReady(
            ReadyExecution resume,
            ManagedAdmissionStore.Turn turn,
            Runner runner,
            ManagedDeliveryHandoff handoff)
            throws Exception {
        if (closed.get()) throw new ManagedDeliverySuspended(Duration.ofMillis(500));
        // The original receipt may have been lost after the same durable claim was accepted.
        if ((resume.continuation() == null
                        ? turn.resumeId() == null
                        : resume.resumeId().equals(turn.resumeId()))
                && turn.executionToken() != null
                && leases.owns(
                        new ManagedLeaseStore.Lease(
                                resume.scope().key("execution"), turn.executionToken()))) return;
        Runnable release;
        if (handoff.reserved()) release = handoff.takeExecution();
        else {
            if (!capacity.tryAcquire()) throw new ManagedDeliverySuspended(Duration.ofMillis(500));
            release = capacity::release;
        }
        var attempt = new Attempt(resume, runner, handoff, release);
        attempts.add(attempt);
        boolean submitted = false;
        try {
            synchronized (attempt) {
                if (closed.get()) throw new ManagedDeliverySuspended(Duration.ofMillis(500));
                attempt.guard =
                        ManagedExecutionGuard.acquireContinuation(
                                        resume.scope(),
                                        leases,
                                        ttl,
                                        System::nanoTime,
                                        attempt::lost)
                                .orElseThrow(
                                        () -> new ManagedDeliverySuspended(Duration.ofMillis(500)));
                attempt.renewal =
                        renewals.scheduleWithFixedDelay(
                                () -> {
                                    try {
                                        attempt.guard.renew();
                                    } catch (RuntimeException lost) {
                                        /* Guard invalidates and interrupts its runner. */
                                    }
                                },
                                period.toMillis(),
                                period.toMillis(),
                                TimeUnit.MILLISECONDS);
                attempt.guard.check();
                attempt.epoch =
                        resume.continuation() == null
                                ? admission.activate(
                                        resume.scope(),
                                        resume.turnId(),
                                        resume.publicationSequence(),
                                        attempt.guard.token())
                                : admission.activateResume(
                                        resume.continuation(), attempt.guard.token());
                attempt.guard.check();
                // An accepted runnable owns the permit and lease; the callback no longer owns
                // either.
                workers.execute(attempt);
                submitted = true;
                ManagedRuntimeLog.info(
                        log,
                        "harness_runtime.resume.claimed",
                        resume.scope(),
                        resume.turnId(),
                        "resume_id",
                        resume.resumeId(),
                        "epoch",
                        attempt.epoch);
            }
        } finally {
            if (!submitted) attempt.finish();
        }
    }

    private final class Attempt implements Runnable {
        private final ReadyExecution resume;
        private final Runner runner;
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile ManagedExecutionGuard guard;
        private volatile ScheduledFuture<?> renewal;
        private volatile Thread thread;
        private volatile boolean interruptedByGuard;
        private long epoch;
        private volatile boolean boundaryCommitted;
        private final AtomicBoolean started = new AtomicBoolean();
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private final Sinks.One<Throwable> interruption = Sinks.one();
        private volatile ManagedTurnExecution asyncExecution;
        private volatile Disposable subscription;
        private volatile String asyncStage = "ack";
        private final ManagedDeliveryHandoff handoff;
        private final Runnable release;

        Attempt(
                ReadyExecution resume,
                Runner runner,
                ManagedDeliveryHandoff handoff,
                Runnable release) {
            this.handoff = handoff;
            this.release = release;
            this.resume = resume;
            this.runner = runner;
        }

        synchronized void lost() {
            interruptedByGuard = true;
            interruption.tryEmitValue(
                    new ManagedTransportException("Execution owner lost or runtime closed"));
            var executing = thread;
            if (executing != null) executing.interrupt();
        }

        @Override
        public void run() {
            started.set(true);
            if (runner instanceof AsyncRunner async) {
                runAsync(async);
                return;
            }
            synchronized (this) {
                thread = Thread.currentThread();
            }
            ManagedTurnExecution execution = null;
            String stage = "ack";
            try {
                if (closed.get() || finished.get()) return;
                handoff.await(
                        ttl.dividedBy(2).compareTo(Duration.ofSeconds(30)) < 0
                                ? ttl.dividedBy(2)
                                : Duration.ofSeconds(30));
                ManagedRuntimeLog.info(
                        log,
                        "harness_runtime.resume.ack_confirmed",
                        resume.scope(),
                        resume.turnId(),
                        "resume_id",
                        resume.resumeId(),
                        "epoch",
                        epoch);
                stage = "execution_claim";
                guard.check();
                // A terminal or newer suspension may have won before this worker started.
                if (resume.continuation() != null
                        && journal.suspensions().settled(resume.continuation())) return;
                admission.requireEpoch(resume.scope(), resume.turnId(), epoch);
                execution =
                        new ManagedTurnExecution(
                                resume.scope(),
                                resume.turnId(),
                                epoch,
                                guard,
                                admission,
                                journal,
                                commits);
                ManagedRuntimeLog.checkpoint(
                        log,
                        "harness_runtime.execution.started",
                        resume.scope(),
                        resume.turnId(),
                        execution.head().checkpoint(),
                        "epoch",
                        epoch,
                        "trigger",
                        "MQ_RESUME_CLAIM",
                        "resume_id",
                        resume.resumeId());
                stage = "runner";
                runner.run(execution);
                if (!execution.handedOff())
                    throw new ManagedStateException(
                            "turn_unfinished",
                            "Runner returned without committing a durable handoff");
            } catch (Exception | Error failure) {
                // Receipt may already be ACKed. The unfinished durable claim is repaired to MQ.
                ManagedRuntimeLog.info(
                        log,
                        "harness_runtime.resume.execution_pending",
                        resume.scope(),
                        resume.turnId(),
                        "resume_id",
                        resume.resumeId(),
                        "epoch",
                        epoch,
                        "failure_type",
                        failure.getClass().getSimpleName(),
                        "failure_code",
                        failureCode(failure),
                        "stage",
                        stage,
                        "cause_type",
                        failure.getCause() == null
                                ? null
                                : failure.getCause().getClass().getSimpleName(),
                        "root_cause_type",
                        rootCauseType(failure),
                        "guard_interrupted",
                        interruptedByGuard);
            } finally {
                boundaryCommitted = execution != null && execution.handedOff();
                synchronized (this) {
                    thread = null;
                }
                // The guard interrupted this attempt, not the cleanup transaction. Hikari's
                // interruptible borrow otherwise fails and leaves the claim until its deadline.
                // Detach first so a concurrent renewal cannot interrupt cleanup again.
                if (interruptedByGuard) Thread.interrupted();
                finish();
                if (interruptedByGuard) Thread.interrupted();
            }
        }

        private void runAsync(AsyncRunner async) {
            if (finished.get()) return;
            var stop = interruption.asMono();
            var timeout =
                    ttl.dividedBy(2).compareTo(Duration.ofSeconds(30)) < 0
                            ? ttl.dividedBy(2)
                            : Duration.ofSeconds(30);
            Mono<Void> acknowledged =
                    Mono.create(
                            sink ->
                                    handoff.whenAcknowledged(
                                            (ignored, error) -> {
                                                if (error == null) sink.success();
                                                else sink.error(error);
                                            }));
            subscription =
                    acknowledged
                            .timeout(timeout)
                            .takeUntilOther(stop.flatMap(Mono::error))
                            .publishOn(scheduler)
                            .then(
                                    Mono.defer(
                                            () -> {
                                                if (closed.get() || finished.get())
                                                    return Mono.error(
                                                            new ManagedTransportException(
                                                                    "Runtime closed"));
                                                ManagedRuntimeLog.info(
                                                        log,
                                                        "harness_runtime.resume.ack_confirmed",
                                                        resume.scope(),
                                                        resume.turnId(),
                                                        "resume_id",
                                                        resume.resumeId(),
                                                        "epoch",
                                                        epoch);
                                                asyncStage = "execution_claim";
                                                guard.check();
                                                if (resume.continuation() != null
                                                        && journal.suspensions()
                                                                .settled(resume.continuation()))
                                                    return Mono.empty();
                                                admission.requireEpoch(
                                                        resume.scope(), resume.turnId(), epoch);
                                                var execution =
                                                        new ManagedTurnExecution(
                                                                resume.scope(),
                                                                resume.turnId(),
                                                                epoch,
                                                                guard,
                                                                admission,
                                                                journal,
                                                                commits);
                                                asyncExecution = execution;
                                                ManagedRuntimeLog.checkpoint(
                                                        log,
                                                        "harness_runtime.execution.started",
                                                        resume.scope(),
                                                        resume.turnId(),
                                                        execution.head().checkpoint(),
                                                        "epoch",
                                                        epoch,
                                                        "trigger",
                                                        "MQ_RESUME_CLAIM",
                                                        "resume_id",
                                                        resume.resumeId());
                                                asyncStage = "runner";
                                                return async.runAsync(execution, scheduler, stop)
                                                        .then(
                                                                Mono.fromRunnable(
                                                                        () -> {
                                                                            if (!execution
                                                                                    .handedOff())
                                                                                throw new ManagedStateException(
                                                                                        "turn_unfinished",
                                                                                        "Runner"
                                                                                            + " returned"
                                                                                            + " without"
                                                                                            + " committing"
                                                                                            + " a durable"
                                                                                            + " handoff");
                                                                        }));
                                            }))
                            .materialize()
                            .publishOn(scheduler)
                            .subscribe(
                                    signal -> completeAsync(signal.getThrowable()),
                                    error -> {
                                        // Cleanup still needs a blocking worker if the execution
                                        // scheduler
                                        // rejects work.
                                        Schedulers.boundedElastic()
                                                .schedule(() -> completeAsync(error));
                                    });
        }

        private void completeAsync(Throwable failure) {
            try {
                if (failure != null)
                    ManagedRuntimeLog.info(
                            log,
                            "harness_runtime.resume.execution_pending",
                            resume.scope(),
                            resume.turnId(),
                            "resume_id",
                            resume.resumeId(),
                            "epoch",
                            epoch,
                            "failure_type",
                            failure.getClass().getSimpleName(),
                            "failure_code",
                            failureCode(failure),
                            "stage",
                            asyncStage,
                            "root_cause_type",
                            rootCauseType(failure),
                            "guard_interrupted",
                            interruptedByGuard);
                boundaryCommitted = asyncExecution != null && asyncExecution.handedOff();
            } finally {
                // A reactive cancellation never poisons the metadata cleanup thread's interrupt
                // bit.
                finish();
            }
        }

        void finish() {
            if (!finished.compareAndSet(false, true)) return;
            try {
                if (!boundaryCommitted && epoch > 0 && guard != null) {
                    try {
                        admission.abandonExecution(
                                resume.scope(), resume.turnId(), epoch, guard.token());
                    } catch (RuntimeException unavailable) {
                        ManagedRuntimeLog.info(
                                log,
                                "harness_runtime.resume.release_failed",
                                resume.scope(),
                                resume.turnId(),
                                "epoch",
                                epoch,
                                "failure_type",
                                unavailable.getClass().getSimpleName(),
                                "cause_type",
                                unavailable.getCause() == null
                                        ? null
                                        : unavailable.getCause().getClass().getSimpleName(),
                                "interrupted",
                                Thread.currentThread().isInterrupted());
                    }
                }
                if (renewal != null) renewal.cancel(false);
                if (guard != null) guard.close();
            } finally {
                attempts.remove(this);
                try {
                    release.run();
                } finally {
                    completion.complete(null);
                }
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        var stopping = Set.copyOf(attempts);
        for (var attempt : stopping) {
            synchronized (attempt) {
                attempt.lost();
                if (!attempt.started.get()) {
                    try {
                        attempt.finish();
                    } catch (RuntimeException unavailable) {
                        log.warn("Queued Resume release pending: {}", unavailable.toString());
                    }
                }
            }
        }
        // Let async cancellation and recorder cleanup finish before shutting down their executor.
        try {
            CompletableFuture.allOf(
                            stopping.stream()
                                    .map(attempt -> attempt.completion)
                                    .toArray(CompletableFuture[]::new))
                    .get(5, TimeUnit.SECONDS);
        } catch (Exception delayed) {
            // A non-cooperative provider is fenced exactly as a lost worker; repair remains MQ
            // driven.
            for (var attempt : stopping)
                if (!attempt.finished.get() && attempt.guard != null) {
                    try {
                        attempt.guard.close();
                        try {
                            if (attempt.subscription != null) attempt.subscription.dispose();
                        } finally {
                            attempt.finish();
                        }
                    } catch (RuntimeException unavailable) {
                        log.warn("Resume lease release pending: {}", unavailable.toString());
                    }
                }
            if (delayed instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            for (var queued : workers.shutdownNow())
                if (queued instanceof Attempt attempt) {
                    try {
                        attempt.finish();
                    } catch (RuntimeException unavailable) {
                        log.warn("Queued Resume release pending: {}", unavailable.toString());
                    }
                }
        }
    }
}
