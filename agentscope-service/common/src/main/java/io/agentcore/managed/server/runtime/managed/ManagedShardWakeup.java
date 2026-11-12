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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.apache.rocketmq.client.apis.message.MessageView;

/** Bounded transport-only nudges for lost LiteTopic ready notifications. No business replay or SQL. */
final class ManagedShardWakeup implements AutoCloseable {
    static final String PROPERTY = "ma_transport_wakeup";
    static final byte[] BODY = "managed-shard-wakeup-v1".getBytes(StandardCharsets.US_ASCII);

    record Destination(ManagedMessageBus.Channel channel, String liteTopic) {}

    private final List<Destination> destinations;
    private final Consumer<Destination> send;
    private final ScheduledExecutorService timer;
    private int cursor;
    private volatile boolean closed;

    ManagedShardWakeup(List<Destination> destinations, Consumer<Destination> send) {
        this.destinations = List.copyOf(destinations);
        this.send = send;
        timer =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            var thread = new Thread(r, "managed-mq-shard-wakeup");
                            thread.setDaemon(true);
                            return thread;
                        });
    }

    void start() {
        // One outstanding send; at most eight requests/s per producer, with no work queue.
        timer.scheduleWithFixedDelay(this::tick, 5000, 125, TimeUnit.MILLISECONDS);
    }

    void tick() {
        if (closed || destinations.isEmpty()) return;
        var destination = destinations.get(cursor);
        cursor = (cursor + 1) % destinations.size();
        try {
            send.accept(destination);
        } catch (RuntimeException unavailable) {
            // The sender records failure. Move on fairly; a later sweep retries this shard.
        }
    }

    static boolean matches(MessageView view, ManagedStableRouting routing) {
        String version = view.getProperties().get(PROPERTY);
        if (version == null) return false;
        var body = view.getBody().duplicate();
        if (!"1".equals(version)
                || routing == null
                || !routing.ownsDestination(view.getLiteTopic().orElse(""))
                || body.remaining() != BODY.length)
            throw new IllegalArgumentException("Invalid transport wakeup");
        byte[] bytes = new byte[body.remaining()];
        body.get(bytes);
        if (!Arrays.equals(bytes, BODY)) throw new IllegalArgumentException("Invalid wakeup body");
        return true;
    }

    @Override
    public void close() {
        closed = true;
        timer.shutdownNow();
    }
}
