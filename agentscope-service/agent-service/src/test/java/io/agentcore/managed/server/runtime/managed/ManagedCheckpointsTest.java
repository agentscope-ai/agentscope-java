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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.session.SessionEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ManagedCheckpointsTest {
    @TempDir private Path managedCheckpointRoot;

    private final ManagedScope scope = new ManagedScope("t", "n", UUID.randomUUID().toString());
    private final ObjectMapper mapper = new ObjectMapper();
    private ManagedAdmissionStore admission;
    private ManagedJournalStore journal;
    private JdbcTemplate jdbc;
    private String turn;

    @BeforeEach
    void setup() {
        var source = database();
        ManagedAdmissionStore.initialize(source);
        jdbc = new JdbcTemplate(source);
        admission = ProjectionTestSupport.admission(source, mapper);
        journal =
                ProjectionTestSupport.journal(
                        source,
                        mapper,
                        new ManagedCheckpointObjects(mapper, managedCheckpointRoot));
        admission.createSession(scope, "{}");
        turn = admission.acceptTurn(scope, "actor", "turn", "{}").id();
        admission.reservePublication(scope, turn);
        admission.confirmPublication(scope, turn, "broker");
        admission.activate(scope, turn, 1, "token");
    }

    @Test
    void indexedPagesPinTheHeadAndNeverExposePrivateState() throws Exception {
        var firstFile =
                journal.files()
                        .upload(scope, "actor", "one", "one.txt", "text/plain", new byte[] {1});
        commit(1, "state/checkpoint", List.of());
        var first = journal.checkpoints().list(scope, null, null, 1);
        var firstCheckpoint = ((List<Map<String, Object>>) first.get("data")).get(0);
        String id = (String) firstCheckpoint.get("checkpoint_id");
        assertEquals(List.of(firstFile.get("file_id")), journal.checkpoints().files(scope, id));
        journal.files().upload(scope, "actor", "two", "two.txt", "text/plain", new byte[] {2});
        commit(2, "state/checkpoint", List.of());
        var page = journal.checkpoints().list(scope, null, null, 1);
        assertEquals(true, page.get("has_more"));
        commit(3, "state/restored", List.of());
        var next = journal.checkpoints().list(scope, (String) page.get("next_cursor"), null, 1);
        assertEquals(page.get("as_of"), next.get("as_of"));
        assertEquals(false, next.get("has_more"));
        assertEquals(
                3,
                ((List<?>) journal.checkpoints().list(scope, null, null, 10).get("data")).size());
        assertEquals(
                2,
                ((List<?>)
                                journal.checkpoints()
                                        .list(scope, null, (String) page.get("as_of"), 10)
                                        .get("data"))
                        .size());
        assertEquals(1L, journal.checkpoints().metadata(scope, id).get("file_count"));
        assertFalse(mapper.writeValueAsString(page).contains("private-state"));
        assertFalse(mapper.writeValueAsString(page).contains("stateJson"));
        assertNull(next.get("next_cursor"));
        assertEquals(3, journal.head(scope).checkpointSequence());
    }

    @Test
    void failedToolWorkerCommitRollsBackTheIndexAndFileMembership() {
        journal.files().upload(scope, "actor", "one", "one.txt", "text/plain", new byte[] {1});
        assertThrows(
                EmptyResultDataAccessException.class,
                () ->
                        commit(
                                1,
                                "state/checkpoint",
                                List.of(
                                        new ManagedRuntimeBatch.InputReceipt(
                                                "missing", "applied", null))));
        assertEquals(0, journal.head(scope).sequence());
        assertEquals(
                0L,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.state_checkpoint WHERE scope_key=?",
                        Long.class,
                        scope.messageGroup()));
        assertEquals(
                0L,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.checkpoint_file WHERE scope_key=?",
                        Long.class,
                        scope.messageGroup()));
    }

    @Test
    void handlesAndCursorKindsAreBoundToTheWholeSessionScope() {
        commit(1, "state/checkpoint", List.of());
        commit(2, "state/checkpoint", List.of());
        var page = journal.checkpoints().list(scope, null, null, 1);
        var other = new ManagedScope("other-tenant", scope.namespace(), scope.sessionId());
        admission.createSession(other, "{}");
        String id = (String) ((Map<?, ?>) ((List<?>) page.get("data")).get(0)).get("checkpoint_id");
        assertEquals(
                "checkpoint_not_found",
                assertThrows(
                                ManagedStateException.class,
                                () -> journal.checkpoints().metadata(other, id))
                        .code());
        assertThrows(
                IllegalArgumentException.class,
                () -> journal.checkpoints().list(other, (String) page.get("next_cursor"), null, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> journal.checkpoints().list(scope, (String) page.get("as_of"), null, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> journal.checkpoints().list(scope, null, (String) page.get("next_cursor"), 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> journal.checkpoints().list(scope, "bad", null, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> journal.checkpoints().list(scope, null, null, 101));
    }

    protected DataSource database() {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:checkpoints-"
                        + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
    }

    private void commit(long sequence, String type, List<ManagedRuntimeBatch.InputReceipt> inputs) {
        var event =
                new SessionEvent(
                        1,
                        "event-" + sequence,
                        sequence,
                        1000 + sequence,
                        type,
                        "run",
                        turn,
                        true,
                        "{\"projectorVersion\":1,\"stateJson\":\"private-state\"}");
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
                                "batch-" + sequence,
                                sequence - 1,
                                List.of(event),
                                List.of(),
                                inputs,
                                null)));
    }
}
