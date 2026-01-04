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
package io.agentscope.core.model;

/**
 * Represents token usage information for chat completion responses.
 *
 * <p>This immutable data class tracks the number of tokens used during a chat completion,
 * including input tokens (prompt), output tokens (generated response), and execution time.
 */
public class ChatUsage {

    private final int inputTokens;
    private final int outputTokens;
    private final double time;

    /**
     * Creates a new ChatUsage instance.
     *
     * @param inputTokens the number of tokens used for the input/prompt
     * @param outputTokens the number of tokens used for the output/generated response
     * @param time the execution time in seconds
     */
    public ChatUsage(int inputTokens, int outputTokens, double time) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.time = time;
    }

    /**
     * Gets the number of input tokens used.
     *
     * @return the number of tokens used for the input/prompt
     */
    public int getInputTokens() {
        return inputTokens;
    }

    /**
     * Gets the number of output tokens used.
     *
     * @return the number of tokens used for the output/generated response
     */
    public int getOutputTokens() {
        return outputTokens;
    }

    /**
     * Gets the total number of tokens used.
     *
     * @return the sum of input and output tokens
     */
    public int getTotalTokens() {
        return inputTokens + outputTokens;
    }

    /**
     * Gets the execution time.
     *
     * @return the execution time in seconds
     */
    public double getTime() {
        return time;
    }

    /**
     * Creates a new builder for ChatUsage.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for creating ChatUsage instances.
     */
    public static class Builder {
        private int inputTokens;
        private int outputTokens;
        private double time;

        /**
         * Sets the number of input tokens.
         *
         * @param inputTokens the number of tokens used for the input/prompt
         * @return this builder instance
         */
        public Builder inputTokens(int inputTokens) {
            this.inputTokens = inputTokens;
            return this;
        }

        /**
         * Sets the number of output tokens.
         *
         * @param outputTokens the number of tokens used for the output/generated response
         * @return this builder instance
         */
        public Builder outputTokens(int outputTokens) {
            this.outputTokens = outputTokens;
            return this;
        }

        /**
         * Sets the execution time.
         *
         * @param time the execution time in seconds
         * @return this builder instance
         */
        public Builder time(double time) {
            this.time = time;
            return this;
        }

        /**
         * Builds a new ChatUsage instance with the set values.
         *
         * @return a new ChatUsage instance
         */
        public ChatUsage build() {
            return new ChatUsage(inputTokens, outputTokens, time);
        }
    }
}
