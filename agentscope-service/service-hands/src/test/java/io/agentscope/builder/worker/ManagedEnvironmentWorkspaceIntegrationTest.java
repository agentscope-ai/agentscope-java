/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.worker;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.builder.control.SessionResolveResult;
import io.agentscope.builder.hands.ManagedHandsTestPipeline;
import io.agentscope.builder.hands.ManagedSandboxToolExecutor;
import io.agentscope.builder.hands.ManagedWorkerService;
import io.agentscope.builder.runtime.ManagedCatalog;
import io.agentscope.builder.runtime.managed.InMemoryManagedLeaseStore;
import io.agentscope.builder.runtime.managed.InMemoryManagedMessageBus;
import io.agentscope.builder.runtime.managed.ManagedAdmissionStore;
import io.agentscope.builder.runtime.managed.ManagedBatchCodec;
import io.agentscope.builder.runtime.managed.ManagedBrainToolEvents;
import io.agentscope.builder.runtime.managed.ManagedCheckpointLoop;
import io.agentscope.builder.runtime.managed.ManagedCheckpointObjects;
import io.agentscope.builder.runtime.managed.ManagedCheckpointOperations;
import io.agentscope.builder.runtime.managed.ManagedHandsStore;
import io.agentscope.builder.runtime.managed.ManagedJournalStore;
import io.agentscope.builder.runtime.managed.ManagedMessageBus.Channel;
import io.agentscope.builder.runtime.managed.ManagedRuntimeBatch;
import io.agentscope.builder.runtime.managed.ManagedScope;
import io.agentscope.builder.runtime.managed.ManagedToolCommand;
import io.agentscope.builder.runtime.managed.ManagedToolResultPublisher;
import io.agentscope.builder.runtime.managed.ManagedWorkerProtocol;
import io.agentscope.builder.runtime.managed.RedisManagedInfrastructure;
import io.agentscope.builder.web.managed.EnvironmentDto;
import io.agentscope.builder.web.managed.ManagedSessionDto;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.state.AgentState;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Real outbound HTTP and local Sandbox processes; the cloud connector and Redis are explicit fakes. */
@EnabledOnOs({OS.LINUX, OS.MAC})
@Timeout(60)
class ManagedEnvironmentWorkspaceIntegrationTest {
    @TempDir private Path managedCheckpointRoot;

    @TempDir Path root;

    @Test
    void persistedResultsSurviveWorkerReplacementAndImportTheOriginalSnapshot() throws Exception {
        var mapper = new ObjectMapper();
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:outbound-workspace-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        ManagedAdmissionStore.initialize(source);
        var from = new ManagedScope("tenant", "namespace", "from");
        var to = new ManagedScope("tenant", "namespace", "to");
        var admission = new ManagedAdmissionStore(source, mapper);
        var journal =
                new ManagedJournalStore(
                        source,
                        mapper,
                        new ManagedCheckpointObjects(mapper, managedCheckpointRoot));
        admission.createSession(from, "{}");
        admission.createSession(to, "{}");
        String turn = admission.acceptTurn(from, "actor", "seed", "{}").id();
        admission.reservePublication(from, turn);
        admission.confirmPublication(from, turn, "broker");
        admission.activate(from, turn, 1, "token");
        admission.closeInputs(from, turn, 1);
        var event =
                new SessionEvent(
                        1,
                        "checkpoint",
                        1,
                        1,
                        "state/checkpoint",
                        "run",
                        turn,
                        true,
                        mapper.writeValueAsString(
                                Map.of("stateJson", AgentState.builder().build().toJson())));
        journal.commit(
                journal.objects()
                        .prepare(
                                new ManagedRuntimeBatch(
                                        from,
                                        turn,
                                        1,
                                        "token",
                                        "writer",
                                        "seed",
                                        0,
                                        List.of(event),
                                        List.of(),
                                        List.of(),
                                        "succeeded")));
        var catalog = mock(ManagedCatalog.class);
        var sourceDefinition = definition("source-env", "source-box", from);
        var targetDefinition = definition("target-env", "target-box", to);
        when(catalog.resolve(from)).thenReturn(sourceDefinition);
        when(catalog.resolve(to)).thenReturn(targetDefinition);
        recordWorkspaceUse(source, from);
        var operations = new ManagedCheckpointOperations(source, mapper, journal);
        String checkpoint =
                (String)
                        ((Map<?, ?>)
                                        ((List<?>)
                                                        journal.checkpoints()
                                                                .list(from, null, null, 10)
                                                                .get("data"))
                                                .get(0))
                                .get("checkpoint_id");
        var command = operations.prepare(from, to, "actor", "fork", checkpoint);
        var leases = new InMemoryManagedLeaseStore(System::currentTimeMillis);
        var redis = mock(RedisManagedInfrastructure.class);
        when(redis.acquire(anyString(), any()))
                .thenAnswer(call -> leases.acquire(call.getArgument(0), call.getArgument(1)));
        when(redis.renew(any(), any()))
                .thenAnswer(call -> leases.renew(call.getArgument(0), call.getArgument(1)));
        when(redis.owns(any())).thenAnswer(call -> leases.owns(call.getArgument(0)));
        when(redis.release(any())).thenAnswer(call -> leases.release(call.getArgument(0)));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var httpThreads = Executors.newCachedThreadPool();
        server.setExecutor(httpThreads);
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        server.createContext(
                "/api/internal/environments/",
                exchange -> {
                    boolean valid =
                            mapper.readTree(exchange.getRequestBody())
                                    .path("key")
                                    .asText()
                                    .equals("worker-key");
                    byte[] body = mapper.writeValueAsBytes(Map.of("ok", valid));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
        server.start();
        var pipeline =
                new ManagedHandsTestPipeline(
                        source,
                        mapper,
                        journal,
                        catalog,
                        leases,
                        scope -> null,
                        root.resolve("blobs"));
        var workers =
                new ManagedWorkerService(
                        pipeline.targets,
                        pipeline.hands,
                        redis,
                        pipeline.results,
                        mapper,
                        source,
                        base.toString(),
                        "internal-token");
        var coordinator =
                new ManagedCheckpointLoop(
                        operations,
                        leases,
                        pipeline.administrative::executor,
                        ignored -> "owner",
                        1,
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(15),
                        (ignored, failure) -> {
                            throw failure;
                        });
        var operation = new ManagedCheckpointOperations.Operation(from, to, command.id(), "actor");
        var loseResult = new AtomicBoolean(true);
        server.createContext(
                "/v1/handss/environments/",
                exchange -> {
                    String[] path = exchange.getRequestURI().getPath().split("/");
                    String environment = path[4], action = path[6];
                    assertEquals("tool-tasks", path[5]);
                    String key =
                            exchange.getRequestHeaders()
                                    .getFirst(ManagedWorkerService.ENVIRONMENT_KEY);
                    Object reply =
                            switch (action) {
                                case "renew" ->
                                        workers.renew(
                                                environment,
                                                key,
                                                mapper.readValue(
                                                        exchange.getRequestBody(),
                                                        ManagedWorkerProtocol.Lease.class));
                                case "started" ->
                                        workers.started(
                                                environment,
                                                key,
                                                mapper.readValue(
                                                        exchange.getRequestBody(),
                                                        ManagedWorkerProtocol.Lease.class));
                                case "unknown" ->
                                        workers.unknown(
                                                environment,
                                                key,
                                                mapper.readValue(
                                                        exchange.getRequestBody(),
                                                        ManagedWorkerProtocol.Lease.class));
                                case "output" ->
                                        workers.output(
                                                environment,
                                                key,
                                                mapper.readValue(
                                                        exchange.getRequestBody(),
                                                        ManagedWorkerProtocol.Output.class));
                                case "result" ->
                                        workers.result(
                                                environment,
                                                key,
                                                mapper.readValue(
                                                        exchange.getRequestBody(),
                                                        ManagedWorkerProtocol.Result.class));
                                default ->
                                        throw new IllegalArgumentException(
                                                "Unexpected test action");
                            };
                    byte[] body = mapper.writeValueAsBytes(reply);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(
                            action.equals("result") && loseResult.compareAndSet(true, false)
                                    ? 503
                                    : 200,
                            body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
        Path sourceDirectory = root.resolve("source-sandbox"),
                targetDirectory = root.resolve("target-sandbox");
        Files.createDirectories(sourceDirectory);
        Files.createDirectories(targetDirectory);
        byte[] bytes = new byte[2 * 1024 * 1024];
        new Random(42).nextBytes(bytes);
        Files.write(sourceDirectory.resolve("data.bin"), bytes);
        ManagedEnvironmentWorker.SandboxExecutors local =
                assignment ->
                        new ManagedSandboxToolExecutor(
                                mapper,
                                text ->
                                        control(
                                                assignment.lease().scope().equals(from)
                                                        ? sourceDirectory
                                                        : targetDirectory,
                                                text));
        try {
            var capture = claim(workers, "source-env", coordinator, operation, pipeline);
            Path sourceOutbox = root.resolve("source-worker");
            try (var first =
                    new ManagedEnvironmentWorker(
                            base, "source-env", "worker-key", sourceOutbox, 1, local)) {
                assertThrows(IllegalStateException.class, () -> first.execute(capture));
                try (var files = Files.list(sourceOutbox.resolve("results"))) {
                    assertEquals(1, files.count());
                }
            }
            var restore = claim(workers, "target-env", coordinator, operation, pipeline);
            try (var replacement =
                    new ManagedEnvironmentWorker(
                            base, "source-env", "worker-key", sourceOutbox, 1, local)) {
                replacement.retryResults();
                try (var files = Files.list(sourceOutbox.resolve("results"))) {
                    assertEquals(0, files.count());
                }
            }
            Files.writeString(sourceDirectory.resolve("data.bin"), "source changed after snapshot");
            try (var destination =
                    new ManagedEnvironmentWorker(
                            base,
                            "target-env",
                            "worker-key",
                            root.resolve("target-worker"),
                            1,
                            local)) {
                destination.execute(restore);
            }
            assertArrayEquals(bytes, Files.readAllBytes(targetDirectory.resolve("data.bin")));
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (!operations.get(from, command.id()).status().equals("completed")) {
                pipeline.check();
                coordinator.execute(operation);
                assertTrue(System.nanoTime() < deadline);
                Thread.sleep(20);
            }
            assertEquals(1, journal.head(to).sequence());
        } finally {
            coordinator.close();
            pipeline.close();
            server.stop(0);
            httpThreads.shutdownNow();
        }
    }

    @Test
    void toolOutputContinuesAfterLostHttpResponseAndWorkerReplacementWithoutBrain()
            throws Exception {
        var mapper = new ObjectMapper();
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:outbound-output-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        ManagedAdmissionStore.initialize(source);
        var scope = new ManagedScope("tenant", "namespace", "output-session");
        var admission = new ManagedAdmissionStore(source, mapper);
        var journal =
                new ManagedJournalStore(
                        source,
                        mapper,
                        new ManagedCheckpointObjects(mapper, managedCheckpointRoot));
        admission.createSession(scope, "{}");
        String turn = admission.acceptTurn(scope, "actor", "request", "{}").id();
        admission.reservePublication(scope, turn);
        admission.confirmPublication(scope, turn, "broker");
        admission.activate(scope, turn, 1, "brain");
        var checkpoint =
                new SessionEvent(
                        1,
                        "checkpoint",
                        1,
                        1,
                        "state/checkpoint",
                        "run",
                        turn,
                        true,
                        "{\"stateJson\":\"{\\\"context\\\":[]}\"}");
        journal.commit(
                journal.objects()
                        .prepare(
                                new ManagedRuntimeBatch(
                                        scope,
                                        turn,
                                        1,
                                        "brain",
                                        "writer",
                                        "seed",
                                        0,
                                        List.of(checkpoint),
                                        List.of(
                                                new ManagedRuntimeBatch.ToolIntent(
                                                        "operation",
                                                        "sandbox.exec",
                                                        mapper.writeValueAsString(
                                                                Map.of(
                                                                        "native_tool_call_id",
                                                                        "native-call",
                                                                        "command",
                                                                        "echo once >> effects;"
                                                                            + " printf 'first 世界\\n"
                                                                            + "'; sleep 2; printf"
                                                                            + " 'last\\n"
                                                                            + "'",
                                                                        "timeout_seconds",
                                                                        10)))),
                                        List.of(),
                                        null)));
        var catalog = mock(ManagedCatalog.class);
        when(catalog.resolve(scope))
                .thenReturn(definition("environment", "existing-sandbox", scope));
        var clock = new AtomicLong(System.currentTimeMillis());
        var leases = new InMemoryManagedLeaseStore(clock::get);
        var redis = mock(RedisManagedInfrastructure.class);
        when(redis.acquire(anyString(), any()))
                .thenAnswer(call -> leases.acquire(call.getArgument(0), call.getArgument(1)));
        when(redis.renew(any(), any()))
                .thenAnswer(call -> leases.renew(call.getArgument(0), call.getArgument(1)));
        when(redis.owns(any())).thenAnswer(call -> leases.owns(call.getArgument(0)));
        when(redis.release(any())).thenAnswer(call -> leases.release(call.getArgument(0)));
        var mq = new InMemoryManagedMessageBus(System::currentTimeMillis, 8);
        var codec = new ManagedBatchCodec(mapper, root.resolve("tool-blobs"));
        var physical = new ManagedHandsStore(source, mapper);
        var originalTask =
                journal.tool(
                                scope,
                                "tool_"
                                        + ManagedScope.fingerprint(
                                                scope.messageGroup(), turn, "operation"))
                        .orElseThrow();
        physical.receive(
                new ManagedToolCommand(
                        scope, "execute_" + originalTask.id(), "execute", originalTask, null));
        var publisher = new ManagedToolResultPublisher(physical, mq, codec);
        var eventHandler = new ManagedBrainToolEvents(codec, journal, ignored -> {});
        var subscription =
                mq.subscribe(
                        Channel.TOOL_EVENTS,
                        2,
                        eventHandler::consume,
                        (delivery, failure) -> {
                            throw new AssertionError(failure);
                        });
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var threads = Executors.newCachedThreadPool();
        server.setExecutor(threads);
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        server.createContext(
                "/api/internal/environments/",
                exchange -> {
                    boolean valid =
                            "worker-key"
                                    .equals(
                                            mapper.readTree(exchange.getRequestBody())
                                                    .path("key")
                                                    .asText());
                    byte[] body = mapper.writeValueAsBytes(Map.of("ok", valid));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
        server.start();
        var hands =
                new ManagedWorkerService(
                        catalog,
                        physical,
                        redis,
                        publisher,
                        mapper,
                        source,
                        base.toString(),
                        "internal-token");
        var lose = new AtomicBoolean(true);
        server.createContext(
                "/v1/handss/environments/",
                exchange -> {
                    String action = exchange.getRequestURI().getPath().split("/")[6];
                    String key =
                            exchange.getRequestHeaders()
                                    .getFirst(ManagedWorkerService.ENVIRONMENT_KEY);
                    Object reply;
                    boolean fail = false;
                    switch (action) {
                        case "renew" ->
                                reply =
                                        hands.renew(
                                                "environment",
                                                key,
                                                mapper.readValue(
                                                        exchange.getRequestBody(),
                                                        ManagedWorkerProtocol.Lease.class));
                        case "started" ->
                                reply =
                                        hands.started(
                                                "environment",
                                                key,
                                                mapper.readValue(
                                                        exchange.getRequestBody(),
                                                        ManagedWorkerProtocol.Lease.class));
                        case "output" -> {
                            var body =
                                    mapper.readValue(
                                            exchange.getRequestBody(),
                                            ManagedWorkerProtocol.Output.class);
                            reply = hands.output("environment", key, body);
                            fail = !body.page().data().isEmpty() && lose.compareAndSet(true, false);
                        }
                        case "result" ->
                                reply =
                                        hands.result(
                                                "environment",
                                                key,
                                                mapper.readValue(
                                                        exchange.getRequestBody(),
                                                        ManagedWorkerProtocol.Result.class));
                        default -> throw new IllegalArgumentException(action);
                    }
                    byte[] body = mapper.writeValueAsBytes(reply);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(fail ? 503 : 200, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
        Path sandbox = root.resolve("tool-sandbox");
        Files.createDirectories(sandbox);
        ManagedEnvironmentWorker.SandboxExecutors local =
                assignment ->
                        new ManagedSandboxToolExecutor(mapper, text -> control(sandbox, text));
        try {
            var original =
                    hands.claim(
                                    "environment",
                                    "worker-key",
                                    new ManagedWorkerProtocol.Claim("first", 1))
                            .get(0);
            try (var worker =
                    new ManagedEnvironmentWorker(
                            base,
                            "environment",
                            "worker-key",
                            root.resolve("first-worker"),
                            1,
                            local)) {
                assertThrows(IllegalStateException.class, () -> worker.execute(original));
            }
            long offset =
                    physical.toolEvents()
                            .cursor(scope, original.task().id())
                            .orElseThrow()
                            .offset();
            assertTrue(offset > 0);
            long projected = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (journal.toolEvents()
                                    .cursor(scope, original.task().id())
                                    .map(cursor -> cursor.consumedSeq())
                                    .orElse(0L)
                            < 2
                    && System.nanoTime() < projected) Thread.sleep(10);
            assertTrue(
                    journal.publicEvents(scope, 0, 100).stream()
                            .anyMatch(event -> "tool.delta".equals(event.type())));
            clock.addAndGet(61000);
            var replacement =
                    hands.claim(
                                    "environment",
                                    "worker-key",
                                    new ManagedWorkerProtocol.Claim("replacement", 1))
                            .get(0);
            assertTrue(!replacement.firstLaunch());
            assertEquals(original.lease().executionId(), replacement.lease().executionId());
            try (var worker =
                    new ManagedEnvironmentWorker(
                            base,
                            "environment",
                            "worker-key",
                            root.resolve("replacement-worker"),
                            1,
                            local)) {
                worker.execute(replacement);
            }
            long applied = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (journal.tool(scope, original.task().id()).orElseThrow().result() == null
                    && System.nanoTime() < applied) Thread.sleep(10);
            assertEquals(List.of("once"), Files.readAllLines(sandbox.resolve("effects")));
            var cursor = journal.toolEvents().cursor(scope, original.task().id()).orElseThrow();
            assertTrue(cursor.captureComplete());
            assertTrue(
                    physical.toolEvents().cursor(scope, original.task().id()).orElseThrow().offset()
                            > offset);
            assertEquals(cursor.finalSeq().longValue(), cursor.consumedSeq());
            assertEquals(
                    "succeeded", journal.tool(scope, original.task().id()).orElseThrow().status());
            var output = new StringBuilder();
            for (var event : journal.publicEvents(scope, 0, 100))
                if (event.type().equals("tool.delta"))
                    output.append(
                            mapper.readTree(event.payload())
                                    .path("output")
                                    .get(0)
                                    .path("text")
                                    .asText());
            assertEquals("first 世界\nlast\n", output.toString());
            assertEquals(1, journal.head(scope).sequence());
        } finally {
            subscription.close();
            mq.close();
            server.stop(0);
            threads.shutdownNow();
        }
    }

    private ManagedWorkerProtocol.Assignment claim(
            ManagedWorkerService workers,
            String environment,
            ManagedCheckpointLoop coordinator,
            ManagedCheckpointOperations.Operation operation,
            ManagedHandsTestPipeline pipeline)
            throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (true) {
            pipeline.check();
            coordinator.execute(operation);
            var tasks =
                    workers.claim(
                            environment,
                            "worker-key",
                            new ManagedWorkerProtocol.Claim(environment, 1));
            if (!tasks.isEmpty()) return tasks.get(0);
            assertTrue(System.nanoTime() < deadline, "Workspace command was not delivered over MQ");
            Thread.sleep(20);
        }
    }

    private void recordWorkspaceUse(DriverManagerDataSource source, ManagedScope scope) {
        new JdbcTemplate(source)
                .update(
                        "INSERT INTO"
                            + " ma.tool_task(scope_key,task_id,turn_id,tool_call_id,execution_id,tool_name,arguments,checkpoint_sequence,status,result,result_hash)"
                            + " SELECT"
                            + " scope_key,'workspace-seed',turn_id,'workspace-seed','workspace-seed','fs.upload','{}',0,'succeeded','{}','seed'"
                            + " FROM ma.turn WHERE scope_key=?",
                        scope.messageGroup());
    }

    private String control(Path directory, String command) throws Exception {
        Path output = Files.createTempFile("workspace-control-", ".json");
        var process =
                new ProcessBuilder("sh")
                        .directory(directory.toFile())
                        .redirectOutput(output.toFile())
                        .start();
        try {
            try (var input = process.getOutputStream()) {
                input.write(command.getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            return Files.readString(output);
        } finally {
            process.destroyForcibly();
            Files.deleteIfExists(output);
        }
    }

    private SessionResolveResult definition(String id, String box, ManagedScope scope) {
        var environment =
                new EnvironmentDto(
                        id, id, "self_hosted", Map.of("sandboxId", box), "owner", null, 1, 1, null);
        return new SessionResolveResult(
                new ManagedSessionDto(
                        scope.sessionId(),
                        "owner",
                        "agent",
                        "owner",
                        1,
                        null,
                        null,
                        id,
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        "created",
                        null,
                        1,
                        1,
                        null),
                Map.of(),
                null,
                null,
                null,
                Map.of(),
                environment,
                List.of(),
                List.of(),
                null,
                null);
    }
}
