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
package io.agentscope.harness.agent.subagent.task;

import java.util.List;
import java.util.Objects;

/** Durable identity needed to resume a subagent task suspended for permission approval. */
public record TaskSuspension(
        String userId,
        String parentSessionId,
        String subSessionId,
        String replyId,
        List<PendingToolCall> pendingToolCalls) {

    public TaskSuspension {
        Objects.requireNonNull(parentSessionId, "parentSessionId must not be null");
        Objects.requireNonNull(subSessionId, "subSessionId must not be null");
        Objects.requireNonNull(replyId, "replyId must not be null");
        pendingToolCalls = pendingToolCalls == null ? List.of() : List.copyOf(pendingToolCalls);
    }

    /** Stable tool identity carried without duplicating tool input or permission decisions. */
    public record PendingToolCall(String toolCallId, String toolName) {
        public PendingToolCall {
            Objects.requireNonNull(toolCallId, "toolCallId must not be null");
            Objects.requireNonNull(toolName, "toolName must not be null");
        }
    }
}
