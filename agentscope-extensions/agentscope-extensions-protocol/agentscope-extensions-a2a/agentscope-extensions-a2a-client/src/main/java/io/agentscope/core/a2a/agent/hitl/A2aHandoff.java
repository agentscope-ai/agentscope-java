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

package io.agentscope.core.a2a.agent.hitl;

import io.agentscope.core.a2a.agent.message.MessageConstants;
import io.agentscope.core.message.Msg;
import io.agentscope.core.util.JsonUtils;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Serializable durable resume capability returned by the A2A client. */
public record A2aHandoff(
        String taskId,
        String contextId,
        String handoffId,
        A2aHandoffType type,
        Instant expiresAt,
        List<A2aPendingTool> pendingTools,
        String resumeToken) {

    public A2aHandoff {
        requireText(taskId, "taskId");
        requireText(contextId, "contextId");
        requireText(handoffId, "handoffId");
        type = Objects.requireNonNull(type, "type must not be null");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        pendingTools = pendingTools == null ? List.of() : List.copyOf(pendingTools);
        if (pendingTools.isEmpty()) {
            throw new IllegalArgumentException("pendingTools must not be empty");
        }
        Set<String> ids = new HashSet<>();
        for (A2aPendingTool pendingTool : pendingTools) {
            Objects.requireNonNull(pendingTool, "pendingTools must not contain null");
            if (!ids.add(pendingTool.toolCallId())) {
                throw new IllegalArgumentException("pendingTools must not contain duplicate IDs");
            }
        }
        requireText(resumeToken, "resumeToken");
    }

    /** Parse only the local client enhancement, never best-effort wire metadata. */
    public static Optional<A2aHandoff> tryFrom(Msg msg) {
        if (msg == null || msg.getMetadata() == null) {
            return Optional.empty();
        }
        Object value = msg.getMetadata().get(MessageConstants.LOCAL_HANDOFF_METADATA_KEY);
        if (value instanceof A2aHandoff handoff) {
            return Optional.of(handoff);
        }
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(JsonUtils.getJsonCodec().convertValue(value, A2aHandoff.class));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public String toString() {
        return "A2aHandoff[resumeToken=<redacted>]";
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
