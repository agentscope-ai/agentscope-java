/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.durable;

import io.agentscope.durable.DurableTypes.EventFilter;
import io.agentscope.durable.DurableTypes.PublicEvent;
import io.agentscope.durable.store.RecordStore;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A host-owned committed-event subscription for external effects such as webhooks.
 * Capture advances the Session cursor and saves delivery work in the same transaction.
 * Delivery is at least once; receivers use the original event ID as their idempotency key.
 */
public final class CommittedEventOutbox {
    private final DurableSession session;
    private final EventFilter filter;
    private final RecordStore store;
    private final String scope;
    private final String configuration;
    private final String initialCursor;
    private final long leaseMillis;
    private final long retryMillis;

    public CommittedEventOutbox(
            DurableSession session,
            String subscriptionId,
            EventFilter filter,
            String initialCursor,
            RecordStore store,
            Duration deliveryLease,
            Duration retryDelay) {
        this.session = Objects.requireNonNull(session);
        this.filter = Objects.requireNonNull(filter);
        this.store = Objects.requireNonNull(store);
        this.initialCursor = initialCursor;
        scope =
                "committed-hook:"
                        + DurableJson.hash(
                                List.of(
                                        session.key(),
                                        DurableJson.text(subscriptionId, "subscriptionId")));
        configuration = DurableJson.hash(Arrays.asList(filter, initialCursor));
        leaseMillis = deliveryLease.toMillis();
        retryMillis = retryDelay.toMillis();
        if (leaseMillis < 1 || retryMillis < 1)
            throw new IllegalArgumentException("Delivery lease and retry delay must be positive");
    }

    public static final class State {
        public int format = 1;
        public String configuration;
        public String cursor;
        public long admitted;
        public long completed;
        public long dueAt;
        public String owner;
        public long leaseUntil;
    }

    private record Delivery(String owner, PublicEvent event) {}

    /** Saves a bounded page of authorized committed events. Cursor expiry is reported to the host. */
    public int capture(int limit) {
        String cursor = store.transaction(scope, tx -> read(tx).cursor);
        var page = session.listEvents(filter, cursor, limit);
        return store.transaction(
                scope,
                tx -> {
                    var state = read(tx);
                    if (!Objects.equals(cursor, state.cursor)) return 0;
                    for (var event : page.events())
                        tx.put(item(++state.admitted), DurableJson.value(event));
                    state.cursor = page.cursor();
                    save(tx, state);
                    return page.events().size();
                });
    }

    /**
     * Delivers the next pending event on the caller's worker. Returning normally acknowledges
     * delivery. An exception preserves the event with its original ID and schedules a retry.
     */
    public boolean deliver(Consumer<PublicEvent> receiver) {
        Objects.requireNonNull(receiver);
        var delivery =
                store.transaction(
                        scope,
                        tx -> {
                            var state = read(tx);
                            long now = tx.now();
                            if (state.completed == state.admitted
                                    || state.dueAt > now
                                    || state.leaseUntil > now) return null;
                            state.owner = UUID.randomUUID().toString();
                            state.leaseUntil = Math.addExact(now, leaseMillis);
                            var event =
                                    DurableJson.convert(
                                            tx.get(item(state.completed + 1)), PublicEvent.class);
                            save(tx, state);
                            return new Delivery(state.owner, event);
                        });
        if (delivery == null) return false;
        try {
            receiver.accept(delivery.event());
        } catch (RuntimeException failed) {
            try {
                finish(delivery, false);
            } catch (RuntimeException recording) {
                failed.addSuppressed(recording);
            }
            throw failed;
        }
        finish(delivery, true);
        return true;
    }

    private void finish(Delivery delivery, boolean acknowledged) {
        store.transaction(
                scope,
                tx -> {
                    var state = read(tx);
                    if (!delivery.owner().equals(state.owner) || state.leaseUntil <= tx.now())
                        throw new DurableException(
                                DurableException.Code.FENCED, "Event delivery lease lost");
                    state.owner = null;
                    state.leaseUntil = 0;
                    if (acknowledged) {
                        tx.put(item(++state.completed), null);
                        state.dueAt = 0;
                    } else state.dueAt = Math.addExact(tx.now(), retryMillis);
                    save(tx, state);
                    return null;
                });
    }

    private State read(RecordStore.Transaction tx) {
        var data = tx.get("");
        if (data == null || data.isNull()) {
            var state = new State();
            state.configuration = configuration;
            state.cursor = initialCursor;
            return state;
        }
        if (data.path("format").asInt() != 1)
            throw new DurableException(
                    DurableException.Code.INCOMPATIBLE, "Unknown event outbox layout");
        var state = DurableJson.convert(data, State.class);
        if (!configuration.equals(state.configuration))
            throw new DurableException(
                    DurableException.Code.CONFLICT, "Event subscription changed");
        return state;
    }

    private static String item(long sequence) {
        return "event:" + sequence;
    }

    private static void save(RecordStore.Transaction tx, State state) {
        tx.put("", DurableJson.value(state));
    }
}
