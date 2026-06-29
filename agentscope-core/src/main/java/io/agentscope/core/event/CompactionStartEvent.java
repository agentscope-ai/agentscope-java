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
 * Emitted when context compaction is triggered.
 */
public class CompactionStartEvent extends AgentEvent {

    private final int estimatedTokens;
    private final int triggerThreshold;

    @JsonCreator
    public CompactionStartEvent(
            @JsonProperty("id") String id,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("estimatedTokens") int estimatedTokens,
            @JsonProperty("triggerThreshold") int triggerThreshold) {
        super(id, createdAt);
        this.estimatedTokens = estimatedTokens;
        this.triggerThreshold = triggerThreshold;
    }

    public CompactionStartEvent(int estimatedTokens, int triggerThreshold) {
        this.estimatedTokens = estimatedTokens;
        this.triggerThreshold = triggerThreshold;
    }

    @Override
    public AgentEventType getType() {
        return AgentEventType.COMPACTION_START;
    }

    public int getEstimatedTokens() {
        return estimatedTokens;
    }

    public int getTriggerThreshold() {
        return triggerThreshold;
    }
}
