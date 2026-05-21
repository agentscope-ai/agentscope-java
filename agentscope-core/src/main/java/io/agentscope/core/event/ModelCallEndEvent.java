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
 * Emitted when the agent finishes a model (LLM) call.
 */
public class ModelCallEndEvent extends AgentEvent {

    private final String replyId;
    private final ChatUsage usage;

    @JsonCreator
    public ModelCallEndEvent(
            @JsonProperty("id") String id,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("replyId") String replyId,
            @JsonProperty("usage") ChatUsage usage) {
        super(id, createdAt);
        this.replyId = replyId;
        this.usage = usage;
    }

    public ModelCallEndEvent(String replyId, ChatUsage usage) {
        this.replyId = replyId;
        this.usage = usage;
    }

    @Override
    public AgentEventType getType() {
        return AgentEventType.MODEL_CALL_END;
    }

    public String getReplyId() {
        return replyId;
    }

    public ChatUsage getUsage() {
        return usage;
    }
}
