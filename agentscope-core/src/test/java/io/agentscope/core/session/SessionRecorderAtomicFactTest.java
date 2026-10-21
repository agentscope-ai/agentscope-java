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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionRecorderAtomicFactTest {
    @Test
    void changedAndUnchangedStateEachCommitTheirInputReceiptWithOneCheckpoint() {
        var log = spy(new InMemorySessionLogStore().open(new SessionKey("u", "a", "s"), null));
        var batches = new ArrayList<List<SessionEvent>>();
        doAnswer(
                        call -> {
                            batches.add(List.copyOf(call.getArgument(3)));
                            return call.callRealMethod();
                        })
                .when(log)
                .commit(any(), anyString(), anyLong(), anyList());
        var state = AgentState.builder().sessionId("s").build();
        var recorder = new SessionRecorder(log, "turn", "run", null);
        try {
            recorder.checkpointNow(state, "initial");
            batches.clear();
            recorder.checkpointedRecordNow(
                    state, "unchanged", "inbox/applied", Map.of("commandIds", List.of("first")));
            assertAtomic(batches, state);
            batches.clear();
            state.contextMutable().add(new UserMessage("added"));
            recorder.checkpointedRecordNow(
                    state, "changed", "inbox/applied", Map.of("commandIds", List.of("second")));
            assertAtomic(batches, state);
        } finally {
            recorder.close();
        }
        assertEquals(1, SessionProjection.read(log).restore().getContext().size());
        assertTrue(SessionTurns.read(log).applied().containsAll(List.of("first", "second")));
    }

    private void assertAtomic(List<List<SessionEvent>> batches, AgentState state) {
        assertEquals(1, batches.size());
        assertEquals(
                1,
                batches.get(0).stream()
                        .filter(event -> "inbox/applied".equals(event.type()))
                        .count());
        var checkpoints =
                batches.get(0).stream()
                        .filter(event -> "state/checkpoint".equals(event.type()))
                        .toList();
        assertEquals(1, checkpoints.size());
        assertEquals(state.toJson(), checkpoints.get(0).data().get("stateJson"));
    }
}
