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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
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
    private final Map<Channel, ManagedMqMetrics> publishMetrics = new ConcurrentHashMap<>();
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
            SessionCredentialsProvider credentials,
            String sessionReadyTopic) {
        public Settings(
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
            this(
                    endpoints,
                    turnTopic,
                    resumeTopic,
                    toolEventTopic,
                    toolExecuteTopic,
                    toolControlTopic,
                    publishChannels,
                    consumerGroups,
                    cacheMessages,
                    cacheBytes,
                    ssl,
                    credentials,
                    "ma_session_ready_v1");
        }

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
            ManagedScope.requireText(sessionReadyTopic, "Session-ready Topic");
            if (allTopics.contains(sessionReadyTopic))
                throw new IllegalArgumentException(
                        "Session-ready requires a distinct parent Topic");
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
        return connect(settings, Map.of(Channel.SESSION_PROJECTIONS, "ma_session_projections_v3"));
    }

    public static RocketMqManagedMessageBus connect(
            Settings settings, Map<Channel, String> additionalTopics) {
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
                new HashMap<Channel, String>(
                        Map.of(
                                Channel.TURNS,
                                settings.turnTopic(),
                                Channel.SESSION_READY,
                                settings.sessionReadyTopic(),
                                Channel.TOOL_EVENTS,
                                settings.toolEventTopic(),
                                Channel.TURN_RESUMES,
                                settings.resumeTopic(),
                                Channel.TOOL_EXECUTE,
                                settings.toolExecuteTopic(),
                                Channel.TOOL_CONTROL,
                                settings.toolControlTopic()));
        for (var entry : additionalTopics.entrySet()) {
            if (topics.containsKey(entry.getKey())
                    || entry.getValue() == null
                    || entry.getValue().isBlank()
                    || topics.containsValue(entry.getValue()))
                throw new IllegalArgumentException(
                        "Session Store channels require distinct parent Topics");
            topics.put(entry.getKey(), entry.getValue());
        }
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

    private ManagedStableRouting routing;
    private ManagedShardWakeup wakeup;

    public void routing(ManagedStableRouting routing) {
        checkOpen();
        if (this.routing != null) throw new IllegalStateException("Routing already configured");
        this.routing = routing;
        if (routing != null && producer != null && !publishChannels.isEmpty()) {
            var destinations = new ArrayList<ManagedShardWakeup.Destination>();
            for (var channel : publishChannels)
                for (var destination : routing.destinations())
                    destinations.add(new ManagedShardWakeup.Destination(channel, destination));
            wakeup = new ManagedShardWakeup(destinations, this::publishWakeup);
            wakeup.start();
        }
    }

    private void publishWakeup(ManagedShardWakeup.Destination destination) {
        if (closed.get()) return;
        var channel = destination.channel();
        var metrics =
                publishMetrics.computeIfAbsent(
                        channel,
                        key -> new ManagedMqMetrics(key.name(), topics.get(key), "producer"));
        try {
            producer.send(
                    provider.newMessageBuilder()
                            .setTopic(topics.get(channel))
                            .setLiteTopic(destination.liteTopic())
                            .addProperty(ManagedShardWakeup.PROPERTY, "1")
                            .setBody(ManagedShardWakeup.BODY)
                            .build());
            metrics.count("wakeup.success", 1);
        } catch (ClientException failure) {
            metrics.count("wakeup.failure", 1);
            throw new ManagedTransportException("Shard wakeup unconfirmed", failure);
        }
    }

    @Override
    public Published publish(Channel channel, Message message) {
        checkOpen();
        if (!publishChannels.contains(channel))
            throw new IllegalArgumentException("Managed producer role cannot publish " + channel);
        ManagedTurnLanes.validate(channel, message.depth());
        String routeScope =
                routing == null ? message.messageGroup() : routing.envelopeScope(message);
        var route = routing == null ? null : routing.route(routeScope);
        var nativeMessage =
                provider.newMessageBuilder()
                        .setTopic(topics.get(channel))
                        .setKeys(message.id())
                        .setLiteTopic(route == null ? message.messageGroup() : route.liteTopic())
                        .addProperty("ma_scope", routeScope)
                        .addProperty("ma_message_group", message.messageGroup())
                        .addProperty(
                                "ma_route_version",
                                route == null ? "0" : Integer.toString(route.layoutVersion()))
                        .addProperty("ma_logical_id", message.id())
                        .addProperty("ma_depth", Integer.toString(message.depth()))
                        .setBody(message.body())
                        .build();
        var metrics =
                publishMetrics.computeIfAbsent(
                        channel,
                        key -> new ManagedMqMetrics(key.name(), topics.get(key), "producer"));
        long started = System.nanoTime();
        try {
            var result = new Published(producer.send(nativeMessage).getMessageId().toString());
            metrics.count("publish.success", 1);
            return result;
        } catch (ClientException unavailable) {
            metrics.publishFailure(unavailable, message.id());
            throw new ManagedTransportException(
                    "Managed MQ publication is unconfirmed", unavailable);
        } finally {
            metrics.duration("publish.rpc", System.nanoTime() - started);
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
        consumer.routing(routing);
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
        if (wakeup != null) wakeup.close();
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
