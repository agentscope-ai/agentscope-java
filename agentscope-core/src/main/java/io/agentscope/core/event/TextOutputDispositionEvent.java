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
import io.agentscope.core.message.GenerateReason;
import java.util.Objects;

/**
 * Classifies an already-streamed model reply as intermediate or terminal for its agent invocation.
 *
 * <p>A terminal disposition is a lifecycle signal, not an authoritative final answer. Consumers
 * must use {@link AgentResultEvent} for the invocation result.
 */
public final class TextOutputDispositionEvent extends AgentEvent {

    private final String replyId;
    private final TextOutputDisposition disposition;
    private final GenerateReason generateReason;

    public TextOutputDispositionEvent(
            String replyId, TextOutputDisposition disposition, GenerateReason generateReason) {
        this.replyId = replyId;
        this.disposition = Objects.requireNonNull(disposition, "disposition");
        this.generateReason = generateReason;
    }

    @JsonCreator
    public TextOutputDispositionEvent(
            @JsonProperty("id") String id,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("replyId") String replyId,
            @JsonProperty("disposition") TextOutputDisposition disposition,
            @JsonProperty("generateReason") GenerateReason generateReason) {
        super(id, createdAt);
        this.replyId = replyId;
        this.disposition = Objects.requireNonNull(disposition, "disposition");
        this.generateReason = generateReason;
    }

    @Override
    public AgentEventType getType() {
        return AgentEventType.TEXT_OUTPUT_DISPOSITION;
    }

    public String getReplyId() {
        return replyId;
    }

    public TextOutputDisposition getDisposition() {
        return disposition;
    }

    public GenerateReason getGenerateReason() {
        return generateReason;
    }
}
