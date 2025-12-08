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
 * Event containing streaming arguments for a tool call.
 *
 * <p>This event is emitted during streaming to deliver tool arguments in chunks.
 * The delta contains a JSON fragment that, when concatenated with previous deltas,
 * forms the complete tool arguments JSON.
 */
public final class ToolCallArgsEvent implements AguiEvent {

    private final String threadId;
    private final String runId;
    private final String toolCallId;
    private final String delta;

    /**
     * Creates a new ToolCallArgsEvent.
     *
     * @param threadId The thread ID
     * @param runId The run ID
     * @param toolCallId The tool call ID this belongs to
     * @param delta The incremental JSON arguments fragment
     */
    @JsonCreator
    public ToolCallArgsEvent(
            @JsonProperty("threadId") String threadId,
            @JsonProperty("runId") String runId,
            @JsonProperty("toolCallId") String toolCallId,
            @JsonProperty("delta") String delta) {
        this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
        this.runId = Objects.requireNonNull(runId, "runId cannot be null");
        this.toolCallId = Objects.requireNonNull(toolCallId, "toolCallId cannot be null");
        this.delta = Objects.requireNonNull(delta, "delta cannot be null");
    }

    @Override
    public AguiEventType getType() {
        return AguiEventType.TOOL_CALL_ARGS;
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
     * Get the incremental JSON arguments fragment.
     *
     * @return The delta JSON fragment
     */
    public String getDelta() {
        return delta;
    }

    @Override
    public String toString() {
        return "ToolCallArgsEvent{threadId='"
                + threadId
                + "', runId='"
                + runId
                + "', toolCallId='"
                + toolCallId
                + "', delta='"
                + delta
                + "'}";
    }
}
