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

import io.agentcore.managed.server.runtime.managed.ManagedJournalStore.ToolClaim;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore.ToolTask;
import io.agentcore.managed.server.runtime.managed.ManagedToolExecutor.Outcome;
import io.agentcore.managed.server.runtime.managed.ManagedToolExecutor.State;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One leased execution; only short transitions occupy blocking workers. */
final class ManagedAsyncToolExecution {
    private static final Logger log = LoggerFactory.getLogger(ManagedAsyncToolExecution.class);
    private final ManagedScope scope;
    private final String taskId;
    private ManagedToolExecutor executor;
    private final Supplier<ManagedToolExecutor> provider;
    private final ManagedDeliveryHandoff handoff;
    private volatile boolean acknowledged;
    private boolean initialized;
    private ToolClaim claim;
    private boolean startAttempted;
    private ScheduledFuture<?> acknowledgementTimeout;
    private final ManagedLeaseStore leases;
    private final ManagedToolWorkStore journal;
    private final ManagedToolResultPublisher results;
    private final ScheduledExecutorService timers;
    private final Executor workers;
    private final Duration ttl, renewalPeriod, poll;
    private final CompletableFuture<Boolean> done = new CompletableFuture<>();
    private volatile boolean valid = true;
    private volatile boolean stopRequested;
    private volatile long deadline;
    private volatile long collectorDeadline = Long.MAX_VALUE;
    private ManagedLeaseStore.Lease lease;
    private ToolTask task;
    private ScheduledFuture<?> renewal, nextPoll;
    private CompletionStage<?> notification;
    private boolean notificationReady;
    private boolean queued, running, dirty, stopped;

    ManagedAsyncToolExecution(
            ManagedScope scope,
            String taskId,
            Supplier<ManagedToolExecutor> provider,
            ManagedDeliveryHandoff handoff,
            ManagedLeaseStore leases,
            ManagedToolWorkStore journal,
            ManagedToolResultPublisher results,
            ScheduledExecutorService timers,
            Executor workers,
            Duration ttl,
            Duration renewalPeriod,
            Duration poll) {
        this.scope = scope;
        this.taskId = taskId;
        this.provider = provider;
        this.handoff = handoff;
        this.leases = leases;
        this.journal = journal;
        this.results = results;
        this.timers = timers;
        this.workers = workers;
        this.ttl = ttl;
        this.renewalPeriod = renewalPeriod;
        this.poll = poll;
    }

    /** Called during the short callback: no provider/configuration/MCP access. */
    boolean claim() {
        try {
            if (lease != null) return true;
            long before = System.nanoTime();
            var acquired = leases.acquire(scope.key("tool:" + taskId), ttl);
            if (acquired.isEmpty()) {
                finish(false, null);
                return false;
            }
            lease = acquired.get();
            deadline = before + ttl.toNanos();
            check();
            claim = journal.claimTool(scope, taskId, lease.token());
            var lifetime = journal.toolOwnerLifetime();
            if (lifetime != null) collectorDeadline = System.nanoTime() + lifetime.toNanos();
            task = claim.task();
            ManagedRuntimeLog.tool(
                    log,
                    "tool_worker.tool.execution.claimed",
                    scope,
                    task,
                    "first_launch",
                    claim.firstLaunch());
            journal.toolHeartbeat(scope, taskId, lease.token(), System.currentTimeMillis());
            return true;
        } catch (RuntimeException failure) {
            finish(false, failure);
            throw failure;
        }
    }

    CompletableFuture<Boolean> start() {
        done.whenComplete(
                (value, error) -> {
                    if (done.isCancelled()) signal();
                });
        renewal =
                timers.scheduleWithFixedDelay(
                        this::renew,
                        renewalPeriod.toMillis(),
                        renewalPeriod.toMillis(),
                        TimeUnit.MILLISECONDS);
        acknowledgementTimeout =
                timers.schedule(
                        () -> {
                            if (!acknowledged) stop();
                        },
                        Math.min(30_000, Math.max(1, ttl.toMillis() / 2)),
                        TimeUnit.MILLISECONDS);
        handoff.whenAcknowledged(
                (ignored, failure) -> {
                    if (failure != null) stop();
                    else {
                        ManagedRuntimeLog.tool(log, "tool_worker.tool.ack_confirmed", scope, task);
                        acknowledged = true;
                        acknowledgementTimeout.cancel(false);
                        signal();
                    }
                });
        return done;
    }

    void stop() {
        stopRequested = true;
        signal();
    }

    CompletableFuture<Boolean> finished() {
        return done;
    }

    synchronized void signal() {
        if (stopped) return;
        dirty = true;
        if (queued || running) return;
        queued = true;
        try {
            workers.execute(this::run);
        } catch (RejectedExecutionException busy) {
            queued = false;
            schedulePoll();
        }
    }

    private void run() {
        synchronized (this) {
            queued = false;
            if (stopped) return;
            running = true;
            dirty = false;
        }
        try {
            if (stopRequested || done.isCancelled()) finish(false, null);
            else step();
        } catch (RuntimeException failure) {
            if (lease != null && (claim == null || !claim.firstLaunch() || startAttempted)) {
                try {
                    journal.markToolUnknown(scope, taskId, lease.token());
                } catch (RuntimeException recording) {
                    failure.addSuppressed(recording);
                }
            }
            finish(false, failure);
        } finally {
            synchronized (this) {
                running = false;
                if (dirty && !stopped) signal();
            }
        }
    }

    private void step() {
        if (!acknowledged) return;
        if (!initialized) {
            // Provider preparation may contact an unavailable sandbox. A confirmed unstarted
            // cancellation or stored result must not depend on that external connection.
            if (settleBeforeStart()) return;
            check();
            long prepareStarted = System.nanoTime();
            ManagedRuntimeLog.tool(log, "tool_worker.tool.prepare.started", scope, task);
            boolean prepared = false;
            try {
                executor = provider.get();
                prepared = executor != null;
            } finally {
                ManagedRuntimeLog.tool(
                        log,
                        "tool_worker.tool.prepare.finished",
                        scope,
                        task,
                        "success",
                        prepared,
                        "duration_ms",
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - prepareStarted));
            }
            if (executor == null) {
                finish(false, null);
                return;
            }
            initialized = true;
            check();
            if (!begin()) return;
        }
        check();
        // A pending local Future is authoritative for liveness in this process. Only control
        // wakeups/renewals check cancellation; no per-second result/batch SQL polling is needed.
        boolean waiting;
        synchronized (this) {
            waiting = notification != null && !notificationReady;
        }
        if (waiting) {
            if (!journal.cancelRequested(scope, task)) return;
            check();
            var outcome = executor.cancel(scope, task);
            if (outcome.terminal()) capture(outcome);
            return;
        }
        synchronized (this) {
            notification = null;
            notificationReady = false;
        }
        var captured = journal.stagedToolResult(scope, taskId);
        if (captured.isPresent()) {
            if (collect()) {
                results.publish(captured.get());
                finish(true, null);
            } else schedulePoll();
            return;
        }
        var outcome = executor.query(scope, task);
        boolean cancelling = journal.cancelRequested(scope, task);
        if (!outcome.terminal() && cancelling) {
            check();
            outcome = executor.cancel(scope, task);
        }
        if (outcome.terminal()) {
            capture(outcome);
            return;
        }
        if (outcome.state() == State.NOT_FOUND && !cancelling && executor.idempotentStart()) {
            check();
            startAttempted = true;
            String handle = executor.start(scope, task);
            check();
            task = journal.recordToolHandle(scope, taskId, lease.token(), handle);
        } else if (outcome.state() == State.UNKNOWN || outcome.state() == State.NOT_FOUND) {
            journal.markToolUnknown(scope, taskId, lease.token());
            finish(false, null);
            return;
        }
        if (!collect()) {
            schedulePoll();
            return;
        }
        if (!executor.waitForCompletion(task)) {
            finish(false, null);
            return;
        }
        var completion = executor.completion(scope, task);
        if (completion == null) {
            schedulePoll();
            return;
        }
        synchronized (this) {
            notification = completion;
            notificationReady = false;
        }
        completion.whenComplete(
                (value, failure) -> {
                    synchronized (this) {
                        if (stopped || notification != completion) return;
                        notificationReady = true;
                    }
                    signal();
                });
    }

    private boolean settleBeforeStart() {
        check();
        if (results.publishStored(scope, taskId) || task.result() != null) {
            finish(true, null);
            return true;
        }
        if (claim.firstLaunch() && !startAttempted && journal.cancelRequested(scope, task)) {
            check();
            results.publish(
                    new ManagedToolResult(
                            scope,
                            taskId,
                            task.executionId(),
                            "cancelled",
                            ManagedToolResult.cancelledPayload(task.name(), task.arguments())));
            finish(true, null);
            return true;
        }
        return false;
    }

    private boolean begin() {
        // Recheck after preparation: cancellation can arrive while the provider is connecting.
        if (settleBeforeStart()) return false;
        check();
        ManagedRuntimeLog.tool(
                log,
                "tool_worker.tool.execution.prepared",
                scope,
                task,
                "first_launch",
                claim.firstLaunch());
        if (claim.firstLaunch()) {
            startAttempted = true;
            String handle = executor.start(scope, task);
            check();
            task = journal.recordToolHandle(scope, taskId, lease.token(), handle);
        }
        ManagedRuntimeLog.tool(
                log,
                claim.firstLaunch()
                        ? "tool_worker.tool.started"
                        : "tool_worker.tool.querying_existing",
                scope,
                task);
        journal.toolEvents().open(scope, task, lease.token(), executor.streamsOutput(task));
        return true;
    }

    private void capture(Outcome outcome) {
        // A confirmed external result survives loss of an execution lease. The Store validates
        // its immutable execution identity; only output collection requires current ownership.
        results.capture(
                new ManagedToolResult(
                        scope,
                        taskId,
                        task.executionId(),
                        outcome.state().name().toLowerCase(Locale.ROOT),
                        outcome.result()));
        synchronized (this) {
            notification = null;
            notificationReady = false;
        }
        signal();
    }

    private boolean collect() {
        for (int i = 0; i < 4; i++) {
            var cursor = journal.toolEvents().cursor(scope, taskId).orElseThrow();
            if (cursor.captureComplete()) return true;
            check();
            var page = executor.output(scope, task, cursor.offset());
            check();
            var next = journal.toolEvents().capture(scope, task, lease.token(), page);
            results.flush(scope, taskId);
            if (next.captureComplete()) return true;
            if (next.offset() == cursor.offset()) return false;
        }
        return false;
    }

    private void renew() {
        synchronized (this) {
            if (stopped) return;
        }
        long before = System.nanoTime();
        try {
            if (!valid
                    || collectorDeadline - before <= 0
                    || deadline - before <= 0
                    || !leases.renew(lease, ttl))
                throw new ManagedTransportException("Tool token expired");
            deadline = before + ttl.toNanos();
            journal.toolHeartbeat(scope, taskId, lease.token(), System.currentTimeMillis());
        } catch (RuntimeException lost) {
            valid = false;
        }
        signal();
    }

    private synchronized void schedulePoll() {
        if (stopped || (nextPoll != null && !nextPoll.isDone())) return;
        try {
            nextPoll =
                    timers.schedule(
                            () -> {
                                synchronized (this) {
                                    nextPoll = null;
                                }
                                signal();
                            },
                            poll.toMillis(),
                            TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException shutdown) {
            // No external execution is retried here. Its persisted owner expires for recovery.
            stopped = true;
            done.completeExceptionally(shutdown);
        }
    }

    private void check() {
        if (!valid
                || collectorDeadline - System.nanoTime() <= 0
                || deadline - System.nanoTime() <= 0
                || !leases.owns(lease))
            throw new ManagedTransportException("Tool execution ownership was lost");
    }

    private void finish(boolean success, RuntimeException failure) {
        synchronized (this) {
            if (stopped) return;
            stopped = true;
            notification = null;
            if (renewal != null) renewal.cancel(false);
            if (acknowledgementTimeout != null) acknowledgementTimeout.cancel(false);
            if (nextPoll != null) nextPoll.cancel(false);
        }
        if (task != null && claim != null && claim.firstLaunch() && !startAttempted) {
            try {
                journal.unstarted(scope, task);
            } catch (RuntimeException release) {
                if (failure == null) failure = release;
                else failure.addSuppressed(release);
            }
        }
        valid = false;
        if (lease != null) {
            try {
                leases.release(lease);
            } catch (RuntimeException release) {
                if (failure == null) failure = release;
                else failure.addSuppressed(release);
            }
        }
        if (task != null)
            ManagedRuntimeLog.tool(
                    log,
                    "tool_worker.tool.execution.released",
                    scope,
                    task,
                    "reason",
                    stopRequested || done.isCancelled()
                            ? "stopped"
                            : success ? "completed" : "recovery_pending");
        if (failure != null) done.completeExceptionally(failure);
        else done.complete(success);
    }
}
