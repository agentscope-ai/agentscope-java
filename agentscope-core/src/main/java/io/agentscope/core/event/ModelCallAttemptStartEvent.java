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

/**
 * Emitted when a model call attempt begins.
 *
 * <p>Each retry within a logical model call produces a new {@code MODEL_ATTEMPT_START} event
 * with an incremented {@link #getAttemptIndex()}. When a fallback model is configured and
 * activated, its attempts continue the index sequence with {@link ModelCallAttemptRole#FALLBACK}.
 */
public class ModelCallAttemptStartEvent extends AgentEvent {

    private final String replyId;
    private final int attemptIndex;
    private final int maxAttempts;
    private final String provider;
    private final String modelName;
    private final ModelCallAttemptRole role;

    @JsonCreator
    public ModelCallAttemptStartEvent(
            @JsonProperty("id") String id,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("replyId") String replyId,
            @JsonProperty("attemptIndex") int attemptIndex,
            @JsonProperty("maxAttempts") int maxAttempts,
            @JsonProperty("provider") String provider,
            @JsonProperty("modelName") String modelName,
            @JsonProperty("role") ModelCallAttemptRole role) {
        super(id, createdAt);
        this.replyId = replyId;
        this.attemptIndex = attemptIndex;
        this.maxAttempts = maxAttempts;
        this.provider = provider;
        this.modelName = modelName;
        this.role = role;
    }

    public ModelCallAttemptStartEvent(
            String replyId,
            int attemptIndex,
            int maxAttempts,
            String provider,
            String modelName,
            ModelCallAttemptRole role) {
        this.replyId = replyId;
        this.attemptIndex = attemptIndex;
        this.maxAttempts = maxAttempts;
        this.provider = provider;
        this.modelName = modelName;
        this.role = role;
    }

    @Override
    public AgentEventType getType() {
        return AgentEventType.MODEL_ATTEMPT_START;
    }

    public String getReplyId() {
        return replyId;
    }

    public int getAttemptIndex() {
        return attemptIndex;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public String getProvider() {
        return provider;
    }

    public String getModelName() {
        return modelName;
    }

    public ModelCallAttemptRole getRole() {
        return role;
    }
}
