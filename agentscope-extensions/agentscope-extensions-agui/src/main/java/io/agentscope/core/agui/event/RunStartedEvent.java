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
 * Event indicating that an agent run has started.
 *
 * <p>This is the first event emitted when an agent begins processing a request.
 */
public final class RunStartedEvent implements AguiEvent {

    private final String threadId;
    private final String runId;

    /**
     * Creates a new RunStartedEvent.
     *
     * @param threadId The thread ID
     * @param runId The run ID
     */
    @JsonCreator
    public RunStartedEvent(
            @JsonProperty("threadId") String threadId, @JsonProperty("runId") String runId) {
        this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
        this.runId = Objects.requireNonNull(runId, "runId cannot be null");
    }

    @Override
    public AguiEventType getType() {
        return AguiEventType.RUN_STARTED;
    }

    @Override
    public String getThreadId() {
        return threadId;
    }

    @Override
    public String getRunId() {
        return runId;
    }

    @Override
    public String toString() {
        return "RunStartedEvent{threadId='" + threadId + "', runId='" + runId + "'}";
    }
}
