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
 *
 * <p>{@code callId} identifies the prepared request this call consumed — the same id the
 * {@link io.agentscope.core.middleware.ModelRequestPreparer} uses to publish its {@code
 * ContextManifest} into the {@link io.agentscope.core.agent.RuntimeContext}. Observers can
 * therefore correlate the call with the post-compaction request shape; null on events emitted
 * before this field existed (older serialized payloads deserialize as null) (#4269).
 */
public class ModelCallEndEvent extends AgentEvent {

    private final String replyId;
    private final ChatUsage usage;
    private final String callId;

    @JsonCreator
    public ModelCallEndEvent(
            @JsonProperty("id") String id,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("replyId") String replyId,
            @JsonProperty("usage") ChatUsage usage,
            @JsonProperty("callId") String callId) {
        super(id, createdAt);
        this.replyId = replyId;
        this.usage = usage;
        this.callId = callId;
    }

    public ModelCallEndEvent(String replyId, ChatUsage usage) {
        this(replyId, usage, null);
    }

    public ModelCallEndEvent(String replyId, ChatUsage usage, String callId) {
        this.replyId = replyId;
        this.usage = usage;
        this.callId = callId;
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

    /** Returns the prepared-request id, or null on legacy events. */
    public String getCallId() {
        return callId;
    }
}
