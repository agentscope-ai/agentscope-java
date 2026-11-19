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
 * Submission metadata that must outlive the HTTP request that created the task.
 *
 * <p>{@code detail} decides how much of the run reaches SSE subscribers and {@code userId} feeds
 * the run's {@link io.agentscope.core.agent.RuntimeContext}; both are read again on every {@code
 * /resume}, which may be served by a different instance than the original {@code POST /tasks}.
 * {@code parentAgentId} and {@code parentSessionId} identify the submitting agent and conversation
 * for reporting; neither selects the agent or session a run executes in. {@code raw} is the
 * submission {@code context} map exactly as received, so custom keys keep reaching the {@link
 * AgentFactory} across HITL pauses.
 *
 * @param userId parsed from {@code context.user_id}
 * @param parentSessionId parsed from {@code context.parent_session_id}
 * @param parentAgentId parsed from {@code context.parent_agent_id}
 * @param detail stream detail level; defaults to {@code status} when absent or blank
 * @param raw the submission {@code context} map exactly as received
 */
public record ProtocolTaskContext(
        String userId,
        String parentSessionId,
        String parentAgentId,
        String detail,
        Map<String, Object> raw) {

    public ProtocolTaskContext {
        // JSON payloads may contain null values, so Map.copyOf is not usable here.
        raw =
                raw == null || raw.isEmpty()
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(raw));
    }

    /** Context used when a caller submitted no {@code context} at all. */
    public static ProtocolTaskContext empty() {
        return new ProtocolTaskContext(null, null, null, "status", Map.of());
    }

    /** Parses the protocol fields out of a submission {@code context} map. */
    public static ProtocolTaskContext from(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return empty();
        }
        String userId = stringVal(raw.get("user_id"));
        String parentSessionId = stringVal(raw.get("parent_session_id"));
        String parentAgentId =
                stringVal(raw.get(AgentProtocolConstants.CONTEXT_PARENT_AGENT_ID_FIELD));
        String detail = stringVal(raw.get("detail"));
        if (detail == null || detail.isBlank()) {
            detail = "status";
        }
        return new ProtocolTaskContext(userId, parentSessionId, parentAgentId, detail, raw);
    }

    /**
     * Session the submission runs in, or {@code null} when the caller did not name one.
     *
     * <p>Read from {@code context.session_id} and consumed by {@code submit}; every {@code /resume}
     * keeps the {@link #raw()} map for the {@link AgentFactory}.
     */
    public String sessionId() {
        return stringVal(raw.get(AgentProtocolConstants.CONTEXT_SESSION_ID_FIELD));
    }

    private static String stringVal(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
