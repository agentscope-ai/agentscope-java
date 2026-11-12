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

import io.agentcore.managed.harness.runtime.SessionServiceClient;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/** One non-revivable lease per process; execution fragments carry separate fixed deadlines. */
public final class ManagedProcessOwnership implements AutoCloseable {
    private final ManagedWorkerLeaseStore store;
    private final ManagedWorkerLeaseStore.Lease lease;
    private SessionServiceClient remote;
    private boolean attached;
    private final JdbcTemplate jdbc;
    private final Duration ttl;
    private final long fragmentMillis;
    private final long toolMillis;
    private final ScheduledExecutorService renewal;
    private volatile boolean closed;
    private final AtomicBoolean lost = new AtomicBoolean();
    private volatile Runnable onExpired = () -> {};

    public void onExpired(Runnable action) {
        onExpired = action;
    }

    private void expired() {
        closed = true;
        if (renewal != null) renewal.shutdown();
        if (lost.compareAndSet(false, true)) onExpired.run();
    }

    private volatile long validUntil;

    public ManagedProcessOwnership(
            DataSource source, String role, Duration ttl, Duration fragment, Duration tool) {
        this.store = new ManagedWorkerLeaseStore(source);
        this.jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(5);
        this.ttl = ttl;
        fragmentMillis = ManagedMessageBus.positiveMillis(fragment);
        toolMillis = ManagedMessageBus.positiveMillis(tool);
        lease = store.register(role, UUID.randomUUID().toString(), ttl);
        validUntil = System.nanoTime() + ttl.toNanos();
        renewal =
                Executors.newSingleThreadScheduledExecutor(
                        task -> {
                            var thread = new Thread(task, "worker-process-lease");
                            thread.setDaemon(true);
                            return thread;
                        });
        renewal.scheduleWithFixedDelay(
                this::renew, ttl.toMillis() / 3, ttl.toMillis() / 3, TimeUnit.MILLISECONDS);
    }

    /** Brain has no database access for process liveness. Registration retries retain one identity. */
    public ManagedProcessOwnership(
            SessionServiceClient remote, Duration ttl, Duration fragment, Duration tool) {
        this.store = null;
        this.jdbc = null;
        this.remote = remote;
        this.ttl = ttl;
        fragmentMillis = ManagedMessageBus.positiveMillis(fragment);
        toolMillis = ManagedMessageBus.positiveMillis(tool);
        lease =
                new ManagedWorkerLeaseStore.Lease(
                        UUID.randomUUID().toString(),
                        "agent-harness-runtime",
                        UUID.randomUUID().toString());
        long before = System.nanoTime();
        long remaining;
        try {
            remaining = remoteLease("process-register");
        } catch (SessionServiceClient.Unavailable uncertain) {
            remaining = remoteLease("process-register");
        }
        validUntil = before + Duration.ofMillis(Math.min(ttl.toMillis(), remaining)).toNanos();
        renewal =
                Executors.newSingleThreadScheduledExecutor(
                        task -> {
                            var thread = new Thread(task, "remote-process-lease");
                            thread.setDaemon(true);
                            return thread;
                        });
        checkLocal();
        renewal.scheduleWithFixedDelay(
                this::renew, ttl.toMillis() / 3, ttl.toMillis() / 3, TimeUnit.MILLISECONDS);
    }

    private ManagedProcessOwnership(
            DataSource source,
            ManagedWorkerLeaseStore.Lease lease,
            Duration fragment,
            Duration tool) {
        store = new ManagedWorkerLeaseStore(source);
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(5);
        this.lease = lease;
        this.ttl = null;
        fragmentMillis = ManagedMessageBus.positiveMillis(fragment);
        toolMillis = ManagedMessageBus.positiveMillis(tool);
        renewal = null;
        attached = true;
    }

    /** Request-scoped view: validates the original process, never registers or renews one. */
    public static ManagedProcessOwnership attached(
            DataSource source,
            ManagedWorkerLeaseStore.Lease lease,
            Duration fragment,
            Duration tool) {
        return new ManagedProcessOwnership(source, lease, fragment, tool);
    }

    public ManagedWorkerLeaseStore.Lease identity() {
        return lease;
    }

    private long remoteLease(String operation) {
        return ManagedServiceCalls.coordinate(
                        remote, operation, Map.of("process", lease, "ttlMillis", ttl.toMillis()))
                .longValue();
    }

    public Duration toolLifetime() {
        return Duration.ofMillis(toolMillis);
    }

    public void checkLocal() {
        if (!closed && System.nanoTime() >= validUntil) expired();
        if (closed)
            throw new ManagedStateException("worker_lease_expired", "Process lease has expired");
    }

    record ClaimOwner(String owner, String token, Long deadline) {}

    ClaimOwner guardClaim(
            String table, String idColumn, ManagedScope scope, String id, String token) {
        var claim = claim(table, idColumn, scope, id);
        if (claim.owner() != null) {
            // The enclosing whileValid transaction already checked our own lease. Upgrading its
            // key-share lock to SHARE here would block our renewal during normal resumptions.
            Long expiry =
                    incarnation().equals(claim.owner())
                            ? Long.MAX_VALUE
                            : store.lockIncarnation(claim.owner());
            long now = store.now();
            if (expiry != null
                    && expiry > now
                    && claim.deadline() != null
                    && claim.deadline() > now
                    && !token.equals(claim.token()))
                throw new ManagedStateException(
                        "execution_busy", "A live durable owner holds this execution");
        }
        return claim;
    }

    void verifyClaim(
            ClaimOwner expected, String table, String idColumn, ManagedScope scope, String id) {
        if (!expected.equals(claim(table, idColumn, scope, id)))
            throw new ManagedStateException(
                    "execution_busy", "Execution owner changed while claiming");
    }

    private ClaimOwner claim(String table, String idColumn, ManagedScope scope, String id) {
        String token = table.equals("ma.turn") ? "execution_token" : "worker_token";
        var row =
                jdbc.queryForMap(
                        "SELECT owner_incarnation,"
                                + token
                                + ",execution_deadline FROM "
                                + table
                                + " WHERE scope_key=? AND "
                                + idColumn
                                + "=?",
                        scope.messageGroup(),
                        id);
        return new ClaimOwner(
                (String) row.get("owner_incarnation"),
                (String) row.get(token),
                row.get("execution_deadline") == null
                        ? null
                        : ((Number) row.get("execution_deadline")).longValue());
    }

    public String incarnation() {
        return lease.incarnation();
    }

    public long deadline(boolean tool) {
        return Math.addExact(store.now(), tool ? toolMillis : fragmentMillis);
    }

    public <T> T transaction(Supplier<T> work) {
        if (closed)
            throw new ManagedStateException("worker_lease_expired", "Process lease is retired");
        if (remote != null)
            throw new IllegalStateException(
                    "Brain metadata transactions belong to Session Service");
        return store.whileValid(lease, work);
    }

    public void requireTurn(ManagedScope scope, String turn) {
        var rows =
                jdbc.queryForList(
                        "SELECT owner_incarnation,execution_deadline FROM ma.turn WHERE scope_key=?"
                                + " AND turn_id=?",
                        scope.messageGroup(),
                        turn);
        requireTurn(rows.isEmpty() ? null : rows.get(0));
    }

    /** Reuse a Turn read after locking its Session in this same execution transaction. */
    void requireTurn(Map<String, Object> turn) {
        if (turn == null
                || !incarnation().equals(turn.get("owner_incarnation"))
                || turn.get("execution_deadline") == null
                || ((Number) turn.get("execution_deadline")).longValue() <= store.now())
            throw new ManagedStateException(
                    "stale_execution", "Turn owner or fragment deadline expired");
    }

    public void requireTool(ManagedScope scope, String task, String token) {
        var rows =
                jdbc.queryForList(
                        "SELECT owner_incarnation,execution_deadline,worker_token FROM"
                                + " ma.hands_execution WHERE scope_key=? AND task_id=?",
                        scope.messageGroup(),
                        task);
        if (rows.isEmpty()
                || !incarnation().equals(rows.get(0).get("owner_incarnation"))
                || !token.equals(rows.get(0).get("worker_token"))
                || rows.get(0).get("execution_deadline") == null
                || ((Number) rows.get(0).get("execution_deadline")).longValue() <= store.now())
            throw new ManagedStateException(
                    "stale_execution", "Tool owner or collector deadline expired");
    }

    private void renew() {
        if (closed) return;
        try {
            long before = System.nanoTime();
            checkLocal();
            long remaining =
                    remote == null
                            ? (store.renew(lease, ttl) ? ttl.toMillis() : 0)
                            : remoteLease("process-renew");
            if (remaining <= 0 || closed) {
                expired();
            } else {
                validUntil =
                        before + Duration.ofMillis(Math.min(ttl.toMillis(), remaining)).toNanos();
                checkLocal();
            }
        } catch (RuntimeException unavailable) {
            LoggerFactory.getLogger(ManagedProcessOwnership.class)
                    .warn(
                            "Worker process lease renewal unavailable for incarnation {}",
                            lease.incarnation(),
                            unavailable);
            if (System.nanoTime() >= validUntil) expired();
        }
    }

    @Override
    public void close() {
        closed = true;
        if (renewal != null) renewal.shutdownNow();
        if (attached) return;
        if (remote == null) store.retire(lease);
        else ManagedServiceCalls.coordinate(remote, "process-retire", Map.of("process", lease));
    }
}
