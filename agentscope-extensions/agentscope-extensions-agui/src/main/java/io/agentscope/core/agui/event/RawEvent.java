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
 * Event containing raw/custom data.
 *
 * <p>This event type allows passing through custom data that doesn't fit into
 * the standard AG-UI event types. It can be used for application-specific
 * events, errors, or any other custom information.
 */
public final class RawEvent implements AguiEvent {

    private final String threadId;
    private final String runId;
    private final Object rawEvent;

    /**
     * Creates a new RawEvent.
     *
     * @param threadId The thread ID
     * @param runId The run ID
     * @param rawEvent The raw event data (can be any serializable object)
     */
    @JsonCreator
    public RawEvent(
            @JsonProperty("threadId") String threadId,
            @JsonProperty("runId") String runId,
            @JsonProperty("rawEvent") Object rawEvent) {
        this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
        this.runId = Objects.requireNonNull(runId, "runId cannot be null");
        this.rawEvent = rawEvent;
    }

    @Override
    public AguiEventType getType() {
        return AguiEventType.RAW;
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
     * Get the raw event data.
     *
     * @return The raw event data
     */
    public Object getRawEvent() {
        return rawEvent;
    }

    @Override
    public String toString() {
        return "RawEvent{threadId='"
                + threadId
                + "', runId='"
                + runId
                + "', rawEvent="
                + rawEvent
                + "}";
    }
}
