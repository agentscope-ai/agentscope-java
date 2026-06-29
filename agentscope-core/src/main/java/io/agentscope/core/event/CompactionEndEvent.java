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
 * Emitted when context compaction completes.
 */
public class CompactionEndEvent extends AgentEvent {

    private final int originalMessageCount;
    private final int compactedMessageCount;
    private final int estimatedTokensSaved;

    @JsonCreator
    public CompactionEndEvent(
            @JsonProperty("id") String id,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("originalMessageCount") int originalMessageCount,
            @JsonProperty("compactedMessageCount") int compactedMessageCount,
            @JsonProperty("estimatedTokensSaved") int estimatedTokensSaved) {
        super(id, createdAt);
        this.originalMessageCount = originalMessageCount;
        this.compactedMessageCount = compactedMessageCount;
        this.estimatedTokensSaved = estimatedTokensSaved;
    }

    public CompactionEndEvent(
            int originalMessageCount, int compactedMessageCount, int estimatedTokensSaved) {
        this.originalMessageCount = originalMessageCount;
        this.compactedMessageCount = compactedMessageCount;
        this.estimatedTokensSaved = estimatedTokensSaved;
    }

    @Override
    public AgentEventType getType() {
        return AgentEventType.COMPACTION_END;
    }

    public int getOriginalMessageCount() {
        return originalMessageCount;
    }

    public int getCompactedMessageCount() {
        return compactedMessageCount;
    }

    public int getEstimatedTokensSaved() {
        return estimatedTokensSaved;
    }
}
