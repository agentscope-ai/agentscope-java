/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.durable.rocketmq;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.LiteSimpleConsumer;
import org.apache.rocketmq.client.apis.consumer.MessageListener;
import org.apache.rocketmq.client.apis.message.MessageView;

/** Holds and renews a LiteTopic delivery while its Turn phase is active. */
final class RocketMqStageConsumer implements AutoCloseable {
    private static final Duration INVISIBLE = Duration.ofSeconds(30);
    private final LiteSimpleConsumer client;
    private final MessageListener listener;
    private final Semaphore slots;
    private final AtomicBoolean receiving = new AtomicBoolean(true);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ExecutorService receivers =
            Executors.newSingleThreadExecutor(task -> daemon(task, "durable-turn-receive"));
    private final ExecutorService callbacks;
    private final ScheduledThreadPoolExecutor renewals =
            new ScheduledThreadPoolExecutor(1, task -> daemon(task, "durable-turn-renew"));

    static RocketMqLoop.Subscription subscription(
            ClientServiceProvider provider,
            ClientConfiguration configuration,
            String topic,
            String group,
            int capacity,
            Duration receiveWait) {
        return listener ->
                new RocketMqStageConsumer(
                        provider.newLiteSimpleConsumerBuilder()
                                .setClientConfiguration(configuration)
                                .bindTopic(topic)
                                .setConsumerGroup(group)
                                .setAwaitDuration(receiveWait)
                                .build(),
                        listener,
                        capacity);
    }

    RocketMqStageConsumer(LiteSimpleConsumer client, MessageListener listener, int capacity) {
        this.client = client;
        this.listener = listener;
        slots = new Semaphore(capacity);
        callbacks =
                Executors.newFixedThreadPool(capacity, task -> daemon(task, "durable-turn-ack"));
        renewals.setRemoveOnCancelPolicy(true);
        receivers.execute(this::receive);
    }

    private void receive() {
        try {
            while (receiving.get()) {
                slots.acquire();
                boolean submitted = false;
                try {
                    var received = client.receive(1, INVISIBLE);
                    if (!received.isEmpty() && receiving.get()) {
                        MessageView message = received.get(0);
                        callbacks.execute(() -> process(message));
                        submitted = true;
                    }
                } catch (Exception failure) {
                    if (!receiving.get()) return;
                    report(failure);
                    Thread.sleep(200);
                } finally {
                    if (!submitted) slots.release();
                }
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    }

    private void process(MessageView message) {
        try {
            var extending = new AtomicBoolean(true);
            long renewalInterval = INVISIBLE.toMillis() / 3;
            var renewal =
                    renewals.scheduleWithFixedDelay(
                            () -> {
                                synchronized (message) {
                                    if (extending.get()) extend(message);
                                }
                            },
                            renewalInterval,
                            renewalInterval,
                            TimeUnit.MILLISECONDS);
            ConsumeResult result;
            try {
                result = listener.consume(message);
            } finally {
                extending.set(false);
                renewal.cancel(false);
            }
            synchronized (message) {
                if (result == ConsumeResult.SUCCESS) client.ack(message);
            }
        } catch (Exception failure) {
            if (!closed.get()) report(failure);
        } finally {
            slots.release();
        }
    }

    private void extend(MessageView message) {
        try {
            client.changeInvisibleDuration(message, INVISIBLE);
        } catch (Exception failure) {
            if (!closed.get()) report(failure);
        }
    }

    private static Thread daemon(Runnable task, String name) {
        var thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void report(Exception failure) {
        System.getLogger(RocketMqStageConsumer.class.getName())
                .log(System.Logger.Level.WARNING, "RocketMQ Turn delivery failed", failure);
    }

    void stopReceiving() {
        if (receiving.compareAndSet(true, false)) receivers.shutdownNow();
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        stopReceiving();
        callbacks.shutdown();
        try {
            if (!callbacks.awaitTermination(5, TimeUnit.SECONDS)) callbacks.shutdownNow();
        } catch (InterruptedException interrupted) {
            callbacks.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            renewals.shutdownNow();
            client.close();
        }
    }
}
