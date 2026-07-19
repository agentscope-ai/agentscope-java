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
import io.agentscope.extensions.model.openai.dto.OpenAIRequest;
import io.agentscope.extensions.model.openai.formatter.OpenAIMultiAgentFormatter;

/** Multi-agent formatter for MiniMax OpenAI-compatible chat completions. */
public class MiniMaxMultiAgentFormatter extends OpenAIMultiAgentFormatter {

    public MiniMaxMultiAgentFormatter() {
        super();
    }

    public MiniMaxMultiAgentFormatter(String conversationHistoryPrompt) {
        super(conversationHistoryPrompt);
    }

    @Override
    public void applyOptions(
            OpenAIRequest request, GenerateOptions options, GenerateOptions defaultOptions) {
        // Apply before super so additionalBodyParam can override the MiniMax default.
        MiniMaxFormatter.applyReasoningSplit(request);
        super.applyOptions(request, options, defaultOptions);
    }

    @Override
    protected void applyMaxTokens(
            OpenAIRequest request, GenerateOptions options, GenerateOptions defaultOptions) {
        MiniMaxFormatter.applyMiniMaxMaxTokens(
                request,
                getOptionOrDefault(
                        options, defaultOptions, GenerateOptions::getMaxCompletionTokens),
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getMaxTokens));
    }

    @Override
    protected boolean supportsStrict() {
        return false;
    }
}
