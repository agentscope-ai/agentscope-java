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

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.MessageListener;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.Producer;

/** One LITE parent subscription and the publisher used by its adapter. */
final class RocketMqLoop implements AutoCloseable {
    static final int MAX_BYTES = 1024 * 1024;

    @FunctionalInterface
    interface Subscription {
        AutoCloseable open(MessageListener listener) throws Exception;
    }

    static Subscription subscription(
            ClientServiceProvider provider,
            ClientConfiguration configuration,
            String topic,
            String group,
            int cachedMessages) {
        return subscription(provider, configuration, topic, group, cachedMessages, null);
    }

    static Subscription subscription(
            ClientServiceProvider provider,
            ClientConfiguration configuration,
            String topic,
            String group,
            int cachedMessages,
            String route) {
        return listener -> {
            var consumer =
                    provider.newLitePushConsumerBuilder()
                            .setClientConfiguration(configuration)
                            .bindTopic(topic)
                            .setConsumerGroup(group)
                            .setConsumptionThreadCount(Math.min(4, cachedMessages))
                            .setMaxCacheMessageCount(cachedMessages)
                            .setMaxCacheMessageSizeInBytes(cachedMessages * MAX_BYTES)
                            .setEnableFifoConsumeAccelerator(true)
                            .setMessageListener(listener)
                            .build();
            try {
                if (route != null) consumer.subscribeLite(route);
                return consumer;
            } catch (Exception failure) {
                try {
                    consumer.close();
                } catch (Exception closeError) {
                    failure.addSuppressed(closeError);
                }
                throw failure;
            }
        };
    }

    private final ClientServiceProvider provider;
    private final Producer producer;
    private final Subscription subscription;
    private final String incomingTopic;
    private final Consumer<Throwable> errors;
    private final ObjectStore objects;
    private final RocketMqRejections rejections;
    private final Object admission = new Object();
    private final Set<Thread> callbacks = ConcurrentHashMap.newKeySet();
    private final Set<String> pendingHints = ConcurrentHashMap.newKeySet();
    private volatile Thread repairThread;
    private final ScheduledExecutorService repairs =
            Executors.newSingleThreadScheduledExecutor(
                    action -> {
                        var thread = new Thread(action, "durable-rocketmq-repair");
                        thread.setDaemon(true);
                        repairThread = thread;
                        return thread;
                    });
    private volatile boolean closed;
    private boolean accepting = true;
    private int inFlight;
    private AutoCloseable consumer;

    RocketMqLoop(
            ClientServiceProvider provider,
            Producer producer,
            Subscription subscription,
            String incomingTopic,
            Consumer<Throwable> errors,
            ObjectStore objects,
            RecordStore records) {
        this.provider = Objects.requireNonNull(provider);
        this.producer = Objects.requireNonNull(producer);
        this.subscription = Objects.requireNonNull(subscription);
        this.incomingTopic = Objects.requireNonNull(incomingTopic);
        this.errors = Objects.requireNonNull(errors);
        this.objects = objects;
        rejections = new RocketMqRejections(records, incomingTopic);
    }

    void ensureOpen() {
        if (closed) throw new IllegalStateException("RocketMQ adapter closed");
    }

    void send(String topic, String liteTopic, String key, Object body) {
        ensureOpen();
        send(provider, producer, topic, liteTopic, key, body, objects);
    }

    static void send(
            ClientServiceProvider provider,
            Producer producer,
            String topic,
            String liteTopic,
            String key,
            Object body,
            ObjectStore objects) {
        var value = DurableJson.value(body);
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) {
            if (objects == null)
                throw new DurableException(
                        DurableException.Code.INVALID,
                        "Large MQ payload requires a shared ObjectStore");
            var reference = objects.put(objectScope(value), bytes);
            bytes =
                    DurableJson.value(Map.of("objectReference", reference))
                            .toString()
                            .getBytes(StandardCharsets.UTF_8);
        }
        try {
            producer.send(
                    provider.newMessageBuilder()
                            .setTopic(topic)
                            .setLiteTopic(liteTopic)
                            .setKeys(key)
                            .setBody(bytes)
                            .build());
        } catch (Exception error) {
            throw new DurableException(
                    DurableException.Code.UNAVAILABLE, "RocketMQ publication unconfirmed", error);
        }
    }

    synchronized void start(
            BiConsumer<JsonNode, MessageView> receiver, Runnable repair, Duration interval) {
        ensureOpen();
        if (consumer != null) return;
        try {
            consumer = subscription.open(message -> receive(message, receiver));
        } catch (Exception error) {
            throw new DurableException(
                    DurableException.Code.UNAVAILABLE,
                    "Cannot start LiteTopic consumer for " + incomingTopic,
                    error);
        }
        if (repair != null)
            repairs.scheduleWithFixedDelay(
                    () -> {
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

    private ConsumeResult receive(MessageView message, BiConsumer<JsonNode, MessageView> receiver) {
        synchronized (admission) {
            if (!accepting || closed) return ConsumeResult.FAILURE;
            inFlight++;
            callbacks.add(Thread.currentThread());
        }
        try {
            if (!incomingTopic.equals(message.getTopic()))
                throw new IllegalArgumentException("Unexpected RocketMQ parent Topic");
            receiver.accept(payload(message), message);
            return ConsumeResult.SUCCESS;
        } catch (RuntimeException error) {
            if (!permanent(error)) {
                report(error);
                return ConsumeResult.FAILURE;
            }
            try {
                var rejection =
                        rejections.record(
                                message.getTopic(),
                                message.getBody(),
                                error instanceof SecurityException
                                        ? "unauthorized"
                                        : "invalid_message");
                report(
                        new DurableException(
                                DurableException.Code.INVALID,
                                "RocketMQ message quarantined: " + rejection.id()));
                return ConsumeResult.SUCCESS;
            } catch (RuntimeException failure) {
                report(failure);
                return ConsumeResult.FAILURE;
            }
        } finally {
            synchronized (admission) {
                callbacks.remove(Thread.currentThread());
                inFlight--;
                admission.notifyAll();
            }
        }
    }

    private JsonNode payload(MessageView message) {
        var body = message.getBody().asReadOnlyBuffer();
        if (body.remaining() > MAX_BYTES)
            throw new IllegalArgumentException("Oversized RocketMQ message");
        byte[] bytes = new byte[body.remaining()];
        body.get(bytes);
        var value = DurableJson.parse(new String(bytes, StandardCharsets.UTF_8));
        if (!value.has("objectReference")) return value;
        if (objects == null || value.size() != 1)
            throw new IllegalArgumentException("Shared ObjectStore required");
        var reference = DurableJson.convert(value.get("objectReference"), Reference.class);
        bytes = ObjectStore.verify(objects.id(), reference, objects.read(reference));
        value = DurableJson.parse(new String(bytes, StandardCharsets.UTF_8));
        if (!reference.scope().equals(objectScope(value)))
            throw new IllegalArgumentException("Execution object scope differs");
        return value;
    }

    private static String objectScope(JsonNode value) {
        var request =
                value.has("requests")
                        ? value.required("requests").required(0)
                        : value.required("request");
        return DurableJson.hash(request.required("thread").required("session"));
    }

    private static boolean permanent(RuntimeException error) {
        return error instanceof IllegalArgumentException
                || error instanceof SecurityException
                || error instanceof DurableException durable
                        && (durable.code() == DurableException.Code.INVALID
                                || durable.code() == DurableException.Code.INCOMPATIBLE);
    }

    void wake(String key, Runnable work) {
        if (closed || pendingHints.size() >= 1024 || !pendingHints.add(key)) return;
        try {
            repairs.execute(
                    () -> {
                        pendingHints.remove(key);
                        try {
                            work.run();
                        } catch (RuntimeException failure) {
                            report(failure);
                        }
                    });
        } catch (RejectedExecutionException closed) {
            pendingHints.remove(key);
        }
    }

    void quiesce() {
        synchronized (admission) {
            accepting = false;
        }
    }

    synchronized boolean stopStageReceiver() {
        if (!(consumer instanceof RocketMqStageConsumer stages)) return false;
        stages.stopReceiving();
        return true;
    }

    boolean drainAdmissions(Duration timeout) throws InterruptedException {
        quiesce();
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (admission) {
            while (inFlight > 0) {
                long left = deadline - System.nanoTime();
                if (left <= 0) return false;
                TimeUnit.NANOSECONDS.timedWait(admission, left);
            }
        }
        return true;
    }

    void report(Throwable error) {
        try {
            errors.accept(error);
        } catch (RuntimeException reporting) {
            System.getLogger(RocketMqLoop.class.getName())
                    .log(System.Logger.Level.WARNING, "RocketMQ error handler failed", reporting);
        }
    }

    @Override
    public void close() {
        AutoCloseable started;
        synchronized (this) {
            if (closed) return;
            closed = true;
            quiesce();
            started = consumer;
            repairs.shutdownNow();
        }
        if (callbacks.contains(Thread.currentThread()) || Thread.currentThread() == repairThread) {
            var closer = new Thread(() -> finishClose(started), "durable-rocketmq-close");
            closer.setDaemon(true);
            closer.start();
        } else finishClose(started);
    }

    private void finishClose(AutoCloseable started) {
        try {
            drainAdmissions(Duration.ofSeconds(5));
            if (started != null) started.close();
            repairs.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            report(failure);
        }
    }
}
