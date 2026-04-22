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
package io.agentscope.core.rag.integration.haystack.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SparseEmbedding {

    @JsonProperty("indices")
    private List<Integer> indices;

    @JsonProperty("values")
    private List<Float> values;

    private SparseEmbedding(Builder builder) {
        this.indices = builder.indices;
        this.values = builder.values;
    }

    public List<Integer> getIndices() {
        return indices;
    }

    public List<Float> getValues() {
        return values;
    }

    public static class Builder {

        private List<Integer> indices;

        private List<Float> values;

        /**
         * List of indices of non-zero elements in the embedding.
         *
         * @param indices list of indices
         * @return this builder
         */
        public Builder indices(List<Integer> indices) {
            this.indices = indices;
            return this;
        }

        /**
         * List of values of non-zero elements in the embedding.
         *
         * @param values list of values
         * @return this builder
         */
        public Builder values(List<Float> values) {
            this.values = values;
            return this;
        }

        public SparseEmbedding build() {
            return new SparseEmbedding(this);
        }
    }

    @Override
    public String toString() {
        return "SparseEmbedding{" + "indices=" + indices + ", values=" + values + '}';
    }
}
