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
package io.agentcore.managed.server.agentservice.sessionservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.agentcore.managed.harness.runtime.CanonicalJson;
import io.agentcore.managed.harness.runtime.ExecutionAccess;
import io.agentcore.managed.harness.runtime.ProtocolSchemas;
import io.agentcore.managed.harness.runtime.SessionExecutionAccess;
import io.agentcore.managed.harness.runtime.SessionServiceClient;
import io.agentcore.managed.server.agentservice.sessionservice.store.HarnessProtocolStore;
import io.agentcore.managed.server.agentservice.sessionservice.store.SessionServiceCoordination;
import io.agentcore.managed.server.agentservice.sessionservice.store.SessionServiceOwnership;
import io.agentcore.managed.server.agentservice.sessionservice.store.SessionServiceStore;
import io.agentcore.managed.server.agentservice.sessionservice.store.SessionServiceWorkspace;
import io.agentcore.managed.server.runtime.managed.FileRuntimeObjectStore;
import io.agentcore.managed.server.runtime.managed.ManagedAdmissionStore;
import io.agentcore.managed.server.runtime.managed.ManagedAsyncToolRegistry;
import io.agentcore.managed.server.runtime.managed.ManagedBatchCodec;
import io.agentcore.managed.server.runtime.managed.ManagedBudgetStore;
import io.agentcore.managed.server.runtime.managed.ManagedCheckpointObjects;
import io.agentcore.managed.server.runtime.managed.ManagedHarnessTurns;
import io.agentcore.managed.server.runtime.managed.ManagedHttpExecutions;
import io.agentcore.managed.server.runtime.managed.ManagedInputDelivery;
import io.agentcore.managed.server.runtime.managed.ManagedInputInbox;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus;
import io.agentcore.managed.server.runtime.managed.ManagedModelPolicy;
import io.agentcore.managed.server.runtime.managed.ManagedPreviewFixture;
import io.agentcore.managed.server.runtime.managed.ManagedPublicResources;
import io.agentcore.managed.server.runtime.managed.ManagedQueryViews;
import io.agentcore.managed.server.runtime.managed.ManagedRemoteFilesystem;
import io.agentcore.managed.server.runtime.managed.ManagedRuntimeInputMiddleware;
import io.agentcore.managed.server.runtime.managed.ManagedRuntimeWriter;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import io.agentcore.managed.server.runtime.managed.ManagedSessionLogStore;
import io.agentcore.managed.server.runtime.managed.ManagedSessionMessageBus;
import io.agentcore.managed.server.runtime.managed.ManagedStateException;
import io.agentcore.managed.server.runtime.managed.ManagedTaskRepository;
import io.agentcore.managed.server.runtime.managed.ManagedToolEvent;
import io.agentcore.managed.server.runtime.managed.ManagedToolRequests;
import io.agentcore.managed.server.runtime.managed.ManagedToolResult;
import io.agentcore.managed.server.runtime.managed.ManagedToolResultDelivery;
import io.agentcore.managed.server.runtime.managed.ManagedTurnExecution;
import io.agentcore.managed.server.runtime.managed.RemoteRuntimeObjectStore;
import io.agentcore.managed.server.runtime.managed.RuntimeObjectStore;
import io.agentcore.managed.server.runtime.managed.projection.PostgresSessionProjectionStore;
import io.agentcore.managed.server.web.managed.service.SessionEventNotifier;
import io.agentscope.core.agent.RuntimeContext;
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
import io.agentscope.core.session.SessionModelPolicy;
import io.agentscope.core.session.SessionRecorder;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.HarnessAgent;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import reactor.core.publisher.Flux;

class HarnessProtocolStoreTest {
    @TempDir Path directory;
    final ObjectMapper mapper = new ObjectMapper();
    final ManagedScope scope = new ManagedScope("tenant", "test", "session");
    final ProtocolSchemas schemas = new ProtocolSchemas();
    DriverManagerDataSource source;
    JdbcTemplate jdbc;
    ManagedAdmissionStore admission;
    ManagedJournalStore journal;
    HarnessProtocolStore service;
    String turn;
    JsonNode claim;
    String harness;

    @BeforeEach
    void setup() {
        setup("pi");
    }

    void setup(String harnessType) {
        harness = harnessType;
        source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        ManagedAdmissionStore.initialize(source);
        PostgresSessionProjectionStore.initialize(source);
        jdbc = new JdbcTemplate(source);
        var views =
                new ManagedQueryViews(
                        new PostgresSessionProjectionStore(source, mapper),
                        mapper,
                        "01234567890123456789012345678901".getBytes(),
                        "v1",
                        1000,
                        new ManagedPublicResources(source, mapper));
        admission = new ManagedAdmissionStore(source, mapper);
        admission.queryViews(views);
        admission.createSession(scope, "{}");
        admission.queue().enable(scope);
        turn = admission.acceptTurn(scope, "actor", "input", "{}").id();
        var objects = new FileRuntimeObjectStore("test", directory);
        journal =
                new ManagedJournalStore(
                        source,
                        mapper,
                        null,
                        new ManagedCheckpointObjects(mapper, objects),
                        new SessionEventNotifier(source, 1000));
        journal.queryViews(views);
        service = new HarnessProtocolStore(source, admission, journal, objects, mapper, null);
        service.harnessType(harness);
        claim =
                service.invoke(
                        "executions/claim",
                        request(
                                Map.of(
                                        "claimId",
                                        "claim",
                                        "harnessType",
                                        harness,
                                        "owner",
                                        Map.of("instanceId", "brain", "incarnationId", "process"),
                                        "trigger",
                                        Map.of(
                                                "kind",
                                                "ready",
                                                "messageId",
                                                "ready",
                                                "queueVersion",
                                                "1"))));
        schemas.validate("execution/ClaimReceipt", claim);
    }

    ObjectNode request(Map<String, ?> fields) {
        ObjectNode value = mapper.valueToTree(fields);
        value.put("protocolVersion", "0.1.0");
        value.set("scope", mapper.valueToTree(scope));
        return value;
    }

    JsonNode object(String data) {
        byte[] bytes = data.getBytes();
        var ref =
                service.invoke(
                        "objects/write",
                        request(
                                Map.of(
                                        "objectId",
                                        RuntimeObjectStore.sha256(bytes),
                                        "sha256",
                                        RuntimeObjectStore.sha256(bytes),
                                        "size",
                                        Integer.toString(bytes.length),
                                        "mediaType",
                                        "application/json",
                                        "formatVersion",
                                        "test-v1",
                                        "dataBase64",
                                        Base64.getEncoder().encodeToString(bytes))));
        schemas.validate("common/ObjectWriteResponse", ref);
        return ref.required("ref");
    }

    ObjectNode boundary(String id) {
        return request(
                Map.of(
                        "turnId",
                        turn,
                        "fence",
                        claim.required("fence"),
                        "commitId",
                        id,
                        "expectedBrainRevision",
                        "0",
                        "nativeState",
                        Map.of(
                                "harnessType",
                                harness,
                                "stateFormatVersion",
                                "test-v1",
                                "nativeCursor",
                                "1",
                                "checkpointRef",
                                object("{}")),
                        "changes",
                        Map.of()));
    }

    @Test
    void codexClaimsAndCommitsItsOwnNativeState() {
        setup("codex");
        var request = boundary("codex-native");
        ((ObjectNode) request.required("nativeState"))
                .put("stateFormatVersion", "codex-rs-e742db9f-v1");
        var receipt = service.invoke("commits", request);
        schemas.validate("commit/CommitReceipt", receipt);
        assertEquals(CanonicalJson.hash(request), receipt.path("contentHash").asText());
        assertEquals(
                "codex",
                jdbc.queryForObject(
                        "SELECT harness_type FROM ma.harness_binding WHERE scope_key=?",
                        String.class,
                        scope.messageGroup()));
    }

    @Test
    void commitsNativeHeadAndReturnsOriginalReceiptAfterOwnerExpires() {
        var request = boundary("first");
        var receipt = service.invoke("commits", request);
        schemas.validate("commit/CommitReceipt", receipt);
        assertEquals(CanonicalJson.hash(request), receipt.path("contentHash").asText());
        assertEquals(1, journal.head(scope).sequence());
        jdbc.update("UPDATE ma.harness_lease SET expires_at=0");
        assertEquals(receipt, service.invoke("commits", request));
        assertEquals(
                receipt,
                service.invoke(
                        "commits/receipt", request(Map.of("turnId", turn, "commitId", "first"))));
        request.put("commitId", "new").put("expectedBrainRevision", "1");
        assertEquals(
                "stale_execution",
                assertThrows(ManagedStateException.class, () -> service.invoke("commits", request))
                        .code());
        assertEquals(1, journal.head(scope).sequence());
    }

    @Test
    void snapshotAndToolAuthorizationCommitTogetherAndConflictingReplayCannotChangeArguments() {
        var request = boundary("tools");
        String task = "tool_" + ManagedScope.fingerprint(scope.messageGroup(), turn, "call");
        request.set(
                "changes",
                mapper.valueToTree(
                        Map.of(
                                "toolCalls",
                                List.of(
                                        Map.of(
                                                "nativeCallId",
                                                "call",
                                                "position",
                                                Map.of("nativeCursor", "1"))),
                                "toolOperations",
                                List.of(
                                        Map.of(
                                                "operationId",
                                                "call",
                                                "taskId",
                                                task,
                                                "executionId",
                                                task,
                                                "nativeCallIds",
                                                List.of("call"),
                                                "toolName",
                                                "shell",
                                                "arguments",
                                                Map.of("inline", Map.of("command", "echo test")),
                                                "configurationRef",
                                                object("{}"))))));
        service.invoke("commits", request);
        assertTrue(journal.tool(scope, task).isPresent());
        assertEquals(1, journal.head(scope).sequence());
        var state =
                service.invoke(
                        "tools/state",
                        request(Map.of("turnId", turn, "taskId", task, "executionId", task)));
        schemas.validate("tools/ToolStateResponse", state);
        ((ObjectNode) request.path("changes").path("toolOperations").get(0))
                .put("toolName", "different");
        assertEquals(
                "request_id_conflict",
                assertThrows(ManagedStateException.class, () -> service.invoke("commits", request))
                        .code());
        assertEquals("shell", journal.tool(scope, task).orElseThrow().name());
        // Brain exits; Hands can still deliver its original result before the next owner starts.
        jdbc.update("UPDATE ma.harness_lease SET expires_at=0");
        var physical =
                new ManagedToolResult(scope, task, task, "succeeded", "{\"output\":\"done\"}");
        journal.commitToolEvent(
                new ManagedToolEvent(
                        scope,
                        task,
                        task,
                        1,
                        "tool.result",
                        Map.of(
                                "result",
                                mapper.convertValue(physical, Map.class),
                                "last_chunk_seq",
                                0,
                                "output_offset",
                                0,
                                "log_status",
                                "not_supported")));
        var takeover =
                service.invoke(
                        "executions/claim",
                        request(
                                Map.of(
                                        "claimId",
                                        "takeover",
                                        "harnessType",
                                        "pi",
                                        "owner",
                                        Map.of("instanceId", "new", "incarnationId", "new-process"),
                                        "trigger",
                                        Map.of(
                                                "kind",
                                                "ready",
                                                "messageId",
                                                "retry",
                                                "queueVersion",
                                                "1"))));
        var recovered =
                service.invoke(
                        "recovery/read",
                        request(Map.of("turnId", turn, "fence", takeover.required("fence"))));
        schemas.validate("recovery/RecoveryResponse", recovered);
        assertEquals(
                "succeeded",
                recovered.path("bundle").path("acceptedResults").get(0).path("status").asText());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ma.tool_task", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"pi", "pi-durable", "codex"})
    void nextSkipsUnreadySuspensionAndDiscoversResumeBeforeActivation(String kernel) {
        setup(kernel);
        String task = "tool_" + ManagedScope.fingerprint(scope.messageGroup(), turn, "call");
        var wait = boundary("wait");
        wait.set(
                "changes",
                mapper.valueToTree(
                        Map.of(
                                "toolCalls",
                                        List.of(
                                                Map.of(
                                                        "nativeCallId",
                                                        "call",
                                                        "position",
                                                        Map.of("nativeCursor", "1"))),
                                "toolOperations",
                                        List.of(
                                                Map.of(
                                                        "operationId",
                                                        "call",
                                                        "taskId",
                                                        task,
                                                        "executionId",
                                                        task,
                                                        "nativeCallIds",
                                                        List.of("call"),
                                                        "toolName",
                                                        "shell",
                                                        "arguments",
                                                        Map.of(
                                                                "inline",
                                                                Map.of("command", "echo test")),
                                                        "configurationRef",
                                                        object("{}"))),
                                "suspension",
                                        Map.of(
                                                "suspensionId",
                                                "suspended",
                                                "pendingTaskIds",
                                                List.of(task),
                                                "pendingActionIds",
                                                List.of(),
                                                "resumePhase",
                                                "WAIT_TOOLS",
                                                "waitPolicy",
                                                "all"))));
        service.invoke("commits", wait);
        var poll =
                mapper.valueToTree(
                        Map.of("protocolVersion", "0.1.0", "harnessType", kernel, "limit", 1));
        assertTrue(service.invoke("executions/next", poll).required("deliveries").isEmpty());
        var other = new ManagedScope("tenant", "test", "other-session");
        admission.createSession(other, "{}");
        admission.queue().enable(other);
        String otherTurn = admission.acceptTurn(other, "actor", "other-input", "{}").id();
        jdbc.update(
                "UPDATE ma.session_dispatch SET next_check_at=0 WHERE scope_key=?",
                scope.messageGroup());
        jdbc.update(
                "UPDATE ma.session_dispatch SET next_check_at=1 WHERE scope_key=?",
                other.messageGroup());
        var available = service.invoke("executions/next", poll).required("deliveries");
        assertEquals(1, available.size(), "A suspended Session must not consume the polling limit");
        assertEquals(otherTurn, available.get(0).required("turnId").asText());
        var otherClaim =
                request(
                        Map.of(
                                "claimId",
                                "other-owner",
                                "harnessType",
                                kernel,
                                "trigger",
                                available.get(0).required("trigger"),
                                "owner",
                                Map.of("instanceId", "other-brain", "incarnationId", "process")));
        otherClaim.set("scope", mapper.valueToTree(other));
        service.invoke("executions/claim", otherClaim);
        var physical =
                new ManagedToolResult(scope, task, task, "succeeded", "{\"output\":\"done\"}");
        journal.commitToolEvent(
                new ManagedToolEvent(
                        scope,
                        task,
                        task,
                        1,
                        "tool.result",
                        Map.of(
                                "result",
                                mapper.convertValue(physical, Map.class),
                                "last_chunk_seq",
                                0,
                                "output_offset",
                                0,
                                "log_status",
                                "not_supported")));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.turn WHERE resume_id IS NOT NULL", Integer.class));
        var deliveries = service.invoke("executions/next", poll).required("deliveries");
        assertEquals(1, deliveries.size(), "Result-ready resume must be visible before activation");
        var trigger = deliveries.get(0).required("trigger");
        assertEquals("resume", trigger.required("kind").asText());
        assertEquals("suspended", trigger.required("suspensionId").asText());
        var deferred =
                request(
                        Map.of(
                                "turnId",
                                turn,
                                "deferralId",
                                "resume-delay",
                                "trigger",
                                trigger,
                                "retryAfterMs",
                                60000,
                                "reason",
                                "temporarily_unavailable"));
        var deferredReceipt = service.invoke("deliveries/defer", deferred);
        schemas.validate("execution/DeferReceipt", deferredReceipt);
        assertEquals(deferredReceipt, service.invoke("deliveries/defer", deferred));
        assertTrue(
                service.invoke("executions/next", poll).required("deliveries").isEmpty(),
                "HTTP polling must honor resume deferral");
        jdbc.update(
                "UPDATE ma.session_dispatch SET next_check_at=0 WHERE scope_key=?",
                scope.messageGroup());
        var resumed =
                service.invoke(
                        "executions/claim",
                        request(
                                Map.of(
                                        "claimId",
                                        "new-owner",
                                        "turnId",
                                        turn,
                                        "harnessType",
                                        kernel,
                                        "trigger",
                                        trigger,
                                        "owner",
                                        Map.of(
                                                "instanceId",
                                                "new-brain",
                                                "incarnationId",
                                                "new-process"))));
        schemas.validate("execution/ClaimReceipt", resumed);
        assertTrue(
                service.invoke("executions/next", poll).required("deliveries").isEmpty(),
                "Current owner excludes another dispatch");
    }

    @Test
    void deferralIsFencedAndDoesNotReleaseAnActiveOwner() {
        var deferred =
                request(
                        Map.of(
                                "turnId",
                                turn,
                                "deferralId",
                                "retry",
                                "trigger",
                                Map.of("kind", "ready", "messageId", "ready", "queueVersion", "1"),
                                "retryAfterMs",
                                60000,
                                "reason",
                                "temporarily_unavailable"));
        assertEquals(
                "execution_busy",
                assertThrows(
                                ManagedStateException.class,
                                () -> service.invoke("deliveries/defer", deferred))
                        .code());
        deferred.set("fence", claim.required("fence"));
        schemas.validate("execution/DeferRequest", deferred);
        var receipt = service.invoke("deliveries/defer", deferred);
        assertEquals(
                1, jdbc.queryForObject("SELECT COUNT(*) FROM ma.harness_lease", Integer.class));
        assertEquals(receipt, service.invoke("deliveries/defer", deferred));
        jdbc.update("UPDATE ma.harness_lease SET execution_token='replacement'");
        deferred.put("deferralId", "late");
        assertEquals(
                "stale_execution",
                assertThrows(
                                ManagedStateException.class,
                                () -> service.invoke("deliveries/defer", deferred))
                        .code());
    }

    @ParameterizedTest
    @ValueSource(strings = {"pi", "pi-durable", "codex", "agentscope-java"})
    void recoveryRetainsCancellationBeforeNativeExecutionStarts(String harnessType) {
        setup(harnessType);
        admission.acceptInput(
                scope, turn, "actor", "cancel", ManagedInputInbox.Type.CANCEL, null, "{}");
        var response =
                service.invoke(
                        "recovery/read",
                        request(Map.of("turnId", turn, "fence", claim.required("fence"))));
        schemas.validate("recovery/RecoveryResponse", response);
        var pending = response.required("bundle").required("pendingInputs");
        assertEquals(1, pending.size());
        assertEquals("cancel", pending.get(0).required("type").asText());
    }

    @Test
    void pagedRecoveryRetainsLongTurnsAndRejectsMixedHeads() {
        var boundary = boundary("head");
        service.invoke("commits", boundary);
        String payload = boundary.toString();
        jdbc.batchUpdate(
                "INSERT INTO ma.harness_boundary(scope_key,turn_id,revision,payload)"
                        + " VALUES(?,?,?,?)",
                new BatchPreparedStatementSetter() {
                    public void setValues(PreparedStatement statement, int index)
                            throws SQLException {
                        statement.setString(1, scope.messageGroup());
                        statement.setString(2, turn);
                        statement.setLong(3, index + 2);
                        statement.setString(4, payload);
                    }

                    public int getBatchSize() {
                        return 4096;
                    }
                });
        jdbc.update("UPDATE ma.harness_head SET revision=4097");
        var request = request(Map.of("turnId", turn, "fence", claim.required("fence")));
        assertEquals(
                "recovery_limit_exceeded",
                assertThrows(
                                ManagedStateException.class,
                                () -> service.invoke("recovery/read", request))
                        .code());
        request.put("pageSize", 16);
        int pages = 0;
        JsonNode first = null;
        while (true) {
            schemas.validate("recovery/RecoveryRequest", request);
            var response = service.invoke("recovery/read", request);
            schemas.validate("recovery/RecoveryResponse", response);
            assertEquals("4097", response.path("bundle").path("brainRevision").asText());
            if (first == null) first = response;
            assertEquals(first.required("snapshotId"), response.required("snapshotId"));
            pages++;
            if (!response.has("nextCursor")) break;
            request.set("cursor", response.required("nextCursor"));
        }
        assertEquals(257, pages);
        request.set("cursor", first.required("nextCursor"));
        jdbc.update("UPDATE ma.harness_head SET revision=4098");
        assertEquals(
                "brain_revision_conflict",
                assertThrows(
                                ManagedStateException.class,
                                () -> service.invoke("recovery/read", request))
                        .code());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Timeout(60)
    void javaNativeExecutionAndNextTurnRestoreUseHttpWithoutBrainDatabase(boolean suspendTool)
            throws Exception {
        setup("agentscope-java");
        jdbc.update(
                "UPDATE ma.harness_lease SET expires_at=0 WHERE scope_key=?", scope.messageGroup());
        jdbc.update(
                "UPDATE ma.turn SET input_payload=? WHERE scope_key=? AND turn_id=?",
                "{\"input\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}]}",
                scope.messageGroup(),
                turn);
        var serverObjects = new FileRuntimeObjectStore("test", directory);
        var store = new SessionServiceStore(source, journal, mapper, serverObjects);
        var budgets = new ManagedBudgetStore(source, mapper, null);
        var coordination =
                new SessionServiceCoordination(source, mapper, admission, null, null, 600, 3600);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var threads = Executors.newCachedThreadPool();
        server.setExecutor(threads);
        var lostCommit = new AtomicBoolean();
        server.createContext(
                "/",
                exchange -> {
                    try {
                        var input = mapper.readTree(exchange.getRequestBody());
                        String operation = exchange.getRequestURI().getPath().substring(1);
                        Object result =
                                switch (operation) {
                                    case "prepared/coordination" ->
                                            coordination.invoke(input).block();
                                    case "prepared/query" ->
                                            store.query(
                                                    mapper.convertValue(
                                                            input,
                                                            SessionServiceStore.Query.class));
                                    case "prepared/objects" -> store.object(input);
                                    case "prepared/model" ->
                                            store.reserve(input, budgets, admission);
                                    case "prepared/receipt" ->
                                            store.receipt(
                                                    mapper.convertValue(
                                                            input.required("scope"),
                                                            ManagedScope.class),
                                                    input.required("commitId").asText());
                                    case "prepared/commits" ->
                                            store.commit(
                                                    mapper.convertValue(
                                                            input,
                                                            SessionServiceStore.PreparedRequest
                                                                    .class));
                                    default -> service.invoke(operation, input);
                                };
                        if ("prepared/commits".equals(operation)
                                && lostCommit.compareAndSet(false, true)) {
                            exchange.close(); // Durable commit succeeded; client must recover the
                            // original receipt.
                            return;
                        }
                        byte[] bytes = mapper.writeValueAsBytes(result);
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    } catch (Exception failure) {
                        byte[] bytes =
                                mapper.writeValueAsBytes(
                                        Map.of(
                                                "code",
                                                failure instanceof ManagedStateException state
                                                        ? state.code()
                                                        : "test_server_failure"));
                        exchange.sendResponseHeaders(409, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        var controls = Executors.newScheduledThreadPool(2);
        try {
            var client =
                    new SessionServiceClient(
                            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                            "internal-test",
                            mapper,
                            Duration.ofSeconds(3));
            var access = new SessionExecutionAccess(client, mapper);
            var handles = new ManagedHttpExecutions(access);
            var remoteAdmission = new ManagedAdmissionStore(client, mapper, null);
            remoteAdmission.executionRequests(handles::request);
            var remoteBudget = new ManagedBudgetStore(client, mapper);
            var remoteJournal =
                    new ManagedJournalStore(
                            client,
                            mapper,
                            remoteBudget,
                            new ManagedCheckpointObjects(
                                    mapper, new RemoteRuntimeObjectStore(client, "test", mapper)));
            var writer = new ManagedRuntimeWriter(remoteJournal, client, mapper);
            var codec =
                    new ManagedBatchCodec(
                            mapper, new RemoteRuntimeObjectStore(client, "test", mapper));
            var delivered = new AtomicInteger();
            var cancelMode = new AtomicBoolean();
            var modelCancelled = new AtomicBoolean();
            var suspendedOutcome = new AtomicReference<ManagedToolResult>();
            var results =
                    new ManagedToolResultDelivery() {
                        public void publish(
                                ManagedScope target,
                                String task,
                                ManagedMessageBus.Message message) {
                            throw new UnsupportedOperationException();
                        }

                        public Optional<ManagedMessageBus.Message> await(
                                ManagedScope target, String task, Duration block) {
                            var persisted = remoteJournal.tool(target, task).orElseThrow();
                            if (persisted.result() == null) {
                                if (suspendTool
                                        && persisted.arguments().contains("echo remote")
                                        && suspendedOutcome.get() != null) return Optional.empty();
                                // Fixture Hands publishes its durable outcome on the Service side.
                                boolean modelTool = persisted.arguments().contains("echo remote");
                                if (modelTool) delivered.incrementAndGet();
                                journal.claimTool(target, task, "fixture-hands");
                                var outcome =
                                        new ManagedToolResult(
                                                target,
                                                task,
                                                persisted.executionId(),
                                                "succeeded",
                                                "{\"output\":\""
                                                        + (modelTool
                                                                ? "remote tool result"
                                                                : "__NOT_FOUND__")
                                                        + "\",\"exitCode\":0,\"truncated\":false}");
                                if (suspendTool && modelTool) {
                                    suspendedOutcome.set(outcome);
                                    return Optional.empty();
                                }
                                journal.stageToolResult(outcome);
                                journal.commitToolResult(outcome);
                            }
                            var value = remoteJournal.tool(target, task).orElseThrow();
                            return Optional.of(
                                    codec.encode(
                                            new ManagedToolResult(
                                                    target,
                                                    task,
                                                    value.executionId(),
                                                    value.status(),
                                                    value.result())));
                        }
                    };
            var requests = new ManagedToolRequests(mapper, Duration.ofMillis(100), results, codec);
            var restored = new AtomicBoolean();
            var calls = new AtomicInteger();
            Model model =
                    new Model() {
                        public String getModelName() {
                            return "http-fixture";
                        }

                        public Flux<ChatResponse> stream(
                                List<Msg> messages,
                                List<ToolSchema> tools,
                                GenerateOptions options) {
                            if (cancelMode.get())
                                return Flux.<ChatResponse>never()
                                        .doOnSubscribe(
                                                ignored ->
                                                        controls.schedule(
                                                                () ->
                                                                        admission.acceptInput(
                                                                                scope,
                                                                                turn,
                                                                                "actor",
                                                                                "in-flight-cancel",
                                                                                ManagedInputInbox
                                                                                        .Type
                                                                                        .CANCEL,
                                                                                null,
                                                                                "{}"),
                                                                100,
                                                                TimeUnit.MILLISECONDS))
                                        .doOnCancel(() -> modelCancelled.set(true));
                            int invocation = calls.incrementAndGet();
                            if (invocation == 1)
                                return Flux.just(
                                        ChatResponse.builder()
                                                .content(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .id("call-http")
                                                                        .name("execute")
                                                                        .input(
                                                                                Map.of(
                                                                                        "command",
                                                                                        "echo remote",
                                                                                        "timeout",
                                                                                        10))
                                                                        .content(
                                                                                "{\"command\":\"echo"
                                                                                    + " remote\",\"timeout\":10}")
                                                                        .build()))
                                                .build());
                            if (invocation == 2)
                                assertTrue(
                                        messages.stream()
                                                .anyMatch(
                                                        message ->
                                                                !message.getContentBlocks(
                                                                                ToolResultBlock
                                                                                        .class)
                                                                        .isEmpty()));
                            if (messages.stream()
                                    .anyMatch(
                                            message ->
                                                    message.getTextContent()
                                                            .contains("HTTP answer")))
                                restored.set(true);
                            return Flux.just(
                                    ChatResponse.builder()
                                            .id("answer-" + calls.get())
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("HTTP answer")
                                                                    .build()))
                                            .build());
                        }
                    };
            int finalRound = suspendTool ? 2 : 1;
            for (int round = 0; round <= finalRound + 2; round++) {
                cancelMode.set(round == finalRound + 1);
                if (round >= finalRound)
                    turn =
                            admission
                                    .acceptTurn(
                                            scope,
                                            "actor",
                                            "follow-up-" + round,
                                            "{\"input\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"continue\"}]}]}")
                                    .id();
                var next =
                        service.invoke(
                                        "executions/next",
                                        mapper.valueToTree(
                                                Map.of(
                                                        "protocolVersion",
                                                        "0.1.0",
                                                        "harnessType",
                                                        "agentscope-java",
                                                        "limit",
                                                        1)))
                                .required("deliveries")
                                .get(0);
                var claimed =
                        access.claim(
                                mapper.convertValue(next, ExecutionAccess.Candidate.class),
                                new ExecutionAccess.Owner("brain", "round-" + round));
                try (var guard = handles.attach(claimed, () -> {});
                        var agent =
                                HarnessAgent.builder()
                                        .name("http-native")
                                        .workspace(directory.resolve("brain-" + round))
                                        .model(model)
                                        .sessionLogStore(new ManagedSessionLogStore(remoteJournal))
                                        .messageBus(new ManagedSessionMessageBus())
                                        .asyncToolRegistry(new ManagedAsyncToolRegistry())
                                        .taskRepository(new ManagedTaskRepository())
                                        .abstractFilesystem(new ManagedRemoteFilesystem(requests))
                                        .middleware(new ManagedRuntimeInputMiddleware(mapper))
                                        .permissionContext(
                                                PermissionContextState.builder()
                                                        .mode(PermissionMode.BYPASS)
                                                        .build())
                                        .disableMemoryHooks()
                                        .disableCompaction()
                                        .disableDefaultWorkspaceSkills()
                                        .maxIters(2)
                                        .build()) {
                    var execution =
                            new ManagedTurnExecution(
                                    scope,
                                    claimed.turnId(),
                                    Long.parseLong(claimed.fence().epoch()),
                                    guard,
                                    remoteAdmission,
                                    remoteJournal,
                                    writer);
                    var context =
                            RuntimeContext.builder()
                                    .userId("user")
                                    .sessionId(scope.sessionId())
                                    .put(ManagedScope.class, scope)
                                    .put(ManagedTurnExecution.class, execution)
                                    .put(SessionRecorder.TURN_ID_KEY, claimed.turnId())
                                    .put(
                                            SessionModelPolicy.CONTEXT_KEY,
                                            new ManagedModelPolicy(remoteBudget, execution))
                                    .put(ToolCallParam.CALL_CONTEXT_KEY, true)
                                    .put(
                                            ManagedToolRequests.Operations.class,
                                            new ManagedToolRequests.Operations())
                                    .build();
                    new ManagedHarnessTurns(
                                    mapper, new ManagedInputDelivery(remoteAdmission), controls)
                            .run(execution, agent, context);
                    assertTrue(execution.handedOff());
                    if (suspendTool && round == 0) {
                        assertEquals(
                                "waiting_tool", admission.turn(scope, claimed.turnId()).status());
                        journal.stageToolResult(suspendedOutcome.get());
                        journal.commitToolResult(suspendedOutcome.get());
                    } else if (cancelMode.get()) {
                        assertTrue(
                                modelCancelled.get(),
                                "HTTP cancellation must dispose the active model stream");
                        assertEquals("cancelled", admission.turn(scope, claimed.turnId()).status());
                    } else {
                        assertEquals("succeeded", admission.turn(scope, claimed.turnId()).status());
                    }
                    assertTrue(remoteJournal.head(scope).sequence() > 0);
                }
            }
            assertTrue(lostCommit.get());
            assertTrue(calls.get() >= 3);
            assertTrue(restored.get(), "Next process must restore the earlier assistant context");
            assertEquals(1, delivered.get(), "Durable tool outcome must not execute twice");
            assertTrue(
                    jdbc.queryForObject("SELECT COUNT(*) FROM ma.harness_model", Integer.class)
                            > 0);
            turn = admission.acceptTurn(scope, "actor", "cancel-turn", "{}").id();
            var candidate =
                    service.invoke(
                                    "executions/next",
                                    mapper.valueToTree(
                                            Map.of(
                                                    "protocolVersion",
                                                    "0.1.0",
                                                    "harnessType",
                                                    harness,
                                                    "limit",
                                                    1)))
                            .required("deliveries")
                            .get(0);
            var cancellable =
                    access.claim(
                            mapper.convertValue(candidate, ExecutionAccess.Candidate.class),
                            new ExecutionAccess.Owner("brain", "cancel-boot"));
            try (var guard = handles.attach(cancellable, () -> {})) {
                var remoteInputs = new ManagedInputDelivery(remoteAdmission);
                assertTrue(!remoteInputs.cancelled(scope, turn));
                admission.acceptInput(
                        scope, turn, "actor", "cancel", ManagedInputInbox.Type.CANCEL, null, "{}");
                assertTrue(remoteInputs.cancelled(scope, turn));
                assertEquals(1, access.inputs(cancellable).required("pendingInputs").size());
            }
        } finally {
            controls.shutdownNow();
            server.stop(0);
            threads.shutdownNow();
        }
    }

    @Test
    void releaseCannotRevokeASuccessorAndLegacyOwnerBlocksClaim() {
        setup("agentscope-java");
        var old = request(Map.of("turnId", turn, "fence", claim.required("fence")));
        schemas.validate("runtime/ReleaseReceipt", service.invoke("executions/release", old));
        service.invoke("executions/release", old);
        var nextClaim =
                request(
                        Map.of(
                                "turnId",
                                turn,
                                "claimId",
                                "successor",
                                "harnessType",
                                harness,
                                "owner",
                                Map.of("instanceId", "next", "incarnationId", "boot2"),
                                "trigger",
                                Map.of(
                                        "kind",
                                        "ready",
                                        "messageId",
                                        "retry",
                                        "queueVersion",
                                        "1")));
        long future = System.currentTimeMillis() + 60000;
        jdbc.update(
                "INSERT INTO ma_worker.lease(incarnation,role,instance_id,expires_at)"
                        + " VALUES(?,?,?,?)",
                "legacy",
                "agent-harness-runtime",
                "old",
                future);
        jdbc.update(
                "UPDATE ma.turn SET owner_incarnation='legacy',execution_deadline=? WHERE"
                        + " turn_id=?",
                future,
                turn);
        assertEquals(
                "execution_busy",
                assertThrows(
                                ManagedStateException.class,
                                () -> service.invoke("executions/claim", nextClaim))
                        .code());
        jdbc.update("UPDATE ma_worker.lease SET expires_at=0");
        var successor = service.invoke("executions/claim", nextClaim);
        assertTrue(
                successor.required("fence").required("epoch").asLong()
                        > claim.required("fence").required("epoch").asLong());
        assertEquals(
                "ownership_lost",
                assertThrows(
                                ManagedStateException.class,
                                () -> service.invoke("executions/release", old))
                        .code());
        var oldOwner =
                new ManagedJournalStore.ExecutionOwner(
                        scope,
                        turn,
                        claim.required("fence").required("epoch").asLong(),
                        claim.required("fence").required("executionToken").asText());
        assertThrows(
                ManagedStateException.class,
                () ->
                        new SessionServiceOwnership(source)
                                .whileCurrent(oldOwner, () -> "late write"));
    }

    @Test
    @Timeout(15)
    void taskCallbackRetriesCapacityRejectionAndOnlyClaimGrantsOwnership() throws Exception {
        setup("agentscope-java");
        service.invoke(
                "executions/release",
                request(Map.of("turnId", turn, "fence", claim.required("fence"))));
        var accepted = new CountDownLatch(1);
        var notifications = new AtomicInteger();
        var authenticated = new AtomicBoolean(true);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/notify",
                exchange -> {
                    authenticated.compareAndSet(
                            true,
                            "callback-token"
                                    .equals(
                                            exchange.getRequestHeaders()
                                                    .getFirst("X-Managed-Agents-Internal-Token")));
                    var candidate = (ObjectNode) mapper.readTree(exchange.getRequestBody());
                    if (notifications.incrementAndGet() == 1) {
                        exchange.sendResponseHeaders(429, -1);
                        exchange.close();
                        return;
                    }
                    candidate
                            .put("protocolVersion", "0.1.0")
                            .put("harnessType", harness)
                            .put("claimId", "callback-claim");
                    candidate.set(
                            "owner",
                            mapper.valueToTree(
                                    Map.of(
                                            "instanceId",
                                            "callback-brain",
                                            "incarnationId",
                                            "boot")));
                    service.invoke("executions/claim", candidate);
                    exchange.sendResponseHeaders(202, -1);
                    exchange.close();
                    accepted.countDown();
                });
        server.start();
        try (var dispatcher =
                new BrainTaskDispatcher(
                        source,
                        service,
                        mapper,
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/notify",
                        "callback-token",
                        harness)) {
            dispatcher.start();
            assertTrue(accepted.await(10, TimeUnit.SECONDS));
            assertTrue(authenticated.get());
            assertTrue(notifications.get() >= 2);
            assertEquals(
                    0,
                    service.invoke(
                                    "executions/next",
                                    mapper.valueToTree(
                                            Map.of(
                                                    "protocolVersion",
                                                    "0.1.0",
                                                    "harnessType",
                                                    harness,
                                                    "limit",
                                                    1)))
                            .required("deliveries")
                            .size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @Timeout(15)
    void failedCallbackDoesNotStarveTheNextSession() throws Exception {
        setup("agentscope-java");
        service.invoke(
                "executions/release",
                request(Map.of("turnId", turn, "fence", claim.required("fence"))));
        var nextScope = new ManagedScope("tenant", "test", "second");
        admission.createSession(nextScope, "{}");
        admission.queue().enable(nextScope);
        admission.acceptTurn(nextScope, "actor", "next", "{}");
        jdbc.update(
                "UPDATE ma.session_dispatch SET next_check_at=0 WHERE scope_key=?",
                scope.messageGroup());
        jdbc.update(
                "UPDATE ma.session_dispatch SET next_check_at=1 WHERE scope_key=?",
                nextScope.messageGroup());
        var accepted = new CountDownLatch(1);
        var failures = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/notify",
                exchange -> {
                    try {
                        var candidate = (ObjectNode) mapper.readTree(exchange.getRequestBody());
                        if (scope.sessionId()
                                .equals(
                                        candidate
                                                .required("scope")
                                                .required("sessionId")
                                                .asText())) {
                            failures.incrementAndGet();
                            return; // Broken connection before a response; this candidate stays
                            // runnable.
                        }
                        candidate
                                .put("protocolVersion", "0.1.0")
                                .put("harnessType", harness)
                                .put("claimId", "second-claim");
                        candidate.set(
                                "owner",
                                mapper.valueToTree(
                                        Map.of("instanceId", "brain2", "incarnationId", "boot")));
                        service.invoke("executions/claim", candidate);
                        exchange.sendResponseHeaders(202, -1);
                        accepted.countDown();
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        try (var dispatcher =
                new BrainTaskDispatcher(
                        source,
                        service,
                        mapper,
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/notify",
                        "callback-token",
                        harness)) {
            dispatcher.start();
            assertTrue(
                    accepted.await(8, TimeUnit.SECONDS),
                    "Failed head must not block another Session");
            assertTrue(failures.get() > 0);
            assertTrue(
                    jdbc.queryForObject(
                                    "SELECT next_check_at FROM ma.session_dispatch WHERE"
                                            + " scope_key=?",
                                    Long.class,
                                    scope.messageGroup())
                            > 0);
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM ma.harness_lease WHERE scope_key=? AND"
                                    + " expires_at>0",
                            Integer.class,
                            nextScope.messageGroup()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void javaClaimsUseTheSameLeaseForPreparedOperations() {
        setup("agentscope-java");
        var owner =
                new ManagedJournalStore.ExecutionOwner(
                        scope,
                        turn,
                        claim.required("fence").required("epoch").asLong(),
                        claim.required("fence").required("executionToken").asText());
        var guard = new SessionServiceOwnership(source);
        assertEquals("current", guard.whileCurrent(owner, () -> "current"));
        var next =
                mapper.createObjectNode()
                        .put("protocolVersion", "0.1.0")
                        .put("harnessType", "agentscope-java")
                        .put("limit", 1);
        assertTrue(service.invoke("executions/next", next).required("deliveries").isEmpty());
        jdbc.update(
                "UPDATE ma.harness_lease SET expires_at=0 WHERE scope_key=?", scope.messageGroup());
        assertEquals(
                "stale_execution",
                assertThrows(
                                ManagedStateException.class,
                                () -> guard.whileCurrent(owner, () -> "must not write"))
                        .code());
        assertEquals(1, service.invoke("executions/next", next).required("deliveries").size());
    }

    @Test
    void remotePreparedGuardKeepsTheOriginalProcessLeaseAndFixedDeadline() {
        var owner =
                new ManagedJournalStore.ExecutionOwner(
                        scope,
                        turn,
                        claim.path("fence").path("epoch").asLong(),
                        claim.path("fence").path("executionToken").asText());
        long future = System.currentTimeMillis() + 60000;
        jdbc.update(
                "INSERT INTO ma_worker.lease(incarnation,role,instance_id,expires_at)"
                        + " VALUES(?,?,?,?)",
                "old-process",
                "agent-harness-runtime",
                "brain",
                future);
        jdbc.update(
                "UPDATE ma.turn SET owner_incarnation=?,execution_deadline=? WHERE turn_id=?",
                "old-process",
                future,
                turn);
        var guard = new SessionServiceOwnership(source);
        assertEquals("saved", guard.whileCurrent(owner, () -> "saved"));
        jdbc.update("UPDATE ma_worker.lease SET expires_at=0");
        assertThrows(
                ManagedStateException.class, () -> guard.whileCurrent(owner, () -> "must not run"));
        jdbc.update("UPDATE ma_worker.lease SET expires_at=?", future);
        jdbc.update("UPDATE ma.turn SET execution_deadline=0");
        assertThrows(
                ManagedStateException.class, () -> guard.whileCurrent(owner, () -> "must not run"));
    }

    @Test
    void preparedModelReservationUsesTheExecutionOwnerTokenAndIsIdempotent() {
        var store =
                new SessionServiceStore(
                        source, journal, mapper, new FileRuntimeObjectStore("test", directory));
        var budgets = new ManagedBudgetStore(source, mapper, null);
        var request =
                mapper.valueToTree(
                        Map.of(
                                "scope",
                                scope,
                                "turnId",
                                turn,
                                "epoch",
                                claim.path("fence").path("epoch").asLong(),
                                "token",
                                claim.path("fence").path("executionToken").asText(),
                                "modelCallId",
                                "reserved-model",
                                "model",
                                "fixture-model"));
        store.reserve(request, budgets, admission);
        store.reserve(request, budgets, admission);
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.model_call WHERE call_id='reserved-model'",
                        Integer.class));
    }

    @ParameterizedTest
    @CsvSource({
        "pi,true",
        "pi,false",
        "pi-durable,true",
        "pi-durable,false",
        "codex,true",
        "codex,false"
    })
    void modelBoundariesProjectPairedSpansAndPreserveUnknownUsage(String kernel, boolean known) {
        setup(kernel);
        service =
                new HarnessProtocolStore(
                        source,
                        admission,
                        journal,
                        new FileRuntimeObjectStore("test", directory),
                        mapper,
                        new ManagedBudgetStore(source, mapper, null));
        service.harnessType(kernel);
        service.invoke(
                "models/reserve",
                request(
                        Map.of(
                                "turnId",
                                turn,
                                "fence",
                                claim.required("fence"),
                                "modelCallId",
                                "model-a",
                                "model",
                                "fixture-model")));
        var dispatch = boundary("dispatch");
        ((ObjectNode) dispatch.required("changes"))
                .set(
                        "modelUpdates",
                        mapper.valueToTree(
                                List.of(
                                        Map.of(
                                                "modelCallId",
                                                "model-a",
                                                "phase",
                                                "dispatch",
                                                "model",
                                                "fixture-model",
                                                "purpose",
                                                "reasoning",
                                                "requestRef",
                                                object("{}"),
                                                "requestHash",
                                                CanonicalJson.hash(mapper.createObjectNode()),
                                                "position",
                                                Map.of("nativeCursor", "1")))));
        var firstReceipt = service.invoke("commits", dispatch);
        assertEquals(firstReceipt, service.invoke("commits", dispatch));
        var completion = boundary("completion");
        completion.put("expectedBrainRevision", "1");
        ((ObjectNode) completion.required("nativeState")).put("nativeCursor", "2");
        ObjectNode update =
                mapper.valueToTree(
                        Map.of(
                                "modelCallId",
                                "model-a",
                                "phase",
                                "complete",
                                "status",
                                "completed",
                                "resultRef",
                                object("{\"private\":\"not public\"}"),
                                "position",
                                Map.of("nativeCursor", "2")));
        if (known)
            update.set(
                    "usage",
                    mapper.valueToTree(
                            Map.of(
                                    "inputTokens",
                                    "12",
                                    "outputTokens",
                                    "7",
                                    "cacheReadInputTokens",
                                    "4",
                                    "cacheWriteInputTokens",
                                    "2")));
        else update.put("usageUnknown", true);
        ((ObjectNode) completion.required("changes"))
                .set("modelUpdates", mapper.valueToTree(List.of(update)));
        var secondReceipt = service.invoke("commits", completion);
        assertEquals(secondReceipt, service.invoke("commits", completion));
        while (journal.projections().project(scope, 128) > 0) {}
        var spans =
                admission.protocol().changes(scope, 0, 100).stream()
                        .map(change -> change.event())
                        .filter(
                                event ->
                                        String.valueOf(event.get("type"))
                                                .startsWith("span.model_request_"))
                        .toList();
        assertEquals(2, spans.size());
        assertEquals("span.model_request_start", spans.get(0).get("type"));
        assertEquals("span.model_request_end", spans.get(1).get("type"));
        assertEquals(spans.get(0).get("id"), spans.get(1).get("model_request_start_id"));
        assertEquals(known, spans.get(1).containsKey("model_usage"));
        if (known)
            assertEquals(
                    mapper.valueToTree(
                            Map.of(
                                    "input_tokens",
                                    12,
                                    "output_tokens",
                                    7,
                                    "cache_read_input_tokens",
                                    4,
                                    "cache_creation_input_tokens",
                                    2)),
                    mapper.valueToTree(spans.get(1).get("model_usage")));
        assertTrue(!spans.toString().contains("private"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"pi", "pi-durable", "codex"})
    void previewsRequireAnActiveReasoningCallAndCurrentOwnerWithoutDurableWrites(String kernel)
            throws Exception {
        setup(kernel);
        service =
                new HarnessProtocolStore(
                        source,
                        admission,
                        journal,
                        new FileRuntimeObjectStore("test", directory),
                        mapper,
                        new ManagedBudgetStore(source, mapper, null));
        service.harnessType(kernel);
        var interest =
                request(
                        Map.of(
                                "turnId",
                                turn,
                                "fence",
                                claim.required("fence"),
                                "modelCallId",
                                "preview-model"));
        try (var preview = new ManagedPreviewFixture(scope, mapper)) {
            service.previews(preview.previews);
            assertEquals(
                    "preview_model_closed",
                    assertThrows(
                                    ManagedStateException.class,
                                    () -> service.invoke("previews/interest", interest))
                            .code());
            var reserve = interest.deepCopy().put("model", "fixture");
            service.invoke("models/reserve", reserve);
            assertEquals(
                    "preview_model_closed",
                    assertThrows(
                                    ManagedStateException.class,
                                    () -> service.invoke("previews/interest", interest))
                            .code());
            var dispatch = boundary("preview-dispatch");
            ((ObjectNode) dispatch.required("changes"))
                    .set(
                            "modelUpdates",
                            mapper.valueToTree(
                                    List.of(
                                            Map.of(
                                                    "modelCallId",
                                                    "preview-model",
                                                    "phase",
                                                    "dispatch",
                                                    "model",
                                                    "fixture",
                                                    "purpose",
                                                    "reasoning",
                                                    "requestRef",
                                                    object("{}"),
                                                    "requestHash",
                                                    CanonicalJson.hash(mapper.createObjectNode()),
                                                    "position",
                                                    Map.of("nativeCursor", "1")))));
            service.invoke("commits", dispatch);
            var interested = service.invoke("previews/interest", interest);
            schemas.validate("preview/InterestResponse", interested);
            assertTrue(interested.path("interested").asBoolean());
            var publish =
                    interest.deepCopy().put("messageId", "public-message").put("sequence", "0");
            publish.set("chunks", mapper.valueToTree(List.of("你好🙂")));
            int facts = journal.publicEvents(scope, 0, 1000).size();
            var accepted = service.invoke("previews/publish", publish);
            schemas.validate("preview/PublishResponse", accepted);
            assertTrue(accepted.path("accepted").asBoolean());
            long deadline = System.nanoTime() + 3_000_000_000L;
            while (preview.frames.size() < 2 && System.nanoTime() < deadline) Thread.sleep(5);
            assertEquals(2, preview.frames.size());
            assertEquals(facts, journal.publicEvents(scope, 0, 1000).size());
            assertEquals(
                    1,
                    jdbc.queryForObject("SELECT COUNT(*) FROM ma.harness_boundary", Integer.class));

            // A maintenance call is never eligible, even if its execution is current.
            var maintenance =
                    mapper.readTree(
                            jdbc.queryForObject(
                                    "SELECT request FROM ma.harness_model", String.class));
            ((ObjectNode) maintenance).put("purpose", "compaction");
            jdbc.update("UPDATE ma.harness_model SET request=?", maintenance.toString());
            assertEquals(
                    "preview_model_closed",
                    assertThrows(
                                    ManagedStateException.class,
                                    () -> service.invoke("previews/publish", publish))
                            .code());
            ((ObjectNode) maintenance).put("purpose", "reasoning");
            jdbc.update("UPDATE ma.harness_model SET request=?", maintenance.toString());

            var completion = boundary("preview-completion").put("expectedBrainRevision", "1");
            ((ObjectNode) completion.required("nativeState")).put("nativeCursor", "2");
            ((ObjectNode) completion.required("changes"))
                    .set(
                            "modelUpdates",
                            mapper.valueToTree(
                                    List.of(
                                            Map.of(
                                                    "modelCallId",
                                                    "preview-model",
                                                    "phase",
                                                    "complete",
                                                    "status",
                                                    "completed",
                                                    "resultRef",
                                                    object("{}"),
                                                    "usageUnknown",
                                                    true,
                                                    "position",
                                                    Map.of("nativeCursor", "2")))));
            service.invoke("commits", completion);
            assertEquals(
                    "preview_model_closed",
                    assertThrows(
                                    ManagedStateException.class,
                                    () -> service.invoke("previews/publish", publish))
                            .code());
            jdbc.update("UPDATE ma.harness_lease SET execution_token='new-owner'");
            assertEquals(
                    "stale_execution",
                    assertThrows(
                                    ManagedStateException.class,
                                    () -> service.invoke("previews/interest", interest))
                            .code());
        }
    }

    @Test
    void finalOutputSelectsTheStableMessageWithoutDuplicatingIntermediateMessages() {
        var messages = boundary("messages");
        ((ObjectNode) messages.required("changes"))
                .set(
                        "inputApplications",
                        mapper.valueToTree(
                                jdbc
                                        .queryForList(
                                                "SELECT input_id FROM ma.input WHERE scope_key=?"
                                                        + " AND turn_id=? AND status='accepted'",
                                                String.class,
                                                scope.messageGroup(),
                                                turn)
                                        .stream()
                                        .map(
                                                id ->
                                                        Map.of(
                                                                "inputId",
                                                                id,
                                                                "status",
                                                                "applied",
                                                                "position",
                                                                Map.of("nativeCursor", "1")))
                                        .toList()));
        var facts = mapper.createArrayNode();
        for (String id : List.of("intermediate", "final"))
            facts.add(
                    mapper.valueToTree(
                            Map.of(
                                    "factId",
                                    id,
                                    "type",
                                    "item.completed",
                                    "occurredAt",
                                    "2026-10-29T00:00:00Z",
                                    "content",
                                    Map.of(
                                            "inline",
                                            Map.of(
                                                    "schema_version",
                                                    1,
                                                    "turn_id",
                                                    turn,
                                                    "item_id",
                                                    id,
                                                    "item",
                                                    Map.of(
                                                            "message_id",
                                                            id,
                                                            "role",
                                                            "assistant",
                                                            "content",
                                                            List.of(
                                                                    Map.of(
                                                                            "type", "text", "text",
                                                                            id))))))));
        messages.set("publicFacts", facts);
        service.invoke("commits", messages);
        service.invoke(
                "inputs/close", request(Map.of("turnId", turn, "fence", claim.required("fence"))));
        var terminal = boundary("finish");
        terminal.put("expectedBrainRevision", "1");
        ((ObjectNode) terminal.required("nativeState")).put("nativeCursor", "2");
        ((ObjectNode) terminal.required("changes"))
                .set(
                        "terminal",
                        mapper.valueToTree(
                                Map.of(
                                        "status",
                                        "succeeded",
                                        "messageId",
                                        "final",
                                        "output",
                                        Map.of(
                                                "inline",
                                                List.of(
                                                        Map.of(
                                                                "type", "text", "text",
                                                                "final"))))));
        var receipt = service.invoke("commits", terminal);
        assertEquals(receipt, service.invoke("commits", terminal));
        while (journal.projections().project(scope, 128) > 0) {}
        var publicMessages =
                admission.protocol().changes(scope, 0, 100).stream()
                        .map(change -> change.event())
                        .filter(event -> "agent.message".equals(event.get("type")))
                        .toList();
        assertEquals(2, publicMessages.size());
        var output = admission.protocol().output(scope, turn, null, 10);
        assertEquals(List.of(publicMessages.get(1).get("id")), output.get("output_event_ids"));
        assertEquals(List.of(publicMessages.get(1)), output.get("data"));
    }

    @Test
    void serviceWorkspaceUsesSharedTransactionTables() {
        var workspace = new SessionServiceWorkspace(source, journal, mapper);
        workspace.execute(
                mapper.valueToTree(
                        Map.of(
                                "operation",
                                List.of(
                                        "put",
                                        List.of("session"),
                                        "key",
                                        Map.of("value", "persisted")))));
        var item =
                workspace.execute(
                        mapper.valueToTree(
                                Map.of("operation", List.of("get", List.of("session"), "key"))));
        assertEquals("persisted", item.path("value").path("value").asText());
    }
}
