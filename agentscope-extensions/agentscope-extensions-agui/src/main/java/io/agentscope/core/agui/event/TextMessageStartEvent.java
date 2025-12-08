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
 * Event indicating the start of a text message.
 *
 * <p>This event is emitted when the agent begins generating a text response.
 * It is followed by zero or more {@link TextMessageContentEvent} events
 * and concluded with a {@link TextMessageEndEvent}.
 */
public final class TextMessageStartEvent implements AguiEvent {

    private final String threadId;
    private final String runId;
    private final String messageId;
    private final String role;

    /**
     * Creates a new TextMessageStartEvent.
     *
     * @param threadId The thread ID
     * @param runId The run ID
     * @param messageId The unique message ID
     * @param role The message role (user, assistant, system, tool)
     */
    @JsonCreator
    public TextMessageStartEvent(
            @JsonProperty("threadId") String threadId,
            @JsonProperty("runId") String runId,
            @JsonProperty("messageId") String messageId,
            @JsonProperty("role") String role) {
        this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
        this.runId = Objects.requireNonNull(runId, "runId cannot be null");
        this.messageId = Objects.requireNonNull(messageId, "messageId cannot be null");
        this.role = Objects.requireNonNull(role, "role cannot be null");
    }

    @Override
    public AguiEventType getType() {
        return AguiEventType.TEXT_MESSAGE_START;
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
     * Get the message ID.
     *
     * @return The message ID
     */
    public String getMessageId() {
        return messageId;
    }

    /**
     * Get the message role.
     *
     * @return The role (user, assistant, system, tool)
     */
    public String getRole() {
        return role;
    }

    @Override
    public String toString() {
        return "TextMessageStartEvent{threadId='"
                + threadId
                + "', runId='"
                + runId
                + "', messageId='"
                + messageId
                + "', role='"
                + role
                + "'}";
    }
}
