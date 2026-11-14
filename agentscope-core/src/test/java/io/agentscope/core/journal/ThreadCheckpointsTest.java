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
package io.agentscope.core.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.core.agent.ThreadKey;
import io.agentscope.core.state.AgentState;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ThreadCheckpointsTest {
    private final InMemoryThreadJournalStore store = new InMemoryThreadJournalStore();
    private final ThreadKey key = new ThreadKey("user", "agent", "session", "main");
    private final AgentState state =
            AgentState.builder().userId("user").sessionId("session").threadId("main").build();

    @Test
    void initializationRecordsProvenanceWithoutInventingAnInvocation() {
        var log = store.open(key, null);
        ThreadCheckpoints.initialize(log, state, "snapshot", 17);
        ThreadCheckpoints.initialize(log, state, "snapshot", 17);
        assertEquals(1, log.head().seq());
        var event = log.readAfter(0, 10).get(0);
        assertEquals("state/initialized", event.type());
        assertEquals(JournalEvent.SCHEMA_VERSION, event.schemaVersion());
        assertNull(event.executionRunId());
        assertNull(event.turnId());
        assertEquals(17L, ((Number) event.data().get("sourceSequence")).longValue());
        assertEquals(state.toJson(), ThreadCheckpoints.exportState(log).toJson());
    }

    @Test
    void forkUsesDestinationThreadAndNeverCopiesAnActiveWriter() {
        var source = store.open(key, null);
        ThreadCheckpoints.initialize(source, state, "snapshot", 1);
        var destinationKey = new ThreadKey("user", "agent", "session", "research");
        var destination = store.open(destinationKey, null);
        var writer = source.acquire("active-run", Duration.ofMinutes(1));
        assertThrows(
                JournalException.class,
                () -> ThreadCheckpoints.fork(source, destination, destinationKey, "source"));
        assertEquals(0, destination.head().seq());
        source.release(writer);
        ThreadCheckpoints.fork(source, destination, destinationKey, "source");
        assertEquals("research", JournalProjection.read(destination).restore().getThreadId());
        assertEquals("main", JournalProjection.read(source).restore().getThreadId());
        assertNull(destination.readAfter(0, 1).get(0).executionRunId());
        assertEquals(1, source.head().seq());
    }

    @Test
    void administrativeReplacementCannotMoveStateIntoAnotherThread() {
        var log = store.open(key, null);
        ThreadCheckpoints.initialize(log, state, "snapshot", 1);
        var current = ThreadStateAccess.read(log, () -> state);
        var foreign =
                AgentState.builder()
                        .userId("user")
                        .sessionId("session")
                        .threadId("research")
                        .build();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ThreadStateAccess.replace(
                                log,
                                () -> state,
                                "replace",
                                new ThreadStateAccess.Snapshot(current.asOfSeq(), foreign)));
        assertEquals(current.asOfSeq(), log.head().seq());
        assertEquals("main", JournalProjection.read(log).restore().getThreadId());
        assertNull(log.head().owner());
    }
}
