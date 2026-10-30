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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionLogExporterTest {
    private SessionLog log;
    private final List<Long> received = new ArrayList<>();
    private SessionExportSink sink;

    @BeforeEach
    void prepare() {
        log = new InMemorySessionLogStore().open(new SessionKey("user", "agent", "session"), null);
        var writer = log.acquire("writer", Duration.ofMinutes(1));
        log.commit(
                writer,
                "batch",
                0,
                List.of(
                        new SessionEvent(1, "one", 1, 1, "run/start", null, "turn", true, "{}"),
                        new SessionEvent(1, "two", 2, 2, "run/end", null, "turn", true, "{}")));
        log.release(writer);
        sink =
                new SessionExportSink() {
                    public String name() {
                        return "test";
                    }

                    public void accept(SessionEvent event) {
                        received.add(event.seq());
                    }
                };
    }

    @Test
    void defaultDrainAcknowledgesEachEventAndDoesNotReplay() {
        var exporter = new SessionLogExporter(log, sink);
        exporter.drain();
        exporter.drain();
        assertEquals(List.of(1L, 2L), received);
        assertEquals(2, log.exportCursor(sink.name()));
    }

    @Test
    void deferredAcknowledgementsAdvanceInOrderAndTolerateAnotherExporter() {
        var acknowledgements = new ArrayList<Runnable>();
        new SessionLogExporter(log, sink).drain(acknowledgements::add);
        assertEquals(0, log.exportCursor(sink.name()));
        acknowledgements.get(0).run();
        assertEquals(1, log.exportCursor(sink.name()));
        new SessionLogExporter(log, sink).drain();
        acknowledgements.get(1).run();
        assertEquals(2, log.exportCursor(sink.name()));
        assertEquals(List.of(1L, 2L, 2L), received);
    }

    @Test
    void discardingAcknowledgementsLeavesEventsAvailableForReplay() {
        new SessionLogExporter(log, sink).drain(ignored -> {});
        assertEquals(0, log.exportCursor(sink.name()));
        new SessionLogExporter(log, sink).drain();
        assertEquals(List.of(1L, 2L, 1L, 2L), received);
    }

    @Test
    void failedSinkDoesNotAcknowledgeTheFailedEvent() {
        var acknowledgements = new ArrayList<Runnable>();
        var failing =
                new SessionExportSink() {
                    public String name() {
                        return sink.name();
                    }

                    public void accept(SessionEvent event) {
                        if (event.seq() == 2) throw new IllegalStateException("delivery failed");
                    }
                };
        assertThrows(
                IllegalStateException.class,
                () -> new SessionLogExporter(log, failing).drain(acknowledgements::add));
        assertEquals(1, acknowledgements.size());
        assertEquals(0, log.exportCursor(sink.name()));
    }
}
