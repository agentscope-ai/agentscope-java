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

import apache.rocketmq.v2.AckMessageRequest;
import apache.rocketmq.v2.AckMessageResponse;
import apache.rocketmq.v2.Code;
import apache.rocketmq.v2.ForwardMessageToDeadLetterQueueRequest;
import apache.rocketmq.v2.ForwardMessageToDeadLetterQueueResponse;
import apache.rocketmq.v2.ReceiveMessageRequest;
import io.agentcore.managed.server.runtime.managed.ManagedDeliveryHandoff;
import io.agentcore.managed.server.runtime.managed.ManagedMqMetrics;
import io.agentcore.managed.server.runtime.managed.ManagedReceiveCapacity;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.rocketmq.client.java.exception.BadRequestException;
import org.apache.rocketmq.client.java.exception.ForbiddenException;
import org.apache.rocketmq.client.java.exception.TooManyRequestsException;
import org.apache.rocketmq.client.java.message.MessageViewImpl;
import org.apache.rocketmq.client.java.route.MessageQueueImpl;
import org.apache.rocketmq.client.java.rpc.RpcFuture;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.FutureCallback;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.Futures;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.ListenableFuture;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.MoreExecutors;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.SettableFuture;

/** SDK 5.2.2 bridge: reserve before receive, then hand execution off only after confirmed ACK. */
public class ManagedAdmittedLitePushConsumer extends ManagedRetryingLitePushConsumer {
    private record Receipt(ManagedDeliveryHandoff handoff, long received) {}

    private final ManagedReceiveCapacity capacity;
    private final Map<MessageViewImpl, Receipt> receipts = new ConcurrentHashMap<>();
    private final Map<SettableFuture<ReceiveMessageResult>, MessageQueueImpl> waiting =
            new ConcurrentHashMap<>();
    private final Set<SettableFuture<ReceiveMessageResult>> receiving =
            ConcurrentHashMap.newKeySet();
    private final Map<SettableFuture<ReceiveMessageResult>, Long> uncertainReceives =
            new ConcurrentHashMap<>();
    private final Set<ManagedDeliveryHandoff> reservations = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private ManagedMqMetrics metrics;
    private final Map<MessageQueueImpl, Long> emptyUntil = new ConcurrentHashMap<>();
    private final Map<MessageQueueImpl, Integer> batchDemand = new ConcurrentHashMap<>();

    public ManagedAdmittedLitePushConsumer(
            LitePushConsumerBuilderImpl builder, ManagedReceiveCapacity capacity) {
        super(builder);
        this.capacity = capacity;
    }

    @Override
    public void observe(ManagedMqMetrics metrics) {
        super.observe(metrics);
        this.metrics = metrics;
    }

    public ManagedDeliveryHandoff handoff(MessageViewImpl view) {
        var receipt = receipts.get(view);
        if (receipt == null) throw new IllegalStateException("Message has no receive reservation");
        return receipt.handoff();
    }

    @Override
    protected ListenableFuture<ReceiveMessageResult> receiveMessage(
            ReceiveMessageRequest request, MessageQueueImpl queue, Duration timeout) {
        var result = SettableFuture.<ReceiveMessageResult>create();
        var admission = capacity.openReceive();
        waiting.put(result, queue);
        result.addListener(
                () -> {
                    waiting.remove(result);
                    receiving.remove(result);
                    uncertainReceives.remove(result);
                    admission.close();
                },
                MoreExecutors.directExecutor());
        receiveWhenAvailable(request, queue, timeout, result, admission);
        return result;
    }

    private void receiveWhenAvailable(
            ReceiveMessageRequest request,
            MessageQueueImpl queue,
            Duration timeout,
            SettableFuture<ReceiveMessageResult> result,
            ManagedReceiveCapacity.ReceiveRequest admission) {
        if (result.isDone()) return;
        if (stopped.get()) {
            result.cancel(false);
            return;
        }
        long oldest = oldestUnconfirmed();
        if (metrics != null) {
            metrics.gauge("admission.execution_available", capacity.availableExecutions());
            metrics.gauge("admission.execution_limit", capacity.executionLimit());
            metrics.gauge("admission.execution_active", capacity.activeExecutions());
            metrics.gauge("admission.handoff_available", capacity.availableHandoffs());
            metrics.gauge("admission.receipts", receipts.size());
            metrics.gauge("admission.reservations", reservations.size());
            metrics.gauge("admission.oldest_receipt_ms", TimeUnit.NANOSECONDS.toMillis(oldest));
            metrics.gauge("admission.waiting_receives", waiting.size());
            metrics.gauge("admission.uncertain_receives", uncertainReceives.size());
            metrics.gauge("admission.shared_receivers", capacity.activeReceivers());
            metrics.gauge("admission.lane_executions", capacity.laneExecutions());
            metrics.gauge("admission.lane_waiting", capacity.waitingReceives());
        }
        // An uncertain ACK or unavailable claim store must not silently accumulate more receipts.
        List<ManagedDeliveryHandoff> batch;
        if (oldest > Duration.ofSeconds(30).toNanos()
                || System.nanoTime() < emptyUntil.getOrDefault(queue, 0L)) {
            admission.pause();
            batch = List.of();
        } else {
            batch =
                    admission.reserve(
                            Math.min(request.getBatchSize(), batchDemand.getOrDefault(queue, 1)));
        }
        if (batch.isEmpty()) {
            try {
                getScheduler()
                        .schedule(
                                () ->
                                        receiveWhenAvailable(
                                                request, queue, timeout, result, admission),
                                50,
                                TimeUnit.MILLISECONDS);
            } catch (RuntimeException closed) {
                result.setException(closed);
            }
            return;
        }
        reservations.addAll(batch);
        synchronized (waiting) {
            if (stopped.get() || result.isCancelled()) {
                release(batch);
                result.cancel(false);
                return;
            }
            receiving.add(result);
        }
        var reservedRequest = request.toBuilder().setBatchSize(batch.size()).build();
        receiveBatch(reservedRequest, queue, timeout, batch, result, 0);
    }

    private long oldestUnconfirmed() {
        long now = System.nanoTime();
        return Math.max(
                receipts.values().stream().mapToLong(r -> now - r.received()).max().orElse(0),
                uncertainReceives.values().stream()
                        .mapToLong(start -> now - start)
                        .max()
                        .orElse(0));
    }

    private void receiveBatch(
            ReceiveMessageRequest request,
            MessageQueueImpl queue,
            Duration timeout,
            List<ManagedDeliveryHandoff> batch,
            SettableFuture<ReceiveMessageResult> result,
            int attempt) {
        if (stopped.get() || result.isDone()) {
            release(batch);
            result.cancel(false);
            return;
        }
        try {
            var rpc = receiveReserved(request, queue, timeout);
            Futures.addCallback(
                    rpc,
                    new FutureCallback<ReceiveMessageResult>() {
                        public void onSuccess(ReceiveMessageResult response) {
                            var views = response.getMessageViewImpls();
                            if (views.isEmpty())
                                emptyUntil.put(
                                        queue,
                                        System.nanoTime() + Duration.ofMillis(500).toNanos());
                            if (views.size() > batch.size()) {
                                // Protocol violation: don't deliver unbudgeted commands or issue
                                // more receives.
                                stopped.set(true);
                                release(batch);
                                result.setException(
                                        new IllegalStateException(
                                                "Broker exceeded reserved batch"));
                                return;
                            }
                            // Empty/cold queues need one credit, not a whole requested batch.
                            // Busy queues grow only after filling a batch, within their shared
                            // receive budget. An idle READY poll must not starve TURN_RESUMES.
                            batchDemand.put(
                                    queue,
                                    views.size() == batch.size()
                                            ? Math.min(32, batch.size() * 2)
                                            : Math.max(1, views.size()));
                            if (stopped.get() || result.isCancelled()) {
                                release(batch);
                                result.cancel(false);
                                return;
                            }
                            for (int i = 0; i < views.size(); i++)
                                receipts.put(
                                        views.get(i), new Receipt(batch.get(i), System.nanoTime()));
                            release(batch.subList(views.size(), batch.size()));
                            if (stopped.get()) {
                                for (var view : views) releaseReceipt(view, false);
                                result.cancel(false);
                            } else result.set(response);
                        }

                        public void onFailure(Throwable failure) {
                            receiveFailed(request, queue, timeout, batch, result, attempt, failure);
                        }
                    },
                    MoreExecutors.directExecutor());
        } catch (RuntimeException failure) {
            receiveFailed(request, queue, timeout, batch, result, attempt, failure);
        }
    }

    private void receiveFailed(
            ReceiveMessageRequest request,
            MessageQueueImpl queue,
            Duration timeout,
            List<ManagedDeliveryHandoff> batch,
            SettableFuture<ReceiveMessageResult> result,
            int attempt,
            Throwable failure) {
        if (stopped.get() || result.isDone()) {
            release(batch);
            result.cancel(false);
            return;
        }
        if (attempt == 0
                && (failure instanceof ForbiddenException
                        || (failure instanceof TooManyRequestsException
                                && failure.getCause() == null)
                        || failure instanceof BadRequestException)) {
            release(batch);
            result.setException(failure);
            return;
        }
        // A response can be lost after the broker allocated receipts. Preserve both the original
        // attempt ID and batch budget until a successful replay or client shutdown; a subsequent
        // quota rejection does not prove the earlier receive was empty.
        uncertainReceives.putIfAbsent(result, System.nanoTime());
        if (metrics != null) {
            metrics.count("admission.receive_uncertain_retry", 1);
            metrics.gauge("admission.uncertain_receives", uncertainReceives.size());
            metrics.gauge(
                    "admission.oldest_receipt_ms",
                    TimeUnit.NANOSECONDS.toMillis(oldestUnconfirmed()));
        }
        try {
            long delay = Math.min(10_000, 500L << Math.min(attempt, 5));
            getScheduler()
                    .schedule(
                            () -> receiveBatch(request, queue, timeout, batch, result, attempt + 1),
                            delay,
                            TimeUnit.MILLISECONDS);
        } catch (RuntimeException unavailable) {
            stopped.set(true); // Fail closed: no new receives against an unaccounted batch.
            result.setException(unavailable);
        }
    }

    /** Separate seam for deterministic SDK tests; the actual RPC always follows reservation. */
    protected ListenableFuture<ReceiveMessageResult> receiveReserved(
            ReceiveMessageRequest request, MessageQueueImpl queue, Duration timeout) {
        return super.receiveMessage(request, queue, timeout);
    }

    private void release(List<ManagedDeliveryHandoff> batch) {
        for (var permit : batch) {
            reservations.remove(permit);
            permit.close();
        }
    }

    @Override
    protected RpcFuture<AckMessageRequest, AckMessageResponse> ackMessage(MessageViewImpl view) {
        var result = acknowledgeReserved(view);
        Futures.addCallback(
                result,
                new FutureCallback<AckMessageResponse>() {
                    public void onSuccess(AckMessageResponse response) {
                        if (confirmed(response)) releaseReceipt(view, true);
                        else if (response.getStatus().getCode() == Code.INVALID_RECEIPT_HANDLE
                                || response.getEntriesList().stream()
                                        .anyMatch(
                                                e ->
                                                        e.getStatus().getCode()
                                                                == Code.INVALID_RECEIPT_HANDLE)) {
                            // The broker says this receipt is no longer usable. Do not open the
                            // preparation gate; the durable claim/outbox still owns recovery.
                            if (metrics != null) metrics.count("admission.invalid_receipt", 1);
                            releaseReceipt(view, false);
                        }
                        // Failures remain owned by SDK ACK retry. No preparation or capacity
                        // release.
                    }

                    public void onFailure(Throwable failure) {
                        /* SDK retries; retain the reservation. */
                    }
                },
                MoreExecutors.directExecutor());
        return result;
    }

    protected RpcFuture<AckMessageRequest, AckMessageResponse> acknowledgeReserved(
            MessageViewImpl view) {
        return super.ackMessage(view);
    }

    static boolean confirmed(AckMessageResponse response) {
        return response.getStatus().getCode() == Code.OK
                && response.getEntriesCount() == 1
                && response.getEntries(0).getStatus().getCode() == Code.OK;
    }

    void releaseReceipt(MessageViewImpl view, boolean confirmed) {
        var receipt = receipts.remove(view);
        if (receipt == null) return;
        reservations.remove(receipt.handoff());
        if (confirmed) receipt.handoff().confirm();
        else receipt.handoff().close();
    }

    @Override
    public RpcFuture<
                    ForwardMessageToDeadLetterQueueRequest, ForwardMessageToDeadLetterQueueResponse>
            forwardMessageToDeadLetterQueue(MessageViewImpl view) {
        var result = forwardRejected(view);
        Futures.addCallback(
                result,
                new FutureCallback<ForwardMessageToDeadLetterQueueResponse>() {
                    public void onSuccess(ForwardMessageToDeadLetterQueueResponse response) {
                        if (response.getStatus().getCode() == Code.OK) releaseReceipt(view, false);
                    }

                    public void onFailure(Throwable failure) {
                        /* Retain until confirmed DLQ handoff. */
                    }
                },
                MoreExecutors.directExecutor());
        return result;
    }

    protected RpcFuture<
                    ForwardMessageToDeadLetterQueueRequest, ForwardMessageToDeadLetterQueueResponse>
            forwardRejected(MessageViewImpl view) {
        return super.forwardMessageToDeadLetterQueue(view);
    }

    @Override
    void dropProcessQueue(MessageQueueImpl queue) {
        synchronized (waiting) {
            waiting.forEach(
                    (future, owner) -> {
                        if (queue.equals(owner) && !receiving.contains(future))
                            future.cancel(false);
                    });
        }
        emptyUntil.remove(queue);
        batchDemand.remove(queue);
        super.dropProcessQueue(queue);
    }

    @Override
    public void close() {
        stopped.set(true);
        waiting.keySet().forEach(f -> f.cancel(false));
        reservations.forEach(ManagedDeliveryHandoff::close);
        reservations.clear();
        receipts.clear();
        emptyUntil.clear();
        batchDemand.clear();
        super.close();
    }
}
