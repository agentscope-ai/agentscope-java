/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.runtime.managed;

import io.agentscope.builder.runtime.managed.ManagedCheckpointOperations.Operation;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Hands administrative work; short DB intents plus a renewable Redis token, never DB leases. */
public final class ManagedCheckpointLoop implements AutoCloseable {
    private final ManagedCheckpointOperations store;
    private final ManagedLeaseStore leases;
    private final Function<ManagedScope, ManagedToolExecutor> providers;
    private final Function<ManagedScope, String> owners;
    private final BiConsumer<Operation, RuntimeException> errors;
    private final Duration ttl;
    private final Duration renewal;
    private final ExecutorService workers;
    private final ScheduledExecutorService scheduler;
    private final Semaphore capacity;
    private final Set<String> active = ConcurrentHashMap.newKeySet();

    public ManagedCheckpointLoop(
            ManagedCheckpointOperations store,
            ManagedLeaseStore leases,
            Function<ManagedScope, ManagedToolExecutor> providers,
            Function<ManagedScope, String> owners,
            int concurrency,
            Duration ttl,
            Duration renewal,
            BiConsumer<Operation, RuntimeException> errors) {
        if (concurrency < 1
                || concurrency > 16
                || renewal.isNegative()
                || renewal.isZero()
                || renewal.compareTo(ttl.dividedBy(3)) > 0)
            throw new IllegalArgumentException("Invalid checkpoint worker limits");
        ManagedMessageBus.positiveMillis(ttl);
        this.store = store;
        this.leases = leases;
        this.providers = providers;
        this.owners = owners;
        this.ttl = ttl;
        this.renewal = renewal;
        this.errors = errors;
        workers = Executors.newFixedThreadPool(concurrency);
        scheduler = Executors.newScheduledThreadPool(concurrency + 1);
        capacity = new Semaphore(concurrency);
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(this::dispatch, 0, 1000, TimeUnit.MILLISECONDS);
    }

    private void dispatch() {
        try {
            for (var command : store.pending(64)) {
                if (!capacity.tryAcquire()) return;
                if (!active.add(command.id())) {
                    capacity.release();
                    continue;
                }
                try {
                    workers.submit(
                            () -> {
                                try {
                                    execute(command);
                                } catch (RuntimeException failure) {
                                    errors.accept(command, failure);
                                } finally {
                                    active.remove(command.id());
                                    capacity.release();
                                }
                            });
                } catch (RuntimeException stopped) {
                    active.remove(command.id());
                    capacity.release();
                    throw stopped;
                }
            }
        } catch (RuntimeException unavailable) {
            errors.accept(null, unavailable);
        }
    }

    /** One bounded phase per pass. The next replica reconstructs everything from durable facts. */
    public boolean execute(Operation command) {
        long before = System.nanoTime();
        var acquired = leases.acquire(command.source().key("checkpoint:" + command.id()), ttl);
        if (acquired.isEmpty()) return false;
        var lease = acquired.get();
        var valid = new AtomicBoolean(true);
        var deadline = new AtomicLong(before + ttl.toNanos());
        var interruptedByLease = new AtomicBoolean();
        Thread worker = Thread.currentThread();
        var keepalive =
                scheduler.scheduleWithFixedDelay(
                        () -> {
                            try {
                                long begin = System.nanoTime();
                                if (!valid.get()
                                        || deadline.get() - begin <= 0
                                        || !leases.renew(lease, ttl))
                                    throw new ManagedTransportException("Checkpoint token lost");
                                deadline.set(begin + ttl.toNanos());
                            } catch (RuntimeException lost) {
                                if (valid.compareAndSet(true, false)) {
                                    interruptedByLease.set(true);
                                    worker.interrupt();
                                }
                            }
                        },
                        renewal.toMillis(),
                        renewal.toMillis(),
                        TimeUnit.MILLISECONDS);
        Runnable check =
                () -> {
                    if (!valid.get()
                            || deadline.get() - System.nanoTime() <= 0
                            || Thread.currentThread().isInterrupted()
                            || !leases.owns(lease))
                        throw new ManagedTransportException("Checkpoint ownership lost");
                };
        try {
            check.run();
            var receipt = store.get(command.source(), command.id());
            if (receipt.status().equals("validating"))
                receipt = store.validate(command.source(), command.id());
            check.run();
            if (receipt.status().equals("workspace_ready")) {
                store.commitBaseline(
                        command.source(),
                        command.id(),
                        command.actor(),
                        owners.apply(command.target()));
                return true;
            }
            if (!Set.of("validated", "snapshotting", "restoring").contains(receipt.status()))
                return false;
            var task = store.workspaceTask(command.source(), command.id());
            check.run();
            if (task == null) {
                if (store.get(command.source(), command.id()).status().equals("validated"))
                    store.commitBaseline(
                            command.source(),
                            command.id(),
                            command.actor(),
                            owners.apply(command.target()));
                return true;
            }
            var executor = providers.apply(task.sandbox());
            if (executor == null) return false; // Requires the outbound self-hosted Worker path.
            check.run();
            if (task.status().equals("prepared") && store.startWorkspaceTask(task)) {
                check.run();
                String handle = executor.start(task.sandbox(), task.task());
                if (!task.task().executionId().equals(handle))
                    throw new ManagedTransportException("Workspace execution identity differs");
            }
            check.run();
            var outcome = executor.query(task.sandbox(), task.task());
            check.run();
            if (outcome.state() != ManagedToolExecutor.State.RUNNING)
                store.workspaceResult(task, outcome);
            return true;
        } finally {
            valid.set(false);
            keepalive.cancel(false);
            try {
                leases.release(lease);
            } finally {
                if (interruptedByLease.get()) Thread.interrupted();
            }
        }
    }

    @Override
    public void close() {
        workers.shutdownNow();
        scheduler.shutdownNow();
    }
}
