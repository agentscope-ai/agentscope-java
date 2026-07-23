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

/** Optional prompt overrides for auto context compression. */
public class PromptConfig {
    private String previousRoundToolCompressPrompt;
    private String previousRoundSummaryPrompt;
    private String currentRoundLargeMessagePrompt;
    private String currentRoundCompressPrompt;

    public static Builder builder() {
        return new Builder();
    }

    public String getPreviousRoundToolCompressPrompt() {
        return previousRoundToolCompressPrompt;
    }

    public String getPreviousRoundSummaryPrompt() {
        return previousRoundSummaryPrompt;
    }

    public String getCurrentRoundLargeMessagePrompt() {
        return currentRoundLargeMessagePrompt;
    }

    public String getCurrentRoundCompressPrompt() {
        return currentRoundCompressPrompt;
    }

    public static class Builder {
        private final PromptConfig config = new PromptConfig();

        public Builder previousRoundToolCompressPrompt(String prompt) {
            config.previousRoundToolCompressPrompt = prompt;
            return this;
        }

        public Builder previousRoundSummaryPrompt(String prompt) {
            config.previousRoundSummaryPrompt = prompt;
            return this;
        }

        public Builder currentRoundLargeMessagePrompt(String prompt) {
            config.currentRoundLargeMessagePrompt = prompt;
            return this;
        }

        public Builder currentRoundCompressPrompt(String prompt) {
            config.currentRoundCompressPrompt = prompt;
            return this;
        }

        public PromptConfig build() {
            PromptConfig result = new PromptConfig();
            result.previousRoundToolCompressPrompt = config.previousRoundToolCompressPrompt;
            result.previousRoundSummaryPrompt = config.previousRoundSummaryPrompt;
            result.currentRoundLargeMessagePrompt = config.currentRoundLargeMessagePrompt;
            result.currentRoundCompressPrompt = config.currentRoundCompressPrompt;
            return result;
        }
    }
}
