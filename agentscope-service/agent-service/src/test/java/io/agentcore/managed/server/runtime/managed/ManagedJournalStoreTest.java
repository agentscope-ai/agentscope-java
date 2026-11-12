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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.managed.ManagedInputInbox.Type;
import io.agentcore.managed.server.runtime.managed.ManagedRuntimeBatch.InputReceipt;
import io.agentcore.managed.server.runtime.managed.ManagedRuntimeBatch.ToolIntent;
import io.agentcore.managed.server.runtime.managed.ManagedSessionProtocol.Command;
import io.agentscope.core.session.SessionEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ManagedJournalStoreTest {
    @TempDir private Path managedCheckpointRoot;

    private final ManagedScope scope = new ManagedScope("t", "n", "s-" + UUID.randomUUID());
    private final ObjectMapper mapper = new ObjectMapper();
    private ManagedAdmissionStore admission;
    private ManagedJournalStore journal;
    private JdbcTemplate jdbc;
    private String turn;

    @BeforeEach
    void setup() {
        var source = database();
        jdbc = new JdbcTemplate(source);
        ManagedAdmissionStore.initialize(source);
        admission = ProjectionTestSupport.admission(source, mapper);
        journal =
                ProjectionTestSupport.journal(
                        source,
                        mapper,
                        new ManagedCheckpointObjects(mapper, managedCheckpointRoot));
        admission.createSession(scope, "{}");
        turn = admission.acceptTurn(scope, "a", "t", "{}").id();
        admission.reservePublication(scope, turn);
        admission.confirmPublication(scope, turn, "broker");
        admission.activate(scope, turn, 1, "token");
    }

    @Test
    void checkpointIntentAndInputReceiptCommitTogetherAndStalePrefixCannotReplay() {
        var input = admission.acceptInput(scope, turn, "a", "i", Type.INJECT, null, "{}");
        var batch =
                batch(
                        "batch",
                        0,
                        List.of(checkpoint(1)),
                        List.of(new ToolIntent("call", "exec", "{\"command\":\"hello\"}")),
                        List.of(new InputReceipt(input.id(), "applied", null)),
                        null);
        var receipt =
                AgentScopePreparation.commit(
                        journal,
                        new SessionCommitTransactions(journal),
                        AgentScopeCheckpoints.prepare(journal.objects(), batch));
        assertEquals("committed", receipt.outcome());
        assertEquals(
                receipt,
                AgentScopePreparation.commit(
                        journal,
                        new SessionCommitTransactions(journal),
                        AgentScopeCheckpoints.prepare(journal.objects(), batch)));
        assertEquals(1, journal.recoveryEvents(scope, 0, 10).size());
        assertEquals("applied", admission.inputsAfter(scope, turn, 0, 10).get(0).status());
        assertEquals(1, ProjectionTestSupport.project(journal).publicEvents(scope, 0, 10).size());
        String task = "tool_" + ManagedScope.fingerprint(scope.messageGroup(), turn, "call");
        var tool = journal.tool(scope, task).orElseThrow();
        assertEquals(task, tool.executionId());
        assertEquals(1, tool.checkpointSequence());
        assertFalse(
                journal.recoveryManifests(scope, 0, 10).get(0).payloadJson().contains("stateJson"));
        assertFalse(
                jdbc.queryForObject(
                                "SELECT checkpoint FROM ma.session WHERE scope_key=?",
                                String.class,
                                scope.messageGroup())
                        .contains("stateJson"));
        assertTrue(journal.head(scope).checkpoint().sizeBytes() > 0);
        assertEquals("ready", tool.status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"inject", "steer"})
    void inputApplicationPublishesOneProtocolNotificationAfterAtomicCommitAndReplay(String intent)
            throws Exception {
        var protocol = admission.protocol();
        var command =
                new Command(
                        "user.message",
                        List.of(Map.of("type", "text", "text", "extra context")),
                        intent,
                        turn,
                        true);
        var accepted = protocol.accept(scope, "actor", intent, List.of(command), () -> {});
        String input = admission.inputsAfter(scope, turn, 0, 10).get(0).id();
        ProjectionTestSupport.project(journal);
        long before = protocol.head(scope);
        var events =
                List.of(
                        event(
                                1,
                                "inbox/accepted",
                                mapper.writeValueAsString(Map.of("id", input, "kind", intent))),
                        event(
                                2,
                                "inbox/applied",
                                mapper.writeValueAsString(Map.of("commandIds", List.of(input)))),
                        checkpoint(3));
        var applied = new InputReceipt(input, "applied", null);
        var invalid =
                batch(
                        "invalid-application",
                        0,
                        List.of(events.get(0), events.get(1), checkpoint(3)),
                        List.of(),
                        List.of(applied, new InputReceipt("missing", "applied", null)),
                        null);
        assertThrows(
                EmptyResultDataAccessException.class,
                () ->
                        AgentScopePreparation.commit(
                                journal,
                                new SessionCommitTransactions(journal),
                                AgentScopeCheckpoints.prepare(journal.objects(), invalid)));
        assertEquals(before, protocol.head(scope));
        assertEquals("accepted", admission.inputsAfter(scope, turn, 0, 10).get(0).status());
        assertTrue(journal.recoveryEvents(scope, 0, 10).isEmpty());
        assertNull(protocol.link(scope, turn, input).get("applied_at"));

        AgentScopePreparation.commit(
                journal,
                new SessionCommitTransactions(journal),
                AgentScopeCheckpoints.prepare(
                        journal.objects(),
                        batch("application", 0, events, List.of(), List.of(applied), null)));
        assertEquals("applied", admission.inputsAfter(scope, turn, 0, 10).get(0).status());
        ProjectionTestSupport.project(journal);
        assertNotNull(protocol.link(scope, turn, input).get("applied_at"));
        assertEquals(
                List.of("user.message", "managed_agents.input.applied"),
                protocol.changes(scope, before, 100).stream()
                        .map(change -> change.event().get("type"))
                        .toList());
        long committed = protocol.head(scope);
        AgentScopePreparation.commit(
                journal,
                new SessionCommitTransactions(journal),
                AgentScopeCheckpoints.prepare(
                        journal.objects(),
                        batch(
                                "replayed-application",
                                3,
                                List.of(
                                        event(
                                                4,
                                                "inbox/applied",
                                                mapper.writeValueAsString(
                                                        Map.of("commandIds", List.of(input)))),
                                        checkpoint(5)),
                                List.of(),
                                List.of(applied),
                                null)));
        assertEquals(committed, protocol.head(scope));
        assertEquals(5, journal.recoveryEvents(scope, 0, 10).size());
        var history =
                protocol.history(
                        scope,
                        "actor",
                        Map.of(
                                "types[]",
                                List.of(
                                        "managed_agents.input.accepted",
                                        "managed_agents.input.applied")),
                        true);
        assertEquals(2, ((List<?>) history.get("data")).size());
        assertEquals(
                accepted.receipt(),
                protocol.accept(scope, "actor", intent, List.of(command), () -> {}).receipt());
    }

    @Test
    void missingObjectStopsBeforeAnyJournalEffectsAndInlineStateIsRejected() throws Exception {
        var raw =
                batch(
                        "missing-object",
                        0,
                        List.of(checkpoint(1)),
                        List.of(new ToolIntent("call", "exec", "{}")),
                        List.of(),
                        null);
        assertThrows(
                ManagedStateException.class,
                () ->
                        AgentScopePreparation.commit(
                                journal, new SessionCommitTransactions(journal), raw));
        var wire = AgentScopeCheckpoints.prepare(journal.objects(), raw);
        var ref = journal.objects().reference(scope, wire.events().get(0));
        Files.delete(managedCheckpointRoot.resolve(ref.storageRef()));
        assertThrows(
                ManagedTransportException.class,
                () ->
                        AgentScopePreparation.commit(
                                journal, new SessionCommitTransactions(journal), wire));
        assertEquals(0, journal.head(scope).sequence());
        assertTrue(ProjectionTestSupport.project(journal).publicEvents(scope, 0, 10).isEmpty());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.tool_task WHERE scope_key=?",
                        Long.class,
                        scope.messageGroup()));
    }

    @Test
    void terminalCommitSettlesOnceAndRejectsTheRetiredExecution() {
        admission.closeInputs(scope, turn, 1);
        var done = batch("done", 0, List.of(checkpoint(1)), List.of(), List.of(), "succeeded");
        var receipt =
                AgentScopePreparation.commit(
                        journal,
                        new SessionCommitTransactions(journal),
                        AgentScopeCheckpoints.prepare(journal.objects(), done));
        assertEquals("committed", receipt.outcome());
        assertEquals(
                receipt,
                AgentScopePreparation.commit(
                        journal,
                        new SessionCommitTransactions(journal),
                        AgentScopeCheckpoints.prepare(journal.objects(), done)));
        assertEquals("succeeded", admission.turn(scope, turn).status());
        assertEquals(1, admission.session(scope).settledSequence());
    }

    @Test
    void failingInputOrToolBoundaryRollsBackNativeEventsAndPublicProjection() {
        var batch =
                batch(
                        "bad",
                        0,
                        List.of(checkpoint(1)),
                        List.of(new ToolIntent("call", "exec", "{}")),
                        List.of(new InputReceipt("missing", "applied", null)),
                        null);
        assertThrows(
                RuntimeException.class,
                () ->
                        AgentScopePreparation.commit(
                                journal,
                                new SessionCommitTransactions(journal),
                                AgentScopeCheckpoints.prepare(journal.objects(), batch)));
        assertEquals(0, journal.head(scope).sequence());
        assertTrue(journal.recoveryEvents(scope, 0, 10).isEmpty());
        assertTrue(ProjectionTestSupport.project(journal).publicEvents(scope, 0, 10).isEmpty());
        var snapshot = ProjectionTestSupport.project(journal).resources().snapshot(scope);
        assertTrue(
                ProjectionTestSupport.project(journal)
                        .resources()
                        .page(scope, snapshot.id(), "inputs", null, 10)
                        .data()
                        .isEmpty());
        String task = "tool_" + ManagedScope.fingerprint(scope.messageGroup(), turn, "call");
        assertTrue(journal.tool(scope, task).isEmpty());
    }

    @Test
    void publicToolProgressExcludesMaintenanceArgumentsResultsAndWorkerIdentity() {
        AgentScopePreparation.commit(
                journal,
                new SessionCommitTransactions(journal),
                AgentScopeCheckpoints.prepare(
                        journal.objects(),
                        batch(
                                "tool-progress",
                                0,
                                List.of(
                                        event(
                                                1,
                                                "tool/requested",
                                                "{\"toolCallId\":\"native-call\",\"name\":\"run_shell\",\"arguments\":{\"command\":\"hello\"}}"),
                                        checkpoint(2)),
                                List.of(
                                        new ToolIntent(
                                                "rpc",
                                                "framework_fs.execute",
                                                "{\"native_tool_call_id\":\"native-call\",\"private\":\"credential-hidden\"}"),
                                        new ToolIntent(
                                                "maintenance",
                                                "framework_fs.upload",
                                                "{\"private\":\"maintenance-hidden\"}")),
                                List.of(),
                                null)));
        String task = "tool_" + ManagedScope.fingerprint(scope.messageGroup(), turn, "rpc");
        journal.claimTool(scope, task, "worker-token-hidden");
        journal.markToolUnknown(scope, task, "worker-token-hidden");
        var page = ProjectionTestSupport.project(journal).publicTools(scope, turn, null, 10);
        assertEquals(1, page.data().size());
        assertEquals("run_shell", page.data().get(0).get("tool_name"));
        assertEquals("unknown", page.data().get(0).get("status"));
        assertFalse(page.data().toString().contains("hidden"));
        assertFalse(page.data().toString().contains("framework_fs"));
    }

    @Test
    void batchKeepsPerCallPublicStatusAndStablePaginationWithoutExtraTasks() throws Exception {
        String arguments =
                mapper.writeValueAsString(
                        new ManagedMcpBatch.Manifest(
                                List.of(
                                        new ManagedMcpBatch.Call(
                                                "native-a",
                                                "mcp__first",
                                                Map.of("private", "argument-hidden")),
                                        new ManagedMcpBatch.Call(
                                                "native-b",
                                                "mcp__second",
                                                Map.of("private", "argument-hidden")))));
        AgentScopePreparation.commit(
                journal,
                new SessionCommitTransactions(journal),
                AgentScopeCheckpoints.prepare(
                        journal.objects(),
                        batch(
                                "batch-public",
                                0,
                                List.of(
                                        event(
                                                1,
                                                "tool/requested",
                                                "{\"toolCallId\":\"native-a\",\"name\":\"mcp__first\"}"),
                                        event(
                                                2,
                                                "tool/requested",
                                                "{\"toolCallId\":\"native-b\",\"name\":\"mcp__second\"}"),
                                        checkpoint(3)),
                                List.of(
                                        new ToolIntent(
                                                "batch-rpc", ManagedMcpBatch.OPERATION, arguments)),
                                List.of(),
                                null)));
        String parent = "tool_" + ManagedScope.fingerprint(scope.messageGroup(), turn, "batch-rpc");
        assertEquals(parent, journal.mcpBatchForCall(scope, turn, "native-b").orElseThrow().id());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.tool_task WHERE scope_key=?",
                        Integer.class,
                        scope.messageGroup()));
        var first = ProjectionTestSupport.project(journal).publicTools(scope, turn, null, 1);
        var second =
                ProjectionTestSupport.project(journal).publicTools(scope, turn, first.next(), 1);
        assertTrue(first.hasMore());
        assertFalse(second.hasMore());
        assertEquals(1, first.data().size());
        assertEquals(1, second.data().size());
        assertFalse(first.data().get(0).get("task_id").equals(second.data().get(0).get("task_id")));
        journal.claimTool(scope, parent, "worker-hidden");
        String result =
                mapper.writeValueAsString(
                        new ManagedMcpBatch.Result(
                                List.of(
                                        new ManagedMcpBatch.CallResult(
                                                "native-a", "failed", "result-hidden"),
                                        new ManagedMcpBatch.CallResult(
                                                "native-b", "succeeded", "result-hidden"))));
        ManagedPushTestSupport.commitToolResult(
                journal, new ManagedToolResult(scope, parent, parent, "succeeded", result));
        var complete =
                ProjectionTestSupport.project(journal).publicTools(scope, turn, null, 10).data();
        assertEquals(2, complete.size());
        assertEquals(
                "failed",
                complete.stream()
                        .filter(value -> "native-a".equals(value.get("tool_call_id")))
                        .findFirst()
                        .orElseThrow()
                        .get("status"));
        assertEquals(
                "succeeded",
                complete.stream()
                        .filter(value -> "native-b".equals(value.get("tool_call_id")))
                        .findFirst()
                        .orElseThrow()
                        .get("status"));
        assertTrue(
                complete.stream()
                        .allMatch(value -> "committed".equals(value.get("result_status"))));
        assertFalse(complete.toString().contains("hidden"));
    }

    @Test
    void oldEpochCannotWriteAndItsCommittedPrefixRemainsRecoverable() {
        var old = batch("old", 0, List.of(checkpoint(1)), List.of(), List.of(), null);
        var committed =
                AgentScopePreparation.commit(
                        journal,
                        new SessionCommitTransactions(journal),
                        AgentScopeCheckpoints.prepare(journal.objects(), old));
        admission.activate(scope, turn, 1, "successor");
        assertEquals("committed", committed.outcome());
        assertEquals(
                committed,
                AgentScopePreparation.commit(
                        journal,
                        new SessionCommitTransactions(journal),
                        AgentScopeCheckpoints.prepare(journal.objects(), old)));
        var stale =
                batch(
                        "stale",
                        1,
                        List.of(
                                event(
                                        2,
                                        "message/assistant",
                                        "{\"message\":{\"role\":\"assistant\",\"content\":[]}}")),
                        List.of(),
                        List.of(),
                        null);
        assertEquals(
                "stale_execution",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        AgentScopePreparation.commit(
                                                journal,
                                                new SessionCommitTransactions(journal),
                                                AgentScopeCheckpoints.prepare(
                                                        journal.objects(), stale)))
                        .code());
        assertEquals(1, journal.head(scope).sequence());
        assertEquals(1, journal.recoveryEvents(scope, 0, 10).size());
        assertEquals(1, journal.head(scope).checkpointSequence());
        var successor =
                new ManagedRuntimeBatch(
                        scope,
                        turn,
                        2,
                        "successor",
                        "next-writer",
                        "next",
                        1,
                        List.of(checkpoint(2)),
                        List.of(),
                        List.of(),
                        null);
        AgentScopePreparation.commit(
                journal,
                new SessionCommitTransactions(journal),
                AgentScopeCheckpoints.prepare(journal.objects(), successor));
        assertEquals(2, journal.head(scope).sequence());
    }

    @Test
    void terminalWaitsForAcceptedInputSettlementAndUnlocksOnlyItsImmediateSuccessor() {
        var input = admission.acceptInput(scope, turn, "a", "i", Type.STEER, null, "{}");
        var second = admission.acceptTurn(scope, "a", "t2", "{}");
        admission.reservePublication(scope, second.id());
        admission.confirmPublication(scope, second.id(), "broker2");
        admission.closeInputs(scope, turn, 1);
        var terminal =
                batch("terminal", 0, List.of(checkpoint(1)), List.of(), List.of(), "succeeded");
        assertEquals(
                "inputs_unsettled",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        AgentScopePreparation.commit(
                                                journal,
                                                new SessionCommitTransactions(journal),
                                                AgentScopeCheckpoints.prepare(
                                                        journal.objects(), terminal)))
                        .code());
        assertEquals(0, journal.head(scope).sequence());
        var settled =
                batch(
                        "terminal",
                        0,
                        List.of(checkpoint(1)),
                        List.of(),
                        List.of(new InputReceipt(input.id(), "applied", null)),
                        "succeeded");
        var receipt =
                AgentScopePreparation.commit(
                        journal,
                        new SessionCommitTransactions(journal),
                        AgentScopeCheckpoints.prepare(journal.objects(), settled));
        assertEquals("succeeded", admission.turn(scope, turn).status());
        assertEquals(1, admission.session(scope).settledSequence());
        assertEquals(2, admission.activate(scope, second.id(), 2, "second-token"));
        assertEquals("committed", receipt.outcome());
        assertEquals(
                receipt,
                AgentScopePreparation.commit(
                        journal,
                        new SessionCommitTransactions(journal),
                        AgentScopeCheckpoints.prepare(journal.objects(), settled)));
        assertEquals(1, journal.head(scope).sequence());
    }

    @Test
    void stalePrefixCannotBeOverwrittenWithDifferentContent() {
        AgentScopePreparation.commit(
                journal,
                new SessionCommitTransactions(journal),
                AgentScopeCheckpoints.prepare(
                        journal.objects(),
                        batch("same", 0, List.of(checkpoint(1)), List.of(), List.of(), null)));
        assertEquals(
                "batch_content_conflict",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        AgentScopePreparation.commit(
                                                journal,
                                                new SessionCommitTransactions(journal),
                                                AgentScopeCheckpoints.prepare(
                                                        journal.objects(),
                                                        batch(
                                                                "same",
                                                                0,
                                                                List.of(
                                                                        event(
                                                                                1,
                                                                                "run/start",
                                                                                "{}")),
                                                                List.of(),
                                                                List.of(),
                                                                null))))
                        .code());
    }

    @Test
    void managedBackendDoesNotExposePermanentWriterSealing() {
        var reader = new ManagedMqSessionLog(scope, null, 0, null, journal, null);
        assertThrows(UnsupportedOperationException.class, () -> reader.sealWriter("writer"));
        assertEquals(0, journal.head(scope).sequence());
    }

    @Test
    void publicProjectionDoesNotExposePromptsCheckpointOrThinkingBlocks() {
        var privatePrompt =
                event(
                        1,
                        "request/prepared",
                        "{\"purpose\":\"REASONING\",\"modelCallId\":\"m\",\"messages\":[\"SECRET"
                                + " PROMPT\"],\"options\":{\"key\":\"SECRET KEY\"}}");
        var output =
                event(
                        2,
                        "message/assistant",
                        "{\"message\":{\"id\":\"m\",\"role\":\"assistant\",\"metadata\":{\"secret\":\"SECRET"
                            + " ID\"},\"content\":[{\"type\":\"thinking\",\"thinking\":\"SECRET"
                            + " THOUGHT\"},{\"type\":\"text\",\"text\":\"hello\"}]}}");
        AgentScopePreparation.commit(
                journal,
                new SessionCommitTransactions(journal),
                AgentScopeCheckpoints.prepare(
                        journal.objects(),
                        batch(
                                "public",
                                0,
                                List.of(privatePrompt, output, checkpoint(3)),
                                List.of(),
                                List.of(),
                                null)));
        String publicData =
                ProjectionTestSupport.project(journal).publicEvents(scope, 0, 100).toString();
        assertTrue(publicData.contains("hello"));
        assertFalse(publicData.contains("SECRET"));
        assertFalse(publicData.contains("stateJson"));
        assertEquals(3, journal.recoveryEvents(scope, 0, 100).size());
        assertEquals(2, ProjectionTestSupport.project(journal).publicEvents(scope, 0, 100).size());
        assertEquals(1, ProjectionTestSupport.project(journal).publicEvents(scope, 1, 100).size());
    }

    @Test
    void replyAdmissionReferencesAnOpenActionAndSettlementClosesItAtomically() {
        var request =
                event(
                        2,
                        "interaction/requested",
                        "{\"requestId\":\"action\",\"kind\":\"external_execution\",\"call\":{\"type\":\"tool_use\",\"id\":\"action\",\"name\":\"exec\",\"input\":{}}}");
        AgentScopePreparation.commit(
                journal,
                new SessionCommitTransactions(journal),
                AgentScopeCheckpoints.prepare(
                        journal.objects(),
                        batch(
                                "request",
                                0,
                                List.of(checkpoint(1), request),
                                List.of(),
                                List.of(),
                                null)));
        assertEquals("open", journal.action(scope, turn, "action").orElseThrow().status());
        var reply = admission.acceptInput(scope, turn, "user", "reply", Type.REPLY, "action", "{}");
        assertEquals(
                "action_reply_pending",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        admission.acceptInput(
                                                scope,
                                                turn,
                                                "other",
                                                "reply",
                                                Type.REPLY,
                                                "action",
                                                "{}"))
                        .code());
        AgentScopePreparation.commit(
                journal,
                new SessionCommitTransactions(journal),
                AgentScopeCheckpoints.prepare(
                        journal.objects(),
                        batch(
                                "reply",
                                2,
                                List.of(checkpoint(3)),
                                List.of(),
                                List.of(new InputReceipt(reply.id(), "applied", null)),
                                null)));
        assertEquals("answered", journal.action(scope, turn, "action").orElseThrow().status());
        assertEquals(
                reply.id(),
                admission
                        .acceptInput(scope, turn, "user", "reply", Type.REPLY, "action", "{}")
                        .id());
        assertEquals(
                "action_not_pending",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        admission.acceptInput(
                                                scope,
                                                turn,
                                                "user",
                                                "later",
                                                Type.REPLY,
                                                "action",
                                                "{}"))
                        .code());
    }

    @Test
    void concurrentResultRedeliverySettlesOneTaskAndOnePublicCompletion() throws Exception {
        AgentScopePreparation.commit(
                journal,
                new SessionCommitTransactions(journal),
                AgentScopeCheckpoints.prepare(
                        journal.objects(),
                        batch(
                                "intent",
                                0,
                                List.of(checkpoint(1)),
                                List.of(new ToolIntent("call", "sandbox.exec", "{}")),
                                List.of(),
                                null)));
        String task = "tool_" + ManagedScope.fingerprint(scope.messageGroup(), turn, "call");
        journal.claimTool(scope, task, "hands");
        var result = new ManagedToolResult(scope, task, task, "succeeded", "{}");
        journal.stageToolResult(result);
        var workers = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            var commits =
                    List.of(0, 1).stream()
                            .map(
                                    index ->
                                            workers.submit(
                                                    () -> {
                                                        ready.countDown();
                                                        assertTrue(
                                                                start.await(5, TimeUnit.SECONDS));
                                                        return journal.commitToolResult(result);
                                                    }))
                            .toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (var commit : commits)
                assertEquals("committed", commit.get(10, TimeUnit.SECONDS).outcome());
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
        assertEquals("succeeded", journal.tool(scope, task).orElseThrow().status());
        assertEquals(
                1,
                ProjectionTestSupport.project(journal).publicEvents(scope, 0, 100).stream()
                        .filter(event -> "tool.operation.completed".equals(event.type()))
                        .count());
        jdbc.update(
                "DELETE FROM ma.tool_result_outbox WHERE scope_key=? AND task_id=?",
                scope.messageGroup(),
                task);
        admission.activate(scope, turn, 1, "successor");
        journal.commitToolResult(result);
        assertEquals(
                1,
                ProjectionTestSupport.project(journal).publicEvents(scope, 0, 100).stream()
                        .filter(event -> "tool.operation.completed".equals(event.type()))
                        .count());
    }

    @Test
    void toolWorkerOperationsUpdateOneNativeToolWithoutCompletingOrRenamingIt() throws Exception {
        var requested =
                event(
                        1,
                        "tool/requested",
                        "{\"toolCallId\":\"native-call\",\"call\":{\"type\":\"tool_use\",\"id\":\"native-call\",\"name\":\"execute\",\"input\":{}}}");
        AgentScopePreparation.commit(
                journal,
                new SessionCommitTransactions(journal),
                AgentScopeCheckpoints.prepare(
                        journal.objects(),
                        batch(
                                "operations",
                                0,
                                List.of(requested, checkpoint(2)),
                                List.of(
                                        new ToolIntent(
                                                "operation-one",
                                                "sandbox.exec",
                                                "{\"native_tool_call_id\":\"native-call\"}"),
                                        new ToolIntent(
                                                "operation-two",
                                                "fs.download",
                                                "{\"native_tool_call_id\":\"native-call\"}")),
                                List.of(),
                                null)));
        for (String operation : List.of("operation-one", "operation-two")) {
            String id = "tool_" + ManagedScope.fingerprint(scope.messageGroup(), turn, operation);
            journal.claimTool(scope, id, "hands");
            var result = new ManagedToolResult(scope, id, id, "succeeded", "{}");
            ManagedPushTestSupport.commitToolResult(journal, result);
            ManagedPushTestSupport.commitToolResult(journal, result);
        }
        var events = ProjectionTestSupport.project(journal).publicEvents(scope, 0, 20);
        assertEquals(
                0, events.stream().filter(event -> event.type().equals("tool.completed")).count());
        var operations =
                events.stream()
                        .filter(event -> event.type().equals("tool.operation.completed"))
                        .toList();
        assertEquals(2, operations.size());
        for (var operation : operations) {
            var data = mapper.readTree(operation.payload());
            assertEquals("native-call", data.path("tool_call_id").asText());
            assertEquals(
                    data.path("task_id").asText(), data.path("operation_execution_id").asText());
            assertEquals("tool", data.path("source").path("kind").asText());
        }
        var snapshot = ProjectionTestSupport.project(journal).resources().snapshot(scope);
        var tools =
                ProjectionTestSupport.project(journal)
                        .resources()
                        .page(scope, snapshot.id(), "tools", null, 10)
                        .data();
        assertEquals(1, tools.size());
        var data = (Map<?, ?>) tools.get(0).get("data");
        assertEquals("native-call", data.get("tool_call_id"));
        assertEquals("execute", data.get("name"));
        assertEquals("requested", data.get("status"));
        assertEquals(2, ((Map<?, ?>) data.get("operations")).size());
        assertEquals(2, journal.head(scope).sequence());
        String message =
                "{\"message\":{\"id\":\"message-result\",\"role\":\"tool\",\"content\":[{\"type\":\"tool_result\",\"id\":\"native-call\",\"name\":\"execute\",\"state\":\"success\",\"output\":[{\"type\":\"text\",\"text\":\"real"
                    + " result\"}]}]}}";
        AgentScopePreparation.commit(
                journal,
                new SessionCommitTransactions(journal),
                AgentScopeCheckpoints.prepare(
                        journal.objects(),
                        batch(
                                "model-result",
                                2,
                                List.of(event(3, "tool/result", message), checkpoint(4)),
                                List.of(),
                                List.of(),
                                null)));
        AgentScopePreparation.commit(
                journal,
                new SessionCommitTransactions(journal),
                AgentScopeCheckpoints.prepare(
                        journal.objects(),
                        batch(
                                "replayed-model-result",
                                4,
                                List.of(event(5, "tool/result", message), checkpoint(6)),
                                List.of(),
                                List.of(),
                                null)));
        assertEquals(
                1,
                ProjectionTestSupport.project(journal).publicEvents(scope, 0, 30).stream()
                        .filter(event -> event.type().equals("tool.completed"))
                        .count());
        var complete = ProjectionTestSupport.project(journal).resources().snapshot(scope);
        var completedTools =
                ProjectionTestSupport.project(journal)
                        .resources()
                        .page(scope, complete.id(), "tools", null, 10)
                        .data();
        assertEquals(1, completedTools.size());
        var finalData = (Map<?, ?>) completedTools.get(0).get("data");
        assertEquals("success", finalData.get("status"));
        assertEquals(2, ((Map<?, ?>) finalData.get("operations")).size());
    }

    protected DataSource database() {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:journal-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
    }

    private ManagedRuntimeBatch batch(
            String id,
            long sequence,
            List<SessionEvent> events,
            List<ToolIntent> tools,
            List<InputReceipt> inputs,
            String terminal) {
        return new ManagedRuntimeBatch(
                scope, turn, 1, "token", "writer", id, sequence, events, tools, inputs, terminal);
    }

    private SessionEvent checkpoint(long sequence) {
        return event(
                sequence,
                "state/checkpoint",
                "{\"projectorVersion\":1,\"stateJson\":\"{\\\"context\\\":[]}\",\"reason\":\"test\"}");
    }

    private SessionEvent event(long sequence, String type, String payload) {
        return new SessionEvent(
                1,
                "e" + sequence + "_" + UUID.randomUUID(),
                sequence,
                1,
                type,
                "run",
                turn,
                true,
                payload);
    }
}
