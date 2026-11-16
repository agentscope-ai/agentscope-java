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
import io.agentscope.durable.store.ObjectStore;
import io.agentscope.durable.store.ObjectStore.Reference;
import io.agentscope.durable.store.RecordStore;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.producer.Producer;

/** Owned delivery/repair threads, borrowed SDK clients. */
final class RocketMqLoop implements AutoCloseable {
    static final int MAX_BYTES = 1024 * 1024;
    private final ClientServiceProvider provider;
    private final Producer producer;
    private final SimpleConsumer consumer;
    private final String incomingTopic;
    private final Consumer<Throwable> errors;
    private final ObjectStore objects;
    private final RocketMqRejections rejections;
    private final int receiveBatchSize;
    private final AtomicBoolean receiving = new AtomicBoolean(true);
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Object admission = new Object();
    private final Set<Thread> ownedThreads = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService threads =
            Executors.newScheduledThreadPool(
                    2,
                    action -> {
                        var thread = new Thread(action, "durable-execution-mq");
                        thread.setDaemon(true);
                        ownedThreads.add(thread);
                        return thread;
                    });
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    RocketMqLoop(
            ClientServiceProvider provider,
            Producer producer,
            SimpleConsumer consumer,
            String incomingTopic,
            Consumer<Throwable> errors,
            ObjectStore objects,
            RecordStore records,
            int receiveBatchSize) {
        this.provider = Objects.requireNonNull(provider);
        this.producer = Objects.requireNonNull(producer);
        this.consumer = Objects.requireNonNull(consumer);
        this.incomingTopic = Objects.requireNonNull(incomingTopic);
        this.errors = Objects.requireNonNull(errors);
        this.objects = objects;
        this.rejections = new RocketMqRejections(records, incomingTopic);
        this.receiveBatchSize = receiveBatchSize;
    }

    void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("RocketMQ execution adapter closed");
    }

    void send(String topic, String key, Object body) {
        ensureOpen();
        byte[] bytes = DurableJson.value(body).toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) {
            if (objects == null)
                throw new DurableException(
                        DurableException.Code.INVALID,
                        "Large MQ payload requires a shared ObjectStore");
            String scope =
                    DurableJson.hash(
                            DurableJson.value(body).required("request").required("session"));
            var reference = objects.put(scope, bytes);
            bytes =
                    DurableJson.value(Map.of("objectReference", reference))
                            .toString()
                            .getBytes(StandardCharsets.UTF_8);
        }
        try {
            producer.send(
                    provider.newMessageBuilder()
                            .setTopic(topic)
                            .setKeys(key)
                            .setBody(bytes)
                            .build());
        } catch (Exception error) {
            throw new DurableException(
                    DurableException.Code.UNAVAILABLE, "Execution publication unconfirmed", error);
        }
    }

    synchronized void start(Consumer<String> receiver, Runnable repair, Duration interval) {
        ensureOpen();
        if (!started.compareAndSet(false, true)) return;
        threads.execute(
                () -> {
                    while (!closed.get() && receiving.get()) {
                        try {
                            var messages =
                                    consumer.receive(receiveBatchSize, Duration.ofSeconds(30));
                            if (messages.isEmpty()) Thread.sleep(10);
                            for (var message : messages) {
                                synchronized (admission) {
                                    if (closed.get() || !receiving.get()) return;
                                    inFlight.incrementAndGet();
                                }
                                try {
                                    if (!incomingTopic.equals(message.getTopic()))
                                        throw new IllegalArgumentException(
                                                "Unexpected execution topic");
                                    var body = message.getBody().asReadOnlyBuffer();
                                    if (body.remaining() > MAX_BYTES)
                                        throw new IllegalArgumentException(
                                                "Oversized execution message");
                                    byte[] bytes = new byte[body.remaining()];
                                    body.get(bytes);
                                    String payload = new String(bytes, StandardCharsets.UTF_8);
                                    var envelope = DurableJson.parse(payload);
                                    if (envelope.has("objectReference")) {
                                        if (objects == null || envelope.size() != 1)
                                            throw new IllegalArgumentException(
                                                    "Shared ObjectStore required");
                                        var reference =
                                                DurableJson.convert(
                                                        envelope.get("objectReference"),
                                                        Reference.class);
                                        bytes =
                                                ObjectStore.verify(
                                                        objects.id(),
                                                        reference,
                                                        objects.read(reference));
                                        payload = new String(bytes, StandardCharsets.UTF_8);
                                        var value = DurableJson.parse(payload);
                                        if (!reference
                                                .scope()
                                                .equals(
                                                        DurableJson.hash(
                                                                value.required("request")
                                                                        .required("session"))))
                                            throw new IllegalArgumentException(
                                                    "Execution object scope differs");
                                    }
                                    receiver.accept(payload);
                                    // The receiver has durably admitted the command/result before
                                    // returning.
                                    consumer.ack(message);
                                } catch (Exception error) {
                                    if (!closed.get()) {
                                        if (permanent(error)) {
                                            // Record before ACK. A failed quarantine write is
                                            // retried by MQ.
                                            var rejection =
                                                    rejections.record(
                                                            message.getTopic(),
                                                            message.getBody(),
                                                            error instanceof SecurityException
                                                                    ? "unauthorized"
                                                                    : "invalid_message");
                                            consumer.ack(message);
                                            report(
                                                    new DurableException(
                                                            DurableException.Code.INVALID,
                                                            "Execution message quarantined: "
                                                                    + rejection.id()));
                                        } else report(error);
                                    }
                                } finally {
                                    synchronized (admission) {
                                        inFlight.decrementAndGet();
                                        admission.notifyAll();
                                    }
                                }
                            }
                        } catch (InterruptedException stopped) {
                            Thread.currentThread().interrupt();
                            return;
                        } catch (Exception error) {
                            if (!closed.get()) report(error);
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException stop) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }
                });
        threads.scheduleWithFixedDelay(
                () -> {
                    if (!closed.get())
                        try {
                            repair.run();
                        } catch (RuntimeException error) {
                            report(error);
                        }
                },
                0,
                interval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    private static boolean permanent(Exception error) {
        return error instanceof IllegalArgumentException
                || error instanceof SecurityException
                || error instanceof DurableException durable
                        && (durable.code() == DurableException.Code.INVALID
                                || durable.code() == DurableException.Code.INCOMPATIBLE);
    }

    void quiesce() {
        synchronized (admission) {
            receiving.set(false);
        }
    }

    boolean drainAdmissions(Duration timeout) throws InterruptedException {
        quiesce();
        long left = timeout.toNanos();
        long last = System.nanoTime();
        synchronized (admission) {
            while (inFlight.get() > 0) {
                if (left <= 0) return false;
                TimeUnit.NANOSECONDS.timedWait(admission, left);
                long now = System.nanoTime();
                left -= now - last;
                last = now;
            }
        }
        return true;
    }

    void report(Throwable error) {
        try {
            errors.accept(error);
        } catch (RuntimeException reporting) {
            System.getLogger(RocketMqLoop.class.getName())
                    .log(System.Logger.Level.WARNING, "Execution error handler failed", reporting);
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (!closed.compareAndSet(false, true)) return;
            receiving.set(false);
            threads.shutdownNow();
        }
        if (!ownedThreads.contains(Thread.currentThread()))
            try {
                threads.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
    }
}
