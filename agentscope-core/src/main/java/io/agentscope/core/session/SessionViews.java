package io.agentscope.core.session;

import io.agentscope.core.message.Msg;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JsonUtils;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Rebuildable human/history views; context replacement never erases earlier messages. */
public final class SessionViews {
    private SessionViews() {}

    public record Transcript(long asOfSeq, List<Msg> messages) {}

    public static Transcript transcript(SessionLog log) {
        long target = log.head().seq();
        var messages = new LinkedHashMap<String, Msg>();
        for (var event : log.scan(0, target)) {
            if (event.type().equals("migration/baseline")) {
                String state = (String) event.data().get("stateJson");
                if (state != null)
                    for (var msg : AgentState.fromJsonString(state).getContext())
                        messages.putIfAbsent(msg.getId(), msg);
            } else if (Set.of("message/user", "message/assistant", "tool/result", "turn/output")
                    .contains(event.type())) {
                Object value = event.data().get("message");
                if (value != null) {
                    Msg msg = JsonUtils.getJsonCodec().convertValue(value, Msg.class);
                    if (event.type().equals("turn/output")) messages.put(msg.getId(), msg);
                    else messages.putIfAbsent(msg.getId(), msg);
                }
            }
        }
        return new Transcript(target, List.copyOf(messages.values()));
    }
}
