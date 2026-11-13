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

import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableProtocol;
import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.ExecutionMessages;
import io.agentscope.durable.ExecutionMessages.Command;
import io.agentscope.durable.Executor;
import io.agentscope.durable.store.ObjectStore;
import io.agentscope.durable.store.RecordStore;
import io.agentscope.durable.worker.ExecutionStore;
import io.agentscope.durable.worker.TransactionalExecutionStore;
import io.agentscope.durable.worker.WorkerRuntime;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.producer.Producer;

/** Hands-side MQ host for the same WorkerRuntime used by embedded and HTTP deployments. */
public final class RocketMqWorker implements AutoCloseable {
    private final String name;
    private final String updatesTopic;
    private final Consumer<Command> authorizer;
    private final Duration interval;
    private final TransactionalExecutionStore store;
    private final WorkerRuntime worker;
    private final RocketMqLoop loop;
    private final RocketMqLoop controls;
    private final Producer producer;
    private final SimpleConsumer consumer;
    private final SimpleConsumer controlConsumer;
    private final boolean ownsClients;

    private RocketMqWorker(Builder builder) {
        name = builder.name;
        var topology = RocketMqExecutionTopology.route(builder.namespace, name);
        updatesTopic = topology.updates();
        authorizer = builder.authorizer;
        interval = builder.interval;
        store = new TransactionalExecutionStore(builder.records, name);
        ClientServiceProvider provider = builder.provider;
        Producer configuredProducer;
        SimpleConsumer configuredConsumer;
        SimpleConsumer configuredControlConsumer;
        boolean ownsConfiguredClients;
        if (builder.producer == null) {
            Producer createdProducer = null;
            SimpleConsumer createdConsumer = null;
            SimpleConsumer createdControlConsumer = null;
            try {
                createdProducer =
                        provider.newProducerBuilder()
                                .setClientConfiguration(builder.clientConfiguration)
                                .setTopics(topology.updates())
                                .build();
                createdConsumer =
                        provider.newSimpleConsumerBuilder()
                                .setClientConfiguration(builder.clientConfiguration)
                                .setConsumerGroup(topology.workerConsumerGroup())
                                .setAwaitDuration(Duration.ofSeconds(10))
                                .setSubscriptionExpressions(
                                        java.util.Map.of(
                                                topology.commands(), FilterExpression.SUB_ALL))
                                .build();
                createdControlConsumer =
                        provider.newSimpleConsumerBuilder()
                                .setClientConfiguration(builder.clientConfiguration)
                                .setConsumerGroup(topology.controlConsumerGroup())
                                .setAwaitDuration(Duration.ofSeconds(10))
                                .setSubscriptionExpressions(
                                        java.util.Map.of(
                                                topology.controls(), FilterExpression.SUB_ALL))
                                .build();
            } catch (Exception error) {
                close(createdControlConsumer);
                close(createdConsumer);
                close(createdProducer);
                throw new DurableException(
                        DurableException.Code.UNAVAILABLE,
                        "Cannot create RocketMQ worker clients",
                        error);
            }
            configuredProducer = createdProducer;
            configuredConsumer = createdConsumer;
            configuredControlConsumer = createdControlConsumer;
            ownsConfiguredClients = true;
        } else {
            configuredProducer = builder.producer;
            configuredConsumer = builder.consumer;
            configuredControlConsumer = builder.controlConsumer;
            ownsConfiguredClients = false;
        }
        producer = configuredProducer;
        consumer = configuredConsumer;
        controlConsumer = configuredControlConsumer;
        ownsClients = ownsConfiguredClients;
        loop =
                new RocketMqLoop(
                        provider,
                        producer,
                        consumer,
                        topology.commands(),
                        builder.errors,
                        builder.objects,
                        builder.records,
                        builder.receiveBatchSize);
        controls =
                new RocketMqLoop(
                        provider,
                        producer,
                        controlConsumer,
                        topology.controls(),
                        builder.errors,
                        builder.objects,
                        builder.records,
                        builder.receiveBatchSize);
        worker =
                WorkerRuntime.builder()
                        .executionStore(store)
                        .resolver(builder.resolver)
                        .leaseDuration(builder.lease)
                        .pollInterval(interval)
                        .parallelism(builder.parallelism)
                        .recoveryParallelism(builder.recoveryParallelism)
                        .retryMaximum(builder.retryMaximum)
                        .onError(loop::report)
                        .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Starts the complete Hands host, including MQ delivery and the WorkerRuntime engine. */
    public void start() {
        loop.ensureOpen();
        loop.start(this::receive, this::publishPending, interval);
        controls.start(this::receive, () -> {}, interval);
        worker.start();
    }

    private void receive(String body) {
        var value = DurableJson.parse(body);
        DurableProtocol.validate("executionCommand", value);
        var command = DurableJson.convert(value, Command.class);
        ExecutionMessages.validate(command);
        if (!name.equals(command.request().call().executor()))
            throw new DurableException(DurableException.Code.INCOMPATIBLE, "Worker route differs");
        authorizer.accept(command);
        store.receive(command);
    }

    private void publishPending() {
        var keys = store.pendingPublications(100);
        for (var key : keys) {
            try {
                for (int i = 0; i < 100; i++) {
                    var pending = store.pendingEvent(key);
                    if (pending.isEmpty()) break;
                    var event = pending.get();
                    loop.send(updatesTopic, event.eventId(), event);
                    store.published(event);
                }
            } catch (RuntimeException error) {
                loop.report(error);
            }
        }
    }

    public WorkerRuntime.Status status() {
        return worker.status();
    }

    public ExecutionStore.Statistics statistics(int limit) {
        return worker.statistics(limit);
    }

    /** Stop admission, drain active provider calls and flush their durable event outbox. */
    public boolean drain(Duration timeout) throws InterruptedException {
        if (timeout.isNegative()) throw new IllegalArgumentException("Negative drain timeout");
        long deadline = System.nanoTime() + timeout.toNanos();
        loop.quiesce();
        controls.quiesce();
        if (!loop.drainAdmissions(remaining(deadline))
                || !controls.drainAdmissions(remaining(deadline))) return false;
        if (!worker.drain(remaining(deadline))) return false;
        while (!store.pendingPublications(1).isEmpty()) {
            if (System.nanoTime() >= deadline) return false;
            Thread.sleep(Math.min(25, Math.max(1, remaining(deadline).toMillis())));
        }
        return true;
    }

    private static Duration remaining(long deadline) {
        return Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
    }

    /** Stops delivery/execution loops and clients created from clientConfiguration. */
    @Override
    public void close() {
        worker.close();
        controls.close();
        loop.close();
        if (ownsClients) {
            close(controlConsumer);
            close(consumer);
            close(producer);
        }
    }

    private static void close(AutoCloseable value) {
        if (value == null) return;
        try {
            value.close();
        } catch (Exception error) {
            System.getLogger(RocketMqWorker.class.getName())
                    .log(System.Logger.Level.WARNING, "Cannot close RocketMQ client", error);
        }
    }

    public static final class Builder {
        private String name;
        private String namespace = RocketMqExecutionTopology.DEFAULT_NAMESPACE;
        private Function<ExecutionRequest, Executor> resolver;
        private RecordStore records;
        private ObjectStore objects;
        private ClientServiceProvider provider = ClientServiceProvider.loadService();
        private ClientConfiguration clientConfiguration;
        private Producer producer;
        private SimpleConsumer consumer;
        private SimpleConsumer controlConsumer;
        private Consumer<Command> authorizer;
        private int receiveBatchSize = 16;
        private Duration interval = Duration.ofMillis(100);
        private Duration lease = Duration.ofSeconds(30);
        private int parallelism = 4;
        private int recoveryParallelism = 2;
        private Duration retryMaximum = Duration.ofSeconds(30);
        private Consumer<Throwable> errors =
                error ->
                        System.getLogger(RocketMqWorker.class.getName())
                                .log(System.Logger.Level.WARNING, "Hands execution failed", error);

        private Builder() {}

        /** Optional deployment namespace; route-specific topics and groups are derived from it. */
        public Builder namespace(String value) {
            namespace = Objects.requireNonNull(value);
            return this;
        }

        public Builder executor(String route, Executor value) {
            name = DurableJson.text(route, "executor name");
            resolver = request -> value;
            Objects.requireNonNull(value);
            return this;
        }

        /** One pool can resolve Sandbox, MCP and batch targets with their actual capabilities. */
        public Builder resolver(String route, Function<ExecutionRequest, Executor> value) {
            name = DurableJson.text(route, "executor name");
            resolver = Objects.requireNonNull(value);
            return this;
        }

        /** Shared immutable store for payloads exceeding the broker frame limit. Borrowed. */
        public Builder objects(ObjectStore value) {
            objects = Objects.requireNonNull(value);
            return this;
        }

        /** Persistent Hands delivery and execution records. The store remains caller-owned. */
        public Builder recordStore(RecordStore value) {
            records = Objects.requireNonNull(value);
            return this;
        }

        /** RocketMQ connection only; topics and consumer groups follow the shared route convention. */
        public Builder clientConfiguration(ClientConfiguration value) {
            clientConfiguration = Objects.requireNonNull(value);
            return this;
        }

        /** Test hook for borrowed SDK clients already connected to the derived topology. */
        Builder clients(
                ClientServiceProvider factory,
                Producer publisher,
                SimpleConsumer commandsConsumer,
                SimpleConsumer controlsConsumer) {
            provider = Objects.requireNonNull(factory);
            producer = Objects.requireNonNull(publisher);
            consumer = Objects.requireNonNull(commandsConsumer);
            controlConsumer = Objects.requireNonNull(controlsConsumer);
            return this;
        }

        /** Called for every command before any ledger access; throw to reject unauthorized work. */
        public Builder authorize(Consumer<Command> value) {
            authorizer = Objects.requireNonNull(value);
            return this;
        }

        /** MQ delivery batch size is independent of provider execution capacity. */
        public Builder receiveBatchSize(int value) {
            if (value < 1 || value > 1024)
                throw new IllegalArgumentException("Receive batch must be 1..1024");
            receiveBatchSize = value;
            return this;
        }

        public Builder pollInterval(Duration value) {
            if (value.toMillis() < 1)
                throw new IllegalArgumentException("Interval must be positive");
            interval = value;
            return this;
        }

        public Builder leaseDuration(Duration value) {
            if (value.toMillis() < 100)
                throw new IllegalArgumentException("Lease must be >= 100ms");
            lease = value;
            return this;
        }

        public Builder parallelism(int value) {
            if (value < 1) throw new IllegalArgumentException("Parallelism must be positive");
            parallelism = value;
            return this;
        }

        public Builder recoveryParallelism(int value) {
            if (value < 1)
                throw new IllegalArgumentException("Positive recovery capacity required");
            recoveryParallelism = value;
            return this;
        }

        public Builder retryMaximum(Duration value) {
            if (value.toMillis() < 1)
                throw new IllegalArgumentException("Positive retry bound required");
            retryMaximum = value;
            return this;
        }

        public Builder onError(Consumer<Throwable> value) {
            errors = Objects.requireNonNull(value);
            return this;
        }

        public RocketMqWorker build() {
            Objects.requireNonNull(name, "executor");
            Objects.requireNonNull(resolver, "resolver");
            Objects.requireNonNull(records, "store");
            if (producer == null)
                Objects.requireNonNull(clientConfiguration, "clientConfiguration");
            Objects.requireNonNull(authorizer, "authorize");
            return new RocketMqWorker(this);
        }
    }
}
