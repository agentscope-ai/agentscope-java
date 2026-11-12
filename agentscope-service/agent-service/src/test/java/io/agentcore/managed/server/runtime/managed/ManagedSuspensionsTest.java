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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.managed.ManagedRuntimeBatch.Suspension;
import io.agentcore.managed.server.runtime.managed.ManagedRuntimeBatch.ToolIntent;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JsonUtils;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ManagedSuspensionsTest {
    @TempDir private Path managedCheckpointRoot;

    @TempDir Path blobs;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ManagedScope scope = new ManagedScope("tenant", "ns", "s-" + UUID.randomUUID());
    private ManagedAdmissionStore admission;
    private ManagedJournalStore journal;
    private String turn;
    private String task;
    private DataSource source;
    private final InMemoryManagedLeaseStore leases =
            new InMemoryManagedLeaseStore(() -> System.nanoTime() / 1_000_000);

    @BeforeEach
    void setup() {
        source = database();
        ManagedAdmissionStore.initialize(source);
        admission = ProjectionTestSupport.admission(source, mapper);
        journal =
                ProjectionTestSupport.journal(
                        source,
                        mapper,
                        new ManagedCheckpointObjects(mapper, managedCheckpointRoot));
        admission.createSession(scope, "{}");
        turn = admission.acceptTurn(scope, "actor", "t1", "{}").id();
        admission.reservePublication(scope, turn);
        admission.confirmPublication(scope, turn, "broker");
        admission.activate(scope, turn, 1, "token");
        AgentScopePreparation.commit(
                journal,
                new SessionCommitTransactions(journal),
                AgentScopeCheckpoints.prepare(
                        journal.objects(),
                        batch(
                                "intent",
                                0,
                                List.of(new ToolIntent("call", "sandbox.exec", "{}")),
                                null)));
        task = "tool_" + ManagedScope.fingerprint(scope.messageGroup(), turn, "call");
        journal.claimTool(scope, task, "hands");
    }

    @AfterEach
    void removeOnlyThisFixtureContinuationOutbox() {
        new JdbcTemplate(source)
                .update("DELETE FROM ma.turn_resume WHERE scope_key=?", scope.messageGroup());
    }

    protected DataSource database() {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:suspend-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void eitherResultOrderCreatesOneDurableResumeAndFencesTheOldAttempt(boolean resultFirst) {
        if (resultFirst) result();
        var suspended = batch("suspension", 1, List.of(), Suspension.tools("s1", List.of(task)));
        var receipt =
                AgentScopePreparation.commit(
                        journal,
                        new SessionCommitTransactions(journal),
                        AgentScopeCheckpoints.prepare(journal.objects(), suspended));
        assertEquals("committed", receipt.outcome());
        assertEquals(
                receipt,
                AgentScopePreparation.commit(
                        journal,
                        new SessionCommitTransactions(journal),
                        AgentScopeCheckpoints.prepare(journal.objects(), suspended)));
        assertEquals("waiting_tool", admission.turn(scope, turn).status());
        assertTrue(admission.turn(scope, turn).inputsOpen());
        assertEquals(turn, admission.session(scope).currentTurnId());
        assertEquals(0, admission.session(scope).settledSequence());
        assertNull(admission.turn(scope, turn).executionToken());
        assertEquals(
                "stale_execution",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        AgentScopePreparation.commit(
                                                journal,
                                                new SessionCommitTransactions(journal),
                                                AgentScopeCheckpoints.prepare(
                                                        journal.objects(),
                                                        batch("late", 2, List.of(), null))))
                        .code());
        assertEquals(
                "turn_suspended",
                assertThrows(
                                ManagedStateException.class,
                                () -> admission.activate(scope, turn, 1, "old-message"))
                        .code());
        if (!resultFirst) {
            assertTrue(journal.suspensions().pending(10).isEmpty());
            result();
        }
        var resume = journal.suspensions().pending(10).get(0);
        assertEquals(1, journal.suspensions().pending(10).size());
        assertEquals(1, resume.suspensionEpoch());
        assertEquals(turn, resume.turnId());
        assertEquals(
                receipt,
                AgentScopePreparation.commit(
                        journal,
                        new SessionCommitTransactions(journal),
                        AgentScopeCheckpoints.prepare(journal.objects(), suspended)));
        result();
        assertEquals(List.of(resume), journal.suspensions().pending(10));
        assertEquals(2, admission.activateResume(resume, "new-token"));
        assertEquals(task, journal.tool(scope, task).orElseThrow().executionId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void batchCompletionCreatesOneRecoverableMqIntentInEitherArrivalOrder(boolean resultFirst) {
        var jdbc = new JdbcTemplate(source);
        String args =
                JsonUtils.getJsonCodec()
                        .toJson(
                                new ManagedMcpBatch.Manifest(
                                        List.of(
                                                new ManagedMcpBatch.Call(
                                                        "native", "mcp", Map.of()))));
        jdbc.update(
                "UPDATE ma.tool_task SET tool_name='mcp.batch',arguments=? WHERE scope_key=? AND"
                        + " task_id=?",
                args,
                scope.messageGroup(),
                task);
        seedBatchMembers(args);
        String result =
                JsonUtils.getJsonCodec()
                        .toJson(
                                new ManagedMcpBatch.Result(
                                        List.of(
                                                new ManagedMcpBatch.CallResult(
                                                        "native", "succeeded", "{}"))));
        var outcome = new ManagedToolResult(scope, task, task, "succeeded", result);
        if (resultFirst) ManagedPushTestSupport.commitToolResult(journal, outcome);
        AgentScopePreparation.commit(
                journal,
                new SessionCommitTransactions(journal),
                AgentScopeCheckpoints.prepare(
                        journal.objects(),
                        batch(
                                "suspend-batch",
                                1,
                                List.of(),
                                Suspension.tools("batch-s1", List.of(task)))));
        if (!resultFirst) ManagedPushTestSupport.commitToolResult(journal, outcome);
        var pending = journal.suspensions().pending(10);
        assertEquals(1, pending.size());
        var resume = pending.get(0);
        ManagedPushTestSupport.commitToolResult(journal, outcome);
        assertEquals(pending, journal.suspensions().pending(10));
        assertEquals(2, admission.activateResume(resume, "runtime-2"));
        // A crash after activation must retain the retry intent and allow a new execution epoch.
        assertEquals(pending, journal.suspensions().pending(10));
        assertEquals(3, admission.activateResume(resume, "runtime-3"));
        assertEquals(pending, journal.suspensions().pending(10));
        assertTrue(!journal.suspensions().settled(resume));
        assertEquals(pending, journal.suspensions().pending(10));
    }

    @Test
    void incompleteBatchResultCannotCommitOrWakeTheTurn() {
        var jdbc = new JdbcTemplate(source);
        String args =
                JsonUtils.getJsonCodec()
                        .toJson(
                                new ManagedMcpBatch.Manifest(
                                        List.of(
                                                new ManagedMcpBatch.Call("first", "mcp", Map.of()),
                                                new ManagedMcpBatch.Call(
                                                        "second", "mcp", Map.of()))));
        jdbc.update(
                "UPDATE ma.tool_task SET tool_name='mcp.batch',arguments=? WHERE scope_key=? AND"
                        + " task_id=?",
                args,
                scope.messageGroup(),
                task);
        seedBatchMembers(args);
        AgentScopePreparation.commit(
                journal,
                new SessionCommitTransactions(journal),
                AgentScopeCheckpoints.prepare(
                        journal.objects(),
                        batch(
                                "suspend-incomplete",
                                1,
                                List.of(),
                                Suspension.tools("incomplete-s1", List.of(task)))));
        String partial =
                JsonUtils.getJsonCodec()
                        .toJson(
                                new ManagedMcpBatch.Result(
                                        List.of(
                                                new ManagedMcpBatch.CallResult(
                                                        "first", "succeeded", "{}"))));
        var failure =
                assertThrows(
                        ManagedStateException.class,
                        () ->
                                ManagedPushTestSupport.commitToolResult(
                                        journal,
                                        new ManagedToolResult(
                                                scope, task, task, "succeeded", partial)));
        assertEquals("mcp_batch_result_mismatch", failure.code());
        assertEquals("waiting_tool", admission.turn(scope, turn).status());
        assertNull(journal.tool(scope, task).orElseThrow().result());
        assertTrue(journal.suspensions().pending(10).isEmpty());
    }

    @Test
    void toolResumeBacklogCannotStarveInputResumePublication() {
        AgentScopePreparation.commit(
                journal,
                new SessionCommitTransactions(journal),
                AgentScopeCheckpoints.prepare(
                        journal.objects(),
                        batch("suspend", 1, List.of(), Suspension.tools("s1", List.of(task)))));
        result();
        var jdbc = new JdbcTemplate(source);
        jdbc.update(
                "UPDATE ma.turn_resume SET resume_id='a-tool' WHERE scope_key=?",
                scope.messageGroup());
        jdbc.update(
                "INSERT INTO"
                    + " ma.turn_suspension(scope_key,turn_id,suspension_id,execution_epoch,checkpoint_sequence,checkpoint,pending_tasks,pending_actions,resume_phase,status)"
                    + " SELECT"
                    + " scope_key,turn_id,'input-ready',execution_epoch,checkpoint_sequence,checkpoint,'[]','[]','APPLY_INPUTS','ready'"
                    + " FROM ma.turn_suspension WHERE suspension_id='s1' AND scope_key=?",
                scope.messageGroup());
        jdbc.update(
                "INSERT INTO"
                    + " ma.turn_resume(scope_key,resume_id,turn_id,suspension_id,suspension_epoch,publication_sequence)"
                    + " SELECT"
                    + " scope_key,'z-input',turn_id,'input-ready',suspension_epoch,publication_sequence"
                    + " FROM ma.turn_resume WHERE resume_id='a-tool' AND scope_key=?",
                scope.messageGroup());
        var bus = mock(ManagedMessageBus.class);
        when(bus.publish(any(), any())).thenReturn(new ManagedMessageBus.Published("broker"));
        try (var publisher =
                new ManagedResumePublisher(journal.suspensions(), bus, leases, mapper)) {
            assertEquals(1, publisher.retry(1));
        }
        assertEquals(
                "queued",
                jdbc.queryForObject(
                        "SELECT delivery_status FROM ma.turn_resume WHERE resume_id='z-input' AND"
                                + " scope_key=?",
                        String.class,
                        scope.messageGroup()));
        assertEquals(
                "pending",
                jdbc.queryForObject(
                        "SELECT delivery_status FROM ma.turn_resume WHERE resume_id='a-tool' AND"
                                + " scope_key=?",
                        String.class,
                        scope.messageGroup()));
    }

    @Test
    void suspensionRollbackDoesNotReleaseTurnOrPersistAPartialCheckpoint() {
        var broken = batch("invalid", 1, List.of(), Suspension.tools("s1", List.of("missing")));
        assertEquals(
                "tool_not_found",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        AgentScopePreparation.commit(
                                                journal,
                                                new SessionCommitTransactions(journal),
                                                AgentScopeCheckpoints.prepare(
                                                        journal.objects(), broken)))
                        .code());
        assertEquals(1, journal.head(scope).sequence());
        assertEquals("preparing", admission.turn(scope, turn).status());
        assertTrue(journal.suspensions().pending(10).isEmpty());
    }

    @Test
    void forgedResumeCannotChangeItsOriginalActorOrContinuation() {
        AgentScopePreparation.commit(
                journal,
                new SessionCommitTransactions(journal),
                AgentScopeCheckpoints.prepare(
                        journal.objects(),
                        batch("suspend", 1, List.of(), Suspension.tools("s1", List.of(task)))));
        result();
        var original = journal.suspensions().pending(10).get(0);
        var forged =
                new ManagedSuspensions.Resume(
                        scope, original.resumeId(), turn, "other-suspension", 1, 1);
        assertEquals(
                "resume_identity_mismatch",
                assertThrows(
                                ManagedStateException.class,
                                () -> admission.activateResume(forged, "token2"))
                        .code());
        assertEquals("actor", admission.turn(scope, turn).actor());
        assertEquals("waiting_tool", admission.turn(scope, turn).status());
        assertEquals(1, admission.turn(scope, turn).executionEpoch());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void finalizationOnlyResumePublishesRunningBeforeTerminalAndFencesRetiredExecution(
            boolean nativeResume) {
        var batch = resumedHandoff("succeeded", null, nativeResume);
        var wire = AgentScopeCheckpoints.prepare(journal.objects(), batch);
        var receipt =
                AgentScopePreparation.commit(journal, new SessionCommitTransactions(journal), wire);
        assertEquals("committed", receipt.outcome());
        assertEquals(
                receipt,
                AgentScopePreparation.commit(
                        journal, new SessionCommitTransactions(journal), wire));
        assertEquals("succeeded", admission.turn(scope, turn).status());
        assertEquals(
                List.of("turn.running", "turn.waiting_tool", "turn.running", "turn.completed"),
                projectCurrentSession().publicEvents(scope, 0, 100).stream()
                        .map(event -> event.type())
                        .filter(type -> type.startsWith("turn."))
                        .toList());
        assertEquals(
                nativeResume ? 1 : 0,
                journal.recoveryEvents(scope, 0, 100).stream()
                        .filter(event -> "turn/resumed".equals(event.type()))
                        .count());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void restoredFrameworkWorkCanSuspendAgainWithoutLosingOrDuplicatingRunning(
            boolean nativeResume) {
        var batch = resumedHandoff(null, Suspension.tools("s2", List.of(task)), nativeResume);
        var wire = AgentScopeCheckpoints.prepare(journal.objects(), batch);
        var receipt =
                AgentScopePreparation.commit(journal, new SessionCommitTransactions(journal), wire);
        assertEquals("committed", receipt.outcome());
        assertEquals(
                receipt,
                AgentScopePreparation.commit(
                        journal, new SessionCommitTransactions(journal), wire));
        assertEquals("waiting_tool", admission.turn(scope, turn).status());
        assertEquals(
                List.of("turn.running", "turn.waiting_tool", "turn.running", "turn.waiting_tool"),
                projectCurrentSession().publicEvents(scope, 0, 100).stream()
                        .map(event -> event.type())
                        .filter(type -> type.startsWith("turn."))
                        .toList());
    }

    private ManagedJournalStore projectCurrentSession() {
        // PostgreSQL fixtures share a database, but each owns a separate temporary object root.
        while (journal.projections().project(scope, 128) > 0) {}
        return journal;
    }

    private ManagedRuntimeBatch resumedHandoff(
            String terminal, Suspension suspension, boolean nativeResume) {
        AgentScopePreparation.commit(
                journal,
                new SessionCommitTransactions(journal),
                AgentScopeCheckpoints.prepare(
                        journal.objects(),
                        batch("suspend", 1, List.of(), Suspension.tools("s1", List.of(task)))));
        result();
        var resume = journal.suspensions().pending(10).get(0);
        long epoch = admission.activateResume(resume, "resumed-token");
        if (terminal != null) admission.closeInputs(scope, turn, epoch);
        var head = journal.head(scope);
        var events =
                new ArrayList<>(batch("resumed-state", head.sequence(), List.of(), null).events());
        if (nativeResume)
            events.add(
                    new SessionEvent(
                            1,
                            "native-resume",
                            head.sequence() + 2,
                            System.currentTimeMillis(),
                            "turn/resumed",
                            "resumed-run",
                            turn,
                            true,
                            "{}"));
        return new ManagedRuntimeBatch(
                scope,
                turn,
                epoch,
                "resumed-token",
                "resumed-writer",
                "resumed-handoff",
                head.sequence(),
                events,
                List.of(),
                List.of(),
                terminal,
                suspension);
    }

    private void seedBatchMembers(String arguments) {
        int ordinal = 0;
        for (var call : ManagedMcpBatch.manifest(arguments).calls()) {
            new JdbcTemplate(source)
                    .update(
                            "INSERT INTO"
                                + " ma.mcp_batch_call(scope_key,turn_id,task_id,parent_task_id,native_tool_call_id,ordinal)"
                                + " VALUES(?,?,?,?,?,?)",
                            scope.messageGroup(),
                            turn,
                            ManagedMcpBatch.childTaskId(task, call.callId()),
                            task,
                            call.callId(),
                            ordinal++);
        }
    }

    private void result() {
        ManagedPushTestSupport.commitToolResult(
                journal,
                new ManagedToolResult(
                        scope, task, task, "succeeded", "{\"output\":\"done\",\"exitCode\":0}"));
    }

    private ManagedRuntimeBatch batch(
            String id, long expected, List<ToolIntent> tools, Suspension suspension) {
        try {
            var checkpoint =
                    new SessionEvent(
                            1,
                            id,
                            expected + 1,
                            System.currentTimeMillis(),
                            "state/checkpoint",
                            "run",
                            turn,
                            true,
                            mapper.writeValueAsString(
                                    Map.of("stateJson", AgentState.builder().build().toJson())));
            return new ManagedRuntimeBatch(
                    scope,
                    turn,
                    1,
                    "token",
                    "writer",
                    id,
                    expected,
                    List.of(checkpoint),
                    tools,
                    List.of(),
                    null,
                    suspension);
        } catch (Exception invalid) {
            throw new IllegalStateException(invalid);
        }
    }
}
