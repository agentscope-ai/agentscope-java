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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.runtime.managed.ManagedInputInbox.Type;
import io.agentscope.builder.runtime.managed.ManagedRuntimeBatch.Suspension;
import io.agentscope.builder.runtime.managed.ManagedRuntimeBatch.ToolIntent;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.util.JsonUtils;
import java.nio.file.Path;
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

/** Both sides of the Input/checkpoint race use the same durable Session transaction. */
class ManagedInputSuspensionsTest {
    @TempDir Path checkpoints;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ManagedScope scope =
            new ManagedScope("input", "test", UUID.randomUUID().toString());
    private DataSource source;
    private JdbcTemplate jdbc;
    private ManagedAdmissionStore admission;
    private ManagedJournalStore journal;
    private String turn;

    protected DataSource database() {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:inputs-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
    }

    @BeforeEach
    void setup() {
        source = database();
        ManagedAdmissionStore.initialize(source);
        jdbc = new JdbcTemplate(source);
        admission = new ManagedAdmissionStore(source, mapper);
        journal =
                new ManagedJournalStore(
                        source, mapper, new ManagedCheckpointObjects(mapper, checkpoints));
        admission.createSession(scope, "{}");
        turn = admission.acceptTurn(scope, "actor", "turn", "{}").id();
        admission.reservePublication(scope, turn);
        admission.confirmPublication(scope, turn, "broker");
        admission.activate(scope, turn, 1, "first-token");
        var requested =
                event(
                        1,
                        "interaction/requested",
                        Map.of(
                                "requestId",
                                "action",
                                "kind",
                                "confirmation",
                                "call",
                                Map.of(
                                        "type",
                                        "tool_use",
                                        "id",
                                        "action",
                                        "name",
                                        "execute",
                                        "input",
                                        Map.of())));
        var waiting = event(2, "turn/suspended", Map.of("status", "waiting"));
        var checkpoint = checkpoint(3);
        commit(
                new ManagedRuntimeBatch(
                        scope,
                        turn,
                        1,
                        "first-token",
                        "writer",
                        "request",
                        0,
                        List.of(requested, waiting, checkpoint),
                        List.of(),
                        List.of(),
                        null));
    }

    @AfterEach
    void removeOnlyThisFixtureOutbox() {
        jdbc.update("DELETE FROM ma.turn_resume WHERE scope_key=?", scope.messageGroup());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replyBeforeOrAfterHandoffCreatesOneResumeAndNeverAcknowledgesTheOriginal(boolean early) {
        admission.acceptInput(scope, turn, "actor", "steer", Type.STEER, null, "{}");
        admission.acceptInput(scope, turn, "actor", "inject", Type.INJECT, null, "{}");
        ManagedAdmissionStore.Input reply = null;
        if (early) reply = reply();
        var wire = journal.objects().prepare(suspension(List.of("action")));
        var receipt = journal.commit(wire);
        assertEquals("committed", receipt.outcome());
        assertEquals(
                "stale_execution",
                assertThrows(ManagedStateException.class, () -> journal.commit(wire)).code());
        assertEquals("waiting_input", admission.turn(scope, turn).status());
        assertEquals(
                "waiting",
                ManagedJournalStore.publicTurnStatus(admission.turn(scope, turn).status()));
        assertEquals(1, admission.turns(scope, 0, false, "waiting", 10).size());
        assertNull(admission.turn(scope, turn).executionToken());
        if (!early) {
            assertTrue(journal.suspensions().pending(10).isEmpty());
            reply = reply();
        }
        var duplicate = reply();
        assertEquals(reply.id(), duplicate.id());
        assertEquals(reply.sequence(), duplicate.sequence());
        assertEquals(1, journal.suspensions().pending(10).size());
        assertEquals(0, admission.session(scope).settledSequence());
        assertEquals(
                "turn_suspended",
                assertThrows(
                                ManagedStateException.class,
                                () -> admission.activate(scope, turn, 1, "another-original"))
                        .code());
        var next = admission.acceptTurn(scope, "actor", "next", "{}");
        admission.reservePublication(scope, next.id());
        admission.confirmPublication(scope, next.id(), "next-broker");
        assertEquals(
                "missing_predecessor",
                assertThrows(
                                ManagedStateException.class,
                                () -> admission.activate(scope, next.id(), 2, "next-token"))
                        .code());
        var resume = journal.suspensions().pending(10).get(0);
        assertEquals(2, admission.activateResume(resume, "replacement-token"));
        assertEquals(turn, admission.session(scope).currentTurnId());
        assertEquals("actor", admission.turn(scope, turn).actor());
        assertEquals("accepted", admission.input(scope, reply.id()).status());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancelBeforeOrAfterHandoffWakesTheSameTurn(boolean early) {
        if (early) admission.acceptInput(scope, turn, "actor", "cancel", Type.CANCEL, null, "{}");
        commit(suspension(List.of("action")));
        if (!early) {
            assertTrue(journal.suspensions().pending(10).isEmpty());
            admission.acceptInput(scope, turn, "actor", "cancel", Type.CANCEL, null, "{}");
        }
        assertEquals(1, journal.suspensions().pending(10).size());
        assertEquals(turn, journal.suspensions().pending(10).get(0).turnId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void archiveBeforeOrAfterHandoffCannotStrandAnInputWait(boolean early) {
        var lifecycle = new ManagedLifecycles(source);
        if (early) lifecycle.stage(scope, "archived", "actor", "archive");
        commit(suspension(List.of("action")));
        if (!early) {
            assertTrue(journal.suspensions().pending(10).isEmpty());
            lifecycle.stage(scope, "archived", "actor", "archive");
        }
        assertEquals(1, journal.suspensions().pending(10).size());
        assertTrue(journal.cancelRequested(scope, turn));
    }

    @Test
    void unknownActionRollsBackTheCheckpointAndHandoff() {
        var before = journal.head(scope).sequence();
        assertEquals(
                "action_not_pending",
                assertThrows(
                                ManagedStateException.class,
                                () -> commit(suspension(List.of("other"))))
                        .code());
        assertEquals(before, journal.head(scope).sequence());
        assertEquals("waiting", admission.turn(scope, turn).status());
        assertTrue(journal.suspensions().pending(10).isEmpty());
    }

    @Test
    void activeHandsWorkMustSettleBeforeAnInputWait() {
        long sequence = journal.head(scope).sequence();
        commit(
                new ManagedRuntimeBatch(
                        scope,
                        turn,
                        1,
                        "first-token",
                        "writer",
                        "tool",
                        sequence,
                        List.of(checkpoint(sequence + 1)),
                        List.of(new ToolIntent("op", "framework_fs.read", "{}")),
                        List.of(),
                        null));
        assertEquals(
                "tools_unsettled",
                assertThrows(
                                ManagedStateException.class,
                                () -> commit(suspension(List.of("action"))))
                        .code());
        assertEquals("waiting", admission.turn(scope, turn).status());
        assertTrue(journal.suspensions().pending(10).isEmpty());
    }

    private ManagedAdmissionStore.Input reply() {
        return admission.acceptInput(
                scope, turn, "actor", "reply", Type.REPLY, "action", "{\"allow\":true}");
    }

    private ManagedRuntimeBatch suspension(List<String> actions) {
        var head = journal.head(scope);
        return new ManagedRuntimeBatch(
                scope,
                turn,
                head.epoch(),
                "first-token",
                "writer",
                "suspend",
                head.sequence(),
                List.of(checkpoint(head.sequence() + 1)),
                List.of(),
                List.of(),
                null,
                Suspension.inputs("s1", actions));
    }

    private SessionEvent checkpoint(long sequence) {
        return event(sequence, "state/checkpoint", Map.of("stateJson", "{}"));
    }

    private SessionEvent event(long sequence, String type, Map<String, ?> payload) {
        return new SessionEvent(
                1,
                "event-" + sequence,
                sequence,
                System.currentTimeMillis(),
                type,
                "run",
                turn,
                true,
                JsonUtils.getJsonCodec().toJson(payload));
    }

    private void commit(ManagedRuntimeBatch batch) {
        assertEquals("committed", journal.commit(journal.objects().prepare(batch)).outcome());
    }
}
