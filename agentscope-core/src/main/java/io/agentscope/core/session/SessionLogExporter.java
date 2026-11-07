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

import java.util.function.Consumer;

/** Durable outbox cursor. A lost ACK re-delivers the same immutable event, never re-executes it. */
public final class SessionLogExporter {
    private final SessionLog log;
    private final SessionExportSink sink;

    public SessionLogExporter(SessionLog log, SessionExportSink sink) {
        this.log = log;
        this.sink = sink;
    }

    public void drain() {
        drain(Runnable::run);
    }

    /**
     * Exports a stable prefix, handing each cursor acknowledgement to the caller. A transactional
     * sink must run these acknowledgements in order after commit, and discard them on rollback.
     * Until acknowledged, the same immutable events remain available for replay.
     */
    public synchronized void drain(Consumer<Runnable> acknowledge) {
        long cursor = log.exportCursor(sink.name());
        for (var event : log.scan(cursor, log.head().seq())) {
            sink.accept(event);
            long expected = cursor;
            long next = event.seq();
            acknowledge.accept(() -> log.advanceExportCursor(sink.name(), expected, next));
            cursor = next;
        }
    }
}
