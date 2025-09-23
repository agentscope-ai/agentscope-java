/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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

public class GenerateOptions {
    private Double temperature;
    private Double topP;
    private Integer maxTokens;
    private Double frequencyPenalty;
    private Double presencePenalty;

    public GenerateOptions() {}

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Double getTopP() {
        return topP;
    }

    public void setTopP(Double topP) {
        this.topP = topP;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Double getFrequencyPenalty() {
        return frequencyPenalty;
    }

    public void setFrequencyPenalty(Double frequencyPenalty) {
        this.frequencyPenalty = frequencyPenalty;
    }

    public Double getPresencePenalty() {
        return presencePenalty;
    }

    public void setPresencePenalty(Double presencePenalty) {
        this.presencePenalty = presencePenalty;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final GenerateOptions o = new GenerateOptions();

        public Builder temperature(Double v) {
            o.setTemperature(v);
            return this;
        }

        public Builder topP(Double v) {
            o.setTopP(v);
            return this;
        }

        public Builder maxTokens(Integer v) {
            o.setMaxTokens(v);
            return this;
        }

        public Builder frequencyPenalty(Double v) {
            o.setFrequencyPenalty(v);
            return this;
        }

        public Builder presencePenalty(Double v) {
            o.setPresencePenalty(v);
            return this;
        }

        public GenerateOptions build() {
            return o;
        }
    }
}
