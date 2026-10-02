package io.agentscope.core.session;

import java.util.Set;

/** Native schema v1. Public transport names and cursors are independently versioned. */
public final class SessionEventTypes {
    private SessionEventTypes() {}

    public static final Set<String> BUILTIN =
            Set.of(
                    "run/start",
                    "run/end",
                    "run/stop_requested",
                    "turn/start",
                    "turn/end", // Read compatibility for pre-identity journals; no longer emitted.
                    "turn/resumed",
                    "turn/completed",
                    "turn/suspended",
                    "turn/failed",
                    "turn/cancelled",
                    "turn/interrupted",
                    "turn/output",
                    "step/start",
                    "step/end",
                    "input/received",
                    "input/applied",
                    "input/discarded",
                    "inbox/accepted",
                    "inbox/started",
                    "inbox/applied",
                    "inbox/opened",
                    "inbox/closed",
                    "inbox/rejected",
                    "inbox/handled",
                    "message/system",
                    "message/user",
                    "message/assistant",
                    "context/build",
                    "request/prepared",
                    "model/dispatch",
                    "model/end",
                    "model/chunk",
                    "model/retry",
                    "tool/requested",
                    "tool/decision",
                    "action/start",
                    "action/end",
                    "tool/dispatch",
                    "tool/chunk",
                    "tool/result",
                    "interaction/requested",
                    "interaction/resolved",
                    "compaction/start",
                    "compaction/end",
                    "context/replaced",
                    "task/changed",
                    "plan/changed",
                    "permission/changed",
                    "verification/result",
                    "subagent/spawned",
                    "subagent/completed",
                    "subagent/linked",
                    "recovery/applied",
                    "migration/baseline",
                    "presentation/hint",
                    "state/checkpoint",
                    "state/restored");

    public static void validate(SessionEvent event) {
        SessionEventCodecRegistry.defaultRegistry().validate(event);
    }
}
