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

/**
 * Describes the capabilities of a model.
 *
 * <p>This class is used to query what features a specific model supports,
 * such as native structured output, tool calling, vision, etc.
 * Agents can use this information to determine the best strategy for
 * generating structured outputs.
 */
public class ModelCapabilities {
    private final boolean supportsNativeStructuredOutput;
    private final boolean supportsToolCalling;
    private final boolean supportsVision;
    private final boolean supportsThinking;

    /**
     * Create default capabilities (only tool calling supported).
     */
    public ModelCapabilities() {
        this(false, true, false, false);
    }

    /**
     * Create capabilities with specific values.
     *
     * @param supportsNativeStructuredOutput Whether model supports native structured output API
     * @param supportsToolCalling Whether model supports tool/function calling
     * @param supportsVision Whether model supports vision/multimodal input
     * @param supportsThinking Whether model supports thinking/reasoning mode
     */
    public ModelCapabilities(
            boolean supportsNativeStructuredOutput,
            boolean supportsToolCalling,
            boolean supportsVision,
            boolean supportsThinking) {
        this.supportsNativeStructuredOutput = supportsNativeStructuredOutput;
        this.supportsToolCalling = supportsToolCalling;
        this.supportsVision = supportsVision;
        this.supportsThinking = supportsThinking;
    }

    /**
     * Check if model supports native structured output (e.g., OpenAI's response_format).
     *
     * @return true if supported
     */
    public boolean supportsNativeStructuredOutput() {
        return supportsNativeStructuredOutput;
    }

    /**
     * Check if model supports tool/function calling.
     *
     * @return true if supported
     */
    public boolean supportsToolCalling() {
        return supportsToolCalling;
    }

    /**
     * Check if model supports vision/multimodal input.
     *
     * @return true if supported
     */
    public boolean supportsVision() {
        return supportsVision;
    }

    /**
     * Check if model supports thinking/reasoning mode.
     *
     * @return true if supported
     */
    public boolean supportsThinking() {
        return supportsThinking;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private boolean supportsNativeStructuredOutput = false;
        private boolean supportsToolCalling = true;
        private boolean supportsVision = false;
        private boolean supportsThinking = false;

        public Builder supportsNativeStructuredOutput(boolean value) {
            this.supportsNativeStructuredOutput = value;
            return this;
        }

        public Builder supportsToolCalling(boolean value) {
            this.supportsToolCalling = value;
            return this;
        }

        public Builder supportsVision(boolean value) {
            this.supportsVision = value;
            return this;
        }

        public Builder supportsThinking(boolean value) {
            this.supportsThinking = value;
            return this;
        }

        public ModelCapabilities build() {
            return new ModelCapabilities(
                    supportsNativeStructuredOutput,
                    supportsToolCalling,
                    supportsVision,
                    supportsThinking);
        }
    }
}
