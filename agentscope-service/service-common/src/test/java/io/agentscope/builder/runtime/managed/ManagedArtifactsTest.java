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
package io.agentscope.builder.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.builder.runtime.managed.ManagedRuntimeBatch.ToolIntent;
import io.agentscope.core.session.SessionEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ManagedArtifactsTest {
    @TempDir private Path managedCheckpointRoot;

    private final ManagedScope scope = new ManagedScope("t", "n", "artifacts-" + UUID.randomUUID());
    private ManagedAdmissionStore admission;
    private ManagedJournalStore journal;
    private ManagedArtifacts artifacts;
    private JdbcTemplate jdbc;

    protected DataSource database() {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:artifacts-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
    }

    @BeforeEach
    void setup() {
        var source = database();
        ManagedAdmissionStore.initialize(source);
        var mapper = new ObjectMapper();
        admission = new ManagedAdmissionStore(source, mapper);
        journal =
                new ManagedJournalStore(
                        source,
                        mapper,
                        new ManagedCheckpointObjects(mapper, managedCheckpointRoot));
        artifacts = journal.artifacts();
        jdbc = new JdbcTemplate(source);
        admission.createSession(scope, "{}");
    }

    @Test
    void publicationAndDeletionShareSnapshotsAndDoNotAlterNativeRecovery() {
        String file =
                (String)
                        journal.files()
                                .upload(
                                        scope,
                                        "actor",
                                        "file",
                                        "report.txt",
                                        "text/plain",
                                        "report".getBytes(StandardCharsets.UTF_8))
                                .get("file_id");
        String turn = admission.acceptTurn(scope, "actor", "turn", "{}").id();
        var input = Map.<String, Object>of("file_id", file, "turn_id", turn);
        var published = artifacts.publish(scope, "actor", "publish", input);
        String id = (String) published.get("artifact_id");
        assertEquals(published, artifacts.publish(scope, "actor", "publish", input));
        assertEquals(2, journal.publicHead(scope));
        var snapshot = journal.resources().snapshot(scope, turn);
        assertEquals(id, artifacts.metadata(scope, id, snapshot.id()).get("artifact_id"));
        assertEquals(
                1,
                journal.resources()
                        .page(scope, snapshot.id(), "artifacts", null, 10)
                        .data()
                        .size());
        artifacts.delete(scope, id);
        artifacts.delete(scope, id);
        assertEquals(3, journal.publicHead(scope));
        assertThrows(ManagedStateException.class, () -> artifacts.metadata(scope, id, null));
        assertEquals("published", artifacts.metadata(scope, id, snapshot.id()).get("status"));
        assertEquals("deleted", artifacts.publish(scope, "actor", "publish", input).get("status"));
        assertEquals(0, journal.head(scope).sequence());
        assertTrue(journal.nativeEvents(scope, 0, 10).isEmpty());
    }

    @Test
    void callerScopeAndImmutableContentAreCheckedAndExternalUrisAreMetadata() {
        var input = Map.<String, Object>of("name", "report", "uri", "https://example.org/report");
        var first = artifacts.publish(scope, "first", "publish", input);
        var second = artifacts.publish(scope, "second", "publish", input);
        assertNotEquals(first.get("artifact_id"), second.get("artifact_id"));
        assertThrows(
                ManagedStateException.class,
                () ->
                        artifacts.publish(
                                scope,
                                "first",
                                "publish",
                                Map.of("name", "changed", "uri", "https://example.org/report")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        artifacts.publish(
                                scope,
                                "actor",
                                "credentials",
                                Map.of(
                                        "name",
                                        "report",
                                        "uri",
                                        "https://secret@example.org/report")));
        assertThrows(
                ManagedStateException.class,
                () ->
                        artifacts.publish(
                                scope,
                                "actor",
                                "wrong-turn",
                                Map.of(
                                        "name",
                                        "report",
                                        "uri",
                                        "https://example.org/report",
                                        "turn_id",
                                        "missing")));
        var other = new ManagedScope("different-tenant", "n", scope.sessionId());
        admission.createSession(other, "{}");
        assertThrows(
                ManagedStateException.class,
                () -> artifacts.metadata(other, (String) first.get("artifact_id"), null));
        assertThrows(
                ManagedStateException.class,
                () ->
                        artifacts.metadata(
                                other,
                                (String) first.get("artifact_id"),
                                journal.resources().snapshot(scope).id()));
    }

    @Test
    void eventFailureRollsBackArtifactAndClosureStopsPublication() {
        String constraint = "deny_artifact_event";
        jdbc.execute(
                "ALTER TABLE ma.public_event ADD CONSTRAINT "
                        + constraint
                        + " CHECK(type <> 'artifact.published' OR scope_key <> '"
                        + scope.messageGroup()
                        + "')");
        try {
            assertThrows(
                    RuntimeException.class,
                    () ->
                            artifacts.publish(
                                    scope,
                                    "actor",
                                    "fail",
                                    Map.of("name", "report", "uri", "https://example.org/report")));
        } finally {
            jdbc.execute("ALTER TABLE ma.public_event DROP CONSTRAINT " + constraint);
        }
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.artifact WHERE scope_key=?",
                        Long.class,
                        scope.messageGroup()));
        assertEquals(0, journal.publicHead(scope));
        new ManagedLifecycles(jdbc.getDataSource()).stage(scope, "archived", "actor", "archive");
        assertEquals(
                "session_inactive",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        artifacts.publish(
                                                scope,
                                                "actor",
                                                "new",
                                                Map.of(
                                                        "name",
                                                        "report",
                                                        "uri",
                                                        "https://example.org/report")))
                        .code());
    }

    @Test
    void generatedBytesPublishOnlyAtToolCommitAndReplayReturnsMetadata() throws Exception {
        byte[] bytes = new byte[2 * 1024 * 1024];
        new Random(72).nextBytes(bytes);
        var result = generated(bytes);
        journal.toolEvents()
                .open(scope, journal.tool(scope, result.taskId()).orElseThrow(), "hands", false);
        journal.stageToolResult(result);
        assertEquals(0, count("file"));
        assertEquals(0, count("artifact"));
        journal.toolEvents().finish(result);
        var event =
                journal.toolEvents()
                        .publication(scope, result.taskId(), Long.MAX_VALUE)
                        .orElseThrow();
        var committed = journal.commitToolEvent(event);
        assertEquals(1, committed.results().size());
        var delivery = committed.results().get(0);
        var metadata = new ObjectMapper().readTree(delivery.result());
        assertFalse(metadata.has("content"));
        assertTrue(metadata.path("artifact_id").asText().startsWith("artifact_"));
        assertEquals(result.scope(), delivery.scope());
        assertEquals("succeeded", delivery.status());
        assertEquals(metadata.path("sha256").asText(), ManagedFiles.sha256(bytes));
        assertArrayEquals(bytes, journal.files().content(scope, metadata.path("file_id").asText()));
        assertEquals(1, count("artifact"));
        assertEquals(1, count("file"));
        long publicHead = journal.publicHead(scope);
        journal.commitToolEvent(event);
        journal.commitToolResult(result);
        assertEquals(publicHead, journal.publicHead(scope));
        var relayed =
                journal.resultsForDelivery("", "", 256).stream()
                        .filter(value -> value.scope().equals(scope))
                        .findFirst()
                        .orElseThrow();
        assertEquals(delivery, relayed);
        assertEquals(result, journal.stagedToolResult(scope, result.taskId()).orElseThrow());
        assertTrue(
                journal.publicEvents(scope, 0, 100).stream()
                        .noneMatch(
                                value ->
                                        value.payload()
                                                .contains(
                                                        Base64.getEncoder()
                                                                .encodeToString(bytes))));
    }

    @Test
    void toolEventFailureRollsBackFileArtifactAndResultTogether() throws Exception {
        var result = generated(new byte[] {1, 2, 3});
        journal.stageToolResult(result);
        String constraint = "deny_generated_artifact";
        jdbc.execute(
                "ALTER TABLE ma.public_event ADD CONSTRAINT "
                        + constraint
                        + " CHECK(type <> 'artifact.published' OR scope_key <> '"
                        + scope.messageGroup()
                        + "')");
        try {
            assertThrows(RuntimeException.class, () -> journal.commitToolResult(result));
            assertEquals(0, count("file"));
            assertEquals(0, count("artifact"));
            assertEquals(null, journal.tool(scope, result.taskId()).orElseThrow().result());
            assertEquals(
                    null,
                    jdbc.queryForObject(
                            "SELECT result_hash FROM ma.tool_task WHERE scope_key=? AND"
                                    + " task_id=?",
                            String.class,
                            scope.messageGroup(),
                            result.taskId()));
        } finally {
            jdbc.execute("ALTER TABLE ma.public_event DROP CONSTRAINT " + constraint);
        }
        journal.commitToolResult(result);
        assertEquals(1, count("file"));
        assertEquals(1, count("artifact"));
    }

    @Test
    void artifactQuotaRejectsExportWithoutOrphanFileOrStrandedTool() throws Exception {
        for (int index = 0; index < 256; index++)
            jdbc.update(
                    "INSERT INTO ma.artifact(scope_key,artifact_id,input_digest,status,data)"
                            + " VALUES(?,?,?,'published','{}')",
                    scope.messageGroup(),
                    "existing-" + index,
                    "fixture");
        var result = generated(new byte[] {3, 2, 1});
        journal.stageToolResult(result);
        journal.commitToolResult(result);
        var task = journal.tool(scope, result.taskId()).orElseThrow();
        assertEquals("failed", task.status());
        assertEquals(
                "artifact_quota_exceeded",
                new ObjectMapper().readTree(task.result()).path("error").asText());
        assertEquals(0, count("file"));
        assertEquals(256, count("artifact"));
        assertEquals(
                ManagedScope.fingerprint(
                        new ObjectMapper()
                                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                                .writeValueAsString(result)),
                jdbc.queryForObject(
                        "SELECT result_hash FROM ma.tool_task WHERE scope_key=? AND" + " task_id=?",
                        String.class,
                        scope.messageGroup(),
                        result.taskId()));
        journal.commitToolResult(result);
    }

    @Test
    void corruptExportIsSettledWithoutPublishingBytes() throws Exception {
        var original = generated(new byte[] {1, 2, 3});
        var result =
                new ManagedToolResult(
                        scope,
                        original.taskId(),
                        original.executionId(),
                        "succeeded",
                        original.result()
                                .replace(
                                        ManagedFiles.sha256(new byte[] {1, 2, 3}), "0".repeat(64)));
        journal.stageToolResult(result);
        journal.commitToolResult(result);
        assertEquals("failed", journal.tool(scope, result.taskId()).orElseThrow().status());
        assertEquals(0, count("file"));
        assertEquals(0, count("artifact"));
    }

    private long count(String table) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ma." + table + " WHERE scope_key=?",
                Long.class,
                scope.messageGroup());
    }

    private ManagedToolResult generated(byte[] bytes) throws Exception {
        var mapper = new ObjectMapper();
        String turn = admission.acceptTurn(scope, "actor", "generated", "{}").id();
        admission.reservePublication(scope, turn);
        admission.confirmPublication(scope, turn, "broker");
        admission.activate(scope, turn, 1, "token");
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
                        "{\"projectorVersion\":1,\"stateJson\":\"{\\\"context\\\":[]}\"}");
        var arguments =
                mapper.writeValueAsString(
                        Map.of(
                                "path",
                                "output/report.bin",
                                "name",
                                "report.bin",
                                "media_type",
                                "application/octet-stream",
                                "native_tool_call_id",
                                "publish-report"));
        journal.commit(
                journal.objects()
                        .prepare(
                                new ManagedRuntimeBatch(
                                        scope,
                                        turn,
                                        1,
                                        "token",
                                        "writer",
                                        "export",
                                        0,
                                        List.of(checkpoint),
                                        List.of(
                                                new ToolIntent(
                                                        "publish-report_0",
                                                        ManagedArtifactTool.OPERATION,
                                                        arguments)),
                                        List.of(),
                                        null)));
        String task =
                "tool_" + ManagedScope.fingerprint(scope.messageGroup(), turn, "publish-report_0");
        journal.claimTool(scope, task, "hands");
        return new ManagedToolResult(
                scope,
                task,
                task,
                "succeeded",
                mapper.writeValueAsString(
                        Map.of(
                                "content",
                                Base64.getEncoder().encodeToString(bytes),
                                "size",
                                bytes.length,
                                "sha256",
                                ManagedFiles.sha256(bytes))));
    }
}
