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
package io.agentcore.managed.server.runtime.managed;

import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Channel;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Listener;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Message;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.ConsumeResultSuspend;
import org.apache.rocketmq.client.apis.consumer.LitePushConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.java.impl.consumer.LitePushConsumerBuilderImpl;
import org.apache.rocketmq.client.java.impl.consumer.ManagedAdmittedLitePushConsumer;
import org.apache.rocketmq.client.java.impl.consumer.ManagedLitePushConsumer;
import org.apache.rocketmq.client.java.impl.consumer.ManagedLitePushConsumer.Ack;
import org.apache.rocketmq.client.java.impl.consumer.ManagedObservedLitePushConsumer;
import org.apache.rocketmq.client.java.message.MessageViewImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

/** SDK-owned active callbacks, with an optional durable ACK relay for suspended Turn heads. */
public final class RocketMqManagedPushConsumer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RocketMqManagedPushConsumer.class);
    private final ClientConfiguration configuration;
    private final Channel channel;
    private final String topic;
    private final String group;
    private final int callbackThreads;
    private final int cacheMessages;
    private final int cacheBytes;
    private final Listener handler;
    private final BiConsumer<ManagedPushDelivery, Exception> errors;
    private final String consumerId = UUID.randomUUID().toString();
    private final ConcurrentHashMap<String, ManagedPushDelivery> active = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile LitePushConsumer consumer;
    private final BiConsumer<MessageView, Duration> suspended;
    private final ExecutorService receiptWriter;
    private final ManagedMqMetrics metrics;
    private final ManagedResumeBackoff receiptFailures = new ManagedResumeBackoff();

    public RocketMqManagedPushConsumer(
            ClientConfiguration configuration,
            Channel channel,
            String topic,
            String group,
            int callbackThreads,
            int cacheMessages,
            int cacheBytes,
            Listener handler,
            BiConsumer<ManagedPushDelivery, Exception> errors) {
        this(
                configuration,
                channel,
                topic,
                group,
                callbackThreads,
                cacheMessages,
                cacheBytes,
                handler,
                errors,
                null);
    }

    public RocketMqManagedPushConsumer(
            ClientConfiguration configuration,
            Channel channel,
            String topic,
            String group,
            int callbackThreads,
            int cacheMessages,
            int cacheBytes,
            Listener handler,
            BiConsumer<ManagedPushDelivery, Exception> errors,
            BiConsumer<MessageView, Duration> suspended) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.channel = Objects.requireNonNull(channel, "channel");
        ManagedScope.requireText(topic, "parent Topic");
        ManagedScope.requireText(group, "consumer group");
        if (callbackThreads < 1 || cacheMessages < 1 || cacheBytes < 1)
            throw new IllegalArgumentException("Push consumption capacities must be positive");
        this.topic = topic;
        this.group = group;
        this.callbackThreads = callbackThreads;
        this.cacheMessages = cacheMessages;
        this.cacheBytes = cacheBytes;
        this.handler = Objects.requireNonNull(handler, "handler");
        this.errors = Objects.requireNonNull(errors, "errors");
        this.suspended = suspended;
        this.metrics = new ManagedMqMetrics(channel.name(), topic, group);
        metrics.gauge("cache.limit_messages", cacheMessages);
        metrics.gauge("cache.limit_bytes", cacheBytes);
        metrics.gauge("callback.limit", callbackThreads);
        this.receiptWriter =
                suspended == null
                        ? null
                        : new ThreadPoolExecutor(
                                1,
                                1,
                                0,
                                TimeUnit.MILLISECONDS,
                                new ArrayBlockingQueue<>(cacheMessages));
    }

    private ManagedStableRouting routing;

    public void routing(ManagedStableRouting routing) {
        if (consumer != null) throw new IllegalStateException("Consumer already started");
        this.routing = routing;
    }

    /** Register this object with its owner before start: the SDK may deliver during build(). */
    public synchronized void start() {
        if (closed.get()) throw new IllegalStateException("Push consumer is closed");
        if (consumer != null) throw new IllegalStateException("Push consumer already started");
        try {
            var builder =
                    ClientServiceProvider.loadService()
                            .newLitePushConsumerBuilder()
                            .setClientConfiguration(configuration)
                            .bindTopic(topic)
                            .setConsumerGroup(group)
                            .setConsumptionThreadCount(callbackThreads)
                            .setMaxCacheMessageCount(cacheMessages)
                            .setMaxCacheMessageSizeInBytes(cacheBytes)
                            .setEnableFifoConsumeAccelerator(routing == null)
                            .setMessageListener(this::consume);
            if (channel == Channel.TURN_RESUMES || handler.receiveCapacity(channel) != null) {
                if (!(builder instanceof LitePushConsumerBuilderImpl nativeBuilder))
                    throw new ManagedTransportException(
                            "Unsupported LitePush SDK for Resume retries");
                var capacity = handler.receiveCapacity(channel);
                if (capacity == null)
                    capacity =
                            new ManagedReceiveCapacity(
                                    new Semaphore(callbackThreads), Math.min(callbackThreads, 64));
                var managed = new ManagedAdmittedLitePushConsumer(nativeBuilder, capacity);
                consumer = managed;
                managed.observe(metrics);
                managed.startAsync().awaitRunning();
            } else if (suspended == null) {
                if (!(builder instanceof LitePushConsumerBuilderImpl nativeBuilder))
                    throw new ManagedTransportException("Unsupported LitePush SDK for metrics");
                var managed = new ManagedObservedLitePushConsumer(nativeBuilder);
                consumer = managed;
                managed.observe(metrics);
                managed.startAsync().awaitRunning();
            } else {
                if (!(builder instanceof LitePushConsumerBuilderImpl nativeBuilder))
                    throw new ManagedTransportException("Unsupported LitePush SDK for Turn ACKs");
                var managed = new ManagedLitePushConsumer(nativeBuilder, this::afterSuspended);
                // Publish the client before starting: callbacks may run during awaitRunning().
                consumer = managed;
                managed.observe(metrics);
                managed.startAsync().awaitRunning();
            }
            ManagedRuntimeLog.info(
                    log,
                    component() + ".consumer.started",
                    null,
                    null,
                    "consumer_id",
                    consumerId,
                    "channel",
                    channel.name(),
                    "topic",
                    topic,
                    "consumer_group",
                    group);
        } catch (RuntimeException failure) {
            try {
                close();
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw new ManagedTransportException("Cannot start Managed LitePush consumer", failure);
        }
    }

    public boolean active(ManagedPushDelivery delivery) {
        return !closed.get() && active.get(delivery.deliveryId()) == delivery;
    }

    private ConsumeResult consume(MessageView view) {
        long started = System.nanoTime();
        metrics.addGauge("callback.active", 1);
        metrics.count("callback.calls", 1);
        if (view instanceof MessageViewImpl nativeView) {
            metrics.millis(
                    "receive_to_callback",
                    System.currentTimeMillis() - nativeView.getDecodeTimestamp());
            if (ManagedMqMetrics.traceMessage(view.getMessageId().toString()))
                ManagedRuntimeLog.info(
                        log,
                        "mq.callback.entered",
                        null,
                        null,
                        "channel",
                        channel.name(),
                        "consumer_group",
                        group,
                        "broker_message_id",
                        view.getMessageId().toString(),
                        "received_at",
                        nativeView.getDecodeTimestamp(),
                        "attempt",
                        view.getDeliveryAttempt());
        }
        ManagedPushDelivery delivery = null;
        try {
            if (closed.get()) return ConsumeResult.FAILURE;
            if (!topic.equals(view.getTopic()))
                throw new IllegalArgumentException("Unexpected parent Topic");
            if (ManagedShardWakeup.matches(view, routing)) {
                metrics.count("wakeup.received", 1);
                return ConsumeResult.SUCCESS;
            }
            int depth = Integer.parseInt(view.getProperties().get("ma_depth"));
            ManagedTurnLanes.validate(channel, depth);
            var buffer = view.getBody().duplicate();
            if (buffer.remaining() > ManagedMessageBus.MAX_BODY_BYTES)
                throw new IllegalArgumentException("Managed envelope exceeds body limit");
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            String destination =
                    view.getLiteTopic()
                            .orElseThrow(() -> new IllegalArgumentException("Missing LiteTopic"));
            String scope =
                    routing == null
                            ? destination
                            : routing.receive(
                                    view.getProperties().get("ma_scope"),
                                    destination,
                                    view.getProperties().get("ma_route_version"));
            String logicalGroup =
                    routing == null ? scope : view.getProperties().get("ma_message_group");
            var message =
                    new Message(
                            view.getProperties().get("ma_logical_id"), logicalGroup, bytes, depth);
            if (routing != null && !scope.equals(routing.envelopeScope(message)))
                throw new IllegalArgumentException("Envelope scope differs from routing identity");
            delivery =
                    new ManagedPushDelivery(
                            UUID.randomUUID().toString(),
                            consumerId,
                            channel,
                            topic,
                            group,
                            view.getMessageId().toString(),
                            view.getDeliveryAttempt(),
                            message,
                            consumer instanceof ManagedAdmittedLitePushConsumer admitted
                                    ? admitted.handoff((MessageViewImpl) view)
                                    : new ManagedDeliveryHandoff());
            active.put(delivery.deliveryId(), delivery);
            handler.consume(delivery);
            receiptFailures.clear(view.getMessageId().toString());
            var result = closed.get() ? ConsumeResult.FAILURE : ConsumeResult.SUCCESS;
            traceReturn(delivery, result);
            return result;
        } catch (ManagedDeliverySuspended suspended) {
            var result =
                    closed.get()
                            ? ConsumeResult.FAILURE
                            : ConsumeResultSuspend.of(suspended.delay());
            if (delivery != null) traceReturn(delivery, result);
            return result;
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            errors.accept(delivery, failure);
            // An uncommitted/uncertain Resume cannot be ACKed or exhausted into a DLQ.
            // Admission stops new receives when such a receipt exceeds the handoff time budget.
            var result =
                    (channel == Channel.TURN_RESUMES || handler.receiveCapacity(channel) != null)
                                    && !closed.get()
                                    && (failure instanceof DataAccessException
                                            || failure instanceof ManagedTransportException
                                            || failure instanceof RejectedExecutionException
                                            || failure instanceof InterruptedException)
                            ? ConsumeResultSuspend.of(
                                    receiptFailures.next(
                                            view.getMessageId().toString(), Duration.ofSeconds(1)))
                            : ConsumeResult.FAILURE;
            if (delivery != null) traceReturn(delivery, result);
            return result;
        } finally {
            metrics.duration("callback.execution", System.nanoTime() - started);
            metrics.addGauge("callback.active", -1);
            if (delivery != null) active.remove(delivery.deliveryId(), delivery);
        }
    }

    private void afterSuspended(MessageView view, Duration delay) {
        if (closed.get()) return;
        try {
            receiptWriter.execute(
                    () -> {
                        if (closed.get()) return;
                        try {
                            suspended.accept(view, delay);
                        } catch (RuntimeException unavailable) {
                            // A failed save leaves the original MQ head intact; timeout redelivery
                            // retries.
                            log.warn(
                                    "Turn receipt capture pending ({})",
                                    unavailable.getClass().getSimpleName());
                        }
                    });
        } catch (RejectedExecutionException full) {
            log.warn("Turn receipt capture capacity exhausted; retaining MQ timeout recovery");
        }
    }

    public Ack acknowledge(RocketMqTurnReceipt receipt) {
        if (closed.get() || !topic.equals(receipt.topic())) return Ack.RETRY;
        return ManagedLitePushConsumer.acknowledge(consumer, receipt);
    }

    private String component() {
        return channel == Channel.TOOL_EXECUTE || channel == Channel.TOOL_CONTROL
                ? "tool-worker"
                : "agent-harness-runtime";
    }

    private void traceReturn(ManagedPushDelivery delivery, ConsumeResult result) {
        metrics.count(
                result instanceof ConsumeResultSuspend
                        ? "callback.suspend"
                        : result == ConsumeResult.SUCCESS ? "callback.success" : "callback.failure",
                1);
        // HarnessRuntime acknowledges streaming event batches frequently; successful durable
        // boundaries
        // have their own logs. Keep INFO callback tracking for Turn ordering and failures.
        if (channel == Channel.TOOL_EVENTS && result == ConsumeResult.SUCCESS) return;
        ManagedRuntimeLog.write(
                log,
                result == ConsumeResult.SUCCESS
                        && delivery.attempt() == 1
                        && !ManagedMqMetrics.traceMessage(delivery.brokerMessageId()),
                component() + ".callback.returning",
                null,
                null,
                "consumer_id",
                consumerId,
                "delivery_id",
                delivery.deliveryId(),
                "scope_key",
                delivery.message().messageGroup(),
                "channel",
                channel.name(),
                "message_id",
                delivery.message().id(),
                "broker_message_id",
                delivery.brokerMessageId(),
                "consume_result",
                result.name(),
                "attempt",
                delivery.attempt());
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            if (consumer != null) consumer.close();
        } catch (IOException failure) {
            throw new ManagedTransportException("Cannot close Managed LitePush consumer", failure);
        } finally {
            active.clear();
            if (receiptWriter != null) receiptWriter.shutdownNow();
        }
    }
}
