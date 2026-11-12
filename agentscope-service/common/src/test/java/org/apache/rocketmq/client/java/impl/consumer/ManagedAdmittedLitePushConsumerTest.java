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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import apache.rocketmq.v2.AckMessageRequest;
import apache.rocketmq.v2.AckMessageResponse;
import apache.rocketmq.v2.AckMessageResultEntry;
import apache.rocketmq.v2.Code;
import apache.rocketmq.v2.ForwardMessageToDeadLetterQueueRequest;
import apache.rocketmq.v2.ForwardMessageToDeadLetterQueueResponse;
import apache.rocketmq.v2.ReceiveMessageRequest;
import apache.rocketmq.v2.Status;
import io.agentcore.managed.server.runtime.managed.ManagedReceiveCapacity;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.java.exception.ForbiddenException;
import org.apache.rocketmq.client.java.message.MessageViewImpl;
import org.apache.rocketmq.client.java.route.MessageQueueImpl;
import org.apache.rocketmq.client.java.rpc.RpcFuture;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.ListenableFuture;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.SettableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class ManagedAdmittedLitePushConsumerTest {
    static LitePushConsumerBuilderImpl builder() {
        return (LitePushConsumerBuilderImpl)
                ClientServiceProvider.loadService()
                        .newLitePushConsumerBuilder()
                        .setClientConfiguration(
                                ClientConfiguration.newBuilder()
                                        .setEndpoints("localhost:19981")
                                        .build())
                        .setConsumerGroup("test")
                        .bindTopic("test")
                        .setMessageListener(v -> ConsumeResult.SUCCESS);
    }

    static final class Client extends ManagedAdmittedLitePushConsumer {
        final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        final BlockingQueue<Duration> timeouts = new LinkedBlockingQueue<>();
        final BlockingQueue<ReceiveMessageRequest> requests = new LinkedBlockingQueue<>();
        final BlockingQueue<SettableFuture<ReceiveMessageResult>> responses =
                new LinkedBlockingQueue<>();
        final BlockingQueue<SettableFuture<AckMessageResponse>> acks = new LinkedBlockingQueue<>();

        Client(ManagedReceiveCapacity capacity) {
            super(builder(), capacity);
        }

        @Override
        public ScheduledExecutorService getScheduler() {
            return timer;
        }

        @Override
        protected ListenableFuture<ReceiveMessageResult> receiveReserved(
                ReceiveMessageRequest r, MessageQueueImpl q, Duration d) {
            var response = SettableFuture.<ReceiveMessageResult>create();
            timeouts.add(d);
            requests.add(r);
            responses.add(response);
            return response;
        }

        @Override
        protected RpcFuture<AckMessageRequest, AckMessageResponse> acknowledgeReserved(
                MessageViewImpl view) {
            var response = SettableFuture.<AckMessageResponse>create();
            acks.add(response);
            return new RpcFuture<>(null, AckMessageRequest.getDefaultInstance(), response);
        }

        final BlockingQueue<SettableFuture<ForwardMessageToDeadLetterQueueResponse>> deadLetters =
                new LinkedBlockingQueue<>();

        @Override
        protected RpcFuture<
                        ForwardMessageToDeadLetterQueueRequest,
                        ForwardMessageToDeadLetterQueueResponse>
                forwardRejected(MessageViewImpl view) {
            var response = SettableFuture.<ForwardMessageToDeadLetterQueueResponse>create();
            deadLetters.add(response);
            return new RpcFuture<>(
                    null, ForwardMessageToDeadLetterQueueRequest.getDefaultInstance(), response);
        }

        ListenableFuture<ReceiveMessageResult> receive(int size) {
            return receive(size, mock(MessageQueueImpl.class));
        }

        ListenableFuture<ReceiveMessageResult> receive(int size, MessageQueueImpl queue) {
            return receiveMessage(
                    ReceiveMessageRequest.newBuilder()
                            .setBatchSize(size)
                            .setLongPollingTimeout(
                                    org.apache.rocketmq.shaded.com.google.protobuf.Duration
                                            .newBuilder()
                                            .setSeconds(15))
                            .build(),
                    queue,
                    Duration.ofSeconds(15));
        }

        @Override
        public void close() {
            super.close();
            timer.shutdownNow();
            getConsumptionExecutor().shutdownNow();
        }
    }

    @Test
    void coldReceiveReservesOneCreditAndAckPreservesExecutionOwnership() throws Exception {
        var executions = new Semaphore(0);
        var capacity = new ManagedReceiveCapacity(executions, 2);
        try (var client = new Client(capacity)) {
            var pending = client.receive(32);
            assertNull(client.requests.poll(100, TimeUnit.MILLISECONDS));
            executions.release(2);
            var request = client.requests.poll(2, TimeUnit.SECONDS);
            assertEquals(1, request.getBatchSize());
            assertEquals(15, request.getLongPollingTimeout().getSeconds());
            assertEquals(Duration.ofSeconds(15), client.timeouts.take());
            assertEquals(1, executions.availablePermits());
            var view = mock(MessageViewImpl.class);
            client.responses.take().set(new ReceiveMessageResult(null, List.of(view)));
            pending.get(2, TimeUnit.SECONDS);
            assertEquals(1, executions.availablePermits());
            var release = client.handoff(view).takeExecution();
            client.ackMessage(view);
            client.acks.take().set(ok());
            assertEquals(
                    1,
                    executions.availablePermits(),
                    "ACK must not return an active execution credit");
            release.run();
            assertEquals(2, executions.availablePermits());
            assertEquals(2, capacity.availableHandoffs());
        }
    }

    @Test
    void idleInitialAndResumeQueuesCanAllPollTheSharedBudget() throws Exception {
        var capacity = new ManagedReceiveCapacity(new Semaphore(1024), 64);
        try (var initial = new Client(capacity);
                var resume = new Client(capacity)) {
            for (int i = 0; i < 4; i++) initial.receive(32);
            for (int i = 0; i < 4; i++) resume.receive(32);
            for (int i = 0; i < 4; i++) {
                assertEquals(1, initial.requests.poll(1, TimeUnit.SECONDS).getBatchSize());
                assertEquals(1, resume.requests.poll(1, TimeUnit.SECONDS).getBatchSize());
            }
            assertEquals(8, capacity.activeReceivers());
            assertEquals(56, capacity.availableHandoffs());
        }
        assertEquals(0, capacity.activeReceivers());
        assertEquals(64, capacity.availableHandoffs());
        assertEquals(1024, capacity.availableExecutions());
    }

    @Test
    void busyQueueGrowsBatchesAndPartialResponseReleasesUnusedCredits() throws Exception {
        var capacity = new ManagedReceiveCapacity(new Semaphore(4), 4);
        var queue = mock(MessageQueueImpl.class);
        try (var client = new Client(capacity)) {
            var cold = client.receive(32, queue);
            assertEquals(1, client.requests.take().getBatchSize());
            var first = mock(MessageViewImpl.class);
            client.responses.take().set(new ReceiveMessageResult(null, List.of(first)));
            cold.get();
            client.ackMessage(first);
            client.acks.take().set(ok());
            var warm = client.receive(32, queue);
            assertEquals(2, client.requests.take().getBatchSize());
            assertEquals(2, capacity.availableExecutions());
            var second = mock(MessageViewImpl.class);
            client.responses.take().set(new ReceiveMessageResult(null, List.of(second)));
            warm.get();
            assertEquals(3, capacity.availableExecutions());
            client.ackMessage(second);
            client.acks.take().set(ok());
            assertEquals(4, capacity.availableExecutions());
        }
    }

    @Test
    void failedOrPartialAckCannotOpenGateAndConfirmedRetryCan() throws Exception {
        var capacity = new ManagedReceiveCapacity(new Semaphore(1), 1);
        try (var client = new Client(capacity)) {
            var future = client.receive(32);
            var view = mock(MessageViewImpl.class);
            client.responses.take().set(new ReceiveMessageResult(null, List.of(view)));
            future.get();
            var handoff = client.handoff(view);
            client.ackMessage(view);
            client.acks
                    .take()
                    .set(
                            AckMessageResponse.newBuilder()
                                    .setStatus(Status.newBuilder().setCode(Code.OK))
                                    .build());
            assertThrows(TimeoutException.class, () -> handoff.await(Duration.ofMillis(10)));
            assertEquals(0, capacity.availableHandoffs());
            client.ackMessage(view);
            client.acks.take().setException(new IllegalStateException("lost response"));
            assertThrows(TimeoutException.class, () -> handoff.await(Duration.ofMillis(10)));
            client.ackMessage(view);
            client.acks.take().set(ok());
            handoff.await(Duration.ofSeconds(1));
            assertEquals(1, capacity.availableExecutions());
            assertEquals(1, capacity.availableHandoffs());
        }
    }

    @Test
    void receiveFailureEmptyBatchAndShutdownReturnReservationsExactlyOnce() throws Exception {
        var capacity = new ManagedReceiveCapacity(new Semaphore(2), 2);
        var client = new Client(capacity);
        try {
            var failed = client.receive(32);
            client.responses
                    .take()
                    .setException(
                            new ForbiddenException(40300, "request", "rejected before delivery"));
            assertThrows(ExecutionException.class, () -> failed.get());
            assertEquals(2, capacity.availableExecutions());
            var empty = client.receive(32);
            client.responses.take().set(new ReceiveMessageResult(null, List.of()));
            empty.get();
            assertEquals(2, capacity.availableExecutions());
            var closing = client.receive(32);
            var rpc = client.responses.take();
            client.close();
            assertTrue(closing.isCancelled());
            rpc.set(new ReceiveMessageResult(null, List.of(mock(MessageViewImpl.class))));
            assertEquals(2, capacity.availableExecutions());
            assertEquals(2, capacity.availableHandoffs());
        } finally {
            client.close();
        }
    }

    @Test
    void droppedQueueCannotIssueAReceiveAfterWaitingForCapacity() throws Exception {
        var executions = new Semaphore(0);
        var capacity = new ManagedReceiveCapacity(executions, 1);
        try (var client = new Client(capacity)) {
            var queue = mock(MessageQueueImpl.class);
            var future =
                    client.receiveMessage(
                            ReceiveMessageRequest.newBuilder().setBatchSize(32).build(),
                            queue,
                            Duration.ofSeconds(1));
            client.dropProcessQueue(queue);
            executions.release();
            assertTrue(future.isCancelled());
            assertNull(client.requests.poll(100, TimeUnit.MILLISECONDS));
            assertEquals(1, executions.availablePermits());
        }
    }

    @Test
    void invalidReceiptOrConfirmedDeadLetterReleasesBudgetWithoutOpeningPreparation()
            throws Exception {
        var capacity = new ManagedReceiveCapacity(new Semaphore(1), 1);
        try (var client = new Client(capacity)) {
            var received = client.receive(1);
            var invalid = mock(MessageViewImpl.class);
            client.responses.take().set(new ReceiveMessageResult(null, List.of(invalid)));
            received.get();
            var gate = client.handoff(invalid);
            client.ackMessage(invalid);
            client.acks
                    .take()
                    .set(
                            AckMessageResponse.newBuilder()
                                    .setStatus(
                                            Status.newBuilder()
                                                    .setCode(Code.INVALID_RECEIPT_HANDLE))
                                    .build());
            assertThrows(ExecutionException.class, () -> gate.await(Duration.ofMillis(1)));
            assertEquals(1, capacity.availableHandoffs());
            received = client.receive(1);
            var rejected = mock(MessageViewImpl.class);
            client.responses.take().set(new ReceiveMessageResult(null, List.of(rejected)));
            received.get();
            var rejectedGate = client.handoff(rejected);
            client.forwardMessageToDeadLetterQueue(rejected);
            client.deadLetters.take().setException(new IllegalStateException("uncertain DLQ"));
            assertEquals(0, capacity.availableHandoffs());
            client.forwardMessageToDeadLetterQueue(rejected);
            client.deadLetters
                    .take()
                    .set(
                            ForwardMessageToDeadLetterQueueResponse.newBuilder()
                                    .setStatus(Status.newBuilder().setCode(Code.OK))
                                    .build());
            assertThrows(ExecutionException.class, () -> rejectedGate.await(Duration.ofMillis(1)));
            assertEquals(1, capacity.availableHandoffs());
            assertEquals(1, capacity.availableExecutions());
        }
    }

    @Test
    void lostReceiveResponseRetainsItsBudgetAndRetriesTheSameAttemptAndBatch() throws Exception {
        var capacity = new ManagedReceiveCapacity(new Semaphore(2), 2);
        try (var client = new Client(capacity)) {
            var request =
                    ReceiveMessageRequest.newBuilder()
                            .setBatchSize(32)
                            .setAttemptId("stable-attempt")
                            .build();
            var result =
                    client.receiveMessage(
                            request, mock(MessageQueueImpl.class), Duration.ofSeconds(1));
            var first = client.requests.take();
            client.responses
                    .take()
                    .setException(new IllegalStateException("response lost after allocation"));
            assertEquals(1, capacity.availableExecutions());
            assertEquals(1, capacity.availableHandoffs());
            assertFalse(result.isDone());
            var retried = client.requests.poll(2, TimeUnit.SECONDS);
            assertEquals(first, retried);
            // A rejection on retry cannot erase the uncertain original allocation.
            client.responses
                    .take()
                    .setException(new ForbiddenException(40300, "retry", "inflight quota"));
            assertEquals(1, capacity.availableHandoffs());
            assertEquals(first, client.requests.poll(3, TimeUnit.SECONDS));
            var view = mock(MessageViewImpl.class);
            client.responses.take().set(new ReceiveMessageResult(null, List.of(view)));
            assertEquals(List.of(view), result.get().getMessageViewImpls());
            assertEquals(1, capacity.availableExecutions());
            client.ackMessage(view);
            client.acks.take().set(ok());
            assertEquals(2, capacity.availableExecutions());
            assertEquals(2, capacity.availableHandoffs());
        }
    }

    static AckMessageResponse ok() {
        return AckMessageResponse.newBuilder()
                .setStatus(Status.newBuilder().setCode(Code.OK))
                .addEntries(
                        AckMessageResultEntry.newBuilder()
                                .setStatus(Status.newBuilder().setCode(Code.OK)))
                .build();
    }
}
