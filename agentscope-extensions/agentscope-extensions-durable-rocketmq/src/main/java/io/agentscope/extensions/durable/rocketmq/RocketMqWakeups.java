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

package io.agentscope.extensions.durable.rocketmq;

import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.Wakeups;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.producer.Producer;

/** RocketMQ 5 gRPC transport. Host owns topic/ACL/provisioning and the supplied SDK clients. */
public final class RocketMqWakeups implements Wakeups, AutoCloseable {
    private final ClientServiceProvider provider;
    private final Producer producer;
    private final SimpleConsumer consumer;
    private final String topic;
    private final Consumer<Throwable> errors;

    private record Listener(Consumer<SessionKey> callback) {}

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Object lifecycle = new Object();
    private volatile boolean closed;
    private ExecutorService worker;

    public RocketMqWakeups(
            ClientServiceProvider provider,
            Producer producer,
            SimpleConsumer consumer,
            String topic,
            Consumer<Throwable> errors) {
        this.provider = Objects.requireNonNull(provider);
        this.producer = Objects.requireNonNull(producer);
        this.consumer = Objects.requireNonNull(consumer);
        this.topic = DurableJson.text(topic, "topic");
        this.errors = Objects.requireNonNull(errors);
    }

    @Override
    public void publish(SessionKey session) {
        if (closed) throw new IllegalStateException("Wakeups closed");
        try {
            producer.send(
                    provider.newMessageBuilder()
                            .setTopic(topic)
                            .setKeys(session.storageKey())
                            .setBody(
                                    DurableJson.value(session)
                                            .toString()
                                            .getBytes(StandardCharsets.UTF_8))
                            .build());
        } catch (Exception error) {
            throw new DurableException(
                    DurableException.Code.UNAVAILABLE, "RocketMQ wakeup unconfirmed", error);
        }
    }

    @Override
    public AutoCloseable subscribe(Consumer<SessionKey> listener) {
        var registered = new Listener(Objects.requireNonNull(listener));
        synchronized (lifecycle) {
            if (closed) throw new IllegalStateException("Wakeups closed");
            listeners.add(registered);
            if (worker == null) {
                worker =
                        Executors.newSingleThreadExecutor(
                                action -> {
                                    var thread = new Thread(action, "durable-rocketmq");
                                    thread.setDaemon(true);
                                    return thread;
                                });
                worker.execute(this::consume);
            }
            lifecycle.notifyAll();
        }
        return () -> listeners.removeIf(current -> current == registered);
    }

    private void consume() {
        try {
            while (!closed) {
                synchronized (lifecycle) {
                    while (!closed && listeners.isEmpty()) lifecycle.wait();
                }
                if (closed) return;
                try {
                    for (var message : consumer.receive(16, Duration.ofSeconds(30))) {
                        if (closed) return;
                        var targets = List.copyOf(listeners);
                        if (targets.isEmpty()) continue;
                        try {
                            var body = message.getBody().asReadOnlyBuffer();
                            if (body.remaining() > 4096)
                                throw new IllegalArgumentException("Oversized wakeup");
                            byte[] bytes = new byte[body.remaining()];
                            body.get(bytes);
                            var key =
                                    DurableJson.convert(
                                            DurableJson.parse(
                                                    new String(bytes, StandardCharsets.UTF_8)),
                                            SessionKey.class);
                            boolean accepted = true;
                            for (var target : targets) {
                                try {
                                    target.callback().accept(key);
                                } catch (RuntimeException error) {
                                    accepted = false;
                                    report(error);
                                }
                            }
                            if (accepted) consumer.ack(message);
                        } catch (Exception error) {
                            if (!closed) report(error);
                        }
                    }
                } catch (Exception error) {
                    if (!closed) report(error);
                    if (!closed) Thread.sleep(100);
                }
            }
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
        }
    }

    private void report(Throwable error) {
        try {
            errors.accept(error);
        } catch (RuntimeException reportingError) {
            System.getLogger(RocketMqWakeups.class.getName())
                    .log(
                            System.Logger.Level.WARNING,
                            "Wakeup error callback failed",
                            reportingError);
        }
    }

    /** Stops the delivery loop; supplied Producer/SimpleConsumer remain caller-owned. */
    @Override
    public void close() {
        synchronized (lifecycle) {
            if (closed) return;
            closed = true;
            listeners.clear();
            lifecycle.notifyAll();
            if (worker != null) worker.shutdownNow();
        }
    }
}
