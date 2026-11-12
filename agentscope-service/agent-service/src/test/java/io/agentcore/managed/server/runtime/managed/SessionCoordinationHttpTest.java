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
package io.agentcore.managed.server.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentcore.managed.harness.runtime.RunConfigurationContract;
import io.agentcore.managed.harness.runtime.SessionServiceClient;
import io.agentcore.managed.server.agentservice.config.AgentServiceJsonConfiguration;
import io.agentcore.managed.server.agentservice.sessionservice.api.SessionCoordinationApi;
import io.agentcore.managed.server.agentservice.sessionservice.store.HarnessRunConfiguration;
import io.agentcore.managed.server.agentservice.sessionservice.store.SessionServiceCoordination;
import io.agentcore.managed.server.control.SessionResolveResult;
import io.agentcore.managed.server.runtime.ManagedCatalog;
import io.agentcore.managed.server.runtime.managed.ManagedRuntimeBatch.ToolIntent;
import io.agentcore.managed.server.runtime.managed.ManagedTurnPublisher.Command;
import io.agentcore.managed.server.runtime.protocol.RuntimeBootstrapDigest;
import io.agentcore.managed.server.runtime.protocol.RuntimeProtocol;
import io.agentcore.managed.server.runtime.protocol.RuntimeRequestContext;
import io.agentcore.managed.server.runtime.protocol.RuntimeSessionBootstrap;
import io.agentcore.managed.server.web.managed.ManagedSessionDto;
import io.agentcore.managed.server.web.managed.service.SessionEventNotifier;
import io.agentcore.managed.server.web.managed.service.VaultCrypto;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.rocketmq.client.java.impl.consumer.ManagedLitePushConsumer.Ack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/** Real HTTP and durable metadata, with no DataSource supplied to any Brain coordination facade. */
@Timeout(30)
class SessionCoordinationHttpTest {
    @TempDir Path directory;
    final ObjectMapper mapper = new ObjectMapper();
    final ManagedScope scope = new ManagedScope("tenant", "space", "session");
    final VaultCrypto crypto =
            new VaultCrypto(new MockEnvironment(), "01234567890123456789012345678901");
    ManagedAdmissionStore service;
    ManagedJournalStore journal;
    ManagedSubagents children;
    JdbcTemplate jdbc;
    String turn;
    AnnotationConfigApplicationContext context;
    DisposableServer http;
    SessionServiceClient client;
    HarnessRunConfiguration modelConfigurations;
    SessionServiceCoordination coordination;

    @Configuration
    @EnableWebFlux
    static class HttpConfiguration {}

    @BeforeEach
    void setup() {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:coord-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        ManagedAdmissionStore.initialize(source);
        jdbc = new JdbcTemplate(source);
        service = new ManagedAdmissionStore(source, mapper);
        var budgets = new ManagedBudgetStore(source, mapper, (model, usage) -> null);
        journal =
                new ManagedJournalStore(
                        source,
                        mapper,
                        budgets,
                        new ManagedCheckpointObjects(
                                mapper, new FileRuntimeObjectStore("test", directory)),
                        new SessionEventNotifier(source, 1000));
        children = new ManagedSubagents(source, mapper, service, journal);
        var catalogs =
                new ManagedMcpCatalog(
                        source,
                        mapper,
                        crypto,
                        journal.transport(),
                        (tools, target, cancelled) -> {
                            throw new AssertionError(
                                    "Service must not launch a Brain-local MCP transport");
                        });
        modelConfigurations =
                new HarnessRunConfiguration(
                        source,
                        new ManagedCatalog(service, mapper, crypto, children),
                        catalogs,
                        service,
                        new FileRuntimeObjectStore("test", directory),
                        mapper,
                        "managed");
        coordination =
                new SessionServiceCoordination(
                        source, mapper, service, children, catalogs, 600, 3600);
        ReflectionTestUtils.setField(coordination, "configurations", modelConfigurations);
        context = new AnnotationConfigApplicationContext();
        context.register(HttpConfiguration.class, AgentServiceJsonConfiguration.class);
        context.registerBean(ObjectMapper.class, () -> mapper);
        context.registerBean(
                SessionCoordinationApi.class, () -> new SessionCoordinationApi(coordination));
        context.refresh();
        http =
                HttpServer.create()
                        .host("127.0.0.1")
                        .port(0)
                        .handle(
                                new ReactorHttpHandlerAdapter(
                                        WebHttpHandlerBuilder.applicationContext(context).build()))
                        .bindNow();
        client =
                new SessionServiceClient(
                        URI.create(
                                "http://127.0.0.1:" + http.port() + "/internal/session-service/v0"),
                        "internal-test-token",
                        mapper,
                        Duration.ofSeconds(5));
        service.createSession(scope, "{}");
        turn = service.acceptTurn(scope, "actor", "input", "{}").id();
        service.reservePublication(scope, turn);
        service.confirmPublication(scope, turn, "broker");
    }

    @AfterEach
    void close() {
        if (http != null) http.disposeNow();
        if (context != null) context.close();
    }

    ManagedProcessOwnership process(SessionServiceClient transport) {
        return new ManagedProcessOwnership(
                transport, Duration.ofSeconds(30), Duration.ofMinutes(10), Duration.ofHours(1));
    }

    SessionServiceClient loseFirst(String operation) {
        var faulty = spy(client);
        var lost = new AtomicBoolean();
        doAnswer(
                        invocation -> {
                            Object response = invocation.callRealMethod();
                            var request = mapper.valueToTree(invocation.getArgument(1));
                            if (operation.equals(request.path("operation").asText())
                                    && lost.compareAndSet(false, true))
                                throw new SessionServiceClient.Unavailable(
                                        "Committed response lost", null);
                            return response;
                        })
                .when(faulty)
                .post(eq("prepared/coordination"), any());
        return faulty;
    }

    @Test
    void preparedModelSelectionUsesSealedOverridesAndRejectsStaleOwnersOrOtherKernels()
            throws Exception {
        var modelScope = new ManagedScope("tenant", "space", "model-session");
        String modelTurn = admitModelDefinition(modelScope);
        ReflectionTestUtils.setField(
                modelConfigurations,
                "modelBindings",
                "{\"selected\":{\"provider\":\"managed\",\"id\":\"native-model\",\"api\":\"openai-completions\",\"credentialEnv\":\"MANAGED_MODEL_BOUND\",\"contextWindow\":8192,\"outputLimit\":1024,\"parameters\":{\"maxTokens\":512}}}");
        try (var process = process(client)) {
            var brain = new ManagedAdmissionStore(client, mapper, process);
            brain.activate(modelScope, modelTurn, 1, "model-owner");
            var request =
                    mapper.createObjectNode()
                            .put("protocolVersion", "0.1.0")
                            .put("operation", "model-configuration");
            var data =
                    request.putObject("data")
                            .put("turnId", modelTurn)
                            .put("epoch", 1)
                            .put("token", "model-owner");
            data.set("scope", mapper.valueToTree(modelScope));
            var response = client.post("prepared/coordination", request).required("result");
            RunConfigurationContract.validateModelBinding(response);
            assertEquals("binding", response.path("configuration").path("mode").asText());
            assertEquals(
                    "native-model",
                    response.path("configuration").path("model").path("id").asText());
            assertEquals("agent", response.path("identity").path("agentId").asText());
            assertEquals(3, response.path("identity").path("agentVersion").asInt());
            assertEquals(
                    "agentscope-java",
                    jdbc.queryForObject(
                            "SELECT harness_type FROM ma.harness_binding WHERE scope_key=?",
                            String.class,
                            modelScope.messageGroup()));
            // Changed deployment configuration cannot reinterpret an admitted Turn.
            ReflectionTestUtils.setField(modelConfigurations, "modelBindings", "{}");
            assertEquals(
                    response, client.post("prepared/coordination", request).required("result"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> modelConfigurations.resolveModel(modelScope, modelTurn, "pi"));
            data.put("token", "stale-owner");
            assertThrows(
                    SessionServiceClient.Rejected.class,
                    () -> client.post("prepared/coordination", request));
            data.put("token", "model-owner").put("epoch", 2);
            assertThrows(
                    SessionServiceClient.Rejected.class,
                    () -> client.post("prepared/coordination", request));
            data.put("epoch", 1);
            jdbc.update(
                    "UPDATE ma_worker.lease SET expires_at=0 WHERE incarnation=?",
                    process.incarnation());
            assertThrows(
                    SessionServiceClient.Rejected.class,
                    () -> client.post("prepared/coordination", request));
            var corrupt = response.deepCopy();
            ((ObjectNode) corrupt.required("configuration").required("model"))
                    .put("id", "substituted");
            jdbc.update(
                    "UPDATE ma.harness_model_configuration SET payload=? WHERE scope_key=? AND"
                            + " turn_id=?",
                    corrupt.toString(),
                    modelScope.messageGroup(),
                    modelTurn);
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            modelConfigurations.resolveModel(
                                    modelScope, modelTurn, "agentscope-java"));
        }
    }

    @Test
    void nativeModelSelectionIsExplicitAndMalformedCatalogsCannotFallBack() throws Exception {
        var modelScope = new ManagedScope("tenant", "space", "native-model-session");
        String modelTurn = admitModelDefinition(modelScope);
        for (String invalid : List.of("[]", "null", "{\"secret\":", "true")) {
            ReflectionTestUtils.setField(modelConfigurations, "modelBindings", invalid);
            var error =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    modelConfigurations.resolveModel(
                                            modelScope, modelTurn, "agentscope-java"));
            assertFalse(error.getMessage().contains("secret"));
        }
        ReflectionTestUtils.setField(modelConfigurations, "modelBindings", "{}");
        ReflectionTestUtils.setField(modelConfigurations, "modelOptions", "[]");
        assertThrows(
                IllegalArgumentException.class,
                () -> modelConfigurations.resolveModel(modelScope, modelTurn, "agentscope-java"));
        ReflectionTestUtils.setField(modelConfigurations, "modelOptions", "{}");
        var selection =
                modelConfigurations
                        .resolveModel(modelScope, modelTurn, "agentscope-java")
                        .required("configuration");
        assertEquals("native", selection.required("mode").asText());
        assertEquals("selected", selection.required("name").asText());
    }

    private String admitModelDefinition(ManagedScope modelScope) throws Exception {
        var session =
                mapper.convertValue(
                        Map.of(
                                "id",
                                modelScope.sessionId(),
                                "ownerId",
                                "owner",
                                "agentId",
                                "agent",
                                "agentVersion",
                                3,
                                "agentOverridesJson",
                                "{\"model\":\"selected\"}"),
                        ManagedSessionDto.class);
        var resolved =
                new SessionResolveResult(
                        session,
                        Map.of("name", "model-test", "model", "original"),
                        null,
                        null,
                        null,
                        Map.of(),
                        null,
                        List.of(),
                        List.of(),
                        Map.of(),
                        Map.of());
        Map<String, Object> sealed = mapper.convertValue(resolved, new TypeReference<>() {});
        var bootstrap =
                new RuntimeSessionBootstrap(
                        new RuntimeRequestContext(
                                RuntimeProtocol.VERSION,
                                modelScope.tenant(),
                                modelScope.namespace(),
                                null,
                                modelScope.sessionId(),
                                null,
                                "bootstrap",
                                "trace",
                                Map.of(),
                                "initial",
                                Long.MAX_VALUE),
                        "managed",
                        RuntimeBootstrapDigest.configurationRevision(mapper, sealed),
                        sealed);
        service.createSession(
                modelScope,
                mapper.writeValueAsString(
                        Map.of(
                                "encrypted_definition",
                                crypto.encrypt(mapper.writeValueAsString(bootstrap)))));
        String modelTurn =
                service.acceptTurn(modelScope, "actor", "input", "{\"text\":\"hello\"}").id();
        service.reservePublication(modelScope, modelTurn);
        service.confirmPublication(modelScope, modelTurn, "broker");
        return modelTurn;
    }

    @Test
    void uncertainRegistrationAndClaimRetryTheirOriginalIdentity() {
        try (var owner = process(loseFirst("process-register"))) {
            assertEquals(
                    1, jdbc.queryForObject("SELECT COUNT(*) FROM ma_worker.lease", Integer.class));
            var brain = new ManagedAdmissionStore(loseFirst("turn-claim"), mapper, owner);
            assertEquals(1, brain.activate(scope, turn, 1, "guard"));
            assertEquals(1, brain.activate(scope, turn, 1, "guard"));
            assertEquals(1, service.session(scope).executionEpoch());
            assertEquals(
                    owner.incarnation(),
                    jdbc.queryForObject(
                            "SELECT owner_incarnation FROM ma.turn WHERE turn_id=?",
                            String.class,
                            turn));
            assertEquals(scope, brain.sessionsById(scope.sessionId()).get(0).scope());
            var input =
                    service.acceptInput(
                            scope,
                            turn,
                            "actor",
                            "inject",
                            ManagedInputInbox.Type.INJECT,
                            null,
                            "{}");
            assertEquals(input.id(), brain.pendingInputs(scope, turn, 32).get(0).id());
            brain.confirmInputDelivery(scope, input.id());
            assertEquals("delivered", brain.input(scope, input.id()).deliveryStatus());
            assertEquals(input.sequence(), brain.closeInputs(scope, turn, 1));
        }
    }

    @Test
    void liveOwnerIsProtectedAndExpiredProcessCannotRevive() {
        try (var first = process(client);
                var second = process(client)) {
            var old = new ManagedAdmissionStore(client, mapper, first);
            var replacement = new ManagedAdmissionStore(client, mapper, second);
            old.activate(scope, turn, 1, "old");
            assertEquals(
                    "execution_busy",
                    assertThrows(
                                    ManagedStateException.class,
                                    () -> replacement.activate(scope, turn, 1, "new"))
                            .code());
            jdbc.update(
                    "UPDATE ma_worker.lease SET expires_at=0 WHERE incarnation=?",
                    first.incarnation());
            assertEquals(
                    0,
                    ManagedServiceCalls.coordinate(
                                    client,
                                    "process-renew",
                                    Map.of("process", first.identity(), "ttlMillis", 30000))
                            .longValue());
            assertEquals(
                    "worker_lease_expired",
                    assertThrows(
                                    ManagedStateException.class,
                                    () ->
                                            ManagedServiceCalls.coordinate(
                                                    client,
                                                    "process-register",
                                                    Map.of(
                                                            "process",
                                                            first.identity(),
                                                            "ttlMillis",
                                                            30000)))
                            .code());
            assertEquals(2, replacement.activate(scope, turn, 1, "new"));
            assertEquals(
                    "worker_lease_expired",
                    assertThrows(
                                    ManagedStateException.class,
                                    () -> old.activate(scope, turn, 1, "old"))
                            .code());
            assertFalse(old.abandonExecution(scope, turn, 1, "old"));
        }
    }

    @Test
    void fixedFragmentDeadlineSurvivesProcessRenewalsAndFencesOldMutations() {
        try (var first = process(client);
                var second = process(client)) {
            var old = new ManagedAdmissionStore(client, mapper, first);
            var replacement = new ManagedAdmissionStore(client, mapper, second);
            old.activate(scope, turn, 1, "old");
            jdbc.update("UPDATE ma.turn SET execution_deadline=0 WHERE turn_id=?", turn);
            assertTrue(
                    ManagedServiceCalls.coordinate(
                                            client,
                                            "process-renew",
                                            Map.of("process", first.identity(), "ttlMillis", 30000))
                                    .longValue()
                            > 0);
            assertEquals(
                    "stale_execution",
                    assertThrows(ManagedStateException.class, () -> old.closeInputs(scope, turn, 1))
                            .code());
            assertEquals(2, replacement.activate(scope, turn, 1, "new"));
            assertEquals(
                    "stale_execution",
                    assertThrows(ManagedStateException.class, () -> old.closeInputs(scope, turn, 1))
                            .code());
            assertEquals(0, replacement.closeInputs(scope, turn, 2));
        }
    }

    @Test
    void ackLedgerSurvivesConsumerReplacementAndLostClaimResponse() {
        var attempts = new AtomicInteger();
        var command = new Command(scope, turn, 1);
        var receipt =
                new RocketMqTurnReceipt(
                        "message",
                        "turns",
                        scope.messageGroup(),
                        "private-handle",
                        "queue",
                        1,
                        1,
                        1);
        var first =
                new ManagedTurnAcknowledgements(
                        client,
                        mapper,
                        "turns",
                        "brain",
                        r -> {
                            attempts.incrementAndGet();
                            return Ack.ACKNOWLEDGED;
                        });
        first.suspended(command, receipt, Duration.ofSeconds(60));
        assertEquals(0, first.retry(8));
        jdbc.update("UPDATE ma.turn SET status='succeeded' WHERE turn_id=?", turn);
        jdbc.update("UPDATE ma.turn_ack_receipt SET ready=TRUE WHERE turn_id=?", turn);
        var lost =
                new ManagedTurnAcknowledgements(
                        loseFirst("ack-claim"),
                        mapper,
                        "turns",
                        "brain",
                        r -> {
                            attempts.incrementAndGet();
                            return Ack.ACKNOWLEDGED;
                        });
        assertThrows(SessionServiceClient.Unavailable.class, () -> lost.retry(8));
        assertEquals(0, attempts.get());
        assertEquals(0, first.retry(8));
        jdbc.update("UPDATE ma.turn_ack_receipt SET next_attempt_at=0");
        var replacement =
                new ManagedTurnAcknowledgements(
                        client,
                        mapper,
                        "turns",
                        "brain",
                        r -> {
                            attempts.incrementAndGet();
                            return Ack.ACKNOWLEDGED;
                        });
        assertEquals(1, replacement.retry(8));
        assertEquals(
                0, jdbc.queryForObject("SELECT COUNT(*) FROM ma.turn_ack_receipt", Integer.class));
        // Terminal-before-capture takes the same Session lock and is immediately eligible.
        replacement.suspended(command, receipt, Duration.ofSeconds(60));
        assertEquals(1, replacement.retry(8));
        assertEquals(2, attempts.get());
    }

    @Test
    void sessionQueueRetainsGenerationAndDispatchTokenAcrossRpc() {
        var queued = new ManagedScope("tenant", "space", "queued");
        service.createSession(queued, "{}");
        service.queue().enable(queued);
        String queuedTurn = service.acceptTurn(queued, "actor", "input", "{}").id();
        try (var owner = process(client)) {
            var queue = new ManagedAdmissionStore(client, mapper, owner).queue();
            assertTrue(queue.enabled(queued));
            assertEquals(queuedTurn, queue.candidate(queued));
            assertTrue(queue.due(32).contains(queued));
            var dispatch = queue.acquire(queued);
            assertNotNull(dispatch);
            var ready = service.queue().notification(queued);
            queue.validate(ready);
            queue.defer(ready, Duration.ofMillis(10));
            queue.release(dispatch);
            assertTrue(
                    jdbc.queryForObject(
                            "SELECT requested_generation>handled_generation FROM"
                                    + " ma.session_dispatch WHERE scope_key=?",
                            Boolean.class,
                            queued.messageGroup()));
            var stale =
                    new ManagedSessionQueue.Ready(
                            1,
                            queued,
                            ManagedSessionQueue.MODE,
                            ready.schedulerGeneration() + 1,
                            ready.requestedGeneration(),
                            ready.notificationId());
            assertEquals(
                    "scheduler_generation_mismatch",
                    assertThrows(ManagedStateException.class, () -> queue.validate(stale)).code());
        }
    }

    @Test
    void mcpDiscoveryKeepsLocalEnvironmentWhileServicePinsOneDescription() {
        var server = new McpServerConfig();
        server.setTransport("stdio");
        server.setCommand("brain-local-mcp");
        server.setEnv(Map.of("MCP_SECRET", "private-local-credential"));
        var tools = new ToolsConfig();
        tools.setMcpServers(Map.of("local", server));
        var resolved =
                new SessionResolveResult(
                        null,
                        Map.of("modelSecret", "brain-only"),
                        "/brain/workspace",
                        null,
                        null,
                        Map.of(),
                        null,
                        List.of(),
                        List.of(),
                        null,
                        Map.of());
        var description =
                new ManagedMcpCatalog.Description(
                        List.of(
                                new ManagedMcpCatalog.Tool(
                                        "local",
                                        "local__echo",
                                        "echo",
                                        "Echo",
                                        Map.of("type", "object"),
                                        null,
                                        true)),
                        List.of());
        var discoveries = new AtomicInteger();
        var remote =
                new ManagedMcpCatalog(
                        client,
                        mapper,
                        crypto,
                        (config, target, cancelled) -> {
                            assertEquals("/brain/workspace", target.workspacePath());
                            assertEquals("brain-only", target.agentSnapshot().get("modelSecret"));
                            assertFalse(cancelled.getAsBoolean());
                            discoveries.incrementAndGet();
                            return description;
                        });
        assertEquals(description, remote.require(scope, tools, resolved, turn));
        assertEquals(description, remote.require(scope, tools, resolved, turn));
        assertEquals(1, discoveries.get());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ma.mcp_catalog", Integer.class));
        assertFalse(
                jdbc.queryForObject("SELECT payload FROM ma.mcp_catalog", String.class)
                        .contains("private-local-credential"));
    }

    @Test
    void childReadsReuseTheServiceAdmittedFrozenIdentity() throws Exception {
        service.activate(scope, turn, 1, "owner");
        var spec = new ManagedSubagents.Specification("worker", "task", "result", "{}");
        String arguments =
                mapper.writeValueAsString(Map.of("subagent", spec, "native_tool_call_id", "spawn"));
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
                        "{\"stateJson\":\"{}\",\"reason\":\"test\"}");
        AgentScopePreparation.commit(
                journal,
                new SessionCommitTransactions(journal),
                AgentScopeCheckpoints.prepare(
                        journal.objects(),
                        new ManagedRuntimeBatch(
                                scope,
                                turn,
                                1,
                                "owner",
                                "owner",
                                "spawn",
                                0,
                                List.of(checkpoint),
                                List.of(
                                        new ToolIntent(
                                                "spawn", ManagedSubagents.OPERATION, arguments)),
                                List.of(),
                                null)));
        String task = "tool_" + ManagedScope.fingerprint(scope.messageGroup(), turn, "spawn");
        var admitted = children.start(scope, task);
        var remote = new ManagedSubagents(client, mapper);
        assertEquals(admitted, remote.find(scope, task).orElseThrow());
        assertEquals(admitted, remote.parent(admitted.child()).orElseThrow());
        assertEquals(
                admitted, remote.parent(admitted.child(), admitted.childTurnId()).orElseThrow());
        assertTrue(remote.parent(new ManagedScope("tenant", "space", "absent")).isEmpty());
        assertThrows(IllegalStateException.class, () -> remote.start(scope, task));
        assertEquals(admitted, children.start(scope, task));
    }
}
