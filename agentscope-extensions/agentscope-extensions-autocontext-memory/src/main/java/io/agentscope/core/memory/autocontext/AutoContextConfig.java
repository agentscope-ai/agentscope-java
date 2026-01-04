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
package io.agentscope.core.memory.autocontext;

/**
 * Configuration class for AutoContextMemory.
 *
 * <p>This class contains all configurable parameters for the AutoContextMemory system,
 * including storage backends, compression thresholds, and offloading settings.
 *
 * <p><b>Key Configuration Areas:</b>
 * <ul>
 *   <li><b>Storage:</b> Working storage and original history storage backends</li>
 *   <li><b>Compression Triggers:</b> Message count and token count thresholds</li>
 *   <li><b>Offloading:</b> Large payload thresholds and preview lengths</li>
 *   <li><b>Protection:</b> Number of recent messages to keep uncompressed</li>
 * </ul>
 *
 * <p>All fields have default values and can be customized via builder pattern.
 */
public class AutoContextConfig {

    /** Threshold (in characters) for large payload messages to be offloaded. */
    long largePayloadThreshold = 5 * 1024;

    /** Maximum token limit for context window. */
    long maxToken = 128 * 1024;

    /** Token ratio threshold (0.0-1.0) to trigger compression. */
    double tokenRatio = 0.75;

    /** Preview length (in characters) for offloaded messages. */
    int offloadSinglePreview = 200;

    /** Message count threshold to trigger compression. */
    int msgThreshold = 100;

    /** Number of recent messages to keep uncompressed. */
    int lastKeep = 50;

    /** Minimum number of consecutive tool messages required for compression. */
    int minConsecutiveToolMessages = 6;

    /** Compression ratio (0.0-1.0) for current round messages. Default is 0.3 (30%). */
    double currentRoundCompressionRatio = 0.3;

    /**
     * Optional custom prompt configuration.
     * If null, default prompts from {@link Prompts} will be used.
     */
    private PromptConfig customPrompt;

    public long getLargePayloadThreshold() {
        return largePayloadThreshold;
    }

    public long getMaxToken() {
        return maxToken;
    }

    public double getTokenRatio() {
        return tokenRatio;
    }

    public int getOffloadSinglePreview() {
        return offloadSinglePreview;
    }

    public int getMsgThreshold() {
        return msgThreshold;
    }

    public int getLastKeep() {
        return lastKeep;
    }

    public int getMinConsecutiveToolMessages() {
        return minConsecutiveToolMessages;
    }

    public double getCurrentRoundCompressionRatio() {
        return currentRoundCompressionRatio;
    }

    /**
     * Gets the custom prompt configuration.
     *
     * @return the custom prompt configuration, or null if using defaults
     */
    public PromptConfig getCustomPrompt() {
        return customPrompt;
    }

    /**
     * Creates a new Builder instance for constructing AutoContextConfig.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder class for constructing AutoContextConfig instances.
     *
     * <p>This builder provides a fluent API for configuring AutoContextMemory parameters.
     * All fields have default values matching those in AutoContextConfig.
     *
     * <p>Example usage:
     * <pre>{@code
     * AutoContextConfig config = AutoContextConfig.builder()
     *     .msgThreshold(50)
     *     .maxToken(64 * 1024)
     *     .lastKeep(20)
     *     .build();
     * }</pre>
     */
    public static class Builder {
        private long largePayloadThreshold = 5 * 1024;
        private long maxToken = 128 * 1024;
        private double tokenRatio = 0.75;
        private int offloadSinglePreview = 200;
        private int msgThreshold = 100;
        private int lastKeep = 50;
        private int minConsecutiveToolMessages = 6;
        private double currentRoundCompressionRatio = 0.3;
        private PromptConfig customPrompt;

        /**
         * Sets the threshold (in characters) for large payload messages to be offloaded.
         *
         * @param largePayloadThreshold the threshold in characters
         * @return this builder instance for method chaining
         */
        public Builder largePayloadThreshold(long largePayloadThreshold) {
            this.largePayloadThreshold = largePayloadThreshold;
            return this;
        }

        /**
         * Sets the maximum token limit for context window.
         *
         * @param maxToken the maximum token count
         * @return this builder instance for method chaining
         */
        public Builder maxToken(long maxToken) {
            this.maxToken = maxToken;
            return this;
        }

        /**
         * Sets the token ratio threshold (0.0-1.0) to trigger compression.
         *
         * @param tokenRatio the token ratio (0.0-1.0)
         * @return this builder instance for method chaining
         */
        public Builder tokenRatio(double tokenRatio) {
            this.tokenRatio = tokenRatio;
            return this;
        }

        /**
         * Sets the preview length (in characters) for offloaded messages.
         *
         * @param offloadSinglePreview the preview length in characters
         * @return this builder instance for method chaining
         */
        public Builder offloadSinglePreview(int offloadSinglePreview) {
            this.offloadSinglePreview = offloadSinglePreview;
            return this;
        }

        /**
         * Sets the message count threshold to trigger compression.
         *
         * @param msgThreshold the message count threshold
         * @return this builder instance for method chaining
         */
        public Builder msgThreshold(int msgThreshold) {
            this.msgThreshold = msgThreshold;
            return this;
        }

        /**
         * Sets the number of recent messages to keep uncompressed.
         *
         * @param lastKeep the number of messages to keep
         * @return this builder instance for method chaining
         */
        public Builder lastKeep(int lastKeep) {
            this.lastKeep = lastKeep;
            return this;
        }

        /**
         * Sets the minimum number of consecutive tool messages required for compression.
         *
         * @param minConsecutiveToolMessages the minimum consecutive tool messages count
         * @return this builder instance for method chaining
         */
        public Builder minConsecutiveToolMessages(int minConsecutiveToolMessages) {
            this.minConsecutiveToolMessages = minConsecutiveToolMessages;
            return this;
        }

        /**
         * Sets the compression ratio (0.0-1.0) for current round messages.
         * Default is 0.3 (30%), meaning the compressed output should be approximately
         * 30% of the original token count.
         *
         * @param currentRoundCompressionRatio the compression ratio (0.0-1.0)
         * @return this builder instance for method chaining
         */
        public Builder currentRoundCompressionRatio(double currentRoundCompressionRatio) {
            this.currentRoundCompressionRatio = currentRoundCompressionRatio;
            return this;
        }

        /**
         * Sets custom prompt configuration.
         *
         * <p>If provided, custom prompts will be used instead of default prompts from {@link Prompts}.
         * If null, default prompts will be used.
         *
         * @param customPrompt the custom prompt configuration, or null to use defaults
         * @return this builder instance for method chaining
         */
        public Builder customPrompt(PromptConfig customPrompt) {
            this.customPrompt = customPrompt;
            return this;
        }

        /**
         * Builds and returns a new AutoContextConfig instance with the configured values.
         *
         * @return a new AutoContextConfig instance
         */
        public AutoContextConfig build() {
            AutoContextConfig config = new AutoContextConfig();
            config.largePayloadThreshold = this.largePayloadThreshold;
            config.maxToken = this.maxToken;
            config.tokenRatio = this.tokenRatio;
            config.offloadSinglePreview = this.offloadSinglePreview;
            config.msgThreshold = this.msgThreshold;
            config.lastKeep = this.lastKeep;
            config.minConsecutiveToolMessages = this.minConsecutiveToolMessages;
            config.currentRoundCompressionRatio = this.currentRoundCompressionRatio;
            config.customPrompt = this.customPrompt;
            return config;
        }
    }
}
