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
package io.agentcore.managed.server.agentservice.sessionservice.store;

import io.agentcore.managed.server.runtime.managed.ManagedJournalStore.ExecutionOwner;
import io.agentcore.managed.server.runtime.managed.ManagedStateException;
import io.agentcore.managed.server.runtime.managed.ManagedWorkerLeaseStore;
import java.util.Objects;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Preserves the Java Brain's process lease, fixed deadline and lock ordering across RPC. */
@Component
public final class SessionServiceOwnership {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ManagedWorkerLeaseStore leases;

    public SessionServiceOwnership(DataSource source) {
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(10);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setTimeout(15);
        leases = new ManagedWorkerLeaseStore(source);
    }

    public <T> T whileCurrent(ExecutionOwner owner, Supplier<T> action) {
        var before =
                jdbc.queryForMap(
                        "SELECT owner_incarnation FROM ma.turn WHERE scope_key=? AND turn_id=?",
                        owner.scope().messageGroup(),
                        owner.turnId());
        String incarnation = (String) before.get("owner_incarnation");
        Supplier<T> guarded =
                () ->
                        tx.execute(
                                ignored -> {
                                    var session =
                                            jdbc.queryForMap(
                                                    "SELECT current_turn_id,execution_epoch FROM"
                                                            + " ma.session WHERE scope_key=? FOR"
                                                            + " UPDATE",
                                                    owner.scope().messageGroup());
                                    var turn =
                                            jdbc.queryForMap(
                                                    "SELECT"
                                                        + " execution_token,owner_incarnation,execution_deadline"
                                                        + " FROM ma.turn WHERE scope_key=? AND"
                                                        + " turn_id=?",
                                                    owner.scope().messageGroup(),
                                                    owner.turnId());
                                    if (!owner.turnId().equals(session.get("current_turn_id"))
                                            || owner.epoch()
                                                    != ((Number) session.get("execution_epoch"))
                                                            .longValue()
                                            || !owner.token().equals(turn.get("execution_token"))
                                            || !Objects.equals(
                                                    incarnation, turn.get("owner_incarnation"))
                                            || incarnation != null
                                                    && (turn.get("execution_deadline") == null
                                                            || ((Number)
                                                                                    turn.get(
                                                                                            "execution_deadline"))
                                                                            .longValue()
                                                                    <= leases.now()))
                                        throw new ManagedStateException(
                                                "stale_execution",
                                                "Remote owner is no longer current");
                                    // HTTP-claimed Java executions use the same lease as the
                                    // generic clients. The session lock serializes takeover,
                                    // renewal and prepared writes; epoch/token alone do not
                                    // authorize a write after lease expiry.
                                    var protocolLeases =
                                            jdbc.queryForList(
                                                    "SELECT"
                                                        + " turn_id,epoch,execution_token,expires_at"
                                                        + " FROM ma.harness_lease WHERE"
                                                        + " scope_key=?",
                                                    owner.scope().messageGroup());
                                    if (!protocolLeases.isEmpty()) {
                                        var lease = protocolLeases.get(0);
                                        if (!owner.turnId().equals(lease.get("turn_id"))
                                                || owner.epoch()
                                                        != ((Number) lease.get("epoch")).longValue()
                                                || !owner.token()
                                                        .equals(lease.get("execution_token"))
                                                || ((Number) lease.get("expires_at")).longValue()
                                                        <= leases.now())
                                            throw new ManagedStateException(
                                                    "stale_execution",
                                                    "HTTP execution lease is no longer current");
                                    }
                                    return action.get();
                                });
        return incarnation == null
                ? guarded.get()
                : leases.whileIncarnationValid(incarnation, guarded);
    }
}
