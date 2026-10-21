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

import io.agentscope.builder.runtime.managed.ManagedJournalStore.ToolReference;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/** Durable, bounded execution owners. MQ acknowledges admission, never the lifetime of a tool. */
public final class ManagedHandsLoop implements AutoCloseable {
    private final ManagedHandsStore store;
    private final ManagedToolExecutions executions;
    private final BiFunction<ManagedScope, String, ManagedToolExecutor> providers;
    private final BiConsumer<ToolReference, RuntimeException> errors;
    private final ExecutorService workers;
    private final ScheduledExecutorService dispatch = Executors.newSingleThreadScheduledExecutor();
    private final Semaphore capacity;
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean wakeupQueued = new AtomicBoolean();
    private final long leaseMillis;
    private final long retryMillis;

    public ManagedHandsLoop(
            ManagedHandsStore store,
            ManagedToolExecutions executions,
            int concurrency,
            Duration leaseTtl,
            Duration retryDelay,
            BiFunction<ManagedScope, String, ManagedToolExecutor> providers,
            BiConsumer<ToolReference, RuntimeException> errors) {
        if (concurrency < 1)
            throw new IllegalArgumentException("Tool concurrency must be positive");
        this.store = store;
        this.executions = executions;
        this.providers = providers;
        this.errors = errors;
        this.leaseMillis = ManagedMessageBus.positiveMillis(leaseTtl);
        this.retryMillis = ManagedMessageBus.positiveMillis(retryDelay);
        workers = Executors.newFixedThreadPool(concurrency);
        capacity = new Semaphore(concurrency);
    }

    public void start() {
        if (started.compareAndSet(false, true))
            dispatch.scheduleWithFixedDelay(this::dispatch, 0, 500, TimeUnit.MILLISECONDS);
    }

    public void wakeup(ManagedScope scope, String taskId) {
        if (closed.get() || !wakeupQueued.compareAndSet(false, true)) return;
        try {
            dispatch.execute(
                    () -> {
                        wakeupQueued.set(false);
                        dispatch();
                    });
        } catch (RuntimeException stopped) {
            wakeupQueued.set(false);
            if (!closed.get()) throw stopped;
        }
    }

    private void dispatch() {
        if (closed.get() || capacity.availablePermits() == 0) return;
        try {
            long now = System.currentTimeMillis();
            for (var ref : store.pendingExecutions(256, now, leaseMillis, retryMillis)) {
                if (!capacity.tryAcquire()) return;
                String key = ref.scope().messageGroup() + ":" + ref.taskId();
                if (!active.add(key)) {
                    capacity.release();
                    continue;
                }
                boolean submitted = false;
                try {
                    if (!store.reserveExecution(
                            ref.scope(), ref.taskId(), now, leaseMillis, retryMillis)) continue;
                    workers.execute(() -> execute(ref, key));
                    submitted = true;
                } finally {
                    if (!submitted) {
                        active.remove(key);
                        capacity.release();
                    }
                }
            }
        } catch (RuntimeException unavailable) {
            if (!closed.get()) errors.accept(null, unavailable);
        }
    }

    private void execute(ToolReference ref, String key) {
        try {
            var provider = providers.apply(ref.scope(), ref.taskId());
            if (provider != null) executions.execute(ref.scope(), ref.taskId(), provider);
        } catch (RuntimeException failure) {
            if (!closed.get()) errors.accept(ref, failure);
        } finally {
            active.remove(key);
            capacity.release();
            // This is only a local hint. The durable due scan repairs a lost hint or owner crash.
            if (!closed.get()) wakeup(ref.scope(), ref.taskId());
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        dispatch.shutdownNow();
        workers.shutdownNow();
        try {
            workers.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
