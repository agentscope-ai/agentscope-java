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
package io.agentcore.managed.server.runtime.managed;

import io.agentcore.managed.server.runtime.managed.ManagedLifecycles.Command;
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

/** Bounded administrative cleanup, independent of the event and long-tool execution pools. */
public final class ManagedLifecycleLoop implements AutoCloseable {
    @FunctionalInterface
    public interface Cleaner {
        void cleanup(Command command, Runnable check);
    }

    private final ManagedLifecycles store;
    private final ManagedLeaseStore leases;
    private final Cleaner cleaner;
    private final BiConsumer<Command, RuntimeException> errors;
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers;
    private final ScheduledExecutorService scheduler;
    private final Semaphore capacity;
    private final Duration ttl;
    private final Duration renewal;

    public ManagedLifecycleLoop(
            ManagedLifecycles store,
            ManagedLeaseStore leases,
            Cleaner cleaner,
            int concurrency,
            Duration ttl,
            Duration renewal,
            BiConsumer<Command, RuntimeException> errors) {
        if (concurrency < 1
                || concurrency > 32
                || renewal.isZero()
                || renewal.isNegative()
                || renewal.compareTo(ttl.dividedBy(3)) > 0)
            throw new IllegalArgumentException("Invalid lifecycle worker limits");
        ManagedMessageBus.positiveMillis(ttl);
        this.store = store;
        this.leases = leases;
        this.cleaner = cleaner;
        this.errors = errors;
        this.ttl = ttl;
        this.renewal = renewal;
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
                } catch (RuntimeException rejected) {
                    active.remove(command.id());
                    capacity.release();
                    throw rejected;
                }
            }
        } catch (RuntimeException failed) {
            errors.accept(null, failed);
        }
    }

    public boolean execute(Command command) {
        long before = System.nanoTime();
        var acquired = leases.acquire(command.scope().key("lifecycle-cleanup"), ttl);
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
                                long start = System.nanoTime();
                                if (!valid.get()
                                        || deadline.get() - start <= 0
                                        || !leases.renew(lease, ttl))
                                    throw new ManagedTransportException("Cleanup token lost");
                                deadline.set(start + ttl.toNanos());
                            } catch (RuntimeException unavailable) {
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
                        throw new ManagedTransportException("Cleanup ownership lost");
                };
        RuntimeException primary = null;
        try {
            check.run();
            if (!store.beginCleanup(command)) return false;
            cleaner.cleanup(command, check);
            check.run();
            store.complete(command);
            return true;
        } catch (RuntimeException failure) {
            primary = failure;
            try {
                store.failed(command, "cleanup_unconfirmed");
            } catch (RuntimeException recording) {
                failure.addSuppressed(recording);
            }
            throw failure;
        } finally {
            valid.set(false);
            keepalive.cancel(false);
            try {
                leases.release(lease);
            } catch (RuntimeException releasing) {
                if (primary == null) throw releasing;
                primary.addSuppressed(releasing);
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
