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

/**
 * Metadata filter condition for Dify knowledge base retrieval.
 *
 * <p>Each condition specifies a filter on document metadata fields.
 *
 * <p>Example usage:
 * <pre>{@code
 * MetadataFilterCondition condition = MetadataFilterCondition.builder()
 *     .name("category")
 *     .comparisonOperator("equals")
 *     .value("technical")
 *     .build();
 * }</pre>
 */
public class MetadataFilterCondition {

    private final String name;
    private final String comparisonOperator;
    private final String value;

    private MetadataFilterCondition(Builder builder) {
        this.name = builder.name;
        this.comparisonOperator = builder.comparisonOperator;
        this.value = builder.value;
    }

    /**
     * Gets the metadata field name.
     *
     * @return the field name
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the comparison operator.
     *
     * @return the comparison operator (e.g., "equals", "contains", "not_equals")
     */
    public String getComparisonOperator() {
        return comparisonOperator;
    }

    /**
     * Gets the filter value.
     *
     * @return the value to compare against
     */
    public String getValue() {
        return value;
    }

    /**
     * Creates a new builder for MetadataFilterCondition.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for MetadataFilterCondition.
     */
    public static class Builder {
        private String name;
        private String comparisonOperator;
        private String value;

        private Builder() {}

        /**
         * Sets the metadata field name.
         *
         * @param name the field name
         * @return this builder for method chaining
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets the comparison operator.
         *
         * <p>Common operators:
         * <ul>
         *   <li>equals - exact match
         *   <li>not_equals - not equal
         *   <li>contains - substring match
         *   <li>not_contains - does not contain
         *   <li>starts_with - prefix match
         *   <li>ends_with - suffix match
         * </ul>
         *
         * @param comparisonOperator the comparison operator
         * @return this builder for method chaining
         */
        public Builder comparisonOperator(String comparisonOperator) {
            this.comparisonOperator = comparisonOperator;
            return this;
        }

        /**
         * Sets the filter value.
         *
         * @param value the value to compare against
         * @return this builder for method chaining
         */
        public Builder value(String value) {
            this.value = value;
            return this;
        }

        /**
         * Builds a new MetadataFilterCondition instance.
         *
         * @return a new MetadataFilterCondition instance
         */
        public MetadataFilterCondition build() {
            return new MetadataFilterCondition(this);
        }
    }

    @Override
    public String toString() {
        return "MetadataFilterCondition{"
                + "name='"
                + name
                + '\''
                + ", comparisonOperator='"
                + comparisonOperator
                + '\''
                + ", value='"
                + value
                + '\''
                + '}';
    }
}
