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
package io.agentscope.core.rag.integration.dify;

import java.util.ArrayList;
import java.util.List;

/**
 * Metadata filter configuration for Dify knowledge base retrieval.
 *
 * <p>This class allows filtering documents based on their metadata fields.
 * Multiple conditions can be combined using a logical operator (AND/OR).
 *
 * <p>Example usage:
 * <pre>{@code
 * MetadataFilter filter = MetadataFilter.builder()
 *     .logicalOperator("and")
 *     .addCondition(MetadataFilterCondition.builder()
 *         .name("category")
 *         .comparisonOperator("equals")
 *         .value("technical")
 *         .build())
 *     .addCondition(MetadataFilterCondition.builder()
 *         .name("language")
 *         .comparisonOperator("equals")
 *         .value("en")
 *         .build())
 *     .build();
 * }</pre>
 */
public class MetadataFilter {

    private final String logicalOperator;
    private final List<MetadataFilterCondition> conditions;

    private MetadataFilter(Builder builder) {
        this.logicalOperator = builder.logicalOperator != null ? builder.logicalOperator : "and";
        this.conditions =
                builder.conditions != null
                        ? new ArrayList<>(builder.conditions)
                        : new ArrayList<>();
    }

    /**
     * Gets the logical operator for combining conditions.
     *
     * @return the logical operator ("and" or "or")
     */
    public String getLogicalOperator() {
        return logicalOperator;
    }

    /**
     * Gets the list of filter conditions.
     *
     * @return a copy of the conditions list
     */
    public List<MetadataFilterCondition> getConditions() {
        return new ArrayList<>(conditions);
    }

    /**
     * Creates a new builder for MetadataFilter.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for MetadataFilter.
     */
    public static class Builder {
        private String logicalOperator;
        private List<MetadataFilterCondition> conditions;

        private Builder() {}

        /**
         * Sets the logical operator for combining conditions.
         *
         * <p>Default: "and"
         *
         * @param logicalOperator "and" or "or"
         * @return this builder for method chaining
         */
        public Builder logicalOperator(String logicalOperator) {
            this.logicalOperator = logicalOperator;
            return this;
        }

        /**
         * Sets the list of filter conditions.
         *
         * @param conditions the list of conditions
         * @return this builder for method chaining
         */
        public Builder conditions(List<MetadataFilterCondition> conditions) {
            this.conditions = conditions != null ? new ArrayList<>(conditions) : null;
            return this;
        }

        /**
         * Adds a single filter condition.
         *
         * @param condition the condition to add
         * @return this builder for method chaining
         */
        public Builder addCondition(MetadataFilterCondition condition) {
            if (this.conditions == null) {
                this.conditions = new ArrayList<>();
            }
            this.conditions.add(condition);
            return this;
        }

        /**
         * Builds a new MetadataFilter instance.
         *
         * @return a new MetadataFilter instance
         */
        public MetadataFilter build() {
            return new MetadataFilter(this);
        }
    }

    @Override
    public String toString() {
        return "MetadataFilter{"
                + "logicalOperator='"
                + logicalOperator
                + '\''
                + ", conditions="
                + conditions
                + '}';
    }
}
