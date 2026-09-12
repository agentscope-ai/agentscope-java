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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiMessage;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InMemoryAguiSnapshotStore}.
 */
class InMemoryAguiSnapshotStoreTest {

    private static AguiThreadSnapshot snapshot(String threadId, long updatedAt) {
        return new AguiThreadSnapshot(
                threadId,
                List.of(AguiMessage.userMessage(threadId, "hi")),
                Map.of(),
                List.of(),
                null,
                "run-1",
                updatedAt);
    }

    private static AguiThreadSnapshot snapshotWithInterrupt(String threadId, long updatedAt) {
        return new AguiThreadSnapshot(
                threadId,
                List.of(AguiMessage.userMessage(threadId, "hi")),
                Map.of(),
                List.of(),
                new AguiEvent.RunFinishedInterruptOutcome(
                        List.of(
                                new AguiEvent.Interrupt(
                                        "i1", "tool_call", "msg", "tc1", null, null, null))),
                "run-1",
                updatedAt);
    }

    @Test
    void saveFindDelete() {
        InMemoryAguiSnapshotStore store = new InMemoryAguiSnapshotStore();
        store.save(snapshot("t1", 1L));

        Optional<AguiThreadSnapshot> found = store.find("t1");
        assertTrue(found.isPresent());
        assertEquals("t1", found.get().threadId());

        store.delete("t1");
        assertTrue(store.find("t1").isEmpty());
    }

    @Test
    void clearPendingInterruptsDropsTrailingInterrupt() {
        InMemoryAguiSnapshotStore store = new InMemoryAguiSnapshotStore();
        store.save(snapshotWithInterrupt("t1", 1L));

        store.clearPendingInterrupts("t1");

        AguiThreadSnapshot found = store.find("t1").orElseThrow();
        assertEquals(null, found.pendingOutcome());
        // Messages are preserved.
        assertEquals(1, found.messages().size());
    }

    @Test
    void clearPendingInterruptsNoOpWhenNoInterrupt() {
        InMemoryAguiSnapshotStore store = new InMemoryAguiSnapshotStore();
        store.save(snapshot("t1", 1L));
        // Should not throw and should not alter the snapshot.
        store.clearPendingInterrupts("t1");
        assertEquals(null, store.find("t1").orElseThrow().pendingOutcome());
    }

    @Test
    void overflowEvictsOldest() {
        InMemoryAguiSnapshotStore store = new InMemoryAguiSnapshotStore(2);
        store.save(snapshot("t1", 1L));
        store.save(snapshot("t2", 2L));
        store.save(snapshot("t3", 3L));

        assertFalse(store.find("t1").isPresent()); // oldest evicted
        assertTrue(store.find("t2").isPresent());
        assertTrue(store.find("t3").isPresent());
    }

    @Test
    void rejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryAguiSnapshotStore(0));
    }
}
