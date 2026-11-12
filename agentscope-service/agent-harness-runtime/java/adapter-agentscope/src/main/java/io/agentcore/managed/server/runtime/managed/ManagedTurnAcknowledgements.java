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
import io.agentcore.managed.harness.runtime.SessionServiceClient;
import io.agentcore.managed.server.runtime.managed.ManagedTurnPublisher.Command;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.apache.rocketmq.client.java.impl.consumer.ManagedLitePushConsumer.Ack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Consumer-side broker ACK delivery; Session Service owns the durable receipt ledger. */
public final class ManagedTurnAcknowledgements implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ManagedTurnAcknowledgements.class);
    private final ManagedTurnAckLedger ledger;
    private final String topic;
    private final String group;
    private final Function<RocketMqTurnReceipt, Ack> acknowledge;
    private ScheduledExecutorService dispatcher;

    public ManagedTurnAcknowledgements(
            ManagedTurnAckLedger ledger,
            String topic,
            String group,
            Function<RocketMqTurnReceipt, Ack> acknowledge) {
        this.ledger = ledger;
        this.topic = ManagedScope.requireText(topic, "Turn Topic");
        this.group = ManagedScope.requireText(group, "Turn consumer group");
        this.acknowledge = acknowledge;
    }

    public ManagedTurnAcknowledgements(
            SessionServiceClient client,
            ObjectMapper mapper,
            String topic,
            String group,
            Function<RocketMqTurnReceipt, Ack> acknowledge) {
        this(
                new ManagedTurnAckLedger() {
                    public void capture(
                            String t,
                            String g,
                            Command command,
                            RocketMqTurnReceipt receipt,
                            Duration delay) {
                        ManagedServiceCalls.coordinate(
                                client,
                                "ack-capture",
                                Map.of(
                                        "topic",
                                        t,
                                        "group",
                                        g,
                                        "command",
                                        command,
                                        "receipt",
                                        receipt,
                                        "delayMillis",
                                        delay.toMillis()));
                    }

                    public List<Pending> claim(String t, String g, int limit) {
                        return mapper.convertValue(
                                ManagedServiceCalls.coordinate(
                                        client,
                                        "ack-claim",
                                        Map.of("topic", t, "group", g, "limit", limit)),
                                mapper.getTypeFactory()
                                        .constructCollectionType(List.class, Pending.class));
                    }

                    public void complete(String t, String g, String id) {
                        ManagedServiceCalls.coordinate(
                                client,
                                "ack-complete",
                                Map.of("topic", t, "group", g, "receiptId", id));
                    }
                },
                topic,
                group,
                acknowledge);
    }

    public void suspended(Command command, RocketMqTurnReceipt receipt, Duration delay) {
        ledger.capture(topic, group, command, receipt, delay);
    }

    public int retry(int limit) {
        int sent = 0;
        for (var pending : ledger.claim(topic, group, limit)) {
            Ack result = Ack.RETRY;
            try {
                result = acknowledge.apply(pending.receipt());
            } catch (Exception unavailable) {
                // Keep the private capability for another attempt; never log its content.
            }
            if (result != Ack.RETRY) {
                ledger.complete(topic, group, pending.receiptId());
                if (result == Ack.ACKNOWLEDGED) sent++;
            }
            ManagedRuntimeLog.info(
                    log,
                    "harness_runtime.turn.ack",
                    null,
                    pending.turnId(),
                    "scope_key",
                    pending.scopeKey(),
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
}
