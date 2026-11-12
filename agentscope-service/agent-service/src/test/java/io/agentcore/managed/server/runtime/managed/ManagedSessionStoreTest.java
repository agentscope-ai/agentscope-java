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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.managed.projection.PostgresSessionProjectionStore;
import io.agentcore.managed.server.web.managed.service.SessionEventNotifier;
import io.agentscope.core.agent.AgentFinalization;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionProjection;
import io.agentscope.core.session.SessionRecorder;
import io.agentscope.core.state.AgentState;
import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import io.agentscope.extensions.jdbc.dialect.vendor.H2Dialect;
import io.agentscope.extensions.jdbc.dialect.vendor.PostgresDialect;
import io.agentscope.extensions.jdbc.store.JdbcStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Timeout(20)
class ManagedSessionStoreTest {
    @TempDir Path directory;
    final ObjectMapper mapper = new ObjectMapper();
    final ManagedScope scope =
            new ManagedScope("tenant", "namespace", UUID.randomUUID().toString());
    DriverManagerDataSource source;
    JdbcTemplate jdbc;
    ManagedAdmissionStore admission;
    ManagedJournalStore journal;
    ManagedSessionStore store;
    ManagedCheckpointObjects checkpoints;
    String turn;
    final Map<String, Map<String, Object>> requests = new LinkedHashMap<>();
    final String state = AgentState.builder().build().toJson();

    protected DriverManagerDataSource database() throws Exception {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:direct-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
    }

    @BeforeEach
    void setup() throws Exception {
        source = database();
        ManagedAdmissionStore.initialize(source);
        jdbc = new JdbcTemplate(source);
        admission = ProjectionTestSupport.admission(source, mapper);
        admission.queryViews(ProjectionTestSupport.views(source, mapper));
        admission.createSession(scope, "{}");
        turn = admission.acceptTurn(scope, "actor", "turn", "{}").id();
        admission.reservePublication(scope, turn);
        admission.confirmPublication(scope, turn, "broker");
        admission.activate(scope, turn, 1, "token");
        checkpoints =
                new ManagedCheckpointObjects(
                        mapper, new FileRuntimeObjectStore("fixture", directory));
        journal = ProjectionTestSupport.journal(source, mapper, checkpoints);
        store = new ManagedSessionStore(journal, new SessionCommitTransactions(journal));
    }

    @Test
    void platformTransactionConsumesExplicitChangesWithoutReadingNativePayloads() {
        var plan = foreignPlan();
        var models = new ManagedModelResults(jdbc, mapper, checkpoints.backend());
        var receipt = new SessionCommitTransactions(journal).commitPlan(plan, models);
        assertEquals("committed", receipt.outcome());
        assertEquals(1, journal.head(scope).sequence());
        assertEquals("open", journal.action(scope, turn, "approval").orElseThrow().status());
        assertEquals(
                "dispatched",
                jdbc.queryForObject(
                        "SELECT status FROM ma.native_tool_call WHERE scope_key=? AND"
                                + " tool_call_id='foreign-call'",
                        String.class,
                        scope.messageGroup()));
        assertEquals(
                "running",
                jdbc.queryForObject(
                        "SELECT status FROM ma.turn WHERE scope_key=? AND turn_id=?",
                        String.class,
                        scope.messageGroup(),
                        turn));
        assertEquals(receipt, new SessionCommitTransactions(journal).commitPlan(plan, models));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.action WHERE scope_key=?",
                        Integer.class,
                        scope.messageGroup()));
    }

    @Test
    void preparedPlatformChangesStillRejectAFencedOwnerWithoutPartialMetadata() {
        var plan = foreignPlan();
        assertEquals(2, admission.activate(scope, turn, 1, "replacement"));
        assertEquals(
                "stale_execution",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        new SessionCommitTransactions(journal)
                                                .commitPlan(
                                                        plan,
                                                        new ManagedModelResults(
                                                                jdbc,
                                                                mapper,
                                                                checkpoints.backend())))
                        .code());
        assertEquals(0, journal.head(scope).sequence());
        assertNull(journal.receipt(scope, plan.boundary().commitId()));
        assertTrue(journal.action(scope, turn, "approval").isEmpty());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.native_tool_call WHERE scope_key=?",
                        Integer.class,
                        scope.messageGroup()));
    }

    private ManagedCommitPlan foreignPlan() {
        // Deliberately not JSON or an AgentScope event. The adapter has already interpreted it.
        var fact =
                new SessionCommit.RecoveryFact(
                        1,
                        "foreign-record",
                        1,
                        1,
                        "foreign/private-record",
                        "foreign-run",
                        turn,
                        true,
                        "opaque native bytes");
        var boundary =
                new SessionCommit(
                        1,
                        scope,
                        turn,
                        1,
                        "token",
                        "writer",
                        "foreign-commit",
                        0,
                        List.of(fact),
                        List.of(),
                        List.of(),
                        null,
                        null);
        byte[] bytes = "opaque native bytes".getBytes(StandardCharsets.UTF_8);
        var segment =
                checkpoints
                        .backend()
                        .put(
                                scope.messageGroup(),
                                "recovery",
                                RuntimeObjectStore.sha256(bytes),
                                bytes);
        var change =
                new ManagedCommitPlan.Change(
                        1,
                        List.of(new ManagedCommitPlan.ToolCall("foreign-call", true)),
                        null,
                        null,
                        null,
                        null,
                        null,
                        new ManagedCommitPlan.Interaction("approval", "confirmation", "{}", true),
                        List.of(
                                new ManagedCommitPlan.PublicFact(
                                        "foreign-running",
                                        "turn.running",
                                        Map.of("turn_id", turn, "status", "running"))),
                        "running");
        return new ManagedCommitPlan(
                boundary,
                ManagedScope.fingerprint("foreign-commit"),
                List.of(change),
                List.of(),
                segment,
                List.of(),
                List.of(),
                List.of(),
                "state");
    }

    @Test
    void adapterEvidenceCannotOverrideDurableToolIdentityAndRollsBackTheWholeBoundary()
            throws Exception {
        String operation = "call_0";
        String task = "tool_" + ManagedScope.fingerprint(scope.messageGroup(), turn, operation);
        String arguments = "{\"native_tool_call_id\":\"native-call\",\"command\":\"echo ok\"}";
        var nativeBoundary =
                new ManagedRuntimeBatch(
                        scope,
                        turn,
                        1,
                        "token",
                        "writer",
                        "invalid-operation",
                        0,
                        List.of(nativeEvent(1, "state/checkpoint", Map.of("stateJson", state))),
                        List.of(new ManagedRuntimeBatch.ToolIntent(operation, "exec", arguments)),
                        List.of(),
                        null,
                        ManagedRuntimeBatch.Suspension.tools("wait", List.of(task)));
        var boundary =
                AgentScopeCheckpoints.prepare(
                        checkpoints,
                        nativeBoundary,
                        Map.of(
                                "toolOperations",
                                List.of(
                                        Map.of(
                                                "taskId",
                                                task,
                                                "executionId",
                                                "wrong-execution",
                                                "operationToolCallId",
                                                operation,
                                                "name",
                                                "exec",
                                                "arguments",
                                                arguments))));
        assertEquals(
                "checkpoint_tool_conflict",
                assertThrows(ManagedStateException.class, () -> commit(boundary)).code());
        assertEquals(0, journal.head(scope).sequence());
        assertNull(journal.receipt(scope, "invalid-operation"));
        assertTrue(journal.tool(scope, task).isEmpty());
        for (String table : List.of("state_checkpoint", "turn_suspension", "tool_command_outbox"))
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM ma." + table + " WHERE scope_key=?",
                            Integer.class,
                            scope.messageGroup()),
                    table);
    }

    ManagedRuntimeBatch batch(String id, long expected) throws Exception {
        var event =
                new SessionEvent(
                        1,
                        id,
                        expected + 1,
                        System.currentTimeMillis(),
                        "state/checkpoint",
                        "run",
                        turn,
                        true,
                        mapper.writeValueAsString(Map.of("stateJson", state, "reason", id)));
        return AgentScopeCheckpoints.prepare(
                checkpoints,
                new ManagedRuntimeBatch(
                        scope,
                        turn,
                        1,
                        "token",
                        "writer",
                        id,
                        expected,
                        List.of(event),
                        List.of(),
                        List.of(),
                        null));
    }

    private SessionCommitReceipt commit(ManagedRuntimeBatch batch) {
        return store.commit(ManagedSessionCommitAdapter.encode(batch));
    }

    private ManagedTurnExecution privateWorkspaceFrame() throws Exception {
        var result = Msg.builder().role(MsgRole.ASSISTANT).textContent("done").build();
        commit(
                AgentScopeCheckpoints.prepare(
                        checkpoints,
                        nativeBatch(
                                "private-frame",
                                0,
                                List.of(
                                        nativeEvent(
                                                1,
                                                ManagedFinalization.START,
                                                Map.of(
                                                        "id",
                                                        "private-frame",
                                                        "startedAt",
                                                        123L,
                                                        "zone",
                                                        "UTC",
                                                        "result",
                                                        result,
                                                        "stateJson",
                                                        state))))));
        var guard = mock(ManagedExecutionGuard.class);
        when(guard.token()).thenReturn("token");
        when(guard.remaining()).thenReturn(Duration.ofSeconds(30));
        return new ManagedTurnExecution(
                scope,
                turn,
                1,
                guard,
                admission,
                journal,
                new ManagedRuntimeWriter(journal, new SessionCommitTransactions(journal)));
    }

    @Test
    void normalDeliveryAndDuplicateUseNoExecutionSqlAndGapReleasesReceipt() throws Exception {
        journal.resourceEvent(
                scope, "first", "session.created", Map.of("session_id", scope.sessionId()));
        var first = delivery(1);
        journal.resourceEvent(
                scope, "second", "session.updated", Map.of("session_id", scope.sessionId()));
        var second = delivery(2);
        var forbidden = mock(DataSource.class);
        when(forbidden.getConnection())
                .thenThrow(
                        new AssertionError("Projection must not borrow an execution connection"));
        var querySource =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:no-execution-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        PostgresSessionProjectionStore.initialize(querySource);
        var target = new PostgresSessionProjectionStore(querySource, mapper);
        var views =
                new ManagedQueryViews(
                        target,
                        mapper,
                        "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8),
                        "v1",
                        1000,
                        new ManagedPublicResources(forbidden, mapper));
        var reader =
                new ManagedSessionProjections(
                        forbidden,
                        mapper,
                        new SessionEventNotifier(forbidden, 1000),
                        checkpoints.backend());
        reader.queryViews(views);
        assertThrows(ManagedDeliverySuspended.class, () -> reader.deliver(second));
        reader.deliver(first);
        reader.deliver(first); // ACK loss does not rewrite execution or advance twice.
        reader.deliver(second);
        assertEquals(2, views.progress(scope).visibleSequence());
        assertEquals(2, views.events(scope, 0, 10).size());
        verifyNoInteractions(forbidden);
        var protocolFacts =
                List.of(
                        Map.entry(
                                "protocol.event",
                                Map.of(
                                        "id",
                                        "user-event",
                                        "type",
                                        "user.message",
                                        "managed_agents",
                                        Map.of("turn_id", turn, "intent", "new_turn"))),
                        Map.entry(
                                "item.completed",
                                Map.of(
                                        "turn_id",
                                        turn,
                                        "item",
                                        Map.of(
                                                "role",
                                                "user",
                                                "message_id",
                                                "command_" + turn + "_0"))),
                        Map.entry(
                                "protocol.catalog",
                                Map.of(
                                        "turn_id",
                                        turn,
                                        "tools",
                                        List.of(
                                                Map.of(
                                                        "name",
                                                        "mcp.echo",
                                                        "server",
                                                        "server",
                                                        "remoteName",
                                                        "echo")))),
                        Map.entry(
                                "tool.requested",
                                Map.of(
                                        "turn_id",
                                        turn,
                                        "tool_call_id",
                                        "call",
                                        "name",
                                        "mcp.echo",
                                        "input",
                                        Map.of("text", "echo"))),
                        Map.entry(
                                "tool.completed",
                                Map.of(
                                        "turn_id",
                                        turn,
                                        "tool_call_id",
                                        "call",
                                        "name",
                                        "mcp.echo",
                                        "status",
                                        "succeeded",
                                        "result",
                                        Map.of(
                                                "output",
                                                List.of(Map.of("type", "text", "text", "echo"))))),
                        Map.entry(
                                "model.started", Map.of("turn_id", turn, "model_call_id", "model")),
                        Map.entry(
                                "usage.recorded",
                                Map.of(
                                        "turn_id",
                                        turn,
                                        "model_call_id",
                                        "model",
                                        "usage",
                                        Map.of(
                                                "inputTokens",
                                                1,
                                                "outputTokens",
                                                1,
                                                "cachedTokens",
                                                0,
                                                "cacheCreationTokens",
                                                0))),
                        Map.entry(
                                "item.finalized",
                                Map.of(
                                        "turn_id",
                                        turn,
                                        "item_id",
                                        "answer",
                                        "item",
                                        Map.of(
                                                "role",
                                                "assistant",
                                                "message_id",
                                                "answer",
                                                "content",
                                                List.of(
                                                        Map.of(
                                                                "type", "text", "text",
                                                                "answer"))))),
                        Map.entry("turn.completed", Map.of("turn_id", turn, "status", "completed")),
                        Map.entry(
                                "protocol.event",
                                Map.of(
                                        "id",
                                        "queued-event",
                                        "type",
                                        "user.message",
                                        "managed_agents",
                                        Map.of("turn_id", "queued", "intent", "new_turn"))),
                        Map.entry(
                                "protocol.event",
                                Map.of(
                                        "id",
                                        "conversion",
                                        "type",
                                        "user.queue.convert_to_steer",
                                        "managed_agents",
                                        Map.of(
                                                "turn_id",
                                                "queued",
                                                "target_turn_id",
                                                "active",
                                                "input_id",
                                                "steer"))),
                        Map.entry(
                                "input.applied", Map.of("turn_id", "active", "input_id", "steer")));
        new TransactionTemplate(new DataSourceTransactionManager(source))
                .executeWithoutResult(
                        ignored -> {
                            int id = 0;
                            for (var fact : protocolFacts)
                                journal.projections()
                                        .capture(
                                                scope,
                                                "projection-" + (++id),
                                                fact.getKey(),
                                                mapper.valueToTree(fact.getValue()).toString(),
                                                1000 + id);
                        });
        reader.deliver(delivery(3));
        reader.deliver(delivery(3));
        assertNotNull(views.protocol().latest(scope, "user-event").get("processed_at"));
        assertNotNull(views.protocol().latest(scope, "queued-event").get("processed_at"));
        verifyNoInteractions(forbidden);
        // Same visible version with a different stable identity cannot be silently ACKed.
        var original = first.commits().get(0);
        var conflict =
                new ManagedProjectionNotice(
                        3,
                        scope,
                        List.of(
                                new ManagedProjectionNotice.Commit(
                                        "different", original.hash(), original.manifest())));
        assertEquals(
                "projection_conflict",
                assertThrows(ManagedStateException.class, () -> reader.deliver(conflict)).code());
    }

    @Test
    void postCommitWakeupNeverRunsOnRollbackAndPrivateCommitProducesNoHint() throws Exception {
        var calls = new AtomicInteger();
        try (var signal = ManagedProjectionSignals.subscribe(source, calls::incrementAndGet)) {
            var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            tx.executeWithoutResult(
                                    ignored -> {
                                        journal.resourceEvent(
                                                scope,
                                                "rolled-back",
                                                "session.created",
                                                Map.of("session_id", scope.sessionId()));
                                        throw new IllegalStateException("crash");
                                    }));
            assertEquals(0, calls.get());
            assertTrue(journal.projections().outbox().pending(32).isEmpty());
            commit(batch("private", 0));
            assertEquals(0, calls.get());
            journal.resourceEvent(
                    scope, "visible", "session.created", Map.of("session_id", scope.sessionId()));
            assertEquals(1, calls.get());
        }
    }

    @Test
    void brokerConfirmationFailureReusesCommitAndExpiredClaimReplaysSameDelivery()
            throws Exception {
        journal.resourceEvent(
                scope, "visible", "session.created", Map.of("session_id", scope.sessionId()));
        var outbox = journal.projections().outbox();
        var first = outbox.claim(scope);
        var message = outbox.message(first);
        // Broker accepted, process died before marking sent.
        jdbc.update(
                "UPDATE ma.runtime_commit SET next_attempt_at=0 WHERE scope_key=?",
                scope.messageGroup());
        var recovered = outbox.claim(scope);
        assertEquals(message, outbox.message(recovered));
        assertNotEquals(first.token(), recovered.token());
        outbox.retry(first);
        assertEquals(
                recovered.token(),
                jdbc.queryForObject(
                        "SELECT publisher_token FROM ma.runtime_commit WHERE scope_key=?",
                        String.class,
                        scope.messageGroup()));
        outbox.completed(recovered);
        assertTrue(outbox.pending(32).isEmpty());
    }

    private ManagedProjectionNotice delivery(long version) throws Exception {
        var row =
                jdbc.queryForMap(
                        "SELECT commit_id,projection_manifest FROM ma.runtime_commit WHERE"
                                + " scope_key=? AND public_commit_version=?",
                        scope.messageGroup(),
                        version);
        var manifest =
                mapper.readValue(
                        (String) row.get("projection_manifest"),
                        ManagedProjectionFacts.Manifest.class);
        return new ManagedProjectionNotice(
                3,
                scope,
                List.of(
                        new ManagedProjectionNotice.Commit(
                                (String) row.get("commit_id"),
                                ManagedProjectionFacts.identity(manifest),
                                manifest)));
    }

    @Test
    void retainedCommitsRebuildAShadowWithoutMqAndRejectMissingHistory() {
        journal.resourceEvent(scope, "one", "session.updated", Map.of("value", 1));
        journal.resourceEvent(scope, "two", "session.updated", Map.of("value", 2));
        var outbox = journal.projections().outbox();
        var claim = outbox.claim(scope);
        outbox.completed(claim); // Broker history is no longer available to this rebuild.
        var shadowStore =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:shadow-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        PostgresSessionProjectionStore.initialize(shadowStore);
        var view =
                new ManagedQueryViews(
                        new PostgresSessionProjectionStore(shadowStore, mapper),
                        mapper,
                        "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8),
                        "shadow-v3",
                        1000,
                        new ManagedPublicResources(source, mapper));
        var shadow =
                new ManagedSessionProjections(
                        source,
                        mapper,
                        new SessionEventNotifier(source, 1000),
                        checkpoints.backend());
        shadow.queryViews(view);
        var replayFacts =
                new ManagedProjectionFacts(
                        source, mapper, new ManagedFactBundles(checkpoints.backend(), mapper));
        assertEquals(2, ManagedQueryStoreMain.rebuild(replayFacts, view, scope, 2));
        assertEquals(0, ManagedQueryStoreMain.rebuild(replayFacts, view, scope, 2));
        assertEquals(0, shadow.project(scope, 32));
        assertEquals(2, view.progress(scope).commitVersion());
        assertEquals(2, view.events(scope, 0, 10).size());
        shadow.deliver(new ManagedProjectionNotice(3, scope, claim.commits()));
        assertEquals(2, view.progress(scope).commitVersion());
        journal.resourceEvent(scope, "three", "session.updated", Map.of("value", 3));
        jdbc.update(
                "DELETE FROM ma.runtime_commit WHERE scope_key=? AND public_commit_version=3",
                scope.messageGroup());
        assertEquals(
                "projection_gap",
                assertThrows(ManagedStateException.class, () -> shadow.project(scope, 32)).code());
    }

    private void queryBackend() {
        var querySource =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:delivery-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        PostgresSessionProjectionStore.initialize(querySource);
        journal.queryViews(
                new ManagedQueryViews(
                        new PostgresSessionProjectionStore(querySource, mapper),
                        mapper,
                        "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8),
                        "v1",
                        1000,
                        new ManagedPublicResources(source, mapper)));
    }

    private BaseStore transactionalWorkspace() throws Exception {
        AbstractJdbcDialect dialect;
        try (var connection = source.getConnection()) {
            dialect =
                    connection.getMetaData().getDatabaseProductName().equals("H2")
                            ? new H2Dialect()
                            : new PostgresDialect();
        }
        // Only the workspace table is needed; SDK snapshots use BLOB, unsupported in H2 PG mode.
        dialect.storeCreateTableDdls().forEach(jdbc::execute);
        return JdbcStore.builder(new TransactionAwareDataSourceProxy(source))
                .dialect(dialect)
                .build();
    }

    private RuntimeContext privateWorkspaceContext(ManagedTurnExecution execution) {
        var phase = new ManagedFinalization(execution, mapper);
        ReflectionTestUtils.setField(phase, "active", true);
        return RuntimeContext.builder().put(AgentFinalization.class, phase).build();
    }

    @Test
    void concurrentHeadAdvanceAfterAnEmptyRangeReadIsNotAProjectionGap() {
        var objects = new FileRuntimeObjectStore("fixture", directory);
        var reader =
                spy(
                        new ManagedProjectionFacts(
                                source, mapper, new ManagedFactBundles(objects, mapper)));
        doAnswer(
                        call -> {
                            journal.resourceEvent(
                                    scope,
                                    "racing-head",
                                    "session.created",
                                    Map.of("session_id", scope.sessionId()));
                            return call.callRealMethod();
                        })
                .when(reader)
                .acceptedVersion(scope);
        var pages = reader.next(scope, 0, 32);
        assertEquals(1, pages.size());
        assertEquals(1, pages.get(0).facts().size());
        jdbc.update("DELETE FROM ma.runtime_commit WHERE scope_key=?", scope.messageGroup());
        var fresh =
                new ManagedProjectionFacts(source, mapper, new ManagedFactBundles(objects, mapper));
        assertEquals(
                "projection_gap",
                assertThrows(ManagedStateException.class, () -> fresh.next(scope, 0, 32)).code());
    }

    @Test
    void finalizationEndStagesUntilRunFinishAndCrashReplaysTheUnfinishedFrame() throws Exception {
        var execution = privateWorkspaceFrame();
        var runtime = RuntimeContext.builder().build();
        var agentState = AgentState.fromJsonString(state);
        runtime.setAgentState(agentState);
        var recorder = new SessionRecorder(execution.log(), turn);
        runtime.put(SessionRecorder.CONTEXT_KEY, recorder);
        try {
            recorder.start(List.of(), agentState).block();
            var phase = new ManagedFinalization(execution, mapper);
            long before = journal.head(scope).sequence();
            phase.finish(runtime, phase.resumeResult(), Mono::empty).block();
            assertEquals(
                    before,
                    journal.head(scope).sequence(),
                    "Stage must not masquerade as a durable commit");
            assertFalse(journal.finalization(scope, turn).completed());
        } finally {
            recorder.close();
        } // Simulate process loss before the enclosing finish flush.
        var replacement = new SessionRecorder(execution.log(), turn);
        runtime.put(SessionRecorder.CONTEXT_KEY, replacement);
        try {
            replacement.start(List.of(), agentState).block();
            var phase = new ManagedFinalization(execution, mapper);
            assertTrue(phase.pending());
            phase.finish(runtime, phase.resumeResult(), Mono::empty).block();
            replacement.finish(agentState, "completed").block();
            assertTrue(journal.finalization(scope, turn).completed());
            var facts = journal.recoveryEvents(scope, 0, 1000);
            long end =
                    facts.stream()
                            .filter(e -> ManagedFinalization.END.equals(e.type()))
                            .findFirst()
                            .orElseThrow()
                            .seq();
            long runEnd =
                    facts.stream()
                            .filter(e -> "run/end".equals(e.type()))
                            .mapToLong(SessionEvent::seq)
                            .max()
                            .orElseThrow();
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM ma.runtime_commit WHERE scope_key=? AND"
                                    + " first_sequence<=? AND last_sequence>=?",
                            Integer.class,
                            scope.messageGroup(),
                            end,
                            runEnd));
        } finally {
            replacement.close();
        }
    }

    @Test
    void publicModelBoundaryWritesSessionAndTurnOnceIncludingUsage() throws Exception {
        var counter = new QueryCounter(source);
        var measured = ProjectionTestSupport.journal(counter, mapper, checkpoints);
        var boundary =
                AgentScopeCheckpoints.prepare(
                        checkpoints,
                        nativeBatch(
                                "combined-model",
                                0,
                                List.of(
                                        nativeEvent(
                                                1, "state/checkpoint", Map.of("stateJson", state)),
                                        nativeEvent(
                                                2,
                                                "request/prepared",
                                                Map.of("modelCallId", "call")),
                                        nativeEvent(
                                                3, "model/dispatch", Map.of("modelCallId", "call")),
                                        nativeEvent(
                                                4,
                                                "model/end",
                                                Map.of(
                                                        "modelCallId",
                                                        "call",
                                                        "status",
                                                        "completed",
                                                        "usage",
                                                        Map.of(
                                                                "inputTokens",
                                                                12,
                                                                "outputTokens",
                                                                3))))));
        counter.statements.clear();
        AgentScopePreparation.commit(measured, new SessionCommitTransactions(measured), boundary);
        assertEquals(
                1,
                counter.statements.stream()
                        .filter(sql -> sql.startsWith("UPDATE ma.session SET"))
                        .count());
        assertEquals(
                1,
                counter.statements.stream()
                        .filter(sql -> sql.startsWith("UPDATE ma.turn SET"))
                        .count());
        assertEquals(1, counter.selecting("ma.session"));
        assertEquals(1, counter.selecting("ma.turn"));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.runtime_commit WHERE scope_key=?",
                        Integer.class,
                        scope.messageGroup()));
        assertEquals(1, measured.projections().acceptedVersion(scope));
    }

    @Test
    void privateMemoryWriteBeforeFinalizationCheckpointReplaysOriginalReadsWithoutAppendingTwice()
            throws Exception {
        var execution = privateWorkspaceFrame();
        var backend = transactionalWorkspace();
        var namespace = List.of("private", scope.messageGroup());
        var fs =
                new ManagedPrivateFilesystem(
                        new ManagedTransactionalWorkspaceStore(backend, source), namespace);
        String path = "memory/day.md";
        var first = privateWorkspaceContext(execution);
        assertFalse(fs.read(first, path, 0, 0).isSuccess());
        assertFalse(fs.read(first, path, 0, 0).isSuccess());
        fs.uploadFiles(
                first, List.of(Map.entry(path, "one entry".getBytes(StandardCharsets.UTF_8))));
        var committed = backend.get(namespace, path);
        assertNotNull(committed);
        // The file is committed, but END/checkpoint did not happen. A fresh Harness repeats hooks.
        var restored = privateWorkspaceContext(execution);
        assertFalse(fs.read(restored, path, 0, 0).isSuccess());
        assertFalse(fs.read(restored, path, 0, 0).isSuccess());
        fs.uploadFiles(
                restored, List.of(Map.entry(path, "one entry".getBytes(StandardCharsets.UTF_8))));
        assertEquals(committed, backend.get(namespace, path));
        assertEquals("one entry", backend.get(namespace, path).value().get("content"));
        assertTrue(
                jdbc.queryForObject(
                                "SELECT count(*) FROM ma.finalization_io WHERE read_ref IS NOT"
                                        + " NULL",
                                Integer.class)
                        > 0);
    }

    @Test
    void privateFileMutationAndItsReceiptRollbackTogether() throws Exception {
        var execution = privateWorkspaceFrame();
        var backend = spy(transactionalWorkspace());
        var namespace = List.of("private", scope.messageGroup());
        doAnswer(
                        call -> {
                            call.callRealMethod();
                            throw new IllegalStateException(
                                    "Crash after file SQL before receipt SQL");
                        })
                .when(backend)
                .put(any(), anyString(), any());
        var recorder =
                new ManagedFinalizationWorkspace(
                        execution,
                        "private-frame",
                        new ManagedTransactionalWorkspaceStore(backend, source),
                        new AtomicInteger()::getAndIncrement,
                        mapper);
        assertThrows(
                IllegalStateException.class,
                () -> recorder.put(namespace, "file", Map.of("content", "data")));
        assertNull(backend.get(namespace, "file"));
        assertEquals(
                0, jdbc.queryForObject("SELECT count(*) FROM ma.finalization_io", Integer.class));
    }

    @Test
    void privateReplayRejectsChangedContentAndStaleOwners() throws Exception {
        var execution = privateWorkspaceFrame();
        var backend = new ManagedTransactionalWorkspaceStore(transactionalWorkspace(), source);
        var namespace = List.of("private", scope.messageGroup());
        new ManagedFinalizationWorkspace(
                        execution,
                        "private-frame",
                        backend,
                        new AtomicInteger()::getAndIncrement,
                        mapper)
                .put(namespace, "file", Map.of("content", "data", "modified_at", "first"));
        var same =
                new ManagedFinalizationWorkspace(
                        execution,
                        "private-frame",
                        backend,
                        new AtomicInteger()::getAndIncrement,
                        mapper);
        same.put(namespace, "file", Map.of("content", "data", "modified_at", "regenerated"));
        assertEquals("first", backend.get(namespace, "file").value().get("modified_at"));
        var changed =
                new ManagedFinalizationWorkspace(
                        execution,
                        "private-frame",
                        backend,
                        new AtomicInteger()::getAndIncrement,
                        mapper);
        assertThrows(
                ManagedStateException.class,
                () -> changed.put(namespace, "file", Map.of("content", "different")));
        jdbc.update(
                "UPDATE ma.session SET execution_epoch=2 WHERE scope_key=?", scope.messageGroup());
        assertThrows(
                ManagedStateException.class,
                () -> same.put(namespace, "other", Map.of("content", "stale")));
        assertNull(backend.get(namespace, "other"));
    }

    @Test
    void privateCompareAndSetConflictDoesNotAbortReceiptTransaction() throws Exception {
        var execution = privateWorkspaceFrame();
        var backend = new ManagedTransactionalWorkspaceStore(transactionalWorkspace(), source);
        var namespace = List.of("private", scope.messageGroup());
        backend.put(namespace, "file", Map.of("content", "original"));
        var recorder =
                new ManagedFinalizationWorkspace(
                        execution,
                        "private-frame",
                        backend,
                        new AtomicInteger()::getAndIncrement,
                        mapper);
        assertFalse(recorder.putIfVersion(namespace, "file", Map.of("content", "changed"), 0));
        var replay =
                new ManagedFinalizationWorkspace(
                        execution,
                        "private-frame",
                        backend,
                        new AtomicInteger()::getAndIncrement,
                        mapper);
        assertFalse(replay.putIfVersion(namespace, "file", Map.of("content", "changed"), 0));
        assertEquals("original", backend.get(namespace, "file").value().get("content"));
        assertEquals(
                1, jdbc.queryForObject("SELECT count(*) FROM ma.finalization_io", Integer.class));
    }

    @Test
    void privateWritesRejectAStoreOutsideTheExecutionTransaction() throws Exception {
        var execution = privateWorkspaceFrame();
        var recorder =
                new ManagedFinalizationWorkspace(
                        execution,
                        "private-frame",
                        transactionalWorkspace(),
                        new AtomicInteger()::getAndIncrement,
                        mapper);
        assertEquals(
                "workspace_not_transactional",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        recorder.put(
                                                List.of("private"),
                                                "file",
                                                Map.of("content", "unsafe")))
                        .code());
    }

    @Test
    void privateBoundaryDoesNotCreateQueryWorkAndPublicCasRecoveryStillRepairsWatermark()
            throws Exception {
        var querySource =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:query-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        PostgresSessionProjectionStore.initialize(querySource);
        var views =
                new ManagedQueryViews(
                        new PostgresSessionProjectionStore(querySource, mapper),
                        mapper,
                        "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8),
                        "v1",
                        1000,
                        new ManagedPublicResources(source, mapper));
        journal.queryViews(views);
        commit(batch("empty-view", 0));
        assertEquals(0, journal.projections().acceptedVersion(scope));
        assertEquals(0, journal.projections().project(scope, 128));
        assertEquals(0, views.progress(scope).commitVersion());
        journal.resourceEvent(
                scope, "created-view", "session.created", Map.of("session_id", scope.sessionId()));
        assertEquals(1, journal.projections().acceptedVersion(scope));
        assertEquals(1, journal.projections().project(scope, 128));
        assertEquals(1, views.progress(scope).visibleSequence());
        assertEquals(1, views.progress(scope).commitVersion());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT projection_version FROM ma.runtime_commit WHERE scope_key=? AND"
                                + " commit_id='empty-view'",
                        Long.class,
                        scope.messageGroup()));
        assertEquals(0, journal.projections().project(scope, 128));
        assertEquals(1, views.progress(scope).commitVersion());
        assertEquals(1, journal.publicEvents(scope, 0, 10).size());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ma.public_event", Integer.class));
    }

    @Test
    void directCommitAndLostResponseReplayHaveOneDurableHead() throws Exception {
        var boundary = batch("first", 0);
        var result = commit(boundary);
        assertEquals("committed", result.outcome());
        assertEquals(1, result.sequence());
        assertEquals(result, commit(boundary));
        assertEquals(result, store.receipt(scope, "first"));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM ma.runtime_commit WHERE scope_key=?",
                        Integer.class,
                        scope.messageGroup()));
        assertEquals(1, journal.recoveryEvents(scope, 0, 10).size());
    }

    @Test
    void boundaryManifestStoresOneRangeRatherThanPerEventIdentities() throws Exception {
        var events = new ArrayList<SessionEvent>();
        for (int i = 1; i <= 256; i++)
            events.add(
                    new SessionEvent(
                            1, "fact-" + i, i, 1, "fixture/fact", "run", turn, false, "{}"));
        var boundary = nativeBatch("range", 0, events);
        var result = commit(boundary);
        assertEquals(result, commit(boundary));
        var manifest = jdbc.queryForMap("SELECT * FROM ma.runtime_commit WHERE commit_id='range'");
        assertEquals(1L, ((Number) manifest.get("first_sequence")).longValue());
        assertEquals(256L, ((Number) manifest.get("last_sequence")).longValue());
        assertEquals(turn, manifest.get("turn_id"));
        assertNotNull(manifest.get("recovery_ref"));
        assertEquals(
                1, jdbc.queryForObject("SELECT COUNT(*) FROM ma.runtime_commit", Integer.class));
        assertEquals(256, journal.recoveryEvents(scope, 0, 4096).size());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.tables WHERE"
                                + " lower(table_name)='recovery_fact_identity'",
                        Integer.class));
    }

    @Test
    void idempotentReceiptSurvivesEpochChangeButDifferentContentIsRejected() throws Exception {
        var boundary = batch("first", 0);
        var receipt = commit(boundary);
        jdbc.update(
                "UPDATE ma.session SET execution_epoch=2 WHERE scope_key=?", scope.messageGroup());
        assertEquals(receipt, commit(boundary));
        var changed =
                new ManagedRuntimeBatch(
                        scope,
                        turn,
                        1,
                        "other",
                        "writer",
                        "first",
                        0,
                        boundary.events(),
                        List.of(),
                        List.of(),
                        null);
        assertThrows(ManagedStateException.class, () -> commit(changed));
    }

    @Test
    void stagedCheckpointCannotAdvanceHeadAfterExecutionIsFenced() throws Exception {
        var boundary = batch("staged", 0);
        assertEquals(0, store.head(scope).sequence());
        jdbc.update(
                "UPDATE ma.session SET execution_epoch=2 WHERE scope_key=?", scope.messageGroup());
        assertThrows(ManagedStateException.class, () -> commit(boundary));
        assertEquals(0, store.head(scope).sequence());
        assertNull(store.receipt(scope, "staged"));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM ma.runtime_commit WHERE scope_key=?",
                        Integer.class,
                        scope.messageGroup()));
    }

    @Test
    void anotherBrainRecoversFromSharedFilesAndNativeSegmentMetadata() throws Exception {
        commit(batch("first", 0));
        var second =
                ProjectionTestSupport.journal(
                        source,
                        mapper,
                        new ManagedCheckpointObjects(
                                mapper, new FileRuntimeObjectStore("fixture", directory)));
        assertEquals(state, second.recoveryEvents(scope, 0, 10).get(0).data().get("stateJson"));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM ma.runtime_commit WHERE scope_key=?",
                        Integer.class,
                        scope.messageGroup()));
    }

    @Test
    void repeatedStateReusesBytesAcrossDistinctCheckpointManifests() throws Exception {
        commit(batch("first", 0));
        commit(batch("second", 1));
        try (var paths = Files.list(directory.resolve(scope.messageGroup()).resolve("state"))) {
            assertEquals(1, paths.count());
        }
        assertEquals(2, journal.recoveryEvents(scope, 0, 10).size());
    }

    @Test
    void projectionOnlyMovesItsOwnWatermarkAndCanLagBehindCoreFacts() {
        journal.resourceEvent(
                scope, "created", "session.created", Map.of("session_id", scope.sessionId()));
        assertEquals(1, journal.projections().progress(scope).acceptedThrough());
        assertEquals(0, journal.publicHead(scope));
        assertTrue(journal.publicEvents(scope, 0, 100).isEmpty());
        assertEquals(1, journal.projections().project(scope, 32));
        assertEquals(1, journal.publicHead(scope));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT public_sequence FROM ma.session WHERE scope_key=?",
                        Long.class,
                        scope.messageGroup()));
        assertEquals(0, journal.projections().project(scope, 32));
    }

    @Test
    void agentServiceConsumerHandlesDuplicateWakeupsAndRepairsMissingNotification()
            throws Exception {
        queryBackend();
        try (var bus = new InMemoryManagedMessageBus(System::currentTimeMillis, 8);
                var consumer =
                        new ManagedProjectionConsumer(journal.projections(), bus, mapper, 2)) {
            consumer.start();
            journal.resourceEvent(
                    scope, "created", "session.created", Map.of("session_id", scope.sessionId()));
            var codec = new ManagedBatchCodec(mapper, checkpoints.backend());
            var publisher = new ManagedProjectionPublisher(journal, bus, mapper);
            publisher.scan();
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (journal.publicHead(scope) < 1 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(1, journal.publicHead(scope));
            bus.publish(
                    ManagedMessageBus.Channel.SESSION_PROJECTIONS,
                    new ManagedMessageBus.Message(
                            "duplicate",
                            scope.messageGroup(),
                            mapper.writeValueAsBytes(delivery(1))));
            journal.resourceEvent(
                    scope, "metadata", "session.updated", Map.of("session_id", scope.sessionId()));
            // Deliberately omit the second MQ notice and trigger the low-frequency repair tick.
            publisher.scan();
            while (journal.publicHead(scope) < 2 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(2, journal.publicHead(scope));
            assertEquals(2, journal.publicEvents(scope, 0, 10).size());
        }
    }

    @Test
    void isolatedProjectionPoolUsesItsOwnTransactionAndNotifiesApiAfterCommit() {
        var apiNotifier = new SessionEventNotifier(source, 1000);
        var querySide =
                new ManagedSessionProjections(
                        new DelegatingDataSource(source),
                        mapper,
                        apiNotifier,
                        checkpoints.backend());
        querySide.queryViews(ProjectionTestSupport.views(source, mapper));
        var notifications = new AtomicInteger();
        var listener =
                apiNotifier
                        .wakeups(scope.messageGroup())
                        .skip(1)
                        .subscribe(ignored -> notifications.incrementAndGet());
        try {
            journal.resourceEvent(
                    scope, "created", "session.created", Map.of("session_id", scope.sessionId()));
            assertEquals(0, notifications.get());
            assertEquals(1, querySide.project(scope, 32));
            assertEquals(
                    1,
                    notifications
                            .get()); // Local wakeup, without execution-DB notification or progress
            // write.
            assertEquals(1, journal.publicHead(scope));
        } finally {
            listener.dispose();
        }
    }

    @Test
    void delayedProjectionPreservesSourceTimeAndCausalOrderForEqualTimestamps() {
        new TransactionTemplate(new DataSourceTransactionManager(source))
                .executeWithoutResult(
                        ignored -> {
                            journal.projections()
                                    .capture(
                                            scope,
                                            "terminal",
                                            "turn.cancelled",
                                            mapper.valueToTree(
                                                            Map.of(
                                                                    "turn_id",
                                                                    turn,
                                                                    "status",
                                                                    "cancelled"))
                                                    .toString(),
                                            1234);
                            journal.projections()
                                    .capture(
                                            scope,
                                            "running",
                                            "turn.running",
                                            mapper.valueToTree(
                                                            Map.of(
                                                                    "turn_id",
                                                                    "next-turn",
                                                                    "status",
                                                                    "running"))
                                                    .toString(),
                                            1234);
                        });
        assertEquals(2, journal.projections().project(scope, 32));
        var history =
                mapper.valueToTree(
                                admission
                                        .protocol()
                                        .history(
                                                scope,
                                                "actor",
                                                Map.of(
                                                        "limit",
                                                        List.of("100"),
                                                        "types[]",
                                                        List.of(
                                                                "managed_agents.turn.cancelled",
                                                                "managed_agents.turn.running")),
                                                true))
                        .path("data");
        assertEquals(2, history.size());
        assertEquals("managed_agents.turn.cancelled", history.get(0).path("type").asText());
        assertEquals("managed_agents.turn.running", history.get(1).path("type").asText());
        for (var event : history)
            assertEquals("1970-01-01T00:00:01.234Z", event.path("processed_at").asText());
    }

    @Test
    void factSourceContainsOnlyObjectIndexesAndCorruptionCannotAdvanceProjection()
            throws Exception {
        var event = nativeEvent(1, "model/dispatch", Map.of("modelCallId", "call"));
        commit(nativeBatch("facts", 0, List.of(event)));
        var encoded =
                jdbc.queryForObject(
                        "SELECT projection_manifest FROM ma.runtime_commit WHERE scope_key=?",
                        String.class,
                        scope.messageGroup());
        var manifest = mapper.readValue(encoded, ManagedProjectionFacts.Manifest.class);
        assertEquals(1, manifest.entries().size());
        assertFalse(mapper.valueToTree(manifest.entries().get(0)).has("payload"));
        var reference = manifest.objects().values().iterator().next();
        var file =
                directory
                        .resolve(reference.scope())
                        .resolve(reference.kind())
                        .resolve(reference.key());
        Files.writeString(file, "corrupt");
        assertThrows(ManagedStateException.class, () -> journal.projections().project(scope, 32));
        assertEquals(0, journal.publicHead(scope));
        assertEquals(1, store.head(scope).sequence());
        assertEquals(1, journal.projections().progress(scope).acceptedThrough());
    }

    @Test
    void modelOutcomeIsAtomicPrivateAndReadableByAnotherBrainWithoutChunkReplay() throws Exception {
        commit(batch("entry", 0));
        var request =
                nativeEvent(
                        2,
                        "request/prepared",
                        Map.of(
                                "modelCallId",
                                "model",
                                "model",
                                "fixture",
                                "purpose",
                                "SUMMARY",
                                "messages",
                                List.of("PRIVATE_PROMPT")));
        var chunk =
                nativeEvent(
                        3,
                        "model/chunk",
                        Map.of(
                                "modelCallId",
                                "model",
                                "chunk",
                                Map.of(
                                        "id",
                                        "reply",
                                        "content",
                                        List.of(Map.of("type", "text", "text", "answer")),
                                        "finishReason",
                                        "stop")));
        commit(nativeBatch("response", 1, List.of(request)));
        assertEquals(null, journal.modelResult(scope, turn, "model"));
        var end =
                nativeEvent(
                        3,
                        "model/end",
                        Map.of(
                                ManagedModelCapture.OUTCOME,
                                Map.of(
                                        "request",
                                        request.data(),
                                        "responses",
                                        List.of(chunk.data().get("chunk")),
                                        "replayHash",
                                        ManagedScope.fingerprint("fixture")),
                                "modelCallId",
                                "model",
                                "status",
                                "completed",
                                "finishReason",
                                "stop",
                                "usage",
                                Map.of("inputTokens", 2, "outputTokens", 1)));
        var boundary = nativeBatch("outcome", 2, List.of(end));
        var receipt = commit(boundary);
        assertEquals(receipt, commit(boundary));
        var second =
                ProjectionTestSupport.journal(
                        source,
                        mapper,
                        new ManagedCheckpointObjects(
                                mapper, new FileRuntimeObjectStore("fixture", directory)));
        var result = second.modelResult(scope, turn, "model");
        assertEquals("completed", result.status());
        assertEquals(1, result.entryCheckpointSequence());
        assertEquals(3, result.completionSequence());
        assertEquals(1, result.responses().size());
        assertEquals("stop", result.completion().get("finishReason"));
        assertTrue(
                new String(checkpoints.backend().read(result.request()), StandardCharsets.UTF_8)
                        .contains("PRIVATE_PROMPT"));
        while (journal.projections().project(scope, 128) > 0) {}
        assertFalse(journal.publicEvents(scope, 0, 100).toString().contains("PRIVATE_PROMPT"));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM ma.model_call_result WHERE scope_key=? AND"
                                + " completion_sequence IS NOT NULL",
                        Integer.class,
                        scope.messageGroup()));
    }

    @Test
    void fencedModelCompletionDoesNotExposeItsStagedOutcome() throws Exception {
        var request =
                nativeEvent(
                        1,
                        "request/prepared",
                        Map.of("modelCallId", "model", "messages", List.of()));
        commit(nativeBatch("request", 0, List.of(request)));
        jdbc.update(
                "UPDATE ma.session SET execution_epoch=2 WHERE scope_key=?", scope.messageGroup());
        var end =
                nativeEvent(2, "model/end", Map.of("modelCallId", "model", "status", "completed"));
        assertThrows(
                ManagedStateException.class, () -> commit(nativeBatch("end", 1, List.of(end))));
        assertEquals(null, journal.modelResult(scope, turn, "model"));
        assertEquals(1, journal.head(scope).sequence());
    }

    @Test
    void failedAndCancelledModelsKeepTheirPartialOutcomeWithoutClaimingCompletion()
            throws Exception {
        long sequence = 0;
        for (String status : List.of("failed", "cancelled")) {
            var request =
                    nativeEvent(++sequence, "request/prepared", Map.of("modelCallId", status));
            var end =
                    nativeEvent(
                            ++sequence,
                            "model/end",
                            Map.of(
                                    "modelCallId",
                                    status,
                                    "status",
                                    status,
                                    "errorType",
                                    "fixture"));
            commit(nativeBatch(status, sequence - 2, List.of(request, end)));
            var result = journal.modelResult(scope, turn, status);
            assertEquals(status, result.status());
            assertTrue(result.responses().isEmpty());
            assertEquals("fixture", result.completion().get("errorType"));
        }
    }

    @Test
    void objectPackagesArePreparedAndFetchedOutsideSqlTransactions() throws Exception {
        var objects = new UnlockedRuntimeObjectStore(checkpoints.backend());
        var other =
                ProjectionTestSupport.journal(
                        source, mapper, new ManagedCheckpointObjects(mapper, objects));
        AgentScopePreparation.commit(
                other,
                new SessionCommitTransactions(other),
                nativeBatch(
                        "model",
                        0,
                        List.of(
                                nativeEvent(1, "request/prepared", Map.of("modelCallId", "call")),
                                nativeEvent(
                                        2,
                                        "model/end",
                                        Map.of("modelCallId", "call", "status", "completed")))));
        assertEquals("completed", other.modelResult(scope, turn, "call").status());
        assertEquals(1, other.projections().project(scope, 32));
        other.files().upload(scope, "actor", "file", "file.txt", "text/plain", new byte[] {1});
        assertEquals(1, other.projections().project(scope, 32));
    }

    @Test
    void concurrentConsumersRecheckPreparedPrefixWithoutDuplicateViews() throws Exception {
        var events = new ArrayList<SessionEvent>();
        for (int i = 1; i <= 12; i++)
            events.add(nativeEvent(i, "model/dispatch", Map.of("modelCallId", "call-" + i)));
        commit(nativeBatch("facts", 0, events));
        var executor = Executors.newFixedThreadPool(4);
        try {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 4; i++)
                futures.add(
                        executor.submit(
                                () -> {
                                    while (journal.projections().progress(scope).projectedThrough()
                                            < 12) journal.projections().project(scope, 2);
                                }));
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertEquals(12, journal.publicHead(scope));
        assertEquals(12, journal.publicEvents(scope, 0, 100).size());
    }

    @Test
    void missingModelResultCannotFallBackToDisplayOrNativeChunks() throws Exception {
        commit(
                nativeBatch(
                        "model",
                        0,
                        List.of(
                                nativeEvent(1, "request/prepared", Map.of("modelCallId", "call")),
                                nativeEvent(
                                        2,
                                        "model/end",
                                        Map.of("modelCallId", "call", "status", "completed")))));
        String encoded =
                jdbc.queryForObject(
                        "SELECT result_ref FROM ma.model_call_result WHERE scope_key=? AND"
                                + " model_call_id='call'",
                        String.class,
                        scope.messageGroup());
        var ref = mapper.readValue(encoded, RuntimeObjectStore.Reference.class);
        Files.delete(directory.resolve(ref.scope()).resolve(ref.kind()).resolve(ref.key()));
        assertEquals(2, journal.recoveryEvents(scope, 0, 100).size());
        assertThrows(
                ManagedTransportException.class, () -> journal.modelResult(scope, turn, "call"));
    }

    @Test
    void modelEntryCheckpointCannotMoveToALaterCaptureInTheCompletionBatch() throws Exception {
        var request = nativeEvent(1, "request/prepared", Map.of("modelCallId", "call"));
        var end = nativeEvent(2, "model/end", Map.of("modelCallId", "call", "status", "completed"));
        var later = nativeEvent(3, "state/checkpoint", Map.of("stateJson", state));
        commit(
                AgentScopeCheckpoints.prepare(
                        checkpoints, nativeBatch("model", 0, List.of(request, end, later))));
        assertEquals(3, journal.head(scope).checkpointSequence());
        assertEquals(0, journal.modelResult(scope, turn, "call").entryCheckpointSequence());
    }

    @Test
    void oneWakeupDrainsMultipleFactPagesToItsTarget() throws Exception {
        queryBackend();
        var events = new ArrayList<SessionEvent>();
        for (int i = 1; i <= 320; i++)
            events.add(nativeEvent(i, "model/dispatch", Map.of("modelCallId", "call-" + i)));
        commit(nativeBatch("many-facts", 0, events));
        try (var bus = new InMemoryManagedMessageBus(System::currentTimeMillis, 8);
                var consumer =
                        new ManagedProjectionConsumer(journal.projections(), bus, mapper, 2)) {
            consumer.start();
            bus.publish(
                    ManagedMessageBus.Channel.SESSION_PROJECTIONS,
                    new ManagedMessageBus.Message(
                            "wake", scope.messageGroup(), mapper.writeValueAsBytes(delivery(1))));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (journal.publicHead(scope) < 320 && System.nanoTime() < deadline)
                Thread.sleep(10);
            assertEquals(320, journal.publicHead(scope));
        }
    }

    @Test
    void finalizationUsesCommittedIndexesAndFrozenCaptureWithoutReadingNativeLog()
            throws Exception {
        var start =
                nativeEvent(
                        1,
                        ManagedFinalization.START,
                        Map.of(
                                "id",
                                "frame",
                                "startedAt",
                                123L,
                                "zone",
                                "UTC",
                                "result",
                                Map.of(),
                                "stateJson",
                                state));
        var model =
                nativeEvent(
                        2,
                        ManagedFinalization.MODEL,
                        Map.of(
                                "frame",
                                "frame",
                                "slot",
                                0,
                                "modelCallId",
                                "call",
                                "requestHash",
                                "0".repeat(64)));
        var request = nativeEvent(3, "request/prepared", Map.of("modelCallId", "call"));
        var end = nativeEvent(4, "model/end", Map.of("modelCallId", "call", "status", "completed"));
        var batch =
                AgentScopeCheckpoints.prepare(
                        checkpoints, nativeBatch("frame", 0, List.of(start, model, request, end)));
        commit(batch);
        var backend = spy(checkpoints.backend());
        doThrow(new AssertionError("Recovery must not scan native log"))
                .when(backend)
                .read(anyString(), eq("native"), anyString());
        var recovered =
                ProjectionTestSupport.journal(
                        source, mapper, new ManagedCheckpointObjects(mapper, backend));
        assertEquals("frame", recovered.finalization(scope, turn).data().get("id"));
        assertFalse(recovered.finalization(scope, turn).completed());
        assertEquals(
                List.of(new ManagedJournalStore.FinalizationModel("call", "0".repeat(64))),
                recovered.finalizationModels(scope, turn, "frame", 0));
        assertEquals("completed", recovered.modelResult(scope, turn, "call").status());
        commit(
                nativeBatch(
                        "done",
                        4,
                        List.of(nativeEvent(5, ManagedFinalization.END, Map.of("id", "frame")))));
        assertTrue(recovered.finalization(scope, turn).completed());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM ma.finalization_frame WHERE scope_key=? AND"
                                + " turn_id=?",
                        Integer.class,
                        scope.messageGroup(),
                        "other"));
    }

    @Test
    void concurrentProjectionPublishersCannotSplitOrDoubleClaimTheSameBatch() throws Exception {
        for (int i = 0; i < 32; i++)
            journal.resourceEvent(
                    scope,
                    "concurrent-public-" + i,
                    "session.updated",
                    Map.of("session_id", scope.sessionId()));
        var outbox = journal.projections().outbox();
        var pool = Executors.newFixedThreadPool(8);
        var start = new CountDownLatch(1);
        try {
            var claims = new ArrayList<Future<ManagedProjectionOutbox.Claim>>();
            for (int i = 0; i < 8; i++)
                claims.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return outbox.claim(scope);
                                }));
            start.countDown();
            var winners = new ArrayList<ManagedProjectionOutbox.Claim>();
            for (var future : claims) {
                var claim = future.get(10, TimeUnit.SECONDS);
                if (claim != null) winners.add(claim);
            }
            assertEquals(1, winners.size());
            assertEquals(32, winners.get(0).commits().size());
            assertEquals(
                    32L,
                    jdbc.queryForObject(
                            "SELECT SUM(attempt_count) FROM ma.runtime_commit WHERE scope_key=?",
                            Long.class,
                            scope.messageGroup()));
            outbox.completed(winners.get(0));
            assertTrue(outbox.pending(32).isEmpty());
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void thirtyTwoPublicCommitsUseOneClaimUpdateAndOneConfirmationUpdate() {
        for (int i = 0; i < 32; i++)
            journal.resourceEvent(
                    scope,
                    "public-" + i,
                    "session.updated",
                    Map.of("session_id", scope.sessionId()));
        var counter = new QueryCounter(source);
        var facts =
                new ManagedProjectionFacts(
                        counter, mapper, new ManagedFactBundles(checkpoints.backend(), mapper));
        var outbox = new ManagedProjectionOutbox(counter, mapper, facts);
        var claim = outbox.claim(scope);
        assertEquals(32, claim.commits().size());
        assertEquals(32, outbox.message(claim).commits().size());
        outbox.completed(claim);
        assertEquals(
                2,
                counter.statements.stream()
                        .filter(sql -> sql.startsWith("UPDATE ma.runtime_commit SET"))
                        .count());
        assertTrue(outbox.pending(32).isEmpty());
    }

    @Test
    void commitBurstCoalescesWithoutIdleSqlPollingOrOneMessagePerCommit() throws Exception {
        var outbox = spy(journal.projections().outbox());
        var initialScan = new CountDownLatch(1);
        doAnswer(
                        call -> {
                            Object result = call.callRealMethod();
                            initialScan.countDown();
                            return result;
                        })
                .when(outbox)
                .pending(anyInt());
        var bus = mock(ManagedMessageBus.class);
        var sent = new CountDownLatch(1);
        var message = new AtomicReference<ManagedProjectionNotice>();
        doAnswer(
                        call -> {
                            ManagedMessageBus.Message envelope = call.getArgument(1);
                            message.set(
                                    mapper.readValue(
                                            envelope.body(), ManagedProjectionNotice.class));
                            sent.countDown();
                            return null;
                        })
                .when(bus)
                .publish(eq(ManagedMessageBus.Channel.SESSION_PROJECTIONS), any());
        try (var publisher = new ManagedProjectionPublisher(journal, bus, mapper, 1, 1000)) {
            ReflectionTestUtils.setField(publisher, "outbox", outbox);
            publisher.start();
            assertTrue(initialScan.await(3, TimeUnit.SECONDS));
            for (int i = 0; i < 5; i++)
                journal.resourceEvent(scope, "burst-" + i, "session.updated", Map.of("value", i));
            assertFalse(sent.await(100, TimeUnit.MILLISECONDS));
            verify(outbox, times(1)).pending(anyInt());
            assertTrue(sent.await(3, TimeUnit.SECONDS));
            assertEquals(5, message.get().commits().size());
            verify(bus, times(1)).publish(eq(ManagedMessageBus.Channel.SESSION_PROJECTIONS), any());
        }
    }

    @Test
    void aLiveClaimDoesNotCauseLaterVersionsToSpinInPendingScan() {
        journal.resourceEvent(
                scope, "one", "session.created", Map.of("session_id", scope.sessionId()));
        var outbox = journal.projections().outbox();
        var first = outbox.claim(scope);
        journal.resourceEvent(
                scope, "two", "session.updated", Map.of("session_id", scope.sessionId()));
        assertTrue(outbox.pending(32).isEmpty());
        assertNull(outbox.claim(scope));
        outbox.completed(first);
        assertEquals(List.of(scope), outbox.pending(32));
        var second = outbox.claim(scope);
        assertEquals(2, second.commits().get(0).manifest().version());
    }

    @Test
    void stalePublisherCannotConfirmAReplacementClaim() {
        journal.resourceEvent(
                scope, "one", "session.created", Map.of("session_id", scope.sessionId()));
        var outbox = journal.projections().outbox();
        var first = outbox.claim(scope);
        assertNotNull(first);
        assertNull(outbox.claim(scope));
        jdbc.update(
                "UPDATE ma.runtime_commit SET next_attempt_at=0 WHERE scope_key=?",
                scope.messageGroup());
        var replacement = outbox.claim(scope);
        assertNotNull(replacement);
        outbox.completed(first);
        assertEquals(
                "inflight",
                jdbc.queryForObject(
                        "SELECT publication_state FROM ma.runtime_commit WHERE scope_key=?",
                        String.class,
                        scope.messageGroup()));
        outbox.completed(replacement);
        assertTrue(outbox.pending(128).isEmpty());
    }

    @Test
    void concurrentBrainPublishersSendOneWakeupForTheSameCommittedPrefix() throws Exception {
        journal.resourceEvent(
                scope, "created", "session.created", Map.of("session_id", scope.sessionId()));
        try (var bus = spy(new InMemoryManagedMessageBus(System::currentTimeMillis, 8))) {
            var codec = new ManagedBatchCodec(mapper, checkpoints.backend());
            var pool = Executors.newFixedThreadPool(8);
            var start = new CountDownLatch(1);
            try {
                var work = new ArrayList<Future<?>>();
                for (int n = 0; n < 8; n++)
                    work.add(
                            pool.submit(
                                    () -> {
                                        try {
                                            start.await();
                                            new ManagedProjectionPublisher(journal, bus, mapper)
                                                    .scan();
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                            throw new RuntimeException(e);
                                        }
                                    }));
                start.countDown();
                for (var future : work) future.get(5, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
            verify(bus, times(1))
                    .publish(
                            eq(ManagedMessageBus.Channel.SESSION_PROJECTIONS),
                            any(ManagedMessageBus.Message.class));
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM ma.runtime_commit WHERE publication_state='sent'"
                                    + " AND scope_key=?",
                            Long.class,
                            scope.messageGroup()));
        }
    }

    @Test
    void completedPrimaryOutcomeReplaysWithoutProviderOrDiagnosticReads() throws Exception {
        commit(batch("entry", 0));
        String hash =
                ManagedFinalization.requestHash(
                        mapper, "fixture", "REASONING", List.of(), List.of(), null);
        var capture =
                new ManagedModelCapture("fixture", "REASONING", List.of(), List.of(), null, hash);
        capture.accept(
                ChatResponse.builder()
                        .id("reply")
                        .content(List.of(TextBlock.builder().text("saved").build()))
                        .build());
        var request =
                nativeEvent(
                        2,
                        "request/prepared",
                        Map.of("modelCallId", "call", "model", "fixture", "purpose", "REASONING"));
        var end =
                nativeEvent(
                        3,
                        "model/end",
                        Map.of(
                                "modelCallId",
                                "call",
                                "status",
                                "completed",
                                ManagedModelCapture.OUTCOME,
                                capture.freeze("completed")));
        commit(nativeBatch("completed", 1, List.of(request, end)));
        var other =
                spy(
                        ProjectionTestSupport.journal(
                                source,
                                mapper,
                                new ManagedCheckpointObjects(
                                        mapper, new FileRuntimeObjectStore("fixture", directory))));
        doThrow(new AssertionError("Recovery scanned the event stream"))
                .when(other)
                .recoveryManifests(any(), anyLong(), anyInt());
        var guard = mock(ManagedExecutionGuard.class);
        when(guard.token()).thenReturn("token");
        when(guard.remaining()).thenReturn(Duration.ofSeconds(30));
        var execution =
                new ManagedTurnExecution(
                        scope,
                        turn,
                        1,
                        guard,
                        admission,
                        other,
                        new ManagedRuntimeWriter(other, new SessionCommitTransactions(other)));
        var replay =
                new ManagedFinalization(execution, mapper)
                        .stream(
                                        "fixture",
                                        "REASONING",
                                        List.of(),
                                        List.of(),
                                        null,
                                        id ->
                                                Flux.error(
                                                        new AssertionError(
                                                                "Completed provider call was"
                                                                        + " repeated")))
                                .collectList()
                                .block(Duration.ofSeconds(2));
        assertEquals(1, replay.size());
        assertEquals("saved", ((TextBlock) replay.get(0).getContent().get(0)).getText());
        verify(other, never()).recoveryManifests(any(), anyLong(), anyInt());
        commit(
                AgentScopeCheckpoints.prepare(
                        checkpoints,
                        nativeBatch(
                                "applied",
                                3,
                                List.of(
                                        nativeEvent(
                                                4,
                                                "state/checkpoint",
                                                Map.of("stateJson", state))))));
        assertNull(journal.replayModel(scope, turn, hash, 4));
    }

    @Test
    void diagnosticOnlyFlushHasNoSqlRowsObjectsOrExecutionProgress() throws Exception {
        var guard = mock(ManagedExecutionGuard.class);
        when(guard.token()).thenReturn("token");
        when(guard.remaining()).thenReturn(Duration.ofSeconds(30));
        var log =
                new ManagedMqSessionLog(
                        scope,
                        turn,
                        1,
                        guard,
                        journal,
                        new ManagedRuntimeWriter(journal, new SessionCommitTransactions(journal)));
        var writer = log.acquire("writer", Duration.ofSeconds(30));
        var chunks = new ArrayList<SessionEvent>();
        for (int i = 1; i <= 1000; i++)
            chunks.add(
                    nativeEvent(
                            i,
                            "model/chunk",
                            Map.of(
                                    "modelCallId",
                                    "discard",
                                    "chunk",
                                    Map.of("content", List.of()))));
        assertEquals(0, log.commit(writer, "chunks", 0, chunks).seq());
        assertEquals(0, journal.head(scope).sequence());
        assertEquals(
                0, jdbc.queryForObject("SELECT COUNT(*) FROM ma.runtime_commit", Integer.class));
        assertEquals(
                0, jdbc.queryForObject("SELECT COUNT(*) FROM ma.runtime_commit", Integer.class));
        var checkpoint = nativeEvent(1001, "state/checkpoint", Map.of("stateJson", state));
        assertEquals(1, log.commit(writer, "checkpoint", 0, List.of(checkpoint)).seq());
        assertEquals(state, SessionProjection.read(log).stateJson());
        assertEquals(1, journal.recoveryEvents(scope, 0, 1000).size());
    }

    private static final class QueryCounter extends DelegatingDataSource {
        final List<String> statements = new CopyOnWriteArrayList<>();

        QueryCounter(DataSource source) {
            super(source);
        }

        @Override
        public Connection getConnection() throws SQLException {
            var connection = super.getConnection();
            return (Connection)
                    Proxy.newProxyInstance(
                            Connection.class.getClassLoader(),
                            new Class<?>[] {Connection.class},
                            (proxy, method, args) -> {
                                if (method.getName().equals("prepareStatement"))
                                    statements.add((String) args[0]);
                                try {
                                    var result = method.invoke(connection, args);
                                    if (method.getName().equals("createStatement")) {
                                        var statement = (Statement) result;
                                        return Proxy.newProxyInstance(
                                                Statement.class.getClassLoader(),
                                                new Class<?>[] {Statement.class},
                                                (ignored, operation, parameters) -> {
                                                    if (operation.getName().startsWith("execute")
                                                            && parameters != null
                                                            && parameters.length > 0
                                                            && parameters[0] instanceof String sql)
                                                        statements.add(sql);
                                                    try {
                                                        return operation.invoke(
                                                                statement, parameters);
                                                    } catch (InvocationTargetException failure) {
                                                        throw failure.getCause();
                                                    }
                                                });
                                    }
                                    return result;
                                } catch (InvocationTargetException failure) {
                                    throw failure.getCause();
                                }
                            });
        }

        long selecting(String table) {
            return statements.stream()
                    .filter(
                            sql ->
                                    sql.startsWith("SELECT ")
                                            && sql.contains(" FROM " + table + " "))
                    .count();
        }
    }

    @Test
    void checkpointCommitUsesTwoIdentityReadsAndOneFragmentSnapshot() throws Exception {
        var counter = new QueryCounter(source);
        var measured = ProjectionTestSupport.journal(counter, mapper, checkpoints);
        try (var owner =
                new ManagedProcessOwnership(
                        counter,
                        "brain",
                        Duration.ofSeconds(60),
                        Duration.ofMinutes(10),
                        Duration.ofHours(1))) {
            measured.processOwnership(owner);
            jdbc.update(
                    "UPDATE ma.turn SET owner_incarnation=?,execution_deadline=? WHERE scope_key=?",
                    owner.incarnation(),
                    System.currentTimeMillis() + 60000,
                    scope.messageGroup());
            var boundary = batch("counted", 0);
            counter.statements.clear();
            AgentScopePreparation.commit(
                    measured, new SessionCommitTransactions(measured), boundary);
            // Lock Session, read Turn once; still check the process lease and a fresh DB clock.
            assertEquals(1, counter.selecting("ma.session"));
            assertEquals(1, counter.selecting("ma.turn"));
            assertEquals(1, counter.selecting("ma_worker.lease"));
            assertEquals(
                    2,
                    counter.statements.stream()
                            .filter(
                                    sql ->
                                            sql.startsWith("SELECT ")
                                                    && (sql.contains("clock_timestamp()")
                                                            || sql.equals(
                                                                    "SELECT CURRENT_TIMESTAMP")))
                            .count());
            assertEquals(
                    0,
                    counter.statements.stream()
                            .filter(
                                    sql ->
                                            sql.startsWith("UPDATE ma.projection_source_head")
                                                    || sql.startsWith(
                                                            "INSERT INTO ma.projection_commit"))
                            .count());
            counter.statements.clear();
            new ManagedLocalJournal(scope, turn, measured);
            assertEquals(1, counter.selecting("ma.session"));
            assertEquals(0, counter.selecting("ma.turn"));
        }
    }

    @Test
    void privateBoundariesKeepRecoveryButDoNotAdvanceProjectionOrPublishWakeups() throws Exception {
        var first = batch("private-one", 0);
        var receipt = commit(first);
        assertEquals(receipt, commit(first));
        commit(batch("private-two", 1));
        assertEquals(2, journal.head(scope).sequence());
        assertEquals(
                state,
                mapper.readTree(
                                checkpoints
                                        .read(journal.head(scope).checkpoint())
                                        .nativePayloadJson())
                        .path("stateJson")
                        .asText());
        assertEquals(2, journal.recoveryEvents(scope, 0, 100).size());
        assertEquals(0, journal.projections().acceptedVersion(scope));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.runtime_commit WHERE public_commit_version IS NOT"
                                + " NULL",
                        Integer.class));
        assertEquals(
                2, jdbc.queryForObject("SELECT COUNT(*) FROM ma.runtime_commit", Integer.class));
        assertTrue(journal.projections().outbox().pending(64).isEmpty());

        journal.resourceEvent(
                scope, "visible", "session.created", Map.of("session_id", scope.sessionId()));
        commit(batch("private-three", 2));
        assertEquals(1, journal.projections().acceptedVersion(scope));
        assertEquals(
                1L,
                jdbc.queryForObject(
                        "SELECT projection_version FROM ma.runtime_commit WHERE"
                                + " commit_id='private-three'",
                        Long.class));
        assertEquals(1, journal.projections().project(scope, 32));
        assertEquals(1, journal.publicHead(scope));
    }

    @Test
    void fragmentStartupReadsOneSnapshotButCommitRechecksChangedAuthority() throws Exception {
        commit(batch("snapshot", 0));
        var observed = spy(journal);
        var local = new ManagedLocalJournal(scope, turn, observed);
        assertEquals(journal.head(scope), local.head());
        verify(observed, times(1)).executionSnapshot(scope, turn);
        verify(observed, never()).head(scope);
        verify(observed, never()).modelAnchor(scope, turn);
        verify(observed, never()).executionPosition(scope, turn);
        verify(observed, never()).checkpointContext(scope, turn);
        // The fragment snapshot is recovery data, never authority for a later write.
        jdbc.update(
                "UPDATE ma.turn SET execution_token='new-owner' WHERE scope_key=?",
                scope.messageGroup());
        var next = batch("stale", 1);
        assertEquals(
                "stale_execution",
                assertThrows(ManagedStateException.class, () -> commit(next)).code());
        assertEquals(1, journal.head(scope).sequence());
    }

    @Test
    void commitReusesLockedTurnButStillRejectsExpiredOwnerAndDeadline() throws Exception {
        try (var owner =
                new ManagedProcessOwnership(
                        source,
                        "brain",
                        Duration.ofSeconds(60),
                        Duration.ofMinutes(10),
                        Duration.ofHours(1))) {
            journal.processOwnership(owner);
            jdbc.update(
                    "UPDATE ma.turn SET owner_incarnation=?,execution_deadline=? WHERE scope_key=?",
                    owner.incarnation(),
                    System.currentTimeMillis() + 60000,
                    scope.messageGroup());
            commit(batch("owned", 0));
            jdbc.update(
                    "UPDATE ma.turn SET execution_deadline=0 WHERE scope_key=?",
                    scope.messageGroup());
            var expired = batch("expired", 1);
            assertEquals(
                    "stale_execution",
                    assertThrows(ManagedStateException.class, () -> commit(expired)).code());
            jdbc.update(
                    "UPDATE ma.turn SET owner_incarnation='other',execution_deadline=? WHERE"
                            + " scope_key=?",
                    System.currentTimeMillis() + 60000,
                    scope.messageGroup());
            assertEquals(
                    "stale_execution",
                    assertThrows(ManagedStateException.class, () -> commit(expired)).code());
            jdbc.update(
                    "UPDATE ma.turn SET owner_incarnation=? WHERE scope_key=?",
                    owner.incarnation(),
                    scope.messageGroup());
            jdbc.update(
                    "UPDATE ma_worker.lease SET expires_at=0 WHERE incarnation=?",
                    owner.incarnation());
            assertEquals(
                    "worker_lease_expired",
                    assertThrows(ManagedStateException.class, () -> commit(expired)).code());
            assertEquals(1, journal.head(scope).sequence());
        }
    }

    @Test
    void previewFramesNeverCreateProjectionFactsOrAdvanceAccepted() {
        for (String type : List.of("item.delta", "event_start", "event_delta"))
            journal.projections().capture(scope, "ephemeral-" + type, type, "{}", 1);
        assertEquals(0, journal.projections().progress(scope).acceptedThrough());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.runtime_commit WHERE public_commit_version IS NOT"
                                + " NULL",
                        Integer.class));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.projection_tool_identity", Integer.class));
    }

    @Test
    void nextTurnRestoresCheckpointWithoutAnyCompletedTurnSegment() throws Exception {
        var context = AgentState.fromJsonString(state);
        context.contextMutable()
                .add(
                        UserMessage.builder()
                                .id("history-message")
                                .textContent("keep this context")
                                .build());
        commit(
                AgentScopeCheckpoints.prepare(
                        checkpoints,
                        nativeBatch(
                                "history",
                                0,
                                List.of(
                                        nativeEvent(
                                                1,
                                                "run/start",
                                                Map.of("executionRunId", "old-run")),
                                        nativeEvent(2, "run/end", Map.of("status", "completed")),
                                        nativeEvent(
                                                3,
                                                "state/checkpoint",
                                                Map.of("stateJson", context.toJson()))))));
        // Emulate completed admission so the next independent Turn receives a new execution epoch.
        jdbc.update(
                "UPDATE ma.turn SET status='succeeded' WHERE scope_key=? AND turn_id=?",
                scope.messageGroup(),
                turn);
        jdbc.update(
                "UPDATE ma.session SET current_turn_id=NULL,settled_sequence=published_sequence"
                        + " WHERE scope_key=?",
                scope.messageGroup());
        var next = admission.acceptTurn(scope, "actor", "next", "{}").id();
        long publication = admission.reservePublication(scope, next).publicationSequence();
        admission.confirmPublication(scope, next, "next-broker");
        long epoch = admission.activate(scope, next, publication, "next-token");
        String encoded =
                jdbc.queryForObject(
                        "SELECT recovery_ref FROM ma.runtime_commit WHERE scope_key=? AND"
                                + " first_sequence=1",
                        String.class,
                        scope.messageGroup());
        var ref = mapper.readValue(encoded, RuntimeObjectStore.Reference.class);
        Files.delete(directory.resolve(ref.scope()).resolve(ref.kind()).resolve(ref.key()));
        var other =
                ProjectionTestSupport.journal(
                        source,
                        mapper,
                        new ManagedCheckpointObjects(
                                mapper, new FileRuntimeObjectStore("fixture", directory)));
        var guard = mock(ManagedExecutionGuard.class);
        when(guard.token()).thenReturn("next-token");
        when(guard.remaining()).thenReturn(Duration.ofSeconds(30));
        var execution =
                new ManagedTurnExecution(
                        scope,
                        next,
                        epoch,
                        guard,
                        admission,
                        other,
                        new ManagedRuntimeWriter(other, new SessionCommitTransactions(other)));
        assertEquals(
                "keep this context",
                SessionProjection.read(execution.log())
                        .restore()
                        .getContext()
                        .get(0)
                        .getTextContent());
        assertEquals(3, SessionProjection.read(execution.log()).asOfSeq());
    }

    private SessionEvent nativeEvent(long sequence, String type, Map<String, Object> data)
            throws Exception {
        if ("request/prepared".equals(type))
            requests.put(String.valueOf(data.get("modelCallId")), data);
        if ("model/end".equals(type) && !data.containsKey(ManagedModelCapture.OUTCOME)) {
            var value = new LinkedHashMap<String, Object>(data);
            value.put(
                    ManagedModelCapture.OUTCOME,
                    Map.of(
                            "request",
                            requests.getOrDefault(
                                    String.valueOf(data.get("modelCallId")), Map.of()),
                            "responses",
                            List.of(),
                            "replayHash",
                            ManagedScope.fingerprint("fixture")));
            data = value;
        }
        return new SessionEvent(
                1,
                "event-" + sequence,
                sequence,
                System.currentTimeMillis(),
                type,
                "run",
                turn,
                true,
                mapper.writeValueAsString(data));
    }

    private ManagedRuntimeBatch nativeBatch(String id, long expected, List<SessionEvent> events) {
        return new ManagedRuntimeBatch(
                scope, turn, 1, "token", "writer", id, expected, events, List.of(), List.of(),
                null);
    }
}
