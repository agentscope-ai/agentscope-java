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

import io.agentscope.core.agent.ThreadKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class JournalExporterTest {
    @Test
    void boundedDrainsResumeFromTheAcknowledgedPrefix() {
        var log = log();
        var delivered = new ArrayList<Long>();
        var sink =
                new JournalExportSink() {
                    public String name() {
                        return "bounded";
                    }

                    public int maxEventsPerDrain() {
                        return 2;
                    }

                    public void accept(JournalEvent event) {
                        delivered.add(event.seq());
                    }
                };
        var exporter = new JournalExporter(log, sink);
        exporter.drain();
        assertEquals(List.of(1L, 2L), delivered);
        assertEquals(2, log.exportCursor(sink.name()));
        exporter.drain();
        assertEquals(List.of(1L, 2L, 3L, 4L), delivered);
        assertEquals(4, log.exportCursor(sink.name()));
        assertEquals(0, exporter.drain(2));
    }

    @Test
    void failedDeliveryCannotAdvanceTheDurableCursor() {
        var log = log();
        var sink =
                new JournalExportSink() {
                    public String name() {
                        return "failed";
                    }

                    public void accept(JournalEvent event) {
                        if (event.seq() == 2)
                            throw new IllegalStateException("transport unavailable");
                    }
                };
        assertThrows(IllegalStateException.class, () -> new JournalExporter(log, sink).drain(2));
        assertEquals(1, log.exportCursor(sink.name()));
    }

    private ThreadJournal log() {
        var log =
                new InMemoryThreadJournalStore()
                        .open(new ThreadKey("user", "agent", "session", "main"), null);
        var writer = log.acquire("writer", Duration.ofMinutes(1));
        var events = new ArrayList<JournalEvent>();
        for (long seq = 1; seq <= 4; seq++)
            events.add(
                    new JournalEvent(
                            JournalEvent.SCHEMA_VERSION,
                            "event-" + seq,
                            seq,
                            1,
                            "model/chunk",
                            "run",
                            "turn",
                            true,
                            "{}"));
        log.commit(writer, "batch", 0, events);
        return log;
    }
}
