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

/** Configuration for auto context compression. */
public class AutoContextConfig {

    long largePayloadThreshold = 5 * 1024;
    long maxToken = 128 * 1024;
    double tokenRatio = 0.75;
    int offloadSinglePreview = 200;
    int msgThreshold = 100;
    int lastKeep = 50;
    int minConsecutiveToolMessages = 6;
    double currentRoundCompressionRatio = 0.3;
    int minCompressionTokenThreshold = 5000;
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

    public int getMinCompressionTokenThreshold() {
        return minCompressionTokenThreshold;
    }

    public PromptConfig getCustomPrompt() {
        return customPrompt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final AutoContextConfig config = new AutoContextConfig();

        public Builder largePayloadThreshold(long largePayloadThreshold) {
            config.largePayloadThreshold = largePayloadThreshold;
            return this;
        }

        public Builder maxToken(long maxToken) {
            config.maxToken = maxToken;
            return this;
        }

        public Builder tokenRatio(double tokenRatio) {
            config.tokenRatio = tokenRatio;
            return this;
        }

        public Builder offloadSinglePreview(int offloadSinglePreview) {
            config.offloadSinglePreview = offloadSinglePreview;
            return this;
        }

        public Builder msgThreshold(int msgThreshold) {
            config.msgThreshold = msgThreshold;
            return this;
        }

        public Builder lastKeep(int lastKeep) {
            config.lastKeep = lastKeep;
            return this;
        }

        public Builder minConsecutiveToolMessages(int minConsecutiveToolMessages) {
            config.minConsecutiveToolMessages = minConsecutiveToolMessages;
            return this;
        }

        public Builder currentRoundCompressionRatio(double currentRoundCompressionRatio) {
            config.currentRoundCompressionRatio = currentRoundCompressionRatio;
            return this;
        }

        public Builder minCompressionTokenThreshold(int minCompressionTokenThreshold) {
            config.minCompressionTokenThreshold = minCompressionTokenThreshold;
            return this;
        }

        public Builder customPrompt(PromptConfig customPrompt) {
            config.customPrompt = customPrompt;
            return this;
        }

        public AutoContextConfig build() {
            AutoContextConfig result = new AutoContextConfig();
            result.largePayloadThreshold = config.largePayloadThreshold;
            result.maxToken = config.maxToken;
            result.tokenRatio = config.tokenRatio;
            result.offloadSinglePreview = config.offloadSinglePreview;
            result.msgThreshold = config.msgThreshold;
            result.lastKeep = config.lastKeep;
            result.minConsecutiveToolMessages = config.minConsecutiveToolMessages;
            result.currentRoundCompressionRatio = config.currentRoundCompressionRatio;
            result.minCompressionTokenThreshold = config.minCompressionTokenThreshold;
            result.customPrompt = config.customPrompt;
            return result;
        }
    }
}
