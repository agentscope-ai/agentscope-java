/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.agentprotocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everything an {@link AgentFactory} needs to pick or build the agent for one task run.
 *
 * @param taskId task identifier, also used as the agent session id
 * @param agentId requested agent id from {@code POST /tasks}
 * @param input user input; empty on a resume run, which carries confirmation decisions instead
 * @param userId parsed from {@code context.user_id}
 * @param parentSessionId parsed from {@code context.parent_session_id}
 * @param resume whether this run resumes a task that was awaiting tool confirmation
 * @param context the submission {@code context} map exactly as received, including any custom keys
 */
public record AgentRequest(
        String taskId,
        String agentId,
        String input,
        String userId,
        String parentSessionId,
        boolean resume,
        Map<String, Object> context) {

    public AgentRequest {
        // JSON payloads may contain null values, so Map.copyOf is not usable here.
        context =
                context == null || context.isEmpty()
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(context));
    }

    /** Returns the raw {@code context} value for {@code key}, or {@code null} when absent. */
    public Object contextValue(String key) {
        return key == null ? null : context.get(key);
    }

    /** Returns the {@code context} value for {@code key} as a string, or {@code null}. */
    public String contextString(String key) {
        Object v = contextValue(key);
        return v == null ? null : String.valueOf(v);
    }

    /**
     * Caller-defined attributes from {@code context.attributes}, or an empty map when the caller
     * sent none. These are the values propagated into the agent's {@link
     * io.agentscope.core.agent.RuntimeContext}; the surrounding {@link #context()} also holds the
     * protocol's own fields.
     */
    public Map<String, Object> attributes() {
        Object raw = context.get(AgentProtocolConstants.CONTEXT_ATTRIBUTES_FIELD);
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /** Returns the {@code context.attributes} value for {@code key} as a string, or {@code null}. */
    public String attributeString(String key) {
        Object v = key == null ? null : attributes().get(key);
        return v == null ? null : String.valueOf(v);
    }
}
