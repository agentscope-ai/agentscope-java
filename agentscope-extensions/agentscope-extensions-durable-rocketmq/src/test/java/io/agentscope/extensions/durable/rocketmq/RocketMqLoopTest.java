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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.extensions.durable.jdbc.JdbcRecordStore;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.LitePushConsumer;
import org.apache.rocketmq.client.apis.consumer.MessageListener;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RocketMqLoopTest {
    @TempDir Path directory;

    @Test
    void closeWaitsForAdmittedCallbackBeforeBorrowedStoreCanBeClosed() throws Exception {
        var consumer = mock(LitePushConsumer.class);
        var producer = mock(Producer.class);
        var listener = new AtomicReference<MessageListener>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try (var records = JdbcRecordStore.sqlite(directory.resolve("close.db"));
                var loop =
                        new RocketMqLoop(
                                mock(ClientServiceProvider.class),
                                producer,
                                callback -> {
                                    listener.set(callback);
                                    return consumer;
                                },
                                "commands",
                                ignored -> {},
                                null,
                                records)) {
            loop.start(
                    (body, message) -> {
                        entered.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(error);
                        }
                        records.transaction(
                                "received",
                                tx -> {
                                    tx.put("receipt", body);
                                    return null;
                                });
                    },
                    null,
                    Duration.ofMillis(10));
            var delivery = executor.submit(() -> listener.get().consume(message()));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var closing = executor.submit(loop::close);
                assertThrows(TimeoutException.class, () -> closing.get(200, TimeUnit.MILLISECONDS));
                release.countDown();
                assertEquals(ConsumeResult.SUCCESS, delivery.get(5, TimeUnit.SECONDS));
                closing.get(5, TimeUnit.SECONDS);
                assertNotNull(records.transaction("received", tx -> tx.get("receipt")));
                verify(consumer).close();
                verify(producer, never()).close();
            } finally {
                release.countDown();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void closeFromCallbackDoesNotWaitForItsOwnThread() throws Exception {
        var listener = new AtomicReference<MessageListener>();
        var stopped = new CountDownLatch(1);
        try (var records = JdbcRecordStore.sqlite(directory.resolve("self-close.db"));
                var loop =
                        new RocketMqLoop(
                                mock(ClientServiceProvider.class),
                                mock(Producer.class),
                                callback -> {
                                    listener.set(callback);
                                    return stopped::countDown;
                                },
                                "commands",
                                ignored -> {},
                                null,
                                records)) {
            loop.start((body, message) -> loop.close(), null, Duration.ofMillis(10));
            assertTimeoutPreemptively(
                    Duration.ofSeconds(2),
                    () -> assertEquals(ConsumeResult.SUCCESS, listener.get().consume(message())));
            assertTrue(stopped.await(2, TimeUnit.SECONDS));
        }
    }

    private static MessageView message() {
        var message = mock(MessageView.class);
        when(message.getTopic()).thenReturn("commands");
        when(message.getBody()).thenReturn(ByteBuffer.wrap("{}".getBytes(StandardCharsets.UTF_8)));
        return message;
    }
}
