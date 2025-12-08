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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Event containing a full state snapshot.
 *
 * <p>This event replaces the entire client-side state with the provided snapshot.
 * It is typically emitted at the start or end of a run to synchronize state.
 */
public final class StateSnapshotEvent implements AguiEvent {

    private final String threadId;
    private final String runId;
    private final Map<String, Object> snapshot;

    /**
     * Creates a new StateSnapshotEvent.
     *
     * @param threadId The thread ID
     * @param runId The run ID
     * @param snapshot The complete state snapshot
     */
    @JsonCreator
    public StateSnapshotEvent(
            @JsonProperty("threadId") String threadId,
            @JsonProperty("runId") String runId,
            @JsonProperty("snapshot") Map<String, Object> snapshot) {
        this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
        this.runId = Objects.requireNonNull(runId, "runId cannot be null");
        this.snapshot =
                snapshot != null
                        ? Collections.unmodifiableMap(new HashMap<>(snapshot))
                        : Collections.emptyMap();
    }

    @Override
    public AguiEventType getType() {
        return AguiEventType.STATE_SNAPSHOT;
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
     * Get the complete state snapshot.
     *
     * @return The state snapshot as an immutable map
     */
    public Map<String, Object> getSnapshot() {
        return snapshot;
    }

    @Override
    public String toString() {
        return "StateSnapshotEvent{threadId='"
                + threadId
                + "', runId='"
                + runId
                + "', snapshot="
                + snapshot
                + "}";
    }
}
