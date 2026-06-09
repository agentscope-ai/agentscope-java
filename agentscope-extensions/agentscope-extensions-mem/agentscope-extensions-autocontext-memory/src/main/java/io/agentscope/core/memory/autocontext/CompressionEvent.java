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
package io.agentscope.core.memory.autocontext;

import io.agentscope.core.state.State;
import java.util.HashMap;
import java.util.Map;

/** Compression event record persisted by auto context. */
public class CompressionEvent implements State {
    public static final String TOOL_INVOCATION_COMPRESS = "TOOL_INVOCATION_COMPRESS";
    public static final String LARGE_MESSAGE_OFFLOAD_WITH_PROTECTION =
            "LARGE_MESSAGE_OFFLOAD_WITH_PROTECTION";
    public static final String LARGE_MESSAGE_OFFLOAD = "LARGE_MESSAGE_OFFLOAD";
    public static final String PREVIOUS_ROUND_CONVERSATION_SUMMARY =
            "PREVIOUS_ROUND_CONVERSATION_SUMMARY";
    public static final String CURRENT_ROUND_LARGE_MESSAGE_SUMMARY =
            "CURRENT_ROUND_LARGE_MESSAGE_SUMMARY";
    public static final String CURRENT_ROUND_MESSAGE_COMPRESS = "CURRENT_ROUND_MESSAGE_COMPRESS";

    private String eventType;
    private long timestamp;
    private int compressedMessageCount;
    private String previousMessageId;
    private String nextMessageId;
    private String compressedMessageId;
    private Map<String, Object> metadata = new HashMap<>();

    public CompressionEvent() {}

    public CompressionEvent(
            String eventType,
            long timestamp,
            int compressedMessageCount,
            String previousMessageId,
            String nextMessageId,
            String compressedMessageId,
            Map<String, Object> metadata) {
        this.eventType = eventType;
        this.timestamp = timestamp;
        this.compressedMessageCount = compressedMessageCount;
        this.previousMessageId = previousMessageId;
        this.nextMessageId = nextMessageId;
        this.compressedMessageId = compressedMessageId;
        this.metadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public int getCompressedMessageCount() {
        return compressedMessageCount;
    }

    public void setCompressedMessageCount(int compressedMessageCount) {
        this.compressedMessageCount = compressedMessageCount;
    }

    public String getPreviousMessageId() {
        return previousMessageId;
    }

    public void setPreviousMessageId(String previousMessageId) {
        this.previousMessageId = previousMessageId;
    }

    public String getNextMessageId() {
        return nextMessageId;
    }

    public void setNextMessageId(String nextMessageId) {
        this.nextMessageId = nextMessageId;
    }

    public String getCompressedMessageId() {
        return compressedMessageId;
    }

    public void setCompressedMessageId(String compressedMessageId) {
        this.compressedMessageId = compressedMessageId;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
    }

    public int getTokenBefore() {
        Object value = metadata != null ? metadata.get("tokenBefore") : null;
        return value instanceof Number n ? n.intValue() : 0;
    }

    public int getTokenAfter() {
        Object value = metadata != null ? metadata.get("tokenAfter") : null;
        return value instanceof Number n ? n.intValue() : 0;
    }

    public int getTokenReduction() {
        return getTokenBefore() - getTokenAfter();
    }

    public int getCompressInputToken() {
        Object value = metadata != null ? metadata.get("inputToken") : null;
        return value instanceof Number n ? n.intValue() : 0;
    }

    public int getCompressOutputToken() {
        Object value = metadata != null ? metadata.get("outputToken") : null;
        return value instanceof Number n ? n.intValue() : 0;
    }
}
