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
