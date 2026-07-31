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
 * Emitted when the primary model is exhausted and the fallback model is activated.
 *
 * <p>This event is only produced when a {@code fallbackModel} is configured via
 * {@link io.agentscope.core.ReActAgent.Builder#fallbackModel} and the primary model
 * has exhausted all retry attempts.
 */
public class ModelFallbackActivatedEvent extends AgentEvent {

    private final String replyId;
    private final int failedAttemptCount;
    private final String fromModel;
    private final String toModel;

    @JsonCreator
    public ModelFallbackActivatedEvent(
            @JsonProperty("id") String id,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("replyId") String replyId,
            @JsonProperty("failedAttemptCount") int failedAttemptCount,
            @JsonProperty("fromModel") String fromModel,
            @JsonProperty("toModel") String toModel) {
        super(id, createdAt);
        this.replyId = replyId;
        this.failedAttemptCount = failedAttemptCount;
        this.fromModel = fromModel;
        this.toModel = toModel;
    }

    public ModelFallbackActivatedEvent(
            String replyId, int failedAttemptCount, String fromModel, String toModel) {
        this.replyId = replyId;
        this.failedAttemptCount = failedAttemptCount;
        this.fromModel = fromModel;
        this.toModel = toModel;
    }

    @Override
    public AgentEventType getType() {
        return AgentEventType.MODEL_FALLBACK_ACTIVATED;
    }

    public String getReplyId() {
        return replyId;
    }

    public int getFailedAttemptCount() {
        return failedAttemptCount;
    }

    public String getFromModel() {
        return fromModel;
    }

    public String getToModel() {
        return toModel;
    }
}
