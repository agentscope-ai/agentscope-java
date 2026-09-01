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
package io.agentscope.core.agui.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.AguiEventType;
import io.agentscope.core.agui.model.AguiMessage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AguiSnapshotHydrator}.
 */
class AguiSnapshotHydratorTest {

    private final AguiSnapshotHydrator hydrator = new AguiSnapshotHydrator();

    @Test
    void emptySnapshotProducesThreeFrameHandshake() {
        List<AguiEvent> frames = hydrator.hydrate(null, "t1", "r1");

        assertEquals(3, frames.size());
        assertEquals(AguiEventType.RUN_STARTED, frames.get(0).getType());
        assertTrue(frames.get(1) instanceof AguiEvent.MessagesSnapshot);
        assertTrue(frames.get(2) instanceof AguiEvent.RunFinished);
        assertEquals("t1", frames.get(0).getThreadId());
        assertEquals("r1", frames.get(0).getRunId());
    }

    @Test
    void snapshotProducesFullFrameOrder() {
        AguiThreadSnapshot snapshot =
                new AguiThreadSnapshot(
                        "t1",
                        List.of(AguiMessage.userMessage("u1", "hi")),
                        Map.of("count", 2),
                        List.of(
                                new AguiThreadSnapshot.ActivityFrame(
                                        "u1", "progress", Map.of("step", 1))),
                        null,
                        "r0",
                        1L);

        List<AguiEvent> frames = hydrator.hydrate(snapshot, "t1", "r1");

        // RUN_STARTED, MESSAGES_SNAPSHOT, STATE_SNAPSHOT, ACTIVITY_SNAPSHOT, RUN_FINISHED
        assertEquals(5, frames.size());
        assertEquals(AguiEventType.RUN_STARTED, frames.get(0).getType());
        assertTrue(frames.get(1) instanceof AguiEvent.MessagesSnapshot);
        assertTrue(frames.get(2) instanceof AguiEvent.StateSnapshot);
        assertTrue(frames.get(3) instanceof AguiEvent.ActivitySnapshot);
        assertTrue(frames.get(4) instanceof AguiEvent.RunFinished);
    }

    @Test
    void stateOmittedWhenEmpty() {
        AguiThreadSnapshot snapshot =
                new AguiThreadSnapshot(
                        "t1",
                        List.of(AguiMessage.userMessage("u1", "hi")),
                        Map.of(),
                        List.of(),
                        null,
                        "r0",
                        1L);

        List<AguiEvent> frames = hydrator.hydrate(snapshot, "t1", "r1");

        // RUN_STARTED, MESSAGES_SNAPSHOT, RUN_FINISHED (no state, no activities)
        assertEquals(3, frames.size());
        assertTrue(frames.get(1) instanceof AguiEvent.MessagesSnapshot);
        assertTrue(frames.get(2) instanceof AguiEvent.RunFinished);
    }

    @Test
    void pendingOutcomePropagatesToRunFinished() {
        AguiEvent.RunFinishedOutcome outcome =
                new AguiEvent.RunFinishedInterruptOutcome(
                        List.of(
                                new AguiEvent.Interrupt(
                                        "i1", "tool_call", "msg", "tc1", null, null, null)));
        AguiThreadSnapshot snapshot =
                new AguiThreadSnapshot("t1", List.of(), Map.of(), List.of(), outcome, "r0", 1L);

        List<AguiEvent> frames = hydrator.hydrate(snapshot, "t1", "r1");
        AguiEvent.RunFinished finished = (AguiEvent.RunFinished) frames.get(frames.size() - 1);
        assertEquals(outcome, finished.outcome());
    }

    @Test
    void nullPendingOutcomeSerializesAsSuccess() {
        AguiThreadSnapshot snapshot =
                new AguiThreadSnapshot("t1", List.of(), Map.of(), List.of(), null, "r0", 1L);

        List<AguiEvent> frames = hydrator.hydrate(snapshot, "t1", "r1");
        AguiEvent.RunFinished finished = (AguiEvent.RunFinished) frames.get(frames.size() - 1);
        assertNull(finished.outcome());
    }
}
