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

import java.util.Map;

/**
 * Represents a compression event that occurred in AutoContextMemory.
 *
 * <p>This class records information about context compression operations, including the type of
 * compression, timing, token reduction, and message positioning information.
 */
public class CompressionEvent {

    /** Event type: Compress historical tool invocations (Strategy 1). */
    public static final String TOOL_INVOCATION_COMPRESS = "TOOL_INVOCATION_COMPRESS";

    /** Event type: Offload large messages with lastKeep protection (Strategy 2). */
    public static final String LARGE_MESSAGE_OFFLOAD_WITH_PROTECTION =
            "LARGE_MESSAGE_OFFLOAD_WITH_PROTECTION";

    /** Event type: Offload large messages without protection (Strategy 3). */
    public static final String LARGE_MESSAGE_OFFLOAD = "LARGE_MESSAGE_OFFLOAD";

    /** Event type: Summarize previous round conversations (Strategy 4). */
    public static final String PREVIOUS_ROUND_CONVERSATION_SUMMARY =
            "PREVIOUS_ROUND_CONVERSATION_SUMMARY";

    /** Event type: Summarize current round large messages (Strategy 5). */
    public static final String CURRENT_ROUND_LARGE_MESSAGE_SUMMARY =
            "CURRENT_ROUND_LARGE_MESSAGE_SUMMARY";

    /** Event type: Compress current round messages (Strategy 6). */
    public static final String CURRENT_ROUND_MESSAGE_COMPRESS = "CURRENT_ROUND_MESSAGE_COMPRESS";

    private final String eventType;
    private final long timestamp;
    private final int compressedMessageCount;
    private final String previousMessageId;
    private final String nextMessageId;
    private final String compressedMessageId;
    private final Map<String, Object> metadata;

    /**
     * Creates a new compression event.
     *
     * @param eventType the type of compression event
     * @param timestamp the timestamp when the event occurred (milliseconds since epoch)
     * @param compressedMessageCount the number of messages that were compressed
     * @param previousMessageId the ID of the message before the compressed range (null if none)
     * @param nextMessageId the ID of the message after the compressed range (null if none)
     * @param compressedMessageId the ID of the compressed message (null if not a compression type)
     * @param metadata additional metadata for the event (may contain tokenBefore, tokenAfter, inputToken, outputToken, etc.)
     */
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
        this.metadata = metadata;
    }

    /**
     * Gets the event type.
     *
     * @return the event type
     */
    public String getEventType() {
        return eventType;
    }

    /**
     * Gets the timestamp when the event occurred.
     *
     * @return the timestamp in milliseconds since epoch
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Gets the number of messages that were compressed.
     *
     * @return the compressed message count
     */
    public int getCompressedMessageCount() {
        return compressedMessageCount;
    }

    /**
     * Gets the ID of the message before the compressed range.
     *
     * @return the previous message ID, or null if there is no previous message
     */
    public String getPreviousMessageId() {
        return previousMessageId;
    }

    /**
     * Gets the ID of the message after the compressed range.
     *
     * @return the next message ID, or null if there is no next message
     */
    public String getNextMessageId() {
        return nextMessageId;
    }

    /**
     * Gets the ID of the compressed message (for compression types only).
     *
     * @return the compressed message ID, or null if not a compression type
     */
    public String getCompressedMessageId() {
        return compressedMessageId;
    }

    /**
     * Gets the metadata for this event.
     *
     * @return the metadata map, which may contain tokenBefore, tokenAfter, inputToken, outputToken, etc.
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * Gets the token count before compression from metadata.
     *
     * @return the token count before compression, or 0 if not available
     */
    public int getTokenBefore() {
        if (metadata != null && metadata.get("tokenBefore") instanceof Number) {
            return ((Number) metadata.get("tokenBefore")).intValue();
        }
        return 0;
    }

    /**
     * Gets the token count after compression from metadata.
     *
     * @return the token count after compression, or 0 if not available
     */
    public int getTokenAfter() {
        if (metadata != null && metadata.get("tokenAfter") instanceof Number) {
            return ((Number) metadata.get("tokenAfter")).intValue();
        }
        return 0;
    }

    /**
     * Gets the token reduction amount.
     *
     * @return the difference between tokenBefore and tokenAfter
     */
    public int getTokenReduction() {
        return getTokenBefore() - getTokenAfter();
    }

    /**
     * Gets the input tokens consumed by the compression operation from metadata.
     *
     * @return the input tokens consumed during compression, or 0 if not available
     */
    public int getCompressInputToken() {
        if (metadata != null && metadata.get("inputToken") instanceof Number) {
            return ((Number) metadata.get("inputToken")).intValue();
        }
        return 0;
    }

    /**
     * Gets the output tokens consumed by the compression operation from metadata.
     *
     * @return the output tokens consumed during compression, or 0 if not available
     */
    public int getCompressOutputToken() {
        if (metadata != null && metadata.get("outputToken") instanceof Number) {
            return ((Number) metadata.get("outputToken")).intValue();
        }
        return 0;
    }
}
