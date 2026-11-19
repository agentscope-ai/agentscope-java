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
package io.agentscope.extensions.agentprotocol;

import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ProtocolTaskRunRegistry} keeping leases, cancel intents and nothing else under a {@link
 * BaseStore}, so that every protocol instance reads the same ownership facts.
 *
 * <p>Layout: one entry per lease under {@code [agent-protocol, task-run-registry-leases]}, keyed by the same opaque id
 * the task store passes ({@code taskId}, or {@code session:<id>} for the one-writer session guard),
 * holding {@code owner_id}, {@code lease_id} and {@code expires_at}. Cancel intents live under
 * {@code [agent-protocol, task-run-registry-cancels]} under that same key: a renewal rewrites the lease value as a
 * whole, so an intent stored inside it would be lost by the next heartbeat.
 *
 * <p>Leases carry their deadline in the value rather than relying on a store TTL, because {@link
 * BaseStore} has none. An expired entry is overwritten by the next acquisition and otherwise left
 * alone, which keeps the table proportional to the number of distinct task and session ids.
 *
 * <p>Correctness depends on the store: {@link BaseStore#supportsAtomicJournalStorage()} decides
 * whether acquisitions and renewals use compare-and-set ({@link BaseStore#putIfVersion}) or fall back
 * to a write followed by a read-back that lets the stored value decide. The fallback still detects a
 * race instead of silently overwriting the faster writer, but it cannot prevent two instances from
 * acquiring in the same window, so a store without compare-and-set is only safe when the runs are
 * effectively serialized by other means. Instances must also agree on the clock closely enough that
 * a lease is not judged expired early; the configured TTL is an order of magnitude above the
 * heartbeat for that reason.
 *
 * <p>What stays process-local, as the interface requires: the cancel handlers, and the owner
 * identifier this instance reports.
 *
 * <p>{@link #release} reads the entry, refuses to touch a lease another acquisition now owns, and
 * then deletes it. There is no delete-if-version in {@link BaseStore}, so a takeover can slip
 * between the check and the delete; the window is a single round trip and only opens once the lease
 * has already expired, which is the one case where the releasing side has lost ownership.
 */
public final class BaseStoreProtocolTaskRunRegistry implements ProtocolTaskRunRegistry {

    private static final List<String> LEASES =
            List.of("agent-protocol", "task-run-registry-leases");
    private static final List<String> CANCELS =
            List.of("agent-protocol", "task-run-registry-cancels");

    private static final String OWNER_ID = "owner_id";
    private static final String LEASE_ID = "lease_id";
    private static final String EXPIRES_AT = "expires_at";
    private static final String REQUESTED_AT = "requested_at";

    private static final int MAX_CAS_ATTEMPTS = 5;
    private static final Duration DEFAULT_LEASE_TTL = Duration.ofMinutes(1);

    private final BaseStore store;
    private final boolean atomic;
    private final String ownerId;
    private final Clock clock;
    private final Map<String, Runnable> cancelHandlers = new ConcurrentHashMap<>();

    /** Creates a registry that identifies this JVM in every lease it holds. */
    public BaseStoreProtocolTaskRunRegistry(BaseStore store) {
        this(store, defaultOwnerId(), Clock.systemUTC());
    }

    BaseStoreProtocolTaskRunRegistry(BaseStore store, String ownerId, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.atomic = store.supportsAtomicJournalStorage();
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Process-local registry, equivalent to a single-instance deployment. */
    public static BaseStoreProtocolTaskRunRegistry inMemory() {
        return new BaseStoreProtocolTaskRunRegistry(new InMemoryStore());
    }

    /**
     * Identifier this registry reports as lease owner: {@code <host>:<pid>:<random>}.
     *
     * <p>Derived rather than configured, because instance identity is not a deployment decision:
     * every instance must report its own value. It is informative only — ownership is decided by the
     * per-acquisition lease id — but it is what lets a lease read from a shared store name the
     * instance holding it.
     */
    public String ownerId() {
        return ownerId;
    }

    private static String defaultOwnerId() {
        return hostName() + ":" + ProcessHandle.current().pid() + ":" + shortId();
    }

    private static String hostName() {
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isBlank()) {
            host = System.getProperty("hostname");
        }
        if (host != null && !host.isBlank()) {
            return host;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown-host";
        }
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @Override
    public Optional<ProtocolTaskLease> tryAcquire(String taskId, Duration leaseTtl) {
        Objects.requireNonNull(taskId, "taskId");
        Instant now = clock.instant();
        ProtocolTaskLease candidate =
                new ProtocolTaskLease(
                        taskId,
                        ownerId,
                        UUID.randomUUID().toString(),
                        now.plus(positiveOr(leaseTtl)));
        if (!acquire(taskId, candidate, now)) {
            return Optional.empty();
        }
        // A new acquisition supersedes a cancel intent left behind by a previous holder.
        store.delete(CANCELS, taskId);
        return Optional.of(candidate);
    }

    private boolean acquire(String key, ProtocolTaskLease candidate, Instant now) {
        if (!atomic) {
            return acquireWithoutCas(key, candidate, now);
        }
        for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
            StoreItem current = store.get(LEASES, key);
            if (current == null) {
                if (store.putIfVersion(LEASES, key, encode(candidate), 0L)) {
                    return true;
                }
                continue;
            }
            ProtocolTaskLease held = decode(key, current.value());
            if (held != null && held.expiresAt().isAfter(now)) {
                return false;
            }
            if (store.putIfVersion(LEASES, key, encode(candidate), current.version())) {
                return true;
            }
        }
        return false;
    }

    private boolean acquireWithoutCas(String key, ProtocolTaskLease candidate, Instant now) {
        StoreItem current = store.get(LEASES, key);
        if (current != null) {
            ProtocolTaskLease held = decode(key, current.value());
            if (held != null && held.expiresAt().isAfter(now)) {
                return false;
            }
        }
        store.put(LEASES, key, encode(candidate));
        StoreItem written = store.get(LEASES, key);
        ProtocolTaskLease winner = decode(key, written == null ? null : written.value());
        return winner != null && candidate.leaseId().equals(winner.leaseId());
    }

    @Override
    public boolean renew(ProtocolTaskLease lease, Duration leaseTtl) {
        if (lease == null) {
            return false;
        }
        Instant now = clock.instant();
        Instant expiresAt = now.plus(positiveOr(leaseTtl));
        StoreItem current = store.get(LEASES, lease.taskId());
        if (!heldBy(current, lease)) {
            return false;
        }
        if (!atomic) {
            ProtocolTaskLease renewed = renewed(lease, expiresAt, current.value());
            store.put(LEASES, lease.taskId(), encode(renewed));
            return heldBy(store.get(LEASES, lease.taskId()), lease);
        }
        for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
            StoreItem held = store.get(LEASES, lease.taskId());
            if (!heldBy(held, lease)) {
                return false;
            }
            ProtocolTaskLease renewed = renewed(lease, expiresAt, held.value());
            if (store.putIfVersion(LEASES, lease.taskId(), encode(renewed), held.version())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void release(ProtocolTaskLease lease) {
        if (lease == null) {
            return;
        }
        StoreItem current = store.get(LEASES, lease.taskId());
        ProtocolTaskLease held = decode(lease.taskId(), current == null ? null : current.value());
        if (held == null || !held.leaseId().equals(lease.leaseId())) {
            // Someone else owns it now (or it is already gone): never delete their lease.
            return;
        }
        if (!held.expiresAt().isAfter(clock.instant())) {
            return;
        }
        store.delete(LEASES, lease.taskId());
        store.delete(CANCELS, lease.taskId());
    }

    @Override
    public Optional<ProtocolTaskLease> find(String taskId) {
        if (taskId == null) {
            return Optional.empty();
        }
        StoreItem item = store.get(LEASES, taskId);
        ProtocolTaskLease held = decode(taskId, item == null ? null : item.value());
        if (held == null || !held.expiresAt().isAfter(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(held);
    }

    @Override
    public void bindCancelHandler(String taskId, Runnable handler) {
        if (taskId == null || handler == null) {
            return;
        }
        cancelHandlers.put(taskId, handler);
    }

    @Override
    public void unbindCancelHandler(String taskId) {
        if (taskId != null) {
            cancelHandlers.remove(taskId);
        }
    }

    @Override
    public boolean requestCancel(String taskId) {
        if (taskId == null) {
            return false;
        }
        Runnable handler = cancelHandlers.get(taskId);
        if (handler == null && find(taskId).isEmpty()) {
            // Nothing is running for this task here, and no handler can deliver the intent later.
            return false;
        }
        store.put(CANCELS, taskId, Map.of(REQUESTED_AT, clock.millis()));
        if (handler != null) {
            handler.run();
        }
        return true;
    }

    @Override
    public boolean isCancelled(String taskId) {
        return taskId != null && store.get(CANCELS, taskId) != null;
    }

    private static boolean heldBy(StoreItem item, ProtocolTaskLease lease) {
        if (item == null) {
            return false;
        }
        ProtocolTaskLease held = decode(lease.taskId(), item.value());
        return held != null && held.leaseId().equals(lease.leaseId());
    }

    private static ProtocolTaskLease renewed(
            ProtocolTaskLease lease, Instant expiresAt, Map<String, Object> stored) {
        ProtocolTaskLease held = decode(lease.taskId(), stored);
        String owner = held != null ? held.ownerId() : lease.ownerId();
        return new ProtocolTaskLease(lease.taskId(), owner, lease.leaseId(), expiresAt);
    }

    private static Map<String, Object> encode(ProtocolTaskLease lease) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put(OWNER_ID, lease.ownerId());
        value.put(LEASE_ID, lease.leaseId());
        value.put(EXPIRES_AT, lease.expiresAt().toEpochMilli());
        return value;
    }

    private static ProtocolTaskLease decode(String key, Map<String, Object> value) {
        if (value == null) {
            return null;
        }
        Object leaseId = value.get(LEASE_ID);
        Object expiresAt = value.get(EXPIRES_AT);
        if (leaseId == null || !(expiresAt instanceof Number deadline)) {
            return null;
        }
        Object owner = value.get(OWNER_ID);
        return new ProtocolTaskLease(
                key,
                owner == null ? "" : String.valueOf(owner),
                String.valueOf(leaseId),
                Instant.ofEpochMilli(deadline.longValue()));
    }

    private static Duration positiveOr(Duration leaseTtl) {
        if (leaseTtl == null || leaseTtl.isZero() || leaseTtl.isNegative()) {
            return DEFAULT_LEASE_TTL;
        }
        return leaseTtl;
    }
}
