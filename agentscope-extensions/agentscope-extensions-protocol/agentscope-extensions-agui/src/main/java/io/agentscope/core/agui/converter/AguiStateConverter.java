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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
        List<JsonPatchOperation> operations = computeDelta(before, after, "");

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
        return !computeDelta(before, after, "").isEmpty();
    }

    /**
     * Compute the JSON Patch operations needed to transform "before" into "after".
     *
     * @param before The state before changes
     * @param after The state after changes
     * @param basePath The base JSON Pointer path
     * @return List of JsonPatchOperations
     */
    @SuppressWarnings("unchecked")
    private List<JsonPatchOperation> computeDelta(
            Map<String, Object> before, Map<String, Object> after, String basePath) {
        List<JsonPatchOperation> operations = new ArrayList<>();

        if (before == null && after == null) {
            return operations;
        }

        if (before == null) {
            before = Map.of();
        }

        if (after == null) {
            after = Map.of();
        }

        Set<String> allKeys = new HashSet<>();
        allKeys.addAll(before.keySet());
        allKeys.addAll(after.keySet());

        for (String key : allKeys) {
            String path = basePath + "/" + escapeJsonPointer(key);
            Object beforeValue = before.get(key);
            Object afterValue = after.get(key);

            if (!before.containsKey(key)) {
                // Key was added
                operations.add(JsonPatchOperation.add(path, afterValue));
            } else if (!after.containsKey(key)) {
                // Key was removed
                operations.add(JsonPatchOperation.remove(path));
            } else if (!Objects.equals(beforeValue, afterValue)) {
                // Value changed
                if (beforeValue instanceof Map && afterValue instanceof Map) {
                    // Recurse into nested maps
                    operations.addAll(
                            computeDelta(
                                    (Map<String, Object>) beforeValue,
                                    (Map<String, Object>) afterValue,
                                    path));
                } else {
                    // Replace value
                    operations.add(JsonPatchOperation.replace(path, afterValue));
                }
            }
        }

        return operations;
    }

    /**
     * Escape a string for use in a JSON Pointer (RFC 6901).
     *
     * @param value The string to escape
     * @return The escaped string
     */
    private String escapeJsonPointer(String value) {
        // Per RFC 6901, ~ must be escaped as ~0 and / as ~1
        return value.replace("~", "~0").replace("/", "~1");
    }
}
