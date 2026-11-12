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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import javax.sql.DataSource;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/** Low-frequency keyset recovery; never prepares or runs a Harness. */
final class ManagedInitialReadyRecovery {
    private final JdbcTemplate jdbc;
    private final ManagedAdmissionStore admission;
    private final ManagedLeaseStore leases;
    private final ManagedClaimExpiry expiry;
    private final Duration interval;

    ManagedInitialReadyRecovery(
            DataSource source, ObjectMapper mapper, ManagedLeaseStore leases, Duration interval) {
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(5);
        admission = new ManagedAdmissionStore(source, mapper);
        this.leases = leases;
        this.interval = interval;
        expiry = new ManagedClaimExpiry(source);
    }

    int repair() {
        long now = new ManagedWorkerLeaseStore(jdbc.getDataSource()).now();
        long cutoff = now - interval.toMillis();
        long after = 0;
        String scopeAfter = "", turnAfter = "";
        // Broker admission does not prove delivery. Repair this gap as well as lost owners.
        int repaired = admission.queue().renotifyUnclaimed(now, interval, 256);
        if (repaired > 0)
            ManagedRuntimeLog.info(
                    LoggerFactory.getLogger(ManagedInitialReadyRecovery.class),
                    "session_ready.rearmed",
                    null,
                    null,
                    "count",
                    repaired,
                    "minimum_age_ms",
                    interval.toMillis());
        while (!Thread.currentThread().isInterrupted()) {
            var rows =
                    jdbc.queryForList(
                            "SELECT s.tenant,s.namespace,s.session_id,t.* FROM ma.turn t JOIN"
                                + " ma.session s USING(scope_key) WHERE"
                                + " s.scheduler_mode='db_queue_v1' AND t.resume_id IS NULL AND"
                                + " t.resume_claimed_at<=? AND t.execution_token IS NOT NULL AND"
                                + " t.status IN ('preparing','running') AND (t.owner_incarnation IS"
                                + " NULL OR t.execution_deadline<=? OR NOT EXISTS (SELECT 1 FROM"
                                + " ma_worker.lease w WHERE w.incarnation=t.owner_incarnation AND"
                                + " w.expires_at>?)) AND (t.resume_claimed_at>? OR"
                                + " (t.resume_claimed_at=? AND (t.scope_key>? OR (t.scope_key=? AND"
                                + " t.turn_id>?)))) ORDER BY"
                                + " t.resume_claimed_at,t.scope_key,t.turn_id LIMIT 256",
                            cutoff,
                            now,
                            now,
                            after,
                            after,
                            scopeAfter,
                            scopeAfter,
                            turnAfter);
            if (rows.isEmpty()) break;
            for (var row : rows) {
                var scope =
                        new ManagedScope(
                                (String) row.get("tenant"),
                                (String) row.get("namespace"),
                                (String) row.get("session_id"));
                String turn = (String) row.get("turn_id"),
                        token = (String) row.get("execution_token");
                if (expiry.httpLeaseCurrent(scope, turn, token)) continue;
                var lease = new ManagedLeaseStore.Lease(scope.key("execution"), token);
                if (expiry.turnExpired(scope, turn, token)) leases.release(lease);
                if (!leases.owns(lease)
                        && expiry.withoutCurrentHttpLease(
                                scope,
                                turn,
                                token,
                                () ->
                                        admission.abandonExecution(
                                                scope,
                                                turn,
                                                ((Number) row.get("execution_epoch")).longValue(),
                                                token))) repaired++;
            }
            var last = rows.get(rows.size() - 1);
            after = ((Number) last.get("resume_claimed_at")).longValue();
            scopeAfter = (String) last.get("scope_key");
            turnAfter = (String) last.get("turn_id");
            if (rows.size() < 256) break;
        }
        return repaired;
    }
}
