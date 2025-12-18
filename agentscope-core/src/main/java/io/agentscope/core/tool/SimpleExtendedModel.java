package io.agentscope.core.tool;

import java.util.List;
import java.util.Map;

/**
 * Simple implementation of ExtendedModel using maps.
 */
public class SimpleExtendedModel implements ExtendedModel {

    private final Map<String, Object> additionalProperties;
    private final List<String> additionalRequired;

    public SimpleExtendedModel(
            Map<String, Object> additionalProperties, List<String> additionalRequired) {
        this.additionalProperties = additionalProperties;
        this.additionalRequired = additionalRequired;
    }

    @Override
    public Map<String, Object> getAdditionalProperties() {
        return additionalProperties;
    }

    @Override
    public List<String> getAdditionalRequired() {
        return additionalRequired;
    }
}
