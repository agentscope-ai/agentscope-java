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
