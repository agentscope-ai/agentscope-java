/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.tool;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Interface for extended model that adds properties to tool parameters.
 */
public interface ExtendedModel {

    /**
     * Get the additional properties to merge into base schema.
     */
    Map<String, Object> getAdditionalProperties();

    /**
     * Get the additional required fields.
     */
    List<String> getAdditionalRequired();

    /**
     * Merge this extended model with base schema from tool.
     *
     * @param baseParameters Base parameter schema from AgentTool
     * @return Merged schema
     * @throws IllegalStateException if properties conflict
     */
    default Map<String, Object> mergeWithBaseSchema(Map<String, Object> baseParameters) {
        Map<String, Object> merged = new HashMap<>(baseParameters);

        // Get base properties and required
        @SuppressWarnings("unchecked")
        Map<String, Object> baseProps =
                (Map<String, Object>) merged.getOrDefault("properties", new HashMap<>());
        @SuppressWarnings("unchecked")
        List<String> baseRequired = (List<String>) merged.getOrDefault("required", List.of());

        // Merge properties with conflict detection
        Map<String, Object> extendedProps = getAdditionalProperties();
        Set<String> conflicts = new HashSet<>();
        for (String key : extendedProps.keySet()) {
            if (baseProps.containsKey(key)) {
                conflicts.add(key);
            }
        }

        if (!conflicts.isEmpty()) {
            throw new IllegalStateException(
                    "Extended model has conflicting properties with base schema: " + conflicts);
        }

        Map<String, Object> mergedProps = new HashMap<>(baseProps);
        mergedProps.putAll(extendedProps);
        merged.put("properties", mergedProps);

        // Merge required arrays
        List<String> extendedRequired = getAdditionalRequired();
        if (!extendedRequired.isEmpty()) {
            Set<String> mergedRequired = new HashSet<>(baseRequired);
            mergedRequired.addAll(extendedRequired);
            merged.put("required", List.copyOf(mergedRequired));
        }

        return merged;
    }
}
