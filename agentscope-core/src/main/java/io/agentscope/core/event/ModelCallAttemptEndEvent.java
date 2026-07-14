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
package io.agentscope.core.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.core.model.ChatUsage;

/**
 * Emitted when a model call attempt completes successfully.
 *
 * <p>Carries per-attempt usage and latency. The {@link #getUsage()} field may be {@code null}
 * when the provider does not report usage for the attempt.
 */
public class ModelCallAttemptEndEvent extends AgentEvent {

    private final String replyId;
    private final int attemptIndex;
    private final boolean success;
    private final ChatUsage usage;
    private final long latencyMs;
    private final ModelCallAttemptRole role;

    @JsonCreator
    public ModelCallAttemptEndEvent(
            @JsonProperty("id") String id,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("replyId") String replyId,
            @JsonProperty("attemptIndex") int attemptIndex,
            @JsonProperty("success") boolean success,
            @JsonProperty("usage") ChatUsage usage,
            @JsonProperty("latencyMs") long latencyMs,
            @JsonProperty("role") ModelCallAttemptRole role) {
        super(id, createdAt);
        this.replyId = replyId;
        this.attemptIndex = attemptIndex;
        this.success = success;
        this.usage = usage;
        this.latencyMs = latencyMs;
        this.role = role;
    }

    public ModelCallAttemptEndEvent(
            String replyId,
            int attemptIndex,
            boolean success,
            ChatUsage usage,
            long latencyMs,
            ModelCallAttemptRole role) {
        this.replyId = replyId;
        this.attemptIndex = attemptIndex;
        this.success = success;
        this.usage = usage;
        this.latencyMs = latencyMs;
        this.role = role;
    }

    @Override
    public AgentEventType getType() {
        return AgentEventType.MODEL_ATTEMPT_END;
    }

    public String getReplyId() {
        return replyId;
    }

    public int getAttemptIndex() {
        return attemptIndex;
    }

    public boolean isSuccess() {
        return success;
    }

    public ChatUsage getUsage() {
        return usage;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public ModelCallAttemptRole getRole() {
        return role;
    }
}
