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
package org.apache.rocketmq.client.java.impl.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.ConsumeResultSuspend;
import org.apache.rocketmq.client.java.hook.MessageInterceptor;
import org.apache.rocketmq.client.java.message.MessageViewImpl;
import org.apache.rocketmq.client.java.misc.ClientId;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.Futures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class ManagedRetryingLiteConsumeServiceTest {
    MessageViewImpl view(String scope) {
        var view = mock(MessageViewImpl.class);
        when(view.getLiteTopic()).thenReturn(Optional.of(scope));
        return view;
    }

    @Test
    void waitsWithoutHoldingCallbackThreadOrAcknowledgingTheFifoHead() throws Exception {
        var head = view("one");
        var tail = view("one");
        var other = view("two");
        var started = new CountDownLatch(1);
        var done = new CountDownLatch(2);
        var attempts = new AtomicInteger();
        var tails = new AtomicInteger();
        var workers = Executors.newSingleThreadExecutor();
        var timer = Executors.newSingleThreadScheduledExecutor();
        var pq = mock(ProcessQueue.class);
        when(pq.eraseFifoMessage(any(), any()))
                .thenAnswer(
                        call -> {
                            assertEquals(ConsumeResult.SUCCESS, call.getArgument(1));
                            done.countDown();
                            return Futures.immediateVoidFuture();
                        });
        try (var service =
                new ManagedRetryingLiteConsumeService(
                        new ClientId(),
                        "resumes",
                        message -> {
                            if (message == head && attempts.incrementAndGet() == 1) {
                                started.countDown();
                                return ConsumeResultSuspend.of(Duration.ofMillis(400));
                            }
                            if (message == tail) tails.incrementAndGet();
                            return ConsumeResult.SUCCESS;
                        },
                        workers,
                        mock(MessageInterceptor.class),
                        timer,
                        false)) {
            service.consume(pq, List.of(head, tail));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertEquals(
                    ConsumeResult.SUCCESS, service.consume(other).get(200, TimeUnit.MILLISECONDS));
            verify(pq, never()).eraseFifoMessage(any(), any());
            assertEquals(0, tails.get());
            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertEquals(2, attempts.get());
            assertEquals(1, tails.get());
            verify(head, never()).incrementAndGetDeliveryAttempt();
        } finally {
            workers.shutdownNow();
            timer.shutdownNow();
        }
    }

    @Test
    void shutdownCancelsRetainedMessagesWithoutAckOrASecondCallback() throws Exception {
        var workers = Executors.newSingleThreadExecutor();
        var timer = Executors.newSingleThreadScheduledExecutor();
        var first = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var service =
                new ManagedRetryingLiteConsumeService(
                        new ClientId(),
                        "resumes",
                        message -> {
                            calls.incrementAndGet();
                            first.countDown();
                            return ConsumeResultSuspend.of(Duration.ofSeconds(30));
                        },
                        workers,
                        mock(MessageInterceptor.class),
                        timer,
                        true);
        try {
            var pending = service.consume(view("one"));
            assertTrue(first.await(2, TimeUnit.SECONDS));
            assertFalse(pending.isDone());
            service.close();
            assertTrue(pending.isCancelled());
            assertTrue(service.consume(view("two")).isCancelled());
            assertEquals(1, calls.get());
        } finally {
            service.close();
            workers.shutdownNow();
            timer.shutdownNow();
        }
    }

    @Test
    void ordinaryFailuresRemainOwnedByTheSdkFailurePolicy() throws Exception {
        var workers = Executors.newSingleThreadExecutor();
        var timer = Executors.newSingleThreadScheduledExecutor();
        try (var service =
                new ManagedRetryingLiteConsumeService(
                        new ClientId(),
                        "resumes",
                        message -> ConsumeResult.FAILURE,
                        workers,
                        mock(MessageInterceptor.class),
                        timer,
                        true)) {
            assertEquals(
                    ConsumeResult.FAILURE, service.consume(view("one")).get(2, TimeUnit.SECONDS));
        } finally {
            workers.shutdownNow();
            timer.shutdownNow();
        }
    }
}
