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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import apache.rocketmq.v2.AckMessageResponse;
import apache.rocketmq.v2.AckMessageResultEntry;
import apache.rocketmq.v2.ChangeInvisibleDurationResponse;
import apache.rocketmq.v2.Code;
import apache.rocketmq.v2.Status;
import io.agentcore.managed.server.runtime.managed.RocketMqTurnReceipt;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.ConsumeResultSuspend;
import org.apache.rocketmq.client.java.hook.MessageInterceptor;
import org.apache.rocketmq.client.java.message.MessageViewImpl;
import org.apache.rocketmq.client.java.misc.ClientId;
import org.apache.rocketmq.client.java.rpc.RpcFuture;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.Futures;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.ListenableFuture;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.SettableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ManagedLitePushConsumerTest {
    MessageViewImpl view(String lite) {
        var v = mock(MessageViewImpl.class);
        when(v.getLiteTopic()).thenReturn(Optional.of(lite));
        return v;
    }

    @Test
    void receiptIsObservedOnlyAfterTheWholeSameTopicBatchIsSuspended() {
        var first = view("s1");
        var second = view("s1");
        var other = view("s2");
        var headRpc = SettableFuture.<Void>create();
        var tailRpc = SettableFuture.<Void>create();
        var pq = mock(ProcessQueue.class);
        when(pq.eraseFifoMessage(any(), any())).thenReturn(Futures.immediateVoidFuture());
        when(pq.eraseFifoMessage(any(), any()))
                .thenAnswer(
                        call ->
                                call.getArgument(0) == first
                                        ? headRpc
                                        : call.getArgument(0) == second
                                                ? tailRpc
                                                : Futures.immediateVoidFuture());
        var captured = new ArrayList<MessageViewImpl>();
        var callbacks = new ArrayList<MessageViewImpl>();
        var workers = Executors.newSingleThreadExecutor();
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            var service =
                    new ManagedLiteFifoConsumeService(
                            new ClientId(),
                            "brain",
                            v -> ConsumeResult.SUCCESS,
                            workers,
                            mock(MessageInterceptor.class),
                            scheduler,
                            false,
                            (v, d) -> {
                                assertEquals(Duration.ofSeconds(60), d);
                                captured.add((MessageViewImpl) v);
                            }) {
                        @Override
                        public ListenableFuture<ConsumeResult> consume(MessageViewImpl v) {
                            callbacks.add(v);
                            return Futures.immediateFuture(
                                    v == first
                                            ? ConsumeResultSuspend.of(Duration.ofSeconds(60))
                                            : ConsumeResult.SUCCESS);
                        }
                    };
            service.consume(pq, List.of(first, second, other));
            assertEquals(List.of(first), callbacks);
            headRpc.set(null);
            assertTrue(
                    captured.isEmpty(),
                    "Head-only completion would race a cached successor Suspend");
            assertFalse(callbacks.contains(second), "Cached successor must not run during Suspend");
            tailRpc.set(null);
            assertEquals(List.of(first), captured);
            assertEquals(List.of(first, other), callbacks);
        } finally {
            workers.shutdownNow();
            scheduler.shutdownNow();
        }
    }

    @Test
    void failedBatchSuspendDoesNotPublishAnAckCapability() {
        var first = view("s1");
        var second = view("s1");
        var pq = mock(ProcessQueue.class);
        when(pq.eraseFifoMessage(any(), any()))
                .thenReturn(
                        Futures.immediateVoidFuture(),
                        Futures.immediateFailedFuture(new IllegalStateException("Suspend failed")));
        var observed = new AtomicInteger();
        var workers = Executors.newSingleThreadExecutor();
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            var service =
                    new ManagedLiteFifoConsumeService(
                            new ClientId(),
                            "brain",
                            v -> ConsumeResult.SUCCESS,
                            workers,
                            mock(MessageInterceptor.class),
                            scheduler,
                            true,
                            (v, d) -> observed.incrementAndGet()) {
                        @Override
                        public ListenableFuture<ConsumeResult> consume(MessageViewImpl v) {
                            return Futures.immediateFuture(
                                    ConsumeResultSuspend.of(Duration.ofSeconds(60)));
                        }
                    };
            service.consume(pq, List.of(first, second));
            assertEquals(0, observed.get());
        } finally {
            workers.shutdownNow();
            scheduler.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"OK", "INVALID_RECEIPT_HANDLE", "INTERNAL_ERROR"})
    void acknowledgementChecksIndividualEntryStatus(String entry) {
        var client = mock(LitePushConsumerImpl.class);
        var receipt = mock(RocketMqTurnReceipt.class);
        var v = view("scope");
        when(receipt.restore()).thenReturn(v);
        when(receipt.messageId()).thenReturn("broker");
        var result =
                AckMessageResponse.newBuilder()
                        .setStatus(Status.newBuilder().setCode(Code.OK))
                        .addEntries(
                                AckMessageResultEntry.newBuilder()
                                        .setMessageId("broker")
                                        .setStatus(
                                                Status.newBuilder().setCode(Code.valueOf(entry))))
                        .build();
        when(client.changeInvisibleDuration(any(), any()))
                .thenReturn(
                        new RpcFuture<>(
                                null,
                                null,
                                Futures.immediateFuture(
                                        ChangeInvisibleDurationResponse.newBuilder()
                                                .setStatus(Status.newBuilder().setCode(Code.OK))
                                                .build())));
        when(client.ackMessage(v))
                .thenReturn(new RpcFuture<>(null, null, Futures.immediateFuture(result)));
        assertEquals(
                "OK".equals(entry)
                        ? ManagedLitePushConsumer.Ack.ACKNOWLEDGED
                        : "INVALID_RECEIPT_HANDLE".equals(entry)
                                ? ManagedLitePushConsumer.Ack.EXPIRED
                                : ManagedLitePushConsumer.Ack.RETRY,
                ManagedLitePushConsumer.acknowledge(client, receipt));
    }

    @Test
    void emptyAckEntriesDoNotConfirmRelease() {
        var client = mock(LitePushConsumerImpl.class);
        var receipt = mock(RocketMqTurnReceipt.class);
        var restored = view("scope");
        when(receipt.restore()).thenReturn(restored);
        when(client.changeInvisibleDuration(any(), any()))
                .thenReturn(
                        new RpcFuture<>(
                                null,
                                null,
                                Futures.immediateFuture(
                                        ChangeInvisibleDurationResponse.newBuilder()
                                                .setStatus(Status.newBuilder().setCode(Code.OK))
                                                .build())));
        when(client.ackMessage(any()))
                .thenReturn(
                        new RpcFuture<>(
                                null,
                                null,
                                Futures.immediateFuture(
                                        AckMessageResponse.newBuilder()
                                                .setStatus(Status.newBuilder().setCode(Code.OK))
                                                .build())));
        assertEquals(
                ManagedLitePushConsumer.Ack.RETRY,
                ManagedLitePushConsumer.acknowledge(client, receipt));
    }

    @ParameterizedTest
    @ValueSource(strings = {"INVALID_RECEIPT_HANDLE", "INTERNAL_ERROR"})
    void cannotAckUntilQueueWakeupIsConfirmed(String code) {
        var client = mock(LitePushConsumerImpl.class);
        var receipt = mock(RocketMqTurnReceipt.class);
        var restored = view("scope");
        when(receipt.restore()).thenReturn(restored);
        when(client.changeInvisibleDuration(any(), any()))
                .thenReturn(
                        new RpcFuture<>(
                                null,
                                null,
                                Futures.immediateFuture(
                                        ChangeInvisibleDurationResponse.newBuilder()
                                                .setStatus(
                                                        Status.newBuilder()
                                                                .setCode(Code.valueOf(code)))
                                                .build())));
        assertEquals(
                "INVALID_RECEIPT_HANDLE".equals(code)
                        ? ManagedLitePushConsumer.Ack.EXPIRED
                        : ManagedLitePushConsumer.Ack.RETRY,
                ManagedLitePushConsumer.acknowledge(client, receipt));
        verify(client, never()).ackMessage(any());
    }

    @Test
    void wakeupPrecedesAckAndUsesTheReturnedReceiptHandle() {
        var client = mock(LitePushConsumerImpl.class);
        var receipt = mock(RocketMqTurnReceipt.class);
        var restored = view("scope");
        when(receipt.restore()).thenReturn(restored);
        when(receipt.messageId()).thenReturn("broker");
        when(client.changeInvisibleDuration(any(), any()))
                .thenReturn(
                        new RpcFuture<>(
                                null,
                                null,
                                Futures.immediateFuture(
                                        ChangeInvisibleDurationResponse.newBuilder()
                                                .setStatus(Status.newBuilder().setCode(Code.OK))
                                                .setReceiptHandle("new-capability")
                                                .build())));
        when(client.ackMessage(any()))
                .thenReturn(
                        new RpcFuture<>(
                                null,
                                null,
                                Futures.immediateFuture(
                                        AckMessageResponse.newBuilder()
                                                .setStatus(Status.newBuilder().setCode(Code.OK))
                                                .addEntries(
                                                        AckMessageResultEntry.newBuilder()
                                                                .setMessageId("broker")
                                                                .setStatus(
                                                                        Status.newBuilder()
                                                                                .setCode(Code.OK)))
                                                .build())));
        assertEquals(
                ManagedLitePushConsumer.Ack.ACKNOWLEDGED,
                ManagedLitePushConsumer.acknowledge(client, receipt));
        var order = inOrder(client, restored);
        order.verify(client).changeInvisibleDuration(restored, Duration.ofMillis(50));
        order.verify(restored).setReceiptHandle("new-capability");
        order.verify(client).ackMessage(restored);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OK", "INVALID_RECEIPT_HANDLE", "INTERNAL_ERROR"})
    void suspendResponseRefreshesOnlyAConfirmedReceipt(String code) throws Exception {
        var view = view("scope");
        var response =
                ChangeInvisibleDurationResponse.newBuilder()
                        .setStatus(Status.newBuilder().setCode(Code.valueOf(code)))
                        .setReceiptHandle("suspended-capability")
                        .build();
        assertEquals(
                response,
                ManagedLitePushConsumer.trackReceipt(
                                view,
                                new RpcFuture<>(null, null, Futures.immediateFuture(response)))
                        .get());
        if ("OK".equals(code)) verify(view).setReceiptHandle("suspended-capability");
        else verify(view, never()).setReceiptHandle(any());
    }
}
