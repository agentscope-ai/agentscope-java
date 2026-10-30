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
 * Emitted when the agent starts a model (LLM) call.
 */
public class ModelCallStartEvent extends AgentEvent {

    private final String replyId;

    /** 1-based attempt number of this model call within the same reasoning step. */
    private final int attempt;

    @JsonCreator
    public ModelCallStartEvent(
            @JsonProperty("id") String id,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("replyId") String replyId,
            @JsonProperty("attempt") Integer attempt) {
        super(id, createdAt);
        this.replyId = replyId;
        this.attempt = attempt != null ? attempt : 1;
    }

    public ModelCallStartEvent(String replyId) {
        this(replyId, 1);
    }

    public ModelCallStartEvent(String replyId, int attempt) {
        this.replyId = replyId;
        this.attempt = attempt;
    }

    @Override
    public AgentEventType getType() {
        return AgentEventType.MODEL_CALL_START;
    }

    public String getReplyId() {
        return replyId;
    }

    /**
     * Attempt number of this model call. Values greater than 1 indicate the call is a
     * retry of an earlier attempt (e.g. after an incomplete stream), letting consumers
     * reset state accumulated from the aborted attempt.
     *
     * @return 1-based attempt number
     */
    public int getAttempt() {
        return attempt;
    }
}
