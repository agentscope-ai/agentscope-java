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
package io.agentscope.core.observation;

import io.agentscope.core.message.ToolExecutionDetails;
import io.agentscope.core.state.EvidenceBinding;
import java.util.LinkedHashMap;
import java.util.Map;

/** One execution attempt's lifecycle fact. RETURNED is not a business success verdict. */
public record ActionObservation(
        String actionId,
        String toolCallId,
        String toolName,
        String agentId,
        String userId,
        String sessionId,
        long startedAt,
        Long endedAt,
        Status status,
        String errorType,
        ToolExecutionDetails executionDetails,
        EvidenceBinding evidenceBinding) {

    public enum Status {
        STARTED,
        RETURNED,
        FAILED,
        DENIED,
        SUSPENDED,
        INTERRUPTED
    }

    public ActionObservation settled(
            Status status, String errorType, ToolExecutionDetails details) {
        return new ActionObservation(
                actionId,
                toolCallId,
                toolName,
                agentId,
                userId,
                sessionId,
                startedAt,
                System.currentTimeMillis(),
                status,
                errorType,
                details,
                evidenceBinding);
    }

    /** No arguments, raw output or exception messages are copied to observation events. */
    public Map<String, Object> eventPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action_id", actionId);
        if (toolCallId != null) payload.put("tool_call_id", toolCallId);
        payload.put("tool_name", toolName);
        payload.put("status", status.name());
        payload.put("started_at", startedAt);
        if (endedAt != null) payload.put("ended_at", endedAt);
        if (errorType != null) payload.put("error_type", errorType);
        if (executionDetails != null) payload.put("execution_details", executionDetails);
        if (evidenceBinding != null) payload.put("evidence_binding", evidenceBinding);
        return Map.copyOf(payload);
    }
}
