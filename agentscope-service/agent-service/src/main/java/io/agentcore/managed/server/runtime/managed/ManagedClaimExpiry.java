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

import java.util.Objects;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Lock the process lease before checking real DB time; expired incarnations cannot revive. */
final class ManagedClaimExpiry {
    private final JdbcTemplate jdbc;
    private final ManagedWorkerLeaseStore workers;
    private final TransactionTemplate tx;
    private volatile boolean harnessLeaseTablePresent;

    ManagedClaimExpiry(DataSource source) {
        jdbc = new JdbcTemplate(source);
        workers = new ManagedWorkerLeaseStore(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setTimeout(5);
    }

    boolean httpLeaseCurrent(ManagedScope scope, String turn, String token) {
        // Legacy-only databases do not have the optional HTTP protocol schema.
        // Cache presence, never absence: protocol initialization can happen later.
        if (!harnessLeaseTablePresent) {
            harnessLeaseTablePresent =
                    jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM information_schema.tables WHERE"
                                            + " LOWER(table_schema)='ma' AND"
                                            + " LOWER(table_name)='harness_lease'",
                                    Integer.class)
                            > 0;
            if (!harnessLeaseTablePresent) return false;
        }
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.harness_lease WHERE scope_key=? AND turn_id=?"
                                + " AND execution_token=? AND expires_at>?",
                        Integer.class,
                        scope.messageGroup(),
                        turn,
                        token,
                        workers.now())
                > 0;
    }

    boolean withoutCurrentHttpLease(
            ManagedScope scope, String turn, String token, BooleanSupplier action) {
        return Boolean.TRUE.equals(
                tx.execute(
                        ignored -> {
                            // Use the same lock as HTTP claim/renew, so a scan followed by
                            // a concurrent renewal cannot revoke a live protocol owner.
                            jdbc.queryForObject(
                                    "SELECT scope_key FROM ma.session WHERE scope_key=? FOR UPDATE",
                                    String.class,
                                    scope.messageGroup());
                            return !httpLeaseCurrent(scope, turn, token) && action.getAsBoolean();
                        }));
    }

    boolean turnExpired(ManagedScope scope, String turn, String token) {
        return tx.execute(
                ignored -> {
                    var rows =
                            jdbc.queryForList(
                                    "SELECT owner_incarnation,execution_deadline,execution_token"
                                            + " FROM ma.turn WHERE scope_key=? AND turn_id=?",
                                    scope.messageGroup(),
                                    turn);
                    if (rows.isEmpty()
                            || rows.get(0).get("owner_incarnation") == null
                            || !Objects.equals(token, rows.get(0).get("execution_token")))
                        return false;
                    var row = rows.get(0);
                    Long expiry = workers.lockIncarnation((String) row.get("owner_incarnation"));
                    long now = workers.now();
                    return expiry == null
                            || expiry <= now
                            || row.get("execution_deadline") == null
                            || ((Number) row.get("execution_deadline")).longValue() <= now;
                });
    }
}
