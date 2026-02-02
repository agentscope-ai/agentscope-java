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

package io.agentscope.core.training.strategy;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Selection Decision - Selection decision result
 *
 * <p>Encapsulates the decision of whether to train, along with related metadata (labels, reason, etc.)
 */
public class SelectionDecision {

    /** Whether training should occur */
    private final boolean shouldTrain;

    /** Decision reason (for logging and debugging) */
    private final String reason;

    /** Training labels (optional, for classifying training data) */
    private final List<String> labels;

    /** Additional metadata (optional) */
    private final Map<String, Object> metadata;

    private SelectionDecision(
            boolean shouldTrain, String reason, List<String> labels, Map<String, Object> metadata) {
        this.shouldTrain = shouldTrain;
        this.reason = reason;
        this.labels =
                labels != null ? Collections.unmodifiableList(labels) : Collections.emptyList();
        this.metadata =
                metadata != null ? Collections.unmodifiableMap(metadata) : Collections.emptyMap();
    }

    /**
     * Create a "should train" decision
     */
    public static SelectionDecision accept(String reason) {
        return new SelectionDecision(true, reason, null, null);
    }

    /**
     * Create a "should train" decision with labels
     */
    public static SelectionDecision accept(String reason, String... labels) {
        return new SelectionDecision(true, reason, Arrays.asList(labels), null);
    }

    /**
     * Create a "should train" decision with labels and metadata
     */
    public static SelectionDecision accept(
            String reason, List<String> labels, Map<String, Object> metadata) {
        return new SelectionDecision(true, reason, labels, metadata);
    }

    /**
     * Create a "should not train" decision
     */
    public static SelectionDecision reject(String reason) {
        return new SelectionDecision(false, reason, null, null);
    }

    // Getters
    public boolean shouldTrain() {
        return shouldTrain;
    }

    public String getReason() {
        return reason;
    }

    public List<String> getLabels() {
        return labels;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    @Override
    public String toString() {
        return "SelectionDecision{"
                + "shouldTrain="
                + shouldTrain
                + ", reason='"
                + reason
                + '\''
                + ", labels="
                + labels
                + ", metadata="
                + metadata
                + '}';
    }
}
