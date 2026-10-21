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
package io.agentscope.builder.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.runtime.managed.ManagedToolExecutor.OutputPage;
import io.agentscope.core.session.SessionEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Timeout(20)
class ManagedToolEventsTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ManagedScope scope =
            new ManagedScope("tenant", "namespace", "tool-events-" + UUID.randomUUID());
    private DataSource source;
    private ManagedJournalStore journal;
    private ManagedJournalStore.ToolTask task;
    private ManagedToolEvents events;
    private String turn;

    protected DataSource database() {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:tool-events-"
                        + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
    }

    @BeforeEach
    void setup() {
        source = database();
        ManagedAdmissionStore.initialize(source);
        var admission = new ManagedAdmissionStore(source, mapper);
        journal =
                new ManagedJournalStore(
                        source, mapper, new ManagedCheckpointObjects(mapper, directory));
        events = journal.toolEvents();
        admission.createSession(scope, "{}");
        turn = admission.acceptTurn(scope, "user", "request", "{}").id();
        admission.reservePublication(scope, turn);
        admission.confirmPublication(scope, turn, "broker");
        admission.activate(scope, turn, 1, "brain");
        var requested =
                new SessionEvent(
                        1,
                        "requested",
                        1,
                        1,
                        "tool/requested",
                        "run",
                        turn,
                        true,
                        "{\"toolCallId\":\"native\",\"call\":{\"type\":\"tool_use\",\"id\":\"native\",\"name\":\"execute\",\"input\":{}}}");
        var checkpoint =
                new SessionEvent(
                        1,
                        "checkpoint",
                        2,
                        1,
                        "state/checkpoint",
                        "run",
                        turn,
                        true,
                        "{\"stateJson\":\"{\\\"context\\\":[]}\"}");
        journal.commit(
                journal.objects()
                        .prepare(
                                new ManagedRuntimeBatch(
                                        scope,
                                        turn,
                                        1,
                                        "brain",
                                        "writer",
                                        "batch",
                                        0,
                                        List.of(requested, checkpoint),
                                        List.of(
                                                new ManagedRuntimeBatch.ToolIntent(
                                                        "operation",
                                                        "sandbox.exec",
                                                        "{\"native_tool_call_id\":\"native\"}")),
                                        List.of(),
                                        null)));
        String id = "tool_" + ManagedScope.fingerprint(scope.messageGroup(), turn, "operation");
        task = journal.claimTool(scope, id, "collector").task();
        events.open(scope, task, "collector", true);
    }

    private OutputPage page(long offset, String value, boolean terminal) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return new OutputPage(
                offset,
                offset + bytes.length,
                Base64.getEncoder().encodeToString(bytes),
                offset + bytes.length,
                terminal,
                null);
    }

    private ManagedToolResult result() {
        return new ManagedToolResult(
                scope,
                task.id(),
                task.executionId(),
                "succeeded",
                "{\"output\":\"final\",\"exitCode\":0}");
    }

    private List<ManagedToolEvent> sources() {
        return new JdbcTemplate(source)
                .query(
                        "SELECT payload FROM ma.tool_event WHERE scope_key=? AND task_id=? ORDER BY"
                                + " source_sequence",
                        (row, index) -> {
                            try {
                                return mapper.readValue(row.getString(1), ManagedToolEvent.class);
                            } catch (Exception invalid) {
                                throw new IllegalStateException(invalid);
                            }
                        },
                        scope.messageGroup(),
                        task.id());
    }

    @Test
    void runningOutputIsVisibleWithoutSettlingTheToolOrAdvancingNativeLog() throws Exception {
        events.capture(scope, task, "collector", page(0, "hello 世界\n", false));
        for (var event : sources()) journal.commitToolEvent(event);
        assertNull(journal.tool(scope, task.id()).orElseThrow().result());
        assertEquals(2, journal.head(scope).sequence());
        assertEquals(2, events.read(scope, task.id(), 0, 20).size());
        var snapshot = journal.resources().snapshot(scope);
        var tools = journal.resources().page(scope, snapshot.id(), "tools", null, 10).data();
        assertEquals(1, tools.size());
        var data = mapper.valueToTree(tools.get(0)).path("data");
        assertEquals("execute", data.path("name").asText());
        assertEquals("requested", data.path("status").asText());
        assertEquals("hello 世界\n", data.path("progress").get(0).path("text").asText());
        assertFalse(data.path("operations").path(task.id()).has("output"));
        assertEquals(
                null,
                new JdbcTemplate(source)
                        .queryForObject(
                                "SELECT result_hash FROM ma.tool_task WHERE scope_key=? AND"
                                        + " task_id=?",
                                String.class,
                                scope.messageGroup(),
                                task.id()));
    }

    @Test
    void futureFinalIsBufferedAndOnlyTheContinuousPrefixCommitsItsResult() {
        events.capture(scope, task, "collector", page(0, "first", true));
        journal.stageToolResult(result());
        events.finish(result());
        var source = sources();
        var buffered = journal.commitToolEvent(source.get(2));
        assertEquals("buffered", buffered.receipt().outcome());
        assertTrue(buffered.results().isEmpty());
        assertNull(journal.tool(scope, task.id()).orElseThrow().result());
        assertEquals("buffered", journal.commitToolEvent(source.get(1)).receipt().outcome());
        assertEquals(0, events.cursor(scope, task.id()).orElseThrow().consumedSeq());
        var completed = journal.commitToolEvent(source.get(0));
        assertEquals(List.of(result()), completed.results());
        assertEquals("succeeded", journal.tool(scope, task.id()).orElseThrow().status());
        assertEquals(3, events.cursor(scope, task.id()).orElseThrow().consumedSeq());
        assertTrue(journal.commitToolEvent(source.get(2)).results().isEmpty());
        assertEquals(
                1,
                journal.publicEvents(scope, 0, 100).stream()
                        .filter(event -> "tool.operation.completed".equals(event.type()))
                        .count());
        assertEquals(
                0,
                journal.publicEvents(scope, 0, 100).stream()
                        .filter(event -> "tool.completed".equals(event.type()))
                        .count());
        assertEquals(2, journal.head(scope).sequence());
    }

    @Test
    void duplicateIdentityWithDifferentContentIsRecordedWithoutChangingAnyOldFact() {
        events.capture(scope, task, "collector", page(0, "first", false));
        var original = sources().get(1);
        journal.commitToolEvent(sources().get(0));
        journal.commitToolEvent(original);
        var changed =
                new ManagedToolEvent(
                        scope,
                        task.id(),
                        task.executionId(),
                        original.sourceSeq(),
                        original.type(),
                        Map.of(
                                "chunk_seq",
                                1,
                                "from_offset",
                                0,
                                "to_offset",
                                5,
                                "channel",
                                "combined",
                                "data",
                                Base64.getEncoder()
                                        .encodeToString("other".getBytes(StandardCharsets.UTF_8))));
        assertEquals(
                "tool_event_conflict",
                assertThrows(ManagedStateException.class, () -> journal.commitToolEvent(changed))
                        .code());
        assertEquals(original, events.read(scope, task.id(), 1, 10).get(0));
        assertEquals(
                1,
                new JdbcTemplate(source)
                        .queryForObject(
                                "SELECT COUNT(*) FROM ma.tool_event_conflict WHERE scope_key=?",
                                Integer.class,
                                scope.messageGroup()));
        assertEquals(2, events.cursor(scope, task.id()).orElseThrow().consumedSeq());
    }

    @Test
    void replacementUsesTheDurableCursorAndRejectsOldCollectorWrites() {
        events.capture(scope, task, "collector", page(0, "one", false));
        var replacement = journal.claimTool(scope, task.id(), "replacement").task();
        assertEquals(
                "stale_hands",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        events.capture(
                                                scope, task, "collector", page(3, "old", true)))
                        .code());
        var cursor = events.capture(scope, replacement, "replacement", page(3, "two", true));
        assertEquals(6, cursor.offset());
        assertEquals(2, cursor.chunkSeq());
        assertTrue(cursor.captureComplete());
        assertEquals(
                "tool_output_closed",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        events.capture(
                                                scope,
                                                replacement,
                                                "replacement",
                                                page(6, "late", true)))
                        .code());
    }

    @Test
    void resultIsFrozenBeforeLogDrainAndRecoveryDoesNotQueryOrRestartTheTool() {
        events.capture(scope, task, "collector", page(0, "one", false));
        journal.stageToolResult(result());
        var executor = mock(ManagedToolExecutor.class);
        when(executor.streamsOutput(any())).thenReturn(true);
        when(executor.output(any(), any(), eq(3L))).thenReturn(page(3, "two", true));
        var bus = mock(ManagedMessageBus.class);
        when(bus.publish(any(), any())).thenReturn(new ManagedMessageBus.Published("broker"));
        var renewals = Executors.newSingleThreadScheduledExecutor();
        try {
            var worker =
                    new ManagedToolExecutions(
                            new InMemoryManagedLeaseStore(System::currentTimeMillis),
                            new InMemoryManagedInputInbox(System::currentTimeMillis),
                            journal,
                            new ManagedToolResultPublisher(
                                    journal, bus, new ManagedBatchCodec(mapper, directory)),
                            renewals,
                            Duration.ofSeconds(3),
                            Duration.ofMillis(500),
                            Duration.ofMillis(5));
            assertTrue(worker.execute(scope, task.id(), executor));
            verify(executor, never()).query(any(), any());
            verify(executor, never()).start(any(), any());
            assertEquals(6, events.cursor(scope, task.id()).orElseThrow().offset());
            assertEquals(result(), journal.stagedToolResult(scope, task.id()).orElseThrow());
            for (var event : sources()) journal.commitToolEvent(event);
            assertEquals("succeeded", journal.tool(scope, task.id()).orElseThrow().status());
        } finally {
            renewals.shutdownNow();
        }
    }

    @Test
    void brokerUnknownPredecessorPreventsPublishingLaterSourcesAndRebuildRetriesIdenticalBytes() {
        events.capture(scope, task, "collector", page(0, "one", true));
        journal.stageToolResult(result());
        events.finish(result());
        var sent = new ArrayList<ManagedMessageBus.Message>();
        var codec = new ManagedBatchCodec(mapper, directory);
        var broken = mock(ManagedMessageBus.class);
        doAnswer(
                        call -> {
                            sent.add(call.getArgument(1));
                            throw new ManagedTransportException("accepted; reply lost");
                        })
                .when(broken)
                .publish(any(), any());
        assertThrows(
                ManagedTransportException.class,
                () ->
                        new ManagedToolResultPublisher(journal, broken, codec)
                                .flush(scope, task.id()));
        assertEquals(1, sent.size());
        assertEquals(1, codec.decodeToolEvent(sent.get(0)).sourceSeq());
        assertTrue(events.publication(scope, task.id(), System.currentTimeMillis()).isEmpty());
        events.retryFirst(scope, task.id());
        var recovered = mock(ManagedMessageBus.class);
        when(recovered.publish(any(), any()))
                .thenAnswer(
                        call -> {
                            sent.add(call.getArgument(1));
                            return new ManagedMessageBus.Published("broker");
                        });
        assertEquals(
                3,
                new ManagedToolResultPublisher(journal, recovered, codec).flush(scope, task.id()));
        assertArrayEquals(sent.get(0).body(), sent.get(1).body());
        assertEquals(
                List.of(1L, 1L, 2L, 3L),
                sent.stream().map(message -> codec.decodeToolEvent(message).sourceSeq()).toList());
    }

    @Test
    void explicitSourceGapIsSavedAndFinalWatermarkCannotSkipUnconsumedOutput() {
        events.capture(scope, task, "collector", page(0, "one", false));
        events.capture(
                scope,
                task,
                "collector",
                new OutputPage(3, 100, "", 100, true, "source_unavailable"));
        journal.stageToolResult(result());
        events.finish(result());
        assertEquals(
                "tool_output_pending",
                assertThrows(ManagedStateException.class, () -> journal.commitToolResult(result()))
                        .code());
        var source = sources();
        for (var event : source) journal.commitToolEvent(event);
        assertTrue(events.cursor(scope, task.id()).orElseThrow().hasGap());
        var completed =
                journal.publicEvents(scope, 0, 100).stream()
                        .filter(event -> "tool.operation.completed".equals(event.type()))
                        .findFirst()
                        .orElseThrow();
        assertTrue(completed.payload().contains("\"log_status\":\"gapped\""));
        assertEquals(1, ((Number) source.get(3).data().get("last_chunk_seq")).longValue());
        assertEquals(100, ((Number) source.get(3).data().get("output_offset")).longValue());
    }

    @Test
    void projectionFailureRollsBackFinalCoverageResultAndOutboxClosureTogether() {
        events.capture(scope, task, "collector", page(0, "one", true));
        journal.stageToolResult(result());
        events.finish(result());
        var sourceEvents = sources();
        journal.commitToolEvent(sourceEvents.get(0));
        journal.commitToolEvent(sourceEvents.get(1));
        var jdbc = new JdbcTemplate(source);
        jdbc.execute(
                "ALTER TABLE ma.public_event ADD CONSTRAINT fixture_reject_tool_final CHECK"
                        + " (scope_key<>'"
                        + scope.messageGroup()
                        + "' OR type<>'tool.operation.completed')");
        try {
            assertThrows(
                    DataIntegrityViolationException.class,
                    () -> journal.commitToolEvent(sourceEvents.get(2)));
            assertEquals(2, events.cursor(scope, task.id()).orElseThrow().consumedSeq());
            assertNull(journal.tool(scope, task.id()).orElseThrow().result());
            assertEquals(
                    null,
                    new JdbcTemplate(source)
                            .queryForObject(
                                    "SELECT result_hash FROM ma.tool_task WHERE scope_key=? AND"
                                            + " task_id=?",
                                    String.class,
                                    scope.messageGroup(),
                                    task.id()));
            assertEquals("result_pending", journal.tool(scope, task.id()).orElseThrow().status());
        } finally {
            jdbc.execute("ALTER TABLE ma.public_event DROP CONSTRAINT fixture_reject_tool_final");
        }
        assertEquals(List.of(result()), journal.commitToolEvent(sourceEvents.get(2)).results());
    }

    @Test
    void previewIsBoundedWhileTheDurableSourceAndPublicDeltaHistoryRemainComplete()
            throws Exception {
        String value = "x".repeat(8192);
        long offset = 0;
        for (int i = 0; i < 40; i++) {
            events.capture(scope, task, "collector", page(offset, value, i == 39));
            offset += value.length();
        }
        for (var event : sources()) journal.commitToolEvent(event);
        var snapshot = journal.resources().snapshot(scope);
        var tools = journal.resources().page(scope, snapshot.id(), "tools", null, 10).data();
        var data = mapper.valueToTree(tools.get(0)).path("data");
        assertTrue(data.path("progress_truncated").asBoolean());
        assertEquals(65536, data.path("progress").get(0).path("text").asText().length());
        assertEquals(40, data.path("operations").path(task.id()).path("chunk_seq").asInt());
        long bytes = 0;
        for (var event : journal.publicEvents(scope, 0, 100))
            if ("tool.delta".equals(event.type()))
                bytes +=
                        mapper.readTree(event.payload())
                                .path("output")
                                .get(0)
                                .path("text")
                                .asText()
                                .length();
        assertEquals(offset, bytes);
        assertEquals(41, events.read(scope, task.id(), 0, 100).size());
        assertNull(journal.tool(scope, task.id()).orElseThrow().result());
    }

    @Test
    void finalWatermarkMismatchCannotWakeBrainEvenIfTheBrokenFactWasStaged() throws Exception {
        events.capture(scope, task, "collector", page(0, "one", true));
        journal.stageToolResult(result());
        events.finish(result());
        var staged = sources();
        journal.commitToolEvent(staged.get(0));
        journal.commitToolEvent(staged.get(1));
        var original = staged.get(2);
        var data = new LinkedHashMap<>(original.data());
        data.put("last_chunk_seq", 2);
        var wrong =
                new ManagedToolEvent(
                        scope,
                        task.id(),
                        task.executionId(),
                        original.sourceSeq(),
                        original.type(),
                        data);
        new JdbcTemplate(source)
                .update(
                        "UPDATE ma.tool_event SET payload=?,content_hash=? WHERE scope_key=? AND"
                                + " task_id=? AND source_sequence=?",
                        mapper.writeValueAsString(wrong),
                        events.hash(wrong),
                        scope.messageGroup(),
                        task.id(),
                        wrong.sourceSeq());
        assertEquals(
                "tool_final_watermark",
                assertThrows(ManagedStateException.class, () -> journal.commitToolEvent(wrong))
                        .code());
        assertEquals(2, events.cursor(scope, task.id()).orElseThrow().consumedSeq());
        assertNull(journal.tool(scope, task.id()).orElseThrow().result());
        assertEquals(
                null,
                new JdbcTemplate(source)
                        .queryForObject(
                                "SELECT result_hash FROM ma.tool_task WHERE scope_key=? AND"
                                        + " task_id=?",
                                String.class,
                                scope.messageGroup(),
                                task.id()));
    }
}
