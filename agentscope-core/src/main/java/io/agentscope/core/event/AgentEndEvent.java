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
 * Emitted when an agent finishes processing an invocation.
 */
public class AgentEndEvent extends AgentEvent {

    /**
     * Metadata key describing whether a synthesized invocation end succeeded, failed, or cancelled.
     *
     * <p>It is written by the producer that synthesizes an end on behalf of a forwarded invocation —
     * for example the harness spawn tool, which tags every subagent end with {@link #OUTCOME_SUCCESS},
     * {@link #OUTCOME_ERROR} or {@link #OUTCOME_CANCELLED}. {@link
     * AgentEventStreams#withTextOutputDisposition} only treats a subagent end as a normal completion
     * when this key is present and set to {@link #OUTCOME_SUCCESS}, so a producer that synthesizes
     * subagent ends should set it to keep those replies classifiable.
     */
    public static final String METADATA_INVOCATION_OUTCOME = "invocationOutcome";

    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_ERROR = "error";
    public static final String OUTCOME_CANCELLED = "cancelled";

    private final String replyId;

    @JsonCreator
    public AgentEndEvent(
            @JsonProperty("id") String id,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("replyId") String replyId) {
        super(id, createdAt);
        this.replyId = replyId;
    }

    public AgentEndEvent(String replyId) {
        this.replyId = replyId;
    }

    @Override
    public AgentEventType getType() {
        return AgentEventType.AGENT_END;
    }

    public String getReplyId() {
        return replyId;
    }
}
