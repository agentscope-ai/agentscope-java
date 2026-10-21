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

import io.agentscope.builder.runtime.managed.ManagedJournalStore.Receipt;
import io.agentscope.builder.runtime.managed.ManagedMessageBus.Channel;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Tool-source callback commits its durable facts before returning SUCCESS. */
public final class ManagedBrainToolEvents {
    private static final Logger log = LoggerFactory.getLogger(ManagedBrainToolEvents.class);
    private final ManagedBatchCodec codec;
    private final ManagedJournalStore journal;
    private final Consumer<ManagedToolResult> resultNotify;

    public ManagedBrainToolEvents(
            ManagedBatchCodec codec,
            ManagedJournalStore journal,
            Consumer<ManagedToolResult> resultNotify) {
        this.codec = codec;
        this.journal = journal;
        this.resultNotify = resultNotify;
    }

    public Receipt consume(ManagedPushDelivery delivery) {
        if (delivery.channel() != Channel.TOOL_EVENTS)
            throw new IllegalArgumentException("Brain consumes tool source events only");
        String kind = codec.kind(delivery.message());
        if (!"tool_event".equals(kind))
            throw new IllegalArgumentException(
                    "Managed event kind does not match its producer channel");
        ManagedScope scope;
        Receipt receipt;
        switch (kind) {
            case "tool_event" -> {
                var event = codec.decodeToolEvent(delivery.message());
                scope = event.scope();
                var commit = journal.commitToolEvent(event);
                receipt = commit.receipt();
                for (var result : commit.results()) {
                    try {
                        resultNotify.accept(result);
                        ManagedRuntimeLog.info(
                                log,
                                "brain.tool.result.notified",
                                scope,
                                null,
                                "task_id",
                                result.taskId(),
                                "execution_id",
                                result.executionId(),
                                "status",
                                result.status(),
                                "delivery_id",
                                delivery.deliveryId(),
                                "consumer_id",
                                delivery.consumerId());
                    } catch (RuntimeException unavailable) {
                        log.warn(
                                "Tool result delivery remains pending: task={}, cause={}",
                                result.taskId(),
                                unavailable.toString());
                    }
                }
            }
            default -> throw new IllegalArgumentException("Unknown Managed event kind");
        }
        return receipt;
    }
}
