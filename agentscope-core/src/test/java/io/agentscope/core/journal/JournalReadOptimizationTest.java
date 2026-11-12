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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.ThreadKey;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JournalReadOptimizationTest {
    private JournalEvent event(long seq, String type, Map<String, Object> data) {
        return new JournalEvent(
                JournalEvent.SCHEMA_VERSION,
                "e" + seq,
                seq,
                1,
                type,
                "earlier",
                "turn",
                true,
                JsonUtils.getJsonCodec().toJson(data));
    }

    @Test
    void projectionMaterializesOnlyItsLastCheckpointAndKeepsEarlierUncertainCalls() {
        var log = mock(ThreadJournal.class, CALLS_REAL_METHODS);
        var old = event(1, "state/checkpoint", Map.of("ref", "old"));
        var dispatch = event(2, "tool/dispatch", Map.of("toolCallId", "call"));
        var latest = event(3, "state/checkpoint", Map.of("ref", "latest"));
        var state = AgentState.builder().build();
        state.contextMutable().add(new UserMessage("latest state"));
        var body = event(3, "state/checkpoint", Map.of("stateJson", state.toJson()));
        when(log.head())
                .thenReturn(
                        new ThreadJournal.Head(
                                ThreadJournal.FORMAT_VERSION, 3, 1, null, 0, "batch"));
        when(log.readMetadataAfter(0, 3)).thenReturn(List.of(old, dispatch, latest));
        doAnswer(
                        call -> {
                            JournalEvent value = call.getArgument(0);
                            if (!value.equals(latest))
                                throw new AssertionError("Historical checkpoint was loaded");
                            return body;
                        })
                .when(log)
                .materialize(any());
        clearInvocations(log);
        var observed = new ArrayList<JournalEvent>();
        var projection = JournalProjection.read(log, observed::add);
        assertEquals(3, projection.asOfSeq());
        assertEquals(state.toJson(), projection.restore().toJson());
        assertEquals(Set.of("call"), projection.uncertainToolCalls());
        assertEquals(List.of(old, dispatch, latest), observed);
        verify(log, times(1)).readMetadataAfter(0, 3);
        verify(log, times(1)).materialize(any());
    }

    @Test
    void recorderCollectsValidationAndHistoricalMessageIdsInOnePrefixScan() {
        var log =
                spy(
                        new InMemoryThreadJournalStore()
                                .open(new ThreadKey("u", "a", "s", "main"), null));
        var writer = log.acquire("seed", Duration.ofMinutes(1));
        var old = AgentState.builder().build();
        var historical = new UserMessage("before context replacement");
        old.contextMutable().add(historical);
        var current = AgentState.builder().build();
        log.commit(
                writer,
                "seed",
                0,
                List.of(
                        event(1, "state/initialized", Map.of("stateJson", old.toJson())),
                        event(2, "state/checkpoint", Map.of("stateJson", current.toJson()))));
        log.release(writer);
        clearInvocations(log);
        var recorder = new InvocationRecorder(log, "turn", "new-run", null);
        try {
            assertEquals(current.toJson(), recorder.initial().restore().toJson());
            assertTrue(recorder.acceptedMessageIds().contains(historical.getId()));
            verify(log, times(1)).scanMetadata(0, 2);
            verify(log, times(1)).scan(0, 2);
            verify(log, never()).readAfter(anyLong(), anyInt());
        } finally {
            recorder.close();
        }
    }

    @Test
    void mergedStartupStillRejectsReusedRunsAndUnknownOutcomes() {
        for (var fact :
                List.of(
                        event(1, "run/start", Map.of()),
                        event(1, "tool/dispatch", Map.of("toolCallId", "call")),
                        event(1, "test/unknown-required", Map.of()))) {
            var log = mock(ThreadJournal.class, CALLS_REAL_METHODS);
            var writer = new ThreadJournal.Writer("earlier", 1);
            when(log.acquire(any(), any())).thenReturn(writer);
            when(log.head())
                    .thenReturn(
                            new ThreadJournal.Head(
                                    ThreadJournal.FORMAT_VERSION, 1, 1, null, 0, "batch"));
            when(log.readMetadataAfter(0, 1)).thenReturn(List.of(fact));
            String run = "run/start".equals(fact.type()) ? "earlier" : "new-run";
            assertThrows(
                    JournalException.class, () -> new InvocationRecorder(log, "turn", run, null));
            verify(log).release(writer);
        }
    }

    @Test
    void metadataScanStillFailsOnMissingCommittedPrefix() {
        var log = mock(ThreadJournal.class, CALLS_REAL_METHODS);
        when(log.head())
                .thenReturn(
                        new ThreadJournal.Head(
                                ThreadJournal.FORMAT_VERSION, 1, 1, null, 0, "batch"));
        when(log.readMetadataAfter(anyLong(), anyInt())).thenReturn(List.of());
        assertThrows(JournalException.class, () -> JournalProjection.read(log));
    }
}
