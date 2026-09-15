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

import io.agentscope.core.agui.event.AguiEvent.JsonPatchOperation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Shared RFC 6902 diff helpers used by {@link AguiStateConverter} and
 * {@link AguiActivityConverter}.
 *
 * <p>Emits only {@code add} / {@code remove} / {@code replace} operations with RFC 6901 escaping
 * so the result is symmetric with {@link io.agentscope.core.agui.store.AguiJsonPatch#apply}.
 */
final class AguiJsonDiff {

    private AguiJsonDiff() {}

    /**
     * Compute the JSON Patch operations needed to transform {@code before} into {@code after}.
     *
     * @param before the state before changes, may be null
     * @param after the state after changes, may be null
     * @param basePath the base JSON Pointer path
     * @return list of patch operations, never null
     */
    @SuppressWarnings("unchecked")
    static List<JsonPatchOperation> computeDelta(
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
                operations.add(JsonPatchOperation.add(path, afterValue));
            } else if (!after.containsKey(key)) {
                operations.add(JsonPatchOperation.remove(path));
            } else if (!Objects.equals(beforeValue, afterValue)) {
                if (beforeValue instanceof Map && afterValue instanceof Map) {
                    operations.addAll(
                            computeDelta(
                                    (Map<String, Object>) beforeValue,
                                    (Map<String, Object>) afterValue,
                                    path));
                } else {
                    operations.add(JsonPatchOperation.replace(path, afterValue));
                }
            }
        }

        return operations;
    }

    /**
     * Check whether two states differ.
     *
     * @param before the state before changes, may be null
     * @param after the state after changes, may be null
     * @return true if there are differences
     */
    static boolean hasChanges(Map<String, Object> before, Map<String, Object> after) {
        return !computeDelta(before, after, "").isEmpty();
    }

    /**
     * Escape a string for use in a JSON Pointer (RFC 6901).
     *
     * <p>Per RFC 6901, {@code ~} must be escaped as {@code ~0} and {@code /} as {@code ~1}.
     *
     * @param value the string to escape
     * @return the escaped string
     */
    static String escapeJsonPointer(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }
}
