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
package io.agentscope.builder.runtime.managed;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.SessionCredentialsProvider;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.java.impl.consumer.ManagedLitePushConsumer.Ack;

/** Five logical channels, each with one parent Topic and an independent wildcard/FIFO consumer. */
public final class RocketMqManagedMessageBus implements ManagedMessageBus {
    private final ClientServiceProvider provider;
    private final Producer producer;
    private final ClientConfiguration configuration;
    private final Map<Channel, String> topics;
    private final Set<Channel> publishChannels;
    private final Map<Channel, String> groups;
    private final int cacheMessages;
    private final int cacheBytes;

    private final Map<Channel, RocketMqManagedPushConsumer> consumers = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private BiConsumer<MessageView, Duration> turnSuspended;

    public record Settings(
            String endpoints,
            String turnTopic,
            String resumeTopic,
            String toolEventTopic,
            String toolExecuteTopic,
            String toolControlTopic,
            Set<Channel> publishChannels,
            Map<Channel, String> consumerGroups,
            int cacheMessages,
            int cacheBytes,
            boolean ssl,
            SessionCredentialsProvider credentials) {
        public Settings {
            ManagedScope.requireText(endpoints, "MQ endpoints");
            for (String topic :
                    List.of(
                            turnTopic,
                            resumeTopic,
                            toolEventTopic,
                            toolExecuteTopic,
                            toolControlTopic)) ManagedScope.requireText(topic, "parent Topic");
            var allTopics =
                    new HashSet<>(
                            List.of(
                                    turnTopic,
                                    resumeTopic,
                                    toolEventTopic,
                                    toolExecuteTopic,
                                    toolControlTopic));
            if (allTopics.size() != 5)
                throw new IllegalArgumentException(
                        "Managed channels require distinct parent Topics");
            publishChannels = Set.copyOf(publishChannels);
            consumerGroups = Map.copyOf(consumerGroups);
            if (publishChannels.isEmpty() && consumerGroups.isEmpty()
                    || cacheMessages < 1
                    || cacheBytes < 1)
                throw new IllegalArgumentException(
                        "Managed producer role and bounded cache are required");
            for (String group : consumerGroups.values())
                ManagedScope.requireText(group, "consumer group");
            if (new HashSet<>(consumerGroups.values()).size() != consumerGroups.size())
                throw new IllegalArgumentException(
                        "Each consumer group must bind one parent Topic");
        }
    }

    public static RocketMqManagedMessageBus connect(Settings settings) {
        var provider = ClientServiceProvider.loadService();
        var builder =
                ClientConfiguration.newBuilder()
                        .setEndpoints(settings.endpoints())
                        .setRequestTimeout(Duration.ofSeconds(5))
                        .setMaxStartupAttempts(2)
                        .enableSsl(settings.ssl());
        if (settings.credentials() != null) builder.setCredentialProvider(settings.credentials());
        var configuration = builder.build();
        var topics =
                Map.of(
                        Channel.TURNS,
                        settings.turnTopic(),
                        Channel.TOOL_EVENTS,
                        settings.toolEventTopic(),
                        Channel.TURN_RESUMES,
                        settings.resumeTopic(),
                        Channel.TOOL_EXECUTE,
                        settings.toolExecuteTopic(),
                        Channel.TOOL_CONTROL,
                        settings.toolControlTopic());
        var publishedTopics = new ArrayList<String>();
        for (var channel : settings.publishChannels()) publishedTopics.add(topics.get(channel));
        try {
            var producer =
                    publishedTopics.isEmpty()
                            ? null
                            : provider.newProducerBuilder()
                                    .setClientConfiguration(configuration)
                                    .setTopics(publishedTopics.toArray(String[]::new))
                                    .build();
            return new RocketMqManagedMessageBus(
                    provider,
                    producer,
                    configuration,
                    topics,
                    settings.publishChannels(),
                    settings.consumerGroups(),
                    settings.cacheMessages(),
                    settings.cacheBytes());
        } catch (ClientException failure) {
            throw new ManagedTransportException(
                    "Cannot connect Managed RocketMQ producer", failure);
        }
    }

    RocketMqManagedMessageBus(
            ClientServiceProvider provider,
            Producer producer,
            ClientConfiguration configuration,
            Map<Channel, String> topics,
            Set<Channel> publishChannels,
            Map<Channel, String> groups,
            int cacheMessages,
            int cacheBytes) {
        this.provider = provider;
        this.producer = producer;
        this.configuration = configuration;
        this.topics = Map.copyOf(topics);
        this.publishChannels = Set.copyOf(publishChannels);
        this.groups = Map.copyOf(groups);
        this.cacheMessages = cacheMessages;
        this.cacheBytes = cacheBytes;
    }

    @Override
    public Published publish(Channel channel, Message message) {
        checkOpen();
        if (!publishChannels.contains(channel))
            throw new IllegalArgumentException("Managed producer role cannot publish " + channel);
        ManagedTurnLanes.validate(channel, message.depth());
        var nativeMessage =
                provider.newMessageBuilder()
                        .setTopic(topics.get(channel))
                        .setKeys(message.id())
                        .setLiteTopic(message.messageGroup())
                        .addProperty("ma_logical_id", message.id())
                        .addProperty("ma_depth", Integer.toString(message.depth()))
                        .setBody(message.body())
                        .build();
        try {
            return new Published(producer.send(nativeMessage).getMessageId().toString());
        } catch (ClientException unavailable) {
            throw new ManagedTransportException(
                    "Managed MQ publication is unconfirmed", unavailable);
        }
    }

    @Override
    public synchronized Subscription subscribe(
            Channel channel,
            int callbackThreads,
            Listener listener,
            BiConsumer<ManagedPushDelivery, Exception> errors) {
        checkOpen();
        if (!groups.containsKey(channel) || consumers.containsKey(channel))
            throw new IllegalArgumentException(
                    "Missing or duplicate Managed subscription: " + channel);
        var consumer =
                new RocketMqManagedPushConsumer(
                        configuration,
                        channel,
                        topics.get(channel),
                        groups.get(channel),
                        callbackThreads,
                        cacheMessages,
                        cacheBytes,
                        listener,
                        errors,
                        channel == Channel.TURNS ? turnSuspended : null);
        consumers.put(channel, consumer);
        try {
            consumer.start();
        } catch (RuntimeException failed) {
            consumers.remove(channel, consumer);
            try {
                closeConsumers(List.of(consumer));
            } catch (RuntimeException cleanup) {
                failed.addSuppressed(cleanup);
            }
            throw failed;
        }
        return () -> {
            consumers.remove(channel, consumer);
            closeConsumers(List.of(consumer));
        };
    }

    /** Configure before subscribing; other channel handlers retain normal SDK ACK ownership. */
    public synchronized void turnAcknowledgements(BiConsumer<MessageView, Duration> suspended) {
        checkOpen();
        if (consumers.containsKey(Channel.TURNS) || turnSuspended != null)
            throw new IllegalStateException("Configure Turn ACKs before subscribing");
        turnSuspended = Objects.requireNonNull(suspended);
    }

    public String turnTopic() {
        return topics.get(Channel.TURNS);
    }

    public String turnConsumerGroup() {
        return groups.get(Channel.TURNS);
    }

    public Ack acknowledgeTurn(RocketMqTurnReceipt receipt) {
        var consumer = consumers.get(Channel.TURNS);
        return closed.get() || consumer == null ? Ack.RETRY : consumer.acknowledge(receipt);
    }

    @Override
    public boolean active(ManagedPushDelivery delivery) {
        var consumer = consumers.get(delivery.channel());
        return !closed.get() && consumer != null && consumer.active(delivery);
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        var failure = new ManagedTransportException("Cannot close Managed MQ clients");
        var closing = List.copyOf(consumers.values());
        consumers.clear();
        try {
            closeConsumers(closing);
        } catch (RuntimeException close) {
            failure.addSuppressed(close);
        }
        try {
            if (producer != null) producer.close();
        } catch (Exception close) {
            failure.addSuppressed(close);
        }
        if (failure.getSuppressed().length != 0) throw failure;
    }

    private void checkOpen() {
        if (closed.get()) throw new ManagedTransportException("Managed MQ clients are closed");
    }

    private static void closeConsumers(Collection<RocketMqManagedPushConsumer> closing) {
        if (closing.isEmpty()) return;
        var executor = Executors.newFixedThreadPool(closing.size());
        var failure = new ManagedTransportException("Cannot close Managed callback pools");
        try {
            var tasks = new ArrayList<Future<?>>();
            for (var consumer : closing) tasks.add(executor.submit(consumer::close));
            for (var task : tasks) {
                try {
                    task.get();
                } catch (ExecutionException close) {
                    failure.addSuppressed(close.getCause());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    failure.addSuppressed(interrupted);
                    break;
                }
            }
        } finally {
            executor.shutdownNow();
        }
        if (failure.getSuppressed().length != 0) throw failure;
    }
}
