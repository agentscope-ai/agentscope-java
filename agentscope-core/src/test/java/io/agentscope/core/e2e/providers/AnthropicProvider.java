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
package io.agentscope.core.e2e.providers;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.anthropic.AnthropicChatFormatter;
import io.agentscope.core.formatter.anthropic.AnthropicMultiAgentFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.AnthropicChatModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.Toolkit;

public class AnthropicProvider implements ModelProvider {

    private final String modelName;
    private final boolean multiAgentFormatter;

    public AnthropicProvider(String modelName, boolean multiAgentFormatter) {
        this.modelName = modelName;
        this.multiAgentFormatter = multiAgentFormatter;
    }

    @Override
    public ReActAgent createAgent(String name, Toolkit toolkit) {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("ANTHROPIC_API_KEY environment variable is required");
        }

        String baseUrl = System.getenv("ANTHROPIC_BASE_URL"); // Optional custom endpoint

        AnthropicChatModel.Builder builder =
                AnthropicChatModel.builder()
                        .apiKey(apiKey)
                        .baseUrl(baseUrl)
                        .modelName(modelName)
                        .formatter(
                                multiAgentFormatter
                                        ? new AnthropicMultiAgentFormatter()
                                        : new AnthropicChatFormatter())
                        .defaultOptions(GenerateOptions.builder().build());

        return ReActAgent.builder()
                .name(name)
                .model(builder.build())
                .toolkit(toolkit)
                .memory(new InMemoryMemory())
                .build();
    }

    @Override
    public String getProviderName() {
        return "Anthropic";
    }

    @Override
    public boolean supportsThinking() {
        return false;
    }

    @Override
    public boolean isEnabled() {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        return apiKey != null && !apiKey.isEmpty();
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    public static class ClaudeHaiku45Anthropic extends AnthropicProvider {
        public ClaudeHaiku45Anthropic() {
            super("claude-haiku-4-5-20251001", false);
        }

        @Override
        public String getProviderName() {
            return "Anthropic";
        }
    }

    public static class ClaudeHaiku45MultiAgentAnthropic extends AnthropicProvider {
        public ClaudeHaiku45MultiAgentAnthropic() {
            super("claude-haiku-4-5-20251001", true);
        }

        @Override
        public String getProviderName() {
            return "Anthropic";
        }
    }
}
