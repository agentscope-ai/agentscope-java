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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionInteractions;
import io.agentscope.core.session.SessionLogException;
import io.agentscope.core.session.SessionProjection;
import io.agentscope.core.session.SessionRecorder;
import io.agentscope.core.session.SessionTurns;
import io.agentscope.core.session.SessionViews;
import io.agentscope.core.state.AgentState;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ManagedJournalReadsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ManagedScope scope =
            new ManagedScope("tenant", "ns", "reads-" + UUID.randomUUID());
    @TempDir Path checkpointRoot;
    private final AtomicInteger checkpointReads = new AtomicInteger();
    private CountingSource source;
    private ManagedAdmissionStore admission;
    private ManagedJournalStore journal;
    private String turn;

    protected DataSource database() {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:reads-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
    }

    @BeforeEach
    void setup() {
        source = new CountingSource(database());
        ManagedAdmissionStore.initialize(source);
        admission = ProjectionTestSupport.admission(source, mapper);
        var objects = spy(objectStore(source, mapper, checkpointRoot));
        doAnswer(
                        call -> {
                            checkpointReads.incrementAndGet();
                            return call.callRealMethod();
                        })
                .when(objects)
                .read(any());
        journal = ProjectionTestSupport.journal(source, mapper, objects);
        admission.createSession(scope, "{}");
        turn = admission.acceptTurn(scope, "actor", "request", "{}").id();
        admission.reservePublication(scope, turn);
        admission.confirmPublication(scope, turn, "broker");
        admission.activate(scope, turn, 1, "token");
    }

    protected ManagedCheckpointObjects objectStore(
            DataSource source, ObjectMapper mapper, Path root) {
        return new ManagedCheckpointObjects(mapper, root);
    }

    protected void deleteObject(ManagedCheckpointObjects.Reference reference) throws Exception {
        Files.delete(
                checkpointRoot
                        .resolve(reference.identity().scope().messageGroup())
                        .resolve("checkpoint")
                        .resolve(reference.identity().checkpointId()));
    }

    protected void corruptObject(ManagedCheckpointObjects.Reference reference) throws Exception {
        Files.writeString(
                checkpointRoot
                        .resolve(reference.identity().scope().messageGroup())
                        .resolve("checkpoint")
                        .resolve(reference.identity().checkpointId()),
                "corrupt");
    }

    private void resetReads() {
        source.queries.clear();
        checkpointReads.set(0);
    }

    @Test
    void repeatedViewsReuseAllPagesWithoutChangingTheStablePrefix() {
        var events = new ArrayList<SessionEvent>();
        events.add(checkpoint(1, "baseline"));
        for (int sequence = 2; sequence <= 301; sequence++)
            events.add(event(sequence, "step/end", Map.of()));
        commit("pages", 0, events);
        var local = new ManagedLocalJournal(scope, turn, journal);
        var guard = mock(ManagedExecutionGuard.class);
        when(guard.remaining()).thenReturn(Duration.ofMinutes(1));
        var log =
                new ManagedMqSessionLog(
                        scope,
                        turn,
                        1,
                        guard,
                        journal,
                        new ManagedRuntimeWriter(journal, new SessionCommitTransactions(journal)),
                        local);
        resetReads();
        var first = SessionProjection.read(log);
        assertEquals(301, first.asOfSeq());
        assertEquals(2, source.reads("ma.runtime_commit"));
        SessionTurns.read(log);
        assertEquals(first, SessionProjection.read(log));
        assertEquals(2, source.reads("ma.runtime_commit"));
        assertEquals(1, checkpointReads.get());
    }

    @Test
    void restoreLoadsOneCheckpointWhileStatusAndTranscriptLoadNone() {
        commit(
                "seed",
                0,
                List.of(checkpoint(1, "old"), checkpoint(2, "middle"), checkpoint(3, "latest")));
        var reader = new ManagedMqSessionLog(scope, null, 0, null, journal, null);
        resetReads();
        assertEquals(
                "latest",
                SessionProjection.read(reader).restore().getContext().get(0).getTextContent());
        assertEquals(1, source.reads("ma.runtime_commit"));
        assertEquals(1, checkpointReads.get());
        resetReads();
        SessionTurns.read(reader);
        SessionInteractions.pending(reader);
        SessionViews.transcript(reader);
        assertEquals(0, checkpointReads.get());
        resetReads();
        assertEquals(3, reader.readAfter(0, 10).size());
        assertEquals(3, checkpointReads.get());
    }

    @Test
    void unusedOldObjectIsNotNeededButAuthoritativeObjectStillFailsClosed() throws Exception {
        var wire = commit("seed", 0, List.of(checkpoint(1, "old"), checkpoint(2, "latest")));
        var old = journal.objects().reference(scope, wire.events().get(0));
        var latest = journal.objects().reference(scope, wire.events().get(1));
        deleteObject(old);
        var reader = new ManagedMqSessionLog(scope, null, 0, null, journal, null);
        assertEquals(
                "latest",
                SessionProjection.read(reader).restore().getContext().get(0).getTextContent());
        assertThrows(ManagedTransportException.class, () -> reader.readAfter(0, 10));
        corruptObject(latest);
        assertThrows(ManagedStateException.class, () -> SessionProjection.read(reader));
    }

    @Test
    void sharedExecutionReadsReuseThePrefixAndObserveItsNextCommittedCheckpoint() {
        commit("seed", 0, List.of(checkpoint(1, "first")));
        var guard = mock(ManagedExecutionGuard.class);
        when(guard.token()).thenReturn("token");
        when(guard.remaining()).thenReturn(Duration.ofMinutes(1));
        var execution =
                new ManagedTurnExecution(
                        scope,
                        turn,
                        1,
                        guard,
                        admission,
                        journal,
                        new ManagedRuntimeWriter(journal, new SessionCommitTransactions(journal)));
        resetReads();
        SessionProjection.read(execution.log());
        SessionTurns.read(execution.log());
        var recorder = new SessionRecorder(execution.log(), turn, "recorder", null);
        try {
            assertEquals(
                    "first", recorder.initial().restore().getContext().get(0).getTextContent());
            assertEquals(1, source.reads("ma.runtime_commit"));
            assertEquals(1, checkpointReads.get());
        } finally {
            recorder.close();
        }
        var log = execution.log();
        var writer = log.acquire("writer", Duration.ofMinutes(1));
        log.commit(writer, "next", 1, List.of(checkpoint(2, "second")));
        log.release(writer);
        resetReads();
        assertEquals(
                "second",
                SessionProjection.read(execution.log())
                        .restore()
                        .getContext()
                        .get(0)
                        .getTextContent());
        assertEquals(0, source.reads("ma.runtime_commit"));
        assertEquals(1, checkpointReads.get());
        var original = execution.log().readMetadataAfter(1, 1).get(0);
        var altered =
                new SessionEvent(
                        original.schemaVersion(),
                        original.eventId(),
                        original.seq(),
                        original.occurredAt(),
                        original.type(),
                        original.executionRunId(),
                        original.turnId(),
                        original.required(),
                        original.payloadJson().replace("checkpointRef", "wrongReference"));
        assertThrows(ManagedStateException.class, () -> execution.log().materialize(altered));
        resetReads();
        var freshExecution =
                new ManagedTurnExecution(
                        scope,
                        turn,
                        1,
                        guard,
                        admission,
                        journal,
                        new ManagedRuntimeWriter(journal, new SessionCommitTransactions(journal)));
        resetReads();
        assertEquals(
                "second",
                SessionProjection.read(freshExecution.log())
                        .restore()
                        .getContext()
                        .get(0)
                        .getTextContent());
        assertEquals(1, source.reads("ma.runtime_commit"));
        assertEquals(1, checkpointReads.get());
        admission.activate(scope, turn, 1, "successor");
        assertThrows(
                SessionLogException.class,
                () -> {
                    var stale = execution.log();
                    var staleWriter = stale.acquire("old", Duration.ofMinutes(1));
                    stale.commit(staleWriter, "stale", 2, List.of(checkpoint(3, "stale")));
                });
        assertEquals(2, journal.head(scope).sequence());
    }

    @Test
    void oversizedEventsAreRereadWithoutChangingTheirContentOrCommittedPosition() {
        commit(
                "seed",
                0,
                List.of(event(1, "run/stop_requested", Map.of("body", "x".repeat(1_500_000)))));
        var local = new ManagedLocalJournal(scope, turn, journal);
        resetReads();
        var first = local.readAfter(0, 1);
        assertEquals(first, local.readAfter(0, 1));
        assertEquals(2, source.reads("ma.runtime_commit"));
        assertEquals(1, local.head().sequence());
    }

    @Test
    void evictedPagesAreRereadWithoutLosingCommittedEvents() {
        var events = new ArrayList<SessionEvent>();
        for (int sequence = 1; sequence <= 2100; sequence++)
            events.add(event(sequence, "step/end", Map.of()));
        commit("many", 0, events);
        var local = new ManagedLocalJournal(scope, turn, journal);
        resetReads();
        for (int after = 0; after < events.size(); after += 256)
            assertEquals(
                    events.subList(after, Math.min(after + 256, events.size())),
                    local.readMetadataAfter(after, 256));
        assertEquals(9, source.reads("ma.runtime_commit"));
        assertEquals(events.subList(0, 256), local.readMetadataAfter(0, 256));
        assertEquals(10, source.reads("ma.runtime_commit"));
        assertEquals(events.size(), local.head().sequence());
    }

    @Test
    void missingEventCannotBeHiddenByMetadataPaging() {
        commit("gap", 0, List.of(event(1, "step/end", Map.of()), event(2, "step/end", Map.of())));
        var local = new ManagedLocalJournal(scope, turn, journal);
        new JdbcTemplate(source)
                .update(
                        "DELETE FROM ma.runtime_commit WHERE scope_key=? AND first_sequence=1",
                        scope.messageGroup());
        assertThrows(SessionLogException.class, () -> local.readMetadataAfter(0, 2));
        assertEquals(2, local.head().sequence());
    }

    @Test
    void nativeToolTasksAreLoadedInOneQueryWithTheirOriginalDetails() {
        var intents =
                List.of(
                        new ManagedRuntimeBatch.ToolIntent(
                                "one", "sandbox.exec", "{\"native_tool_call_id\":\"native\"}"),
                        new ManagedRuntimeBatch.ToolIntent(
                                "two", "fs.download", "{\"native_tool_call_id\":\"native\"}"),
                        new ManagedRuntimeBatch.ToolIntent(
                                "three", "fs.upload", "{\"native_tool_call_id\":\"native\"}"));
        AgentScopePreparation.commit(
                journal,
                new SessionCommitTransactions(journal),
                AgentScopeCheckpoints.prepare(
                        journal.objects(),
                        new ManagedRuntimeBatch(
                                scope,
                                turn,
                                1,
                                "token",
                                "writer",
                                "tools",
                                0,
                                List.of(checkpoint(1, "state")),
                                intents,
                                List.of(),
                                null)));
        resetReads();
        var tasks = journal.toolsForNativeCall(scope, turn, "native");
        assertEquals(3, tasks.size());
        assertEquals(1, source.reads("ma.tool_task"));
        assertTrue(
                tasks.stream()
                        .allMatch(
                                task ->
                                        task.id().equals(task.executionId())
                                                && task.checkpointSequence() == 1
                                                && task.status().equals("ready")));
        assertEquals(
                tasks.stream().map(ManagedJournalStore.ToolTask::id).sorted().toList(),
                tasks.stream().map(ManagedJournalStore.ToolTask::id).toList());
    }

    private ManagedRuntimeBatch commit(String id, long expected, List<SessionEvent> events) {
        var batch =
                AgentScopeCheckpoints.prepare(
                        journal.objects(),
                        new ManagedRuntimeBatch(
                                scope, turn, 1, "token", "writer", id, expected, events, List.of(),
                                List.of(), null));
        AgentScopePreparation.commit(journal, new SessionCommitTransactions(journal), batch);
        return batch;
    }

    private SessionEvent checkpoint(long seq, String text) {
        var state = AgentState.builder().sessionId(scope.sessionId()).build();
        state.contextMutable().add(new UserMessage(text));
        return event(seq, "state/checkpoint", Map.of("stateJson", state.toJson()));
    }

    private SessionEvent event(long seq, String type, Map<String, Object> data) {
        return new SessionEvent(1, "e" + seq, seq, 1, type, "seed", turn, true, json(data));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception invalid) {
            throw new AssertionError(invalid);
        }
    }

    private static final class CountingSource extends DelegatingDataSource {
        private final List<String> queries = new CopyOnWriteArrayList<>();

        CountingSource(DataSource source) {
            super(source);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return instrument(super.getConnection());
        }

        @Override
        public Connection getConnection(String user, String password) throws SQLException {
            return instrument(super.getConnection(user, password));
        }

        private Connection instrument(Connection connection) {
            return (Connection)
                    Proxy.newProxyInstance(
                            Connection.class.getClassLoader(),
                            new Class<?>[] {Connection.class},
                            (proxy, method, args) -> {
                                if (method.getName().equals("prepareStatement")
                                        && args != null
                                        && args[0] instanceof String sql)
                                    queries.add(sql.toLowerCase(Locale.ROOT));
                                try {
                                    return method.invoke(connection, args);
                                } catch (InvocationTargetException failed) {
                                    throw failed.getCause();
                                }
                            });
        }

        long reads(String table) {
            return queries.stream()
                    .filter(sql -> sql.startsWith("select") && sql.contains("from " + table))
                    .count();
        }
    }
}
