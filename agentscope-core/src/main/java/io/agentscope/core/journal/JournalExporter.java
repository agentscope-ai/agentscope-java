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

/** Durable outbox cursor. A lost ACK re-delivers the same immutable event, never re-executes it. */
public final class JournalExporter {
    private final ThreadJournal log;
    private final JournalExportSink sink;

    public JournalExporter(ThreadJournal log, JournalExportSink sink) {
        this.log = log;
        this.sink = sink;
    }

    public synchronized void drain() {
        drain(sink.maxEventsPerDrain());
    }

    /** Drains a bounded committed prefix, retaining the same durable acknowledgement rules. */
    public synchronized long drain(long maxEvents) {
        if (maxEvents < 1) throw new IllegalArgumentException("Export limit must be positive");
        long cursor = log.exportCursor(sink.name());
        long through = cursor + Math.min(maxEvents, log.head().seq() - cursor);
        long count = 0;
        for (var event : log.scan(cursor, through)) {
            sink.accept(event);
            log.advanceExportCursor(sink.name(), cursor, event.seq());
            cursor = event.seq();
            count++;
        }
        return count;
    }
}
