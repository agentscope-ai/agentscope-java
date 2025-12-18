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
 * Event indicating the end of a tool call.
 *
 * <p>This event is emitted when a tool invocation completes.
 * It follows a {@link ToolCallStartEvent} and zero or more {@link ToolCallArgsEvent} events.
 */
public final class ToolCallEndEvent implements AguiEvent {

    private final String threadId;
    private final String runId;
    private final String toolCallId;
    private final String result;

    /**
     * Creates a new ToolCallEndEvent.
     *
     * @param threadId The thread ID
     * @param runId The run ID
     * @param toolCallId The unique tool call ID
     * @param result The result of the tool call
     */
    @JsonCreator
    public ToolCallEndEvent(
            @JsonProperty("threadId") String threadId,
            @JsonProperty("runId") String runId,
            @JsonProperty("toolCallId") String toolCallId,
            @JsonProperty("result") String result) {
        this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
        this.runId = Objects.requireNonNull(runId, "runId cannot be null");
        this.toolCallId = Objects.requireNonNull(toolCallId, "toolCallId cannot be null");
        this.result = result;
    }

    @Override
    public AguiEventType getType() {
        return AguiEventType.TOOL_CALL_END;
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
     * Get the result of the tool call.
     *
     * @return The result, may be null
     */
    public String getResult() {
        return result;
    }

    @Override
    public String toString() {
        return "ToolCallEndEvent{threadId='"
                + threadId
                + "', runId='"
                + runId
                + "', toolCallId='"
                + toolCallId
                + "', result='"
                + result
                + "'}";
    }
}
