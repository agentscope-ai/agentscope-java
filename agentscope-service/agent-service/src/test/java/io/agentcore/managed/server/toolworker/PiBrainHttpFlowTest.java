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
package io.agentcore.managed.server.toolworker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.harness.runtime.RunConfigurationContract;
import io.agentcore.managed.server.agentservice.config.AgentServiceJsonConfiguration;
import io.agentcore.managed.server.agentservice.sessionservice.api.SessionServiceApi;
import io.agentcore.managed.server.agentservice.sessionservice.store.HarnessProtocolStore;
import io.agentcore.managed.server.agentservice.sessionservice.store.HarnessRunConfiguration;
import io.agentcore.managed.server.agentservice.sessionservice.store.SessionServiceStore;
import io.agentcore.managed.server.agentservice.sessionservice.store.SessionServiceWorkspace;
import io.agentcore.managed.server.runtime.ManagedCatalog;
import io.agentcore.managed.server.runtime.managed.FileRuntimeObjectStore;
import io.agentcore.managed.server.runtime.managed.InMemoryManagedLeaseStore;
import io.agentcore.managed.server.runtime.managed.InMemoryManagedMessageBus;
import io.agentcore.managed.server.runtime.managed.ManagedAdmissionStore;
import io.agentcore.managed.server.runtime.managed.ManagedAsyncToolExecutions;
import io.agentcore.managed.server.runtime.managed.ManagedBatchCodec;
import io.agentcore.managed.server.runtime.managed.ManagedBudgetStore;
import io.agentcore.managed.server.runtime.managed.ManagedCheckpointObjects;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore;
import io.agentcore.managed.server.runtime.managed.ManagedMcpCatalog;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Channel;
import io.agentcore.managed.server.runtime.managed.ManagedPreviewFixture;
import io.agentcore.managed.server.runtime.managed.ManagedPublicResources;
import io.agentcore.managed.server.runtime.managed.ManagedQueryViews;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import io.agentcore.managed.server.runtime.managed.ManagedSubagents;
import io.agentcore.managed.server.runtime.managed.ManagedToolCommandPublisher;
import io.agentcore.managed.server.runtime.managed.ManagedToolResultPublisher;
import io.agentcore.managed.server.runtime.managed.ManagedToolWorkerCommands;
import io.agentcore.managed.server.runtime.managed.ManagedToolWorkerLoop;
import io.agentcore.managed.server.runtime.managed.ManagedToolWorkerStore;
import io.agentcore.managed.server.runtime.managed.projection.PostgresSessionProjectionStore;
import io.agentcore.managed.server.runtime.protocol.RuntimeBootstrapDigest;
import io.agentcore.managed.server.runtime.protocol.RuntimeProtocol;
import io.agentcore.managed.server.runtime.protocol.RuntimeRequestContext;
import io.agentcore.managed.server.runtime.protocol.RuntimeSessionBootstrap;
import io.agentcore.managed.server.web.managed.service.SessionEventNotifier;
import io.agentcore.managed.server.web.managed.service.VaultCrypto;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.reactivestreams.Publisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;

/** Node PI Worker processes, HTTP Session Service and real Hands shell execution.
 * H2 and in-memory transport replace PostgreSQL/Redis/MQ; only the model is scripted.
 * Opt in after building the TypeScript workspace with -Dmanaged.pi.http=true.
 */
@Timeout(60)
@EnabledIfSystemProperty(named = "managed.pi.http", matches = "true")
class PiBrainHttpFlowTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper();
    final List<Process> brains = new ArrayList<>();
    final Map<Process, Integer> healthPorts = new HashMap<>();
    final HttpClient httpClient = HttpClient.newHttpClient();
    final ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

    @Configuration(proxyBeanMethods = false)
    @EnableWebFlux
    static class HttpConfiguration implements WebFluxConfigurer {
        @Override
        public void configureHttpMessageCodecs(ServerCodecConfigurer codecs) {
            // Match application.yml's production object transport limit (90 MiB).
            codecs.defaultCodecs().maxInMemorySize(90 * 1024 * 1024);
        }
    }

    @ParameterizedTest
    @CsvSource({"pi,false", "pi,true", "pi-durable,false", "pi-durable,true"})
    void handsResultAutomaticallyContinuesBrain(String harness, boolean restart) throws Exception {
        var source = database("session");
        ManagedAdmissionStore.initialize(source);
        PostgresSessionProjectionStore.initialize(source);
        var jdbc = new JdbcTemplate(source);
        var views =
                new ManagedQueryViews(
                        new PostgresSessionProjectionStore(source, mapper),
                        mapper,
                        "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8),
                        "v1",
                        1000,
                        new ManagedPublicResources(source, mapper));
        var admission = new ManagedAdmissionStore(source, mapper);
        admission.queryViews(views);
        var scope = new ManagedScope("tenant", "test", "pi-" + UUID.randomUUID());
        var crypto = new VaultCrypto(new MockEnvironment(), "configuration-fixture");
        Map<String, Object> definition =
                Map.of(
                        "session",
                        Map.of(
                                "id",
                                scope.sessionId(),
                                "ownerId",
                                "owner",
                                "agentId",
                                "fixture-agent",
                                "agentVersion",
                                3,
                                "agentOverridesJson",
                                "{\"system\":\"fixture instructions from Session"
                                        + " override\",\"model\":\"fixture\",\"maxIters\":4}"),
                        "agentSnapshot",
                        Map.of(
                                "name",
                                "fixture-agent",
                                "system",
                                "original instructions",
                                "model",
                                "unregistered-base-model",
                                "maxIters",
                                20),
                        "definitionFiles",
                        Map.of(),
                        "vaultCredentials",
                        List.of());
        var bootstrap =
                new RuntimeSessionBootstrap(
                        new RuntimeRequestContext(
                                RuntimeProtocol.VERSION,
                                scope.tenant(),
                                scope.namespace(),
                                null,
                                scope.sessionId(),
                                null,
                                "bootstrap",
                                "trace",
                                Map.of(),
                                "initial",
                                Long.MAX_VALUE),
                        "managed",
                        RuntimeBootstrapDigest.configurationRevision(mapper, definition),
                        definition);
        admission.createSession(
                scope,
                mapper.writeValueAsString(
                        Map.of(
                                "encrypted_definition",
                                crypto.encrypt(mapper.writeValueAsString(bootstrap)))));
        admission.queue().enable(scope);
        String turn = admission.acceptTurn(scope, "actor", "input", "{\"message\":\"run\"}").id();
        var budgets = new ManagedBudgetStore(source, mapper, (name, usage) -> null);
        var objects = new FileRuntimeObjectStore("test", root.resolve("service-objects"));
        var journal =
                new ManagedJournalStore(
                        source,
                        mapper,
                        budgets,
                        new ManagedCheckpointObjects(mapper, objects),
                        new SessionEventNotifier(source, 1000));
        journal.queryViews(views);
        var protocol =
                new HarnessProtocolStore(source, admission, journal, objects, mapper, budgets);
        protocol.harnessType(harness);
        var preview = new ManagedPreviewFixture(scope, mapper);
        protocol.previews(preview.previews);
        // Compile the admitted encrypted definition on the actual HTTP request. Only MCP
        // discovery is stubbed (this Agent has no MCP servers); no run configuration is prefilled.
        var catalog = new ManagedCatalog(admission, mapper, crypto, mock(ManagedSubagents.class));
        var mcp = mock(ManagedMcpCatalog.class);
        when(mcp.seal(any(), any())).thenReturn("{}");
        when(mcp.require(any(), any(), any(), any()))
                .thenReturn(new ManagedMcpCatalog.Description(List.of(), List.of()));
        var configuration =
                new HarnessRunConfiguration(
                        source, catalog, mcp, admission, objects, mapper, "fixture");
        var context = new AnnotationConfigApplicationContext();
        context.register(HttpConfiguration.class, AgentServiceJsonConfiguration.class);
        context.registerBean(ObjectMapper.class, () -> mapper);
        context.registerBean(
                SessionServiceApi.class,
                () ->
                        new SessionServiceApi(
                                new SessionServiceStore(source, journal, mapper, objects),
                                protocol,
                                new SessionServiceWorkspace(source, journal, mapper),
                                configuration,
                                budgets,
                                admission));
        var commits = new AtomicInteger();
        var receipts = new AtomicInteger();
        var claims = new AtomicInteger();
        var requests = new ConcurrentLinkedQueue<String>();
        context.registerBean(
                WebFilter.class,
                () ->
                        (exchange, chain) -> {
                            String path = exchange.getRequest().getPath().value();
                            requests.add(path);
                            if (path.endsWith("/commits/receipt")) receipts.incrementAndGet();
                            if (path.endsWith("/executions/claim")) claims.incrementAndGet();
                            if (!path.endsWith("/commits")) return chain.filter(exchange);
                            commits.incrementAndGet();
                            var response =
                                    new ServerHttpResponseDecorator(exchange.getResponse()) {
                                        @Override
                                        public Mono<Void> writeWith(
                                                Publisher<? extends DataBuffer> body) {
                                            // Lose the acknowledgement at every committed boundary,
                                            // including authorization, suspension and terminal.
                                            if (getStatusCode() == null
                                                    || getStatusCode().is2xxSuccessful())
                                                setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                                            return super.writeWith(body);
                                        }
                                    };
                            return chain.filter(exchange.mutate().response(response).build());
                        });
        context.refresh();
        var http =
                HttpServer.create()
                        .host("127.0.0.1")
                        .port(0)
                        .handle(
                                new ReactorHttpHandlerAdapter(
                                        WebHttpHandlerBuilder.applicationContext(context).build()))
                        .bindNow();
        var bus = new InMemoryManagedMessageBus(() -> System.nanoTime() / 1_000_000, 8);
        var leases = new InMemoryManagedLeaseStore(() -> System.nanoTime() / 1_000_000);
        var controls = Executors.newScheduledThreadPool(3);
        var workerSource = database("hands");
        ManagedAdmissionStore.initialize(workerSource);
        var worker = new ManagedToolWorkerStore(workerSource, mapper);
        var codec = new ManagedBatchCodec(mapper, objects);
        var publisher = new ManagedToolResultPublisher(worker, bus, codec);
        var executions =
                new ManagedAsyncToolExecutions(
                        leases,
                        worker,
                        publisher,
                        controls,
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(1),
                        Duration.ofMillis(20));
        var sandbox = new ManagedSandboxToolExecutor(mapper, this::control);
        try (var commandsOut = new ManagedToolCommandPublisher(source, bus, codec, mapper);
                var hands =
                        new ManagedToolWorkerLoop(
                                worker,
                                executions,
                                2,
                                Duration.ofSeconds(10),
                                Duration.ofMillis(20),
                                (s, task) -> sandbox,
                                (task, error) -> errors.add(error))) {
            journal.transport().onPublicationReady(commandsOut::wakeup);
            var commands =
                    new ManagedToolWorkerCommands(
                            worker, codec, publisher, hands, journal.transport()::confirm);
            bus.subscribe(Channel.TOOL_EXECUTE, 2, commands, (d, e) -> errors.add(e));
            bus.subscribe(
                    Channel.TOOL_EVENTS,
                    2,
                    delivery -> journal.commitToolEvent(codec.decodeToolEvent(delivery.message())),
                    (d, e) -> errors.add(e));
            commandsOut.start();
            publisher.start();
            hands.start();
            String command =
                    "printf 'started\\n' >> once; "
                            + (restart ? "while [ ! -f release ]; do sleep 0.05; done; " : "")
                            + "printf 'hands-result'";
            var first = startBrain(harness, restart, http.port(), command);
            if (restart) {
                await(
                        () -> {
                            String status = admission.turn(scope, turn).status();
                            if ("failed".equals(status))
                                throw new AssertionError(
                                        "Brain failed before suspension: "
                                                + requests
                                                + " "
                                                + jdbc.queryForList(
                                                        "SELECT type,payload FROM ma.public_event"
                                                                + " WHERE scope_key=?",
                                                        scope.messageGroup())
                                                + logs());
                            return "waiting_tool".equals(status);
                        },
                        "Brain suspension");
                assertEquals(
                        0,
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ma.harness_lease WHERE scope_key=?",
                                Integer.class,
                                scope.messageGroup()));
                // A new OS process must recover entirely from Session Service. No manual next(),
                // claim(), runClaimed() or result-ready flag participates in the handoff.
                await(() -> idle(first), "suspended Brain releases its only worker slot");
                await(
                        () -> Files.exists(root.resolve("once")),
                        "Hands side effect before Brain exit");
                first.destroyForcibly();
                assertTrue(first.waitFor(5, TimeUnit.SECONDS));
                Files.writeString(root.resolve("release"), "ready");
                startBrain(harness, restart, http.port(), command);
            }
            await(
                    () ->
                            List.of("succeeded", "failed")
                                    .contains(admission.turn(scope, turn).status()),
                    "terminal turn");
            await(
                    () -> idle(brains.get(brains.size() - 1)),
                    "terminal receipt releases the worker slot");
            assertEquals(
                    "succeeded",
                    admission.turn(scope, turn).status(),
                    () ->
                            requests
                                    + " "
                                    + jdbc.queryForList(
                                            "SELECT type,payload FROM ma.public_event WHERE"
                                                    + " scope_key=?",
                                            scope.messageGroup())
                                    + logs());
            var effective =
                    mapper.readTree(
                            jdbc.queryForObject(
                                    "SELECT payload FROM ma.harness_configuration WHERE scope_key=?"
                                            + " AND turn_id=?",
                                    String.class,
                                    scope.messageGroup(),
                                    turn));
            RunConfigurationContract.validate(effective);
            assertEquals("fixture-agent", effective.path("identity").path("agentId").asText());
            assertEquals(3, effective.path("identity").path("agentVersion").asInt());
            assertEquals(4, effective.path("configuration").path("maxModelCalls").asInt());
            assertTrue(
                    receipts.get() > 0,
                    "The lost commit response must recover its original receipt");
            assertEquals(
                    commits.get(), receipts.get(), "Every boundary must recover the same receipt");
            assertEquals(restart ? 2 : 1, claims.get(), this::logs);
            if (restart) {
                var nativeState =
                        mapper.readTree(
                                jdbc.queryForObject(
                                        "SELECT native_state FROM ma.harness_head WHERE"
                                                + " scope_key=?",
                                        String.class,
                                        scope.messageGroup()));
                assertEquals(
                        "managed-pi-snapshot-v1",
                        nativeState.path("checkpointRef").path("formatVersion").asText());
                assertTrue(nativeState.path("journalRefs").size() > 0);
            }
            assertEquals(List.of("started"), Files.readAllLines(root.resolve("once")));
            assertEquals(
                    2,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM ma.model_call WHERE scope_key=?",
                            Integer.class,
                            scope.messageGroup()));
            assertEquals(
                    1,
                    new JdbcTemplate(workerSource)
                            .queryForObject(
                                    "SELECT COUNT(*) FROM ma.hands_execution", Integer.class));
            assertTrue(journal.pendingToolIds(scope, turn).isEmpty());
            assertEquals(1, admission.session(scope).settledSequence());
            while (journal.projections().project(scope, 128) > 0) {}
            var events =
                    admission.protocol().changes(scope, 0, 256).stream()
                            .map(change -> change.event())
                            .toList();
            var starts =
                    events.stream()
                            .filter(event -> "span.model_request_start".equals(event.get("type")))
                            .toList();
            var ends =
                    events.stream()
                            .filter(event -> "span.model_request_end".equals(event.get("type")))
                            .toList();
            assertEquals(2, starts.size(), this::logs);
            assertEquals(2, ends.size(), this::logs);
            for (var end : ends) {
                assertTrue(
                        starts.stream()
                                .anyMatch(
                                        start ->
                                                start.get("id")
                                                        .equals(end.get("model_request_start_id"))),
                        this::logs);
                assertTrue(end.containsKey("model_usage"), this::logs);
            }
            var messages =
                    events.stream()
                            .filter(event -> "agent.message".equals(event.get("type")))
                            .toList();
            assertEquals(2, messages.size(), this::logs);
            var previewStarts =
                    preview.frames.stream()
                            .filter(frame -> "event_start".equals(frame.path("type").asText()))
                            .toList();
            var previewDeltas =
                    preview.frames.stream()
                            .filter(frame -> "event_delta".equals(frame.path("type").asText()))
                            .toList();
            assertEquals(2, previewStarts.size(), this::logs);
            assertEquals(2, previewDeltas.size(), this::logs);
            for (int index = 0; index < 2; index++) {
                assertEquals(
                        messages.get(index).get("id"),
                        previewStarts.get(index).path("event").path("id").asText());
                assertEquals(
                        messages.get(index).get("id"),
                        previewDeltas.get(index).path("event_id").asText());
            }
            assertEquals(
                    "checking: hands",
                    previewDeltas.get(0).path("delta").path("content").path("text").asText());
            assertEquals(
                    "finished: hands-result",
                    previewDeltas.get(1).path("delta").path("content").path("text").asText());
            assertTrue(messages.get(0).toString().contains("checking: hands"), this::logs);
            assertTrue(messages.get(1).toString().contains("finished: hands-result"), this::logs);
            assertEquals(
                    List.of(messages.get(1).get("id")),
                    admission.protocol().output(scope, turn, null, 10).get("output_event_ids"));
            assertTrue(
                    journal.publicEvents(scope, 0, 1000).stream()
                            .anyMatch(e -> e.payload().contains("finished: hands-result")),
                    this::logs);
            assertEquals(
                    harness,
                    jdbc.queryForObject(
                            "SELECT harness_type FROM ma.harness_binding WHERE scope_key=?",
                            String.class,
                            scope.messageGroup()));
            String output = logs();
            assertEquals(
                    2,
                    output.lines().filter(line -> line.contains("fixture.model")).count(),
                    output);
            assertTrue(output.contains("\"hasToolResult\":true"), output);
            assertTrue(!output.contains("execution.retry"), output);
            if (!errors.isEmpty()) throw new AssertionError("Hands pipeline failed", errors.peek());
        } finally {
            Files.writeString(root.resolve("release"), "cleanup");
            for (var process : brains) {
                process.destroy();
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
            }
            publisher.close();
            controls.shutdownNow();
            bus.close();
            http.disposeNow();
            context.close();
            preview.close();
        }
    }

    private DriverManagerDataSource database(String role) {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:" + role + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
    }

    private Process startBrain(String harness, boolean suspend, int port, String command)
            throws Exception {
        Path repo = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (repo != null
                && !Files.isDirectory(
                        repo.resolve("managed-agents/agent-harness-runtime/typescript")))
            repo = repo.getParent();
        if (repo == null) throw new IllegalStateException("Cannot find TypeScript workspace");
        int healthPort;
        try (var socket = new ServerSocket(0)) {
            healthPort = socket.getLocalPort();
        }
        Path script =
                repo.resolve(
                        "managed-agents/agent-harness-runtime/typescript/packages/adapter-pi/test/http-worker.mjs");
        assertTrue(
                Files.isRegularFile(script.resolveSibling("../dist/index.js").normalize()),
                "Build TypeScript first: pnpm -r build");
        var builder =
                new ProcessBuilder("node", script.toString())
                        .directory(root.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(root.resolve("brain-" + brains.size() + ".log").toFile());
        builder.environment()
                .putAll(
                        Map.of(
                                "SESSION_SERVICE_URL",
                                "http://127.0.0.1:" + port + "/internal/session-service/v0",
                                "MANAGED_AGENTS_INTERNAL_TOKEN",
                                "test-token",
                                "MANAGED_AGENTS_HARNESS_TYPE",
                                harness,
                                "BRAIN_CONCURRENCY",
                                "1",
                                "BRAIN_INLINE_WAIT_MS",
                                suspend ? "0" : "10000",
                                "PORT",
                                Integer.toString(healthPort),
                                "PI_TEST_COMMAND",
                                command,
                                "PI_TEST_PADDING_BYTES",
                                suspend ? "300000" : "0"));
        var process = builder.start();
        brains.add(process);
        healthPorts.put(process, healthPort);
        return process;
    }

    private boolean idle(Process process) {
        try {
            var response =
                    httpClient.send(
                            HttpRequest.newBuilder(
                                            URI.create(
                                                    "http://127.0.0.1:"
                                                            + healthPorts.get(process)
                                                            + "/readyz"))
                                    .timeout(Duration.ofSeconds(1))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200
                    && mapper.readTree(response.body()).path("active").asInt(-1) == 0;
        } catch (Exception unavailable) {
            return false;
        }
    }

    private String control(String command) throws Exception {
        Path output = Files.createTempFile(root, "control-", ".json");
        var process =
                new ProcessBuilder("sh")
                        .directory(root.toFile())
                        .redirectOutput(output.toFile())
                        .redirectError(ProcessBuilder.Redirect.INHERIT)
                        .start();
        try {
            try (var input = process.getOutputStream()) {
                input.write(command.getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            return Files.readString(output);
        } finally {
            process.destroyForcibly();
            Files.deleteIfExists(output);
        }
    }

    private void await(BooleanSupplier condition, String description) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(35).toNanos();
        while (!condition.getAsBoolean() && errors.isEmpty() && System.nanoTime() < deadline) {
            for (var process : brains)
                if (!process.isAlive() && process == brains.get(brains.size() - 1))
                    throw new AssertionError("Brain exited: " + logs());
            Thread.sleep(20);
        }
        if (!errors.isEmpty())
            throw new AssertionError("Hands pipeline failed: " + logs(), errors.peek());
        assertTrue(
                condition.getAsBoolean(),
                () -> "Timed out waiting for " + description + "\n" + logs());
    }

    private String logs() {
        var output = new StringBuilder();
        for (int i = 0; i < brains.size(); i++) {
            try {
                output.append(Files.readString(root.resolve("brain-" + i + ".log")));
            } catch (Exception failure) {
                output.append(failure);
            }
        }
        return output.toString();
    }
}
