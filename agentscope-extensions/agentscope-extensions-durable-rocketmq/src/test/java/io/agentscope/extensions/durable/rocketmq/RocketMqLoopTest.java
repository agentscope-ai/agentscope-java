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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.durable.DurableJson;
import io.agentscope.extensions.durable.jdbc.JdbcRecordStore;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RocketMqLoopTest {
    @TempDir Path directory;

    @Test
    void closeWaitsForAdmittedCallbackBeforeBorrowedStoreCanBeClosed() throws Exception {
        var consumer = mock(SimpleConsumer.class);
        var producer = mock(Producer.class);
        var message = message(consumer);
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var closer = Executors.newSingleThreadExecutor();
        try (var records = JdbcRecordStore.sqlite(directory.resolve("close.db"));
                var loop =
                        new RocketMqLoop(
                                mock(ClientServiceProvider.class),
                                producer,
                                consumer,
                                "commands",
                                ignored -> {},
                                null,
                                records,
                                1)) {
            loop.start(
                    body -> {
                        entered.countDown();
                        boolean waiting = true;
                        while (waiting) {
                            try {
                                release.await();
                                waiting = false;
                            } catch (InterruptedException closing) {
                                interrupted.countDown();
                            }
                        }
                        records.transaction(
                                "received",
                                tx -> {
                                    tx.put("receipt", DurableJson.value(body));
                                    return null;
                                });
                    },
                    () -> {},
                    Duration.ofMillis(10));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var closing = closer.submit(loop::close);
                assertTrue(interrupted.await(5, TimeUnit.SECONDS));
                assertFalse(
                        closing.isDone(),
                        "Close returned while the store callback was still running");
                release.countDown();
                closing.get(5, TimeUnit.SECONDS);
                assertNotNull(records.transaction("received", tx -> tx.get("receipt")));
                verify(consumer).ack(message);
                verify(consumer, never()).close();
                verify(producer, never()).close();
            } finally {
                release.countDown();
            }
        } finally {
            closer.shutdownNow();
        }
    }

    @Test
    void closeFromCallbackDoesNotWaitForItsOwnThread() throws Exception {
        var consumer = mock(SimpleConsumer.class);
        message(consumer);
        var returned = new CountDownLatch(1);
        try (var records = JdbcRecordStore.sqlite(directory.resolve("self-close.db"));
                var loop =
                        new RocketMqLoop(
                                mock(ClientServiceProvider.class),
                                mock(Producer.class),
                                consumer,
                                "commands",
                                ignored -> {},
                                null,
                                records,
                                1)) {
            loop.start(
                    body -> {
                        loop.close();
                        returned.countDown();
                    },
                    () -> {},
                    Duration.ofMillis(10));
            assertTrue(returned.await(2, TimeUnit.SECONDS));
        }
    }

    private MessageView message(SimpleConsumer consumer) throws Exception {
        var message = mock(MessageView.class);
        when(message.getTopic()).thenReturn("commands");
        when(message.getBody()).thenReturn(ByteBuffer.wrap("{}".getBytes(StandardCharsets.UTF_8)));
        var offered = new AtomicBoolean();
        when(consumer.receive(anyInt(), any()))
                .thenAnswer(ignored -> offered.getAndSet(true) ? List.of() : List.of(message));
        return message;
    }
}
