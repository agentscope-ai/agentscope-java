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
import io.agentscope.core.formatter.openai.OpenAIChatFormatter;
import io.agentscope.core.formatter.openai.OpenAIMultiAgentFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Provider for OpenRouter API - 100% compatible with OpenAI API format.
 *
 * <p>OpenRouter provides access to various LLMs through an OpenAI-compatible interface, allowing
 * our OpenAI HTTP implementation to work seamlessly with multiple model providers.
 */
@ModelCapabilities({
    ModelCapability.BASIC,
    ModelCapability.TOOL_CALLING,
    ModelCapability.STRUCTURED_OUTPUT
})
public class OpenRouterProvider extends BaseModelProvider {

    private static final String API_KEY_ENV = "OPENROUTER_API_KEY";
    private static final String BASE_URL_ENV = "OPENROUTER_BASE_URL";
    private static final String DEFAULT_OPENROUTER_BASE_URL = "https://openrouter.ai/api";

    private final GenerateOptions defaultOptions;

    public OpenRouterProvider(String modelName, boolean multiAgentFormatter) {
        this(modelName, multiAgentFormatter, null);
    }

    public OpenRouterProvider(
            String modelName, boolean multiAgentFormatter, GenerateOptions defaultOptions) {
        super(API_KEY_ENV, modelName, multiAgentFormatter);
        this.defaultOptions = defaultOptions;
    }

    @Override
    protected ReActAgent.Builder doCreateAgentBuilder(String name, Toolkit toolkit, String apiKey) {
        String baseUrl = System.getenv(BASE_URL_ENV);
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = System.getProperty(BASE_URL_ENV);
        }
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = DEFAULT_OPENROUTER_BASE_URL;
        }

        // Check if model needs specific configuration
        GenerateOptions options = this.defaultOptions;
        if (options == null && getModelName().contains("gemini")) {
            // Gemini models on OpenRouter need include_reasoning=true for tools to work
            options =
                    GenerateOptions.builder()
                            .additionalBodyParam("include_reasoning", true)
                            .build();
        }

        OpenAIChatModel model =
                OpenAIChatModel.builder()
                        .baseUrl(baseUrl)
                        .apiKey(apiKey)
                        .modelName(getModelName())
                        .stream(false)
                        .generateOptions(options)
                        .formatter(
                                isMultiAgentFormatter()
                                        ? new OpenAIMultiAgentFormatter()
                                        : new OpenAIChatFormatter())
                        .build();

        return ReActAgent.builder()
                .name(name)
                .model(model)
                .toolkit(toolkit)
                .memory(new InMemoryMemory());
    }

    @Override
    public String getProviderName() {
        return "OpenRouter";
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
    // GPT Models
    // ==========================================================================

    /** GPT-4o mini - OpenAI's fast, cost-effective model for various tasks. */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.STRUCTURED_OUTPUT
    })
    public static class GPT4oMini extends OpenRouterProvider {
        public GPT4oMini() {
            super("openai/gpt-4o-mini", false);
        }

        @Override
        public String getProviderName() {
            return "OpenRouter - GPT-4o mini";
        }
    }

    /** GPT-4o mini with Multi-Agent Formatter. */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.MULTI_AGENT_FORMATTER,
        ModelCapability.STRUCTURED_OUTPUT
    })
    public static class GPT4oMiniMultiAgent extends OpenRouterProvider {
        public GPT4oMiniMultiAgent() {
            super("openai/gpt-4o-mini", true);
        }

        @Override
        public String getProviderName() {
            return "OpenRouter - GPT-4o mini (MultiAgent)";
        }
    }

    // ==========================================================================
    // Claude Models
    // ==========================================================================

    /** Claude 3.5 Sonnet - Anthropic's powerful model for complex reasoning. */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.STRUCTURED_OUTPUT
    })
    public static class Claude35Sonnet extends OpenRouterProvider {
        public Claude35Sonnet() {
            super("anthropic/claude-3.5-sonnet", true);
        }

        @Override
        public String getProviderName() {
            return "OpenRouter - Claude 3.5 Sonnet";
        }
    }

    /** Claude 3.5 Sonnet with Multi-Agent Formatter. */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.MULTI_AGENT_FORMATTER,
        ModelCapability.STRUCTURED_OUTPUT
    })
    public static class Claude35SonnetMultiAgent extends OpenRouterProvider {
        public Claude35SonnetMultiAgent() {
            super("anthropic/claude-3.5-sonnet", true);
        }

        @Override
        public String getProviderName() {
            return "OpenRouter - Claude 3.5 Sonnet (MultiAgent)";
        }
    }

    /** Claude 3.5 Sonnet with Thinking Mode enabled. */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.THINKING,
        ModelCapability.THINKING_BUDGET
    })
    public static class Claude35SonnetThinking extends OpenRouterProvider {
        public Claude35SonnetThinking(int budget) {
            super(
                    "anthropic/claude-3.5-sonnet",
                    false,
                    GenerateOptions.builder()
                            .additionalBodyParam(
                                    "thinking", Map.of("type", "enabled", "budget_tokens", budget))
                            .maxTokens(Math.max(budget + 2000, 4000))
                            .build());
        }

        @Override
        public String getProviderName() {
            return "OpenRouter - Claude 3.5 Sonnet (Thinking)";
        }
    }

    // ==========================================================================
    // Qwen Models
    // ==========================================================================

    /** Qwen3 VL 235B - Alibaba's vision model for multimodal tasks. */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.VIDEO,
        ModelCapability.STRUCTURED_OUTPUT
    })
    public static class QwenVL72B extends OpenRouterProvider {
        public QwenVL72B() {
            super("qwen/qwen3-vl-235b-a22b-instruct", false);
        }

        @Override
        public String getProviderName() {
            return "OpenRouter - Qwen3 VL 235B";
        }
    }

    /** Qwen3 VL 235B with Multi-Agent Formatter. */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.VIDEO,
        ModelCapability.MULTI_AGENT_FORMATTER,
        ModelCapability.STRUCTURED_OUTPUT
    })
    public static class QwenVL72BMultiAgent extends OpenRouterProvider {
        public QwenVL72BMultiAgent() {
            super("qwen/qwen3-vl-235b-a22b-instruct", true);
        }

        @Override
        public String getProviderName() {
            return "OpenRouter - Qwen3 VL 235B (MultiAgent)";
        }
    }

    // ==========================================================================
    // Gemini Models
    // ==========================================================================

    /** Gemini 3 Flash Preview - Google's latest fast model via OpenRouter. */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.AUDIO,
        ModelCapability.VIDEO,
        ModelCapability.THINKING
    })
    public static class Gemini3FlashPreview extends OpenRouterProvider {
        public Gemini3FlashPreview() {
            super("google/gemini-3-flash-preview", false);
        }

        @Override
        public String getProviderName() {
            return "OpenRouter - Gemini 3 Flash Preview";
        }
    }

    /** Gemini 3 Flash Preview with Multi-Agent Formatter. */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.AUDIO,
        ModelCapability.VIDEO,
        ModelCapability.THINKING,
        ModelCapability.MULTI_AGENT_FORMATTER
    })
    public static class Gemini3FlashPreviewMultiAgent extends OpenRouterProvider {
        public Gemini3FlashPreviewMultiAgent() {
            super("google/gemini-3-flash-preview", true);
        }

        @Override
        public String getProviderName() {
            return "OpenRouter - Gemini 3 Flash Preview (MultiAgent)";
        }
    }

    /** Gemini 3 Pro Preview - Google's latest powerful model via OpenRouter. */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.AUDIO,
        ModelCapability.VIDEO,
        ModelCapability.THINKING
    })
    public static class Gemini3ProPreview extends OpenRouterProvider {
        public Gemini3ProPreview() {
            super("google/gemini-3-pro-preview", false);
        }

        @Override
        public String getProviderName() {
            return "OpenRouter - Gemini 3 Pro Preview";
        }
    }

    /** Gemini 3 Pro Preview with Multi-Agent Formatter. */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.AUDIO,
        ModelCapability.VIDEO,
        ModelCapability.THINKING,
        ModelCapability.MULTI_AGENT_FORMATTER
    })
    public static class Gemini3ProPreviewMultiAgent extends OpenRouterProvider {
        public Gemini3ProPreviewMultiAgent() {
            super("google/gemini-3-pro-preview", true);
        }

        @Override
        public String getProviderName() {
            return "OpenRouter - Gemini 3 Pro Preview (MultiAgent)";
        }
    }

    // ==========================================================================
    // DeepSeek Models
    // ==========================================================================

    /** DeepSeek Chat - DeepSeek's fast and efficient model via OpenRouter. */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.STRUCTURED_OUTPUT
    })
    public static class DeepSeekChat extends OpenRouterProvider {
        public DeepSeekChat() {
            super("deepseek/deepseek-chat", true);
        }

        @Override
        public String getProviderName() {
            return "OpenRouter - DeepSeek Chat";
        }
    }

    /** DeepSeek Chat with Multi-Agent Formatter. */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.MULTI_AGENT_FORMATTER,
        ModelCapability.STRUCTURED_OUTPUT
    })
    public static class DeepSeekChatMultiAgent extends OpenRouterProvider {
        public DeepSeekChatMultiAgent() {
            super("deepseek/deepseek-chat", true);
        }

        @Override
        public String getProviderName() {
            return "OpenRouter - DeepSeek Chat (MultiAgent)";
        }
    }

    /** DeepSeek R1 - DeepSeek's reasoning model via OpenRouter. */
    @ModelCapabilities({ModelCapability.BASIC, ModelCapability.THINKING})
    public static class DeepSeekR1 extends OpenRouterProvider {
        public DeepSeekR1() {
            super("deepseek/deepseek-r1", false);
        }

        @Override
        public String getProviderName() {
            return "OpenRouter - DeepSeek R1";
        }

        @Override
        public boolean supportsToolCalling() {
            return false;
        }
    }

    /** DeepSeek R1 with Multi-Agent Formatter. */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.THINKING,
        ModelCapability.MULTI_AGENT_FORMATTER
    })
    public static class DeepSeekR1MultiAgent extends OpenRouterProvider {
        public DeepSeekR1MultiAgent() {
            super("deepseek/deepseek-r1", true);
        }

        @Override
        public String getProviderName() {
            return "OpenRouter - DeepSeek R1 (MultiAgent)";
        }

        @Override
        public boolean supportsToolCalling() {
            return false;
        }
    }

    // ==========================================================================
    // GLM Models
    // ==========================================================================

    /** GLM 4.6V - Zhipu AI's latest GLM model via OpenRouter. */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.STRUCTURED_OUTPUT
    })
    public static class GLM46 extends OpenRouterProvider {
        public GLM46() {
            super("z-ai/glm-4.6v", true);
        }

        @Override
        public String getProviderName() {
            return "OpenRouter - GLM 4.6V";
        }
    }

    /** GLM 4.6V with Multi-Agent Formatter. */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.MULTI_AGENT_FORMATTER,
        ModelCapability.STRUCTURED_OUTPUT
    })
    public static class GLM46MultiAgent extends OpenRouterProvider {
        public GLM46MultiAgent() {
            super("z-ai/glm-4.6v", true);
        }

        @Override
        public String getProviderName() {
            return "OpenRouter - GLM 4.6V (MultiAgent)";
        }
    }
}
