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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.SessionKey;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RocketMqWakeupsTest {
    @Test
    void publishContainsOnlyStableSessionIdentity() throws Exception {
        var producer = mock(Producer.class);
        var key = new SessionKey("tenant", "session");
        var wakeups =
                new RocketMqWakeups(
                        ClientServiceProvider.loadService(),
                        producer,
                        mock(SimpleConsumer.class),
                        "durable",
                        error -> {});
        wakeups.publish(key);
        var argument = ArgumentCaptor.forClass(Message.class);
        verify(producer).send(argument.capture());
        var bytes = argument.getValue().getBody().asReadOnlyBuffer();
        byte[] value = new byte[bytes.remaining()];
        bytes.get(value);
        assertEquals(
                key,
                DurableJson.convert(
                        DurableJson.parse(new String(value, StandardCharsets.UTF_8)),
                        SessionKey.class));
    }

    @Test
    void failedNotificationIsNotAcknowledgedAndRedeliveryIsSafe() throws Exception {
        var consumer = mock(SimpleConsumer.class);
        var message = mock(MessageView.class);
        var key = new SessionKey("tenant", "session");
        when(message.getBody())
                .thenReturn(
                        ByteBuffer.wrap(
                                DurableJson.value(key)
                                        .toString()
                                        .getBytes(StandardCharsets.UTF_8)));
        when(consumer.receive(anyInt(), any(Duration.class)))
                .thenAnswer(
                        invocation -> {
                            Thread.sleep(10);
                            return List.of(message);
                        });
        var deliveries = new AtomicInteger();
        var failures = new AtomicInteger();
        var received = new CountDownLatch(1);
        var wakeups =
                new RocketMqWakeups(
                        ClientServiceProvider.loadService(),
                        mock(Producer.class),
                        consumer,
                        "durable",
                        error -> failures.incrementAndGet());
        try (wakeups;
                var subscription =
                        wakeups.subscribe(
                                session -> {
                                    assertEquals(key, session);
                                    if (deliveries.incrementAndGet() == 1)
                                        throw new IllegalStateException(
                                                "Dispatch temporarily unavailable");
                                    received.countDown();
                                })) {
            assertTrue(received.await(3, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (mockingDetails(consumer).getInvocations().stream()
                            .noneMatch(i -> "ack".equals(i.getMethod().getName()))
                    && System.nanoTime() < deadline) Thread.sleep(5);
            verify(consumer, atLeastOnce()).ack(message);
            assertEquals(1, failures.get());
        }
    }

    @Test
    void fanoutRetryAndResubscribeShareOneConsumerAndSurviveReportingFailures() throws Exception {
        var consumer = mock(SimpleConsumer.class);
        var producer = mock(Producer.class);
        var message = mock(MessageView.class);
        var key = new SessionKey("tenant", "session");
        when(message.getBody())
                .thenReturn(
                        ByteBuffer.wrap(
                                DurableJson.value(key)
                                        .toString()
                                        .getBytes(StandardCharsets.UTF_8)));
        var incoming = new LinkedBlockingQueue<MessageView>();
        when(consumer.receive(anyInt(), any(Duration.class)))
                .thenAnswer(
                        call -> {
                            var next = incoming.poll(100, TimeUnit.MILLISECONDS);
                            return next == null ? List.of() : List.of(next);
                        });
        var acknowledged = new CountDownLatch(1);
        doAnswer(
                        call -> {
                            acknowledged.countDown();
                            return null;
                        })
                .when(consumer)
                .ack(message);
        var delivered = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var reports = new AtomicInteger();
        try (var wakeups =
                new RocketMqWakeups(
                        ClientServiceProvider.loadService(),
                        producer,
                        consumer,
                        "durable",
                        error -> {
                            reports.incrementAndGet();
                            throw new IllegalStateException("Reporter failed");
                        })) {
            try (var failing =
                            wakeups.subscribe(
                                    session -> {
                                        throw new IllegalStateException("Receiver failed");
                                    });
                    var healthy =
                            wakeups.subscribe(
                                    session -> {
                                        calls.incrementAndGet();
                                        delivered.countDown();
                                    })) {
                incoming.add(message);
                assertTrue(delivered.await(3, TimeUnit.SECONDS));
                verify(consumer, never()).ack(message);
                assertEquals(1, reports.get());
                failing.close();
                incoming.add(message);
                assertTrue(acknowledged.await(3, TimeUnit.SECONDS));
                assertEquals(2, calls.get());
            }
            var redelivered = new CountDownLatch(1);
            try (var replacement = wakeups.subscribe(session -> redelivered.countDown())) {
                incoming.add(message);
                assertTrue(redelivered.await(3, TimeUnit.SECONDS));
            }
            wakeups.close();
            assertThrows(IllegalStateException.class, () -> wakeups.subscribe(session -> {}));
        }
        verify(consumer, never()).close();
        verify(producer, never()).close();
    }
}
