/*
 * Copyright 2024-2025 the original author or authors.
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

package io.agentscope.core.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Immutable generation options for LLM models.
 * Use the builder pattern to construct instances.
 */
public class GenerateOptions {
    private final Double temperature;
    private final Double topP;
    private final Integer maxTokens;
    private final Double frequencyPenalty;
    private final Double presencePenalty;
    private final Map<String, Object> additionalOptions;

    private GenerateOptions(Builder builder) {
        this.temperature = builder.temperature;
        this.topP = builder.topP;
        this.maxTokens = builder.maxTokens;
        this.frequencyPenalty = builder.frequencyPenalty;
        this.presencePenalty = builder.presencePenalty;
        this.additionalOptions =
                builder.additionalOptions != null
                        ? Collections.unmodifiableMap(new HashMap<>(builder.additionalOptions))
                        : Collections.emptyMap();
    }

    public Double getTemperature() {
        return temperature;
    }

    public Double getTopP() {
        return topP;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public Double getFrequencyPenalty() {
        return frequencyPenalty;
    }

    public Double getPresencePenalty() {
        return presencePenalty;
    }

    public Map<String, Object> getAdditionalOptions() {
        return additionalOptions;
    }

    public Object getAdditionalOption(String key) {
        return additionalOptions.get(key);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Double temperature;
        private Double topP;
        private Integer maxTokens;
        private Double frequencyPenalty;
        private Double presencePenalty;
        private Map<String, Object> additionalOptions;

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder topP(Double topP) {
            this.topP = topP;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder frequencyPenalty(Double frequencyPenalty) {
            this.frequencyPenalty = frequencyPenalty;
            return this;
        }

        public Builder presencePenalty(Double presencePenalty) {
            this.presencePenalty = presencePenalty;
            return this;
        }

        public Builder additionalOption(String key, Object value) {
            if (this.additionalOptions == null) {
                this.additionalOptions = new HashMap<>();
            }
            this.additionalOptions.put(key, value);
            return this;
        }

        public GenerateOptions build() {
            return new GenerateOptions(this);
        }
    }
}
