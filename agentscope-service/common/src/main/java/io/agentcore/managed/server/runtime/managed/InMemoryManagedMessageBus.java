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

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/** Explicit test Broker. Only return from a successful callback removes its FIFO head. */
public final class InMemoryManagedMessageBus implements ManagedMessageBus {
    private final LongSupplier clock;
    private final int maxAttempts;
    private final Map<Channel, Map<String, ArrayDeque<Item>>> queues = new EnumMap<>(Channel.class);
    private final Map<Channel, List<Subscriber>> subscribers = new EnumMap<>(Channel.class);
    private final Map<String, Active> active = new HashMap<>();
    private final List<Message> deadLetters = new ArrayList<>();
    private final ScheduledExecutorService dispatch =
            Executors.newSingleThreadScheduledExecutor(InMemoryManagedMessageBus::daemon);
    private long nextId;
    private boolean closed;

    public InMemoryManagedMessageBus(LongSupplier clock, int maxAttempts) {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
        this.clock = clock;
        this.maxAttempts = maxAttempts;
        for (var channel : Channel.values()) {
            queues.put(channel, new LinkedHashMap<>());
            subscribers.put(channel, new ArrayList<>());
        }
        dispatch.scheduleWithFixedDelay(this::dispatch, 0, 10, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized Published publish(Channel channel, Message message) {
        checkOpen();
        ManagedTurnLanes.validate(channel, message.depth());
        String id = "mock-" + ++nextId;
        queues.get(channel)
                .computeIfAbsent(message.messageGroup(), ignored -> new ArrayDeque<>())
                .addLast(new Item(id, message));
        return new Published(id);
    }

    @Override
    public synchronized Subscription subscribe(
            Channel channel,
            int callbacks,
            Listener listener,
            BiConsumer<ManagedPushDelivery, Exception> errors) {
        checkOpen();
        if (callbacks < 1) throw new IllegalArgumentException("Callback capacity must be positive");
        var subscriber = new Subscriber(channel, callbacks, listener, errors);
        subscribers.get(channel).add(subscriber);
        return subscriber;
    }

    @Override
    public synchronized boolean active(ManagedPushDelivery delivery) {
        var current = active.get(delivery.deliveryId());
        return !closed
                && current != null
                && current.delivery == delivery
                && !current.subscriber.closed.get();
    }

    private synchronized void dispatch() {
        if (closed) return;
        long now = clock.getAsLong();
        for (var channel : Channel.values())
            for (var entry : new ArrayList<>(queues.get(channel).entrySet())) {
                var queue = entry.getValue();
                if (queue.isEmpty()) continue;
                var item = queue.peekFirst();
                if (item.deliveryId != null) {
                    if (now < item.until) continue;
                    active.remove(item.deliveryId);
                    item.deliveryId = null;
                }
                if (now < item.retryAt) continue;
                if (item.attempt >= maxAttempts) {
                    deadLetters.add(queue.removeFirst().message);
                    continue;
                }
                Subscriber selected = null;
                Pool selectedPool = null;
                ManagedDeliveryHandoff handoff = null;
                for (var subscriber : subscribers.get(channel)) {
                    var pool = subscriber.pool;
                    if (!subscriber.closed.get() && pool.capacity.tryAcquire()) {
                        var admission = subscriber.listener.receiveCapacity(channel);
                        if (admission == null) handoff = new ManagedDeliveryHandoff();
                        else {
                            var reserved = admission.reserve(1);
                            if (reserved.isEmpty()) {
                                pool.capacity.release();
                                continue;
                            }
                            handoff = reserved.get(0);
                        }
                        selected = subscriber;
                        selectedPool = pool;
                        break;
                    }
                }
                if (selected == null) continue;
                var subscriber = selected;
                var pool = selectedPool;
                item.deliveryId = UUID.randomUUID().toString();
                item.until = now + Duration.ofHours(3).toMillis();
                var delivery =
                        new ManagedPushDelivery(
                                item.deliveryId,
                                subscriber.id,
                                channel,
                                "fake-topic-" + channel,
                                "fake-group-" + channel,
                                item.brokerId,
                                ++item.attempt,
                                item.message,
                                handoff);
                active.put(delivery.deliveryId(), new Active(delivery, subscriber, item));
                try {
                    pool.workers.execute(() -> consume(subscriber, pool, delivery, item, queue));
                    // A suspended ancestor must not repeatedly win the only shared slot.
                    var channelQueues = queues.get(channel);
                    channelQueues.remove(entry.getKey());
                    channelQueues.put(entry.getKey(), queue);
                } catch (RuntimeException stopped) {
                    active.remove(delivery.deliveryId());
                    item.deliveryId = null;
                    delivery.handoff().close();
                    pool.capacity.release();
                }
            }
    }

    private void consume(
            Subscriber subscriber,
            Pool pool,
            ManagedPushDelivery delivery,
            Item item,
            ArrayDeque<Item> queue) {
        boolean success = false;
        Duration suspend = null;
        try {
            subscriber.listener.consume(delivery);
            success = true;
        } catch (ManagedDeliverySuspended waiting) {
            suspend = waiting.delay();
        } catch (Throwable failed) {
            subscriber.errors.accept(
                    delivery,
                    failed instanceof Exception exception
                            ? exception
                            : new ManagedTransportException("Fake callback failed", failed));
        } finally {
            synchronized (this) {
                active.remove(delivery.deliveryId());
                if (delivery.deliveryId().equals(item.deliveryId)) {
                    if (success
                            && !closed
                            && !subscriber.closed.get()
                            && queue.peekFirst() == item) {
                        queue.removeFirst();
                        delivery.handoff().confirm();
                    } else delivery.handoff().close();
                    item.deliveryId = null;
                    if (suspend != null) item.attempt--;
                    item.retryAt = clock.getAsLong() + (suspend == null ? 10 : suspend.toMillis());
                }
            }
            pool.capacity.release();
        }
    }

    public synchronized List<Message> deadLetters() {
        return List.copyOf(deadLetters);
    }

    @Override
    public void close() {
        List<Subscriber> closing = new ArrayList<>();
        synchronized (this) {
            if (closed) return;
            closed = true;
            subscribers.values().forEach(closing::addAll);
            active.clear();
        }
        dispatch.shutdownNow();
        closing.forEach(Subscriber::close);
    }

    private void checkOpen() {
        if (closed) throw new ManagedTransportException("Fake Broker is closed");
    }

    private static Thread daemon(Runnable task) {
        var thread = new Thread(task, "managed-fake-push");
        thread.setDaemon(true);
        return thread;
    }

    private final class Subscriber implements Subscription {
        private final String id = UUID.randomUUID().toString();
        private final Channel channel;
        private final Listener listener;
        private final BiConsumer<ManagedPushDelivery, Exception> errors;
        private final Pool pool;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Subscriber(
                Channel channel,
                int callbacks,
                Listener listener,
                BiConsumer<ManagedPushDelivery, Exception> errors) {
            this.channel = channel;
            this.listener = listener;
            this.errors = errors;
            this.pool = new Pool(callbacks);
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            synchronized (InMemoryManagedMessageBus.this) {
                subscribers.get(channel).remove(this);
                for (var held : new ArrayList<>(active.values()))
                    if (held.subscriber == this) {
                        active.remove(held.delivery.deliveryId());
                        if (held.delivery.deliveryId().equals(held.item.deliveryId))
                            held.item.deliveryId = null;
                    }
            }
            pool.workers.shutdownNow();
        }
    }

    private static final class Pool {
        private final Semaphore capacity;
        private final ExecutorService workers;

        private Pool(int callbacks) {
            capacity = new Semaphore(callbacks);
            workers = Executors.newFixedThreadPool(callbacks, InMemoryManagedMessageBus::daemon);
        }
    }

    private record Active(ManagedPushDelivery delivery, Subscriber subscriber, Item item) {}

    private static final class Item {
        private final String brokerId;
        private final Message message;
        private String deliveryId;
        private long until;
        private long retryAt;
        private int attempt;

        private Item(String brokerId, Message message) {
            this.brokerId = brokerId;
            this.message = message;
        }
    }
}
