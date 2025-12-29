/*
 * Copyright 2024-2025 the original author or authors.
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
package io.agentscope.core.memory.autocontext;

/**
 * Configuration class for compression prompts.
 *
 * <p>All prompts are optional. If not specified, default prompts from {@link Prompts} will be used.
 * Note: Format templates (PREVIOUS_ROUND_COMPRESSED_TOOL_INVOCATION_FORMAT and
 * PREVIOUS_ROUND_CONVERSATION_SUMMARY_FORMAT) are not configurable and will always use default
 * values.
 */
public class PromptConfig {

    /** Strategy 1: Prompt for compressing previous round tool invocations */
    private String previousRoundToolCompressPrompt;

    /** Strategy 4: Prompt for summarizing previous round conversations */
    private String previousRoundSummaryPrompt;

    /** Strategy 5: Prompt for summarizing current round large messages */
    private String currentRoundLargeMessagePrompt;

    /** Strategy 6: Prompt for compressing current round messages (supports format: %d, %d, %.0f, %.0f) */
    private String currentRoundCompressPrompt;

    // 私有构造函数
    private PromptConfig() {}

    // Builder 类
    public static class Builder {
        private PromptConfig config = new PromptConfig();

        /** Strategy 1: Sets prompt for compressing previous round tool invocations */
        public Builder previousRoundToolCompressPrompt(String prompt) {
            config.previousRoundToolCompressPrompt = prompt;
            return this;
        }

        /** Strategy 4: Sets prompt for summarizing previous round conversations */
        public Builder previousRoundSummaryPrompt(String prompt) {
            config.previousRoundSummaryPrompt = prompt;
            return this;
        }

        /** Strategy 5: Sets prompt for summarizing current round large messages */
        public Builder currentRoundLargeMessagePrompt(String prompt) {
            config.currentRoundLargeMessagePrompt = prompt;
            return this;
        }

        /** Strategy 6: Sets prompt for compressing current round messages (supports format: %d, %d, %.0f, %.0f) */
        public Builder currentRoundCompressPrompt(String prompt) {
            config.currentRoundCompressPrompt = prompt;
            return this;
        }

        public PromptConfig build() {
            return config;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    // Getters (返回 null 表示使用默认值)
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
}
