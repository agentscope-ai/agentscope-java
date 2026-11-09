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
package io.agentscope.builder.web.managed.selfhosted;

import io.agentscope.builder.web.managed.SessionEventDto;
import io.agentscope.builder.web.managed.SessionEventTypes;
import io.agentscope.builder.web.managed.service.SessionEventLog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Resolves committed external-execution requests, with read-only support for older rows. */
@Service
public class PendingHandsToolService {

    private final SessionEventLog eventLog;

    public PendingHandsToolService(SessionEventLog eventLog) {
        this.eventLog = eventLog;
    }

    /** Returns pending tool_use payloads (id / name / input / state) for a session. */
    public List<Map<String, Object>> listPending(String sessionId) {
        List<SessionEventDto> events = eventLog.list(sessionId);
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        Set<String> completed = new LinkedHashSet<>();
        Map<String, String> requests = new LinkedHashMap<>();
        for (SessionEventDto event : events) {
            Map<String, Object> data = event.payload() == null ? Map.of() : event.payload();
            if ("required_action.created".equals(event.type())
                    && "external_execution".equals(data.get("kind"))
                    && data.get("tool_call") instanceof Map<?, ?> call) {
                String id = stringOf(call.get("id"));
                if (id != null) {
                    Map<String, Object> pending = new LinkedHashMap<>();
                    pending.put("id", id);
                    pending.put("name", call.get("name"));
                    pending.put("input", call.get("input"));
                    pending.put("state", "pending");
                    pending.put("requestId", data.get("request_id"));
                    pending.put("turnId", data.get("turn_id"));
                    pending.put("eventId", event.id());
                    pending.put("seq", event.seq());
                    byId.put(id, pending);
                    requests.put(stringOf(data.get("request_id")), id);
                }
            } else if ("required_action.resolved".equals(event.type())) {
                String id = requests.get(stringOf(data.get("request_id")));
                if (id != null) completed.add(id);
            } else if ("item.completed".equals(event.type())
                    && data.get("item") instanceof Map<?, ?> item
                    && item.get("content") instanceof List<?> blocks) {
                for (Object block : blocks)
                    if (block instanceof Map<?, ?> value
                            && "tool_result".equals(value.get("type"))) {
                        String id = stringOf(value.get("id"));
                        if (id != null) completed.add(id);
                    }
            }

            if (SessionEventTypes.AGENT_TOOL_USE.equals(event.type())
                    || SessionEventTypes.AGENT_CUSTOM_TOOL_USE.equals(event.type())) {
                Map<String, Object> payload = event.payload() != null ? event.payload() : Map.of();
                String id = stringOf(payload.get("id"));
                if (id == null) {
                    id = stringOf(payload.get("toolCallId"));
                }
                if (id == null) {
                    continue;
                }
                Map<String, Object> copy = new LinkedHashMap<>(payload);
                copy.putIfAbsent("id", id);
                copy.put("state", "pending");
                copy.put("eventId", event.id());
                copy.put("seq", event.seq());
                byId.put(id, copy);
            } else if (SessionEventTypes.AGENT_TOOL_RESULT.equals(event.type())
                    || SessionEventTypes.USER_TOOL_RESULT.equals(event.type())
                    || SessionEventTypes.USER_CUSTOM_TOOL_RESULT.equals(event.type())) {
                Map<String, Object> payload = event.payload() != null ? event.payload() : Map.of();
                String id = stringOf(payload.get("tool_use_id"));
                if (id == null) {
                    id = stringOf(payload.get("toolUseId"));
                }
                if (id == null) {
                    id = stringOf(payload.get("id"));
                }
                if (id != null) {
                    completed.add(id);
                }
            }
        }
        List<Map<String, Object>> pending = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : byId.entrySet()) {
            if (!completed.contains(e.getKey())) {
                pending.add(e.getValue());
            }
        }
        return pending;
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
