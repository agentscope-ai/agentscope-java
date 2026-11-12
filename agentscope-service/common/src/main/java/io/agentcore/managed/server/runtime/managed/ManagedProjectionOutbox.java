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
import io.micrometer.core.instrument.Metrics;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Publication-only ownership. Never locks execution rows or waits on object/MQ IO in a transaction. */
final class ManagedProjectionOutbox {
    record Claim(ManagedScope scope, String token, List<ManagedProjectionNotice.Commit> commits) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final ManagedProjectionFacts facts;

    ManagedProjectionOutbox(DataSource source, ObjectMapper mapper, ManagedProjectionFacts facts) {
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(5);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setTimeout(5);
        this.mapper = mapper;
        this.facts = facts;
    }

    DataSource source() {
        return jdbc.getDataSource();
    }

    List<ManagedScope> pending(int limit) {
        return jdbc
                .query(
                        "SELECT s.tenant,s.namespace,s.session_id FROM ma.runtime_commit c JOIN"
                            + " ma.session s ON s.scope_key=c.scope_key WHERE c.publication_state"
                            + " IN ('pending','inflight') AND c.next_attempt_at<=? AND NOT EXISTS"
                            + " (SELECT 1 FROM ma.runtime_commit earlier WHERE"
                            + " earlier.scope_key=c.scope_key AND earlier.publication_state IN"
                            + " ('pending','inflight') AND"
                            + " earlier.public_commit_version<c.public_commit_version) ORDER BY"
                            + " c.next_attempt_at,c.scope_key,c.public_commit_version LIMIT ?",
                        (row, n) ->
                                new ManagedScope(
                                        row.getString(1), row.getString(2), row.getString(3)),
                        System.currentTimeMillis(),
                        limit)
                .stream()
                .distinct()
                .toList();
    }

    Claim claim(ManagedScope scope) {
        return tx.execute(
                ignored -> {
                    long now = System.currentTimeMillis();
                    String token = UUID.randomUUID().toString();
                    var rows =
                            jdbc.queryForList(
                                    "SELECT commit_id,projection_manifest,next_attempt_at FROM"
                                        + " ma.runtime_commit WHERE scope_key=? AND"
                                        + " publication_state IN ('pending','inflight') ORDER BY"
                                        + " public_commit_version LIMIT 32 FOR UPDATE",
                                    scope.messageGroup());
                    var selected = new ArrayList<ManagedProjectionNotice.Commit>();
                    long bytes = 0, objectBytes = 0, version = -1;
                    for (var row : rows) {
                        if (((Number) row.get("next_attempt_at")).longValue() > now) break;
                        String raw = (String) row.get("projection_manifest");
                        var m = decode(raw);
                        if (version >= 0
                                && (m.version() != version + 1
                                        || bytes + raw.length() * 3L > 512 * 1024
                                        || objectBytes + ManagedProjectionFacts.deliveryBytes(m)
                                                > 64L * 1024 * 1024)) break;
                        String id = (String) row.get("commit_id");
                        selected.add(
                                new ManagedProjectionNotice.Commit(
                                        id, ManagedProjectionFacts.identity(m), m));
                        version = m.version();
                        bytes += raw.length() * 3L;
                        objectBytes += ManagedProjectionFacts.deliveryBytes(m);
                    }
                    if (selected.isEmpty()) return null;
                    String placeholders =
                            String.join(",", Collections.nCopies(selected.size(), "?"));
                    var parameters =
                            new ArrayList<Object>(
                                    List.of(token, now + 30000, scope.messageGroup(), now));
                    selected.forEach(c -> parameters.add(c.id()));
                    int claimed =
                            jdbc.update(
                                    "UPDATE ma.runtime_commit SET"
                                        + " publication_state='inflight',publisher_token=?,next_attempt_at=?,attempt_count=attempt_count+1"
                                        + " WHERE scope_key=? AND publication_state IN"
                                        + " ('pending','inflight') AND next_attempt_at<=? AND"
                                        + " commit_id IN ("
                                            + placeholders
                                            + ")",
                                    parameters.toArray());
                    if (claimed != selected.size()) {
                        ignored.setRollbackOnly();
                        return null;
                    }
                    Metrics.summary("managed.agents.projection.publication.batch")
                            .record(selected.size());
                    return selected.isEmpty()
                            ? null
                            : new Claim(scope, token, List.copyOf(selected));
                });
    }

    ManagedProjectionNotice message(Claim claim) {
        var commits = new ArrayList<ManagedProjectionNotice.Commit>();
        for (var c : claim.commits())
            commits.add(
                    new ManagedProjectionNotice.Commit(
                            c.id(), c.hash(), facts.prepareDelivery(claim.scope(), c.manifest())));
        return new ManagedProjectionNotice(3, claim.scope(), commits);
    }

    void completed(Claim claim) {
        finish(claim, true);
    }

    void retry(Claim claim) {
        finish(claim, false);
    }

    private void finish(Claim claim, boolean sent) {
        long now = System.currentTimeMillis();
        tx.executeWithoutResult(
                ignored -> {
                    // Claim and completion must acquire overlapping rows in the same order.
                    // An unordered batch UPDATE can deadlock with another publisher, including
                    // an expired owner's completion. These locks never cover object or MQ IO.
                    jdbc.queryForList(
                            "SELECT commit_id FROM ma.runtime_commit WHERE scope_key=? AND"
                                    + " publisher_token=? AND publication_state='inflight' ORDER BY"
                                    + " public_commit_version FOR UPDATE",
                            claim.scope().messageGroup(),
                            claim.token());
                    jdbc.update(
                            "UPDATE ma.runtime_commit SET"
                                + " publication_state=?,publisher_token=NULL,next_attempt_at=?,published_at=?"
                                + " WHERE scope_key=? AND publisher_token=? AND"
                                + " publication_state='inflight'",
                            sent ? "sent" : "pending",
                            sent ? 0 : now + 1000,
                            sent ? now : null,
                            claim.scope().messageGroup(),
                            claim.token());
                });
    }

    private ManagedProjectionFacts.Manifest decode(String raw) {
        try {
            return mapper.readValue(raw, ManagedProjectionFacts.Manifest.class);
        } catch (Exception error) {
            throw new ManagedStateException(
                    "projection_object_invalid", "Invalid committed manifest");
        }
    }
}
