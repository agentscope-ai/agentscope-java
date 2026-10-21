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
package io.agentscope.builder.runtime.managed;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.runtime.managed.ManagedTurnPublisher.Command;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;
import javax.sql.DataSource;
import org.apache.rocketmq.client.java.impl.consumer.ManagedLitePushConsumer.Ack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Private receipts + the immutable durable Turn terminal form the ACK outbox. No second task queue:
 * RocketMQ retains the FIFO head until terminal commit. Any Runtime can drain this outbox.
 */
public final class ManagedTurnAcknowledgements implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ManagedTurnAcknowledgements.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final String topic;
    private final String group;
    private final Function<RocketMqTurnReceipt, Ack> acknowledge;
    private final LongSupplier clock;
    private ScheduledExecutorService dispatcher;

    public ManagedTurnAcknowledgements(
            DataSource source,
            ObjectMapper mapper,
            String topic,
            String group,
            Function<RocketMqTurnReceipt, Ack> acknowledge) {
        this(source, mapper, topic, group, acknowledge, System::currentTimeMillis);
    }

    ManagedTurnAcknowledgements(
            DataSource source,
            ObjectMapper mapper,
            String topic,
            String group,
            Function<RocketMqTurnReceipt, Ack> acknowledge,
            LongSupplier clock) {
        this.jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(10);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setTimeout(10);
        this.mapper = mapper;
        this.topic = ManagedScope.requireText(topic, "Turn Topic");
        this.group = ManagedScope.requireText(group, "Turn consumer group");
        this.acknowledge = acknowledge;
        this.clock = clock;
    }

    /** Called off SDK RPC/event-loop threads, only after the whole cached batch is suspended. */
    public void suspended(Command command, RocketMqTurnReceipt receipt, Duration delay) {
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

    public int retry(int limit) {
        if (limit < 1) throw new IllegalArgumentException("ACK batch limit must be positive");
        long now = clock.getAsLong();
        // Expiry is a fallback, not successful ACK. The next real MQ delivery refreshes the
        // capability.
        jdbc.update(
                "DELETE FROM ma.turn_ack_receipt WHERE receipt_id IN (SELECT receipt_id FROM"
                    + " ma.turn_ack_receipt WHERE topic=? AND consumer_group=? AND expires_at<=?"
                    + " LIMIT ?)",
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
        int sent = 0;
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
            Ack result = Ack.RETRY;
            try {
                result =
                        acknowledge.apply(
                                mapper.readValue(
                                        (String) row.get("payload"), RocketMqTurnReceipt.class));
            } catch (Exception unavailable) {
                // Keep the row for another replica/attempt. Never log the private receipt or SQL
                // value.
            }
            if (result != Ack.RETRY) {
                jdbc.update("DELETE FROM ma.turn_ack_receipt WHERE receipt_id=?", id);
                if (result == Ack.ACKNOWLEDGED) sent++;
            }
            ManagedRuntimeLog.info(
                    log,
                    "brain.turn.ack",
                    null,
                    (String) row.get("turn_id"),
                    "scope_key",
                    row.get("scope_key"),
                    "outcome",
                    result.name());
        }
        return sent;
    }

    public synchronized void start() {
        if (dispatcher != null) return;
        dispatcher = Executors.newSingleThreadScheduledExecutor();
        dispatcher.scheduleWithFixedDelay(
                () -> {
                    try {
                        retry(32);
                    } catch (RuntimeException unavailable) {
                        log.warn(
                                "Turn ACK outbox remains pending ({})",
                                unavailable.getClass().getSimpleName());
                    }
                },
                0,
                200,
                TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() {
        if (dispatcher != null) dispatcher.shutdownNow();
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Cannot encode private Turn receipt");
        }
    }
}
