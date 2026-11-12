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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableRuntime;
import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.ExecutionResult;
import io.agentscope.durable.DurableTypes.ExecutionState;
import io.agentscope.durable.DurableTypes.RunStepRef;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.DurableTypes.ToolCall;
import io.agentscope.durable.DurableTypes.TurnStatus;
import io.agentscope.durable.ExecutionMessages;
import io.agentscope.durable.ExecutionMessages.Kind;
import io.agentscope.durable.Executor;
import io.agentscope.durable.TransactionalSessionBackend;
import io.agentscope.durable.Wakeups;
import io.agentscope.durable.agentscope.AgentScopeAdapter;
import io.agentscope.durable.store.RecordStore;
import io.agentscope.durable.worker.TransactionalExecutionStore;
import io.agentscope.extensions.durable.jdbc.JdbcRecordStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/** Real SQLite and AgentScope, SDK transport fault fixture (not a broker integration test). */
class RocketMqExecutionTest {
    @TempDir Path directory;
    private final String postgresUrl = System.getProperty("durable.test.jdbcUrl");
    private final Map<Path, String> schemas = new HashMap<>();

    private JdbcRecordStore open(Path file) {
        if (postgresUrl == null) return JdbcRecordStore.sqlite(file);
        var schema =
                schemas.computeIfAbsent(
                        file,
                        ignored -> {
                            String name =
                                    "durable_queue_"
                                            + UUID.randomUUID().toString().replace("-", "");
                            try (var connection = DriverManager.getConnection(postgresUrl);
                                    var statement = connection.createStatement()) {
                                statement.execute("CREATE SCHEMA " + name);
                            } catch (Exception error) {
                                throw new IllegalStateException(error);
                            }
                            return name;
                        });
        var properties = new Properties();
        properties.setProperty("currentSchema", schema);
        return JdbcRecordStore.postgres(postgresUrl, properties);
    }

    @AfterEach
    void cleanupSchemas() throws Exception {
        if (postgresUrl != null)
            try (var connection = DriverManager.getConnection(postgresUrl);
                    var statement = connection.createStatement()) {
                for (var schema : schemas.values())
                    statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
    }

    static final Duration POLL = Duration.ofMillis(10);
    static final ClientServiceProvider FACTORY = ClientServiceProvider.loadService();
    static final RocketMqExecutionTopology.Route TOPOLOGY =
            RocketMqExecutionTopology.route(RocketMqExecutionTopology.DEFAULT_NAMESPACE, "hands");
    final ExecutionRequest request =
            new ExecutionRequest(
                    "execution",
                    new ThreadKey(new SessionKey("tenant", "session"), "main"),
                    "turn",
                    new ToolCall("call", "hands", "echo", DurableJson.value(Map.of("value", 7)))
                            .withOrigin(new RunStepRef("brain-run", "brain-step")));

    static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Condition did not become true");
    }

    @Test
    void derivesTransportTopologyFromNamespaceAndExecutorRoute() {
        var topology = RocketMqExecutionTopology.route("production", "hands");

        assertEquals("production-hands-commands", topology.commands());
        assertEquals("production-hands-controls", topology.controls());
        assertEquals("production-hands-updates", topology.updates());
        assertEquals("production-hands-brain", topology.brainConsumerGroup());
        assertEquals("production-hands-worker", topology.workerConsumerGroup());
        assertEquals("production-hands-worker-controls", topology.controlConsumerGroup());
        assertThrows(
                IllegalArgumentException.class,
                () -> RocketMqExecutionTopology.route("production", "bad route"));
    }

    static class Broker implements AutoCloseable {
        final Producer producer = mock(Producer.class);
        final LinkedBlockingQueue<MessageView> commands = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<MessageView> controls = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<MessageView> events = new LinkedBlockingQueue<>();
        final Set<MessageView> acked = ConcurrentHashMap.newKeySet();
        final ScheduledExecutorService redelivery = Executors.newSingleThreadScheduledExecutor();
        final AtomicInteger lostCommandResponse = new AtomicInteger();
        final AtomicInteger failedEventPublish = new AtomicInteger();
        final AtomicInteger failedEventAck = new AtomicInteger();
        final AtomicInteger commandAcks = new AtomicInteger();
        final AtomicInteger eventAcks = new AtomicInteger();

        Broker() throws Exception {
            when(producer.send(any()))
                    .thenAnswer(
                            call -> {
                                var message = (Message) call.getArgument(0);
                                boolean command = !message.getTopic().equals(TOPOLOGY.updates());
                                if (!command
                                        && failedEventPublish.getAndUpdate(n -> Math.max(0, n - 1))
                                                > 0)
                                    throw new IllegalStateException("Event publication failed");
                                var view = mock(MessageView.class);
                                when(view.getTopic()).thenReturn(message.getTopic());
                                when(view.getBody())
                                        .thenAnswer(
                                                ignored -> message.getBody().asReadOnlyBuffer());
                                (message.getTopic().equals(TOPOLOGY.controls())
                                                ? controls
                                                : command ? commands : events)
                                        .add(view);
                                if (command
                                        && lostCommandResponse.getAndUpdate(n -> Math.max(0, n - 1))
                                                > 0)
                                    throw new IllegalStateException("Command broker response lost");
                                return null;
                            });
        }

        SimpleConsumer consumer(boolean command) throws Exception {
            return consumer(command ? TOPOLOGY.commands() : TOPOLOGY.updates());
        }

        SimpleConsumer consumer(String topic) throws Exception {
            boolean command = !topic.equals(TOPOLOGY.updates());
            var consumer = mock(SimpleConsumer.class);
            var queue = topic.equals(TOPOLOGY.controls()) ? controls : command ? commands : events;
            when(consumer.receive(anyInt(), any(Duration.class)))
                    .thenAnswer(
                            call -> {
                                var message = queue.poll(20, TimeUnit.MILLISECONDS);
                                if (message == null) return List.of();
                                redelivery.schedule(
                                        () -> {
                                            if (!acked.contains(message)) queue.add(message);
                                        },
                                        50,
                                        TimeUnit.MILLISECONDS);
                                return List.of(message);
                            });
            doAnswer(
                            call -> {
                                MessageView message = call.getArgument(0);
                                if (!command
                                        && failedEventAck.getAndUpdate(n -> Math.max(0, n - 1)) > 0)
                                    throw new IllegalStateException("Consumer ACK lost");
                                acked.add(message);
                                (command ? commandAcks : eventAcks).incrementAndGet();
                                return null;
                            })
                    .when(consumer)
                    .ack(any());
            return consumer;
        }

        void send(Object command) throws Exception {
            producer.send(
                    FACTORY.newMessageBuilder()
                            .setTopic(
                                    command instanceof ExecutionMessages.Command c
                                                    && c.kind() != Kind.EXECUTE
                                            ? TOPOLOGY.controls()
                                            : TOPOLOGY.commands())
                            .setBody(
                                    DurableJson.value(command)
                                            .toString()
                                            .getBytes(StandardCharsets.UTF_8))
                            .build());
        }

        @Override
        public void close() {
            redelivery.shutdownNow();
        }
    }

    @Test
    void agentScopeResumesThroughSeparateLedgersDespitePublicationAndAckFailures()
            throws Exception {
        var starts = new AtomicInteger();
        var queries = new AtomicInteger();
        var modelCalls = new AtomicInteger();
        var errors = new CopyOnWriteArrayList<Throwable>();
        Executor provider =
                new Executor() {
                    public Capabilities capabilities() {
                        return new Capabilities(false, true, true);
                    }

                    public ExecutionResult startExecution(ExecutionRequest request) {
                        starts.incrementAndGet();
                        throw new IllegalStateException("External start response lost");
                    }

                    public ExecutionResult query(ExecutionRequest request, String handle) {
                        queries.incrementAndGet();
                        return new ExecutionResult(
                                ExecutionState.SUCCEEDED,
                                "external",
                                DurableJson.value("tool output"),
                                null);
                    }

                    public ExecutionResult cancel(ExecutionRequest request, String handle) {
                        return query(request, handle);
                    }
                };
        Model model =
                new Model() {
                    public String getModelName() {
                        return "offline-mq";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        modelCalls.incrementAndGet();
                        boolean applied =
                                messages.stream()
                                        .flatMap(
                                                message ->
                                                        message
                                                                .getContentBlocks(
                                                                        ToolResultBlock.class)
                                                                .stream())
                                        .anyMatch(result -> !result.isSuspended());
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                applied
                                                        ? List.of(
                                                                TextBlock.builder()
                                                                        .text("done")
                                                                        .build())
                                                        : List.of(
                                                                ToolUseBlock.builder()
                                                                        .id("call")
                                                                        .name("echo")
                                                                        .input(Map.of("value", 7))
                                                                        .build()))
                                        .build());
                    }
                };
        var adapter =
                AgentScopeAdapter.forReAct(
                        binding -> {
                            var toolkit = new Toolkit();
                            toolkit.registerSchema(
                                    ToolSchema.builder()
                                            .name("echo")
                                            .description("remote tool")
                                            .parameters(
                                                    Map.of(
                                                            "type",
                                                            "object",
                                                            "properties",
                                                            Map.of(
                                                                    "value",
                                                                    Map.of("type", "integer"))))
                                            .build());
                            return ReActAgent.builder()
                                    .name("agent")
                                    .model(model)
                                    .toolkit(toolkit)
                                    .permissionContext(
                                            PermissionContextState.builder()
                                                    .mode(PermissionMode.BYPASS)
                                                    .build());
                        },
                        Map.of("echo", "hands"));
        try (var broker = new Broker();
                var brain = open(directory.resolve("brain.db"));
                var hands = open(directory.resolve("hands.db"))) {
            var backend = new TransactionalSessionBackend(brain).withWakeups(Wakeups.local());
            var commands = broker.consumer(true);
            var events = broker.consumer(false);
            broker.lostCommandResponse.set(1);
            broker.failedEventPublish.set(1);
            broker.failedEventAck.set(1);
            try (var worker =
                            RocketMqWorker.builder()
                                    .executor("hands", provider)
                                    .recordStore(hands)
                                    .clients(
                                            FACTORY,
                                            broker.producer,
                                            commands,
                                            broker.consumer(TOPOLOGY.controls()))
                                    .authorize(
                                            command ->
                                                    assertEquals(
                                                            "tenant",
                                                            command.request()
                                                                    .session()
                                                                    .namespace()))
                                    .pollInterval(POLL)
                                    .onError(errors::add)
                                    .build();
                    var executor =
                            RocketMqRemoteExecutor.builder()
                                    .clients(FACTORY, broker.producer, events)
                                    .pollInterval(POLL)
                                    .retryInterval(Duration.ofMillis(40))
                                    .onError(errors::add)
                                    .build();
                    var runtime =
                            DurableRuntime.builder()
                                    .backend(backend)
                                    .harness("agent", "v1", adapter)
                                    .executor("hands", executor)
                                    .pollInterval(POLL)
                                    .build()) {
                var session = new SessionKey("tenant", "session");
                runtime.createSession(session, "agent").mainThread().view().definition();
                var turn = runtime.getSession(session).submit("request", "Use echo");
                worker.start();
                runtime.start();
                await(
                        () ->
                                runtime.getSession(session).getTurn(turn.turnId()).status().status()
                                        == TurnStatus.SUCCEEDED);
                await(() -> broker.eventAcks.get() > 0);
                assertEquals(
                        "done",
                        runtime.getSession(session)
                                .getTurn(turn.turnId())
                                .status()
                                .output()
                                .path("content")
                                .get(0)
                                .path("text")
                                .asText());
                assertEquals(1, starts.get());
                assertEquals(1, queries.get());
                assertEquals(2, modelCalls.get());
                assertTrue(executor.capabilities().output());
                assertNull(runtime.lastFailure(new ThreadKey(session, "main")));
                assertTrue(
                        errors.stream()
                                .anyMatch(error -> error.getMessage().contains("unconfirmed")));
                executor.close();
                assertThrows(IllegalStateException.class, () -> executor.startExecution(request));
            }
            verify(commands, never()).close();
            verify(events, never()).close();
            verify(broker.producer, never()).close();
            assertFalse(hands.keys("execution-pool:", null, 10).isEmpty());
        }
    }

    @Test
    void malformedMessagesAreAcknowledgedOnlyAfterDurableQuarantine() throws Exception {
        var unavailable = new AtomicBoolean(true);
        var rejected = new AtomicInteger();
        try (var broker = new Broker();
                var database = open(directory.resolve("quarantine.db"))) {
            RecordStore records =
                    new RecordStore() {
                        public <T> T transaction(String scope, Function<Transaction, T> action) {
                            if (scope.startsWith("mq-rejected:") && unavailable.get())
                                throw new DurableException(
                                        DurableException.Code.UNAVAILABLE,
                                        "quarantine DB unavailable");
                            return database.transaction(scope, action);
                        }

                        public List<Work> ready(String queue, int limit) {
                            return database.ready(queue, limit);
                        }

                        public List<String> keys(String prefix, String after, int limit) {
                            return database.keys(prefix, after, limit);
                        }
                    };
            var provider = mock(Executor.class);
            try (var worker =
                    RocketMqWorker.builder()
                            .executor("hands", provider)
                            .recordStore(records)
                            .clients(
                                    FACTORY,
                                    broker.producer,
                                    broker.consumer(true),
                                    broker.consumer(TOPOLOGY.controls()))
                            .authorize(command -> {})
                            .onError(error -> rejected.incrementAndGet())
                            .pollInterval(POLL)
                            .build()) {
                worker.start();
                broker.send(Map.of("invalid", "payload"));
                await(() -> rejected.get() > 0);
                assertEquals(0, broker.commandAcks.get());
                assertTrue(database.keys("execution-pool:", null, 10).isEmpty());
                unavailable.set(false);
                await(() -> broker.commandAcks.get() > 0);
                var quarantines =
                        new RocketMqRejections(database, TOPOLOGY.commands()).list(null, 10);
                assertEquals(1, quarantines.size());
                assertEquals("invalid_message", quarantines.get(0).reason());
                assertEquals(
                        DurableJson.value(Map.of("invalid", "payload")).toString(),
                        new String(
                                Base64.getDecoder().decode(quarantines.get(0).bodyBase64()),
                                StandardCharsets.UTF_8));
                verify(provider, never()).startExecution(any());
                assertTrue(worker.drain(Duration.ofSeconds(2)));
                assertFalse(worker.status().accepting());
            }
        }
    }

    @Test
    void authorizationPrecedesAdmissionAndCancellationTombstoneSurvivesLateExecute()
            throws Exception {
        var allow = new AtomicBoolean();
        var denied = new AtomicInteger();
        var provider = mock(Executor.class);
        when(provider.capabilities()).thenReturn(new Executor.Capabilities(true, true, true));
        try (var broker = new Broker();
                var records = open(directory.resolve("hands.db"));
                var worker =
                        RocketMqWorker.builder()
                                .executor("hands", provider)
                                .recordStore(records)
                                .clients(
                                        FACTORY,
                                        broker.producer,
                                        broker.consumer(true),
                                        broker.consumer(TOPOLOGY.controls()))
                                .authorize(
                                        command -> {
                                            if (!allow.get()) throw new SecurityException("Denied");
                                        })
                                .onError(error -> denied.incrementAndGet())
                                .pollInterval(POLL)
                                .build()) {
            worker.start();
            broker.send(ExecutionMessages.command(Kind.CANCEL, request));
            await(() -> denied.get() > 0);
            assertTrue(records.keys("execution-pool:", null, 10).isEmpty());
            assertEquals(1, broker.commandAcks.get());
            assertEquals(
                    "unauthorized",
                    new RocketMqRejections(records, TOPOLOGY.controls())
                            .list(null, 10)
                            .get(0)
                            .reason());
            allow.set(true);
            broker.send(ExecutionMessages.command(Kind.CANCEL, request));
            await(() -> broker.commandAcks.get() > 1);
            broker.send(ExecutionMessages.command(Kind.EXECUTE, request));
            await(() -> broker.commandAcks.get() > 2);
            assertEquals(
                    ExecutionState.CANCELLED,
                    new TransactionalExecutionStore(records, "hands").query(request).state());
            verify(provider, never()).startExecution(any());
        }
    }

    @Test
    void twoWorkersDeduplicateCommandsAndAcknowledgeBeforeToolCompletion() throws Exception {
        var starts = new AtomicInteger();
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        Executor provider =
                new Executor() {
                    public Capabilities capabilities() {
                        return new Capabilities(false, true, true);
                    }

                    public ExecutionResult startExecution(ExecutionRequest request) {
                        starts.incrementAndGet();
                        entered.countDown();
                        try {
                            if (!finish.await(5, TimeUnit.SECONDS))
                                throw new IllegalStateException("Timeout");
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(error);
                        }
                        return new ExecutionResult(
                                ExecutionState.SUCCEEDED,
                                "original",
                                DurableJson.value("done"),
                                null);
                    }

                    public ExecutionResult query(ExecutionRequest request, String handle) {
                        throw new AssertionError("Unexpected query");
                    }

                    public ExecutionResult cancel(ExecutionRequest request, String handle) {
                        return query(request, handle);
                    }
                };
        try (var broker = new Broker();
                var firstStore = open(directory.resolve("pool.db"));
                var secondStore = open(directory.resolve("pool.db"));
                var first =
                        RocketMqWorker.builder()
                                .executor("hands", provider)
                                .recordStore(firstStore)
                                .clients(
                                        FACTORY,
                                        broker.producer,
                                        broker.consumer(true),
                                        broker.consumer(TOPOLOGY.controls()))
                                .authorize(command -> {})
                                .pollInterval(POLL)
                                .build();
                var second =
                        RocketMqWorker.builder()
                                .executor("hands", provider)
                                .recordStore(secondStore)
                                .clients(
                                        FACTORY,
                                        broker.producer,
                                        broker.consumer(true),
                                        broker.consumer(TOPOLOGY.controls()))
                                .authorize(command -> {})
                                .pollInterval(POLL)
                                .build()) {
            first.start();
            second.start();
            broker.send(ExecutionMessages.command(Kind.EXECUTE, request));
            broker.send(ExecutionMessages.command(Kind.EXECUTE, request));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            await(() -> broker.commandAcks.get() >= 2);
            assertEquals(1, starts.get());
            finish.countDown();
            var ledger = new TransactionalExecutionStore(secondStore, "hands");
            await(() -> ledger.query(request).state() == ExecutionState.SUCCEEDED);
            assertEquals(1, starts.get());
        } finally {
            finish.countDown();
        }
    }
}
