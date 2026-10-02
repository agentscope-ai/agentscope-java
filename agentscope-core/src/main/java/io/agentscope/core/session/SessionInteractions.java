package io.agentscope.core.session;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Outstanding typed requests at a committed prefix; callers cannot invent tool outcomes. */
public final class SessionInteractions {
    private SessionInteractions() {}

    /** Resolve a tool continuation against committed pending requests, never caller-invented IDs. */
    public static String continuationTurn(SessionLog log, Set<String> requestIds) {
        if (requestIds.isEmpty())
            throw new IllegalArgumentException("Tool result IDs are required");
        var requests = pending(log);
        String turnId = null;
        for (String id : requestIds) {
            SessionEvent request = requests.get(id);
            if (request == null || !"external_execution".equals(request.data().get("kind")))
                throw new IllegalArgumentException("No pending external execution request: " + id);
            if (request.turnId() == null || request.turnId().isBlank())
                throw new IllegalArgumentException("Pending request has no logical turn: " + id);
            if (turnId != null && !turnId.equals(request.turnId()))
                throw new IllegalArgumentException("Tool results belong to different turns");
            turnId = request.turnId();
        }
        return turnId;
    }

    public static Map<String, SessionEvent> pending(SessionLog log) {
        long upper = log.head().seq(), cursor = 0;
        var result = new LinkedHashMap<String, SessionEvent>();
        while (cursor < upper) {
            var batch = log.readAfter(cursor, 256);
            if (batch.isEmpty()) throw new SessionLogException("Missing interaction prefix");
            for (var event : batch) {
                if (event.seq() > upper) break;
                if (event.type().equals("interaction/requested"))
                    result.put(String.valueOf(event.data().get("requestId")), event);
                if (event.type().equals("interaction/resolved"))
                    result.remove(String.valueOf(event.data().get("requestId")));
                cursor = event.seq();
            }
        }
        return Collections.unmodifiableMap(result);
    }
}
