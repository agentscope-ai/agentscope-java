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
import io.agentcore.managed.server.runtime.managed.ManagedTurnPublisher.Command;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable ACK ledger. No transaction spans a broker or Brain HTTP call. */
public final class SessionTurnAckLedger implements ManagedTurnAckLedger {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final LongSupplier clock;

    public SessionTurnAckLedger(DataSource source, ObjectMapper mapper) {
        this(source, mapper, System::currentTimeMillis);
    }

    public SessionTurnAckLedger(DataSource source, ObjectMapper mapper, LongSupplier clock) {
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(10);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setTimeout(10);
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Called off SDK RPC/event-loop threads, only after the whole cached batch is suspended. */
    public void capture(
            String topic,
            String group,
            Command command,
            RocketMqTurnReceipt receipt,
            Duration delay) {
        if (!topic.equals(receipt.topic())
                || !command.scope().messageGroup().equals(receipt.liteTopic()))
            throw new IllegalArgumentException("Turn receipt routing differs");
        long now = clock.getAsLong();
        // One row per receipt generation: a late old collector cannot replace a newer handle.
        tx.executeWithoutResult(
                ignored -> {
                    // Terminal commit uses this same lock. Capture either precedes the outbox-ready
                    // update
                    // or sees terminal itself: completion-before-capture cannot lose the wakeup.
                    jdbc.queryForObject(
                            "SELECT scope_key FROM ma.session WHERE scope_key=? FOR UPDATE",
                            String.class,
                            command.scope().messageGroup());
                    jdbc.update(
                            "INSERT INTO ma.turn_ack_receipt"
                                + "(receipt_id,scope_key,turn_id,publication_sequence,topic,consumer_group,payload,expires_at,next_attempt_at,ready)"
                                + " SELECT ?,scope_key,turn_id,publication_sequence,?,?,?,?,?,CASE"
                                + " WHEN status IN ('succeeded','failed','cancelled') THEN TRUE"
                                + " ELSE FALSE END FROM ma.turn WHERE scope_key=? AND turn_id=? AND"
                                + " publication_sequence=? ON CONFLICT DO NOTHING",
                            receipt.version(group),
                            topic,
                            group,
                            json(receipt),
                            now + ManagedMessageBus.positiveMillis(delay),
                            now,
                            command.scope().messageGroup(),
                            command.turnId(),
                            command.publicationSequence());
                });
    }

    public List<Pending> claim(String topic, String group, int limit) {
        if (limit < 1 || limit > 256)
            throw new IllegalArgumentException("ACK batch limit must be positive");
        long now = clock.getAsLong();
        // Expiry is a fallback, not successful ACK. The next real MQ delivery refreshes the
        // capability.
        jdbc.update(
                "DELETE FROM ma.turn_ack_receipt WHERE receipt_id IN (SELECT receipt_id FROM"
                    + " ma.turn_ack_receipt WHERE topic=? AND consumer_group=? AND expires_at<=?"
                    + " ORDER BY expires_at,receipt_id LIMIT ? FOR UPDATE SKIP LOCKED)",
                topic,
                group,
                now,
                limit);
        var rows =
                jdbc.queryForList(
                        "SELECT r.receipt_id,r.payload,r.scope_key,r.turn_id FROM"
                            + " ma.turn_ack_receipt r JOIN ma.turn t ON t.scope_key=r.scope_key AND"
                            + " t.turn_id=r.turn_id AND"
                            + " t.publication_sequence=r.publication_sequence WHERE r.topic=? AND"
                            + " r.consumer_group=? AND r.ready=TRUE AND r.expires_at>? AND"
                            + " r.next_attempt_at<=? AND t.status IN"
                            + " ('succeeded','failed','cancelled') ORDER BY"
                            + " r.next_attempt_at,r.receipt_id LIMIT ?",
                        topic,
                        group,
                        now,
                        now,
                        limit);
        var pending = new ArrayList<Pending>();
        for (var row : rows) {
            String id = (String) row.get("receipt_id");
            // Bounded claim, never a DB transaction held across the Broker RPC. A crashed drainer
            // can be replaced after 10s; duplicate ACKs are safe because terminal is immutable.
            if (jdbc.update(
                            "UPDATE ma.turn_ack_receipt SET next_attempt_at=? WHERE receipt_id=?"
                                    + " AND next_attempt_at<=?",
                            clock.getAsLong() + 10000,
                            id,
                            now)
                    != 1) continue;
            try {
                pending.add(
                        new Pending(
                                id,
                                (String) row.get("scope_key"),
                                (String) row.get("turn_id"),
                                mapper.readValue(
                                        (String) row.get("payload"), RocketMqTurnReceipt.class)));
            } catch (Exception invalid) {
                throw new IllegalStateException("Invalid stored Turn ACK capability");
            }
        }
        return pending;
    }

    public void complete(String topic, String group, String receiptId) {
        jdbc.update(
                "DELETE FROM ma.turn_ack_receipt WHERE receipt_id=? AND topic=? AND"
                        + " consumer_group=?",
                receiptId,
                topic,
                group);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Cannot encode private Turn receipt");
        }
    }
}
