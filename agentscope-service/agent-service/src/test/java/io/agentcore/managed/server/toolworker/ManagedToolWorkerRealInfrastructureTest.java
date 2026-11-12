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
package io.agentcore.managed.server.toolworker;

import io.agentcore.managed.server.runtime.managed.ManagedLeaseStore;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Channel;
import io.agentcore.managed.server.runtime.managed.ManagedPushDelivery;
import io.agentcore.managed.server.runtime.managed.ManagedReceiveCapacity;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import io.agentcore.managed.server.runtime.managed.RedisManagedInfrastructure;
import io.agentcore.managed.server.runtime.managed.RocketMqManagedMessageBus;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** The same failure assertions against RocketMQ Lite, Redis and two exclusively owned PostgreSQL DBs. */
@EnabledIfEnvironmentVariable(named = "MANAGED_AGENTS_TOOL_WORKER_PROTOCOL_TEST", matches = "true")
@Timeout(180)
class ManagedToolWorkerRealInfrastructureTest extends ManagedToolWorkerPipelineTest {
    private final List<OwnedDatabase> databases = new ArrayList<>();

    @Override
    ManagedMessageBus messageBus() {
        String prefix = System.getenv("MANAGED_AGENTS_TOOL_WORKER_MQ_TEST_PREFIX");
        if (prefix == null || !prefix.matches("ma_hands_test_[a-z0-9_]+"))
            throw new IllegalArgumentException(
                    "Set a dedicated MANAGED_AGENTS_TOOL_WORKER_MQ_TEST_PREFIX and provision its"
                            + " five channel families");
        var delegate =
                RocketMqManagedMessageBus.connect(
                        new RocketMqManagedMessageBus.Settings(
                                System.getenv("MANAGED_MQ_TEST_ENDPOINT"),
                                prefix + "_turns",
                                prefix + "_resumes",
                                prefix + "_events",
                                prefix + "_execute",
                                prefix + "_control",
                                EnumSet.allOf(Channel.class),
                                Map.of(
                                        Channel.TURNS,
                                        prefix + "_brain",
                                        Channel.TURN_RESUMES,
                                        prefix + "_brain_resumes",
                                        Channel.TOOL_EVENTS,
                                        prefix + "_brain_tools",
                                        Channel.TOOL_EXECUTE,
                                        prefix + "_hands_execute",
                                        Channel.TOOL_CONTROL,
                                        prefix + "_hands_control"),
                                128,
                                8 * 1024 * 1024,
                                false,
                                null));
        // Each case owns separate databases. The dedicated fixture group can still contain
        // messages from a prior, already dropped case, so route only this case's scope.
        return new ManagedMessageBus() {
            final ExecutorService shutdown =
                    Executors.newCachedThreadPool(
                            runnable -> {
                                var thread = new Thread(runnable, "hands-test-transport-shutdown");
                                thread.setDaemon(true);
                                return thread;
                            });
            final ConcurrentLinkedQueue<Future<?>> closing = new ConcurrentLinkedQueue<>();

            void await(Future<?> task, long seconds) {
                try {
                    task.get(seconds, TimeUnit.SECONDS);
                } catch (TimeoutException pending) {
                    /* Transport teardown continues after replacement starts. */
                } catch (Exception failure) {
                    throw new IllegalStateException("Cannot stop fixture transport", failure);
                }
            }

            public Published publish(Channel channel, Message message) {
                return delegate.publish(channel, message);
            }

            public Subscription subscribe(
                    Channel channel,
                    int callbacks,
                    Listener listener,
                    BiConsumer<ManagedPushDelivery, Exception> errors) {
                var subscription =
                        delegate.subscribe(
                                channel,
                                callbacks,
                                new Listener() {
                                    @Override
                                    public void consume(ManagedPushDelivery delivery)
                                            throws Exception {
                                        var actual =
                                                mapper.treeToValue(
                                                        mapper.readTree(delivery.message().body())
                                                                .get("scope"),
                                                        ManagedScope.class);
                                        if (scope.equals(actual)) listener.consume(delivery);
                                    }

                                    @Override
                                    public ManagedReceiveCapacity receiveCapacity(
                                            Channel requested) {
                                        return listener.receiveCapacity(requested);
                                    }
                                },
                                errors);
                return () -> {
                    var task = shutdown.submit(subscription::close);
                    closing.add(task);
                    // A crashed HarnessRuntime must not await SDK long-poll teardown before another
                    // consumer can restore the suspended Turn. The delegate removes its lane first.
                    await(task, 1);
                };
            }

            public boolean active(ManagedPushDelivery delivery) {
                return delegate.active(delivery);
            }

            public void close() {
                var last = shutdown.submit(delegate::close);
                closing.add(last);
                try {
                    for (var task : closing) {
                        await(task, 90);
                        if (!task.isDone())
                            throw new IllegalStateException("Fixture SDK shutdown did not finish");
                    }
                } finally {
                    shutdown.shutdownNow();
                }
            }
        };
    }

    @Override
    ManagedLeaseStore leaseStore() {
        return new RedisManagedInfrastructure(System.getenv("MANAGED_REDIS_TEST_URI"), 4);
    }

    @Override
    Duration awaitTimeout() {
        return Duration.ofSeconds(60);
    }

    @Override
    DataSource database(String role) {
        try {
            var database = new OwnedDatabase(role);
            databases.add(database);
            return new DriverManagerDataSource(database.url, "managed", "managed-local-test");
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot create owned test database", failure);
        }
    }

    @Override
    @AfterEach
    void stop() throws Exception {
        try {
            super.stop();
        } finally {
            if (leases instanceof RedisManagedInfrastructure redis) redis.close();
            for (var database : databases) database.close();
        }
    }

    private static final class OwnedDatabase implements AutoCloseable {
        final Connection administrator;
        final String name;
        final String url;

        OwnedDatabase(String role) throws Exception {
            String base = System.getenv("MANAGED_DATABASE_TEST_URI");
            String[] parts = base.split("\\?", 2);
            name = "ma_hands_test_" + role + "_" + UUID.randomUUID().toString().replace("-", "");
            url =
                    parts[0].substring(0, parts[0].lastIndexOf('/') + 1)
                            + name
                            + (parts.length == 2 ? "?" + parts[1] : "");
            administrator = DriverManager.getConnection(base, "managed", "managed-local-test");
            try (var sql = administrator.createStatement()) {
                sql.execute("CREATE DATABASE " + name);
            }
        }

        public void close() throws Exception {
            try (var sql = administrator.createStatement()) {
                sql.execute("DROP DATABASE " + name + " WITH (FORCE)");
            } finally {
                administrator.close();
            }
        }
    }
}
