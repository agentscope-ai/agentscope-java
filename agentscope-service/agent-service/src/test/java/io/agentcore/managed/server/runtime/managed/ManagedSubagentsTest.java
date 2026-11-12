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
package io.agentcore.managed.server.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.managed.ManagedInputInbox.Type;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore.ToolTask;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Channel;
import io.agentcore.managed.server.runtime.managed.ManagedRuntimeBatch.InputReceipt;
import io.agentcore.managed.server.runtime.managed.ManagedRuntimeBatch.ToolIntent;
import io.agentcore.managed.server.runtime.managed.ManagedSubagents.Call;
import io.agentcore.managed.server.runtime.managed.ManagedSubagents.Reply;
import io.agentcore.managed.server.runtime.managed.ManagedSubagents.Specification;
import io.agentcore.managed.server.runtime.managed.ManagedToolExecutor.Outcome;
import io.agentcore.managed.server.runtime.managed.ManagedToolExecutor.State;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.util.JsonUtils;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Timeout(20)
class ManagedSubagentsTest {
    @TempDir private Path managedCheckpointRoot;

    @TempDir Path blobs;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ManagedScope root =
            new ManagedScope(
                    "tenant-" + UUID.randomUUID(), "namespace", UUID.randomUUID().toString());
    private final String actor =
            "{\"user\":\"original-user\",\"internal\":false,\"key_hash\":\"original-key\"}";
    private final Specification specification =
            new Specification(
                    "worker",
                    "do one child task",
                    "child result",
                    "{\"system\":\"frozen child instruction\"}");
    private DataSource source;
    private ManagedAdmissionStore admission;
    private ManagedJournalStore journal;
    private ManagedSubagents children;
    private String turn;

    protected DataSource database() {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:children-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
    }

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
        children = new ManagedSubagents(source, mapper, admission, journal);
        admission.createSession(
                root,
                "{\"owner_id\":\"original-user\",\"application_id\":\""
                        + root.sessionId()
                        + "\",\"encrypted_definition\":\"opaque-parent-definition\"}");
        turn = admission.acceptTurn(root, actor, "parent", "{\"message\":\"parent task\"}").id();
        admission.reservePublication(root, turn);
        admission.confirmPublication(root, turn, "broker");
        admission.activate(root, turn, 1, "brain");
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
                                root,
                                turn,
                                1,
                                "brain",
                                "owner",
                                "checkpoint",
                                0,
                                List.of(checkpoint),
                                List.of(),
                                List.of(),
                                null)));
    }

    String call(String nativeCall, Specification spec) throws Exception {
        return call(root, turn, nativeCall, spec);
    }

    String call(ManagedScope scope, String turnId, String nativeCall, Specification spec)
            throws Exception {
        String arguments =
                mapper.writeValueAsString(
                        Map.of("subagent", spec, "native_tool_call_id", nativeCall));
        var receipt =
                AgentScopePreparation.commit(
                        journal,
                        new SessionCommitTransactions(journal),
                        AgentScopeCheckpoints.prepare(
                                journal.objects(),
                                new ManagedRuntimeBatch(
                                        scope,
                                        turnId,
                                        1,
                                        journal.head(scope).token(),
                                        "owner",
                                        "intent-" + nativeCall,
                                        journal.head(scope).sequence(),
                                        journal.head(scope).checkpoint() == null
                                                ? List.of(
                                                        new SessionEvent(
                                                                1,
                                                                "child-checkpoint-" + nativeCall,
                                                                journal.head(scope).sequence() + 1,
                                                                1,
                                                                "state/checkpoint",
                                                                "run",
                                                                turnId,
                                                                true,
                                                                "{\"stateJson\":\"{}\",\"reason\":\"test\"}"))
                                                : List.of(),
                                        List.of(
                                                new ToolIntent(
                                                        nativeCall,
                                                        ManagedSubagents.OPERATION,
                                                        arguments)),
                                        List.of(),
                                        null)));
        assertEquals("committed", receipt.outcome());
        return "tool_" + ManagedScope.fingerprint(scope.messageGroup(), turnId, nativeCall);
    }

    @Test
    void descendantsRetainTheOriginalRootTurnAndObserveRootCancellation() throws Exception {
        var first = children.start(root, call("spawn", specification));
        admission.reservePublication(first.child(), first.childTurnId());
        admission.confirmPublication(first.child(), first.childTurnId(), "child-broker");
        admission.activate(first.child(), first.childTurnId(), 1, "child-brain");
        String task = call(first.child(), first.childTurnId(), "nested", specification);
        admission.acceptInput(root, turn, actor, "cancel-root", Type.CANCEL, null, "{}");
        var nested = children.start(first.child(), task);
        assertEquals(root, nested.root());
        assertEquals(turn, nested.rootTurnId());
        assertEquals("cancelled", nested.status());
        assertNull(nested.child());
        assertEquals(nested, children.start(first.child(), task));
    }

    @Test
    void runningDescendantsReadAncestorCancellationWithoutAnIntermediateDelivery()
            throws Exception {
        var first = children.start(root, call("spawn", specification));
        activate(first);
        var nested =
                children.start(
                        first.child(),
                        call(first.child(), first.childTurnId(), "nested", specification));
        activate(nested);
        assertFalse(journal.cancelRequested(nested.child(), nested.childTurnId()));
        admission.acceptInput(root, turn, actor, "cancel-root", Type.CANCEL, null, "{}");
        assertTrue(journal.cancelRequested(first.child(), first.childTurnId()));
        assertTrue(journal.cancelRequested(nested.child(), nested.childTurnId()));
        assertTrue(admission.pendingInputs(first.child(), first.childTurnId(), 256).isEmpty());
        assertTrue(admission.pendingInputs(nested.child(), nested.childTurnId(), 256).isEmpty());
    }

    @Test
    void aCancelledPreviousChildTurnDoesNotCancelANewPersistentChildTurn() throws Exception {
        var persistent =
                new Specification("worker", "first", "header", "{}", UUID.randomUUID().toString());
        var first = children.start(root, call("spawn", persistent));
        activate(first);
        admission.acceptInput(
                first.child(), first.childTurnId(), actor, "cancel-child", Type.CANCEL, null, "{}");
        finish(first, "cancelled", false);
        var second = children.start(root, call("send", persistent));
        assertEquals(first.child(), second.child());
        assertNotEquals(first.childTurnId(), second.childTurnId());
        assertTrue(journal.cancelRequested(first.child(), first.childTurnId()));
        assertFalse(journal.cancelRequested(second.child(), second.childTurnId()));
    }

    void activate(Call child) {
        admission.reservePublication(child.child(), child.childTurnId());
        admission.confirmPublication(child.child(), child.childTurnId(), "child-broker");
        admission.activate(child.child(), child.childTurnId(), 1, "child-brain");
    }

    void finish(Call child, String status, boolean output) throws Exception {
        admission.closeInputs(child.child(), child.childTurnId(), 1);
        var head = journal.head(child.child());
        var events = new ArrayList<SessionEvent>();
        if (output)
            events.add(
                    new SessionEvent(
                            1,
                            "child-output",
                            head.sequence() + 1,
                            1,
                            "turn/output",
                            "child-run",
                            child.childTurnId(),
                            true,
                            JsonUtils.getJsonCodec()
                                    .toJson(
                                            Map.of(
                                                    "message",
                                                    new AssistantMessage(
                                                            "original child result")))));
        events.add(
                new SessionEvent(
                        1,
                        "terminal-checkpoint",
                        head.sequence() + events.size() + 1,
                        1,
                        "state/checkpoint",
                        "child-run",
                        child.childTurnId(),
                        true,
                        "{\"stateJson\":\"{}\",\"reason\":\"test\"}"));
        var receipts =
                admission.pendingInputs(child.child(), child.childTurnId(), 256).stream()
                        .map(input -> new InputReceipt(input.id(), "applied", null))
                        .toList();
        assertEquals(
                "committed",
                AgentScopePreparation.commit(
                                journal,
                                new SessionCommitTransactions(journal),
                                AgentScopeCheckpoints.prepare(
                                        journal.objects(),
                                        new ManagedRuntimeBatch(
                                                child.child(),
                                                child.childTurnId(),
                                                1,
                                                head.token(),
                                                "child-writer",
                                                "child-terminal",
                                                head.sequence(),
                                                events,
                                                List.of(),
                                                receipts,
                                                status)))
                        .outcome());
    }

    @Test
    void toolWorkerRestartsReadTheOriginalCommittedChildOutput() throws Exception {
        String id = call("spawn", specification);
        var task = journal.tool(root, id).orElseThrow();
        var executor = new ManagedSubagentExecutor(children, admission, journal, mapper);
        String handle = executor.start(root, task);
        assertEquals(handle, executor.start(root, task));
        assertFalse(executor.waitForCompletion(task));
        assertEquals(State.RUNNING, executor.query(root, task).state());
        var child = children.find(root, id).orElseThrow();
        activate(child);
        finish(child, "succeeded", true);
        var replacement = new ManagedSubagentExecutor(children, admission, journal, mapper);
        var outcome = replacement.query(root, task);
        assertEquals(State.SUCCEEDED, outcome.state());
        var reply = mapper.readValue(outcome.result(), Reply.class);
        assertEquals(
                "original child result",
                JsonUtils.getJsonCodec().fromJson(reply.messageJson(), Msg.class).getTextContent());
        assertEquals(outcome, replacement.query(root, task));
    }

    @Test
    void cancellationAcceptanceIsNotChildStopProof() throws Exception {
        String id = call("spawn", specification);
        var task = journal.tool(root, id).orElseThrow();
        var executor = new ManagedSubagentExecutor(children, admission, journal, mapper);
        executor.start(root, task);
        var child = children.find(root, id).orElseThrow();
        activate(child);
        admission.acceptInput(root, turn, actor, "cancel-root", Type.CANCEL, null, "{}");
        assertEquals(State.RUNNING, executor.query(root, task).state());
        assertEquals(State.RUNNING, executor.cancel(root, task).state());
        assertEquals(1, admission.pendingInputs(child.child(), child.childTurnId(), 256).size());
        assertEquals(
                actor,
                admission.pendingInputs(child.child(), child.childTurnId(), 256).get(0).actor());
        finish(child, "cancelled", false);
        assertEquals(State.CANCELLED, executor.query(root, task).state());
    }

    @Test
    void successfulChildWithoutCommittedOutputCannotProduceAReply() throws Exception {
        String id = call("spawn", specification);
        var task = journal.tool(root, id).orElseThrow();
        var child = children.start(root, id);
        activate(child);
        finish(child, "succeeded", false);
        var executor = new ManagedSubagentExecutor(children, admission, journal, mapper);
        assertEquals(
                "subagent_output_missing",
                assertThrows(ManagedStateException.class, () -> executor.query(root, task)).code());
    }

    @Test
    void boundedToolScanLetsAnUnpolledChildPrecedeItsWaitingParent() throws Exception {
        String id = call("spawn", specification);
        var child = children.start(root, id);
        activate(child);
        String childTool = call(child.child(), child.childTurnId(), "nested", specification);
        journal.claimTool(root, id, "polling-parent");
        var first =
                journal.pendingTools(256).stream()
                        .filter(ref -> ref.scope().tenant().equals(root.tenant()))
                        .findFirst()
                        .orElseThrow();
        assertEquals(child.child(), first.scope());
        assertEquals(childTool, first.taskId());
    }

    @Test
    void deferringARemoteWorkerTaskDoesNotConsumeItsFirstLaunch() throws Exception {
        String id = call("spawn", specification);
        journal.deferToolDispatch(root, id);
        assertEquals("ready", journal.tool(root, id).orElseThrow().status());
        assertTrue(journal.claimTool(root, id, "actual-worker").firstLaunch());
        var reply =
                mapper.readValue(
                        ManagedToolResult.cancelledPayload(ManagedSubagents.OPERATION, "{}"),
                        Reply.class);
        assertEquals("cancelled", reply.status());
        assertNull(reply.messageJson());
    }

    @Test
    void childControlScanFiltersBeforeApplyingItsLimit() throws Exception {
        String childTask = call("spawn", specification);
        journal.deferToolDispatch(root, childTask);
        var head = journal.head(root);
        AgentScopePreparation.commit(
                journal,
                new SessionCommitTransactions(journal),
                AgentScopeCheckpoints.prepare(
                        journal.objects(),
                        new ManagedRuntimeBatch(
                                root,
                                turn,
                                1,
                                head.token(),
                                "owner",
                                "physical-intent",
                                head.sequence(),
                                List.of(),
                                List.of(new ToolIntent("physical", "sandbox.exec", "{}")),
                                List.of(),
                                null)));
        String physicalTask =
                "tool_" + ManagedScope.fingerprint(root.messageGroup(), turn, "physical");
        assertEquals(physicalTask, journal.pendingTools(1, false).get(0).taskId());
        assertEquals(childTask, journal.pendingTools(1, true).get(0).taskId());
    }

    @Test
    void aPeerOwnedTaskDoesNotStarveTheNextChildControl() throws Exception {
        String owned = call("owned", specification);
        String next = call("next", specification);
        journal.deferToolDispatch(root, next);
        var ttl = Duration.ofSeconds(30);
        var leases = new InMemoryManagedLeaseStore(() -> System.nanoTime() / 1_000_000);
        var peer = leases.acquire(root.key("tool:" + owned), ttl).orElseThrow();
        var inbox = new InMemoryManagedInputInbox(System::currentTimeMillis);
        var renewals = Executors.newSingleThreadScheduledExecutor();
        var errors = new ConcurrentLinkedQueue<RuntimeException>();
        try (var bus = new InMemoryManagedMessageBus(() -> System.nanoTime() / 1_000_000, 8)) {
            var executions =
                    new ManagedToolExecutions(
                            leases,
                            inbox,
                            journal,
                            new ManagedToolResultPublisher(
                                    journal, bus, new ManagedBatchCodec(mapper, blobs)),
                            renewals,
                            ttl,
                            Duration.ofSeconds(5),
                            Duration.ofMillis(10));
            var executor = new ManagedSubagentExecutor(children, admission, journal, mapper);
            try (var controls =
                    new ManagedToolLoop(
                            journal,
                            executions,
                            1,
                            true,
                            (scope, task) -> executor,
                            (task, failure) -> errors.add(failure))) {
                controls.start();
                await(() -> children.find(root, next).isPresent(), () -> {});
                assertTrue(leases.owns(peer));
                assertTrue(children.find(root, owned).isEmpty());
                assertTrue(errors.isEmpty(), errors.toString());
            }
        } finally {
            leases.release(peer);
            renewals.shutdownNow();
        }
    }

    @Test
    void oneToolWorkerSlotCanAdmitAChildRunItsToolAndReturnItsOriginalResult() throws Exception {
        String id = call("spawn", specification);
        var codec = new ManagedBatchCodec(mapper, blobs);
        var leases = new InMemoryManagedLeaseStore(() -> System.nanoTime() / 1_000_000);
        var inbox = new InMemoryManagedInputInbox(System::currentTimeMillis);
        var renewals = Executors.newScheduledThreadPool(1);
        var errors = new ConcurrentLinkedQueue<RuntimeException>();
        var childExecutor = new ManagedSubagentExecutor(children, admission, journal, mapper);
        var toolExecutor =
                new ManagedToolExecutor() {
                    @Override
                    public String start(ManagedScope scope, ToolTask task) {
                        return task.executionId();
                    }

                    @Override
                    public Outcome query(ManagedScope scope, ToolTask task) {
                        return new Outcome(State.SUCCEEDED, "{\"output\":\"child tool result\"}");
                    }

                    @Override
                    public Outcome cancel(ManagedScope scope, ToolTask task) {
                        return new Outcome(State.RUNNING, null);
                    }
                };
        try (var bus = new InMemoryManagedMessageBus(() -> System.nanoTime() / 1_000_000, 8)) {
            var executions =
                    new ManagedToolExecutions(
                            leases,
                            inbox,
                            journal,
                            new ManagedToolResultPublisher(journal, bus, codec),
                            renewals,
                            Duration.ofSeconds(3),
                            Duration.ofMillis(200),
                            Duration.ofMillis(10));
            var events = new ManagedHarnessRuntimeToolEvents(codec, journal, r -> {});
            for (var channel : List.of(Channel.TOOL_EVENTS))
                bus.subscribe(
                        channel,
                        2,
                        events::consume,
                        (delivery, failure) -> errors.add(new RuntimeException(failure)));
            Runnable pump = () -> {};
            try (var loop =
                            new ManagedToolLoop(
                                    journal,
                                    executions,
                                    1,
                                    false,
                                    (scope, task) -> toolExecutor,
                                    (task, failure) -> errors.add(failure));
                    var controls =
                            new ManagedToolLoop(
                                    journal,
                                    executions,
                                    1,
                                    true,
                                    (scope, task) -> childExecutor,
                                    (task, failure) -> errors.add(failure))) {
                loop.start();
                controls.start();
                await(() -> children.find(root, id).isPresent(), pump);
                var child = children.find(root, id).orElseThrow();
                activate(child);
                var head = journal.head(child.child());
                assertEquals(
                        "committed",
                        AgentScopePreparation.commit(
                                        journal,
                                        new SessionCommitTransactions(journal),
                                        AgentScopeCheckpoints.prepare(
                                                journal.objects(),
                                                new ManagedRuntimeBatch(
                                                        child.child(),
                                                        child.childTurnId(),
                                                        1,
                                                        head.token(),
                                                        "child-writer",
                                                        "child-tool",
                                                        head.sequence(),
                                                        List.of(
                                                                new SessionEvent(
                                                                        1,
                                                                        "child-checkpoint",
                                                                        1,
                                                                        1,
                                                                        "state/checkpoint",
                                                                        "child-run",
                                                                        child.childTurnId(),
                                                                        true,
                                                                        "{\"stateJson\":\"{}\",\"reason\":\"test\"}")),
                                                        List.of(
                                                                new ToolIntent(
                                                                        "sandbox-call",
                                                                        "sandbox.exec",
                                                                        "{}")),
                                                        List.of(),
                                                        null)))
                                .outcome());
                String childTool =
                        "tool_"
                                + ManagedScope.fingerprint(
                                        child.child().messageGroup(),
                                        child.childTurnId(),
                                        "sandbox-call");
                await(
                        () -> journal.tool(child.child(), childTool).orElseThrow().result() != null,
                        pump);
                assertNull(journal.tool(root, id).orElseThrow().result());
                finish(child, "succeeded", true);
                await(() -> journal.tool(root, id).orElseThrow().result() != null, pump);
                var reply =
                        mapper.readValue(
                                journal.tool(root, id).orElseThrow().result(), Reply.class);
                assertEquals(
                        "original child result",
                        JsonUtils.getJsonCodec()
                                .fromJson(reply.messageJson(), Msg.class)
                                .getTextContent());
                assertTrue(errors.isEmpty(), errors.toString());
                assertEquals(1, admission.turns(child.child(), 0, false, null, 10).size());
            }
        } finally {
            renewals.shutdownNow();
        }
    }

    private static void await(BooleanSupplier condition, Runnable pump) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            pump.run();
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean(), "Single-slot child pipeline did not make progress");
    }

    @Test
    void childCreationAndTurnAdmissionAreOneImmutableIntent() throws Exception {
        String task = call("spawn", specification);
        var first = children.start(root, task);
        assertEquals(first, children.start(root, task));
        assertNotEquals(root, first.child());
        assertEquals(root, first.root());
        assertEquals(actor, admission.turn(first.child(), first.childTurnId()).actor());
        assertEquals(
                "pending", admission.turn(first.child(), first.childTurnId()).deliveryStatus());
        var configuration = mapper.readTree(admission.session(first.child()).configuration());
        assertEquals(root.sessionId(), configuration.path("root_session_id").asText());
        assertEquals(root.sessionId(), configuration.path("parent_session_id").asText());
        assertEquals(
                "frozen child instruction",
                mapper.readTree(first.specification().frozenDefinition()).path("system").asText());
        assertEquals(first, children.parent(first.child()).orElseThrow());
        assertEquals(
                1,
                new JdbcTemplate(source)
                        .queryForObject(
                                "SELECT count(*) FROM ma.subagent_call WHERE parent_scope_key=?",
                                Integer.class,
                                root.messageGroup()));
        assertEquals(1, admission.turns(first.child(), 0, false, null, 10).size());
    }

    @Test
    void persistentChildReceivesNewTurnsWithoutLosingItsFrozenDefinition() throws Exception {
        String childId = UUID.randomUUID().toString();
        var firstSpec =
                new Specification(
                        "worker",
                        "first prompt",
                        "first header",
                        "{\"system\":\"frozen\"}",
                        childId);
        var first = children.start(root, call("spawn", firstSpec));
        activate(first);
        finish(first, "succeeded", true);
        var secondSpec =
                new Specification(
                        "worker",
                        "second prompt",
                        "second header",
                        firstSpec.frozenDefinition(),
                        childId);
        var second = children.start(root, call("send", secondSpec));
        assertEquals(first.child(), second.child());
        assertNotEquals(first.childTurnId(), second.childTurnId());
        assertEquals(2, admission.turns(first.child(), 0, false, null, 10).size());
        assertEquals(first, children.parent(first.child(), first.childTurnId()).orElseThrow());
        assertEquals(second, children.parent(first.child(), second.childTurnId()).orElseThrow());
        var changed =
                new Specification(
                        "worker", "changed", "changed", "{\"system\":\"different\"}", childId);
        String changedTask = call("changed", changed);
        assertEquals(
                "subagent_identity_conflict",
                assertThrows(ManagedStateException.class, () -> children.start(root, changedTask))
                        .code());
        assertEquals(2, admission.turns(first.child(), 0, false, null, 10).size());
    }

    @Test
    void childPayloadsUseBoundedContentLimitsInsteadOfIdentifierLimits() throws Exception {
        var large =
                new Specification(
                        "worker",
                        "任务".repeat(2000),
                        "header",
                        "{\"system\":\"" + "instruction".repeat(2000) + "\"}");
        var child = children.start(root, call("large-definition", large));
        assertEquals(large, child.specification());
        assertThrows(
                IllegalArgumentException.class,
                () -> new Specification("worker", "中".repeat(400_000), "header", "{}"));
    }

    @Test
    void concurrentToolWorkerInstancesPublishOnlyOneChildIdentity() throws Exception {
        String task = call("spawn", specification);
        var other =
                new ManagedSubagents(
                        source,
                        mapper,
                        ProjectionTestSupport.admission(source, mapper),
                        ProjectionTestSupport.journal(
                                source,
                                mapper,
                                new ManagedCheckpointObjects(mapper, managedCheckpointRoot)));
        var threads = Executors.newFixedThreadPool(2);
        try {
            var results =
                    threads.invokeAll(
                            List.of(
                                    (Callable<ManagedSubagents.Call>)
                                            () -> children.start(root, task),
                                    () -> other.start(root, task)));
            assertEquals(
                    results.get(0).get(5, TimeUnit.SECONDS),
                    results.get(1).get(5, TimeUnit.SECONDS));
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    void cancellationBeforeStartDurablyProvesNoChildWasLaunched() throws Exception {
        String task = call("spawn", specification);
        admission.acceptInput(root, turn, actor, "cancel", Type.CANCEL, null, "{}");
        var cancelled = children.start(root, task);
        assertEquals("cancelled", cancelled.status());
        assertNull(cancelled.child());
        assertNull(cancelled.childTurnId());
        assertEquals(cancelled, children.start(root, task));
        assertEquals(
                1,
                new JdbcTemplate(source)
                        .queryForObject(
                                "SELECT count(*) FROM ma.session WHERE tenant=? AND namespace=?",
                                Integer.class,
                                root.tenant(),
                                root.namespace()));
    }

    @Test
    void childDoesNotDeadlockOnItsParentsApplicationConcurrencySlot() throws Exception {
        admission.applicationPolicy(root, Map.of("version", 1, "maxConcurrent", 1));
        var child = children.start(root, call("spawn", specification));
        assertEquals("submitted", child.status());
        assertEquals(
                "subagent_turn_requires_parent",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        admission.acceptTurn(
                                                child.child(), actor, "direct-turn", "{}"))
                        .code());
        var other = new ManagedScope(root.tenant(), root.namespace(), UUID.randomUUID().toString());
        admission.createSession(
                other,
                "{\"owner_id\":\"original-user\",\"application_id\":\"" + root.sessionId() + "\"}");
        assertThrows(
                ManagedStateException.class,
                () -> admission.acceptTurn(other, actor, "other", "{}"));
        admission.applicationPolicy(root, Map.of("version", 2, "maxConcurrent", 2));
        assertEquals("accepted", admission.acceptTurn(other, actor, "other", "{}").status());
    }

    @Test
    void exhaustedRootCapacityRollsBackWithoutAnOrphanSession() throws Exception {
        children = new ManagedSubagents(source, mapper, admission, journal, 1);
        children.start(root, call("first", specification));
        String second = call("second", specification);
        assertEquals(
                "subagent_capacity_exceeded",
                assertThrows(ManagedStateException.class, () -> children.start(root, second))
                        .code());
        assertFalse(children.find(root, second).isPresent());
        assertEquals(
                2,
                new JdbcTemplate(source)
                        .queryForObject(
                                "SELECT count(*) FROM ma.session WHERE tenant=? AND namespace=?",
                                Integer.class,
                                root.tenant(),
                                root.namespace()));
    }

    @Test
    void concurrentRootsUseBestEffortApplicationQueueLimit() throws Exception {
        admission =
                new ManagedAdmissionStore(
                        source, mapper, new ManagedAdmissionStore.Limits(1, 128, 128, 100));
        children = new ManagedSubagents(source, mapper, admission, journal);
        var otherRoot =
                new ManagedScope(root.tenant(), root.namespace(), UUID.randomUUID().toString());
        admission.createSession(otherRoot, admission.session(root).configuration());
        String otherTurn = admission.acceptTurn(otherRoot, actor, "other", "{}").id();
        admission.reservePublication(otherRoot, otherTurn);
        admission.confirmPublication(otherRoot, otherTurn, "other-broker");
        admission.activate(otherRoot, otherTurn, 1, "other-brain");
        String first = call("first", specification);
        String second = call(otherRoot, otherTurn, "second", specification);
        var threads = Executors.newFixedThreadPool(2);
        try {
            var results =
                    threads.invokeAll(
                            List.of(
                                    (Callable<String>) () -> childAdmissionOutcome(root, first),
                                    () -> childAdmissionOutcome(otherRoot, second)));
            var outcomes = List.of(results.get(0).get(), results.get(1).get());
            long submitted = outcomes.stream().filter("submitted"::equals).count();
            assertTrue(submitted >= 1 && submitted <= 2);
            assertEquals(
                    2 - submitted, outcomes.stream().filter("admission_limited"::equals).count());
            assertEquals(
                    (int) (2 + submitted),
                    new JdbcTemplate(source)
                            .queryForObject(
                                    "SELECT COUNT(*) FROM ma.session WHERE tenant=? AND"
                                            + " namespace=?",
                                    Integer.class,
                                    root.tenant(),
                                    root.namespace()));
        } finally {
            threads.shutdownNow();
        }
    }

    private String childAdmissionOutcome(ManagedScope scope, String task) {
        try {
            return children.start(scope, task).status();
        } catch (ManagedStateException rejected) {
            return rejected.code();
        }
    }

    @Test
    void failedChildAdmissionRollsBackItsSessionAndParentLink() throws Exception {
        admission =
                new ManagedAdmissionStore(
                        source, mapper, new ManagedAdmissionStore.Limits(1, 128, 128, 100));
        children = new ManagedSubagents(source, mapper, admission, journal);
        String task = call("spawn", specification);
        admission.acceptTurn(root, actor, "fill-queue", "{}");
        Thread.sleep(1100); // Refresh the soft application queue snapshot.
        assertEquals(
                "admission_limited",
                assertThrows(ManagedStateException.class, () -> children.start(root, task)).code());
        assertTrue(children.find(root, task).isEmpty());
        assertEquals(
                1,
                new JdbcTemplate(source)
                        .queryForObject(
                                "SELECT count(*) FROM ma.session WHERE tenant=? AND namespace=?",
                                Integer.class,
                                root.tenant(),
                                root.namespace()));
    }

    @Test
    void replayCannotChangeFrozenChildInstructions() throws Exception {
        String task = call("spawn", specification);
        children.start(root, task);
        var changed =
                new Specification(
                        "worker",
                        "changed prompt",
                        "child result",
                        specification.frozenDefinition());
        new JdbcTemplate(source)
                .update(
                        "UPDATE ma.tool_task SET arguments=? WHERE scope_key=? AND task_id=?",
                        mapper.writeValueAsString(Map.of("subagent", changed)),
                        root.messageGroup(),
                        task);
        assertEquals(
                "subagent_content_conflict",
                assertThrows(ManagedStateException.class, () -> children.start(root, task)).code());
    }
}
