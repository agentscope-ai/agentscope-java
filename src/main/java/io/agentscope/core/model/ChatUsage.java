/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.agentscope.core.model;

public class ChatUsage {

    private final int inputTokens;
    private final int outputTokens;
    private final double time;

    public ChatUsage(int inputTokens, int outputTokens, double time) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.time = time;
    }

    public int getInputTokens() {
        return inputTokens;
    }

    public int getOutputTokens() {
        return outputTokens;
    }

    public int getTotalTokens() {
        return inputTokens + outputTokens;
    }

    public double getTime() {
        return time;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int inputTokens;
        private int outputTokens;
        private double time;

        public Builder inputTokens(int inputTokens) {
            this.inputTokens = inputTokens;
            return this;
        }

        public Builder outputTokens(int outputTokens) {
            this.outputTokens = outputTokens;
            return this;
        }

        public Builder time(double time) {
            this.time = time;
            return this;
        }

        public ChatUsage build() {
            return new ChatUsage(inputTokens, outputTokens, time);
        }
    }
}
