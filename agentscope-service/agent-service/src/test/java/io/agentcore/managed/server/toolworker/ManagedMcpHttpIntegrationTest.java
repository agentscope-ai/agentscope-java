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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.agentcore.managed.server.control.SessionResolveResult;
import io.agentcore.managed.server.runtime.loadtest.LoadTestModel;
import io.agentcore.managed.server.runtime.loadtest.LoadTestModel.Profile;
import io.agentcore.managed.server.runtime.managed.InMemoryManagedInputInbox;
import io.agentcore.managed.server.runtime.managed.InMemoryManagedLeaseStore;
import io.agentcore.managed.server.runtime.managed.InMemoryManagedMessageBus;
import io.agentcore.managed.server.runtime.managed.InMemoryManagedToolResultDelivery;
import io.agentcore.managed.server.runtime.managed.ManagedAdmissionStore;
import io.agentcore.managed.server.runtime.managed.ManagedAsyncToolExecutions;
import io.agentcore.managed.server.runtime.managed.ManagedBatchCodec;
import io.agentcore.managed.server.runtime.managed.ManagedCheckpointObjects;
import io.agentcore.managed.server.runtime.managed.ManagedCommitPlan;
import io.agentcore.managed.server.runtime.managed.ManagedDeliverySuspended;
import io.agentcore.managed.server.runtime.managed.ManagedHarnessRuntimeToolEvents;
import io.agentcore.managed.server.runtime.managed.ManagedHarnessRuntimeTurns;
import io.agentcore.managed.server.runtime.managed.ManagedHarnessTurns;
import io.agentcore.managed.server.runtime.managed.ManagedInputDelivery;
import io.agentcore.managed.server.runtime.managed.ManagedInputInbox.Type;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore.ToolTask;
import io.agentcore.managed.server.runtime.managed.ManagedMcpBatch;
import io.agentcore.managed.server.runtime.managed.ManagedMcpBatchToolkit;
import io.agentcore.managed.server.runtime.managed.ManagedMcpCatalog;
import io.agentcore.managed.server.runtime.managed.ManagedMcpDiscovery;
import io.agentcore.managed.server.runtime.managed.ManagedMcpTool;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Channel;
import io.agentcore.managed.server.runtime.managed.ManagedPushDelivery;
import io.agentcore.managed.server.runtime.managed.ManagedResumePublisher;
import io.agentcore.managed.server.runtime.managed.ManagedRuntimeInputMiddleware;
import io.agentcore.managed.server.runtime.managed.ManagedRuntimeWriter;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import io.agentcore.managed.server.runtime.managed.ManagedSessionLogStore;
import io.agentcore.managed.server.runtime.managed.ManagedSessionProtocol.Command;
import io.agentcore.managed.server.runtime.managed.ManagedToolCommand;
import io.agentcore.managed.server.runtime.managed.ManagedToolCommandPublisher;
import io.agentcore.managed.server.runtime.managed.ManagedToolCommitGate.Decision;
import io.agentcore.managed.server.runtime.managed.ManagedToolExecutions;
import io.agentcore.managed.server.runtime.managed.ManagedToolRequests;
import io.agentcore.managed.server.runtime.managed.ManagedToolResultPublisher;
import io.agentcore.managed.server.runtime.managed.ManagedToolResultRelay;
import io.agentcore.managed.server.runtime.managed.ManagedToolTarget;
import io.agentcore.managed.server.runtime.managed.ManagedToolWorkerCommands;
import io.agentcore.managed.server.runtime.managed.ManagedToolWorkerLoop;
import io.agentcore.managed.server.runtime.managed.ManagedToolWorkerStore;
import io.agentcore.managed.server.runtime.managed.ManagedTransportException;
import io.agentcore.managed.server.runtime.managed.ManagedTurnExecution;
import io.agentcore.managed.server.runtime.managed.ManagedTurnPublisher;
import io.agentcore.managed.server.runtime.managed.SessionCommitTransactions;
import io.agentcore.managed.server.web.managed.service.VaultCrypto;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.session.SessionRecorder;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Flux;

/** Real SDK and Streamable HTTP server; the endpoint performs no business/network side effects. */
@Timeout(25)
class ManagedMcpHttpIntegrationTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final ManagedScope scope = new ManagedScope("test", "mcp", UUID.randomUUID().toString());
    final AtomicInteger invocations = new AtomicInteger();
    final AtomicBoolean loseResponse = new AtomicBoolean();
    final AtomicBoolean authenticated = new AtomicBoolean();
    final AtomicInteger replyDelayMillis = new AtomicInteger();
    final ScheduledExecutorService renewals = Executors.newScheduledThreadPool(2);
    final ExecutorService httpThreads = Executors.newCachedThreadPool();
    final InMemoryManagedMessageBus bus =
            new InMemoryManagedMessageBus(System::currentTimeMillis, 5);
    final VaultCrypto crypto =
            new VaultCrypto(new MockEnvironment(), "01234567890123456789012345678901");
    HttpServer server;
    ManagedToolWorkerStore store;
    ManagedToolResultPublisher results;
    ManagedBatchCodec codec;
    ManagedMcpExecutors mcp;
    String configuration;

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(httpThreads);
        server.createContext(
                "/mcp",
                exchange -> {
                    try {
                        if (!exchange.getRequestMethod().equals("POST")) {
                            exchange.sendResponseHeaders(405, -1);
                            return;
                        }
                        authenticated.set(
                                "Bearer scoped-test-token"
                                        .equals(
                                                exchange.getRequestHeaders()
                                                        .getFirst("Authorization")));
                        var request = mapper.readTree(exchange.getRequestBody());
                        if (!request.has("id")) {
                            exchange.sendResponseHeaders(202, -1);
                            return;
                        }
                        Object result;
                        switch (request.path("method").asText()) {
                            case "initialize" ->
                                    result =
                                            Map.of(
                                                    "protocolVersion",
                                                    request.path("params")
                                                            .path("protocolVersion")
                                                            .asText(),
                                                    "capabilities",
                                                    Map.of("tools", Map.of("listChanged", false)),
                                                    "serverInfo",
                                                    Map.of(
                                                            "name",
                                                            "hands-fixture",
                                                            "version",
                                                            "1"));
                            case "tools/list" ->
                                    result =
                                            Map.of(
                                                    "tools",
                                                    List.of(
                                                            Map.of(
                                                                    "name",
                                                                    "echo",
                                                                    "description",
                                                                    "Echo",
                                                                    "inputSchema",
                                                                    Map.of(
                                                                            "type",
                                                                            "object",
                                                                            "properties",
                                                                            Map.of(
                                                                                    "value",
                                                                                    Map.of(
                                                                                            "type",
                                                                                            "string")),
                                                                            "required",
                                                                            List.of("value")))));
                            case "tools/call" -> {
                                invocations.incrementAndGet();
                                LockSupport.parkNanos(
                                        Duration.ofMillis(replyDelayMillis.get()).toNanos());
                                if (loseResponse.get()) {
                                    // The request reached the server, then its already-started
                                    // response was lost.
                                    exchange.getResponseHeaders()
                                            .set("Content-Type", "application/json");
                                    exchange.sendResponseHeaders(200, 1024);
                                    return;
                                }
                                result =
                                        Map.of(
                                                "content",
                                                List.of(Map.of("type", "text", "text", "original")),
                                                "isError",
                                                false);
                            }
                            default ->
                                    throw new IllegalArgumentException("Unexpected fixture method");
                        }
                        byte[] bytes =
                                mapper.writeValueAsBytes(
                                        Map.of(
                                                "jsonrpc",
                                                "2.0",
                                                "id",
                                                request.get("id"),
                                                "result",
                                                result));
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:mcp-http-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        ManagedAdmissionStore.initialize(source);
        store = new ManagedToolWorkerStore(source, mapper);
        codec = new ManagedBatchCodec(mapper, root);
        results = new ManagedToolResultPublisher(store, bus, codec);
        var connection = new McpServerConfig();
        connection.setTransport("http");
        connection.setUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
        connection.setHeaders(Map.of("Authorization", "Bearer scoped-test-token"));
        connection.setTimeout(Duration.ofSeconds(2));
        connection.setInitializationTimeout(Duration.ofSeconds(3));
        connection.setPrefixToolNames(true);
        var tools = new ToolsConfig();
        tools.setMcpServers(Map.of("server", connection));
        var minimal =
                new SessionResolveResult(
                        null, Map.of(), null, null, null, Map.of(), null, List.of(), List.of(),
                        null, Map.of());
        configuration =
                crypto.encrypt(mapper.writeValueAsString(new ManagedToolTarget(minimal, tools)));
        mcp = new ManagedMcpExecutors(store, results, mapper, crypto);
    }

    @AfterEach
    void stop() {
        mcp.close();
        server.stop(0);
        httpThreads.shutdownNow();
        renewals.shutdownNow();
        bus.close();
    }

    ManagedToolWorkerCommands driver() {
        return driver(ignored -> Decision.COMMITTED);
    }

    ManagedToolWorkerCommands driver(Function<ManagedToolCommand, Decision> confirmation) {
        var leases = new InMemoryManagedLeaseStore(System::currentTimeMillis);
        var execution =
                new ManagedToolExecutions(
                        leases,
                        new InMemoryManagedInputInbox(System::currentTimeMillis),
                        store,
                        results,
                        renewals,
                        Duration.ofSeconds(5),
                        Duration.ofMillis(500),
                        Duration.ofMillis(10));
        return new ManagedToolWorkerCommands(
                store,
                codec,
                results,
                (owner, taskId) -> {
                    var provider = spy(mcp.executor(owner, taskId));
                    doReturn(false).when(provider).waitForCompletion(any());
                    execution.execute(owner, taskId, provider);
                },
                confirmation);
    }

    ToolTask task(String id, String name, Map<String, Object> input) throws Exception {
        return new ToolTask(
                id,
                "turn",
                id,
                id,
                name,
                mapper.writeValueAsString(input),
                1,
                "ready",
                null,
                null,
                null);
    }

    void segment(ManagedToolWorkerCommands driver, ToolTask task) {
        var command =
                new ManagedToolCommand(
                        scope, "execute_" + task.id(), "execute", task, configuration);
        try {
            var delivery =
                    new ManagedPushDelivery(
                            "delivery",
                            "consumer",
                            Channel.TOOL_EXECUTE,
                            "topic",
                            "group",
                            "broker",
                            1,
                            codec.encode(command));
            driver.consume(delivery);
            delivery.handoff().confirm();
        } catch (ManagedDeliverySuspended waiting) {
            /* The MQ command remains pending. */
        }
    }

    ToolTask invocation(ManagedToolWorkerCommands driver) throws Exception {
        var target = mapper.readValue(crypto.decrypt(configuration), ManagedToolTarget.class);
        var directory =
                new ManagedMcpDiscovery().discover(target.tools(), target.resolved(), () -> false);
        assertTrue(store.tool(scope, "catalog_http").isEmpty());
        assertEquals(1, directory.tools().size());
        return task(
                "invoke",
                "mcp.call",
                Map.of(
                        "server",
                        "server",
                        "tool",
                        "echo",
                        "descriptor_hash",
                        ManagedMcpCatalog.descriptorHash(directory.tools().get(0), mapper),
                        "arguments",
                        Map.of("value", "hello"),
                        "meta",
                        Map.of("trace", "original")));
    }

    @Test
    void simulatedModelConsumesOriginalHttpToolResultAfterReplacement() throws Exception {
        var profile = new Profile(1, 1, 3, 16, "server__echo", Map.of("value", "hello"), 1, 1);
        var input =
                Msg.builder()
                        .role(MsgRole.USER)
                        .textContent(
                                "LOADTEST"
                                    + " {\"request_id\":\"http-recover\",\"marker\":\"http-marker\"}")
                        .build();
        var schemas =
                List.of(ToolSchema.builder().name("server__echo").description("fixture").build());
        var emitted =
                new LoadTestModel("loadtest:http", profile)
                        .stream(List.of(input), schemas, null)
                                .collectList()
                                .block(Duration.ofSeconds(2));
        var call = (ToolUseBlock) emitted.get(2).getContent().get(1);
        var driver = driver();
        var task = invocation(driver);
        segment(driver, task);
        await(() -> store.stagedToolResult(scope, task.id()).isPresent());
        segment(driver, task);
        var original = store.stagedToolResult(scope, task.id()).orElseThrow();
        var result =
                new ManagedMcpTool(
                                new ManagedMcpCatalog.Tool(
                                        "server",
                                        "server__echo",
                                        "echo",
                                        "fixture",
                                        Map.of(),
                                        null,
                                        true),
                                null,
                                mapper)
                        .decode(original.result())
                        .withIdAndName(call.getId(), call.getName());
        var history =
                List.of(
                        input,
                        Msg.builder().role(MsgRole.ASSISTANT).content(call).build(),
                        Msg.builder().role(MsgRole.TOOL).content(result).build());
        var answer =
                new LoadTestModel("loadtest:http", profile)
                        .stream(history, schemas, null).collectList().block(Duration.ofSeconds(2));
        assertTrue(mapper.writeValueAsString(answer).contains("original"));
        assertTrue(
                answer.stream()
                        .flatMap(c -> c.getContent().stream())
                        .noneMatch(ToolUseBlock.class::isInstance));
        segment(driver, task);
        assertEquals(1, invocations.get());
        assertTrue(authenticated.get());
    }

    @Test
    void discoversAndCallsThroughTheActualMcpHttpClient() throws Exception {
        var driver = driver();
        var task = invocation(driver);
        segment(driver, task);
        await(() -> store.stagedToolResult(scope, task.id()).isPresent());
        segment(driver, task);
        assertEquals("succeeded", store.tool(scope, task.id()).orElseThrow().status());
        assertEquals(1, invocations.get());
        assertTrue(authenticated.get());
        assertEquals("succeeded", store.tool(scope, task.id()).orElseThrow().status());
    }

    @Test
    void aLostHttpResponseCannotCauseAReplacementToInvokeAgain() throws Exception {
        var driver = driver();
        var task = invocation(driver);
        loseResponse.set(true);
        segment(driver, task);
        await(
                () -> {
                    segment(driver, task);
                    return "unknown".equals(store.tool(scope, task.id()).orElseThrow().status());
                });
        mcp.close();
        mcp = new ManagedMcpExecutors(store, results, mapper, crypto);
        segment(driver(), task);
        assertEquals(1, invocations.get());
        assertTrue(store.stagedToolResult(scope, task.id()).isEmpty());
    }

    @Test
    void actualHarnessRestoresTheOriginalMcpResultOnAnotherHarnessRuntime() throws Exception {
        actualHarnessRestores(false);
    }

    @Test
    void batchCompletesTwoCallsAndResumesAnotherRuntimeThroughResumeMq() throws Exception {
        actualHarnessRestores(true);
    }

    @Test
    void cancellingASuspendedBatchPreservesItsResultsAndReleasesTheNextTurn() throws Exception {
        actualHarnessRestores(true, true);
    }

    void actualHarnessRestores(boolean batch) throws Exception {
        actualHarnessRestores(batch, false);
    }

    @Test
    void directCommitBatchRecoversWhenR1CrashesBeforeTaskCommit() throws Exception {
        actualHarnessRestores(true, false, 1);
    }

    @Test
    void directCommitBatchRecoversWhenR1CrashesAfterTaskCommit() throws Exception {
        actualHarnessRestores(true, false, 2);
    }

    void actualHarnessRestores(boolean batch, boolean cancel) throws Exception {
        actualHarnessRestores(batch, cancel, 0);
    }

    void actualHarnessRestores(boolean batch, boolean cancel, int crash) throws Exception {
        var runtimeSource =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:mcp-turn-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        ManagedAdmissionStore.initialize(runtimeSource);
        var admission = new ManagedAdmissionStore(runtimeSource, mapper);
        admission.createSession(scope, "{}");
        var journal =
                spy(
                        new ManagedJournalStore(
                                runtimeSource,
                                mapper,
                                new ManagedCheckpointObjects(mapper, root.resolve("checkpoints"))));
        admission.queryViews(
                ManagedToolWorkerTestPipeline.configureQuery(journal, runtimeSource, mapper));
        var commits = spy(new SessionCommitTransactions(journal));
        var crashed = new AtomicBoolean();
        doAnswer(
                        invocation -> {
                            ManagedCommitPlan plan = invocation.getArgument(0);
                            var boundary = plan.boundary();
                            if (crash > 0
                                    && !boundary.toolIntents().isEmpty()
                                    && crashed.compareAndSet(false, true)) {
                                if (crash == 2) invocation.callRealMethod();
                                throw new ManagedTransportException(
                                        "Injected Runtime crash at tool commit");
                            }
                            return invocation.callRealMethod();
                        })
                .when(commits)
                .commitPrepared(any(ManagedCommitPlan.class));
        var leases = new InMemoryManagedLeaseStore(System::currentTimeMillis);
        var inbox = new InMemoryManagedInputInbox(System::currentTimeMillis);
        var delivered = new InMemoryManagedToolResultDelivery();
        var relay = new ManagedToolResultRelay(journal, codec, delivered);
        var requests = new ManagedToolRequests(mapper, Duration.ofMillis(100), delivered, codec);
        var catalogs = new ManagedMcpCatalog(runtimeSource, mapper, crypto, journal.transport());
        var protocol = admission.protocol();
        var accepted =
                protocol.accept(
                        scope,
                        "actor",
                        "turn",
                        List.of(
                                new Command(
                                        "user.message",
                                        List.of(Map.of("type", "text", "text", "echo")),
                                        "new_turn",
                                        null,
                                        true)),
                        () -> {});
        String turn =
                mapper.valueToTree(accepted.receipt())
                        .path("data")
                        .get(0)
                        .path("managed_agents")
                        .path("turn_id")
                        .asText();
        var errors = new ConcurrentLinkedQueue<Throwable>();
        var closing = new ArrayList<AutoCloseable>();
        ManagedToolWorkerCommands commands;
        if (batch) {
            var executions =
                    new ManagedAsyncToolExecutions(
                            leases,
                            store,
                            results,
                            renewals,
                            Duration.ofSeconds(5),
                            Duration.ofMillis(500),
                            Duration.ofMillis(10));
            var workers =
                    new ManagedToolWorkerLoop(
                            store,
                            executions,
                            2,
                            Duration.ofSeconds(5),
                            Duration.ofMillis(10),
                            (owner, taskId) -> mcp.executor(owner, taskId),
                            (ref, failure) -> errors.add(failure),
                            16384,
                            64,
                            Duration.ofMillis(100));
            workers.start();
            closing.add(workers);
            commands =
                    new ManagedToolWorkerCommands(
                            store, codec, results, workers, journal.transport()::confirm);
        } else commands = driver(journal.transport()::confirm);
        var events = new ManagedHarnessRuntimeToolEvents(codec, journal, relay::delivered);
        closing.add(bus.subscribe(Channel.TOOL_EXECUTE, 2, commands, (d, e) -> errors.add(e)));
        closing.add(bus.subscribe(Channel.TOOL_CONTROL, 2, commands, (d, e) -> errors.add(e)));
        closing.add(
                bus.subscribe(
                        Channel.TOOL_EVENTS,
                        2,
                        d -> {
                            var event = codec.decodeToolEvent(d.message());
                            if (!catalogs.receive(event)) events.consume(d);
                        },
                        (d, e) -> errors.add(e)));
        var publications = new ManagedToolCommandPublisher(runtimeSource, bus, codec, mapper);
        journal.transport().onPublicationReady(publications::wakeup);
        publications.start();
        closing.add(publications);
        journal.transport().start(bus, codec);
        closing.add(journal.transport());
        results.start();
        closing.add(results);
        relay.start();
        closing.add(relay);
        var resumes = new ManagedResumePublisher(journal.suspensions(), bus, leases, mapper);
        resumes.start();
        closing.add(resumes);
        try {
            var target = mapper.readValue(crypto.decrypt(configuration), ManagedToolTarget.class);
            var description =
                    new AtomicReference<>(
                            catalogs.require(scope, target.tools(), target.resolved(), turn));
            assertEquals(
                    0,
                    new JdbcTemplate(runtimeSource)
                            .queryForObject(
                                    "SELECT COUNT(*) FROM ma.tool_command_outbox WHERE task_id LIKE"
                                            + " 'catalog_%'",
                                    Integer.class));
            var model = mock(Model.class);
            when(model.getModelName()).thenReturn("mcp-fixture-model");
            var reasoning = new AtomicInteger();
            when(model.stream(anyList(), anyList(), any()))
                    .thenAnswer(
                            call -> {
                                if (reasoning.getAndIncrement() == 0) {
                                    var calls = new ArrayList<ContentBlock>();
                                    calls.add(
                                            ToolUseBlock.builder()
                                                    .id("call")
                                                    .name("server__echo")
                                                    .input(Map.of("value", "hello"))
                                                    .content(
                                                            mapper.writeValueAsString(
                                                                    Map.of("value", "hello")))
                                                    .build());
                                    if (batch)
                                        calls.add(
                                                ToolUseBlock.builder()
                                                        .id("call2")
                                                        .name("server__echo")
                                                        .input(Map.of("value", "second"))
                                                        .content(
                                                                mapper.writeValueAsString(
                                                                        Map.of("value", "second")))
                                                        .build());
                                    if (cancel)
                                        calls.add(
                                                ToolUseBlock.builder()
                                                        .id("call3")
                                                        .name("server__echo")
                                                        .input(Map.of("value", "third"))
                                                        .content(
                                                                mapper.writeValueAsString(
                                                                        Map.of("value", "third")))
                                                        .build());
                                    return Flux.just(ChatResponse.builder().content(calls).build());
                                }
                                List<Msg> messages = call.getArgument(0);
                                if (!cancel)
                                    assertTrue(
                                            messages.stream()
                                                    .flatMap(
                                                            message ->
                                                                    message.getContent().stream())
                                                    .filter(TextBlock.class::isInstance)
                                                    .map(TextBlock.class::cast)
                                                    .anyMatch(
                                                            block ->
                                                                    block.getText()
                                                                            .contains(
                                                                                    "during"
                                                                                        + " wait")));
                                if (!cancel)
                                    assertTrue(
                                            messages.stream()
                                                    .flatMap(
                                                            message ->
                                                                    message.getContent().stream())
                                                    .filter(ToolResultBlock.class::isInstance)
                                                    .map(ToolResultBlock.class::cast)
                                                    .flatMap(result -> result.getOutput().stream())
                                                    .filter(TextBlock.class::isInstance)
                                                    .map(TextBlock.class::cast)
                                                    .anyMatch(
                                                            text ->
                                                                    text.getText()
                                                                            .contains("original")));
                                return Flux.just(
                                        ChatResponse.builder()
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("finished")
                                                                        .build()))
                                                .build());
                            });
            var workspace = new InMemoryStore();
            var agents = new AtomicInteger();
            ManagedHarnessRuntimeTurns.Runner runner =
                    execution -> {
                        execution.toolConfiguration(configuration);
                        Toolkit toolkit = batch ? new ManagedMcpBatchToolkit() : new Toolkit();
                        if (batch) {
                            // Harness skill setup registers auxiliary tools through the SDK
                            // builder.
                            toolkit.registration()
                                    .agentTool(
                                            new ManagedMcpTool(
                                                    description.get().tools().get(0),
                                                    requests,
                                                    mapper))
                                    .apply();
                        }
                        toolkit.registerTool(
                                new ManagedMcpTool(
                                        description.get().tools().get(0), requests, mapper));
                        try (var agent =
                                HarnessAgent.builder()
                                        .name("mcp-turn")
                                        .model(model)
                                        .workspace(root.resolve("harness"))
                                        .toolkit(toolkit)
                                        .toolsConfig(new ToolsConfig())
                                        .abstractFilesystem(new RemoteFilesystem(workspace))
                                        .sessionLogStore(new ManagedSessionLogStore(journal))
                                        .middleware(new ManagedRuntimeInputMiddleware(mapper))
                                        .permissionContext(
                                                PermissionContextState.builder()
                                                        .mode(PermissionMode.BYPASS)
                                                        .build())
                                        .disableFilesystemTools()
                                        .disableMemoryHooks()
                                        .disableMemoryTools()
                                        .disableSubagents()
                                        .disableDefaultWorkspaceSkills()
                                        .disableCompaction()
                                        .maxIters(2)
                                        .build()) {
                            agents.incrementAndGet();
                            var context =
                                    RuntimeContext.builder()
                                            .userId("user")
                                            .sessionId(scope.sessionId())
                                            .put(ManagedScope.class, scope)
                                            .put(ManagedTurnExecution.class, execution)
                                            .put(
                                                    ManagedToolRequests.Operations.class,
                                                    new ManagedToolRequests.Operations())
                                            .put(ToolCallParam.CALL_CONTEXT_KEY, true)
                                            .put(SessionRecorder.TURN_ID_KEY, execution.turnId())
                                            .build();
                            new ManagedHarnessTurns(
                                            mapper,
                                            new ManagedInputDelivery(admission, inbox),
                                            renewals)
                                    .run(execution, agent, context);
                        }
                    };
            var first =
                    new ManagedHarnessRuntimeTurns(
                            bus,
                            leases,
                            admission,
                            journal,
                            new ManagedRuntimeWriter(journal, commits),
                            mapper,
                            renewals,
                            Duration.ofSeconds(5),
                            Duration.ofMillis(500),
                            1,
                            Duration.ofMillis(500));
            closing.add(first);
            var selected = new AtomicReference<>(first);
            var earlyReplacement =
                    new ManagedHarnessRuntimeTurns(
                            bus,
                            leases,
                            admission,
                            journal,
                            new ManagedRuntimeWriter(journal, commits),
                            mapper,
                            renewals,
                            Duration.ofSeconds(5),
                            Duration.ofMillis(500),
                            1,
                            Duration.ofMillis(500));
            closing.add(earlyReplacement);
            var crashObserved = new AtomicBoolean();
            var acceptResume = new AtomicBoolean();
            closing.add(
                    bus.subscribe(
                            Channel.TURNS,
                            2,
                            d -> selected.get().consume(d, runner),
                            (d, e) -> {
                                if (crash > 0
                                        && crashed.get()
                                        && crashObserved.compareAndSet(false, true))
                                    selected.set(earlyReplacement);
                                else errors.add(e);
                            }));
            closing.add(
                    bus.subscribe(
                            Channel.TURN_RESUMES,
                            2,
                            d -> {
                                if (!acceptResume.get())
                                    throw new ManagedDeliverySuspended(Duration.ofMillis(100));
                                selected.get().consume(d, runner);
                            },
                            (d, e) -> errors.add(e)));
            replyDelayMillis.set(600);
            new ManagedTurnPublisher(admission, bus, leases, mapper, Duration.ofSeconds(5))
                    .publish(scope, turn);
            await(() -> "waiting_tool".equals(admission.turn(scope, turn).status()));
            if (crash > 0) assertTrue(crashObserved.get());
            var next =
                    protocol.accept(
                            scope,
                            "actor",
                            "second-turn",
                            List.of(
                                    new Command(
                                            "user.message",
                                            List.of(Map.of("type", "text", "text", "follow up")),
                                            "new_turn",
                                            null,
                                            true)),
                            () -> {});
            String turn2 =
                    mapper.valueToTree(next.receipt())
                            .path("data")
                            .get(0)
                            .path("managed_agents")
                            .path("turn_id")
                            .asText();
            new ManagedTurnPublisher(admission, bus, leases, mapper, Duration.ofSeconds(5))
                    .publish(scope, turn2);
            var added =
                    protocol.accept(
                            scope,
                            "actor",
                            "during-wait",
                            List.of(
                                    new Command(
                                            "user.message",
                                            List.of(Map.of("type", "text", "text", "during wait")),
                                            "inject",
                                            turn,
                                            true)),
                            () -> {});
            String input =
                    mapper.valueToTree(added.receipt())
                            .path("data")
                            .get(0)
                            .path("managed_agents")
                            .path("input_id")
                            .asText();
            if (cancel)
                admission.acceptInput(
                        scope, turn, "actor", "cancel-batch", Type.CANCEL, null, "{}");
            assertEquals("accepted", admission.turn(scope, turn2).status());
            assertEquals("accepted", admission.input(scope, input).status());
            var replacement =
                    new ManagedHarnessRuntimeTurns(
                            bus,
                            leases,
                            admission,
                            journal,
                            new ManagedRuntimeWriter(journal, commits),
                            mapper,
                            renewals,
                            Duration.ofSeconds(5),
                            Duration.ofMillis(500),
                            1,
                            Duration.ofMillis(500));
            closing.add(replacement);
            selected.set(replacement);
            first.close();
            acceptResume.set(true);
            await(
                    () ->
                            (cancel ? "cancelled" : "succeeded")
                                    .equals(admission.turn(scope, turn).status()));
            if (cancel) {
                assertTrue(invocations.get() < 3);
                String aggregate =
                        new JdbcTemplate(runtimeSource)
                                .queryForObject(
                                        "SELECT result FROM ma.tool_task WHERE"
                                                + " tool_name='mcp.batch'",
                                        String.class);
                assertEquals(
                        "cancelled", ManagedMcpBatch.result(aggregate).require("call3").status());
            } else assertEquals(batch ? 2 : 1, invocations.get());
            if (batch) {
                var jdbc = new JdbcTemplate(runtimeSource);
                assertEquals(
                        1,
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ma.tool_task WHERE tool_name='mcp.batch'",
                                Integer.class));
                // An uncommitted wave is not a recovery fact. Retry its already approved native
                // call first; the SDK authorizes the remaining call in its next batch.
                assertEquals(
                        crash == 1 ? 1 : 0,
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ma.tool_task WHERE tool_name='mcp.call'",
                                Integer.class));
                assertEquals(
                        crash == 1 ? 2 : 1,
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ma.turn_resume WHERE"
                                        + " delivery_status='queued'",
                                Integer.class));
            }
            await(() -> "succeeded".equals(admission.turn(scope, turn2).status()));
            if (!cancel) assertEquals("applied", admission.input(scope, input).status());
            assertEquals(cancel ? 2 : 3, reasoning.get());
            assertEquals(crash == 1 ? 5 : crash == 2 ? 4 : 3, agents.get());
            if (!cancel)
                assertEquals(true, protocol.output(scope, turn, null, 100).get("output_available"));
            assertEquals(true, protocol.output(scope, turn2, null, 100).get("output_available"));
            while (journal.projections().project(scope, 128) > 0) {
                /* Agent Service materializes the committed facts. */
            }
            var history = protocol.history(scope, "actor", Map.of("limit", List.of("256")), true);
            var wires = mapper.valueToTree(history).path("data");
            long useCount = 0, resultCount = 0;
            var useIds = new HashSet<String>();
            var resultUses = new HashSet<String>();
            int completed = -1, nextRunning = -1, index = 0;
            for (var event : wires) {
                if ("agent.mcp_tool_use".equals(event.path("type").asText())) {
                    useCount++;
                    useIds.add(event.path("id").asText());
                }
                if ("agent.mcp_tool_result".equals(event.path("type").asText())) {
                    resultCount++;
                    resultUses.add(event.path("mcp_tool_use_id").asText());
                    assertTrue(
                            event.path("content").toString().contains("original")
                                    || (cancel
                                            && event.path("content")
                                                    .toString()
                                                    .contains("Cancelled")));
                }
                if ((cancel ? "managed_agents.turn.cancelled" : "managed_agents.turn.completed")
                                .equals(event.path("type").asText())
                        && turn.equals(event.path("managed_agents").path("turn_id").asText()))
                    completed = index;
                if ("managed_agents.turn.running".equals(event.path("type").asText())
                        && turn2.equals(event.path("managed_agents").path("turn_id").asText()))
                    nextRunning = index;
                index++;
            }
            assertEquals(cancel ? 3 : batch ? 2 : 1, useCount);
            assertEquals(cancel ? 3 : batch ? 2 : 1, resultCount);
            var tools = journal.publicTools(scope, turn, null, 100);
            assertEquals(cancel ? 3 : batch ? 2 : 1, tools.data().size());
            assertEquals(
                    tools.data().size(),
                    tools.data().stream().map(value -> value.get("task_id")).distinct().count());
            if (batch) {
                var page = journal.publicTools(scope, turn, null, 1);
                var following = journal.publicTools(scope, turn, page.next(), 1);
                assertTrue(page.hasMore());
                assertEquals(1, following.data().size());
                assertTrue(
                        !page.data()
                                .get(0)
                                .get("task_id")
                                .equals(following.data().get(0).get("task_id")));
                assertTrue(
                        tools.data().stream()
                                .allMatch(value -> "committed".equals(value.get("result_status"))));
                if (cancel)
                    assertEquals(
                            "cancelled",
                            tools.data().stream()
                                    .filter(value -> "call3".equals(value.get("tool_call_id")))
                                    .findFirst()
                                    .orElseThrow()
                                    .get("status"));
            }

            assertEquals(useCount, useIds.size());
            assertEquals(resultCount, resultUses.size());
            assertEquals(useIds, resultUses);
            assertTrue(
                    completed >= 0 && nextRunning > completed,
                    "terminal=" + completed + ", nextRunning=" + nextRunning);
            assertTrue(errors.isEmpty(), errors::toString);
        } finally {
            for (int i = closing.size() - 1; i >= 0; i--) closing.get(i).close();
        }
    }

    void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(7).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }
}
