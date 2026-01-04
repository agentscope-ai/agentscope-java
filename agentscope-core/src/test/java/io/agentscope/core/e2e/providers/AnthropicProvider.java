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
import java.util.HashSet;
import java.util.Set;

/**
 * Provider for Anthropic Claude API.
 *
 * <p>Supports Claude models with multimodal capabilities.
 */
@ModelCapabilities({
    ModelCapability.BASIC,
    ModelCapability.TOOL_CALLING,
    ModelCapability.IMAGE,
    ModelCapability.THINKING
})
public class AnthropicProvider extends BaseModelProvider {

    private static final String API_KEY_ENV = "ANTHROPIC_API_KEY";
    private static final String BASE_URL_ENV = "ANTHROPIC_BASE_URL";

    public AnthropicProvider(String modelName, boolean multiAgentFormatter) {
        super(API_KEY_ENV, modelName, multiAgentFormatter);
    }

    @Override
    protected ReActAgent.Builder doCreateAgentBuilder(String name, Toolkit toolkit, String apiKey) {
        String baseUrl = System.getenv(BASE_URL_ENV);

        AnthropicChatModel.Builder builder =
                AnthropicChatModel.builder()
                        .apiKey(apiKey)
                        .baseUrl(baseUrl)
                        .modelName(getModelName())
                        .formatter(
                                isMultiAgentFormatter()
                                        ? new AnthropicMultiAgentFormatter()
                                        : new AnthropicChatFormatter())
                        .defaultOptions(GenerateOptions.builder().build());

        return ReActAgent.builder()
                .name(name)
                .model(builder.build())
                .toolkit(toolkit)
                .memory(new InMemoryMemory());
    }

    @Override
    public String getProviderName() {
        return "Anthropic";
    }

    @Override
    public Set<ModelCapability> getCapabilities() {
        Set<ModelCapability> caps = new HashSet<>(super.getCapabilities());
        if (isMultiAgentFormatter()) {
            caps.add(ModelCapability.MULTI_AGENT_FORMATTER);
        }
        return caps;
    }

    // ==========================================================================
    // Provider Instances
    // ==========================================================================

    /** Claude Haiku 4.5 - Fast, efficient model. */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.THINKING
    })
    public static class ClaudeHaiku45Anthropic extends AnthropicProvider {
        public ClaudeHaiku45Anthropic() {
            super("claude-haiku-4-5-20251001", false);
        }

        @Override
        public String getProviderName() {
            return "Anthropic";
        }
    }

    /** Claude Haiku 4.5 with multi-agent formatter. */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.THINKING,
        ModelCapability.MULTI_AGENT_FORMATTER
    })
    public static class ClaudeHaiku45MultiAgentAnthropic extends AnthropicProvider {
        public ClaudeHaiku45MultiAgentAnthropic() {
            super("claude-haiku-4-5-20251001", true);
        }

        @Override
        public String getProviderName() {
            return "Anthropic (Multi-Agent)";
        }
    }
}
