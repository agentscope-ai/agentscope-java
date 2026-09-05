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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.agui.event.AguiEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SnapshotRecordingEnricher}.
 */
class SnapshotRecordingEnricherTest {

    private static AguiStreamContext context() {
        return new AguiStreamContext("t1", "r1", AguiAdapterConfig.defaultConfig());
    }

    @Test
    void savesSnapshotOnTerminalFrame() {
        InMemoryAguiSnapshotStore store = new InMemoryAguiSnapshotStore();
        SnapshotRecordingEnricher enricher = new SnapshotRecordingEnricher(store);

        List<AguiEvent> events =
                List.of(
                        new AguiEvent.TextMessageStart("t1", "r1", "m1", "assistant"),
                        new AguiEvent.TextMessageContent("t1", "r1", "m1", "hi"),
                        new AguiEvent.TextMessageEnd("t1", "r1", "m1"),
                        new AguiEvent.RunFinished("t1", "r1"));

        List<AguiEvent> result = enricher.enrich(null, events, context());

        assertEquals(events, result); // events returned unmodified
        assertTrue(store.find("t1").isPresent());
        AguiThreadSnapshot snapshot = store.find("t1").orElseThrow();
        assertEquals(1, snapshot.messages().size());
        assertEquals("hi", snapshot.messages().get(0).getTextContent());
        assertEquals("r1", snapshot.lastRunId());
    }

    @Test
    void flushSavesOnAbnormalTermination() {
        InMemoryAguiSnapshotStore store = new InMemoryAguiSnapshotStore();
        SnapshotRecordingEnricher enricher = new SnapshotRecordingEnricher(store);

        // Non-terminal events only — simulates a stream that died without a terminal frame.
        enricher.enrich(
                null,
                List.of(
                        new AguiEvent.TextMessageStart("t1", "r1", "m1", "assistant"),
                        new AguiEvent.TextMessageContent("t1", "r1", "m1", "partial")),
                context());

        assertTrue(store.find("t1").isEmpty()); // not saved yet

        enricher.flush("t1", "r1");

        assertTrue(store.find("t1").isPresent());
        assertEquals("partial", store.find("t1").orElseThrow().messages().get(0).getTextContent());
    }

    @Test
    void flushAfterTerminalIsNoOp() {
        InMemoryAguiSnapshotStore store = new InMemoryAguiSnapshotStore();
        SnapshotRecordingEnricher enricher = new SnapshotRecordingEnricher(store);

        enricher.enrich(
                null,
                List.of(
                        new AguiEvent.TextMessageStart("t1", "r1", "m1", "assistant"),
                        new AguiEvent.TextMessageEnd("t1", "r1", "m1"),
                        new AguiEvent.RunFinished("t1", "r1")),
                context());

        // Already saved on the terminal frame; flush must not throw or duplicate.
        enricher.flush("t1", "r1");

        assertTrue(store.find("t1").isPresent());
        assertEquals(1, store.find("t1").orElseThrow().messages().size());
    }

    @Test
    void nullStoreIsNoOp() {
        SnapshotRecordingEnricher enricher = new SnapshotRecordingEnricher(null);
        List<AguiEvent> events = List.of(new AguiEvent.RunFinished("t1", "r1"));
        assertEquals(events, enricher.enrich(null, events, context()));
    }
}
