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
package io.agentscope.extensions.durable.rocketmq;

import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.SessionBackend;
import io.agentscope.durable.TurnDispatch;
import io.agentscope.durable.TurnScheduler;
import io.agentscope.durable.TurnSignal;
import io.agentscope.durable.Wakeups;
import io.agentscope.extensions.durable.rocketmq.RocketMqLoop.Subscription;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.producer.Producer;

/** Thread-scoped notifications, owner signals and replayable dispatch over RocketMQ LiteTopics. */
public final class RocketMqTurnScheduler implements TurnScheduler, AutoCloseable {
    private static final int MAX_DEADLINES = 4096;
    private static final Options DEFAULT_OPTIONS = new Options(Duration.ofSeconds(1));
    private final RocketMqTopology.Turn topology;
    private final ClientServiceProvider provider;
    private final ClientConfiguration configuration;
    private final Subscription dispatchSubscription;
    private final Subscription signalSubscription;
    private final Function<String, Subscription> ownerSubscription;
    private final Options options;
    private final Wakeups local = Wakeups.local();
    private final Set<ThreadKey> pendingSignals = ConcurrentHashMap.newKeySet();
    private final boolean ownsProducer;
    private final LongAdder repairScans = new LongAdder();
    private final LongAdder emptyRepairScans = new LongAdder();
    private final LongAdder failedRepairScans = new LongAdder();
    private final LongAdder repairPublications = new LongAdder();
    private final LongAdder suppressedPublications = new LongAdder();
    private final LongAdder failedPublications = new LongAdder();
    private final Map<ThreadKey, DeadlineCheck> pendingDeadlines = new HashMap<>();
    private final ScheduledThreadPoolExecutor deadlines =
            new ScheduledThreadPoolExecutor(
                    1,
                    task -> {
                        var thread = new Thread(task, "durable-rocketmq-deadlines");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final ThreadPoolExecutor signals =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(1024),
                    task -> {
                        var thread = new Thread(task, "durable-rocketmq-signals");
                        thread.setDaemon(true);
                        return thread;
                    });
    private volatile boolean closed;
    private boolean dispatchQuiesced;
    private Producer producer;
    private RocketMqLoop dispatchLoop;
    private RocketMqLoop signalLoop;
    private RocketMqLoop ownerLoop;

    private final class DeadlineCheck implements Runnable {
        private final SessionBackend backend;
        private final ThreadKey key;
        private ScheduledFuture<?> future;

        private DeadlineCheck(SessionBackend backend, ThreadKey key) {
            this.backend = backend;
            this.key = key;
        }

        @Override
        public void run() {
            synchronized (pendingDeadlines) {
                if (!pendingDeadlines.remove(key, this)) return;
            }
            refreshPublication(backend, key);
        }
    }

    /** Cumulative publication statistics for this scheduler instance. */
    public record Statistics(
            long repairScans,
            long emptyRepairScans,
            long failedRepairScans,
            long repairPublications,
            long suppressedPublications,
            long failedPublications) {}

    public Statistics statistics() {
        return new Statistics(
                repairScans.sum(),
                emptyRepairScans.sum(),
                failedRepairScans.sum(),
                repairPublications.sum(),
                suppressedPublications.sum(),
                failedPublications.sum());
    }

    /** Dispatch outbox intervals, consumer limits and the dispatch long-poll wait. */
    public record Options(
            Duration publicationRepairInterval,
            Duration republishAfter,
            int dispatchCacheSize,
            int signalCacheSize,
            Duration receiveWait) {
        /** Uses the default publication intervals and consumer limits with a custom receive wait. */
        public Options(Duration receiveWait) {
            this(Duration.ofSeconds(2), Duration.ofSeconds(5), 32, 32, receiveWait);
        }

        public Options(
                Duration publicationRepairInterval,
                Duration republishAfter,
                int dispatchCacheSize,
                int signalCacheSize) {
            this(
                    publicationRepairInterval,
                    republishAfter,
                    dispatchCacheSize,
                    signalCacheSize,
                    Duration.ofSeconds(1));
        }

        public Options {
            if (publicationRepairInterval.toMillis() < 1 || republishAfter.toMillis() < 1)
                throw new IllegalArgumentException("Turn publication intervals must be positive");
            if (dispatchCacheSize < 1
                    || dispatchCacheSize > 1024
                    || signalCacheSize < 1
                    || signalCacheSize > 1024)
                throw new IllegalArgumentException("Consumer cache size must be 1..1024");
            if (receiveWait == null || receiveWait.compareTo(Duration.ofMillis(1)) < 0)
                throw new IllegalArgumentException("Dispatch receive wait must be at least 1 ms");
        }
    }

    public static RocketMqTurnScheduler create(
            ClientConfiguration configuration, String namespace) {
        return create(configuration, namespace, DEFAULT_OPTIONS);
    }

    public static RocketMqTurnScheduler create(
            ClientConfiguration configuration, String namespace, Options options) {
        var provider = ClientServiceProvider.loadService();
        var topology = RocketMqTopology.turns(namespace);
        return new RocketMqTurnScheduler(
                provider,
                configuration,
                topology,
                null,
                RocketMqStageConsumer.subscription(
                        provider,
                        configuration,
                        topology.dispatch(),
                        topology.runnerGroup(),
                        options.dispatchCacheSize(),
                        options.receiveWait()),
                RocketMqLoop.subscription(
                        provider,
                        configuration,
                        topology.signal(),
                        topology.signalGroup(),
                        options.signalCacheSize()),
                route ->
                        RocketMqLoop.subscription(
                                provider,
                                configuration,
                                topology.ownerSignal(),
                                topology.ownerGroup(),
                                options.signalCacheSize(),
                                route),
                options);
    }

    RocketMqTurnScheduler(
            ClientServiceProvider provider,
            ClientConfiguration configuration,
            RocketMqTopology.Turn topology,
            Producer producer,
            Subscription dispatchSubscription,
            Subscription signalSubscription,
            Function<String, Subscription> ownerSubscription,
            Options options) {
        this.provider = provider;
        this.configuration = configuration;
        this.topology = topology;
        this.producer = producer;
        ownsProducer = producer == null;
        this.dispatchSubscription = dispatchSubscription;
        this.signalSubscription = signalSubscription;
        this.ownerSubscription = ownerSubscription;
        this.options = options;
        deadlines.setRemoveOnCancelPolicy(true);
    }

    @Override
    public void publish(SessionKey key) {
        local.publish(key);
    }

    @Override
    public AutoCloseable subscribe(Consumer<SessionKey> listener) {
        return local.subscribe(listener);
    }

    @Override
    public void publish(ThreadKey key) {
        changed(null, key);
    }

    @Override
    public void changed(SessionBackend backend, ThreadKey key) {
        local.publish(key.session());
        if (closed || !pendingSignals.add(key)) return;
        try {
            signals.execute(
                    () -> {
                        pendingSignals.remove(key);
                        if (backend != null) refreshPublication(backend, key);
                        try {
                            String route = RocketMqLiteRouting.thread(key);
                            RocketMqLoop.send(
                                    provider,
                                    producer(),
                                    topology.signal(),
                                    route,
                                    route,
                                    key,
                                    null);
                        } catch (RuntimeException error) {
                            if (!closed) report(error);
                        }
                    });
        } catch (RejectedExecutionException saturated) {
            pendingSignals.remove(key);
            if (!closed) report(saturated);
        }
    }

    @Override
    public void controlChanged(SessionBackend backend, ThreadKey key, String turnId) {
        enqueue(
                () ->
                        backend.ownerSignal(key, turnId)
                                .ifPresent(
                                        signal -> {
                                            String route =
                                                    RocketMqLiteRouting.owner(signal.runnerId());
                                            RocketMqLoop.send(
                                                    provider,
                                                    producer(),
                                                    topology.ownerSignal(),
                                                    route,
                                                    route,
                                                    signal,
                                                    null);
                                        }));
        changed(backend, key);
    }

    @Override
    public void claimed(SessionBackend backend, ThreadKey key) {
        enqueue(() -> refreshPublication(backend, key));
    }

    private void enqueue(Runnable action) {
        if (closed) return;
        try {
            signals.execute(
                    () -> {
                        try {
                            action.run();
                        } catch (RuntimeException error) {
                            if (!closed) report(error);
                        }
                    });
        } catch (RejectedExecutionException saturated) {
            if (!closed) report(saturated);
        }
    }

    @Override
    public synchronized AutoCloseable start(
            SessionBackend backend,
            String runnerId,
            Function<TurnDispatch, CompletionStage<Boolean>> advance,
            Consumer<TurnSignal> refresh) {
        if (dispatchLoop != null) throw new IllegalStateException("Turn scheduler already started");
        var records =
                backend.localStorage()
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Turn scheduler requires local durable storage"))
                        .records();
        var publisher = producer();
        var dispatches =
                new RocketMqLoop(
                        provider,
                        publisher,
                        dispatchSubscription,
                        topology.dispatch(),
                        this::report,
                        null,
                        records);
        var notifications =
                new RocketMqLoop(
                        provider,
                        publisher,
                        signalSubscription,
                        topology.signal(),
                        this::report,
                        null,
                        records);
        String ownerRoute = RocketMqLiteRouting.owner(runnerId);
        var directed =
                new RocketMqLoop(
                        provider,
                        publisher,
                        ownerSubscription.apply(ownerRoute),
                        topology.ownerSignal(),
                        this::report,
                        null,
                        records);
        dispatchLoop = dispatches;
        signalLoop = notifications;
        ownerLoop = directed;
        try {
            directed.start(
                    (payload, message) -> {
                        var signal = DurableJson.convert(payload, TurnSignal.class);
                        if (!runnerId.equals(signal.runnerId()))
                            throw new IllegalArgumentException(
                                    "Signal targets another Turn runner");
                        RocketMqLiteRouting.require(message, ownerRoute, ownerRoute);
                        refresh.accept(signal);
                    },
                    null,
                    options.publicationRepairInterval());
            dispatches.start(
                    (payload, message) -> {
                        var dispatch = DurableJson.convert(payload, TurnDispatch.class);
                        RocketMqLiteRouting.require(
                                message,
                                RocketMqLiteRouting.thread(dispatch.thread()),
                                dispatch.id());
                        if (!awaitStage(advance.apply(dispatch)))
                            throw new DurableException(
                                    DurableException.Code.UNAVAILABLE,
                                    "Turn phase requires another delivery");
                    },
                    null,
                    options.publicationRepairInterval());
            notifications.start(
                    (payload, message) -> {
                        var thread = DurableJson.convert(payload, ThreadKey.class);
                        String route = RocketMqLiteRouting.thread(thread);
                        RocketMqLiteRouting.require(message, route, route);
                        local.publish(thread.session());
                        refreshPublication(backend, thread);
                    },
                    () -> repair(backend),
                    options.publicationRepairInterval());
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
        return this;
    }

    private void refreshPublication(SessionBackend backend, ThreadKey key) {
        if (closed) return;
        try {
            backend.dispatchSchedule(key)
                    .ifPresent(
                            schedule -> {
                                if (schedule.delayMillis() > 0)
                                    scheduleCheck(backend, key, schedule.delayMillis());
                                else publish(backend, schedule.dispatch());
                            });
        } catch (RuntimeException error) {
            if (!closed) report(error);
        }
    }

    private void scheduleCheck(SessionBackend backend, ThreadKey key, long delayMillis) {
        synchronized (pendingDeadlines) {
            if (closed) return;
            var previous = pendingDeadlines.get(key);
            if (previous == null && pendingDeadlines.size() >= MAX_DEADLINES) return;
            if (previous != null) {
                if (previous.future.getDelay(TimeUnit.MILLISECONDS) <= delayMillis) return;
                previous.future.cancel(false);
            }
            var task = new DeadlineCheck(backend, key);
            task.future = deadlines.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
            pendingDeadlines.put(key, task);
        }
    }

    private static boolean awaitStage(CompletionStage<Boolean> stage) {
        try {
            return stage.toCompletableFuture().get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException failed) {
            throw new DurableException(
                    DurableException.Code.UNAVAILABLE, "Turn phase failed", failed.getCause());
        }
    }

    private void repair(SessionBackend backend) {
        repairScans.increment();
        List<TurnDispatch> pending;
        try {
            pending = backend.pendingDispatches(100);
        } catch (RuntimeException error) {
            failedRepairScans.increment();
            throw error;
        }
        if (pending.isEmpty()) emptyRepairScans.increment();
        for (var dispatch : pending) {
            try {
                if (publish(backend, dispatch)) repairPublications.increment();
            } catch (RuntimeException error) {
                report(error);
            }
        }
    }

    private boolean publish(SessionBackend backend, TurnDispatch dispatch) {
        try {
            if (!backend.reserveDispatch(dispatch, options.republishAfter())) {
                suppressedPublications.increment();
                return false;
            }
            RocketMqLoop.send(
                    provider,
                    producer(),
                    topology.dispatch(),
                    RocketMqLiteRouting.thread(dispatch.thread()),
                    dispatch.id(),
                    dispatch,
                    null);
            return true;
        } catch (RuntimeException error) {
            failedPublications.increment();
            throw error;
        } finally {
            scheduleCheck(backend, dispatch.thread(), options.republishAfter().toMillis());
        }
    }

    private synchronized Producer producer() {
        if (closed) throw new IllegalStateException("Turn scheduler closed");
        if (producer != null) return producer;
        try {
            producer =
                    provider.newProducerBuilder()
                            .setClientConfiguration(configuration)
                            .setTopics(
                                    topology.dispatch(), topology.signal(), topology.ownerSignal())
                            .build();
            return producer;
        } catch (Exception error) {
            throw new DurableException(
                    DurableException.Code.UNAVAILABLE,
                    "Cannot create RocketMQ Turn publisher",
                    error);
        }
    }

    private void report(Throwable error) {
        System.getLogger(RocketMqTurnScheduler.class.getName())
                .log(System.Logger.Level.WARNING, "RocketMQ Turn scheduling failed", error);
    }

    @Override
    public synchronized void quiesce() {
        if (closed || dispatchQuiesced || dispatchLoop == null) return;
        dispatchQuiesced = true;
        dispatchLoop.quiesce();
        if (dispatchLoop.stopStageReceiver()) return;
        // Closing the SDK consumer can wait for its long poll. Keep the outbox and the
        // signal consumer available while the runtime drains its active invocations.
        var closer = new Thread(dispatchLoop::close, "durable-rocketmq-dispatch-drain");
        closer.setDaemon(true);
        closer.start();
    }

    @Override
    public void close() {
        Producer client;
        synchronized (this) {
            if (closed) return;
            closed = true;
            client = producer;
        }
        signals.shutdownNow();
        pendingSignals.clear();
        synchronized (pendingDeadlines) {
            deadlines.shutdownNow();
            pendingDeadlines.clear();
        }
        if (ownerLoop != null) ownerLoop.close();
        if (signalLoop != null) signalLoop.close();
        if (dispatchLoop != null) dispatchLoop.close();
        if (ownsProducer && client != null) {
            try {
                client.close();
            } catch (Exception error) {
                report(error);
            }
        }
    }
}
