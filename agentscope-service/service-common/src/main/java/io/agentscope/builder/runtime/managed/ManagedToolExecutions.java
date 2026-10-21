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

import io.agentscope.builder.runtime.managed.ManagedJournalStore.ToolTask;
import io.agentscope.builder.runtime.managed.ManagedToolExecutor.Outcome;
import io.agentscope.builder.runtime.managed.ManagedToolExecutor.State;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Long tools occupy a separate bounded pool; only ephemeral Redis tokens are periodically renewed. */
public final class ManagedToolExecutions {
    private static final Logger log = LoggerFactory.getLogger(ManagedToolExecutions.class);
    private final ManagedLeaseStore leases;
    private final ManagedInputInbox inputs;
    private final ManagedToolWorkStore journal;
    private final ManagedToolResultPublisher results;
    private final ScheduledExecutorService renewals;
    private final Duration ttl;
    private final Duration renewalPeriod;
    private final Duration poll;

    public ManagedToolExecutions(
            ManagedLeaseStore leases,
            ManagedInputInbox inputs,
            ManagedToolWorkStore journal,
            ManagedToolResultPublisher results,
            ScheduledExecutorService renewals,
            Duration ttl,
            Duration renewalPeriod,
            Duration poll) {
        ManagedMessageBus.positiveMillis(ttl);
        ManagedMessageBus.positiveMillis(renewalPeriod);
        ManagedMessageBus.positiveMillis(poll);
        if (renewalPeriod.compareTo(ttl.dividedBy(3)) > 0)
            throw new IllegalArgumentException("Tool renewal period leaves insufficient margin");
        this.leases = leases;
        this.inputs = inputs;
        this.journal = journal;
        this.results = results;
        this.renewals = renewals;
        this.ttl = ttl;
        this.renewalPeriod = renewalPeriod;
        this.poll = poll;
    }

    /** Returns false when another Hands worker owns the task or the external outcome is unknown. */
    public boolean execute(ManagedScope scope, String taskId, ManagedToolExecutor executor) {
        if (results.publishStored(scope, taskId)) return true;
        long before = System.nanoTime();
        var acquired = leases.acquire(scope.key("tool:" + taskId), ttl);
        if (acquired.isEmpty()) return false;
        var lease = acquired.get();
        var valid = new AtomicBoolean(true);
        var deadline = new AtomicLong(before + ttl.toNanos());
        var workThread = Thread.currentThread();
        var interruptedByLease = new AtomicBoolean();
        var renewal =
                renewals.scheduleWithFixedDelay(
                        () -> {
                            long start = System.nanoTime();
                            try {
                                if (!valid.get()
                                        || deadline.get() - start <= 0
                                        || !leases.renew(lease, ttl))
                                    throw new ManagedTransportException("Tool token expired");
                                deadline.set(start + ttl.toNanos());
                                journal.toolHeartbeat(
                                        scope, taskId, lease.token(), System.currentTimeMillis());
                            } catch (RuntimeException lost) {
                                if (valid.compareAndSet(true, false)) {
                                    interruptedByLease.set(true);
                                    workThread.interrupt();
                                }
                            }
                        },
                        renewalPeriod.toMillis(),
                        renewalPeriod.toMillis(),
                        TimeUnit.MILLISECONDS);
        ToolTask task = null;
        String releaseReason = "aborted";
        try {
            check(lease, valid, deadline);
            var claim = journal.claimTool(scope, taskId, lease.token());
            task = claim.task();
            journal.toolHeartbeat(scope, taskId, lease.token(), System.currentTimeMillis());
            ManagedRuntimeLog.tool(
                    log,
                    "hands.tool.execution.claimed",
                    scope,
                    task,
                    "first_launch",
                    claim.firstLaunch(),
                    "prior_status",
                    task.status());
            if (task.result() != null) {
                releaseReason = "already_committed";
                return true;
            }
            if (results.publishStored(scope, taskId)) {
                releaseReason = "stored_result_published";
                return true;
            }
            boolean durableCancel = journal.cancelRequested(scope, task);
            check(lease, valid, deadline);
            if (claim.firstLaunch()) {
                if ((durableCancel
                        || (!(journal instanceof ManagedHandsStore)
                                && inputs.cancelled(scope, task.turnId())))) {
                    var stopped =
                            new ManagedToolResult(
                                    scope,
                                    taskId,
                                    task.executionId(),
                                    "cancelled",
                                    cancelledResult(task));
                    results.publish(stopped);
                    releaseReason = "cancelled_before_start";
                    return true;
                }
                ManagedRuntimeLog.tool(
                        log, "hands.tool.start.requested", scope, task, "mode", "first_launch");
                String handle = executor.start(scope, task);
                check(lease, valid, deadline);
                task = journal.recordToolHandle(scope, taskId, lease.token(), handle);
                ManagedRuntimeLog.tool(log, "hands.tool.started", scope, task);
            } else ManagedRuntimeLog.tool(log, "hands.tool.querying_existing", scope, task);
            journal.toolEvents().open(scope, task, lease.token(), executor.streamsOutput(task));
            while (true) {
                check(lease, valid, deadline);
                var captured = journal.stagedToolResult(scope, taskId);
                if (captured.isPresent()) {
                    if (collect(scope, task, lease, valid, deadline, executor)) {
                        results.publish(captured.get());
                        releaseReason = "result_published";
                        return true;
                    }
                    continue;
                }
                Outcome outcome = executor.query(scope, task);
                durableCancel = journal.cancelRequested(scope, task);
                boolean cancelling =
                        durableCancel
                                || (!(journal instanceof ManagedHandsStore)
                                        && inputs.cancelled(scope, task.turnId()));
                // A missing/unknown observation does not prevent asking the original execution
                // to stop. Never restart it after cancellation, even with an idempotent provider.
                if (!outcome.terminal() && cancelling) {
                    check(lease, valid, deadline);
                    outcome = executor.cancel(scope, task);
                }
                if (outcome.state() == State.NOT_FOUND) {
                    if (cancelling || !executor.idempotentStart()) {
                        journal.markToolUnknown(scope, taskId, lease.token());
                        releaseReason = "outcome_unknown";
                        ManagedRuntimeLog.tool(
                                log,
                                "hands.tool.outcome_unknown",
                                scope,
                                task,
                                "external_state",
                                outcome.state().name());
                        return false;
                    }
                    check(lease, valid, deadline);
                    ManagedRuntimeLog.tool(
                            log,
                            "hands.tool.start.requested",
                            scope,
                            task,
                            "mode",
                            "stable_identity_retry");
                    String handle = executor.start(scope, task);
                    check(lease, valid, deadline);
                    task = journal.recordToolHandle(scope, taskId, lease.token(), handle);
                    ManagedRuntimeLog.tool(
                            log,
                            "hands.tool.started",
                            scope,
                            task,
                            "mode",
                            "stable_identity_retry");
                } else if (outcome.state() == State.UNKNOWN) {
                    journal.markToolUnknown(scope, taskId, lease.token());
                    releaseReason = "outcome_unknown";
                    ManagedRuntimeLog.tool(
                            log,
                            "hands.tool.outcome_unknown",
                            scope,
                            task,
                            "external_state",
                            outcome.state().name());
                    return false;
                } else {
                    if (outcome.terminal()) {
                        // A confirmed original result is a fact even when its execution token just
                        // expired.
                        var result =
                                new ManagedToolResult(
                                        scope,
                                        taskId,
                                        task.executionId(),
                                        outcome.state().name().toLowerCase(Locale.ROOT),
                                        outcome.result());
                        results.capture(result);
                        ManagedRuntimeLog.tool(
                                log,
                                "hands.tool.result.captured",
                                scope,
                                task,
                                "status",
                                result.status());
                        continue;
                    }
                }
                collect(scope, task, lease, valid, deadline, executor);
                if (!executor.waitForCompletion(task)) {
                    releaseReason = "yielded";
                    return false;
                }
                LockSupport.parkNanos(poll.toNanos());
                if (Thread.currentThread().isInterrupted())
                    throw new ManagedTransportException("Tool worker stopped");
            }
        } catch (RuntimeException failure) {
            try {
                journal.markToolUnknown(scope, taskId, lease.token());
            } catch (RuntimeException recording) {
                failure.addSuppressed(recording);
            }
            throw failure;
        } finally {
            valid.set(false);
            renewal.cancel(false);
            try {
                boolean released = leases.release(lease);
                if (task != null)
                    ManagedRuntimeLog.tool(
                            log,
                            "hands.tool.execution.released",
                            scope,
                            task,
                            "reason",
                            releaseReason,
                            "guard_released",
                            released);
            } finally {
                if (interruptedByLease.get()) Thread.interrupted();
            }
        }
    }

    private boolean collect(
            ManagedScope scope,
            ManagedJournalStore.ToolTask task,
            ManagedLeaseStore.Lease lease,
            AtomicBoolean valid,
            AtomicLong deadline,
            ManagedToolExecutor executor) {
        for (int i = 0; i < 4; i++) {
            var cursor = journal.toolEvents().cursor(scope, task.id()).orElseThrow();
            if (cursor.captureComplete()) return true;
            check(lease, valid, deadline);
            var page = executor.output(scope, task, cursor.offset());
            check(lease, valid, deadline);
            var next = journal.toolEvents().capture(scope, task, lease.token(), page);
            results.flush(scope, task.id());
            if (next.captureComplete()) return true;
            if (next.offset() == cursor.offset()) {
                LockSupport.parkNanos(poll.toNanos());
                if (Thread.currentThread().isInterrupted())
                    throw new ManagedTransportException("Tool collector stopped");
                return false;
            }
        }
        return false;
    }

    private static String cancelledResult(ManagedJournalStore.ToolTask task) {
        return ManagedToolResult.cancelledPayload(task.name(), task.arguments());
    }

    private void check(ManagedLeaseStore.Lease lease, AtomicBoolean valid, AtomicLong deadline) {
        if (!valid.get() || deadline.get() - System.nanoTime() <= 0 || !leases.owns(lease))
            throw new ManagedTransportException("Tool execution ownership was lost");
    }
}
