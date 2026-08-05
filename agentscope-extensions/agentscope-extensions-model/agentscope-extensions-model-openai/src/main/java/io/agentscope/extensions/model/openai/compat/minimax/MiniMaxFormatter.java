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
package io.agentscope.extensions.model.openai.compat.minimax;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.extensions.model.openai.dto.OpenAIRequest;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;

/**
 * Formatter for MiniMax OpenAI-compatible chat completions.
 *
 * <p>MiniMax <a href="https://platform.minimaxi.com/docs/api-reference/text-chat-openai">Chat
 * Completions API</a>.
 */
public class MiniMaxFormatter extends OpenAIChatFormatter {

    @Override
    public void applyOptions(
            OpenAIRequest request, GenerateOptions options, GenerateOptions defaultOptions) {
        // Apply before super so additionalBodyParam can override the MiniMax default.
        applyReasoningSplit(request);
        super.applyOptions(request, options, defaultOptions);
        removeUnsupported(request);
    }

    @Override
    protected void applyMaxTokens(
            OpenAIRequest request, GenerateOptions options, GenerateOptions defaultOptions) {
        applyMiniMaxMaxTokens(
                request,
                getOptionOrDefault(
                        options, defaultOptions, GenerateOptions::getMaxCompletionTokens),
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getMaxTokens));
    }

    @Override
    public void applyToolChoice(OpenAIRequest request, ToolChoice toolChoice) {
        request.setToolChoice(null);
    }

    @Override
    protected boolean supportsStrict() {
        return false;
    }

    static void applyReasoningSplit(OpenAIRequest request) {
        // Split MiniMax thinking content into OpenAI-compatible reasoning fields so the shared
        // OpenAIResponseParser can convert it to ThinkingBlock.
        request.addExtraParam("reasoning_split", true);
    }

    static void applyMiniMaxMaxTokens(
            OpenAIRequest request, Integer maxCompletionTokens, Integer maxTokens) {
        if (maxCompletionTokens != null) {
            request.setMaxCompletionTokens(maxCompletionTokens);
            return;
        }

        if (maxTokens != null) {
            // MiniMax deprecates max_tokens in favor of max_completion_tokens.
            request.setMaxCompletionTokens(maxTokens);
        }
    }

    static void removeUnsupported(OpenAIRequest request) {
        request.setThinkingBudget(null);
        request.setReasoningEffort(null);
        request.setFrequencyPenalty(null);
        request.setPresencePenalty(null);
        request.setParallelToolCalls(null);
        request.setResponseFormat(null);
        request.setSeed(null);
    }
}
