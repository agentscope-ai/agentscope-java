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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * TrainingAnnotation represents a training annotation for an agent call.
 *
 * <p>It contains:
 * <ul>
 *   <li>enabled: whether this call should be used for training</li>
 *   <li>taskId: (optional) user-specified task ID for this request</li>
 *   <li>labels: user-defined labels for categorizing training data</li>
 *   <li>metadata: additional context information</li>
 *   <li>timestamp: when this annotation was created</li>
 * </ul>
 */
public class TrainingAnnotation {

    /** Whether this call is marked for training */
    private final boolean enabled;

    /** Optional user-specified task ID (null means auto-generate) */
    private final String taskId;

    /** User-defined labels for this training sample */
    private final List<String> labels;

    /** Additional metadata for this training sample */
    private final Map<String, Object> metadata;

    /** Timestamp when this annotation was created */
    private final long timestamp;

    /**
     * Private constructor for builder pattern.
     */
    private TrainingAnnotation(
            boolean enabled,
            String taskId,
            List<String> labels,
            Map<String, Object> metadata,
            long timestamp) {
        this.enabled = enabled;
        this.taskId = taskId;
        this.labels = labels != null ? labels : new ArrayList<>();
        this.metadata = metadata != null ? metadata : new HashMap<>();
        this.timestamp = timestamp;
    }

    // Getters
    public boolean isEnabled() {
        return enabled;
    }

    public String getTaskId() {
        return taskId;
    }

    public List<String> getLabels() {
        return labels;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Check if this annotation has expired based on the given TTL.
     *
     * @param ttlMillis Time-to-live in milliseconds
     * @return true if expired, false otherwise
     */
    public boolean isExpired(long ttlMillis) {
        return System.currentTimeMillis() - timestamp > ttlMillis;
    }

    /**
     * Create a simple enabled annotation without labels or metadata.
     */
    public static TrainingAnnotation enabled() {
        return TrainingAnnotation.builder().enabled(true).build();
    }

    /**
     * Create an enabled annotation with labels.
     */
    public static TrainingAnnotation withLabels(String... labels) {
        return TrainingAnnotation.builder().enabled(true).labels(Arrays.asList(labels)).build();
    }

    /**
     * Create an enabled annotation with labels and metadata.
     */
    public static TrainingAnnotation withLabelsAndMetadata(
            List<String> labels, Map<String, Object> metadata) {
        return TrainingAnnotation.builder()
                .enabled(true)
                .labels(labels != null ? new ArrayList<>(labels) : new ArrayList<>())
                .metadata(metadata != null ? new HashMap<>(metadata) : new HashMap<>())
                .build();
    }

    /**
     * Create a builder for TrainingAnnotation.
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TrainingAnnotation that = (TrainingAnnotation) o;
        return enabled == that.enabled
                && timestamp == that.timestamp
                && Objects.equals(taskId, that.taskId)
                && Objects.equals(labels, that.labels)
                && Objects.equals(metadata, that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, taskId, labels, metadata, timestamp);
    }

    @Override
    public String toString() {
        return "TrainingAnnotation{"
                + "enabled="
                + enabled
                + ", taskId="
                + taskId
                + ", labels="
                + labels
                + ", metadata="
                + metadata
                + ", timestamp="
                + timestamp
                + '}';
    }

    /**
     * Builder class for TrainingAnnotation.
     */
    public static class Builder {
        private boolean enabled;
        private String taskId;
        private List<String> labels = new ArrayList<>();
        private Map<String, Object> metadata = new HashMap<>();
        private long timestamp = System.currentTimeMillis();

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder labels(List<String> labels) {
            this.labels = labels != null ? labels : new ArrayList<>();
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata != null ? metadata : new HashMap<>();
            return this;
        }

        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public TrainingAnnotation build() {
            return new TrainingAnnotation(enabled, taskId, labels, metadata, timestamp);
        }
    }
}
