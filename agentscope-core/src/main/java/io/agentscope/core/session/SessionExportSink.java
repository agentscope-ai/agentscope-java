package io.agentscope.core.session;

/** Acknowledged committed-event export. Implementations must deduplicate by native eventId. */
public interface SessionExportSink {
    String CONTEXT_KEY = "agentscope.session.exportSink";

    String name();

    void accept(SessionEvent event);
}
