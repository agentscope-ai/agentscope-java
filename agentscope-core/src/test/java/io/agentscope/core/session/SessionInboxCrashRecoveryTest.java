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
package io.agentscope.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionInboxCrashRecoveryTest {
    @Test
    void rejectionSurvivesLostCommitAcknowledgementWithoutAnExecutableIntermediateInput() {
        var storage = new FaultStorage();
        var command = new SessionInbox.Command("denied", "inject", null, List.of(), List.of());
        var inbox = new SessionInbox(new JournalSessionLog(storage, "session"));
        storage.loseRejectedAck = true;
        assertThrows(
                SessionLogException.class,
                () -> inbox.locked(tx -> tx.acceptRejected(command, "input_admission_expired")));
        var restarted = new SessionInbox(new JournalSessionLog(storage, "session"));
        assertEquals(1, restarted.snapshot().commands().size());
        assertEquals("input_admission_expired", restarted.snapshot().rejected().get("denied"));
        restarted.locked(tx -> tx.acceptRejected(command, "input_admission_expired"));
        assertEquals(2, new JournalSessionLog(storage, "session").inbox().head().seq());
        var accepted = new SessionInbox.Command("accepted", "inject", null, List.of(), List.of());
        restarted.locked(tx -> tx.accept(accepted));
        restarted.locked(tx -> tx.acceptRejected(accepted, "input_admission_expired"));
        assertFalse(restarted.snapshot().rejected().containsKey("accepted"));
    }

    @Test
    void rejectedSteeringDoesNotKeepTheExecutionOpen() {
        var log = new JournalSessionLog(new FaultStorage(), "session");
        var command = new SessionInbox.Command("submit", "submit", "turn", List.of(), List.of());
        var state = AgentState.builder().sessionId("session").build();
        var recorder = new SessionRecorder(log, "turn", "execution", null);
        var execution = new SessionExecution(log, command);
        execution.prepare(recorder);
        recorder.start(List.of(), state).block();
        new SessionInbox(log)
                .locked(
                        tx -> {
                            var rejected =
                                    new SessionInbox.Command(
                                            "expired", "steer", "turn", List.of(), List.of());
                            tx.acceptRejected(rejected, "input_admission_expired");
                            return null;
                        });
        assertFalse(execution.continueOrClose(recorder));
        assertEquals(null, new SessionInbox(log).snapshot().turnId());
        recorder.finish(state, "completed").block();
        assertEquals("completed", SessionTurns.read(log).latest().status());
    }

    @Test
    void acceptanceSurvivesLostCommitAcknowledgementAndRetryDoesNotDuplicateInput() {
        var storage = new FaultStorage();
        var command =
                new SessionInbox.Command(
                        "input",
                        "inject",
                        null,
                        List.of(new UserMessage("durable input")),
                        List.of());
        storage.loseInboxAck = true;
        var inbox = new SessionInbox(new JournalSessionLog(storage, "session"));
        assertThrows(SessionLogException.class, () -> inbox.locked(tx -> tx.accept(command)));
        var restarted = new SessionInbox(new JournalSessionLog(storage, "session"));
        assertEquals(1, restarted.snapshot().commands().size());
        restarted.locked(tx -> tx.accept(command));
        assertEquals(1, restarted.snapshot().commands().size());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        restarted.locked(
                                tx ->
                                        tx.accept(
                                                new SessionInbox.Command(
                                                        "input",
                                                        "inject",
                                                        null,
                                                        List.of(new UserMessage("changed")),
                                                        List.of()))));
    }

    @Test
    void checkpointBeforeAppliedMarkerRecoversWithoutRepeatingInjectedMessage() {
        var storage = new FaultStorage();
        var log = new JournalSessionLog(storage, "session");
        var injected =
                new SessionInbox.Command(
                        "input",
                        "inject",
                        null,
                        List.of(new UserMessage("exactly once")),
                        List.of());
        new SessionInbox(log).locked(tx -> tx.accept(injected));
        var submit = new SessionInbox.Command("submit", "submit", "turn", List.of(), List.of());
        var state = AgentState.builder().sessionId("session").build();
        var firstRecorder = new SessionRecorder(log, "turn", "first", null);
        try {
            storage.failApplied = true;
            assertThrows(
                    SessionLogException.class,
                    () -> new SessionExecution(log, submit).beforeStep(firstRecorder, state));
        } finally {
            firstRecorder.close();
        }
        var restarted = new JournalSessionLog(storage, "session");
        var recovered = SessionProjection.read(restarted).restore();
        assertEquals(1, recovered.getContext().size());
        assertEquals("exactly once", recovered.getContext().get(0).getTextContent());
        assertFalse(SessionTurns.read(restarted).applied().contains("input"));
        var recorder = new SessionRecorder(restarted, "turn", "replacement", null);
        try {
            var execution = new SessionExecution(restarted, submit);
            execution.beforeStep(recorder, recovered);
            long committed = restarted.head().seq();
            execution.beforeStep(recorder, recovered);
            assertEquals(committed, restarted.head().seq());
        } finally {
            recorder.close();
        }
        assertEquals(1, SessionProjection.read(restarted).restore().getContext().size());
        assertTrue(SessionTurns.read(restarted).applied().contains("input"));
    }

    private static final class FaultStorage implements AtomicSessionStorage {
        private final Map<String, Value> values = new HashMap<>();
        private boolean loseInboxAck;
        private boolean loseRejectedAck;
        private boolean failAckInspection;
        private boolean failApplied;

        @Override
        public synchronized Value read(String path) {
            if (failAckInspection) {
                failAckInspection = false;
                throw new SessionLogException("injected connection loss during ACK inspection");
            }
            return values.get(path);
        }

        @Override
        public synchronized boolean compareAndSet(String path, long version, byte[] bytes) {
            String content = new String(bytes, StandardCharsets.UTF_8);
            if (failApplied && content.contains("inbox/applied")) {
                failApplied = false;
                throw new SessionLogException("injected crash after checkpoint before applied");
            }
            var current = values.get(path);
            if ((current == null ? 0 : current.version()) != version) return false;
            values.put(path, new Value(version + 1, bytes));
            if (path.endsWith("/inbox/head.json")
                    && (loseInboxAck && content.contains("\"seq\":1")
                            || loseRejectedAck && content.contains("\"seq\":2"))) {
                loseInboxAck = false;
                loseRejectedAck = false;
                failAckInspection = true;
                throw new SessionLogException("injected lost committed acceptance ACK");
            }
            return true;
        }
    }
}
