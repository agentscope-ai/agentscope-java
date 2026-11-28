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

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableProtocol;
import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.ExecutionMessages;
import io.agentscope.durable.ExecutionMessages.Command;
import io.agentscope.durable.ExecutionMessages.ExecuteBatch;
import io.agentscope.durable.ExecutionMessages.Kind;
import io.agentscope.durable.Executor;
import io.agentscope.durable.store.ObjectStore;
import io.agentscope.durable.store.RecordStore;
import io.agentscope.durable.worker.ExecutionStore;
import io.agentscope.durable.worker.TransactionalExecutionStore;
import io.agentscope.durable.worker.TransactionalExecutionStore.Received;
import io.agentscope.durable.worker.WorkerRuntime;
import io.agentscope.extensions.durable.rocketmq.RocketMqLoop.Subscription;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.MessageView;
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
    private final boolean ownsClients;

    private RocketMqWorker(Builder builder) {
        name = builder.name;
        var topology = RocketMqTopology.route(builder.namespace, name);
        updatesTopic = topology.updates();
        authorizer = builder.authorizer;
        interval = builder.publicationRepairInterval;
        ClientServiceProvider provider = builder.provider;
        Producer configuredProducer;
        Subscription configuredConsumer;
        Subscription configuredControlConsumer;
        boolean ownsConfiguredClients;
        if (builder.producer == null) {
            Producer createdProducer = null;
            try {
                createdProducer =
                        provider.newProducerBuilder()
                                .setClientConfiguration(builder.clientConfiguration)
                                .setTopics(topology.updates())
                                .build();
            } catch (Exception error) {
                close(createdProducer);
                throw new DurableException(
                        DurableException.Code.UNAVAILABLE,
                        "Cannot create RocketMQ worker clients",
                        error);
            }
            configuredProducer = createdProducer;
            configuredConsumer =
                    RocketMqLoop.subscription(
                            provider,
                            builder.clientConfiguration,
                            topology.commands(),
                            topology.workerConsumerGroup(),
                            builder.receiveBatchSize);
            configuredControlConsumer =
                    RocketMqLoop.subscription(
                            provider,
                            builder.clientConfiguration,
                            topology.controls(),
                            topology.controlConsumerGroup(),
                            builder.controlCacheSize == 0
                                    ? builder.receiveBatchSize
                                    : builder.controlCacheSize);
            ownsConfiguredClients = true;
        } else {
            configuredProducer = builder.producer;
            configuredConsumer = builder.consumer;
            configuredControlConsumer = builder.controlConsumer;
            ownsConfiguredClients = false;
        }
        producer = configuredProducer;
        ownsClients = ownsConfiguredClients;
        loop =
                new RocketMqLoop(
                        provider,
                        producer,
                        configuredConsumer,
                        topology.commands(),
                        builder.errors,
                        builder.objects,
                        builder.records);
        controls =
                new RocketMqLoop(
                        provider,
                        producer,
                        configuredControlConsumer,
                        topology.controls(),
                        builder.errors,
                        builder.objects,
                        builder.records);
        store =
                new TransactionalExecutionStore(
                        builder.records, name, key -> loop.wake(key, () -> publishPending(key)));
        worker =
                WorkerRuntime.builder()
                        .executionStore(store)
                        .resolver(builder.resolver)
                        .authorizeStart(builder.authorizeStart)
                        .leaseDuration(builder.lease)
                        .discoveryInterval(builder.discoveryInterval)
                        .pollInterval(builder.providerPollInterval)
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
        worker.start();
        try {
            loop.start(this::receiveBatch, this::publishPending, interval);
            controls.start(this::receiveControl, null, interval);
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    private void receiveBatch(JsonNode value, MessageView message) {
        DurableProtocol.validate("executionBatch", value);
        var batch = DurableJson.convert(value, ExecuteBatch.class);
        ExecutionMessages.validate(batch);
        RocketMqLiteRouting.require(
                message,
                RocketMqLiteRouting.session(batch.requests().get(0).session()),
                batch.batchId());
        var commands =
                batch.requests().stream()
                        .map(request -> ExecutionMessages.command(Kind.EXECUTE, request))
                        .toList();
        commands.forEach(this::authorize);
        // Broker acknowledgement follows admission of the last child. Replays reuse each ledger.
        List<Received> admissions = commands.stream().map(store::receive).toList();
        admissions.forEach(this::offer);
    }

    private void receiveControl(JsonNode value, MessageView message) {
        DurableProtocol.validate("executionCommand", value);
        var command = DurableJson.convert(value, Command.class);
        ExecutionMessages.validate(command);
        if (command.kind() == Kind.EXECUTE)
            throw new IllegalArgumentException("Execution command is on the wrong parent Topic");
        RocketMqLiteRouting.require(
                message,
                RocketMqLiteRouting.session(command.request().session()),
                command.commandId());
        authorize(command);
        var received = store.receive(command);
        if (command.kind() == Kind.CANCEL) worker.wakeCancellation(received.key());
        offer(received);
    }

    private void authorize(Command command) {
        if (!name.equals(command.request().call().executor()))
            throw new DurableException(DurableException.Code.INCOMPATIBLE, "Worker route differs");
        authorizer.accept(command);
    }

    private void offer(Received received) {
        if (received.runnable()) worker.offer(received.key(), received.recovery());
    }

    private void publishPending() {
        for (var key : store.pendingPublications(100)) {
            try {
                publishPending(key);
            } catch (RuntimeException error) {
                loop.report(error);
            }
        }
    }

    private void publishPending(String key) {
        for (int i = 0; i < 100; i++) {
            var pending = store.pendingEvent(key);
            if (pending.isEmpty()) return;
            var event = pending.get();
            loop.send(
                    updatesTopic,
                    RocketMqLiteRouting.execution(event.request()),
                    event.eventId(),
                    event);
            store.published(event);
        }
        loop.wake(key, () -> publishPending(key));
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
        if (ownsClients) close(producer);
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
        private String namespace = RocketMqTopology.DEFAULT_NAMESPACE;
        private Function<ExecutionRequest, Executor> resolver;
        private RecordStore records;
        private ObjectStore objects;
        private ClientServiceProvider provider = ClientServiceProvider.loadService();
        private ClientConfiguration clientConfiguration;
        private Producer producer;
        private Subscription consumer;
        private Subscription controlConsumer;
        private Consumer<Command> authorizer;
        private Function<ExecutionRequest, Optional<String>> authorizeStart =
                request -> Optional.empty();
        private int receiveBatchSize = 16;
        private int controlCacheSize;
        private Duration discoveryInterval = Duration.ofSeconds(2);
        private Duration publicationRepairInterval = Duration.ofSeconds(2);
        private Duration providerPollInterval = Duration.ofMillis(100);
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
                Subscription commandsConsumer,
                Subscription controlsConsumer) {
            provider = Objects.requireNonNull(factory);
            producer = Objects.requireNonNull(publisher);
            consumer = Objects.requireNonNull(commandsConsumer);
            controlConsumer = Objects.requireNonNull(controlsConsumer);
            return this;
        }

        /** Validates command intake and scope before ledger access. */
        public Builder authorize(Consumer<Command> value) {
            authorizer = Objects.requireNonNull(value);
            return this;
        }

        /** Checks current business permission for each admitted call before its first start. */
        public Builder authorizeStart(Function<ExecutionRequest, Optional<String>> value) {
            authorizeStart = Objects.requireNonNull(value);
            return this;
        }

        /** MQ delivery batch size is independent of provider execution capacity. */
        public Builder receiveBatchSize(int value) {
            if (value < 1 || value > 1024)
                throw new IllegalArgumentException("Receive batch must be 1..1024");
            receiveBatchSize = value;
            return this;
        }

        /** Pending-message limit for the independent CANCEL and QUERY consumer. */
        public Builder controlCacheSize(int value) {
            if (value < 1 || value > 1024)
                throw new IllegalArgumentException("Control cache size must be 1..1024");
            controlCacheSize = value;
            return this;
        }

        /** Sets both periodic discovery and publication repair intervals. */
        public Builder pollInterval(Duration value) {
            return discoveryInterval(value).publicationRepairInterval(value);
        }

        public Builder discoveryInterval(Duration value) {
            if (value.toMillis() < 1)
                throw new IllegalArgumentException("Interval must be positive");
            discoveryInterval = value;
            return this;
        }

        public Builder publicationRepairInterval(Duration value) {
            if (value.toMillis() < 1)
                throw new IllegalArgumentException("Interval must be positive");
            publicationRepairInterval = value;
            return this;
        }

        public Builder providerPollInterval(Duration value) {
            if (value.toMillis() < 1)
                throw new IllegalArgumentException("Interval must be positive");
            providerPollInterval = value;
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
