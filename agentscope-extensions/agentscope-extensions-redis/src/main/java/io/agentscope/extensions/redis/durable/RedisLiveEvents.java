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
package io.agentscope.extensions.redis.durable;

import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.LiveEvent;
import io.agentscope.durable.LiveEvents;
import io.lettuce.core.api.sync.BaseRedisCommands;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Redis Pub/Sub fan-out for previews shared between separate Durable Brain and API hosts. */
public final class RedisLiveEvents implements LiveEvents, AutoCloseable {
    private static final int MAX_PENDING = 128;
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    private static final System.Logger LOG = System.getLogger(RedisLiveEvents.class.getName());

    private final String channel;
    private final BaseRedisCommands<String, String> publisher;
    private final StatefulRedisPubSubConnection<String, String> subscriber;
    private final ThreadPoolExecutor outgoing;
    private final ThreadPoolExecutor incoming;
    private final LiveEvents listeners = LiveEvents.local();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final RedisPubSubAdapter<String, String> receiver =
            new RedisPubSubAdapter<>() {
                @Override
                public void message(String source, String payload) {
                    if (!closed.get() && channel.equals(source) && fits(payload))
                        incoming.execute(() -> receive(payload));
                }
            };

    /**
     * Use a publisher on Brain, a subscriber on API, or both in a combined host. Connections
     * belong to the application. Give the subscriber connection to this transport exclusively.
     */
    public RedisLiveEvents(
            String channel,
            BaseRedisCommands<String, String> publisher,
            StatefulRedisPubSubConnection<String, String> subscriber) {
        if (publisher == null && subscriber == null)
            throw new IllegalArgumentException("A Redis publisher or subscriber is required");
        this.channel = DurableJson.text(channel, "channel");
        this.publisher = publisher;
        this.subscriber = subscriber;
        outgoing = publisher == null ? null : worker("durable-live-redis-publish");
        incoming = subscriber == null ? null : worker("durable-live-redis-subscribe");
        if (subscriber != null) {
            subscriber.addListener(receiver);
            try {
                subscriber.sync().subscribe(channel);
            } catch (RuntimeException failure) {
                close();
                throw failure;
            }
        }
    }

    @Override
    public void publish(LiveEvent event) {
        if (outgoing == null || closed.get()) return;
        String payload = DurableJson.value(Objects.requireNonNull(event)).toString();
        if (fits(payload))
            outgoing.execute(
                    () -> {
                        try {
                            publisher.publish(channel, payload);
                        } catch (RuntimeException failure) {
                            report("Redis preview publication failed", failure);
                        }
                    });
    }

    @Override
    public AutoCloseable subscribe(Consumer<LiveEvent> listener) {
        Objects.requireNonNull(listener);
        if (closed.get()) throw new IllegalStateException("Redis event transport is closed");
        return listeners.subscribe(
                event -> {
                    if (!closed.get()) listener.accept(event);
                });
    }

    private void receive(String payload) {
        try {
            if (closed.get()) return;
            var event = DurableJson.convert(DurableJson.parse(payload), LiveEvent.class);
            if ("1".equals(event.schemaVersion())) listeners.publish(event);
        } catch (RuntimeException failure) {
            report("Redis preview delivery failed", failure);
        }
    }

    private static boolean fits(String payload) {
        return payload.length() <= MAX_PAYLOAD_BYTES
                && payload.getBytes(StandardCharsets.UTF_8).length <= MAX_PAYLOAD_BYTES;
    }

    private static ThreadPoolExecutor worker(String name) {
        return new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_PENDING),
                action -> {
                    var thread = new Thread(action, name);
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.DiscardPolicy());
    }

    private static void report(String message, RuntimeException failure) {
        LOG.log(System.Logger.Level.DEBUG, message, failure);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (outgoing != null) outgoing.shutdownNow();
        if (incoming != null) incoming.shutdownNow();
        if (subscriber != null) {
            subscriber.removeListener(receiver);
            try {
                subscriber.async().unsubscribe(channel);
            } catch (RuntimeException failure) {
                report("Redis preview unsubscribe failed", failure);
            }
        }
    }
}
