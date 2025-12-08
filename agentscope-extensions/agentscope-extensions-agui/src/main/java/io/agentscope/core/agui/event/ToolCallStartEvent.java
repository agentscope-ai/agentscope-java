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
 * Event indicating the start of a tool call.
 *
 * <p>This event is emitted when the agent begins a tool invocation.
 * It is followed by zero or more {@link ToolCallArgsEvent} events
 * and concluded with a {@link ToolCallEndEvent}.
 */
public final class ToolCallStartEvent implements AguiEvent {

    private final String threadId;
    private final String runId;
    private final String toolCallId;
    private final String toolCallName;

    /**
     * Creates a new ToolCallStartEvent.
     *
     * @param threadId The thread ID
     * @param runId The run ID
     * @param toolCallId The unique tool call ID
     * @param toolCallName The name of the tool being called
     */
    @JsonCreator
    public ToolCallStartEvent(
            @JsonProperty("threadId") String threadId,
            @JsonProperty("runId") String runId,
            @JsonProperty("toolCallId") String toolCallId,
            @JsonProperty("toolCallName") String toolCallName) {
        this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
        this.runId = Objects.requireNonNull(runId, "runId cannot be null");
        this.toolCallId = Objects.requireNonNull(toolCallId, "toolCallId cannot be null");
        this.toolCallName = Objects.requireNonNull(toolCallName, "toolCallName cannot be null");
    }

    @Override
    public AguiEventType getType() {
        return AguiEventType.TOOL_CALL_START;
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
     * Get the tool call ID.
     *
     * @return The tool call ID
     */
    public String getToolCallId() {
        return toolCallId;
    }

    /**
     * Get the tool name.
     *
     * @return The tool name
     */
    public String getToolCallName() {
        return toolCallName;
    }

    @Override
    public String toString() {
        return "ToolCallStartEvent{threadId='"
                + threadId
                + "', runId='"
                + runId
                + "', toolCallId='"
                + toolCallId
                + "', toolCallName='"
                + toolCallName
                + "'}";
    }
}
