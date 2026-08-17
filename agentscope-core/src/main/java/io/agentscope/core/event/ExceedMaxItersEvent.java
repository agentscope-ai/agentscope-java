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
 * Emitted when the agent's ReAct loop exceeds the configured maximum iterations.
 */
public class ExceedMaxItersEvent extends AgentEvent {

    private final String replyId;
    private final int maxIters;
    private final int currentIter;

    @JsonCreator
    public ExceedMaxItersEvent(
            @JsonProperty("id") String id,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("replyId") String replyId,
            @JsonProperty("maxIters") int maxIters,
            @JsonProperty("currentIter") int currentIter) {
        super(id, createdAt);
        this.replyId = replyId;
        this.maxIters = maxIters;
        this.currentIter = currentIter;
    }

    public ExceedMaxItersEvent(String replyId, int maxIters, int currentIter) {
        this.replyId = replyId;
        this.maxIters = maxIters;
        this.currentIter = currentIter;
    }

    @Override
    public AgentEventType getType() {
        return AgentEventType.EXCEED_MAX_ITERS;
    }

    public String getReplyId() {
        return replyId;
    }

    public int getMaxIters() {
        return maxIters;
    }

    public int getCurrentIter() {
        return currentIter;
    }
}
