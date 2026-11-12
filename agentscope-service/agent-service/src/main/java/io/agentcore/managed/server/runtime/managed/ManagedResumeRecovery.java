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

import io.agentcore.managed.server.runtime.managed.ManagedSuspensions.Resume;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Low-frequency repair of ACKed execution claims; never runs a Harness or renews SQL leases. */
final class ManagedResumeRecovery {
    private static final Logger log = LoggerFactory.getLogger(ManagedResumeRecovery.class);

    record Claim(Resume resume, long epoch, String token, long claimedAt) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ManagedLeaseStore leases;
    private final Duration interval;
    private final LongSupplier clock;
    private final ManagedClaimExpiry expiry;

    ManagedResumeRecovery(ManagedSuspensions store, ManagedLeaseStore leases, Duration interval) {
        this(store, leases, interval, System::currentTimeMillis);
    }

    ManagedResumeRecovery(
            ManagedSuspensions store,
            ManagedLeaseStore leases,
            Duration interval,
            LongSupplier clock) {
        this.jdbc = new JdbcTemplate(store.dataSource());
        this.jdbc.setQueryTimeout(10);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(store.dataSource()));
        this.tx.setTimeout(10);
        this.leases = leases;
        this.interval = interval;
        this.clock = clock;
        this.expiry = new ManagedClaimExpiry(store.dataSource());
    }

    int repair() {
        long cutoff = clock.getAsLong() - interval.toMillis();
        Claim cursor = null;
        int recovered = 0;
        while (!Thread.currentThread().isInterrupted()) {
            var batch = candidates(cutoff, cursor, 256);
            if (batch.isEmpty()) break;
            for (var claim : batch) {
                try {
                    if (recover(claim)) recovered++;
                } catch (RuntimeException unavailable) {
                    // One unavailable Session must not starve later pages.
                    log.warn("Resume claim repair pending: {}", unavailable.toString());
                }
            }
            cursor = batch.get(batch.size() - 1);
            if (batch.size() < 256) break;
        }
        return recovered;
    }

    List<Claim> candidates(long cutoff, Claim after, int limit) {
        long now = new ManagedWorkerLeaseStore(jdbc.getDataSource()).now();
        String sql =
                "SELECT r.*,s.tenant,s.namespace,s.session_id,t.execution_epoch AS"
                    + " claim_epoch,t.execution_token,t.resume_claimed_at FROM ma.turn t JOIN"
                    + " ma.turn_resume r ON r.scope_key=t.scope_key AND r.resume_id=t.resume_id"
                    + " JOIN ma.session s ON s.scope_key=t.scope_key WHERE t.resume_claimed_at<=?"
                    + " AND t.status NOT IN ('succeeded','failed','cancelled') AND"
                    + " (t.owner_incarnation IS NULL OR t.execution_deadline<=? OR NOT EXISTS"
                    + " (SELECT 1 FROM ma_worker.lease w WHERE w.incarnation=t.owner_incarnation"
                    + " AND w.expires_at>?))"
                        + (after == null
                                ? ""
                                : " AND (t.resume_claimed_at>? OR (t.resume_claimed_at=? AND"
                                        + " (t.scope_key>? OR (t.scope_key=? AND t.turn_id>?))))")
                        + " ORDER BY t.resume_claimed_at,t.scope_key,t.turn_id LIMIT ?";
        Object[] args =
                after == null
                        ? new Object[] {cutoff, now, now, limit}
                        : new Object[] {
                            cutoff,
                            now,
                            now,
                            after.claimedAt(),
                            after.claimedAt(),
                            after.resume().scope().messageGroup(),
                            after.resume().scope().messageGroup(),
                            after.resume().turnId(),
                            limit
                        };
        return jdbc.query(
                sql,
                (row, index) ->
                        new Claim(
                                new Resume(
                                        new ManagedScope(
                                                row.getString("tenant"),
                                                row.getString("namespace"),
                                                row.getString("session_id")),
                                        row.getString("resume_id"),
                                        row.getString("turn_id"),
                                        row.getString("suspension_id"),
                                        row.getLong("suspension_epoch"),
                                        row.getLong("publication_sequence")),
                                row.getLong("claim_epoch"),
                                row.getString("execution_token"),
                                row.getLong("resume_claimed_at")),
                args);
    }

    boolean recover(Claim claim) {
        var resume = claim.resume();
        if (expiry.httpLeaseCurrent(resume.scope(), resume.turnId(), claim.token())) return false;
        String key = resume.scope().key("execution");
        if (claim.token() != null
                && expiry.turnExpired(resume.scope(), resume.turnId(), claim.token()))
            leases.release(new ManagedLeaseStore.Lease(key, claim.token()));
        if (claim.token() != null && leases.owns(new ManagedLeaseStore.Lease(key, claim.token())))
            return false;
        // Fail closed if Redis is unavailable. A new/current executor excludes repair here.
        var acquired =
                ManagedExecutionGuard.acquireContinuation(
                        resume.scope(), leases, Duration.ofSeconds(60), System::nanoTime, () -> {});
        if (acquired.isEmpty()) return false;
        try (var guard = acquired.get()) {
            boolean recovered =
                    Boolean.TRUE.equals(
                            tx.execute(
                                    ignored -> {
                                        String scope = resume.scope().messageGroup();
                                        var session =
                                                jdbc.queryForMap(
                                                        "SELECT current_turn_id,execution_epoch"
                                                            + " FROM ma.session WHERE scope_key=?"
                                                            + " FOR UPDATE",
                                                        scope);
                                        if (!resume.turnId().equals(session.get("current_turn_id"))
                                                || ((Number) session.get("execution_epoch"))
                                                                .longValue()
                                                        != claim.epoch()) return false;
                                        if (expiry.httpLeaseCurrent(
                                                resume.scope(), resume.turnId(), claim.token()))
                                            return false;
                                        var turn =
                                                jdbc.queryForMap(
                                                        "SELECT"
                                                            + " resume_id,suspension_id,execution_token,execution_epoch,resume_claimed_at,status"
                                                            + " FROM ma.turn WHERE scope_key=? AND"
                                                            + " turn_id=?",
                                                        scope,
                                                        resume.turnId());
                                        if (!resume.resumeId().equals(turn.get("resume_id"))
                                                || !resume.suspensionId()
                                                        .equals(turn.get("suspension_id"))
                                                || !Objects.equals(
                                                        claim.token(), turn.get("execution_token"))
                                                || ((Number) turn.get("execution_epoch"))
                                                                .longValue()
                                                        != claim.epoch()
                                                || !Objects.equals(
                                                        claim.claimedAt(),
                                                        turn.get("resume_claimed_at"))
                                                || List.of("succeeded", "failed", "cancelled")
                                                        .contains(turn.get("status"))) return false;
                                        guard.check();
                                        long epoch = Math.addExact(claim.epoch(), 1);
                                        jdbc.update(
                                                "UPDATE ma.session SET execution_epoch=? WHERE"
                                                        + " scope_key=?",
                                                epoch,
                                                scope);
                                        // Keep a repair marker until the next boundary. It also
                                        // covers a delayed publisher
                                        // overwriting pending with queued after an
                                        // ambiguous/previously ACKed send.
                                        jdbc.update(
                                                "UPDATE ma.turn SET"
                                                    + " execution_epoch=?,execution_token=NULL,owner_incarnation=NULL,execution_deadline=NULL,resume_claimed_at=?,status='preparing'"
                                                    + " WHERE scope_key=? AND turn_id=?",
                                                epoch,
                                                clock.getAsLong(),
                                                scope,
                                                resume.turnId());
                                        jdbc.update(
                                                "UPDATE ma.turn_resume SET"
                                                    + " delivery_status='pending',broker_message_id=NULL,retry_at=0,publication_version=publication_version+1"
                                                    + " WHERE scope_key=? AND resume_id=?",
                                                scope,
                                                resume.resumeId());
                                        ManagedResumeSignals.committed(jdbc, scope);
                                        return true;
                                    }));
            if (recovered)
                ManagedRuntimeLog.info(
                        log,
                        "harness_runtime.resume.requeued",
                        resume.scope(),
                        resume.turnId(),
                        "resume_id",
                        resume.resumeId(),
                        "previous_epoch",
                        claim.epoch());
            return recovered;
        }
    }
}
