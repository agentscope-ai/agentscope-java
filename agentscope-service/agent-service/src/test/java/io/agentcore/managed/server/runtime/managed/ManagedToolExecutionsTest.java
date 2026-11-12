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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.managed.ManagedInputInbox.Signal;
import io.agentcore.managed.server.runtime.managed.ManagedInputInbox.Type;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore.ToolTask;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Channel;
import io.agentcore.managed.server.runtime.managed.ManagedRuntimeBatch.ToolIntent;
import io.agentcore.managed.server.runtime.managed.ManagedToolExecutor.Outcome;
import io.agentcore.managed.server.runtime.managed.ManagedToolExecutor.State;
import io.agentscope.core.session.SessionEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Timeout(15)
class ManagedToolExecutionsTest {
    @TempDir private Path managedCheckpointRoot;

    @TempDir Path blobs;
    private final ManagedScope scope = new ManagedScope("t", "n", "s");
    private final ObjectMapper mapper = new ObjectMapper();
    private final Duration ttl = Duration.ofSeconds(2);
    private final InMemoryManagedMessageBus bus =
            new InMemoryManagedMessageBus(() -> System.nanoTime() / 1_000_000, 8);
    private final InMemoryManagedLeaseStore leases =
            new InMemoryManagedLeaseStore(() -> System.nanoTime() / 1_000_000);
    private final InMemoryManagedInputInbox inbox =
            new InMemoryManagedInputInbox(System::currentTimeMillis);
    private ManagedAdmissionStore admission;
    private ManagedJournalStore journal;
    private ManagedBatchCodec codec;
    private ScheduledExecutorService renewals;
    private ManagedToolExecutions worker;
    private DriverManagerDataSource source;
    private String turn;
    private String task;

    @BeforeEach
    void setup() {
        source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:tools-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        ManagedAdmissionStore.initialize(source);
        admission = ProjectionTestSupport.admission(source, mapper);
        journal =
                ProjectionTestSupport.journal(
                        source,
                        mapper,
                        new ManagedCheckpointObjects(mapper, managedCheckpointRoot));
        admission.createSession(scope, "{}");
        turn = admission.acceptTurn(scope, "u", "t", "{}").id();
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
                        "{\"projectorVersion\":1,\"stateJson\":\"{\\\"context\\\":[]}\",\"reason\":\"test\"}");
        AgentScopePreparation.commit(
                journal,
                new SessionCommitTransactions(journal),
                AgentScopeCheckpoints.prepare(
                        journal.objects(),
                        new ManagedRuntimeBatch(
                                scope,
                                turn,
                                1,
                                "brain",
                                "owner",
                                "intent",
                                0,
                                List.of(checkpoint),
                                List.of(new ToolIntent("call", "exec", "{}")),
                                List.of(),
                                null)));
        task = "tool_" + ManagedScope.fingerprint(scope.messageGroup(), turn, "call");
        codec = new ManagedBatchCodec(mapper, blobs);
        renewals = Executors.newScheduledThreadPool(2);
        worker =
                new ManagedToolExecutions(
                        leases,
                        inbox,
                        journal,
                        new ManagedToolResultPublisher(journal, bus, codec),
                        renewals,
                        ttl,
                        Duration.ofMillis(200),
                        Duration.ofMillis(10));
    }

    @AfterEach
    void stop() {
        renewals.shutdownNow();
        bus.close();
    }

    @Test
    void recoveryQueriesOriginalExecutionAndLateResultSurvivesHarnessRuntimeTakeover() {
        journal.claimTool(scope, task, "old-hands");
        journal.recordToolHandle(scope, task, "old-hands", "external-handle");
        admission.activate(scope, turn, 1, "new-brain");
        var executor = new ControlledExecutor(new Outcome(State.SUCCEEDED, result("success")));
        assertTrue(worker.execute(scope, task, executor));
        assertEquals(0, executor.starts.get());
        assertEquals("result_pending", journal.tool(scope, task).orElseThrow().status());
        consumeResult();
        assertEquals("succeeded", journal.tool(scope, task).orElseThrow().status());
        assertEquals(2, admission.session(scope).executionEpoch());
        assertEquals(1, journal.head(scope).sequence());
        assertEquals(1, ProjectionTestSupport.project(journal).publicEvents(scope, 0, 100).size());
    }

    @Test
    void unknownPreviousStartIsNotRepeatedWithoutExternalIdempotency() throws Exception {
        journal.claimTool(scope, task, "old-hands");
        var executor = new ControlledExecutor(new Outcome(State.NOT_FOUND, null));
        assertFalse(worker.execute(scope, task, executor));
        assertEquals(0, executor.starts.get());
        assertEquals("unknown", journal.tool(scope, task).orElseThrow().status());
        try (var probe = new ManagedPushTestSupport.Probe(bus, Channel.TOOL_EVENTS, 1)) {
            assertNull(probe.poll(Duration.ofMillis(50)));
        }
    }

    @Test
    void launchRecordedBeforeActualStartCanRecoverThroughAtomicExternalIdempotency() {
        journal.claimTool(scope, task, "old-hands");
        var executor =
                new ControlledExecutor(new Outcome(State.NOT_FOUND, null)) {
                    @Override
                    public boolean idempotentStart() {
                        return true;
                    }

                    @Override
                    public String start(ManagedScope scope, ToolTask task) {
                        super.start(scope, task);
                        outcome = new Outcome(State.SUCCEEDED, result("success"));
                        return task.executionId();
                    }
                };
        assertTrue(worker.execute(scope, task, executor));
        assertEquals(1, executor.starts.get());
        consumeResult();
        assertEquals("succeeded", journal.tool(scope, task).orElseThrow().status());
    }

    @Test
    void cancelBeforeLaunchHasConfirmedStoppedResultAndDoesNotStartTheTool() {
        inbox.append(scope, turn, new Signal("cancel", 1, Type.CANCEL, null, "ref"));
        var executor = new ControlledExecutor(new Outcome(State.RUNNING, null));
        assertTrue(worker.execute(scope, task, executor));
        assertEquals(0, executor.starts.get());
        assertEquals("result_pending", journal.tool(scope, task).orElseThrow().status());
        consumeResult();
        assertEquals("cancelled", journal.tool(scope, task).orElseThrow().status());
    }

    @Test
    void terminalCannotCommitWhileToolHasUnknownEffects() {
        journal.claimTool(scope, task, "worker");
        journal.markToolUnknown(scope, task, "worker");
        admission.closeInputs(scope, turn, 1);
        var terminal =
                new ManagedRuntimeBatch(
                        scope,
                        turn,
                        1,
                        "brain",
                        "owner",
                        "terminal",
                        1,
                        List.of(),
                        List.of(),
                        List.of(),
                        "cancelled");
        assertEquals(
                "tools_unsettled",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        AgentScopePreparation.commit(
                                                journal,
                                                new SessionCommitTransactions(journal),
                                                AgentScopeCheckpoints.prepare(
                                                        journal.objects(), terminal)))
                        .code());
        assertEquals(0, admission.session(scope).settledSequence());
    }

    @Test
    void staleToolWorkerCannotReplaceSuccessorsProcessHandle() {
        journal.claimTool(scope, task, "old");
        journal.claimTool(scope, task, "new");
        assertEquals(
                "stale_tool_worker",
                assertThrows(
                                ManagedStateException.class,
                                () -> journal.recordToolHandle(scope, task, "old", "bad"))
                        .code());
        journal.recordToolHandle(scope, task, "new", "current");
        assertEquals("current", journal.tool(scope, task).orElseThrow().processHandle());
    }

    @Test
    void childPollingReleasesTheToolWorkerSlotAndResumesTheOriginalExecution() {
        var executor =
                new ControlledExecutor(new Outcome(State.RUNNING, null)) {
                    @Override
                    public boolean waitForCompletion(ToolTask task) {
                        return false;
                    }
                };
        assertFalse(worker.execute(scope, task, executor));
        assertEquals(1, executor.starts.get());
        assertEquals("running", journal.tool(scope, task).orElseThrow().status());
        var available = leases.acquire(scope.key("tool:" + task), ttl).orElseThrow();
        leases.release(available);
        executor.outcome = new Outcome(State.SUCCEEDED, result("succeeded"));
        assertTrue(worker.execute(scope, task, executor));
        assertEquals(1, executor.starts.get());
        consumeResult();
        assertEquals("succeeded", journal.tool(scope, task).orElseThrow().status());
    }

    @Test
    void publicationFailureSurvivesPublisherRebuildWithoutConsultingTheExternalTool() {
        var unavailable = mock(ManagedMessageBus.class);
        doAnswer(
                        invocation -> {
                            throw new ManagedTransportException("MQ is unavailable");
                        })
                .when(unavailable)
                .publish(eq(Channel.TOOL_EVENTS), any());
        var original = new ControlledExecutor(new Outcome(State.SUCCEEDED, result("success")));
        var failing = executions(new ManagedToolResultPublisher(journal, unavailable, codec));
        assertThrows(ManagedTransportException.class, () -> failing.execute(scope, task, original));
        assertEquals(1, original.starts.get());
        assertEquals("result_pending", journal.tool(scope, task).orElseThrow().status());
        assertEquals(1, journal.pendingToolResultPublications(8, Long.MAX_VALUE).size());
        var rebuilt =
                executions(
                        new ManagedToolResultPublisher(
                                journal, bus, new ManagedBatchCodec(mapper, blobs)));
        assertTrue(
                rebuilt.execute(
                        scope,
                        task,
                        new ControlledExecutor(null) {
                            public Outcome query(ManagedScope ignored, ToolTask value) {
                                throw new AssertionError(
                                        "A confirmed result must not query or restart the old"
                                                + " execution");
                            }
                        }));
        consumeResult();
        assertEquals(result("success"), journal.tool(scope, task).orElseThrow().result());
        assertTrue(journal.pendingToolResultPublications(8, Long.MAX_VALUE).isEmpty());
    }

    @Test
    void brokerAcceptanceWithLostResponseKeepsOneImmutableResultAndOneProjection() {
        var ambiguous = mock(ManagedMessageBus.class);
        doAnswer(
                        invocation -> {
                            bus.publish(invocation.getArgument(0), invocation.getArgument(1));
                            throw new ManagedTransportException("Broker accepted; response lost");
                        })
                .when(ambiguous)
                .publish(eq(Channel.TOOL_EVENTS), any());
        var original = new ControlledExecutor(new Outcome(State.SUCCEEDED, result("success")));
        assertThrows(
                ManagedTransportException.class,
                () ->
                        executions(new ManagedToolResultPublisher(journal, ambiguous, codec))
                                .execute(scope, task, original));
        var captured = journal.stagedToolResult(scope, task).orElseThrow();
        new ManagedToolResultPublisher(journal, bus, codec).publish(captured);
        consumeResult();
        var receipt = journal.commitToolResult(captured);
        assertEquals(receipt, journal.commitToolResult(captured));
        assertEquals(1, original.starts.get());
        assertEquals(1, ProjectionTestSupport.project(journal).publicEvents(scope, 0, 100).size());
        assertTrue(journal.pendingToolResultPublications(8, Long.MAX_VALUE).isEmpty());
    }

    @Test
    void acceptedResultSurvivesOutboxCleanupAndEpochTakeover() {
        journal.claimTool(scope, task, "worker");
        var confirmed = new ManagedToolResult(scope, task, task, "succeeded", result("success"));
        journal.stageToolResult(confirmed);
        journal.commitToolResult(confirmed);
        var sql = new JdbcTemplate(source);
        sql.update(
                "DELETE FROM ma.tool_result_outbox WHERE scope_key=? AND task_id=?",
                scope.messageGroup(),
                task);
        admission.activate(scope, turn, 1, "replacement-brain");
        assertEquals("committed", journal.commitToolResult(confirmed).outcome());
        assertEquals(1, ProjectionTestSupport.project(journal).publicEvents(scope, 0, 100).size());
        assertEquals(confirmed.result(), journal.tool(scope, task).orElseThrow().result());
        assertEquals(
                "tool_result_conflict",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        journal.commitToolResult(
                                                new ManagedToolResult(
                                                        scope, task, task, "failed", "{}")))
                        .code());
        assertEquals(
                "tool_execution_mismatch",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        journal.commitToolResult(
                                                new ManagedToolResult(
                                                        scope,
                                                        task,
                                                        "wrong-execution",
                                                        "succeeded",
                                                        confirmed.result())))
                        .code());
        assertEquals(confirmed.result(), journal.tool(scope, task).orElseThrow().result());
    }

    @Test
    void unstagedOrConflictingResultsCannotSettleTheOriginalTask() {
        journal.claimTool(scope, task, "worker");
        var confirmed = new ManagedToolResult(scope, task, task, "succeeded", result("success"));
        assertEquals(
                "tool_result_not_staged",
                assertThrows(ManagedStateException.class, () -> journal.commitToolResult(confirmed))
                        .code());
        journal.stageToolResult(confirmed);
        journal.recordToolHandle(scope, task, "worker", "late-started");
        var staged = journal.claimTool(scope, task, "another-worker").task();
        assertEquals("result_pending", staged.status());
        assertEquals("worker", staged.workerToken());
        assertNull(staged.processHandle());
        var conflicting = new ManagedToolResult(scope, task, task, "failed", "{}");
        assertThrows(ManagedStateException.class, () -> journal.stageToolResult(conflicting));
        assertThrows(ManagedStateException.class, () -> journal.commitToolResult(conflicting));
        assertEquals(confirmed, journal.stagedToolResult(scope, task).orElseThrow());
        assertNull(journal.tool(scope, task).orElseThrow().result());
        assertTrue(ProjectionTestSupport.project(journal).publicEvents(scope, 0, 100).isEmpty());
    }

    @Test
    void projectionFailureDoesNotRollBackTheDurableToolResult() {
        journal.claimTool(scope, task, "worker");
        var confirmed = new ManagedToolResult(scope, task, task, "succeeded", result("success"));
        journal.stageToolResult(confirmed);
        var sql = ProjectionTestSupport.queryJdbc(source, mapper);
        sql.execute(
                "ALTER TABLE ma_query.event_history ADD CONSTRAINT fixture_reject_public"
                        + " CHECK(sequence<0)");
        journal.commitToolResult(confirmed);
        assertEquals(confirmed.result(), journal.tool(scope, task).orElseThrow().result());
        assertTrue(journal.pendingToolResultPublications(8, Long.MAX_VALUE).isEmpty());
        assertThrows(
                DataIntegrityViolationException.class,
                () -> ProjectionTestSupport.project(journal));
        assertEquals(0, journal.publicHead(scope));
        sql.execute("ALTER TABLE ma_query.event_history DROP CONSTRAINT fixture_reject_public");
        ProjectionTestSupport.project(journal);
        assertTrue(journal.publicHead(scope) > 0);
    }

    @Test
    void missingLargeTransportFileIsRebuiltFromTheDurableOutcome() throws Exception {
        var large = result("success").replace("done", "x".repeat(700_000));
        assertTrue(
                worker.execute(
                        scope, task, new ControlledExecutor(new Outcome(State.SUCCEEDED, large))));
        try (var paths = Files.walk(blobs)) {
            for (var path : paths.filter(Files::isRegularFile).toList()) Files.delete(path);
        }
        var captured = journal.stagedToolResult(scope, task).orElseThrow();
        journal.toolResultPublishAttempt(captured, 0);
        assertEquals(
                1,
                new ManagedToolResultPublisher(journal, bus, new ManagedBatchCodec(mapper, blobs))
                        .repair(8));
        consumeResult();
        assertEquals(large, journal.tool(scope, task).orElseThrow().result());
        assertTrue(journal.pendingToolResultPublications(8, Long.MAX_VALUE).isEmpty());
    }

    private ManagedToolExecutions executions(ManagedToolResultPublisher publisher) {
        return new ManagedToolExecutions(
                leases,
                inbox,
                journal,
                publisher,
                renewals,
                ttl,
                Duration.ofMillis(200),
                Duration.ofMillis(10));
    }

    private void consumeResult() {
        try (var probe = new ManagedPushTestSupport.Probe(bus, Channel.TOOL_EVENTS, 1)) {
            var invocation = probe.next();
            new ManagedHarnessRuntimeToolEvents(codec, journal, r -> {})
                    .consume(invocation.delivery());
            invocation.succeed();
        }
    }

    private String result(String state) {
        return "{\"type\":\"tool_result\",\"id\":\"call\",\"name\":\"exec\",\"state\":\""
                + state
                + "\",\"output\":[{\"type\":\"text\",\"text\":\"done\"}]}";
    }

    private static class ControlledExecutor implements ManagedToolExecutor {
        final AtomicInteger starts = new AtomicInteger();
        Outcome outcome;

        ControlledExecutor(Outcome outcome) {
            this.outcome = outcome;
        }

        public String start(ManagedScope scope, ToolTask task) {
            starts.incrementAndGet();
            return task.executionId();
        }

        public Outcome query(ManagedScope scope, ToolTask task) {
            return outcome;
        }

        public Outcome cancel(ManagedScope scope, ToolTask task) {
            return new Outcome(State.CANCELLED, "{}");
        }
    }
}
