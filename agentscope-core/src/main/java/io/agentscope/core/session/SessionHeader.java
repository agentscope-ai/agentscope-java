package io.agentscope.core.session;

/** Immutable coverage/format marker. A checkpoint baseline does not pretend to contain earlier history. */
public record SessionHeader(
        int formatVersion, String authority, String replayCoverage, long createdAt) {
    public static SessionHeader current() {
        return new SessionHeader(1, "event_log", "adapter_chunks", System.currentTimeMillis());
    }

    public void validate() {
        if (formatVersion != 1 || !"event_log".equals(authority))
            throw new SessionLogException("Unsupported session format/authority");
    }
}
