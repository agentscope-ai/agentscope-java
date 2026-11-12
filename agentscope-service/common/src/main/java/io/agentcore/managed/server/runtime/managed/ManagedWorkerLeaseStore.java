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

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** Process-level liveness. Callers lock this row before execution rows in the same short transaction. */
public final class ManagedWorkerLeaseStore {
    public record Lease(String incarnation, String role, String instance) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final boolean postgres;

    public ManagedWorkerLeaseStore(DataSource source) {
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(5);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setTimeout(5);
        try (var connection = source.getConnection()) {
            postgres = "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName());
        } catch (Exception unavailable) {
            throw new IllegalStateException("Cannot identify lease database", unavailable);
        }
    }

    public static void initialize(DataSource source) {
        new ResourceDatabasePopulator(new ClassPathResource("managed-worker-leases.sql"))
                .execute(source);
    }

    public Lease register(String role, String instance, Duration ttl) {
        ManagedScope.requireText(role, "worker role");
        ManagedScope.requireText(instance, "worker instance");
        var lease = new Lease(UUID.randomUUID().toString(), role, instance);
        register(lease, ttl);
        return lease;
    }

    /** Idempotent registration; an expired incarnation cannot be revived by a retry. */
    public long register(Lease lease, Duration ttl) {
        long duration = ttl(ttl);
        return tx.execute(
                ignored -> {
                    jdbc.update(
                            "INSERT INTO ma_worker.lease(incarnation,role,instance_id,expires_at)"
                                    + " VALUES(?,?,?,?) ON CONFLICT DO NOTHING",
                            lease.incarnation(),
                            lease.role(),
                            lease.instance(),
                            Math.addExact(now(), duration));
                    var expires = locked(lease, " FOR UPDATE");
                    long now = now();
                    if (expires == null || expires <= now)
                        throw new ManagedStateException(
                                "worker_lease_expired", "Process identity is retired or differs");
                    return expires - now;
                });
    }

    public boolean renew(Lease lease, Duration ttl) {
        long duration = ttl(ttl);
        return tx.execute(
                ignored -> {
                    // Renewal only extends a non-key field. It must coexist with commit checks.
                    var expires = locked(lease, postgres ? " FOR NO KEY UPDATE" : " FOR UPDATE");
                    long now = now();
                    if (expires == null || expires <= now) return false;
                    return jdbc.update(
                                    "UPDATE ma_worker.lease SET expires_at=? WHERE incarnation=?",
                                    Math.addExact(now, duration),
                                    lease.incarnation())
                            == 1;
                });
    }

    public void retire(Lease lease) {
        tx.executeWithoutResult(
                ignored -> {
                    if (locked(lease, " FOR UPDATE") != null)
                        jdbc.update(
                                "UPDATE ma_worker.lease SET expires_at=? WHERE incarnation=?",
                                now(),
                                lease.incarnation());
                });
    }

    /** Supplier may perform only metadata operations; never object IO, model or tool work. */
    public <T> T whileValid(Lease lease, Supplier<T> operation) {
        return tx.execute(
                ignored -> {
                    var expires = locked(lease, postgres ? " FOR KEY SHARE" : " FOR UPDATE");
                    if (expires == null || expires <= now())
                        throw new ManagedStateException(
                                "worker_lease_expired", "Worker incarnation is no longer valid");
                    return operation.get();
                });
    }

    /** Session Service validates a remote Brain's registered incarnation before locking its Session. */
    public <T> T whileIncarnationValid(String incarnation, Supplier<T> operation) {
        return tx.execute(
                ignored -> {
                    Long expiry = lockIncarnation(incarnation);
                    if (expiry == null || expiry <= now())
                        throw new ManagedStateException(
                                "worker_lease_expired", "Remote process lease expired");
                    return operation.get();
                });
    }

    public record Expired(long expiresAt, String incarnation) {}

    public List<Expired> expired(long afterExpiry, String afterIncarnation, int limit) {
        if (afterExpiry < 0 || afterIncarnation == null || limit < 1 || limit > 256)
            throw new IllegalArgumentException("Invalid lease repair page");
        return jdbc.query(
                "SELECT expires_at,incarnation FROM ma_worker.lease WHERE expires_at<=?"
                        + " AND (expires_at>? OR (expires_at=? AND incarnation>?))"
                        + " ORDER BY expires_at,incarnation LIMIT ?",
                (row, index) -> new Expired(row.getLong(1), row.getString(2)),
                now(),
                afterExpiry,
                afterExpiry,
                afterIncarnation,
                limit);
    }

    private Long locked(Lease lease, String lock) {
        var values =
                jdbc.queryForList(
                        "SELECT expires_at FROM ma_worker.lease WHERE incarnation=? AND role=? AND"
                                + " instance_id=?"
                                + lock,
                        Long.class,
                        lease.incarnation(),
                        lease.role(),
                        lease.instance());
        return values.isEmpty() ? null : values.get(0);
    }

    Long lockIncarnation(String incarnation) {
        var values =
                jdbc.queryForList(
                        "SELECT expires_at FROM ma_worker.lease WHERE incarnation=?"
                                + (postgres ? " FOR SHARE" : " FOR UPDATE"),
                        Long.class,
                        incarnation);
        return values.isEmpty() ? null : values.get(0);
    }

    public long now() {
        // PostgreSQL CURRENT_TIMESTAMP freezes at transaction start, including time spent waiting
        // for locks.
        if (postgres)
            return jdbc.queryForObject(
                    "SELECT FLOOR(EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::bigint",
                    Long.class);
        return jdbc.queryForObject(
                "SELECT CURRENT_TIMESTAMP", (row, index) -> row.getTimestamp(1).getTime());
    }

    private static long ttl(Duration ttl) {
        long value = ManagedMessageBus.positiveMillis(ttl);
        if (value > Duration.ofMinutes(5).toMillis())
            throw new IllegalArgumentException("Worker lease TTL exceeds five minutes");
        return value;
    }
}
