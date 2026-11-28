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
package io.agentscope.extensions.durable.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableRuntime;
import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.ExecutionResult;
import io.agentscope.durable.DurableTypes.ExecutionState;
import io.agentscope.durable.DurableTypes.PublicEvent;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.TurnStatus;
import io.agentscope.durable.ExecutionMessages;
import io.agentscope.durable.ExecutionMessages.Kind;
import io.agentscope.durable.Executor;
import io.agentscope.durable.LiveEvents;
import io.agentscope.durable.TransactionalSessionBackend;
import io.agentscope.durable.agentscope.AgentScopeAdapter;
import io.agentscope.durable.codec.agentscope.AgentScopeCheckpointCodec;
import io.agentscope.durable.worker.QueuedExecutor;
import io.agentscope.durable.worker.TransactionalExecutionStore;
import io.agentscope.durable.worker.WorkerRuntime;
import io.agentscope.extensions.durable.jdbc.JdbcRecordStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class DurableHttpRecoveryTest {
    private static final Duration POLL = Duration.ofMillis(10);
    private static final long AWAIT_DEADLINE_SECONDS = 30;

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
                                    "durable_http_recovery_"
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

    @Test
    void apiRestartPreservesAnActiveTurnAndSseResumesFromItsCommittedCursor() throws Exception {
        var enteredHands = new CountDownLatch(1);
        var finishTool = new CountDownLatch(1);
        var provider = mock(Executor.class);
        when(provider.capabilities()).thenReturn(new Executor.Capabilities(true, false, false));
        when(provider.startExecution(any()))
                .thenAnswer(
                        invocation -> {
                            ExecutionRequest execution = invocation.getArgument(0);
                            enteredHands.countDown();
                            assertTrue(
                                    finishTool.await(AWAIT_DEADLINE_SECONDS, TimeUnit.SECONDS),
                                    "API replacement did not release the tool");
                            return new ExecutionResult(
                                    ExecutionState.SUCCEEDED,
                                    null,
                                    execution.call().arguments().path("value"),
                                    null);
                        });
        var model = mock(Model.class);
        when(model.getModelName()).thenReturn("offline-restart");
        when(model.stream(any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            List<Msg> messages = invocation.getArgument(0);
                            var result =
                                    messages.stream()
                                            .flatMap(
                                                    m ->
                                                            m
                                                                    .getContentBlocks(
                                                                            ToolResultBlock.class)
                                                                    .stream())
                                            .filter(block -> !block.isSuspended())
                                            .findFirst();
                            return Flux.just(
                                    ChatResponse.builder()
                                            .content(
                                                    result.isPresent()
                                                            ? List.of(
                                                                    result.get().getOutput().get(0))
                                                            : List.of(
                                                                    ToolUseBlock.builder()
                                                                            .id("call")
                                                                            .name("echo")
                                                                            .input(
                                                                                    Map.of(
                                                                                            "value",
                                                                                            "hands"
                                                                                                + " reply"))
                                                                            .build()))
                                            .build());
                        });
        var adapter =
                AgentScopeAdapter.forReAct(
                        binding -> {
                            var toolkit = new Toolkit();
                            toolkit.registerSchema(
                                    ToolSchema.builder()
                                            .name("echo")
                                            .description("Echo the input")
                                            .parameters(
                                                    Map.of(
                                                            "type",
                                                            "object",
                                                            "properties",
                                                            Map.of(
                                                                    "value",
                                                                    Map.of("type", "string"))))
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
        var session = new SessionKey("tenant", "session");
        var database = directory.resolve("brain.db");
        var http = HttpClient.newHttpClient();
        var errors = new CopyOnWriteArrayList<Throwable>();
        try (var brain = open(database);
                var hands = open(directory.resolve("hands.db"))) {
            var backend = new TransactionalSessionBackend(brain);
            var ledger = new TransactionalExecutionStore(hands, "hands");
            var executor =
                    new QueuedExecutor(
                            "hands",
                            brain,
                            backend,
                            batch ->
                                    batch.requests()
                                            .forEach(
                                                    call ->
                                                            ledger.receive(
                                                                    ExecutionMessages.command(
                                                                            Kind.EXECUTE, call))),
                            ledger::receive,
                            POLL,
                            Duration.ofSeconds(5),
                            LiveEvents.none());
            var transport = Executors.newSingleThreadScheduledExecutor();
            try (var worker =
                            WorkerRuntime.builder()
                                    .executionStore(ledger)
                                    .executor("hands", provider)
                                    .pollInterval(POLL)
                                    .onError(errors::add)
                                    .build();
                    var runtime =
                            DurableRuntime.builder()
                                    .backend(backend)
                                    .harness("agent", "v1", adapter)
                                    .executor("hands", executor)
                                    .pollInterval(POLL)
                                    .build()) {
                // An in-process transport pumps the same persisted commands and events as a broker.
                transport.scheduleWithFixedDelay(
                        () -> {
                            executor.tick(errors::add);
                            try {
                                for (var key : ledger.pendingPublications(100)) {
                                    ledger.pendingEvent(key)
                                            .ifPresent(
                                                    event -> {
                                                        executor.receive(event);
                                                        ledger.published(event);
                                                    });
                                }
                            } catch (RuntimeException error) {
                                errors.add(error);
                            }
                        },
                        0,
                        POLL.toMillis(),
                        TimeUnit.MILLISECONDS);
                worker.start();
                runtime.start();
                String turnId;
                List<PublicEvent> beforeRestart;
                try (var store = open(database);
                        var api = AcceptanceApi.start(store)) {
                    var turn =
                            api.client()
                                    .createSession(session, "agent")
                                    .submit("request", "Use echo");
                    turnId = turn.turnId();
                    assertTrue(
                            enteredHands.await(AWAIT_DEADLINE_SECONDS, TimeUnit.SECONDS),
                            () -> "Hands did not start: " + errors);
                    beforeRestart = api.replay(http, null);
                    assertTrue(
                            beforeRestart.stream()
                                    .anyMatch(event -> event.type().equals("tool.requested")));
                    assertFalse(turn.status().status().terminal());
                }

                try (var store = open(database);
                        var api = AcceptanceApi.start(store)) {
                    var restored = api.client().getSession(session);
                    var turn = restored.submit("request", "Use echo");
                    assertEquals(turnId, turn.turnId());
                    assertFalse(turn.status().status().terminal());
                    assertFalse(turn.status().cancellationRequested());
                    finishTool.countDown();
                    var settled =
                            turn.await(Duration.ofSeconds(AWAIT_DEADLINE_SECONDS))
                                    .toCompletableFuture()
                                    .get(AWAIT_DEADLINE_SECONDS + 5, TimeUnit.SECONDS);
                    assertEquals(TurnStatus.SUCCEEDED, settled.status(), settled.error());
                    assertEquals(
                            "hands reply",
                            settled.output().path("content").get(0).path("text").asText());
                    var resumed =
                            api.replay(http, beforeRestart.get(beforeRestart.size() - 1).cursor());
                    assertTrue(
                            resumed.stream()
                                    .anyMatch(event -> event.type().equals("turn.succeeded")));
                    var replayed = new ArrayList<>(beforeRestart);
                    replayed.addAll(resumed);
                    assertEquals(restored.listEvents(null, 100).events(), replayed);
                    assertEquals(
                            1,
                            replayed.stream()
                                    .filter(event -> event.type().equals("turn.queued"))
                                    .count());
                    assertEquals(
                            1,
                            restored.history(null, 100).items().stream()
                                    .filter(
                                            item ->
                                                    item.path("requestId")
                                                            .asText()
                                                            .equals("request"))
                                    .count());
                    verify(provider, times(1)).startExecution(any());
                    assertTrue(errors.isEmpty(), () -> "Tool transport errors: " + errors);
                }
            } finally {
                transport.shutdownNow();
                assertTrue(transport.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    private record AcceptanceApi(
            HttpServer server, ExecutorService requests, DurableRuntime host, DurableRuntime client)
            implements AutoCloseable {
        static AcceptanceApi start(JdbcRecordStore store) throws IOException {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var host =
                    DurableRuntime.builder()
                            .backend(new TransactionalSessionBackend(store))
                            .definition("agent", "v1", AgentScopeCheckpointCodec.STATE_VERSION)
                            .build();
            var requests = Executors.newCachedThreadPool();
            DurableRuntimeHandler.Authorizer authorizer =
                    (exchange, operation, namespace, session) -> {};
            server.createContext("/api", new DurableRuntimeHandler(host, authorizer));
            server.createContext(
                    "/events",
                    new SessionEventHandler(host, authorizer, Duration.ofSeconds(1), POLL));
            server.setExecutor(requests);
            server.start();
            var client =
                    DurableRuntime.builder()
                            .transport(new DurableHttp(base(server).resolve("/api")))
                            .pollInterval(POLL)
                            .build();
            return new AcceptanceApi(server, requests, host, client);
        }

        List<PublicEvent> replay(HttpClient http, String cursor) throws Exception {
            var request =
                    HttpRequest.newBuilder(
                                    base(server)
                                            .resolve("/events?namespace=tenant&sessionId=session"))
                            .timeout(Duration.ofSeconds(10));
            if (cursor != null) request.header("Last-Event-ID", cursor);
            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            var events =
                    response.body()
                            .lines()
                            .filter(line -> line.startsWith("data: "))
                            .map(
                                    line ->
                                            DurableJson.convert(
                                                    DurableJson.parse(
                                                            line.substring("data: ".length())),
                                                    PublicEvent.class))
                            .toList();
            assertEquals(
                    events.stream().map(event -> "id: " + event.cursor()).toList(),
                    response.body().lines().filter(line -> line.startsWith("id: ")).toList());
            return events;
        }

        private static URI base(HttpServer server) {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        @Override
        public void close() {
            client.close();
            server.stop(0);
            requests.shutdownNow();
            host.close();
        }
    }
}
