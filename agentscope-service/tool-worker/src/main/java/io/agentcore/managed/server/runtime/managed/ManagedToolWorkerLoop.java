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

import io.agentcore.managed.server.runtime.managed.ManagedJournalStore.ToolReference;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/** Durable async owners. Worker capacity bounds transitions, never outstanding tool lifetimes. */
public final class ManagedToolWorkerLoop implements AutoCloseable {
    private final ManagedToolWorkerStore store;
    private final ManagedAsyncToolExecutions executions;
    private final BiFunction<ManagedScope, String, ManagedToolExecutor> providers;
    private final BiConsumer<ToolReference, RuntimeException> errors;
    private final ExecutorService workers;
    private final ScheduledExecutorService dispatch = Executors.newSingleThreadScheduledExecutor();
    private final ConcurrentHashMap<String, ManagedAsyncToolExecution> owners =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> active = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ManagedReceiveCapacity receiveCapacity;
    private final long repairMillis;
    private final long leaseMillis;
    private final long retryMillis;
    private BiConsumer<ManagedScope, String> republication;

    public void republication(BiConsumer<ManagedScope, String> publisher) {
        if (started.get()) throw new IllegalStateException("Configure recovery before start");
        republication = Objects.requireNonNull(publisher);
    }

    public ManagedToolWorkerLoop(
            ManagedToolWorkerStore store,
            ManagedAsyncToolExecutions executions,
            int concurrency,
            Duration leaseTtl,
            Duration retryDelay,
            BiFunction<ManagedScope, String, ManagedToolExecutor> providers,
            BiConsumer<ToolReference, RuntimeException> errors) {
        this(
                store,
                executions,
                concurrency,
                leaseTtl,
                retryDelay,
                providers,
                errors,
                16_384,
                64,
                Duration.ofSeconds(30));
    }

    public ManagedToolWorkerLoop(
            ManagedToolWorkerStore store,
            ManagedAsyncToolExecutions executions,
            int concurrency,
            Duration leaseTtl,
            Duration retryDelay,
            BiFunction<ManagedScope, String, ManagedToolExecutor> providers,
            BiConsumer<ToolReference, RuntimeException> errors,
            int inflightCapacity,
            int handoffCapacity,
            Duration repairPeriod) {
        if (inflightCapacity < 1)
            throw new IllegalArgumentException("Tool inflight capacity must be positive");
        this.receiveCapacity =
                new ManagedReceiveCapacity(
                        new Semaphore(inflightCapacity),
                        Math.min(inflightCapacity, handoffCapacity));
        this.repairMillis = ManagedMessageBus.positiveMillis(repairPeriod);
        if (concurrency < 1)
            throw new IllegalArgumentException("Tool concurrency must be positive");
        this.store = store;
        this.executions = executions;
        this.providers = providers;
        this.errors = errors;
        this.leaseMillis = ManagedMessageBus.positiveMillis(leaseTtl);
        this.retryMillis = ManagedMessageBus.positiveMillis(retryDelay);
        workers =
                new ThreadPoolExecutor(
                        concurrency,
                        concurrency,
                        0,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(Math.multiplyExact(concurrency, 2)));
    }

    public void start() {
        if (started.compareAndSet(false, true))
            dispatch.scheduleWithFixedDelay(this::dispatch, 0, repairMillis, TimeUnit.MILLISECONDS);
    }

    public ManagedReceiveCapacity receiveCapacity() {
        return receiveCapacity;
    }

    /** Normal command path: durable claim before ACK, preparation only after confirmation. */
    public void admit(ManagedScope scope, String taskId, ManagedDeliveryHandoff handoff) {
        if (closed.get()) throw new ManagedTransportException("Tool worker is closed");
        launch(new ToolReference(scope, taskId), handoff, false);
    }

    /** Pub/sub callback only wakes an existing owner, without SQL or task creation. */
    public void signalLocal(ManagedScope scope, String taskId) {
        var owner = owners.get(scope.messageGroup() + ":" + taskId);
        if (owner != null) owner.signal();
    }

    public void wakeup(ManagedScope scope, String taskId) {
        String key = scope.messageGroup() + ":" + taskId;
        var owner = owners.get(key);
        if (owner != null) {
            owner.signal();
            return;
        }
        if (closed.get()) return;
        if (republication != null) {
            republication.accept(scope, taskId);
            return;
        }
        try {
            workers.execute(() -> launch(new ToolReference(scope, taskId), null, true));
        } catch (RejectedExecutionException saturated) {
            // Durable admission remains available to the low-frequency repair pass.
        }
    }

    private void dispatch() {
        if (closed.get()) return;
        try {
            long now = System.currentTimeMillis();
            if (republication == null && receiveCapacity.availableExecutions() == 0) return;
            for (var ref : store.pendingExecutions(256, now, leaseMillis, retryMillis)) {
                if (closed.get()) break;
                if (republication != null) {
                    if (store.reserveExecution(
                            ref.scope(), ref.taskId(), now, leaseMillis, retryMillis))
                        republication.accept(ref.scope(), ref.taskId());
                } else {
                    if (receiveCapacity.availableExecutions() == 0) break;
                    wakeup(ref.scope(), ref.taskId());
                }
            }
        } catch (RejectedExecutionException saturated) {
            // Admission remains durable. Retry short transitions on the next scan; no tool has
            // been launched by a rejected dispatch and pending tools do not own worker slots.
        } catch (RuntimeException unavailable) {
            if (!closed.get()) errors.accept(null, unavailable);
        }
    }

    private void launch(ToolReference ref, ManagedDeliveryHandoff incoming, boolean recovery) {
        String key = ref.scope().messageGroup() + ":" + ref.taskId();
        Object ticket = new Object();
        if (active.putIfAbsent(key, ticket) != null) return;
        ManagedDeliveryHandoff handoff = incoming;
        ManagedAsyncToolExecution owner = null;
        Runnable release = null;
        boolean submitted = false;
        try {
            if (closed.get()) return;
            if (handoff == null || !handoff.reserved()) {
                var reserved = receiveCapacity.reserve(1);
                if (reserved.isEmpty()) return; // Durable admission is repaired later.
                var local = reserved.get(0);
                release = local.takeExecution();
                local.confirm(); // Local repair has no MQ receipt; claim is still required.
                if (handoff == null) handoff = ManagedDeliveryHandoff.direct();
            } else release = handoff.takeExecution();
            if (recovery
                    && !store.reserveExecution(
                            ref.scope(),
                            ref.taskId(),
                            System.currentTimeMillis(),
                            leaseMillis,
                            retryMillis)) return;
            owner =
                    executions.asynchronous(
                            ref.scope(),
                            ref.taskId(),
                            () -> providers.apply(ref.scope(), ref.taskId()),
                            workers,
                            handoff);
            if (!owner.claim()) return;
            owners.put(key, owner);
            var owned = owner;
            var permit = release;
            owned.finished()
                    .whenComplete(
                            (value, failure) -> {
                                owners.remove(key, owned);
                                active.remove(key, ticket);
                                permit.run();
                                if (!closed.get() && failure instanceof RuntimeException error)
                                    errors.accept(ref, error);
                            });
            owner.start();
            submitted = true;
            if (closed.get()) owner.stop();
        } catch (RuntimeException failure) {
            if (incoming != null) throw failure;
            if (!closed.get()) errors.accept(ref, failure);
        } finally {
            if (!submitted) {
                if (owner != null) owner.stop();
                active.remove(key, ticket);
                if (release != null) release.run();
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        dispatch.shutdownNow();
        var stopping = owners.values().stream().toList();
        stopping.forEach(ManagedAsyncToolExecution::stop);
        try {
            CompletableFuture.allOf(
                            stopping.stream()
                                    .map(ManagedAsyncToolExecution::finished)
                                    .toArray(CompletableFuture[]::new))
                    .get(10, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException shutdown) {
            // Persisted identities and expiring leases retain recovery responsibility.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        workers.shutdown();
        try {
            if (!workers.awaitTermination(10, TimeUnit.SECONDS)) workers.shutdownNow();
        } catch (InterruptedException interrupted) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
