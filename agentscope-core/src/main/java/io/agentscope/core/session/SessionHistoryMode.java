package io.agentscope.core.session;

/** Exactly one authority: standalone state-store persistence or native committed history. */
public enum SessionHistoryMode {
    LEGACY,
    EVENT_LOG
}
