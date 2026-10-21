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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.state.AgentState;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SessionRecoveryCancellationTest {
    @Test
    void idleCancellationBindsItsWriterRejectsSteeringAndCannotTargetADifferentTurn() {
        var log =
                new InMemorySessionLogStore()
                        .open(new SessionKey("user", "agent", "session"), null);
        var state = AgentState.builder().sessionId("session").build();
        var recorder = new SessionRecorder(log, "turn", "execution", null);
        recorder.start(List.of(), state).block();
        var binding = new AtomicReference<String>();
        assertThrows(
                SessionLogException.class,
                () -> SessionRecovery.cancelPending(log, "turn", binding::set));
        assertNull(binding.get());
        recorder.finish(state, "suspended").block();
        new SessionInbox(log)
                .locked(
                        tx ->
                                tx.accept(
                                        new SessionInbox.Command(
                                                "input", "steer", "turn", List.of(), List.of())));
        long before = log.head().seq();
        assertThrows(
                SessionLogException.class,
                () -> SessionRecovery.cancelPending(log, "other-turn", binding::set));
        assertEquals(before, log.head().seq());
        SessionRecovery.cancelPending(
                log,
                "turn",
                writer -> {
                    assertEquals(writer, log.head().owner());
                    binding.set(writer);
                });
        assertEquals("cancelled", SessionTurns.read(log).latest().status());
        boolean foundCancellation = false;
        for (var event : log.scan(before, log.head().seq())) {
            if (!"turn/cancelled".equals(event.type())) continue;
            assertEquals(binding.get(), event.executionRunId());
            foundCancellation = true;
        }
        assertTrue(foundCancellation);
        assertEquals("turn_cancelled", new SessionInbox(log).snapshot().rejected().get("input"));
        long cancelled = log.head().seq();
        SessionRecovery.cancelPending(
                log,
                "turn",
                writer -> {
                    throw new AssertionError("Duplicate binding");
                });
        assertEquals(cancelled, log.head().seq());
    }

    @Test
    void aCommittedCompletionCannotBecomeCancellation() {
        var log =
                new InMemorySessionLogStore()
                        .open(new SessionKey("user", "agent", "session"), null);
        var state = AgentState.builder().sessionId("session").build();
        var recorder = new SessionRecorder(log, "turn", "execution", null);
        recorder.start(List.of(), state).block();
        recorder.finish(state, "completed").block();
        long before = log.head().seq();
        SessionRecovery.cancelPending(
                log,
                "turn",
                writer -> {
                    throw new AssertionError("Completed Turn binding");
                });
        assertEquals(before, log.head().seq());
        assertEquals("completed", SessionTurns.read(log).latest().status());
    }

    @Test
    void preparedUnstartedCancellationCannotReplaceAnUnfinishedOrPreviouslyStartedRun() {
        var log =
                new InMemorySessionLogStore()
                        .open(new SessionKey("user", "agent", "session"), null);
        var state = AgentState.builder().sessionId("session").build();
        var recorder = new SessionRecorder(log, "earlier", "earlier-run", null);
        recorder.start(List.of(), state).block();
        recorder.finish(state, "suspended").block();
        long before = log.head().seq();
        assertThrows(
                SessionLogException.class,
                () -> SessionRecovery.cancelUnstarted(log, "new", "prepared-run", ignored -> {}));
        assertEquals(before, log.head().seq());
        SessionRecovery.cancelPending(log, "earlier");
        before = log.head().seq();
        assertThrows(
                SessionLogException.class,
                () -> SessionRecovery.cancelUnstarted(log, "new", "earlier-run", ignored -> {}));
        assertEquals(before, log.head().seq());
        var binding = new AtomicReference<String>();
        SessionRecovery.cancelUnstarted(log, "new", "prepared-run", binding::set);
        assertEquals("new", SessionTurns.read(log).latest().turnId());
        assertEquals("cancelled", SessionTurns.read(log).latest().status());
        assertEquals(binding.get(), SessionTurns.read(log).latest().runId());
        long cancelled = log.head().seq();
        SessionRecovery.cancelUnstarted(
                log,
                "new",
                "prepared-run",
                ignored -> {
                    throw new AssertionError("Duplicate binding");
                });
        assertEquals(cancelled, log.head().seq());
    }
}
