/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.live.config;

import java.util.Objects;

/**
 * Generation parameter configuration.
 *
 * <p>Controls model generation behavior such as temperature, sampling, etc.
 */
public final class GenerationConfig {

    private final Float temperature;
    private final Float topP;
    private final Integer topK;
    private final Integer maxTokens;

    private GenerationConfig(Builder builder) {
        this.temperature = builder.temperature;
        this.topP = builder.topP;
        this.topK = builder.topK;
        this.maxTokens = builder.maxTokens;
    }

    public Float getTemperature() {
        return temperature;
    }

    public Float getTopP() {
        return topP;
    }

    public Integer getTopK() {
        return topK;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public static GenerationConfig defaults() {
        return builder().temperature(0.8f).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder().temperature(temperature).topP(topP).topK(topK).maxTokens(maxTokens);
    }

    public static class Builder {
        private Float temperature;
        private Float topP;
        private Integer topK;
        private Integer maxTokens;

        public Builder temperature(Float temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder topP(Float topP) {
            this.topP = topP;
            return this;
        }

        public Builder topK(Integer topK) {
            this.topK = topK;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public GenerationConfig build() {
            return new GenerationConfig(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GenerationConfig that = (GenerationConfig) o;
        return Objects.equals(temperature, that.temperature)
                && Objects.equals(topP, that.topP)
                && Objects.equals(topK, that.topK)
                && Objects.equals(maxTokens, that.maxTokens);
    }

    @Override
    public int hashCode() {
        return Objects.hash(temperature, topP, topK, maxTokens);
    }
}
