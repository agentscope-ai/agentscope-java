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
package io.agentscope.durable.agentscope;

import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableException.Code;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.ModelExecutionStore;
import io.agentscope.durable.ModelExecutionStore.Lease;
import io.agentscope.durable.ModelExecutions.Batch;
import io.agentscope.durable.ModelExecutions.Outcome;
import io.agentscope.durable.ModelExecutions.Status;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/** An explicitly started, bounded Model Worker. One instance executes one call at a time. */
public final class ModelWorker implements AutoCloseable {
    private final ModelExecutionStore store;
    private final String route;
    private final ModelExecutor executor;
    private final Duration leaseDuration;
    private final Duration pollInterval;
    private final AtomicBoolean active = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Sinks.Empty<Void> stopped = Sinks.empty();
    private AutoCloseable listener;
    private Disposable loop;

    public ModelWorker(ModelExecutionStore store, String route, ModelExecutor executor) {
        this(store, route, executor, Duration.ofSeconds(30), Duration.ofMillis(100));
    }

    public ModelWorker(
            ModelExecutionStore store,
            String route,
            ModelExecutor executor,
            Duration leaseDuration,
            Duration pollInterval) {
        this.store = Objects.requireNonNull(store, "store");
        this.route = DurableJson.text(route, "model route");
        this.executor = Objects.requireNonNull(executor, "executor");
        if (leaseDuration.toMillis() < 100 || pollInterval.toMillis() < 1)
            throw new IllegalArgumentException("Invalid Model Worker timing");
        this.leaseDuration = leaseDuration;
        this.pollInterval = pollInterval;
    }

    public synchronized void start() {
        if (closed.get()) throw new IllegalStateException("Model Worker is closed");
        if (loop != null) return;
        Sinks.Many<Long> hints = Sinks.many().multicast().directBestEffort();
        try {
            listener = store.wakeups().subscribe(session -> hints.tryEmitNext(0L));
        } catch (RuntimeException error) {
            report(error);
        }
        loop =
                Flux.merge(Flux.interval(Duration.ZERO, pollInterval), hints.asFlux())
                        .onBackpressureDrop()
                        .concatMap(
                                ignored ->
                                        Mono.fromRunnable(this::tick)
                                                .subscribeOn(Schedulers.boundedElastic())
                                                .onErrorResume(
                                                        error -> {
                                                            report(error);
                                                            return Mono.empty();
                                                        }),
                                1)
                        .subscribe();
    }

    /** Processes at most one execution on the calling worker thread. */
    public void tick() {
        if (closed.get() || !active.compareAndSet(false, true)) return;
        try {
            for (var key : store.ready(route, 16)) {
                var lease = store.claim(key, UUID.randomUUID().toString(), leaseDuration);
                if (lease.isPresent()) {
                    execute(lease.get());
                    return;
                }
            }
        } finally {
            active.set(false);
        }
    }

    private void execute(Lease lease) {
        var key = lease.request().key();
        QueuedModelRequest request;
        try {
            request = DurableJson.convert(lease.request().payload(), QueuedModelRequest.class);
            if (!request.executionKey().equals(key))
                throw new DurableException(Code.INVALID, "Model payload identity differs");
        } catch (IllegalArgumentException | DurableException error) {
            store.complete(lease, new Outcome(Status.FAILED, "Invalid model request", false));
            return;
        }
        var heartbeat =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            var thread = new Thread(runnable, "durable-model-heartbeat");
                            thread.setDaemon(true);
                            return thread;
                        });
        var lost = Sinks.<Throwable>one();
        long interval = leaseDuration.toMillis() / 3;
        try {
            store.begin(lease);
            heartbeat.scheduleWithFixedDelay(
                    () -> {
                        try {
                            store.renew(lease, leaseDuration);
                        } catch (RuntimeException error) {
                            lost.tryEmitValue(error);
                        }
                    },
                    interval,
                    interval,
                    TimeUnit.MILLISECONDS);
            var next = new AtomicLong();
            Flux.defer(() -> executor.execute(request))
                    .materialize()
                    .bufferTimeout(32, Duration.ofMillis(25), true)
                    .concatMap(
                            events ->
                                    Mono.fromRunnable(
                                                    () -> {
                                                        var chunks =
                                                                events.stream()
                                                                        .filter(Signal::isOnNext)
                                                                        .map(
                                                                                event ->
                                                                                        DurableJson
                                                                                                .value(
                                                                                                        event
                                                                                                                .get()))
                                                                        .toList();
                                                        if (!chunks.isEmpty()) {
                                                            var batch =
                                                                    new Batch(
                                                                            key,
                                                                            lease.epoch(),
                                                                            next.get(),
                                                                            chunks);
                                                            store.append(lease, batch);
                                                            next.set(batch.nextSequence());
                                                        }
                                                        var end = events.get(events.size() - 1);
                                                        if (end.isOnComplete())
                                                            store.complete(
                                                                    lease,
                                                                    new Outcome(
                                                                            Status.SUCCEEDED,
                                                                            null,
                                                                            false));
                                                        if (end.isOnError()) {
                                                            var error =
                                                                    Objects.requireNonNull(
                                                                            end.getThrowable());
                                                            store.complete(
                                                                    lease,
                                                                    new Outcome(
                                                                            Status.FAILED,
                                                                            error.getClass()
                                                                                    .getSimpleName(),
                                                                            ExecutionConfig
                                                                                    .RETRYABLE_ERRORS
                                                                                    .test(error)));
                                                        }
                                                    })
                                            .subscribeOn(Schedulers.boundedElastic()),
                            1)
                    .takeUntilOther(
                            Mono.firstWithSignal(
                                    lost.asMono().flatMap(Mono::error), stopped.asMono()))
                    .then()
                    .block();
        } finally {
            heartbeat.shutdownNow();
        }
    }

    private static void report(Throwable error) {
        System.getLogger(ModelWorker.class.getName())
                .log(System.Logger.Level.WARNING, "Model Worker operation failed", error);
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        stopped.tryEmitEmpty();
        if (loop != null) loop.dispose();
        if (listener != null) {
            try {
                listener.close();
            } catch (Exception error) {
                report(error);
            }
        }
    }
}
