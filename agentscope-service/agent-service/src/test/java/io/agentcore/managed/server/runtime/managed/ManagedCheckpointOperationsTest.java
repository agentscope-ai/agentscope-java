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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentcore.managed.harness.agentscope.AgentScopeCheckpointCodec;
import io.agentcore.managed.server.control.SessionResolveResult;
import io.agentcore.managed.server.web.managed.EnvironmentDto;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.state.AgentState;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ManagedCheckpointOperationsTest {
    @TempDir private Path managedCheckpointRoot;

    private final ManagedScope sourceScope =
            new ManagedScope("t", "n", UUID.randomUUID().toString());
    private final ManagedScope targetScope =
            new ManagedScope("t", "n", UUID.randomUUID().toString());
    private final ObjectMapper mapper = new ObjectMapper();
    private ManagedAdmissionStore admission;
    private ManagedJournalStore journal;
    private ManagedCheckpointOperations operations;
    private DataSource source;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        source = database();
        ManagedAdmissionStore.initialize(source);
        jdbc = new JdbcTemplate(source);
        admission = ProjectionTestSupport.admission(source, mapper);
        var objects = spy(new ManagedCheckpointObjects(mapper, managedCheckpointRoot));
        doAnswer(
                        call -> {
                            assertFalse(
                                    TransactionSynchronizationManager.isActualTransactionActive(),
                                    "Checkpoint object read must precede DB row locks");
                            return call.callRealMethod();
                        })
                .when(objects)
                .read(any());
        doAnswer(
                        call -> {
                            assertFalse(
                                    TransactionSynchronizationManager.isActualTransactionActive(),
                                    "Checkpoint object writes must precede DB row locks");
                            return call.callRealMethod();
                        })
                .when(objects)
                .externalize(any(), anyLong(), any(), any());
        doAnswer(
                        call -> {
                            assertFalse(
                                    TransactionSynchronizationManager.isActualTransactionActive(),
                                    "Object verification must precede row locks");
                            return call.callRealMethod();
                        })
                .when(objects)
                .verify(any(), anyLong(), any());
        journal = ProjectionTestSupport.journal(source, mapper, objects);
        operations =
                new ManagedCheckpointOperations(
                        source, mapper, journal, new AgentScopeCheckpointCodec());
        admission.createSession(sourceScope, "{}");
        admission.createSession(targetScope, "{}");
    }

    @Test
    void validationSurvivesReplacementHoldsAdmissionAndNeedsAnExplicitTerminalStep()
            throws Exception {
        String checkpoint = seed(260, false, AgentState.builder().build().toJson());
        var command = operations.prepare(sourceScope, targetScope, "actor", "key", checkpoint);
        assertEquals(
                command, operations.prepare(sourceScope, targetScope, "actor", "key", checkpoint));
        assertEquals(
                "checkpoint_pending",
                assertThrows(
                                ManagedStateException.class,
                                () -> admission.acceptTurn(sourceScope, "actor", "new", "{}"))
                        .code());
        assertEquals(
                "checkpoint_pending",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        journal.files()
                                                .upload(
                                                        targetScope,
                                                        "actor",
                                                        "file",
                                                        "one.txt",
                                                        "text/plain",
                                                        new byte[] {1}))
                        .code());
        assertEquals(
                "checkpoint_pending",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        new ManagedLifecycles(source)
                                                .stage(sourceScope, "archived", "actor", "archive"))
                        .code());
        var first = operations.validate(sourceScope, command.id());
        assertEquals("validating", first.status());
        assertTrue(first.scanSequence() > 0 && first.scanSequence() < 260);
        var replacement =
                new ManagedCheckpointOperations(
                        source, mapper, journal, new AgentScopeCheckpointCodec());
        var second = replacement.validate(sourceScope, command.id());
        assertTrue(second.scanSequence() >= first.scanSequence());
        var complete = second;
        // Each page also has a time budget; slower PostgreSQL fixtures can need more pages.
        for (int page = 0; page < 20 && "validating".equals(complete.status()); page++) {
            var previous = complete;
            complete = replacement.validate(sourceScope, command.id());
            assertTrue(complete.scanSequence() >= previous.scanSequence());
        }
        assertEquals("validated", complete.status());
        assertFalse(complete.workspaceRequired());
        assertEquals(complete, replacement.validate(sourceScope, command.id()));
        assertEquals(
                "checkpoint_pending",
                assertThrows(
                                ManagedStateException.class,
                                () -> admission.acceptTurn(targetScope, "actor", "new", "{}"))
                        .code());
        assertEquals(
                "checkpoint_actor_differs",
                assertThrows(
                                ManagedStateException.class,
                                () -> replacement.cancel(sourceScope, command.id(), "other"))
                        .code());
        assertEquals("cancelled", replacement.cancel(sourceScope, command.id(), "actor").status());
        assertEquals("cancelled", replacement.cancel(sourceScope, command.id(), "actor").status());
        assertEquals("accepted", admission.acceptTurn(targetScope, "actor", "new", "{}").status());
    }

    @Test
    void aSettledCurrentHeadDoesNotMakeAnEarlierUncertainCheckpointSafe() throws Exception {
        String checkpoint = seed(1, true, AgentState.builder().build().toJson());
        var command = operations.prepare(sourceScope, targetScope, "actor", "key", checkpoint);
        var result = operations.validate(sourceScope, command.id());
        assertEquals("rejected", result.status());
        assertEquals("checkpoint_has_unresolved_effects", result.errorCode());
        assertEquals(
                0L,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.checkpoint_hold WHERE operation_scope=?",
                        Long.class,
                        sourceScope.messageGroup()));
        assertEquals("accepted", admission.acceptTurn(targetScope, "actor", "next", "{}").status());
    }

    @Test
    void unmatchedToolUseInAgentStateCannotBeForkedEvenBeforeNativeDispatch() throws Exception {
        var state = AgentState.builder().build();
        state.contextMutable()
                .add(
                        AssistantMessage.builder()
                                .content(
                                        ToolUseBlock.builder()
                                                .id("call")
                                                .name("tool")
                                                .input(Map.of())
                                                .build())
                                .build());
        String checkpoint = seed(1, false, state.toJson());
        var command = operations.prepare(sourceScope, targetScope, "actor", "key", checkpoint);
        assertEquals(
                "checkpoint_state_unsettled",
                operations.validate(sourceScope, command.id()).errorCode());
    }

    @Test
    void mismatchedContentAndChangingPrefixCannotAdvanceTheCommand() throws Exception {
        String checkpoint = seed(1, false, AgentState.builder().build().toJson());
        var command = operations.prepare(sourceScope, targetScope, "actor", "key", checkpoint);
        assertEquals(
                "checkpoint_content_conflict",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        operations.prepare(
                                                sourceScope,
                                                sourceScope,
                                                "actor",
                                                "key",
                                                checkpoint))
                        .code());
        jdbc.update(
                "UPDATE ma.session SET native_sequence=native_sequence+1 WHERE scope_key=?",
                sourceScope.messageGroup());
        assertEquals(
                "native_prefix_changed",
                operations.validate(sourceScope, command.id()).errorCode());
        var other = new ManagedScope("other", targetScope.namespace(), targetScope.sessionId());
        assertEquals(
                "checkpoint_scope_differs",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        operations.prepare(
                                                sourceScope, other, "actor", "other", checkpoint))
                        .code());
    }

    @Test
    void forkCommitsAnIndependentBaselineAndOnlyTheCapturedFiles() throws Exception {
        var file =
                journal.files()
                        .upload(
                                sourceScope,
                                "actor",
                                "before",
                                "before.txt",
                                "text/plain",
                                new byte[] {1, 2});
        String checkpoint = seed(1, false, AgentState.builder().build().toJson());
        journal.files()
                .upload(sourceScope, "actor", "after", "after.txt", "text/plain", new byte[] {3});
        var command = operations.prepare(sourceScope, targetScope, "actor", "fork", checkpoint);
        assertEquals("validated", operations.validate(sourceScope, command.id()).status());
        assertEquals(
                "completed",
                operations
                        .commitBaseline(sourceScope, command.id(), "actor", "target-owner")
                        .status());
        assertEquals(
                "completed",
                operations
                        .commitBaseline(sourceScope, command.id(), "actor", "target-owner")
                        .status());
        assertEquals(1, journal.head(targetScope).sequence());
        var restored =
                mapper.readTree(journal.objects().state(journal.head(targetScope).checkpoint()));
        assertEquals(targetScope.sessionId(), restored.get("session_id").asText());
        assertEquals("target-owner", restored.get("user_id").asText());
        assertEquals(1, journal.recoveryEvents(targetScope, 0, 10).size());
        assertEquals(
                "migration/baseline", journal.recoveryEvents(targetScope, 0, 10).get(0).type());
        assertEquals(1, ((List<?>) journal.files().list(targetScope, 0, 100).get("data")).size());
        jdbc.update(
                "UPDATE ma.file SET content=NULL WHERE scope_key=?", sourceScope.messageGroup());
        assertArrayEquals(
                new byte[] {1, 2},
                journal.files().content(targetScope, (String) file.get("file_id")));
        assertEquals(
                1,
                ProjectionTestSupport.project(journal).publicEvents(targetScope, 0, 10).stream()
                        .filter(event -> event.type().equals("session.forked"))
                        .count());
        assertEquals("accepted", admission.acceptTurn(targetScope, "actor", "new", "{}").status());
    }

    @Test
    void restoreAppendsRatherThanRewindsHistoryAndCannotSkipWorkspaceConfirmation()
            throws Exception {
        String checkpoint = seed(2, false, AgentState.builder().build().toJson());
        var command = operations.prepare(sourceScope, sourceScope, "actor", "restore", checkpoint);
        operations.validate(sourceScope, command.id());
        assertEquals(
                "completed",
                operations.commitBaseline(sourceScope, command.id(), "actor", "owner").status());
        assertEquals(3, journal.head(sourceScope).sequence());
        assertEquals("state/restored", journal.recoveryEvents(sourceScope, 2, 1).get(0).type());
        var second = operations.prepare(sourceScope, targetScope, "actor", "fork", checkpoint);
        operations.validate(sourceScope, second.id());
        recordWorkspaceUse(sourceScope);
        assertEquals(
                "workspace_snapshot_required",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        operations.commitBaseline(
                                                sourceScope, second.id(), "actor", "owner"))
                        .code());
        assertEquals("validated", operations.get(sourceScope, second.id()).status());
        assertEquals(0, journal.head(targetScope).sequence());
        operations.cancel(sourceScope, second.id(), "actor");
    }

    @Test
    void failedPublicCommitRollsBackFilesBaselineIndexAndReceipt() throws Exception {
        journal.files()
                .upload(sourceScope, "actor", "file", "one.txt", "text/plain", new byte[] {1});
        String checkpoint = seed(1, false, AgentState.builder().build().toJson());
        var failing = spy(journal);
        var writer =
                new ManagedCheckpointOperations(
                        source, mapper, failing, new AgentScopeCheckpointCodec());
        var command = writer.prepare(sourceScope, targetScope, "actor", "fork", checkpoint);
        writer.validate(sourceScope, command.id());
        doThrow(new IllegalStateException("public fact unavailable"))
                .when(failing)
                .resourceEvent(any(), anyString(), anyString(), any());
        assertThrows(
                IllegalStateException.class,
                () -> writer.commitBaseline(sourceScope, command.id(), "actor", "owner"));
        assertEquals("validated", writer.get(sourceScope, command.id()).status());
        assertEquals(0, journal.head(targetScope).sequence());
        assertEquals(0, ((List<?>) journal.files().list(targetScope, 0, 100).get("data")).size());
        assertEquals(
                0,
                ((List<?>) journal.checkpoints().list(targetScope, null, null, 10).get("data"))
                        .size());
        assertEquals(
                "completed",
                operations.commitBaseline(sourceScope, command.id(), "actor", "owner").status());
    }

    private void recordWorkspaceUse(ManagedScope scope) {
        new JdbcTemplate(source)
                .update(
                        "INSERT INTO"
                            + " ma.tool_task(scope_key,task_id,turn_id,tool_call_id,execution_id,tool_name,arguments,checkpoint_sequence,status,result,result_hash)"
                            + " SELECT"
                            + " scope_key,'workspace-seed',turn_id,'workspace-seed','workspace-seed','fs.upload','{}',0,'succeeded','{}','seed'"
                            + " FROM ma.turn WHERE scope_key=? ORDER BY admission_sequence LIMIT 1"
                            + " ON CONFLICT DO NOTHING",
                        scope.messageGroup());
    }

    protected DataSource database() {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:checkpoint-ops-"
                        + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
    }

    @Test
    void workspaceImportKeepsAdmissionHeldAcrossReplacementAndCommitsAnIndependentImage()
            throws Exception {
        String checkpoint = seed(1, false, AgentState.builder().build().toJson());
        recordWorkspaceUse(sourceScope);
        var command =
                operations.prepare(sourceScope, targetScope, "actor", "workspace", checkpoint);
        operations.validate(sourceScope, command.id());
        var snapshot = operations.workspaceTask(sourceScope, command.id());
        assertEquals("snapshot", snapshot.phase());
        assertTrue(operations.startWorkspaceTask(snapshot));
        assertFalse(operations.startWorkspaceTask(snapshot));
        String image = image();
        assertEquals(
                "validated",
                operations
                        .workspaceResult(
                                snapshot,
                                new ManagedToolExecutor.Outcome(
                                        ManagedToolExecutor.State.SUCCEEDED, image))
                        .status());
        assertEquals(
                true,
                journal.checkpoints().metadata(sourceScope, checkpoint).get("workspace_available"));
        var replacement =
                new ManagedCheckpointOperations(
                        source, mapper, journal, new AgentScopeCheckpointCodec());
        var restore = replacement.workspaceTask(sourceScope, command.id());
        assertEquals("restore", restore.phase());
        assertEquals("restoring", replacement.cancel(sourceScope, command.id(), "actor").status());
        assertTrue(replacement.startWorkspaceTask(restore));
        assertEquals(
                "restoring",
                replacement
                        .workspaceResult(
                                restore,
                                new ManagedToolExecutor.Outcome(
                                        ManagedToolExecutor.State.UNKNOWN, null))
                        .status());
        assertEquals("unknown", operations.workspaceTask(sourceScope, command.id()).status());
        assertFalse(operations.startWorkspaceTask(restore));
        assertEquals(
                "checkpoint_pending",
                assertThrows(
                                ManagedStateException.class,
                                () -> admission.acceptTurn(targetScope, "actor", "blocked", "{}"))
                        .code());
        var node = mapper.readTree(image);
        String confirmed =
                mapper.writeValueAsString(
                        Map.of("sha256", node.path("sha256"), "manifest", node.path("manifest")));
        assertEquals(
                "workspace_ready",
                operations
                        .workspaceResult(
                                restore,
                                new ManagedToolExecutor.Outcome(
                                        ManagedToolExecutor.State.SUCCEEDED, confirmed))
                        .status());
        assertEquals(
                "completed",
                replacement.commitBaseline(sourceScope, command.id(), "actor", "owner").status());
        assertEquals(
                2L,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.checkpoint_workspace WHERE scope_key IN (?,?)",
                        Long.class,
                        sourceScope.messageGroup(),
                        targetScope.messageGroup()));
        byte[] original =
                jdbc.queryForObject(
                        "SELECT content FROM ma.checkpoint_workspace WHERE scope_key=?",
                        byte[].class,
                        targetScope.messageGroup());
        jdbc.update(
                "UPDATE ma.checkpoint_workspace SET content=? WHERE scope_key=?",
                new byte[] {0},
                sourceScope.messageGroup());
        assertArrayEquals(
                original,
                jdbc.queryForObject(
                        "SELECT content FROM ma.checkpoint_workspace WHERE scope_key=?",
                        byte[].class,
                        targetScope.messageGroup()));
        assertEquals("accepted", admission.acceptTurn(targetScope, "actor", "next", "{}").status());
    }

    @Test
    void historicalCheckpointNeverReceivesTheCurrentDirectoryAfterAToolExecution()
            throws Exception {
        String checkpoint = seed(1, false, AgentState.builder().build().toJson());
        recordWorkspaceUse(sourceScope);
        laterToolFacts();
        var command = operations.prepare(sourceScope, targetScope, "actor", "old", checkpoint);
        assertEquals("validated", operations.validate(sourceScope, command.id()).status());
        assertEquals(null, operations.workspaceTask(sourceScope, command.id()));
        assertEquals(
                "checkpoint_workspace_unavailable",
                operations.get(sourceScope, command.id()).errorCode());
        assertEquals("accepted", admission.acceptTurn(targetScope, "actor", "next", "{}").status());
    }

    @Test
    void anExistingHistoricalImageIsReusedEvenAfterLaterToolsChangedTheWorkspace()
            throws Exception {
        String checkpoint = seed(1, false, AgentState.builder().build().toJson());
        recordWorkspaceUse(sourceScope);
        var first = operations.prepare(sourceScope, targetScope, "actor", "first", checkpoint);
        operations.validate(sourceScope, first.id());
        var capture = operations.workspaceTask(sourceScope, first.id());
        operations.startWorkspaceTask(capture);
        String original = image();
        operations.workspaceResult(
                capture,
                new ManagedToolExecutor.Outcome(ManagedToolExecutor.State.SUCCEEDED, original));
        operations.cancel(sourceScope, first.id(), "actor");
        laterToolFacts();
        var second = operations.prepare(sourceScope, targetScope, "actor", "second", checkpoint);
        operations.validate(sourceScope, second.id());
        var restore = operations.workspaceTask(sourceScope, second.id());
        assertEquals("restore", restore.phase());
        assertEquals(
                mapper.readTree(original),
                mapper.readTree(restore.task().arguments()).path("snapshot"));
    }

    @Test
    void cancelledCaptureDiscardsLateResultsAndInvalidImagesCannotAdvance() throws Exception {
        String checkpoint = seed(1, false, AgentState.builder().build().toJson());
        recordWorkspaceUse(sourceScope);
        var command = operations.prepare(sourceScope, targetScope, "actor", "bad", checkpoint);
        operations.validate(sourceScope, command.id());
        var capture = operations.workspaceTask(sourceScope, command.id());
        operations.startWorkspaceTask(capture);
        var invalid = mapper.readTree(image());
        ((ObjectNode) invalid).put("sha256", "bad");
        assertEquals(
                "workspace_image_invalid",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        operations.workspaceResult(
                                                capture,
                                                new ManagedToolExecutor.Outcome(
                                                        ManagedToolExecutor.State.SUCCEEDED,
                                                        invalid.toString())))
                        .code());
        assertEquals("snapshotting", operations.get(sourceScope, command.id()).status());
        assertEquals("cancelled", operations.cancel(sourceScope, command.id(), "actor").status());
        assertEquals(
                "cancelled",
                operations
                        .workspaceResult(
                                capture,
                                new ManagedToolExecutor.Outcome(
                                        ManagedToolExecutor.State.SUCCEEDED, image()))
                        .status());
        assertEquals(
                false,
                journal.checkpoints().metadata(sourceScope, checkpoint).get("workspace_available"));
        assertEquals("accepted", admission.acceptTurn(sourceScope, "actor", "next", "{}").status());
    }

    @Test
    void agentComparisonUsesTheResolvedDefinitionRatherThanMissingEncryptedFields()
            throws Exception {
        String checkpoint = seed(1, false, AgentState.builder().build().toJson());
        var resolved =
                new ManagedCheckpointOperations(
                        source,
                        mapper,
                        journal,
                        new AgentScopeCheckpointCodec(),
                        scope ->
                                definition(
                                        scope.equals(sourceScope) ? "source" : "different", null));
        assertEquals(
                "agent_definition_differs",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        resolved.prepare(
                                                sourceScope,
                                                targetScope,
                                                "actor",
                                                "different",
                                                checkpoint))
                        .code());
    }

    @Test
    void existingSelfHostedWorkspaceIsRequiredWithoutPhysicalBindingsOrToolHistory()
            throws Exception {
        String checkpoint = seed(1, false, AgentState.builder().build().toJson());
        var coordinator =
                new ManagedCheckpointOperations(
                        source,
                        mapper,
                        journal,
                        new AgentScopeCheckpointCodec(),
                        scope -> definition("fixed", scope.sessionId()));
        var command =
                coordinator.prepare(sourceScope, targetScope, "actor", "existing", checkpoint);
        assertTrue(command.workspaceRequired());
        coordinator.validate(sourceScope, command.id());
        assertEquals("snapshot", coordinator.workspaceTask(sourceScope, command.id()).phase());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM ma.hands_sandbox WHERE scope_key IN (?,?)",
                        Integer.class,
                        sourceScope.messageGroup(),
                        targetScope.messageGroup()));
        assertEquals(0, journal.head(targetScope).sequence());
    }

    @Test
    void forkCannotOverwriteItsSourceInstanceAndMissingBindingCannotBypassWorkspaceChecks()
            throws Exception {
        String checkpoint = seed(1, false, AgentState.builder().build().toJson());
        var same =
                new ManagedCheckpointOperations(
                        source,
                        mapper,
                        journal,
                        new AgentScopeCheckpointCodec(),
                        scope -> definition("fixed", "same"));
        assertEquals(
                "borrowed_sandbox_busy",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        same.prepare(
                                                sourceScope,
                                                targetScope,
                                                "actor",
                                                "same",
                                                checkpoint))
                        .code());
        var missing =
                new ManagedCheckpointOperations(
                        source,
                        mapper,
                        journal,
                        new AgentScopeCheckpointCodec(),
                        scope -> definition("fixed", ""));
        assertEquals(
                "sandbox_binding_required",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        missing.prepare(
                                                sourceScope,
                                                targetScope,
                                                "actor",
                                                "missing",
                                                checkpoint))
                        .code());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM ma.checkpoint_hold WHERE scope_key IN (?,?)",
                        Integer.class,
                        sourceScope.messageGroup(),
                        targetScope.messageGroup()));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM ma.hands_sandbox WHERE scope_key IN (?,?)",
                        Integer.class,
                        sourceScope.messageGroup(),
                        targetScope.messageGroup()));
    }

    private SessionResolveResult definition(String name, String box) {
        var environment =
                box == null
                        ? null
                        : new EnvironmentDto(
                                "env",
                                "env",
                                "self_hosted",
                                Map.of("sandboxId", box),
                                "owner",
                                null,
                                1,
                                1,
                                null);
        return new SessionResolveResult(
                null,
                Map.of("name", name),
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

    private String image() throws Exception {
        byte[] content = new byte[] {1, 2, 3};
        return mapper.writeValueAsString(
                Map.of(
                        "content",
                        Base64.getEncoder().encodeToString(content),
                        "sha256",
                        HexFormat.of()
                                .formatHex(MessageDigest.getInstance("SHA-256").digest(content)),
                        "manifest",
                        Map.of()));
    }

    @Test
    void lateResultsKeepTheirOriginalExecutionAfterTheOperationAdvances() throws Exception {
        String checkpoint = seed(1, false, AgentState.builder().build().toJson());
        recordWorkspaceUse(sourceScope);
        var command = operations.prepare(sourceScope, targetScope, "actor", "external", checkpoint);
        operations.validate(sourceScope, command.id());
        var snapshot = operations.workspaceTask(sourceScope, command.id());
        operations.startWorkspaceTask(snapshot);
        String image = image();
        operations.workspaceResult(
                snapshot,
                new ManagedToolExecutor.Outcome(ManagedToolExecutor.State.SUCCEEDED, image));
        var restore = operations.workspaceTask(sourceScope, command.id());
        assertEquals("restore", restore.phase());
        assertEquals("{}", snapshot.task().arguments());
        assertEquals(
                "restoring",
                operations
                        .workspaceResult(
                                snapshot,
                                new ManagedToolExecutor.Outcome(
                                        ManagedToolExecutor.State.SUCCEEDED, image()))
                        .status());
        assertTrue(operations.startWorkspaceTask(restore));
        assertEquals("started", operations.workspaceTask(sourceScope, command.id()).status());
    }

    @Test
    void frameworkFilesystemEffectsAlsoPreventAttachingTheCurrentDirectoryToAnOldCheckpoint()
            throws Exception {
        String checkpoint = seed(1, false, AgentState.builder().build().toJson());
        recordWorkspaceUse(sourceScope);
        String turn = admission.turns(sourceScope, 0, false, null, 1).get(0).id();
        jdbc.update(
                "INSERT INTO"
                    + " ma.tool_task(scope_key,task_id,turn_id,tool_call_id,execution_id,tool_name,arguments,checkpoint_sequence,status,result)"
                    + " VALUES(?,?,?,?,?,'fs.upload','{}',1,'succeeded','{}')",
                sourceScope.messageGroup(),
                "framework",
                turn,
                "framework",
                "framework");
        var command =
                operations.prepare(sourceScope, targetScope, "actor", "framework", checkpoint);
        operations.validate(sourceScope, command.id());
        assertEquals(null, operations.workspaceTask(sourceScope, command.id()));
        assertEquals(
                "checkpoint_workspace_unavailable",
                operations.get(sourceScope, command.id()).errorCode());
    }

    @Test
    void filesystemIntentRequiresAWorkspaceImageWithoutReadingPhysicalToolWorkerBindings()
            throws Exception {
        seed(2, false, AgentState.builder().build().toJson());
        String checkpoint =
                jdbc.queryForObject(
                        "SELECT checkpoint_id FROM ma.state_checkpoint WHERE scope_key=? AND"
                                + " native_sequence=2",
                        String.class,
                        sourceScope.messageGroup());
        String turn = admission.turns(sourceScope, 0, false, null, 1).get(0).id();
        jdbc.update(
                "INSERT INTO"
                    + " ma.tool_task(scope_key,task_id,turn_id,tool_call_id,execution_id,tool_name,arguments,checkpoint_sequence,status,result)"
                    + " VALUES(?,?,?,?,?,'fs.upload','{}',1,'succeeded','{}')",
                sourceScope.messageGroup(),
                "filesystem",
                turn,
                "filesystem",
                "filesystem");
        var command =
                operations.prepare(sourceScope, targetScope, "actor", "filesystem", checkpoint);
        assertTrue(command.workspaceRequired());
        operations.validate(sourceScope, command.id());
        var snapshot = operations.workspaceTask(sourceScope, command.id());
        assertEquals("workspace.snapshot", snapshot.task().name());
        assertEquals(sourceScope, snapshot.sandbox());
        assertEquals(
                0, jdbc.queryForObject("SELECT COUNT(*) FROM ma.hands_sandbox", Integer.class));
    }

    private void laterToolFacts() throws Exception {
        var facts =
                List.of(
                        event(2, "settled", "tool/dispatch", Map.of("toolCallId", "later")),
                        event(
                                3,
                                "settled",
                                "action/end",
                                Map.of("toolCallId", "later", "result", Map.of("ok", true))));
        var segment = journal.stageNative(sourceScope, facts);
        journal.registerRecoveryImport(sourceScope, "fixture-later", 1, facts, segment);
        jdbc.update(
                "UPDATE ma.session SET native_sequence=3 WHERE scope_key=?",
                sourceScope.messageGroup());
    }

    @Test
    void unsupportedKernelCannotAcquireCheckpointHolds() throws Exception {
        String checkpoint = seed(1, false, AgentState.builder().build().toJson());
        jdbc.update(
                "INSERT INTO ma.harness_binding(scope_key,harness_type) VALUES(?,?)",
                sourceScope.messageGroup(),
                "pi");
        assertEquals(
                "checkpoint_harness_unsupported",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        operations.prepare(
                                                sourceScope,
                                                targetScope,
                                                "actor",
                                                "pi",
                                                checkpoint))
                        .code());
        assertEquals(
                0, jdbc.queryForObject("SELECT COUNT(*) FROM ma.checkpoint_hold", Integer.class));
    }

    @Test
    void checkpointValidationAndForkIncludeFinalizationRecords() throws Exception {
        String checkpoint = seed(1, false, AgentState.builder().build().toJson(), true);
        var command =
                operations.prepare(sourceScope, targetScope, "actor", "finalized", checkpoint);
        assertEquals("validated", operations.validate(sourceScope, command.id()).status());
        assertEquals(
                "completed",
                operations.commitBaseline(sourceScope, command.id(), "actor", "owner").status());
    }

    private String seed(int checkpoints, boolean uncertain, String state) throws Exception {
        return seed(checkpoints, uncertain, state, false);
    }

    private String seed(int checkpoints, boolean uncertain, String state, boolean finalized)
            throws Exception {
        String turn = admission.acceptTurn(sourceScope, "actor", "turn", "{}").id();
        admission.reservePublication(sourceScope, turn);
        admission.confirmPublication(sourceScope, turn, "broker");
        admission.activate(sourceScope, turn, 1, "token");
        admission.closeInputs(sourceScope, turn, 1);
        var events = new ArrayList<SessionEvent>();
        if (finalized) {
            events.add(
                    event(
                            1,
                            turn,
                            "managed/finalization_started",
                            Map.of(
                                    "id",
                                    "frame",
                                    "startedAt",
                                    1L,
                                    "zone",
                                    "UTC",
                                    "result",
                                    Map.of(),
                                    "stateJson",
                                    state)));
            events.add(event(2, turn, "managed/finalization_completed", Map.of("id", "frame")));
        }
        if (uncertain)
            events.add(
                    event(events.size() + 1, turn, "tool/dispatch", Map.of("toolCallId", "call")));
        for (int i = 0; i < checkpoints; i++)
            events.add(
                    event(
                            events.size() + 1,
                            turn,
                            "state/checkpoint",
                            Map.of("projectorVersion", 1, "stateJson", state)));
        if (uncertain)
            events.add(
                    event(
                            events.size() + 1,
                            turn,
                            "action/end",
                            Map.of(
                                    "toolCallId",
                                    "call",
                                    "result",
                                    Map.of(
                                            "type",
                                            "tool_result",
                                            "id",
                                            "call",
                                            "content",
                                            List.of()))));
        AgentScopePreparation.commit(
                journal,
                new SessionCommitTransactions(journal),
                AgentScopeCheckpoints.prepare(
                        journal.objects(),
                        new ManagedRuntimeBatch(
                                sourceScope,
                                turn,
                                1,
                                "token",
                                "writer",
                                "seed",
                                0,
                                events,
                                List.of(),
                                List.of(),
                                "succeeded")));
        var page = journal.checkpoints().list(sourceScope, null, null, 100);
        return (String) ((Map<?, ?>) ((List<?>) page.get("data")).get(0)).get("checkpoint_id");
    }

    private SessionEvent event(long sequence, String turn, String type, Map<String, Object> data)
            throws Exception {
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
}
