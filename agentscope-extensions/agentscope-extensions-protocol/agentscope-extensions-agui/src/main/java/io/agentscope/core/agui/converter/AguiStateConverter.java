/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
package io.agentscope.core.agui.converter;

import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.AguiEvent.JsonPatchOperation;
import java.util.List;
import java.util.Map;

/**
 * Converter for state management in the AG-UI protocol.
 *
 * <p>This class handles the creation of state events (snapshots and deltas)
 * from AgentScope's state system.
 */
public class AguiStateConverter {

    /**
     * Create a STATE_SNAPSHOT event from a state map.
     *
     * @param state The state map
     * @param threadId The thread ID
     * @param runId The run ID
     * @return The StateSnapshot event
     */
    public AguiEvent.StateSnapshot createSnapshot(
            Map<String, Object> state, String threadId, String runId) {
        return new AguiEvent.StateSnapshot(threadId, runId, state);
    }

    /**
     * Create a STATE_DELTA event by comparing before and after states.
     *
     * <p>This method generates JSON Patch operations (RFC 6902) that can transform
     * the "before" state into the "after" state.
     *
     * @param before The state before changes
     * @param after The state after changes
     * @param threadId The thread ID
     * @param runId The run ID
     * @return The StateDelta event, or null if there are no changes
     */
    public AguiEvent.StateDelta createDelta(
            Map<String, Object> before, Map<String, Object> after, String threadId, String runId) {
        List<JsonPatchOperation> operations = AguiJsonDiff.computeDelta(before, after, "");

        if (operations.isEmpty()) {
            return null; // No changes
        }

        return new AguiEvent.StateDelta(threadId, runId, operations);
    }

    /**
     * Check if there are any differences between two states.
     *
     * @param before The state before changes
     * @param after The state after changes
     * @return true if there are differences
     */
    public boolean hasChanges(Map<String, Object> before, Map<String, Object> after) {
        return AguiJsonDiff.hasChanges(before, after);
    }
}
