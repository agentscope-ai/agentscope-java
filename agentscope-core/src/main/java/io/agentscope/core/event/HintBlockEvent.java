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
 * One-shot hint block event.
 *
 * <p>Unlike text/thinking blocks, hint blocks are not streamed — the full content is available at
 * creation time (team messages, background tool results, user interruptions, etc.). A single event
 * carries the complete hint.
 */
public class HintBlockEvent extends AgentEvent {

    private final String replyId;
    private final String blockId;
    private final String hintSource;
    private final String hint;

    @JsonCreator
    public HintBlockEvent(
            @JsonProperty("id") String id,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("replyId") String replyId,
            @JsonProperty("blockId") String blockId,
            @JsonProperty("hintSource") String hintSource,
            @JsonProperty("hint") String hint) {
        super(id, createdAt);
        this.replyId = replyId;
        this.blockId = blockId;
        this.hintSource = hintSource;
        this.hint = hint;
    }

    public HintBlockEvent(String replyId, String blockId, String hintSource, String hint) {
        this.replyId = replyId;
        this.blockId = blockId;
        this.hintSource = hintSource;
        this.hint = hint;
    }

    @Override
    public AgentEventType getType() {
        return AgentEventType.HINT_BLOCK;
    }

    public String getReplyId() {
        return replyId;
    }

    public String getBlockId() {
        return blockId;
    }

    /**
     * Returns the sender or origin of this hint. For team messages this is the sender's display
     * name (e.g. {@code "alice"}); for system notifications it may be {@code "system"} or
     * {@code null}.
     *
     * <p>Named {@code hintSource} to avoid collision with {@link AgentEvent#getSource()} which
     * carries the subagent forwarding path.
     */
    public String getHintSource() {
        return hintSource;
    }

    public String getHint() {
        return hint;
    }
}
