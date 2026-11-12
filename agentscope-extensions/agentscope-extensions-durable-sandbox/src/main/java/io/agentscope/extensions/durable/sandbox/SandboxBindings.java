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

package io.agentscope.extensions.durable.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.ExecutionResult;
import io.agentscope.durable.DurableTypes.OutputPage;
import io.agentscope.durable.DurableTypes.ToolCall;
import io.agentscope.durable.Executor;
import io.agentscope.durable.ToolBinding;
import io.agentscope.durable.store.RecordStore;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Optional persistent Sandbox lifecycle around existing Harness Sandbox clients.
 * Hosts supply frozen environment references, credentials and an authoritative cleanup barrier.
 * Resource creation is never retried after an uncertain outcome; attachment never calls start().
 */
public final class SandboxBindings implements AutoCloseable {
    private static final String ALIVE = "durable-sandbox-alive";
    private static final String CLEANUP = "durable-sandbox-cleanup";
    private static final String MARKER = ".durable-sandbox-identity";

    public record Binding(String namespace, String id, String version, JsonNode configuration) {
        public Binding {
            DurableJson.text(namespace, "Sandbox namespace");
            DurableJson.text(id, "Sandbox binding id");
            DurableJson.text(version, "Sandbox definition version");
            configuration = DurableJson.copy(configuration);
            if (!configuration.isObject())
                throw new IllegalArgumentException("Sandbox configuration must be an object");
        }

        @Override
        public JsonNode configuration() {
            return configuration.deepCopy();
        }

        public String key() {
            return "sandbox:" + DurableJson.hash(List.of(namespace, id));
        }
    }

    @FunctionalInterface
    public interface Action {
        void accept(Sandbox sandbox) throws Exception;
    }

    /**
     * All clients are borrowed. Creation returns a pre-start Sandbox; attach must never reconstruct it.
     * Identity names the physical original instance. Destroy must confirm absence and be idempotent.
     */
    public record Target(
            SandboxClient<?> client,
            Supplier<Sandbox> creation,
            Function<Sandbox, String> identity,
            Action keepAlive,
            Action destroy) {
        public Target {
            Objects.requireNonNull(client);
            Objects.requireNonNull(creation);
            Objects.requireNonNull(identity);
            Objects.requireNonNull(keepAlive);
            Objects.requireNonNull(destroy);
        }

        public static <O extends SandboxClientOptions> Target using(
                SandboxClient<O> client,
                WorkspaceSpec workspace,
                O options,
                Function<Sandbox, String> identity) {
            return new Target(
                    client,
                    () -> client.create(workspace, null, options),
                    identity,
                    sandbox -> {},
                    Sandbox::shutdown);
        }

        public Target withKeepAlive(Action action) {
            return new Target(client, creation, identity, action, destroy);
        }

        public Target withDestroy(Action action) {
            return new Target(client, creation, identity, keepAlive, action);
        }
    }

    public record Status(
            String creationId,
            String resourceId,
            boolean ready,
            boolean deletionRequested,
            boolean deleted,
            String reconciliationEvidence) {}

    public static final class State {
        public int format = 1;
        public Binding binding;
        public String digest;
        public String creationId;
        public String resourceId;
        public String sealed;
        public boolean ready;
        public boolean deletionRequested;
        public boolean deleted;
        public boolean destroying;
        public String cleanupOwner;
        public long cleanupUntil;
        public String reconciliationDigest;
        public String reconciliationEvidence;
    }

    private record Entry(
            String digest, Target target, Sandbox sandbox, DirectSandboxExecutor executor) {}

    private final RecordStore records;
    private final Function<Binding, Target> resolver;
    private final Function<String, String> seal;
    private final Function<String, String> unseal;
    private final Predicate<Binding> settled;
    private final Consumer<Throwable> errors;
    private final int capacity;
    private final Duration keepalive;
    private final Duration retry;
    private final Duration cleanupLease;
    private final LinkedHashMap<String, Entry> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile Thread maintenanceThread;
    private final ScheduledExecutorService maintenance =
            Executors.newSingleThreadScheduledExecutor(
                    action -> {
                        var thread = new Thread(action, "durable-sandbox-maintenance");
                        maintenanceThread = thread;
                        thread.setDaemon(true);
                        return thread;
                    });

    private SandboxBindings(Builder builder) {
        records = Objects.requireNonNull(builder.records, "stateStore");
        resolver = Objects.requireNonNull(builder.resolver, "resolver");
        seal = builder.seal;
        unseal = builder.unseal;
        settled = builder.settled;
        errors = builder.errors;
        capacity = builder.capacity;
        keepalive = builder.keepalive;
        retry = builder.retry;
        cleanupLease = builder.cleanupLease;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The tool target contains the neutral Sandbox operation under "operation". */
    public Executor executor(Binding resource, ToolBinding tool) {
        String operation =
                DurableJson.text(tool.target().required("operation").asText(), "Sandbox operation");
        return new Executor() {
            private void verify(ExecutionRequest request) {
                tool.verify(request);
                if (!resource.namespace().equals(request.session().namespace()))
                    throw new SecurityException("Sandbox binding namespace differs");
            }

            private ExecutionRequest mapped(ExecutionRequest request) {
                verify(request);
                return new ExecutionRequest(
                        request.executionId(),
                        request.thread(),
                        request.turnId(),
                        new ToolCall(
                                request.call().callId(),
                                request.call().executor(),
                                operation,
                                request.call().arguments(),
                                request.call().binding(),
                                request.call().origin()));
            }

            public Capabilities capabilities() {
                return new Capabilities(false, true, true, true);
            }

            public void validateStart(ExecutionRequest request) {
                verify(request);
                ensureOpen();
                var state = records.transaction(resource.key(), tx -> read(tx, resource));
                if (state != null && state.deletionRequested)
                    throw failure(
                            DurableException.Code.CONFLICT, "Sandbox deletion already requested");
                if (state == null)
                    Objects.requireNonNull(resolver.apply(resource), "Sandbox target");
            }

            public ExecutionResult startExecution(ExecutionRequest request) {
                var invocation = mapped(request);
                return entry(resource, true).executor().startExecution(invocation);
            }

            public ExecutionResult query(ExecutionRequest request, String handle) {
                var invocation = mapped(request);
                return entry(resource, false).executor().query(invocation, handle);
            }

            public ExecutionResult cancel(ExecutionRequest request, String handle) {
                var invocation = mapped(request);
                return entry(resource, false).executor().cancel(invocation, handle);
            }

            public OutputPage output(ExecutionRequest request, long offset) {
                var invocation = mapped(request);
                return entry(resource, false).executor().output(invocation, offset);
            }
        };
    }

    public Status status(Binding binding) {
        var state = records.transaction(binding.key(), tx -> read(tx, binding));
        return state == null
                ? null
                : new Status(
                        state.creationId,
                        state.resourceId,
                        state.ready,
                        state.deletionRequested,
                        state.deleted,
                        state.reconciliationEvidence);
    }

    public synchronized int cachedBindings() {
        return cache.size();
    }

    private static State read(RecordStore.Transaction tx, Binding binding) {
        var value = tx.get("");
        if (value == null) return null;
        var state = DurableJson.convert(value, State.class);
        if (state.format != 1)
            throw failure(DurableException.Code.INCOMPATIBLE, "Unknown Sandbox binding format");
        if (!DurableJson.hash(binding).equals(state.digest))
            throw failure(
                    DurableException.Code.INCOMPATIBLE, "Original Sandbox definition changed");
        return state;
    }

    private static State initial(Binding binding, String creation) {
        var state = new State();
        state.binding = binding;
        state.digest = DurableJson.hash(binding);
        state.creationId = creation;
        return state;
    }

    private Entry entry(Binding binding, boolean create) {
        ensureOpen();
        String token = UUID.randomUUID().toString();
        var state = records.transaction(binding.key(), tx -> available(tx, binding, create));
        Entry cached;
        synchronized (this) {
            cached = cache.get(binding.key());
        }
        if (cached != null && state != null && cached.digest().equals(state.digest)) {
            try {
                verifyIdentity(cached.target(), cached.sandbox(), state.resourceId);
                verifyMarker(cached.sandbox(), state.creationId);
                return cached;
            } catch (Exception failure) {
                synchronized (this) {
                    cache.remove(binding.key(), cached);
                }
                if (failure instanceof DurableException durable) throw durable;
                throw new DurableException(
                        DurableException.Code.UNAVAILABLE, "Original Sandbox unavailable", failure);
            }
        }
        // Resolving credentials/configuration has no resource side effect. A transient resolver
        // failure must not leave an irreversible "creation attempted" receipt.
        var target = Objects.requireNonNull(resolver.apply(binding), "Sandbox target");
        if (state == null)
            state =
                    records.transaction(
                            binding.key(),
                            tx -> {
                                var saved = available(tx, binding, create);
                                if (saved == null) {
                                    saved = initial(binding, token);
                                    tx.put("", DurableJson.value(saved));
                                    tx.schedule(
                                            ALIVE,
                                            binding.key(),
                                            tx.now() + retry.toMillis(),
                                            DurableJson.value(binding));
                                }
                                return saved;
                            });
        Sandbox sandbox;
        try {
            if (token.equals(state.creationId)) {
                sandbox = target.creation().get();
                try {
                    sandbox.start();
                    String id =
                            DurableJson.text(
                                    target.identity().apply(sandbox), "Sandbox resource identity");
                    var marker = sandbox.exec(null, "printf '%s' '" + token + "' > " + MARKER, 30);
                    if (!marker.ok() || marker.truncated())
                        throw failure(
                                DurableException.Code.UNAVAILABLE,
                                "Sandbox identity write is unconfirmed");
                    persistResource(
                            binding,
                            token,
                            id,
                            target.client().serializeState(sandbox.getState()),
                            true);
                } finally {
                    // Preserve a known physical identity even if initialization/marker response was
                    // lost.
                    String id = target.identity().apply(sandbox);
                    if (id != null && !id.isBlank())
                        persistResource(
                                binding,
                                token,
                                id,
                                target.client().serializeState(sandbox.getState()),
                                false);
                }
            } else {
                if (state.sealed == null)
                    throw failure(
                            DurableException.Code.UNAVAILABLE,
                            "Sandbox creation outcome unknown; reconcile original creation");
                sandbox =
                        target.client()
                                .resume(
                                        target.client()
                                                .deserializeState(unseal.apply(state.sealed)));
                verifyIdentity(target, sandbox, state.resourceId);
                target.keepAlive().accept(sandbox);
                verifyMarker(sandbox, state.creationId);
                if (!state.ready)
                    persistResource(
                            binding,
                            state.creationId,
                            state.resourceId,
                            target.client().serializeState(sandbox.getState()),
                            true);
            }
            verifyIdentity(
                    target,
                    sandbox,
                    state.resourceId == null ? target.identity().apply(sandbox) : state.resourceId);
            var result =
                    new Entry(
                            state.digest,
                            target,
                            sandbox,
                            new DirectSandboxExecutor(target.identity().apply(sandbox), sandbox));
            synchronized (this) {
                if (!closed.get()) {
                    cache.put(binding.key(), result);
                    while (cache.size() > capacity) cache.remove(cache.keySet().iterator().next());
                }
            }
            return result;
        } catch (DurableException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new DurableException(
                    DurableException.Code.UNAVAILABLE, "Original Sandbox unavailable", failure);
        }
    }

    private static State available(RecordStore.Transaction tx, Binding binding, boolean create) {
        var state = read(tx, binding);
        if (state == null) {
            if (!create)
                throw failure(
                        DurableException.Code.UNAVAILABLE,
                        "Original Sandbox has no creation receipt");
            return null;
        }
        if (state.deleted || state.destroying)
            throw failure(
                    DurableException.Code.UNAVAILABLE, "Sandbox is deleted or being destroyed");
        if (create && state.deletionRequested)
            throw failure(DurableException.Code.CONFLICT, "Sandbox deletion already requested");
        return state;
    }

    private void persistResource(
            Binding binding, String creation, String resource, String serialized, boolean ready) {
        String protectedState =
                Objects.requireNonNull(seal.apply(serialized), "sealed Sandbox state");
        records.transaction(
                binding.key(),
                tx -> {
                    var state = read(tx, binding);
                    if (state == null || state.deleted || !creation.equals(state.creationId))
                        throw failure(
                                DurableException.Code.FENCED, "Sandbox creation ownership changed");
                    if (state.resourceId != null && !resource.equals(state.resourceId))
                        throw failure(
                                DurableException.Code.INCOMPATIBLE,
                                "Sandbox physical identity changed");
                    state.resourceId = resource;
                    state.sealed = protectedState;
                    state.ready |= ready;
                    tx.put("", DurableJson.value(state));
                    tx.schedule(
                            ALIVE,
                            binding.key(),
                            tx.now() + keepalive.toMillis(),
                            DurableJson.value(binding));
                    return null;
                });
    }

    private static void verifyIdentity(Target target, Sandbox sandbox, String expected) {
        if (expected == null || !expected.equals(target.identity().apply(sandbox)))
            throw failure(
                    DurableException.Code.INCOMPATIBLE,
                    "Original Sandbox physical identity differs");
    }

    private static void verifyMarker(Sandbox sandbox, String creation) throws Exception {
        var marker = sandbox.exec(null, "cat " + MARKER, 30);
        if (!marker.ok() || marker.truncated() || !creation.equals(marker.stdout().trim()))
            throw failure(
                    DurableException.Code.INCOMPATIBLE,
                    "Original Sandbox workspace evidence is missing");
    }

    /** Host-authorized recovery only: verifies original physical/workspace identity, never creates or starts. */
    public void reconcileCreation(
            Binding binding,
            String creationId,
            String resourceId,
            String serializedState,
            String evidenceRef) {
        ensureOpen();
        DurableJson.text(evidenceRef, "Sandbox reconciliation evidence");
        String digest =
                DurableJson.hash(List.of(creationId, resourceId, serializedState, evidenceRef));
        var state = records.transaction(binding.key(), tx -> read(tx, binding));
        if (state == null || !creationId.equals(state.creationId) || state.deleted)
            throw failure(DurableException.Code.CONFLICT, "No matching uncertain Sandbox creation");
        if (state.reconciliationDigest != null) {
            if (!digest.equals(state.reconciliationDigest))
                throw failure(DurableException.Code.CONFLICT, "Sandbox reconciliation differs");
            return;
        }
        var target = resolver.apply(binding);
        try {
            var sandbox = target.client().resume(target.client().deserializeState(serializedState));
            verifyIdentity(target, sandbox, resourceId);
            verifyMarker(sandbox, creationId);
        } catch (DurableException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new DurableException(
                    DurableException.Code.UNAVAILABLE,
                    "Original Sandbox evidence unavailable",
                    failure);
        }
        String protectedState = seal.apply(serializedState);
        records.transaction(
                binding.key(),
                tx -> {
                    var current = read(tx, binding);
                    if (current.deleted
                            || !creationId.equals(current.creationId)
                            || current.resourceId != null && !resourceId.equals(current.resourceId))
                        throw failure(
                                DurableException.Code.CONFLICT,
                                "Sandbox reconciliation raced with lifecycle change");
                    if (current.reconciliationDigest != null
                            && !digest.equals(current.reconciliationDigest))
                        throw failure(
                                DurableException.Code.CONFLICT, "Sandbox reconciliation differs");
                    current.resourceId = resourceId;
                    current.sealed = protectedState;
                    current.ready = true;
                    current.reconciliationDigest = digest;
                    current.reconciliationEvidence = evidenceRef;
                    tx.put("", DurableJson.value(current));
                    tx.schedule(ALIVE, binding.key(), tx.now(), DurableJson.value(binding));
                    return null;
                });
    }

    /** Persist deletion intent before evaluating the host's barrier; subsequent new starts are rejected. */
    public void requestDeletion(Binding binding) {
        ensureOpen();
        records.transaction(
                binding.key(),
                tx -> {
                    var state = read(tx, binding);
                    if (state == null) {
                        state = initial(binding, UUID.randomUUID().toString());
                        state.deleted = true;
                    }
                    state.deletionRequested = true;
                    tx.put("", DurableJson.value(state));
                    tx.schedule(
                            CLEANUP,
                            binding.key(),
                            state.deleted ? -1 : tx.now(),
                            DurableJson.value(binding));
                    if (state.deleted) tx.schedule(ALIVE, binding.key(), -1, null);
                    return null;
                });
    }

    /** One bounded sweep. Safe to call from an external scheduler instead of start(). */
    public void tick() {
        ensureOpen();
        for (var work : records.ready(CLEANUP, 32)) {
            var binding = DurableJson.convert(work.value(), Binding.class);
            try {
                cleanup(binding);
            } catch (RuntimeException failure) {
                report(failure);
            }
        }
        for (var work : records.ready(ALIVE, 32)) {
            var binding = DurableJson.convert(work.value(), Binding.class);
            try {
                var value = entry(binding, false);
                value.target().keepAlive().accept(value.sandbox());
                reschedule(binding, ALIVE, keepalive);
            } catch (Exception failure) {
                synchronized (this) {
                    cache.remove(binding.key());
                }
                reschedule(binding, ALIVE, retry);
                report(failure);
            }
        }
    }

    private void cleanup(Binding binding) {
        String owner = UUID.randomUUID().toString();
        var claimed =
                records.transaction(
                        binding.key(),
                        tx -> {
                            var state = read(tx, binding);
                            if (state == null
                                    || state.deleted
                                    || !state.deletionRequested
                                    || state.cleanupOwner != null && state.cleanupUntil > tx.now())
                                return null;
                            // A never-confirmed creation cannot be destroyed by creating a
                            // replacement.
                            if (state.resourceId == null || state.sealed == null) {
                                tx.schedule(
                                        CLEANUP,
                                        binding.key(),
                                        tx.now() + retry.toMillis(),
                                        DurableJson.value(binding));
                                return null;
                            }
                            state.cleanupOwner = owner;
                            state.cleanupUntil = tx.now() + cleanupLease.toMillis();
                            tx.put("", DurableJson.value(state));
                            tx.schedule(
                                    CLEANUP,
                                    binding.key(),
                                    state.cleanupUntil,
                                    DurableJson.value(binding));
                            return state;
                        });
        if (claimed == null) return;
        boolean destroyed = false;
        try {
            if (!settled.test(binding)) return;
            boolean owned =
                    records.transaction(
                            binding.key(),
                            tx -> {
                                var current = read(tx, binding);
                                if (!owner.equals(current.cleanupOwner)
                                        || current.cleanupUntil <= tx.now()) return false;
                                current.destroying = true;
                                tx.put("", DurableJson.value(current));
                                return true;
                            });
            if (!owned) return;
            var target = resolver.apply(binding);
            var sandbox =
                    target.client()
                            .resume(target.client().deserializeState(unseal.apply(claimed.sealed)));
            verifyIdentity(target, sandbox, claimed.resourceId);
            target.destroy().accept(sandbox);
            destroyed = true;
        } catch (Exception failure) {
            report(failure);
        } finally {
            boolean completed = destroyed;
            records.transaction(
                    binding.key(),
                    tx -> {
                        var current = read(tx, binding);
                        if (!owner.equals(current.cleanupOwner)) return null;
                        current.cleanupOwner = null;
                        current.cleanupUntil = 0;
                        if (completed) {
                            current.deleted = true;
                            tx.schedule(ALIVE, binding.key(), -1, null);
                        }
                        tx.put("", DurableJson.value(current));
                        tx.schedule(
                                CLEANUP,
                                binding.key(),
                                completed ? -1 : tx.now() + retry.toMillis(),
                                DurableJson.value(binding));
                        return null;
                    });
            if (destroyed)
                synchronized (this) {
                    cache.remove(binding.key());
                }
        }
    }

    private void reschedule(Binding binding, String queue, Duration delay) {
        records.transaction(
                binding.key(),
                tx -> {
                    var state = read(tx, binding);
                    tx.schedule(
                            queue,
                            binding.key(),
                            state == null || state.deleted ? -1 : tx.now() + delay.toMillis(),
                            DurableJson.value(binding));
                    return null;
                });
    }

    public synchronized void start() {
        ensureOpen();
        if (started.compareAndSet(false, true))
            maintenance.scheduleWithFixedDelay(
                    () -> {
                        try {
                            tick();
                        } catch (RuntimeException failure) {
                            if (!closed.get()) report(failure);
                        }
                    },
                    0,
                    Math.max(1, Math.min(keepalive.toMillis(), retry.toMillis())),
                    TimeUnit.MILLISECONDS);
    }

    private void report(Throwable failure) {
        try {
            errors.accept(failure);
        } catch (RuntimeException observerFailure) {
            System.getLogger(SandboxBindings.class.getName())
                    .log(
                            System.Logger.Level.WARNING,
                            "Sandbox error observer failed",
                            observerFailure);
        }
    }

    private void ensureOpen() {
        if (closed.get())
            throw failure(DurableException.Code.UNAVAILABLE, "Sandbox bindings closed");
    }

    /** Stops only this process's maintenance and drops cached handles. Never calls Sandbox.close(). */
    public void close() {
        synchronized (this) {
            if (!closed.compareAndSet(false, true)) return;
            maintenance.shutdownNow();
        }
        if (Thread.currentThread() != maintenanceThread)
            try {
                maintenance.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        synchronized (this) {
            cache.clear();
        }
    }

    private static DurableException failure(DurableException.Code code, String message) {
        return new DurableException(code, message);
    }

    public static final class Builder {
        private RecordStore records;
        private Function<Binding, Target> resolver;
        private Function<String, String> seal = Function.identity(), unseal = Function.identity();
        private Predicate<Binding> settled = binding -> false;
        private int capacity = 128;
        private Duration keepalive = Duration.ofSeconds(60),
                retry = Duration.ofSeconds(5),
                cleanupLease = Duration.ofSeconds(30);
        private Consumer<Throwable> errors =
                failure ->
                        System.getLogger(SandboxBindings.class.getName())
                                .log(
                                        System.Logger.Level.WARNING,
                                        "Sandbox lifecycle pending",
                                        failure);

        /** Persistent Sandbox resource state. The store remains caller-owned. */
        public Builder stateStore(RecordStore value) {
            records = Objects.requireNonNull(value);
            return this;
        }

        public Builder resolver(Function<Binding, Target> value) {
            resolver = Objects.requireNonNull(value);
            return this;
        }

        public Builder protect(Function<String, String> encrypt, Function<String, String> decrypt) {
            seal = Objects.requireNonNull(encrypt);
            unseal = Objects.requireNonNull(decrypt);
            return this;
        }

        /** Must include every admitted operation sharing this resource, including cancellation and unknown effects. */
        public Builder canDelete(Predicate<Binding> barrier) {
            settled = Objects.requireNonNull(barrier);
            return this;
        }

        public Builder cacheCapacity(int value) {
            if (value < 1 || value > 4096)
                throw new IllegalArgumentException("Sandbox cache capacity must be 1..4096");
            capacity = value;
            return this;
        }

        public Builder keepaliveInterval(Duration value) {
            keepalive = positive(value);
            return this;
        }

        public Builder retryInterval(Duration value) {
            retry = positive(value);
            return this;
        }

        public Builder cleanupLease(Duration value) {
            cleanupLease = positive(value);
            return this;
        }

        public Builder onError(Consumer<Throwable> value) {
            errors = Objects.requireNonNull(value);
            return this;
        }

        public SandboxBindings build() {
            return new SandboxBindings(this);
        }

        private static Duration positive(Duration value) {
            if (value == null || value.toMillis() < 1)
                throw new IllegalArgumentException("Positive millisecond duration required");
            return value;
        }
    }
}
