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
import io.agentscope.durable.DurableTypes.ExecutionResult;
import io.agentscope.durable.DurableTypes.OutputPage;
import io.agentscope.durable.ExecutionMessages.Event;
import io.agentscope.durable.ExecutionMessages.Kind;
import io.agentscope.durable.ManagedExecutor;
import io.agentscope.durable.SessionBackend;
import io.agentscope.durable.worker.QueuedExecutor;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.producer.Producer;

/** Brain-side remote execution over RocketMQ, bound and managed by DurableRuntime. */
public final class RocketMqRemoteExecutor implements ManagedExecutor {
    private final Builder configuration;
    private RocketMqLoop loop;
    private QueuedExecutor executor;
    private Producer producer;
    private SimpleConsumer consumer;
    private boolean ownsClients;

    private RocketMqRemoteExecutor(Builder builder) {
        configuration = builder;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public synchronized void bind(String route, SessionBackend backend) {
        if (executor != null) throw new IllegalStateException("RocketMQ executor is already bound");
        var storage =
                backend.localStorage()
                        .orElseThrow(
                                () ->
                                        new DurableException(
                                                DurableException.Code.INCOMPATIBLE,
                                                "RocketMQ execution requires local durable"
                                                        + " storage"));
        var topology = RocketMqExecutionTopology.route(configuration.namespace, route);
        ClientServiceProvider provider = configuration.provider;
        if (configuration.producer == null) {
            ownsClients = true;
            try {
                producer =
                        provider.newProducerBuilder()
                                .setClientConfiguration(configuration.clientConfiguration)
                                .setTopics(topology.commands(), topology.controls())
                                .build();
                consumer =
                        provider.newSimpleConsumerBuilder()
                                .setClientConfiguration(configuration.clientConfiguration)
                                .setConsumerGroup(topology.brainConsumerGroup())
                                .setAwaitDuration(Duration.ofSeconds(10))
                                .setSubscriptionExpressions(
                                        Map.of(topology.updates(), FilterExpression.SUB_ALL))
                                .build();
            } catch (Exception error) {
                closeClients();
                throw new DurableException(
                        DurableException.Code.UNAVAILABLE,
                        "Cannot create RocketMQ execution clients",
                        error);
            }
        } else {
            producer = configuration.producer;
            consumer = configuration.consumer;
        }
        loop =
                new RocketMqLoop(
                        provider,
                        producer,
                        consumer,
                        topology.updates(),
                        configuration.errors,
                        storage.objects(),
                        storage.records(),
                        configuration.receiveBatchSize);
        executor =
                new QueuedExecutor(
                        route,
                        storage.records(),
                        backend,
                        command ->
                                loop.send(
                                        command.kind() == Kind.EXECUTE
                                                ? topology.commands()
                                                : topology.controls(),
                                        command.commandId(),
                                        command),
                        configuration.retry);
    }

    @Override
    public synchronized void open() {
        bound();
        loop.start(
                body -> {
                    var value = DurableJson.parse(body);
                    DurableProtocol.validate("executionEvent", value);
                    executor.receive(DurableJson.convert(value, Event.class));
                },
                () -> executor.tick(loop::report),
                configuration.interval);
    }

    @Override
    public Capabilities capabilities() {
        return executor == null
                ? new Capabilities(true, true, true, true)
                : executor.capabilities();
    }

    @Override
    public ExecutionResult startExecution(ExecutionRequest request) {
        bound().ensureOpen();
        return executor.startExecution(request);
    }

    @Override
    public ExecutionResult query(ExecutionRequest request, String handle) {
        bound().ensureOpen();
        return executor.query(request, handle);
    }

    @Override
    public ExecutionResult cancel(ExecutionRequest request, String handle) {
        bound().ensureOpen();
        return executor.cancel(request, handle);
    }

    @Override
    public OutputPage output(ExecutionRequest request, long offset) {
        bound();
        return executor.output(request, offset);
    }

    private RocketMqLoop bound() {
        var value = loop;
        if (value == null)
            throw new IllegalStateException("Register RocketMqRemoteExecutor with DurableRuntime");
        return value;
    }

    @Override
    public synchronized void close() {
        if (loop != null) loop.close();
        closeClients();
    }

    private void closeClients() {
        if (!ownsClients) return;
        close(consumer);
        close(producer);
        ownsClients = false;
    }

    private static void close(AutoCloseable value) {
        if (value == null) return;
        try {
            value.close();
        } catch (Exception error) {
            System.getLogger(RocketMqRemoteExecutor.class.getName())
                    .log(System.Logger.Level.WARNING, "Cannot close RocketMQ client", error);
        }
    }

    public static final class Builder {
        private String namespace = RocketMqExecutionTopology.DEFAULT_NAMESPACE;
        private ClientServiceProvider provider = ClientServiceProvider.loadService();
        private ClientConfiguration clientConfiguration;
        private Producer producer;
        private SimpleConsumer consumer;
        private int receiveBatchSize = 16;
        private Duration interval = Duration.ofMillis(100);
        private Duration retry = Duration.ofSeconds(5);
        private Consumer<Throwable> errors =
                error ->
                        System.getLogger(RocketMqRemoteExecutor.class.getName())
                                .log(System.Logger.Level.WARNING, "Queued execution failed", error);

        private Builder() {}

        /** Optional deployment namespace; route-specific topics and groups are derived from it. */
        public Builder namespace(String value) {
            namespace = Objects.requireNonNull(value);
            return this;
        }

        /** RocketMQ connection only; DurableRuntime supplies storage, route and result delivery. */
        public Builder clientConfiguration(ClientConfiguration value) {
            clientConfiguration = Objects.requireNonNull(value);
            return this;
        }

        Builder clients(
                ClientServiceProvider factory, Producer publisher, SimpleConsumer updatesConsumer) {
            provider = Objects.requireNonNull(factory);
            producer = Objects.requireNonNull(publisher);
            consumer = Objects.requireNonNull(updatesConsumer);
            return this;
        }

        public Builder receiveBatchSize(int value) {
            if (value < 1 || value > 1024)
                throw new IllegalArgumentException("Receive batch must be 1..1024");
            receiveBatchSize = value;
            return this;
        }

        public Builder pollInterval(Duration value) {
            interval = positive(value);
            return this;
        }

        public Builder retryInterval(Duration value) {
            retry = positive(value);
            return this;
        }

        public Builder onError(Consumer<Throwable> value) {
            errors = Objects.requireNonNull(value);
            return this;
        }

        public RocketMqRemoteExecutor build() {
            if (producer == null)
                Objects.requireNonNull(clientConfiguration, "clientConfiguration");
            return new RocketMqRemoteExecutor(this);
        }

        private static Duration positive(Duration value) {
            if (value.toMillis() < 1)
                throw new IllegalArgumentException("Interval must be positive");
            return value;
        }
    }
}
