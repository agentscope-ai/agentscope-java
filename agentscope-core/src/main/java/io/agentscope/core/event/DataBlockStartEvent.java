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

public class DataBlockStartEvent extends AgentEvent {

    private final String replyId;
    private final String blockId;

    @JsonCreator
    public DataBlockStartEvent(
            @JsonProperty("id") String id,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("replyId") String replyId,
            @JsonProperty("blockId") String blockId) {
        super(id, createdAt);
        this.replyId = replyId;
        this.blockId = blockId;
    }

    public DataBlockStartEvent(String replyId, String blockId) {
        this.replyId = replyId;
        this.blockId = blockId;
    }

    @Override
    public AgentEventType getType() {
        return AgentEventType.DATA_BLOCK_START;
    }

    public String getReplyId() {
        return replyId;
    }

    public String getBlockId() {
        return blockId;
    }
}
