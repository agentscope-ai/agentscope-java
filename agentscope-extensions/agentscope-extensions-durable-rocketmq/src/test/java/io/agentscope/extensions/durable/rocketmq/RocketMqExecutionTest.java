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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
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
import io.agentscope.durable.DurableTurn;
import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.ExecutionResult;
import io.agentscope.durable.DurableTypes.ExecutionState;
import io.agentscope.durable.DurableTypes.RunStepRef;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.DurableTypes.ToolCall;
import io.agentscope.durable.DurableTypes.ToolExecutionStatus;
import io.agentscope.durable.DurableTypes.TurnStatus;
import io.agentscope.durable.ExecutionMessages;
import io.agentscope.durable.ExecutionMessages.Command;
import io.agentscope.durable.ExecutionMessages.ExecuteBatch;
import io.agentscope.durable.ExecutionMessages.Kind;
import io.agentscope.durable.Executor;
import io.agentscope.durable.TransactionalSessionBackend;
import io.agentscope.durable.Wakeups;
import io.agentscope.durable.agentscope.AgentScopeAdapter;
import io.agentscope.durable.store.RecordStore;
import io.agentscope.durable.worker.TransactionalExecutionStore;
import io.agentscope.extensions.durable.jdbc.FileObjectStore;
import io.agentscope.extensions.durable.jdbc.JdbcRecordStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
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

    /** Redelivery window for the fake broker; it has to stay well above consumer latency. */
    static final long ACK_TIMEOUT_MILLIS = 1000;

    /** Shared await budget for every {@link #await} poll in this class; see {@link #waitUntil}. */
    static final long AWAIT_DEADLINE_SECONDS = 30;

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
        assertTrue(waitUntil(condition), "Condition did not become true");
    }

    /** Waits as long as {@link #await(BooleanSupplier)}, then fails with the caller's snapshot. */
    static void await(String what, BooleanSupplier condition, Supplier<String> diagnostics)
            throws Exception {
        assertTrue(
                waitUntil(condition),
                () ->
                        what
                                + " did not become true within "
                                + AWAIT_DEADLINE_SECONDS
                                + "s: "
                                + diagnostics.get());
    }

    /**
     * Recovery with injected broker failures needs far more than 10s when the runner is starved
     * under -T1C: the turn kept progressing (modelCalls complete, acks climbing) yet missed the
     * former 10s deadline on #4182, #4192 and #4103, and main's own run 44516464414 (2026-11-19,
     * ubuntu-only). 30s is the repo's established deflake tier (#3726/#3774).
     */
    private static boolean waitUntil(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_DEADLINE_SECONDS);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        return condition.getAsBoolean();
    }

    private static String recoveryDiagnostics(
            DurableRuntime runtime, DurableTurn turn, Broker broker, AtomicInteger modelCalls) {
        StringBuilder out = new StringBuilder("turnId=").append(turn.turnId());
        try {
            out.append(", turn.status=").append(turn.status().status());
        } catch (RuntimeException e) {
            out.append(", turn.status=<unreadable: ").append(e).append('>');
        }
        try {
            Throwable failure = runtime.lastFailure(turn.threadKey());
            out.append(", lastFailure=").append(failure == null ? "none" : failure.toString());
        } catch (RuntimeException e) {
            out.append(", lastFailure=<unreadable: ").append(e).append('>');
        }
        return out.append(", modelCalls=")
                .append(modelCalls.get())
                .append(", commandAcks=")
                .append(broker.commandAcks.get())
                .append(", eventAcks=")
                .append(broker.eventAcks.get())
                .append(", queuedEvents=")
                .append(broker.events.size())
                .toString();
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
        final List<ExecuteBatch> dispatches = new CopyOnWriteArrayList<>();

        Broker() throws Exception {
            when(producer.send(any()))
                    .thenAnswer(
                            call -> {
                                var message = (Message) call.getArgument(0);
                                boolean command = !message.getTopic().equals(TOPOLOGY.updates());
                                if (message.getTopic().equals(TOPOLOGY.commands())) {
                                    var value =
                                            DurableJson.parse(
                                                    StandardCharsets.UTF_8
                                                            .decode(
                                                                    message.getBody()
                                                                            .asReadOnlyBuffer())
                                                            .toString());
                                    if (value.has("requests"))
                                        dispatches.add(
                                                DurableJson.convert(value, ExecuteBatch.class));
                                }
                                if (!command
                                        && failedEventPublish.getAndUpdate(n -> Math.max(0, n - 1))
                                                > 0)
                                    throw new IllegalStateException("Event publication failed");
                                var view = mock(MessageView.class);
                                when(view.getTopic()).thenReturn(message.getTopic());
                                when(view.getLiteTopic()).thenReturn(message.getLiteTopic());
                                when(view.getKeys()).thenReturn(message.getKeys());
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

        RocketMqLoop.Subscription consumer(boolean command) {
            return consumer(command ? TOPOLOGY.commands() : TOPOLOGY.updates());
        }

        RocketMqLoop.Subscription consumer(String topic) {
            boolean command = !topic.equals(TOPOLOGY.updates());
            var queue = topic.equals(TOPOLOGY.controls()) ? controls : command ? commands : events;
            return listener -> {
                var receiver = Executors.newSingleThreadExecutor();
                receiver.execute(
                        () -> {
                            try {
                                while (!Thread.currentThread().isInterrupted()) {
                                    var message = queue.take();
                                    var result = listener.consume(message);
                                    boolean lost =
                                            !command
                                                    && result == ConsumeResult.SUCCESS
                                                    && failedEventAck.getAndUpdate(
                                                                    n -> Math.max(0, n - 1))
                                                            > 0;
                                    if (result == ConsumeResult.SUCCESS && !lost) {
                                        acked.add(message);
                                        (command ? commandAcks : eventAcks).incrementAndGet();
                                    } else {
                                        redelivery.schedule(
                                                () -> queue.add(message),
                                                ACK_TIMEOUT_MILLIS,
                                                TimeUnit.MILLISECONDS);
                                    }
                                }
                            } catch (InterruptedException stopped) {
                                Thread.currentThread().interrupt();
                            }
                        });
                return receiver::shutdownNow;
            };
        }

        void send(Object command) throws Exception {
            String route = "malformed";
            String key = "malformed";
            if (command instanceof Command control) {
                route = RocketMqLiteRouting.session(control.request().session());
                key = control.commandId();
            } else if (command instanceof ExecuteBatch batch) {
                route = RocketMqLiteRouting.session(batch.requests().get(0).session());
                key = batch.batchId();
            }
            producer.send(
                    FACTORY.newMessageBuilder()
                            .setTopic(
                                    command instanceof Command
                                            ? TOPOLOGY.controls()
                                            : TOPOLOGY.commands())
                            .setLiteTopic(route)
                            .setKeys(key)
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
    void agentScopeBatchesOneModelActionAndResumesDespitePublicationAndAckFailures()
            throws Exception {
        var starts = new AtomicInteger();
        var queries = new AtomicInteger();
        var modelCalls = new AtomicInteger();
        var appliedCalls = new AtomicReference<List<ToolResultBlock>>();
        var approvalRevoked = new AtomicBoolean();
        var errors = new CopyOnWriteArrayList<Throwable>();
        Executor provider =
                new Executor() {
                    public Capabilities capabilities() {
                        return new Capabilities(false, true, true);
                    }

                    public ExecutionResult startExecution(ExecutionRequest request) {
                        assertFalse("revoked".equals(request.call().callId()));
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
                        var applied =
                                messages.stream()
                                        .flatMap(
                                                message ->
                                                        message
                                                                .getContentBlocks(
                                                                        ToolResultBlock.class)
                                                                .stream())
                                        .filter(result -> !result.isSuspended())
                                        .toList();
                        if (!applied.isEmpty()) appliedCalls.set(applied);
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                !applied.isEmpty()
                                                        ? List.of(
                                                                TextBlock.builder()
                                                                        .text("done")
                                                                        .build())
                                                        : List.of(
                                                                ToolUseBlock.builder()
                                                                        .id("call")
                                                                        .name("echo")
                                                                        .input(Map.of("value", 7))
                                                                        .build(),
                                                                ToolUseBlock.builder()
                                                                        .id("revoked")
                                                                        .name("echo")
                                                                        .input(Map.of("value", 9))
                                                                        .build(),
                                                                ToolUseBlock.builder()
                                                                        .id("call-2")
                                                                        .name("echo")
                                                                        .input(Map.of("value", 8))
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
                                            command -> {
                                                assertEquals(
                                                        "tenant",
                                                        command.request().session().namespace());
                                                if ("revoked"
                                                        .equals(command.request().call().callId()))
                                                    approvalRevoked.set(true);
                                            })
                                    .authorizeStart(
                                            request ->
                                                    approvalRevoked.get()
                                                                    && "revoked"
                                                                            .equals(
                                                                                    request.call()
                                                                                            .callId())
                                                            ? Optional.of(
                                                                    "Resource permission revoked")
                                                            : Optional.empty())
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
                        "turn reaches SUCCEEDED",
                        () ->
                                runtime.getSession(session).getTurn(turn.turnId()).status().status()
                                        == TurnStatus.SUCCEEDED,
                        () -> recoveryDiagnostics(runtime, turn, broker, modelCalls));
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
                assertEquals(2, starts.get());
                assertEquals(2, queries.get());
                assertEquals(2, modelCalls.get());
                assertEquals(
                        List.of("call", "revoked", "call-2"),
                        appliedCalls.get().stream().map(ToolResultBlock::getId).toList());
                assertEquals(ToolResultState.DENIED, appliedCalls.get().get(1).getState());
                assertEquals(
                        "Error: Resource permission revoked",
                        ((TextBlock) appliedCalls.get().get(1).getOutput().get(0)).getText());
                assertEquals(ToolExecutionStatus.DENIED, turn.getToolCall("revoked").status());
                assertTrue(turn.getToolCall("revoked").applied());
                assertFalse(broker.dispatches.isEmpty());
                assertEquals(1, broker.dispatches.stream().distinct().count());
                assertEquals(
                        List.of("call", "call-2", "revoked"),
                        broker.dispatches.get(0).requests().stream()
                                .map(req -> req.call().callId())
                                .sorted()
                                .toList());
                assertTrue(executor.capabilities().output());
                assertNull(runtime.lastFailure(new ThreadKey(session, "main")));
                assertTrue(
                        errors.stream()
                                .anyMatch(error -> error.getMessage().contains("unconfirmed")));
                executor.close();
                assertThrows(IllegalStateException.class, () -> executor.startExecution(request));
            }
            verify(broker.producer, never()).close();
            assertFalse(hands.keys("execution-pool:", null, 10).isEmpty());
        }
    }

    @Test
    void largeBatchAcknowledgementWaitsForEveryChildAndRedeliveryDoesNotRepeatEffects()
            throws Exception {
        var arguments = DurableJson.value(Map.of("value", "x".repeat(RocketMqLoop.MAX_BYTES / 2)));
        var first =
                new ExecutionRequest(
                        request.executionId(),
                        request.thread(),
                        request.turnId(),
                        new ToolCall("call", "hands", "echo", arguments)
                                .withOrigin(request.call().origin()));
        var second =
                new ExecutionRequest(
                        "exec-second",
                        request.thread(),
                        request.turnId(),
                        new ToolCall("call-2", "hands", "echo", arguments)
                                .withOrigin(request.call().origin()));
        var pending = new AtomicBoolean(true);
        var missedAdmission = new CountDownLatch(1);
        try (var broker = new Broker();
                var database = open(directory.resolve("partial-batch.db"))) {
            var objects = new FileObjectStore(directory.resolve("objects"));
            String secondIdentity = ExecutionMessages.identity(second);
            RecordStore records =
                    new RecordStore() {
                        public <T> T transaction(String scope, Function<Transaction, T> action) {
                            if (scope.startsWith("execution-pool:")
                                    && scope.endsWith(secondIdentity)
                                    && pending.get()) {
                                missedAdmission.countDown();
                                throw new DurableException(
                                        DurableException.Code.UNAVAILABLE,
                                        "second admission interrupted");
                            }
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
            when(provider.capabilities()).thenReturn(new Executor.Capabilities(true, true, true));
            when(provider.startExecution(any()))
                    .thenReturn(
                            new ExecutionResult(
                                    ExecutionState.SUCCEEDED,
                                    null,
                                    DurableJson.value("done"),
                                    null));
            var store = new TransactionalExecutionStore(database, "hands");
            try (var worker =
                    RocketMqWorker.builder()
                            .executor("hands", provider)
                            .recordStore(records)
                            .objects(objects)
                            .clients(
                                    FACTORY,
                                    broker.producer,
                                    broker.consumer(true),
                                    broker.consumer(TOPOLOGY.controls()))
                            .authorize(command -> {})
                            .onError(error -> {})
                            .pollInterval(POLL)
                            .build()) {
                var batch = ExecutionMessages.batch(List.of(first, second));
                Runnable publish =
                        () ->
                                RocketMqLoop.send(
                                        FACTORY,
                                        broker.producer,
                                        TOPOLOGY.commands(),
                                        RocketMqLiteRouting.session(request.session()),
                                        batch.batchId(),
                                        batch,
                                        objects);
                publish.run();
                assertTrue(
                        DurableJson.parse(
                                        StandardCharsets.UTF_8
                                                .decode(broker.commands.element().getBody())
                                                .toString())
                                .has("objectReference"));
                worker.start();
                assertTrue(missedAdmission.await(AWAIT_DEADLINE_SECONDS, TimeUnit.SECONDS));
                assertEquals(0, broker.commandAcks.get());
                assertEquals(1, store.keys(null, 10).size());
                pending.set(false);
                await(() -> broker.commandAcks.get() == 1);
                await(
                        () ->
                                store.query(first).state().terminal()
                                        && store.query(second).state().terminal());
                publish.run();
                await(() -> broker.commandAcks.get() == 2);
                verify(provider).startExecution(first);
                verify(provider).startExecution(second);
            }
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
            broker.send(ExecutionMessages.batch(List.of(request)));
            await(() -> broker.commandAcks.get() > 2);
            assertEquals(
                    ExecutionState.CANCELLED,
                    new TransactionalExecutionStore(records, "hands").query(request).state());
            verify(provider, never()).startExecution(any());
        }
    }

    @Test
    void durableControlInterruptsLocallyRunningProviderAndUsesItsConfirmedOutcome()
            throws Exception {
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        Executor provider =
                new Executor() {
                    public Capabilities capabilities() {
                        return new Capabilities(false, true, true);
                    }

                    public ExecutionResult startExecution(ExecutionRequest request) {
                        started.countDown();
                        try {
                            Thread.sleep(10_000);
                            throw new AssertionError("Running provider was not interrupted");
                        } catch (InterruptedException error) {
                            interrupted.countDown();
                            return new ExecutionResult(
                                    ExecutionState.UNKNOWN, "external", null, "interrupted");
                        }
                    }

                    public ExecutionResult query(ExecutionRequest request, String handle) {
                        return new ExecutionResult(ExecutionState.RUNNING, "external", null, null);
                    }

                    public ExecutionResult cancel(ExecutionRequest request, String handle) {
                        return new ExecutionResult(
                                ExecutionState.CANCELLED, "external", null, null);
                    }
                };
        try (var broker = new Broker();
                var records = open(directory.resolve("cancel-running.db"));
                var worker =
                        RocketMqWorker.builder()
                                .executor("hands", provider)
                                .recordStore(records)
                                .clients(
                                        FACTORY,
                                        broker.producer,
                                        broker.consumer(true),
                                        broker.consumer(TOPOLOGY.controls()))
                                .authorize(command -> {})
                                .pollInterval(Duration.ofSeconds(1))
                                .build()) {
            worker.start();
            broker.send(ExecutionMessages.batch(List.of(request)));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            broker.send(ExecutionMessages.command(Kind.CANCEL, request));
            assertTrue(interrupted.await(4, TimeUnit.SECONDS));
            var ledger = new TransactionalExecutionStore(records, "hands");
            await(() -> ledger.query(request).state() == ExecutionState.CANCELLED);
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
            broker.send(ExecutionMessages.batch(List.of(request)));
            broker.send(ExecutionMessages.batch(List.of(request)));
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
