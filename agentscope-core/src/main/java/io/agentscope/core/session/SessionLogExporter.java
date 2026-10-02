package io.agentscope.core.session;

/** Durable outbox cursor. A lost ACK re-delivers the same immutable event, never re-executes it. */
public final class SessionLogExporter {
    private final SessionLog log;
    private final SessionExportSink sink;

    public SessionLogExporter(SessionLog log, SessionExportSink sink) {
        this.log = log;
        this.sink = sink;
    }

    public synchronized void drain() {
        long cursor = log.exportCursor(sink.name());
        for (var event : log.scan(cursor, log.head().seq())) {
            sink.accept(event);
            log.advanceExportCursor(sink.name(), cursor, event.seq());
            cursor = event.seq();
        }
    }
}
