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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Channel;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Message;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Publishes durable accepted Turns; ambiguous sends retain the Session's publication barrier. */
public final class ManagedTurnPublisher {
    private static final Logger log = LoggerFactory.getLogger(ManagedTurnPublisher.class);

    public record Command(ManagedScope scope, String turnId, long publicationSequence) {
        public Command {
            if (scope == null || publicationSequence < 1)
                throw new IllegalArgumentException("Invalid Turn command");
            ManagedScope.requireText(turnId, "Turn id");
        }
    }

    private final ManagedAdmissionStore store;
    private final ManagedMessageBus bus;
    private final ManagedLeaseStore leases;
    private final ObjectMapper mapper;
    private final Duration publicationTtl;

    public ManagedTurnPublisher(
            ManagedAdmissionStore store,
            ManagedMessageBus bus,
            ManagedLeaseStore leases,
            ObjectMapper mapper,
            Duration publicationTtl) {
        this.store = store;
        this.bus = bus;
        this.leases = leases;
        this.mapper = mapper;
        ManagedMessageBus.positiveMillis(publicationTtl);
        this.publicationTtl = publicationTtl;
    }

    private boolean httpBrain;

    public void httpBrain(boolean enabled) {
        this.httpBrain = enabled;
    }

    public boolean publish(ManagedScope scope, String turnId) {
        if (httpBrain && store.queue().enabled(scope)) return true;
        if (store.queue().enabled(scope)) return publishReady(scope);

        var acquired = leases.acquire(scope.key("publication"), publicationTtl);
        if (acquired.isEmpty()) return false;
        var lease = acquired.get();
        try {
            var turn = store.turn(scope, turnId);
            if ("queued".equals(turn.deliveryStatus())) return true;
            turn = store.reservePublication(scope, turnId);
            if (!leases.owns(lease))
                throw new ManagedTransportException("Publication ownership was lost before send");
            var command = new Command(scope, turnId, turn.publicationSequence());
            byte[] body;
            try {
                body = mapper.writeValueAsBytes(command);
            } catch (JsonProcessingException error) {
                throw new IllegalArgumentException("Cannot encode Turn", error);
            }
            var receipt =
                    bus.publish(
                            Channel.TURNS,
                            new Message(
                                    turnId,
                                    scope.messageGroup(),
                                    body,
                                    ManagedTurnLanes.depth(
                                            store.session(scope).configuration(), mapper)));
            // This receipt proves Broker admission even if the short publication token expired.
            store.confirmPublication(scope, turnId, receipt.brokerMessageId());
            return true;
        } finally {
            leases.release(lease);
        }
    }

    private boolean publishReady(ManagedScope scope) {
        var ready = store.queue().notification(scope);
        if (ready == null) return true;
        publishNotification(ready);
        return true;
    }

    private void publishNotification(ManagedSessionQueue.Ready ready) {
        try {
            long start = System.nanoTime();
            var receipt =
                    bus.publish(
                            Channel.SESSION_READY,
                            new Message(
                                    ready.notificationId(),
                                    ready.scope().messageGroup(),
                                    mapper.writeValueAsBytes(ready)));
            store.queue().notified(ready);
            ManagedRuntimeLog.info(
                    log,
                    "session_ready.published",
                    ready.scope(),
                    null,
                    "notification_id",
                    ready.notificationId(),
                    "requested_generation",
                    ready.requestedGeneration(),
                    "broker_message_id",
                    receipt.brokerMessageId(),
                    "elapsed_ms",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("Cannot encode Session-ready", invalid);
        }
    }

    /** Called on a bounded blocking worker, never from a reactive event loop. */
    public int retry(int limit) {
        int delivered = 0;
        for (var ready :
                httpBrain
                        ? List.<ManagedSessionQueue.Ready>of()
                        : store.queue().notifications(limit)) {
            try {
                publishNotification(ready);
                delivered++;
            } catch (ManagedTransportException unavailable) {
                /* durable dispatch remains due */
            }
        }
        for (var item : store.pendingPublications(limit)) {
            try {
                if (publish(item.scope(), item.turnId())) delivered++;
            } catch (ManagedTransportException unavailable) {
                // Leave the immutable DB outbox pending; do not skip to the next logical Turn.
            } catch (ManagedStateException busy) {
                if (!"publication_pending".equals(busy.code())) throw busy;
            }
        }
        return delivered;
    }
}
