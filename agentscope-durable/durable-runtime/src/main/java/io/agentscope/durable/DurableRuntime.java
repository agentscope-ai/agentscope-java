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
package io.agentscope.durable;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.durable.DurableTypes.CheckpointRestore;
import io.agentscope.durable.DurableTypes.CommitReceipt;
import io.agentscope.durable.DurableTypes.EventFilter;
import io.agentscope.durable.DurableTypes.ExecutionResult;
import io.agentscope.durable.DurableTypes.Failure;
import io.agentscope.durable.DurableTypes.Frame;
import io.agentscope.durable.DurableTypes.HarnessSelector;
import io.agentscope.durable.DurableTypes.Lease;
import io.agentscope.durable.DurableTypes.LiveEvent;
import io.agentscope.durable.DurableTypes.PublicEvent;
import io.agentscope.durable.DurableTypes.RecoveryPolicy;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.SessionPage;
import io.agentscope.durable.DurableTypes.SessionView;
import io.agentscope.durable.DurableTypes.Snapshot;
import io.agentscope.durable.DurableTypes.ThreadBinding;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.DurableTypes.ThreadSpec;
import io.agentscope.durable.DurableTypes.ToolExecution;
import io.agentscope.durable.DurableTypes.TurnStatus;
import io.agentscope.durable.DurableTypes.TurnView;
import io.agentscope.durable.DurableTypes.WaitCondition;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Unified public API, assembly and lifecycle owner for durable work. Observers never own its
 * subscriptions. Database work, native execution and external I/O run on dedicated workers.
 */
public final class DurableRuntime implements AutoCloseable {
    public record Registration(String harness, String definitionVersion, HarnessAdapter adapter) {
        public Registration {
            DurableJson.text(harness, "harness");
            DurableJson.text(definitionVersion, "definitionVersion");
            Objects.requireNonNull(adapter);
        }
    }

    private final SessionBackend backend;
    private final Function<DurableProtocol.Request, JsonNode> transport;
    private final DurableOperations operations;
    private final Map<String, ThreadBinding> definitions;
    private final BiFunction<SessionKey, String, ThreadBinding> definitionResolver;
    private final Map<String, BiFunction<SessionBackend, CheckpointRestore, CommitReceipt>>
            restorers;
    private final Set<CompletableFuture<TurnView>> waits = ConcurrentHashMap.newKeySet();
    private final Map<String, Registration> harnesses;
    private final Map<String, Registration> versions;
    private final Map<String, Function<ThreadSpec, HarnessAdapter>> resolvers;
    private final Semaphore capacity;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final Map<String, Executor> executors;
    private final Duration pollInterval;
    private final Duration leaseDuration;
    private final int parallelism;
    private final BiConsumer<SessionKey, Throwable> errors;
    private final RecoveryPolicy recoveryPolicy;
    private final Predicate<Throwable> retryableFailure;
    private final LiveEvents liveEvents;
    private final String owner = UUID.randomUUID().toString();
    private final ConcurrentHashMap<ThreadKey, Active> active = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ThreadKey, Throwable> failures = new ConcurrentHashMap<>();
    private final AtomicBoolean wakeupScanQueued = new AtomicBoolean();
    private final Set<Thread> ownedThreads = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService timer =
            Executors.newScheduledThreadPool(2, r -> daemon(r, "durable-timer"));
    private final ExecutorService workers;
    private final ScheduledThreadPoolExecutor observers;
    private final Set<SessionEventSubscription> subscriptions = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private AutoCloseable wakeupSubscription;

    private static final class Active {
        final AtomicBoolean stopped = new AtomicBoolean();
        volatile Thread thread;
        volatile boolean invoking;
    }

    private DurableRuntime(Builder b) {
        if ((b.backend == null) == (b.transport == null))
            throw new IllegalArgumentException(
                    "Configure exactly one Backend or public operation transport");
        if (b.transport != null
                && (!b.harnesses.isEmpty()
                        || !b.resolvers.isEmpty()
                        || !b.executors.isEmpty()
                        || !b.definitions.isEmpty()
                        || b.definitionResolver != null
                        || !b.restorers.isEmpty()))
            throw new IllegalArgumentException(
                    "Remote public access cannot configure local execution or definitions");
        backend = b.backend;
        transport = b.transport;
        operations = backend == null ? null : new DurableOperations(this, backend);
        definitions = Map.copyOf(b.definitions);
        definitionResolver = b.definitionResolver;
        restorers = Map.copyOf(b.restorers);
        harnesses = Map.copyOf(b.harnesses);
        versions = Map.copyOf(b.versions);
        resolvers = Map.copyOf(b.resolvers);
        capacity = new Semaphore(b.parallelism);
        pollInterval = b.pollInterval;
        leaseDuration = b.leaseDuration;
        parallelism = b.parallelism;
        errors = b.errors;
        recoveryPolicy = b.recoveryPolicy;
        retryableFailure = b.retryableFailure;
        liveEvents = b.liveEvents;
        if (pollInterval.toMillis() < 1
                || leaseDuration.toMillis() < 100
                || parallelism < 1
                || b.observerParallelism < 1)
            throw new IllegalArgumentException(
                    "Positive poll interval, lease >= 100ms and parallelism required");
        var boundExecutors = new LinkedHashMap<>(b.executors);
        var managedExecutors = new ArrayList<ManagedExecutor>();
        try {
            for (var entry : boundExecutors.entrySet())
                if (entry.getValue() instanceof ManagedExecutor managed) {
                    if (backend == null)
                        throw new IllegalArgumentException(
                                "Managed executors require a local SessionBackend");
                    managedExecutors.add(managed);
                    managed.bind(entry.getKey(), backend);
                }
        } catch (RuntimeException failure) {
            for (var managed : managedExecutors)
                try {
                    managed.close();
                } catch (RuntimeException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            throw failure;
        }
        executors = Map.copyOf(boundExecutors);
        workers = Executors.newFixedThreadPool(parallelism, r -> daemon(r, "durable-worker"));
        observers =
                new ScheduledThreadPoolExecutor(
                        b.observerParallelism, r -> daemon(r, "durable-observer"));
        observers.setRemoveOnCancelPolicy(true);
        observers.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    public static Builder builder() {
        return new Builder();
    }

    public SessionBackend backend() {
        ensureOpen();
        if (backend == null)
            throw new IllegalStateException("Runtime uses public transport, not a local Backend");
        return backend;
    }

    public record Capabilities(
            String protocolVersion,
            SessionBackend.Capabilities backend,
            Map<String, HarnessAdapter.Capabilities> harnesses,
            List<String> resolvedHarnesses,
            Map<String, Executor.Capabilities> executors) {}

    /** Capabilities of one pinned definition, including a dynamically resolved Harness. */
    public record DefinitionCapabilities(
            String protocolVersion,
            ThreadSpec definition,
            SessionBackend.Capabilities backend,
            HarnessAdapter.Capabilities harness,
            Map<String, Executor.Capabilities> executors) {}

    public Capabilities executionCapabilities() {
        backend();
        var result = new LinkedHashMap<String, HarnessAdapter.Capabilities>();
        harnesses.forEach((key, value) -> result.put(key, value.adapter().capabilities()));
        return new Capabilities(
                DurableProtocol.VERSION,
                backend.capabilities(),
                Map.copyOf(result),
                resolvers.keySet().stream().sorted().toList(),
                executorCapabilities());
    }

    /** Does not claim a Turn or invoke inference. Resolver I/O may block; call off event loops. */
    public DefinitionCapabilities capabilities(ThreadSpec definition) {
        backend();
        return new DefinitionCapabilities(
                DurableProtocol.VERSION,
                definition,
                backend.capabilities(),
                adapter(definition).capabilities(),
                executorCapabilities());
    }

    private Map<String, Executor.Capabilities> executorCapabilities() {
        var result = new LinkedHashMap<String, Executor.Capabilities>();
        executors.forEach((key, value) -> result.put(key, value.capabilities()));
        return Map.copyOf(result);
    }

    private HarnessAdapter adapter(ThreadSpec definition) {
        Objects.requireNonNull(definition);
        var resolver = resolvers.get(definition.harness());
        var registration =
                versions.get(
                        DurableJson.hash(
                                List.of(definition.harness(), definition.definitionVersion())));
        HarnessAdapter adapter =
                resolver != null
                        ? resolver.apply(definition)
                        : registration == null ? null : registration.adapter();
        if (adapter == null
                || !adapter.capabilities().stateVersion().equals(definition.stateVersion()))
            throw new DurableException(
                    DurableException.Code.INCOMPATIBLE,
                    "Pinned Harness definition/state version is unavailable");
        return adapter;
    }

    JsonNode call(String operation, Object... fields) {
        var payload = new LinkedHashMap<String, Object>();
        for (int i = 0; i < fields.length; i += 2) payload.put((String) fields[i], fields[i + 1]);
        return invoke(
                new DurableProtocol.Request(
                        DurableProtocol.VERSION, operation, DurableJson.value(payload)));
    }

    /** Public capabilities available through this local or remote Runtime. */
    public JsonNode capabilities() {
        return call("capabilities");
    }

    public DurableSession createSession(SessionKey key, String definition) {
        return createSession(key, definition, DurableJson.value(null));
    }

    public DurableSession createSession(SessionKey key, String definition, JsonNode metadata) {
        var created =
                DurableJson.convert(
                        call(
                                "createSession",
                                "session",
                                key,
                                "definition",
                                definition,
                                "metadata",
                                metadata),
                        SessionView.class);
        return new DurableSession(this, created.key());
    }

    public DurableSession getSession(SessionKey key) {
        call("getSession", "session", key);
        return new DurableSession(this, key);
    }

    public SessionPage listSessions(String namespace, String cursor, int limit) {
        return DurableJson.convert(
                call("listSessions", "namespace", namespace, "cursor", cursor, "limit", limit),
                SessionPage.class);
    }

    /** Restricted, schema-validated public operation binding for transport adapters. */
    public JsonNode invoke(DurableProtocol.Request request) {
        ensureOpen();
        DurableProtocol.validateRequest(DurableProtocol.Endpoint.USER, DurableJson.value(request));
        var result = transport == null ? operations.invoke(request) : transport.apply(request);
        return DurableProtocol.response(
                DurableProtocol.Endpoint.USER,
                request.operation(),
                DurableJson.value(new DurableProtocol.Response(DurableProtocol.VERSION, result)));
    }

    JsonNode localUserCapabilities() {
        return DurableJson.value(
                Map.of(
                        "protocolVersion",
                        DurableProtocol.VERSION,
                        "operations",
                        DurableOperations.OPERATIONS,
                        "definitions",
                        definitions.keySet().stream().sorted().toList(),
                        "checkpointHarnesses",
                        restorers.keySet().stream().sorted().toList()));
    }

    ThreadBinding binding(SessionKey key, String name) {
        var selected =
                definitionResolver == null
                        ? definitions.get(name)
                        : definitionResolver.apply(key, name);
        if (selected == null)
            throw new DurableException(
                    DurableException.Code.INCOMPATIBLE, "Definition is unavailable");
        return selected;
    }

    CommitReceipt restore(CheckpointRestore request) {
        var restorer = restorers.get(request.target().harness());
        if (restorer == null)
            throw new DurableException(
                    DurableException.Code.INCOMPATIBLE,
                    "Checkpoint restore is unsupported by this host");
        return restorer.apply(backend, request);
    }

    Wakeups wakeups() {
        return backend == null ? Wakeups.none() : backend.wakeups();
    }

    LiveEvents liveEvents() {
        return liveEvents;
    }

    CompletionStage<TurnView> await(DurableThread session, String turnId, Duration timeout) {
        ensureOpen();
        if (timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("Positive timeout required");
        long deadline = System.nanoTime() + timeout.toNanos();
        var result = new CompletableFuture<TurnView>();
        waits.add(result);
        try {
            var scheduled =
                    observers.scheduleWithFixedDelay(
                            () -> {
                                if (result.isDone()) return;
                                try {
                                    ensureOpen();
                                    var view =
                                            DurableJson.convert(
                                                    session.call("getTurn", "turnId", turnId),
                                                    TurnView.class);
                                    if (view.status().terminal()) result.complete(view);
                                    else if (System.nanoTime() - deadline >= 0)
                                        result.completeExceptionally(
                                                new TimeoutException(
                                                        "Timed out observing Turn "
                                                                + session.key()
                                                                + "/"
                                                                + turnId));
                                } catch (RuntimeException error) {
                                    result.completeExceptionally(error);
                                }
                            },
                            0,
                            pollInterval.toMillis(),
                            TimeUnit.MILLISECONDS);
            result.whenComplete(
                    (view, error) -> {
                        scheduled.cancel(false);
                        waits.remove(result);
                    });
        } catch (RejectedExecutionException error) {
            waits.remove(result);
            result.completeExceptionally(error);
        }
        return result;
    }

    SessionEventSubscription observe(
            DurableSession session,
            EventFilter filter,
            String cursor,
            boolean initialSnapshot,
            Consumer<Snapshot> snapshots,
            Consumer<PublicEvent> events) {
        return observe(session, filter, cursor, initialSnapshot, snapshots, events, null);
    }

    SessionEventSubscription observe(
            DurableSession session,
            EventFilter filter,
            String cursor,
            boolean initialSnapshot,
            Consumer<Snapshot> snapshots,
            Consumer<PublicEvent> events,
            Consumer<LiveEvent> liveEvents) {
        synchronized (subscriptions) {
            ensureOpen();
            var subscription =
                    new SessionEventSubscription(
                            session,
                            filter,
                            cursor,
                            initialSnapshot,
                            snapshots,
                            events,
                            liveEvents,
                            error -> reportError(session.key(), error),
                            observers,
                            pollInterval,
                            subscriptions::remove);
            subscriptions.add(subscription);
            try {
                subscription.start();
                return subscription;
            } catch (RuntimeException error) {
                subscription.close();
                throw error;
            }
        }
    }

    public Throwable lastFailure(ThreadKey key) {
        return failures.get(key);
    }

    /**
     * Starts this Brain host and any managed executor transports.
     *
     * <p>Applications call this once for the top-level Brain component. It is safe to call again.
     * API-only runtimes are intentionally not started.
     */
    public void start() {
        ensureOpen();
        ensureExecution();
        if (started.compareAndSet(false, true)) {
            try {
                executors.values().stream()
                        .filter(ManagedExecutor.class::isInstance)
                        .map(ManagedExecutor.class::cast)
                        .forEach(ManagedExecutor::open);
            } catch (RuntimeException error) {
                close();
                throw error;
            }
            try {
                wakeupSubscription = backend.wakeups().subscribe(this::wake);
            } catch (RuntimeException error) {
                reportError(null, error);
            }
            timer.scheduleWithFixedDelay(
                    this::scanSafely, 0, pollInterval.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /** A non-blocking optional wakeup. Durable discovery repairs dropped or duplicate hints. */
    public void wake(SessionKey key) {
        Objects.requireNonNull(key);
        if (!started.get() || closed.get() || !wakeupScanQueued.compareAndSet(false, true)) return;
        try {
            timer.execute(
                    () -> {
                        wakeupScanQueued.set(false);
                        scanSafely();
                    });
        } catch (RejectedExecutionException stopped) {
            wakeupScanQueued.set(false);
        }
    }

    /** Callback admission is bounded; true means admitted or already active, never completed. */
    public boolean offer(ThreadKey key) {
        ensureExecution();
        Objects.requireNonNull(key);
        Active invocation;
        synchronized (active) {
            if (closed.get() || !accepting.get()) return false;
            if (active.containsKey(key)) return true;
            if (!capacity.tryAcquire()) return false;
            invocation = new Active();
            active.put(key, invocation);
        }
        try {
            workers.execute(
                    () -> {
                        try {
                            process(key, invocation);
                        } catch (RuntimeException error) {
                            failures.put(key, error);
                            reportError(key.session(), error);
                        } finally {
                            finished(key, invocation);
                        }
                    });
            return true;
        } catch (RejectedExecutionException stopped) {
            finished(key, invocation);
            return false;
        }
    }

    private void finished(ThreadKey key, Active invocation) {
        synchronized (active) {
            if (active.remove(key, invocation)) capacity.release();
            active.notifyAll();
        }
    }

    /** Stops new admission and lets current invocations reach their next durable boundary. */
    public boolean drain(Duration timeout) throws InterruptedException {
        if (timeout.isNegative()) throw new IllegalArgumentException("Negative drain timeout");
        accepting.set(false);
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (active) {
            while (!active.isEmpty()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return false;
                TimeUnit.NANOSECONDS.timedWait(active, remaining);
            }
        }
        return true;
    }

    private void scanSafely() {
        try {
            scan();
        } catch (Throwable e) {
            if (e instanceof VirtualMachineError fatal) throw fatal;
            reportError(null, e);
        }
    }

    private void scan() {
        if (closed.get() || !accepting.get() || capacity.availablePermits() == 0) return;
        var types = new HashSet<HarnessSelector>();
        resolvers.keySet().forEach(name -> types.add(HarnessSelector.all(name)));
        versions.values().stream()
                .filter(value -> !resolvers.containsKey(value.harness()))
                .forEach(
                        value ->
                                types.add(
                                        new HarnessSelector(
                                                value.harness(),
                                                value.definitionVersion(),
                                                value.adapter().capabilities().stateVersion())));
        for (var definition : backend.ready(List.copyOf(types), Math.min(1000, parallelism))) {
            if (!offer(definition.key())) return;
        }
    }

    /** Deterministic host/test entry point; does not start a background scheduler. */
    public void tick(ThreadKey key) {
        ensureOpen();
        ensureExecution();
        Active invocation = new Active();
        if (active.putIfAbsent(key, invocation) != null) return;
        try {
            process(key, invocation);
        } finally {
            active.remove(key, invocation);
        }
    }

    private void process(ThreadKey key, Active activeCall) {
        activeCall.thread = Thread.currentThread();
        String claimId = owner + ":" + UUID.randomUUID();
        Optional<Lease> claimed;
        try {
            claimed = backend.claim(key, claimId, leaseDuration);
        } catch (DurableException error) {
            if (error.code() != DurableException.Code.UNAVAILABLE) throw error;
            claimed = backend.claim(key, claimId, leaseDuration);
        }
        if (claimed.isEmpty()) return;
        Lease lease = claimed.get();
        var heartbeat =
                timer.scheduleWithFixedDelay(
                        () -> {
                            try {
                                backend.renew(lease, leaseDuration);
                                if (activeCall.invoking
                                        && backend.turn(key, lease.turnId())
                                                .cancellationRequested()) {
                                    activeCall.stopped.set(true);
                                    activeCall.thread.interrupt();
                                }
                            } catch (RuntimeException e) {
                                activeCall.stopped.set(true);
                                activeCall.thread.interrupt();
                            }
                        },
                        Math.max(25, leaseDuration.toMillis() / 3),
                        Math.max(25, leaseDuration.toMillis() / 3),
                        TimeUnit.MILLISECONDS);
        try {
            Frame frame = backend.frame(lease);
            if (frame.turn().status() == TurnStatus.PAUSING) return;
            for (ToolExecution tool : frame.tools()) {
                if (tool.result().state().terminal()) continue;
                if (backend.turn(key, lease.turnId()).status() == TurnStatus.PAUSING) return;
                resolve(
                        lease,
                        tool,
                        frame.turn().cancellationRequested() || recoveryStopped(frame));
            }
            frame = backend.frame(lease);
            if (frame.turn().status() == TurnStatus.PAUSING) return;
            if (frame.turn().cancellationRequested()) {
                if (frame.tools().stream().allMatch(t -> t.result().state().terminal()))
                    adapter(frame.definition())
                            .settle(
                                    new HarnessContext(
                                            backend, lease, activeCall.stopped, liveEvents),
                                    TurnStatus.CANCELLED,
                                    null);
                return;
            }
            if (recoveryStopped(frame)) {
                settleFailure(lease, frame, activeCall);
                return;
            }
            if (frame.tools().stream()
                    .anyMatch(t -> !t.applied() && !t.result().state().terminal())) return;
            WaitCondition wait = frame.turn().waiting();
            if (wait != null
                    && (wait.deadline() == 0 || wait.deadline() > frame.now())
                    && frame.signals().stream()
                            .noneMatch(signal -> signal.key().equals(wait.key()))) return;
            var context = new HarnessContext(backend, lease, activeCall.stopped, liveEvents);
            activeCall.invoking = true;
            adapter(frame.definition()).execute(context);
            activeCall.invoking = false;
            failures.remove(key);
        } catch (Exception error) {
            if (!activeCall.stopped.get()
                    && !(error instanceof DurableException d
                            && d.code() == DurableException.Code.FENCED)) {
                try {
                    if (backend.turn(key, lease.turnId()).status() == TurnStatus.PAUSING) return;
                } catch (RuntimeException unavailable) {
                    // Keep the original failure if the pause intent cannot be confirmed.
                }
                failures.put(key, error);
                reportError(key.session(), error);
                // Another host may resolve an unavailable pinned definition. Ownership loss and
                // host shutdown likewise do not consume the invocation retry budget.
                if (!(error instanceof DurableException d
                        && d.code() == DurableException.Code.INCOMPATIBLE)) {
                    try {
                        var failure =
                                new Failure(
                                        retryableFailure.test(error),
                                        error.getMessage() == null
                                                ? error.getClass().getSimpleName()
                                                : error.getMessage(),
                                        recoveryPolicy);
                        try {
                            backend.recordFailure(lease, failure);
                        } catch (DurableException uncertain) {
                            if (uncertain.code() != DurableException.Code.UNAVAILABLE)
                                throw uncertain;
                            // The lease epoch is the report identity, including after response
                            // loss.
                            backend.recordFailure(lease, failure);
                        }
                        var latest = backend.frame(lease);
                        if (recoveryStopped(latest)) settleFailure(lease, latest, activeCall);
                    } catch (Exception recordingError) {
                        if (!(recordingError instanceof DurableException d
                                && d.code() == DurableException.Code.FENCED))
                            reportError(key.session(), recordingError);
                    }
                }
            }
        } finally {
            heartbeat.cancel(false);
            try {
                backend.release(lease, pollInterval);
            } catch (DurableException e) {
                if (e.code() != DurableException.Code.FENCED) reportError(key.session(), e);
            }
            // Do not leak a cancellation interrupt into the next session on this pooled worker.
            Thread.interrupted();
        }
    }

    private static boolean recoveryStopped(Frame frame) {
        return frame.turn().recovery() != null && !frame.turn().recovery().retryAllowed();
    }

    private void settleFailure(Lease lease, Frame frame, Active activeCall) throws Exception {
        if (frame.turn().cancellationRequested()
                || frame.turn().status() == TurnStatus.PAUSING
                || frame.tools().stream().anyMatch(t -> !t.result().state().terminal())) return;
        adapter(frame.definition())
                .settle(
                        new HarnessContext(backend, lease, activeCall.stopped, liveEvents),
                        TurnStatus.FAILED,
                        frame.turn().recovery().message());
    }

    private static boolean transientFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof DurableException failure)
                return failure.code() == DurableException.Code.UNAVAILABLE
                        || failure.code() == DurableException.Code.CONFLICT;
            if (cause instanceof IOException
                    || cause instanceof UncheckedIOException
                    || cause instanceof TimeoutException) return true;
            if (cause == cause.getCause()) break;
        }
        return false;
    }

    private void resolve(Lease lease, ToolExecution tool, boolean cancel) {
        Executor executor = executors.get(tool.request().call().executor());
        if (executor == null)
            throw new DurableException(
                    DurableException.Code.INCOMPATIBLE, "Executor is not registered");
        if (!cancel && !tool.dispatchAttempted()) executor.validateStart(tool.request());
        ToolExecution before =
                cancel ? tool : backend.beginDispatch(lease, tool.request().executionId());
        if (before.result().state().terminal()) return;
        ExecutionResult result =
                ExecutionRecovery.resolve(
                        executor,
                        before.request(),
                        before.result(),
                        before.dispatchAttempted(),
                        cancel);
        backend.acceptResult(lease.thread(), tool.request().executionId(), result);
    }

    private void ensureExecution() {
        if (backend == null || (harnesses.isEmpty() && resolvers.isEmpty()))
            throw new IllegalStateException("Runtime has no Brain execution configuration");
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("Runtime closed");
    }

    private void reportError(SessionKey session, Throwable error) {
        try {
            errors.accept(session, error);
        } catch (RuntimeException reportingError) {
            System.getLogger(DurableRuntime.class.getName())
                    .log(
                            System.Logger.Level.WARNING,
                            "Durable error callback failed",
                            reportingError);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        synchronized (subscriptions) {
            subscriptions.forEach(SessionEventSubscription::close);
        }
        waits.forEach(
                wait -> wait.completeExceptionally(new IllegalStateException("Runtime closed")));
        observers.shutdownNow();
        if (wakeupSubscription != null)
            try {
                wakeupSubscription.close();
            } catch (Exception error) {
                reportError(null, error);
            }
        active.values()
                .forEach(
                        a -> {
                            a.stopped.set(true);
                            if (a.thread != null) a.thread.interrupt();
                        });
        timer.shutdownNow();
        workers.shutdownNow();
        // The host may now close its backend. Allow interrupted readers/writers to return their
        // borrowed connections first. A callback closing its own runtime must never join itself.
        if (!ownedThreads.contains(Thread.currentThread())) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            try {
                for (var executor : List.of(observers, timer, workers))
                    executor.awaitTermination(
                            Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        executors.values().stream()
                .filter(ManagedExecutor.class::isInstance)
                .map(ManagedExecutor.class::cast)
                .forEach(
                        executor -> {
                            try {
                                executor.close();
                            } catch (RuntimeException error) {
                                reportError(null, error);
                            }
                        });
        // Backends and directly registered Executors are caller-owned and may be shared.
    }

    private Thread daemon(Runnable r, String name) {
        Thread t =
                new Thread(
                        () -> {
                            ownedThreads.add(Thread.currentThread());
                            try {
                                r.run();
                            } finally {
                                ownedThreads.remove(Thread.currentThread());
                            }
                        },
                        name);
        t.setDaemon(true);
        return t;
    }

    public static final class Builder {
        private SessionBackend backend;
        private Function<DurableProtocol.Request, JsonNode> transport;
        private final Map<String, ThreadBinding> definitions = new LinkedHashMap<>();
        private BiFunction<SessionKey, String, ThreadBinding> definitionResolver;
        private final Map<String, BiFunction<SessionBackend, CheckpointRestore, CommitReceipt>>
                restorers = new LinkedHashMap<>();

        /** Access a remote public operation service without an internal Backend or Brain. */
        public Builder transport(Function<DurableProtocol.Request, JsonNode> value) {
            transport = Objects.requireNonNull(value);
            return this;
        }

        /** Register frozen definition metadata without loading a local Agent or model. */
        public Builder definition(String name, String version, String stateVersion) {
            definitions.put(name, new ThreadBinding(name, version, stateVersion));
            return this;
        }

        public Builder definitionResolver(BiFunction<SessionKey, String, ThreadBinding> value) {
            definitionResolver = Objects.requireNonNull(value);
            return this;
        }

        public Builder checkpoints(
                String harness,
                BiFunction<SessionBackend, CheckpointRestore, CommitReceipt> value) {
            restorers.put(DurableJson.text(harness, "harness"), Objects.requireNonNull(value));
            return this;
        }

        private final Map<String, Registration> harnesses = new LinkedHashMap<>();
        private final Map<String, Registration> versions = new LinkedHashMap<>();
        private final Map<String, Function<ThreadSpec, HarnessAdapter>> resolvers =
                new LinkedHashMap<>();
        private final Map<String, Executor> executors = new LinkedHashMap<>();
        private Duration pollInterval = Duration.ofMillis(100);
        private Duration leaseDuration = Duration.ofSeconds(30);
        private RecoveryPolicy recoveryPolicy = RecoveryPolicy.defaults();
        private Predicate<Throwable> retryableFailure = DurableRuntime::transientFailure;
        private LiveEvents liveEvents = LiveEvents.local();
        private int parallelism = 4;
        private int observerParallelism = 2;
        private BiConsumer<SessionKey, Throwable> errors =
                (key, e) ->
                        System.getLogger(DurableRuntime.class.getName())
                                .log(System.Logger.Level.WARNING, "Durable invocation failed", e);

        public Builder backend(SessionBackend value) {
            backend = value;
            return this;
        }

        public Builder harness(String name, String version, HarnessAdapter adapter) {
            DurableJson.text(name, "harness");
            var registration = new Registration(name, version, adapter);
            versions.put(DurableJson.hash(List.of(name, version)), registration);
            harnesses.put(name, registration);
            definition(name, version, adapter.capabilities().stateVersion());
            return this;
        }

        /** Resolve frozen definition references; implementations must reject unavailable definitions. */
        public Builder resolver(String harness, Function<ThreadSpec, HarnessAdapter> resolver) {
            resolvers.put(DurableJson.text(harness, "harness"), Objects.requireNonNull(resolver));
            return this;
        }

        public Builder executor(String name, Executor executor) {
            executors.put(DurableJson.text(name, "executor"), Objects.requireNonNull(executor));
            return this;
        }

        public Builder pollInterval(Duration value) {
            pollInterval = Objects.requireNonNull(value);
            return this;
        }

        public Builder leaseDuration(Duration value) {
            leaseDuration = Objects.requireNonNull(value);
            return this;
        }

        /** Frozen in each Turn on its first recorded failure; retries survive host replacement. */
        public Builder recoveryPolicy(RecoveryPolicy value) {
            recoveryPolicy = Objects.requireNonNull(value);
            return this;
        }

        /** Pure host classification, for example provider-specific transient model failures. */
        public Builder retryableFailure(Predicate<Throwable> value) {
            retryableFailure = Objects.requireNonNull(value);
            return this;
        }

        /**
         * Configure best-effort live presentation delivery. The process-local default requires no
         * setup; split API/Brain deployments can provide a shared transport implementation.
         */
        public Builder liveEvents(LiveEvents value) {
            liveEvents = Objects.requireNonNull(value);
            return this;
        }

        public Builder parallelism(int value) {
            parallelism = value;
            return this;
        }

        /** Separate callback capacity: slow observers never occupy Agent execution workers. */
        public Builder observerParallelism(int value) {
            observerParallelism = value;
            return this;
        }

        public Builder onError(BiConsumer<SessionKey, Throwable> value) {
            errors = Objects.requireNonNull(value);
            return this;
        }

        public DurableRuntime build() {
            return new DurableRuntime(this);
        }
    }
}
