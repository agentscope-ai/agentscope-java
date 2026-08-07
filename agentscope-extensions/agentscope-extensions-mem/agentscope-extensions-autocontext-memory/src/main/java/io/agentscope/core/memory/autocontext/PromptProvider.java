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

/** Helper for prompt fallback resolution. */
public final class PromptProvider {
    private PromptProvider() {}

    public static String getPreviousRoundToolCompressPrompt(PromptConfig customPrompt) {
        if (customPrompt != null
                && customPrompt.getPreviousRoundToolCompressPrompt() != null
                && !customPrompt.getPreviousRoundToolCompressPrompt().isBlank()) {
            return customPrompt.getPreviousRoundToolCompressPrompt();
        }
        return Prompts.PREVIOUS_ROUND_TOOL_INVOCATION_COMPRESS_PROMPT;
    }

    public static String getPreviousRoundSummaryPrompt(PromptConfig customPrompt) {
        if (customPrompt != null
                && customPrompt.getPreviousRoundSummaryPrompt() != null
                && !customPrompt.getPreviousRoundSummaryPrompt().isBlank()) {
            return customPrompt.getPreviousRoundSummaryPrompt();
        }
        return Prompts.PREVIOUS_ROUND_CONVERSATION_SUMMARY_PROMPT;
    }

    public static String getCurrentRoundLargeMessagePrompt(PromptConfig customPrompt) {
        if (customPrompt != null
                && customPrompt.getCurrentRoundLargeMessagePrompt() != null
                && !customPrompt.getCurrentRoundLargeMessagePrompt().isBlank()) {
            return customPrompt.getCurrentRoundLargeMessagePrompt();
        }
        return Prompts.CURRENT_ROUND_LARGE_MESSAGE_SUMMARY_PROMPT;
    }

    public static String getCurrentRoundCompressPrompt(PromptConfig customPrompt) {
        if (customPrompt != null
                && customPrompt.getCurrentRoundCompressPrompt() != null
                && !customPrompt.getCurrentRoundCompressPrompt().isBlank()) {
            return customPrompt.getCurrentRoundCompressPrompt();
        }
        return Prompts.CURRENT_ROUND_MESSAGE_COMPRESS_PROMPT;
    }
}
