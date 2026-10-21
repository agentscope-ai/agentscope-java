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

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Hands repairs result mailboxes from durable outcomes, including Redis loss or a missed notification. */
public final class ManagedToolResultRelay implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ManagedToolResultRelay.class);
    private final ManagedJournalStore journal;
    private final ManagedBatchCodec codec;
    private final ManagedToolResultDelivery delivery;
    private String afterScope = "";
    private String afterTask = "";
    private ScheduledExecutorService worker;

    public ManagedToolResultRelay(
            ManagedJournalStore journal,
            ManagedBatchCodec codec,
            ManagedToolResultDelivery delivery) {
        this.journal = journal;
        this.codec = codec;
        this.delivery = delivery;
    }

    public void delivered(ManagedToolResult result) {
        delivery.publish(result.scope(), result.taskId(), codec.encode(result));
    }

    public synchronized int repair(int limit) {
        var results = journal.resultsForDelivery(afterScope, afterTask, limit);
        if (results.isEmpty()) {
            afterScope = "";
            afterTask = "";
            results = journal.resultsForDelivery("", "", limit);
        }
        int sent = 0;
        for (var result : results) {
            afterScope = result.scope().messageGroup();
            afterTask = result.taskId();
            try {
                delivered(result);
                sent++;
            } catch (RuntimeException unavailable) {
                log.warn(
                        "Tool result delivery remains pending: task={}, cause={}",
                        result.taskId(),
                        unavailable.toString());
            }
        }
        return sent;
    }

    public synchronized void start() {
        if (worker != null) return;
        worker = Executors.newSingleThreadScheduledExecutor();
        worker.scheduleWithFixedDelay(
                () -> {
                    try {
                        repair(64);
                    } catch (RuntimeException unavailable) {
                        log.warn("Tool result repair scan failed: {}", unavailable.toString());
                    }
                },
                0,
                1,
                TimeUnit.SECONDS);
    }

    @Override
    public synchronized void close() {
        if (worker != null) worker.shutdownNow();
    }
}
