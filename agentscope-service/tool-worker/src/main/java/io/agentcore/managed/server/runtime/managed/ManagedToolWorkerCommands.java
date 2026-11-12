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

import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Channel;
import io.agentcore.managed.server.runtime.managed.ManagedToolCommitGate.Decision;
import java.time.Duration;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

/** Acknowledges durable command admission; execution and recovery do not depend on MQ redelivery. */
public final class ManagedToolWorkerCommands implements ManagedConsumerLoop.Handler {
    private static final Logger log = LoggerFactory.getLogger(ManagedToolWorkerCommands.class);
    private final ManagedToolWorkerStore store;
    private final ManagedBatchCodec codec;
    private final ManagedToolResultPublisher results;
    private final BiConsumer<ManagedScope, String> wakeup;
    private final Function<ManagedToolCommand, Decision> confirmation;
    private ManagedToolWorkerLoop loop;
    private BiConsumer<ManagedScope, String> controlWakeup;

    public void controlWakeup(BiConsumer<ManagedScope, String> controlWakeup) {
        this.controlWakeup = controlWakeup;
    }

    public ManagedToolWorkerCommands(
            ManagedToolWorkerStore store,
            ManagedBatchCodec codec,
            ManagedToolResultPublisher results,
            BiConsumer<ManagedScope, String> wakeup,
            Function<ManagedToolCommand, Decision> confirmation) {
        this.store = store;
        this.codec = codec;
        this.results = results;
        this.wakeup = wakeup;
        this.controlWakeup = wakeup;
        this.confirmation = confirmation;
    }

    public ManagedToolWorkerCommands(
            ManagedToolWorkerStore store,
            ManagedBatchCodec codec,
            ManagedToolResultPublisher results,
            ManagedToolWorkerLoop loop,
            Function<ManagedToolCommand, Decision> confirmation) {
        this(store, codec, results, loop::wakeup, confirmation);
        this.loop = loop;
    }

    @Override
    public ManagedReceiveCapacity receiveCapacity(Channel channel) {
        return loop != null && channel == Channel.TOOL_EXECUTE ? loop.receiveCapacity() : null;
    }

    public void consume(ManagedPushDelivery delivery) {
        try {
            execute(delivery);
        } catch (ManagedTransportException | DataAccessException unavailable) {
            throw new ManagedDeliverySuspended(Duration.ofSeconds(1));
        }
    }

    private void execute(ManagedPushDelivery delivery) {
        var command = codec.decodeToolCommand(delivery.message());
        if (("execute".equals(command.kind()) && delivery.channel() != Channel.TOOL_EXECUTE)
                || (!"execute".equals(command.kind())
                        && delivery.channel() != Channel.TOOL_CONTROL))
            throw new IllegalArgumentException("Tool producer channel differs");
        if ("execute".equals(command.kind())) {
            var decision = confirmation.apply(command);
            if (decision == Decision.PENDING) {
                ManagedRuntimeLog.tool(
                        log,
                        "tool_worker.command.awaiting_commit",
                        command.scope(),
                        command.task());
                throw new ManagedDeliverySuspended(Duration.ofMillis(500));
            }
            if (decision == Decision.CANCELLED)
                store.receive(
                        new ManagedToolCommand(
                                command.scope(),
                                "cancel_" + command.task().id(),
                                "cancel",
                                command.task(),
                                null));
            if (decision == Decision.DISCARDED) {
                ManagedRuntimeLog.tool(
                        log, "tool_worker.command.discarded", command.scope(), command.task());
                return;
            }
        }
        store.receive(command);
        if (!"execute".equals(command.kind())) {
            if ("replay".equals(command.kind())) {
                store.replay(command.scope(), command.task().id());
                results.flush(command.scope(), command.task().id());
            }
            if ("cancel".equals(command.kind())) {
                try {
                    controlWakeup.accept(command.scope(), command.task().id());
                } catch (RuntimeException stopped) {
                    log.debug("Tool cancellation wakeup deferred: task={}", command.task().id());
                }
            }
            return;
        }
        ManagedRuntimeLog.tool(
                log, "tool_worker.command.accepted", command.scope(), command.task());
        try {
            if (loop != null) loop.admit(command.scope(), command.task().id(), delivery.handoff());
            else
                delivery.handoff()
                        .afterAcknowledged(
                                () -> wakeup.accept(command.scope(), command.task().id()));
        } catch (RuntimeException stopped) {
            // Admission is already durable. A different replica's scan must repair this window.
            log.warn("Tool dispatch wakeup pending: task={}", command.task().id());
        }
    }
}
