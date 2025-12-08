/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agui.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * Event containing incremental text content for a message.
 *
 * <p>This event is emitted during streaming to deliver text content in chunks.
 * Multiple content events may be emitted between a {@link TextMessageStartEvent}
 * and {@link TextMessageEndEvent}.
 */
public final class TextMessageContentEvent implements AguiEvent {

    private final String threadId;
    private final String runId;
    private final String messageId;
    private final String delta;

    /**
     * Creates a new TextMessageContentEvent.
     *
     * @param threadId The thread ID
     * @param runId The run ID
     * @param messageId The message ID this content belongs to
     * @param delta The incremental text content
     */
    @JsonCreator
    public TextMessageContentEvent(
            @JsonProperty("threadId") String threadId,
            @JsonProperty("runId") String runId,
            @JsonProperty("messageId") String messageId,
            @JsonProperty("delta") String delta) {
        this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
        this.runId = Objects.requireNonNull(runId, "runId cannot be null");
        this.messageId = Objects.requireNonNull(messageId, "messageId cannot be null");
        this.delta = Objects.requireNonNull(delta, "delta cannot be null");
    }

    @Override
    public AguiEventType getType() {
        return AguiEventType.TEXT_MESSAGE_CONTENT;
    }

    @Override
    public String getThreadId() {
        return threadId;
    }

    @Override
    public String getRunId() {
        return runId;
    }

    /**
     * Get the message ID this content belongs to.
     *
     * @return The message ID
     */
    public String getMessageId() {
        return messageId;
    }

    /**
     * Get the incremental text content.
     *
     * @return The delta text
     */
    public String getDelta() {
        return delta;
    }

    @Override
    public String toString() {
        return "TextMessageContentEvent{threadId='"
                + threadId
                + "', runId='"
                + runId
                + "', messageId='"
                + messageId
                + "', delta='"
                + delta
                + "'}";
    }
}
