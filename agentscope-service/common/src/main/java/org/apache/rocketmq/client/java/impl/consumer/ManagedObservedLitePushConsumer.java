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
import apache.rocketmq.v2.ReceiveMessageRequest;
import io.agentcore.managed.server.runtime.managed.ManagedMqMetrics;
import io.agentcore.managed.server.runtime.managed.ManagedRuntimeLog;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.java.message.MessageViewImpl;
import org.apache.rocketmq.client.java.route.MessageQueueImpl;
import org.apache.rocketmq.client.java.rpc.RpcFuture;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.FutureCallback;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.Futures;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.ListenableFuture;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.MoreExecutors;
import org.slf4j.LoggerFactory;

/** SDK 5.2.2 observation bridge; does not change fetching, ACK ownership or FIFO semantics. */
public class ManagedObservedLitePushConsumer extends LitePushConsumerImpl {
    private final Map<MessageQueueImpl, ProcessQueue> observedQueues = new ConcurrentHashMap<>();
    private ManagedMqMetrics metrics;
    private ScheduledFuture<?> sampling;

    public ManagedObservedLitePushConsumer(LitePushConsumerBuilderImpl builder) {
        super(builder);
    }

    public void observe(ManagedMqMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    protected void startUp() throws Exception {
        super.startUp();
        if (metrics != null)
            sampling = getScheduler().scheduleWithFixedDelay(this::sample, 1, 1, TimeUnit.SECONDS);
    }

    @Override
    protected Optional<ProcessQueue> createProcessQueue(
            MessageQueueImpl queue, FilterExpression filter) {
        var result = super.createProcessQueue(queue, filter);
        result.ifPresent(value -> observedQueues.put(queue, value));
        return result;
    }

    @Override
    void dropProcessQueue(MessageQueueImpl queue) {
        super.dropProcessQueue(queue);
        observedQueues.remove(queue);
    }

    private void sample() {
        try {
            long count = 0, bytes = 0, full = 0;
            for (var queue : observedQueues.values()) {
                count += queue.getCachedMessageCount();
                bytes += queue.getCachedMessageBytes();
                if (queue.getCachedMessageCount() >= cacheMessageCountThresholdPerQueue()
                        || queue.getCachedMessageBytes() >= cacheMessageBytesThresholdPerQueue())
                    full++;
            }
            metrics.gauge("cache.messages", count);
            metrics.gauge("cache.bytes", bytes);
            metrics.gauge("cache.full_queues", full);
            metrics.count("cache.full_queue_samples", full);
            metrics.gauge("process_queues", getQueueSize());
            metrics.gauge("cache.messages_per_queue", cacheMessageCountThresholdPerQueue());
            metrics.gauge("receive.batch_limit", getSettings().getReceiveBatchSize());
            if (getConsumptionExecutor() instanceof ThreadPoolExecutor pool) {
                metrics.gauge("callback.threads_active", pool.getActiveCount());
                metrics.gauge("callback.queued", pool.getQueue().size());
            }
            metrics.logSnapshot(getClientId().toString());
        } catch (RuntimeException unavailable) {
            LoggerFactory.getLogger(getClass()).warn("Cannot sample MQ diagnostics", unavailable);
        }
    }

    @Override
    protected ListenableFuture<ReceiveMessageResult> receiveMessage(
            ReceiveMessageRequest request, MessageQueueImpl queue, Duration timeout) {
        if (metrics == null) return super.receiveMessage(request, queue, timeout);
        long start = System.nanoTime();
        metrics.count("receive.requests", 1);
        metrics.addGauge("receive.inflight", 1);
        metrics.gauge("receive.requested_batch", request.getBatchSize());
        ListenableFuture<ReceiveMessageResult> result;
        try {
            result = super.receiveMessage(request, queue, timeout);
        } catch (RuntimeException failure) {
            metrics.addGauge("receive.inflight", -1);
            metrics.receiveFailure(failure);
            metrics.duration("receive.rpc", System.nanoTime() - start);
            throw failure;
        }
        Futures.addCallback(
                result,
                new FutureCallback<ReceiveMessageResult>() {
                    public void onSuccess(ReceiveMessageResult response) {
                        metrics.addGauge("receive.inflight", -1);
                        metrics.duration("receive.rpc", System.nanoTime() - start);
                        metrics.count("receive.messages", response.getMessageViewImpls().size());
                        if (response.getMessageViewImpls().isEmpty())
                            metrics.count("receive.empty", 1);
                        for (var view : response.getMessageViewImpls()) {
                            metrics.millis(
                                    "born_to_receive",
                                    view.getDecodeTimestamp() - view.getBornTimestamp());
                            view.getTransportDeliveryTimestamp()
                                    .ifPresent(
                                            delivery -> {
                                                metrics.millis(
                                                        "born_to_delivery",
                                                        delivery - view.getBornTimestamp());
                                                metrics.millis(
                                                        "delivery_to_receive",
                                                        view.getDecodeTimestamp() - delivery);
                                            });
                            trace("mq.received", view);
                        }
                    }

                    public void onFailure(Throwable failure) {
                        metrics.addGauge("receive.inflight", -1);
                        metrics.receiveFailure(failure);
                        metrics.duration("receive.rpc", System.nanoTime() - start);
                    }
                },
                MoreExecutors.directExecutor());
        return result;
    }

    @Override
    protected RpcFuture<AckMessageRequest, AckMessageResponse> ackMessage(MessageViewImpl view) {
        var result = super.ackMessage(view);
        if (metrics != null) {
            long start = System.nanoTime();
            metrics.addGauge("ack.inflight", 1);
            Futures.addCallback(
                    result,
                    new FutureCallback<AckMessageResponse>() {
                        public void onSuccess(AckMessageResponse response) {
                            metrics.addGauge("ack.inflight", -1);
                            metrics.duration("ack.rpc", System.nanoTime() - start);
                            if (response.getStatus().getCode() == Code.OK
                                    && response.getEntriesCount() == 1
                                    && response.getEntries(0).getStatus().getCode() == Code.OK) {
                                metrics.count("ack.success", 1);
                                metrics.millis(
                                        "receive_to_ack",
                                        System.currentTimeMillis() - view.getDecodeTimestamp());
                                trace("mq.acked", view);
                            } else metrics.count("ack.failures", 1);
                        }

                        public void onFailure(Throwable failure) {
                            metrics.addGauge("ack.inflight", -1);
                            metrics.count("ack.failures", 1);
                            metrics.duration("ack.rpc", System.nanoTime() - start);
                        }
                    },
                    MoreExecutors.directExecutor());
        }
        return result;
    }

    private void trace(String event, MessageViewImpl view) {
        if (!ManagedMqMetrics.traceMessage(view.getMessageId().toString())) return;
        ManagedRuntimeLog.info(
                LoggerFactory.getLogger(getClass()),
                event,
                null,
                null,
                "consumer_group",
                getConsumerGroup(),
                "client_id",
                getClientId().toString(),
                "topic",
                view.getTopic(),
                "broker_message_id",
                view.getMessageId().toString(),
                "born_at",
                view.getBornTimestamp(),
                "received_at",
                view.getDecodeTimestamp(),
                "delivered_at",
                view.getTransportDeliveryTimestamp().orElse(null),
                "attempt",
                view.getDeliveryAttempt());
    }

    @Override
    public void close() {
        if (sampling != null) sampling.cancel(false);
        try {
            super.close();
        } finally {
            observedQueues.clear();
        }
    }
}
