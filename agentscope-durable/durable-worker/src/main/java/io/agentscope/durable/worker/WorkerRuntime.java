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

package io.agentscope.durable.worker;

import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.ExecutionResult;
import io.agentscope.durable.DurableTypes.ExecutionState;
import io.agentscope.durable.DurableTypes.OutputPage;
import io.agentscope.durable.ExecutionRecovery;
import io.agentscope.durable.Executor;
import io.agentscope.durable.store.RecordStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Hands-side execution and recovery manager, embedded locally or hosted in a separate process.
 * Providers and injected storage remain caller-owned; construction does not start the polling
 * loop. A colocated Brain connects through {@link #localExecutor()} rather than treating this
 * lifecycle component as an {@link Executor}.
 */
public final class WorkerRuntime implements AutoCloseable {
    private final ExecutionStore store;
    private final Function<ExecutionRequest, Executor> resolver;
    private final Duration lease;
    private final Duration poll;
    private final Duration retryMaximum;
    private final int parallelism;
    private final int recoveryParallelism;
    private final Consumer<Throwable> errors;
    private final LocalWorkerExecutor localExecutor;
    private final String owner = UUID.randomUUID().toString();
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private final Set<String> recovering = ConcurrentHashMap.newKeySet();
    private final Object lifecycle = new Object();
    private final ExecutorService workers;
    private final ExecutorService recoveries;
    private final Set<Thread> ownedThreads = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, r -> daemon(r, "durable-worker-timer"));
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong leaseFailures = new AtomicLong();
    private volatile long lastFailureAt;
    private volatile long lastScanAt;

    public record Status(
            boolean started,
            boolean accepting,
            boolean closed,
            int activeStarts,
            int activeRecoveries,
            int startCapacity,
            int recoveryCapacity,
            long failures,
            long leaseFailures,
            long lastFailureAt,
            long lastScanAt) {}

    public static Builder builder() {
        return new Builder();
    }

    public WorkerRuntime(ExecutionStore store, Map<String, Executor> providers) {
        this(builder().executionStore(store).providers(providers));
    }

    public WorkerRuntime(
            ExecutionStore store,
            Map<String, Executor> providers,
            Duration lease,
            Duration poll,
            int parallelism,
            Consumer<Throwable> errors) {
        this(
                builder()
                        .executionStore(store)
                        .providers(providers)
                        .leaseDuration(lease)
                        .pollInterval(poll)
                        .parallelism(parallelism)
                        .onError(errors));
    }

    /** Resolve a frozen host target before recording any external dispatch attempt. */
    public WorkerRuntime(
            ExecutionStore store,
            Function<ExecutionRequest, Executor> resolver,
            Duration lease,
            Duration poll,
            int parallelism,
            Consumer<Throwable> errors) {
        this(
                builder()
                        .executionStore(store)
                        .resolver(resolver)
                        .leaseDuration(lease)
                        .pollInterval(poll)
                        .parallelism(parallelism)
                        .onError(errors));
    }

    private WorkerRuntime(Builder builder) {
        store = Objects.requireNonNull(builder.store, "store");
        resolver = Objects.requireNonNull(builder.resolver, "resolver");
        lease = builder.lease;
        poll = builder.poll;
        retryMaximum = builder.retryMaximum.compareTo(poll) < 0 ? poll : builder.retryMaximum;
        parallelism = builder.parallelism;
        recoveryParallelism = builder.recoveryParallelism;
        errors = builder.errors;
        localExecutor = new LocalWorkerExecutor(this);
        workers = Executors.newFixedThreadPool(parallelism, r -> daemon(r, "durable-executor"));
        recoveries =
                Executors.newFixedThreadPool(
                        recoveryParallelism, r -> daemon(r, "durable-execution-recovery"));
    }

    private Thread daemon(Runnable action, String name) {
        var thread =
                new Thread(
                        () -> {
                            ownedThreads.add(Thread.currentThread());
                            try {
                                action.run();
                            } finally {
                                ownedThreads.remove(Thread.currentThread());
                            }
                        },
                        name);
        thread.setDaemon(true);
        return thread;
    }

    /**
     * Return the stable local Brain endpoint for this Hands runtime.
     *
     * <p>The returned executor is borrowed. Close this {@code WorkerRuntime} to stop Hands
     * execution.
     */
    public LocalWorkerExecutor localExecutor() {
        return localExecutor;
    }

    ExecutionResult admit(ExecutionRequest request) {
        synchronized (lifecycle) {
            if (closed.get() || !accepting.get())
                throw new DurableException(DurableException.Code.UNAVAILABLE, "Worker is draining");
            return store.admit(request);
        }
    }

    ExecutionResult queryExecution(ExecutionRequest request) {
        return store.query(request);
    }

    ExecutionResult cancelExecution(ExecutionRequest request) {
        return store.cancel(request);
    }

    OutputPage readOutput(ExecutionRequest request, long offset) {
        return store.output(request, offset);
    }

    public Status status() {
        synchronized (lifecycle) {
            return new Status(
                    started.get(),
                    accepting.get(),
                    closed.get(),
                    active.size() - recovering.size(),
                    recovering.size(),
                    parallelism,
                    recoveryParallelism,
                    failures.get(),
                    leaseFailures.get(),
                    lastFailureAt,
                    lastScanAt);
        }
    }

    public ExecutionStore.Statistics statistics(int limit) {
        return store.statistics(limit);
    }

    /**
     * Starts this standalone execution engine.
     *
     * <p>Transport hosts such as RocketMqWorker call this internally. Applications call it directly
     * only when embedding WorkerRuntime without a higher-level Hands host.
     */
    public void start() {
        synchronized (lifecycle) {
            if (closed.get() || !accepting.get()) throw new IllegalStateException("Worker stopped");
            if (started.compareAndSet(false, true))
                scheduler.scheduleWithFixedDelay(
                        () -> {
                            try {
                                scan();
                            } catch (RuntimeException error) {
                                report(null, error);
                            }
                        },
                        0,
                        poll.toMillis(),
                        TimeUnit.MILLISECONDS);
        }
    }

    private void scan() {
        if (closed.get() || !accepting.get()) return;
        // Independent indexes and capacity keep a backlog of slow first calls from hiding controls.
        dispatch(true);
        dispatch(false);
        lastScanAt = System.currentTimeMillis();
    }

    private void dispatch(boolean recovery) {
        int capacity = recovery ? recoveryParallelism : parallelism;
        for (var key : store.ready(recovery, Math.max(100, capacity * 2))) {
            synchronized (lifecycle) {
                if (closed.get() || !accepting.get()) return;
                int count = recovery ? recovering.size() : active.size() - recovering.size();
                if (count >= capacity) return;
                if (!active.add(key)) continue;
                if (recovery) recovering.add(key);
                try {
                    (recovery ? recoveries : workers).execute(new Job(key));
                } catch (RuntimeException error) {
                    finished(key);
                    throw error;
                }
            }
        }
    }

    private final class Job implements Runnable {
        private final String key;

        private Job(String key) {
            this.key = key;
        }

        @Override
        public void run() {
            try {
                if (!closed.get()) process(key);
            } catch (RuntimeException error) {
                if (!closed.get()) report(key, error);
            } finally {
                finished(key);
            }
        }
    }

    private void finished(String key) {
        synchronized (lifecycle) {
            recovering.remove(key);
            active.remove(key);
            lifecycle.notifyAll();
        }
    }

    /** One synchronous sweep for externally driven hosts. Do not mix tick with background start. */
    public void tick() {
        if (started.get()) throw new IllegalStateException("Background Worker already started");
        for (var key : store.ready(1000)) {
            synchronized (lifecycle) {
                if (closed.get()) throw new IllegalStateException("Worker closed");
                if (!accepting.get()) return;
                if (!active.add(key)) continue;
            }
            try {
                process(key);
            } finally {
                finished(key);
            }
        }
    }

    private void process(String key) {
        var claimed = store.claim(key, owner + ":" + UUID.randomUUID(), lease);
        if (claimed.isEmpty()) return;
        var work = claimed.get();
        Thread thread = Thread.currentThread();
        var stopped = new AtomicBoolean();
        ScheduledFuture<?> renewal;
        try {
            renewal =
                    scheduler.scheduleWithFixedDelay(
                            () -> {
                                try {
                                    store.renew(work, lease);
                                } catch (RuntimeException error) {
                                    leaseFailures.incrementAndGet();
                                    stopped.set(true);
                                    thread.interrupt();
                                    report(work.request().executionId(), error);
                                }
                            },
                            Math.max(25, lease.toMillis() / 3),
                            Math.max(25, lease.toMillis() / 3),
                            TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException closing) {
            // Shutdown can race with a claim. No dispatch marker or external call exists yet.
            store.release(work, Duration.ZERO);
            return;
        }
        boolean retry = false;
        try {
            Executor provider = resolver.apply(work.request());
            if (provider == null)
                throw new DurableException(
                        DurableException.Code.INCOMPATIBLE, "Worker provider unavailable");
            if (!work.attempted() && !work.cancellationRequested())
                provider.validateStart(work.request());
            store.configureOutput(work, provider.capabilities().output());
            var before = store.begin(work);
            ExecutionResult result =
                    ExecutionRecovery.resolve(
                            provider,
                            before.request(),
                            before.result(),
                            before.attempted(),
                            before.cancellationRequested());
            retry = result.state() == ExecutionState.UNKNOWN;
            if (!stopped.get() && !closed.get()) {
                if (before.cancellationRequested() && !before.attempted() && !before.outputSealed())
                    store.appendOutput(work, new OutputPage(0, 0, "", 0, true, null));
                store.update(work, result);
                if (provider.capabilities().output()
                        && !before.outputSealed()
                        && !(before.cancellationRequested() && !before.attempted())) {
                    long offset = before.outputOffset();
                    for (int i = 0; i < 16 && !stopped.get() && !closed.get(); i++) {
                        var page = provider.output(before.request(), offset);
                        store.appendOutput(work, page);
                        if (page.terminal() || page.nextOffset() == offset) break;
                        offset = page.nextOffset();
                    }
                }
            }
        } catch (RuntimeException error) {
            retry = true;
            throw error;
        } finally {
            renewal.cancel(false);
            try {
                if (!stopped.get() && !closed.get()) {
                    if (retry) store.defer(work, poll, retryMaximum);
                    else store.release(work, poll);
                }
            } catch (DurableException error) {
                if (error.code() != DurableException.Code.FENCED) throw error;
            }
            Thread.interrupted();
        }
    }

    private void report(String execution, Throwable error) {
        failures.incrementAndGet();
        lastFailureAt = System.currentTimeMillis();
        try {
            errors.accept(
                    execution == null
                            ? error
                            : new DurableException(
                                    DurableException.Code.UNAVAILABLE,
                                    "Worker operation pending: " + execution,
                                    error));
        } catch (RuntimeException reporting) {
            System.getLogger(WorkerRuntime.class.getName())
                    .log(System.Logger.Level.WARNING, "Worker error observer failed", reporting);
        }
    }

    /** Stop admission and discovery. Active provider calls retain their leases until they return. */
    public void quiesce() {
        synchronized (lifecycle) {
            accepting.set(false);
        }
    }

    /** Drain active calls, not the lifetime of detached external effects. A replacement continues those. */
    public boolean drain(Duration timeout) throws InterruptedException {
        if (timeout.isNegative()) throw new IllegalArgumentException("Negative drain timeout");
        quiesce();
        long remaining = timeout.toNanos();
        long previous = System.nanoTime();
        synchronized (lifecycle) {
            while (!active.isEmpty()) {
                if (remaining <= 0) return false;
                TimeUnit.NANOSECONDS.timedWait(lifecycle, remaining);
                long now = System.nanoTime();
                remaining -= now - previous;
                previous = now;
            }
            return true;
        }
    }

    /** Forced local stop. Never calls provider cancel/close or destroys a remote environment. */
    @Override
    public void close() {
        synchronized (lifecycle) {
            if (!closed.compareAndSet(false, true)) return;
            accepting.set(false);
        }
        scheduler.shutdownNow();
        stopJobs(workers);
        stopJobs(recoveries);
        if (!ownedThreads.contains(Thread.currentThread())) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            try {
                for (var executor : List.of(scheduler, workers, recoveries))
                    executor.awaitTermination(
                            Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (lifecycle) {
            lifecycle.notifyAll();
        }
    }

    private void stopJobs(ExecutorService executor) {
        for (var pending : executor.shutdownNow())
            if (pending instanceof Job job) finished(job.key);
    }

    public static final class Builder {
        private ExecutionStore store;
        private Function<ExecutionRequest, Executor> resolver;
        private Duration lease = Duration.ofSeconds(30);
        private Duration poll = Duration.ofMillis(100);
        private Duration retryMaximum = Duration.ofSeconds(30);
        private int parallelism = 4;
        private int recoveryParallelism = 2;
        private Consumer<Throwable> errors =
                error ->
                        System.getLogger(WorkerRuntime.class.getName())
                                .log(
                                        System.Logger.Level.WARNING,
                                        "Worker operation pending",
                                        error);

        private Builder() {}

        /** Use a custom Hands execution ledger. */
        public Builder executionStore(ExecutionStore value) {
            store = Objects.requireNonNull(value);
            return this;
        }

        /** Use the default transactional Hands execution ledger on a borrowed RecordStore. */
        public Builder recordStore(RecordStore value) {
            store = new TransactionalExecutionStore(Objects.requireNonNull(value));
            return this;
        }

        /** Register one fixed execution route. */
        public Builder executor(String route, Executor value) {
            String name = DurableJson.text(route, "executor name");
            Executor executor = Objects.requireNonNull(value);
            return resolver(request -> name.equals(request.call().executor()) ? executor : null);
        }

        public Builder resolver(Function<ExecutionRequest, Executor> value) {
            resolver = Objects.requireNonNull(value);
            return this;
        }

        public Builder providers(Map<String, Executor> values) {
            var frozen = Map.copyOf(values);
            return resolver(request -> frozen.get(request.call().executor()));
        }

        public Builder leaseDuration(Duration value) {
            if (value.toMillis() < 100)
                throw new IllegalArgumentException("Lease must be >= 100ms");
            lease = value;
            return this;
        }

        public Builder pollInterval(Duration value) {
            poll = positive(value);
            return this;
        }

        public Builder retryMaximum(Duration value) {
            retryMaximum = positive(value);
            return this;
        }

        public Builder parallelism(int value) {
            if (value < 1) throw new IllegalArgumentException("Positive start capacity required");
            parallelism = value;
            return this;
        }

        public Builder recoveryParallelism(int value) {
            if (value < 1)
                throw new IllegalArgumentException("Positive recovery capacity required");
            recoveryParallelism = value;
            return this;
        }

        public Builder onError(Consumer<Throwable> value) {
            errors = Objects.requireNonNull(value);
            return this;
        }

        public WorkerRuntime build() {
            return new WorkerRuntime(this);
        }

        private static Duration positive(Duration value) {
            if (value.toMillis() < 1)
                throw new IllegalArgumentException("Positive duration required");
            return value;
        }
    }
}
