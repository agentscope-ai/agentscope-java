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
package io.agentscope.core.e2e;

import io.agentscope.core.e2e.providers.AnthropicProvider;
import io.agentscope.core.e2e.providers.DashScopeCompatibleProvider;
import io.agentscope.core.e2e.providers.DashScopeProvider;
import io.agentscope.core.e2e.providers.DeepSeekProvider;
import io.agentscope.core.e2e.providers.GLMProvider;
import io.agentscope.core.e2e.providers.GeminiProvider;
import io.agentscope.core.e2e.providers.ModelProvider;
import io.agentscope.core.e2e.providers.OpenRouterProvider;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Factory for creating ModelProvider instances based on available API keys.
 *
 * <p>Dynamically provides enabled providers based on environment variables:
 * - OPENAI_API_KEY: Enables OpenAI Native providers
 * - DASHSCOPE_API_KEY: Enables DashScope Native, DashScope Compatible, and Bailian providers
 * - DEEPSEEK_API_KEY: Enables DeepSeek Native providers
 * - GLM_API_KEY: Enables GLM (Zhipu AI) Native providers
 * - GOOGLE_API_KEY: Enables Google Gemini Native providers
 * - ANTHROPIC_API_KEY: Enables Anthropic Claude Native providers
 * - OPENROUTER_API_KEY: Enables OpenRouter providers (access to various models)
 */
public class ProviderFactory {

    private static final String OPENAI_API_KEY = "OPENAI_API_KEY";
    private static final String DASHSCOPE_API_KEY = "DASHSCOPE_API_KEY";
    private static final String DEEPSEEK_API_KEY = "DEEPSEEK_API_KEY";
    private static final String GLM_API_KEY = "GLM_API_KEY";
    private static final String GOOGLE_API_KEY = "GOOGLE_API_KEY";
    private static final String ANTHROPIC_API_KEY = "ANTHROPIC_API_KEY";
    private static final String OPENROUTER_API_KEY = "OPENROUTER_API_KEY";

    private static final List<String> ALL_KEYS =
            List.of(
                    OPENAI_API_KEY,
                    DASHSCOPE_API_KEY,
                    DEEPSEEK_API_KEY,
                    GLM_API_KEY,
                    GOOGLE_API_KEY,
                    ANTHROPIC_API_KEY,
                    OPENROUTER_API_KEY);

    protected static boolean hasApiKey(String keyName) {
        String key = System.getenv(keyName);
        if (key == null || key.isEmpty()) {
            key = System.getProperty(keyName);
        }
        return key != null && !key.isEmpty();
    }

    protected static boolean hasOpenAIKey() {
        return hasApiKey(OPENAI_API_KEY);
    }

    protected static boolean hasDeepSeekKey() {
        return hasApiKey(DEEPSEEK_API_KEY);
    }

    protected static boolean hasGLMKey() {
        return hasApiKey(GLM_API_KEY);
    }

    protected static boolean hasDashScopeKey() {
        return hasApiKey(DASHSCOPE_API_KEY);
    }

    protected static boolean hasGoogleKey() {
        return hasApiKey(GOOGLE_API_KEY);
    }

    protected static boolean hasAnthropicKey() {
        return hasApiKey(ANTHROPIC_API_KEY);
    }

    protected static boolean hasOpenRouterKey() {
        return hasApiKey(OPENROUTER_API_KEY);
    }

    /**
     * Gets all enabled basic providers for core functionality testing.
     *
     * @return Stream of enabled providers
     */
    public static Stream<ModelProvider> getEnabledBasicProviders() {
        Stream.Builder<ModelProvider> builders = Stream.builder();

        if (hasDashScopeKey()) {
            builders.add(new DashScopeCompatibleProvider.QwenPlusOpenAI());
            builders.add(new DashScopeCompatibleProvider.QwenPlusMultiAgentOpenAI());
            builders.add(new DashScopeProvider.QwenPlusDashScope());
            builders.add(new DashScopeProvider.QwenPlusMultiAgentDashScope());
        }

        if (hasGoogleKey()) {
            builders.add(new GeminiProvider.Gemini25FlashGemini());
            builders.add(new GeminiProvider.Gemini25FlashMultiAgentGemini());
        }

        if (hasAnthropicKey()) {
            builders.add(new AnthropicProvider.ClaudeHaiku45Anthropic());
            builders.add(new AnthropicProvider.ClaudeHaiku45MultiAgentAnthropic());
        }

        if (hasDeepSeekKey()) {
            builders.add(new DeepSeekProvider.DeepSeekChat());
            builders.add(new DeepSeekProvider.DeepSeekChatMultiAgent());
            builders.add(new DeepSeekProvider.DeepSeekR1());
            builders.add(new DeepSeekProvider.DeepSeekR1MultiAgent());
        }

        if (hasGLMKey()) {
            builders.add(new GLMProvider.GLM4Plus());
            builders.add(new GLMProvider.GLM4PlusMultiAgent());
            builders.add(new GLMProvider.GLM4VPlus());
            builders.add(new GLMProvider.GLM4VPlusMultiAgent());
        }

        if (hasOpenRouterKey()) {
            builders.add(new OpenRouterProvider.GPT4oMini());
            builders.add(new OpenRouterProvider.GPT4oMiniMultiAgent());
            builders.add(new OpenRouterProvider.Claude35Sonnet());
            builders.add(new OpenRouterProvider.Claude35SonnetMultiAgent());
            builders.add(new OpenRouterProvider.Gemini3FlashPreview());
            builders.add(new OpenRouterProvider.Gemini3FlashPreviewMultiAgent());
            builders.add(new OpenRouterProvider.GLM46());
            builders.add(new OpenRouterProvider.GLM46MultiAgent());
            builders.add(new OpenRouterProvider.Gemini3ProPreview());
            builders.add(new OpenRouterProvider.Gemini3ProPreviewMultiAgent());
            builders.add(new OpenRouterProvider.DeepSeekChat());
            builders.add(new OpenRouterProvider.DeepSeekChatMultiAgent());
            builders.add(new OpenRouterProvider.DeepSeekR1());
            builders.add(new OpenRouterProvider.DeepSeekR1MultiAgent());
        }

        return builders.build();
    }

    /**
     * Gets all enabled providers for tool functionality testing.
     *
     * @return Stream of enabled providers that support tools
     */
    public static Stream<ModelProvider> getEnabledToolProviders() {
        Stream.Builder<ModelProvider> builders = Stream.builder();

        if (hasDashScopeKey()) {
            builders.add(new DashScopeCompatibleProvider.QwenPlusOpenAI());
            builders.add(new DashScopeCompatibleProvider.QwenPlusMultiAgentOpenAI());
            builders.add(new DashScopeProvider.QwenPlusDashScope());
            builders.add(new DashScopeProvider.QwenPlusMultiAgentDashScope());
        }

        if (hasGoogleKey()) {
            builders.add(new GeminiProvider.Gemini25FlashGemini());
            builders.add(new GeminiProvider.Gemini25FlashMultiAgentGemini());
        }

        if (hasAnthropicKey()) {
            builders.add(new AnthropicProvider.ClaudeHaiku45Anthropic());
            builders.add(new AnthropicProvider.ClaudeHaiku45MultiAgentAnthropic());
        }

        if (hasDeepSeekKey()) {
            builders.add(new DeepSeekProvider.DeepSeekChat());
            builders.add(new DeepSeekProvider.DeepSeekChatMultiAgent());
            // R1 does not support tools yet
        }

        if (hasGLMKey()) {
            builders.add(new GLMProvider.GLM4Plus());
            builders.add(new GLMProvider.GLM4PlusMultiAgent());
            builders.add(new GLMProvider.GLM4VPlus());
            builders.add(new GLMProvider.GLM4VPlusMultiAgent());
        }

        if (hasOpenRouterKey()) {
            builders.add(new OpenRouterProvider.GPT4oMini());
            builders.add(new OpenRouterProvider.GPT4oMiniMultiAgent());
            builders.add(new OpenRouterProvider.Claude35Sonnet());
            builders.add(new OpenRouterProvider.Claude35SonnetMultiAgent());
            builders.add(new OpenRouterProvider.Gemini3FlashPreview());
            builders.add(new OpenRouterProvider.Gemini3FlashPreviewMultiAgent());
            builders.add(new OpenRouterProvider.DeepSeekChat());
            builders.add(new OpenRouterProvider.DeepSeekChatMultiAgent());
            builders.add(new OpenRouterProvider.GLM46());
            builders.add(new OpenRouterProvider.GLM46MultiAgent());
            builders.add(new OpenRouterProvider.Gemini3ProPreview());
            builders.add(new OpenRouterProvider.Gemini3ProPreviewMultiAgent());
        }

        return builders.build();
    }

    /**
     * Gets all enabled providers for image functionality testing.
     *
     * @return Stream of enabled providers that support images
     */
    public static Stream<ModelProvider> getEnabledImageProviders() {
        Stream.Builder<ModelProvider> builders = Stream.builder();

        if (hasDashScopeKey()) {
            //            builders.add(new DashScopeCompatibleProvider.QwenOmniTurboOpenAI());
            builders.add(new DashScopeCompatibleProvider.QwenOmniTurboMultiAgentOpenAI());
            //            builders.add(new DashScopeProvider.QwenVlMaxDashScope());
            //            builders.add(new DashScopeProvider.QwenVlMaxMultiAgentDashScope());
        }

        if (hasGoogleKey()) {
            builders.add(new GeminiProvider.Gemini25FlashGemini());
            builders.add(new GeminiProvider.Gemini25FlashMultiAgentGemini());
        }

        if (hasAnthropicKey()) {
            builders.add(new AnthropicProvider.ClaudeHaiku45Anthropic());
            builders.add(new AnthropicProvider.ClaudeHaiku45MultiAgentAnthropic());
        }

        if (hasGLMKey()) {
            builders.add(new GLMProvider.GLM4VPlus());
            builders.add(new GLMProvider.GLM4VPlusMultiAgent());
        }

        if (hasOpenRouterKey()) {
            builders.add(new OpenRouterProvider.GPT4oMini());
            builders.add(new OpenRouterProvider.GPT4oMiniMultiAgent());
            builders.add(new OpenRouterProvider.Gemini3FlashPreview());
            builders.add(new OpenRouterProvider.Gemini3FlashPreviewMultiAgent());
            builders.add(new OpenRouterProvider.GLM46());
            builders.add(new OpenRouterProvider.GLM46MultiAgent());
            builders.add(new OpenRouterProvider.Gemini3ProPreview());
            builders.add(new OpenRouterProvider.Gemini3ProPreviewMultiAgent());
        }

        return builders.build();
    }

    /**
     * Gets all enabled providers for audio functionality testing.
     *
     * @return Stream of enabled providers that support audio
     */
    public static Stream<ModelProvider> getEnabledAudioProviders() {
        Stream.Builder<ModelProvider> builders = Stream.builder();

        if (hasDashScopeKey()) {
            builders.add(new DashScopeCompatibleProvider.Qwen3OmniFlashOpenAI());
            builders.add(new DashScopeCompatibleProvider.Qwen3OmniFlashMultiAgentOpenAI());
            builders.add(new DashScopeCompatibleProvider.QwenOmniTurboOpenAI());
            builders.add(new DashScopeCompatibleProvider.QwenOmniTurboMultiAgentOpenAI());
        }

        if (hasGoogleKey()) {
            builders.add(new GeminiProvider.Gemini25FlashGemini());
            builders.add(new GeminiProvider.Gemini25FlashMultiAgentGemini());
        }

        if (hasGLMKey()) {
            builders.add(new GLMProvider.GLM4VPlus());
            builders.add(new GLMProvider.GLM4VPlusMultiAgent());
        }

        if (hasOpenRouterKey()) {
            builders.add(new OpenRouterProvider.Gemini3FlashPreview());
            builders.add(new OpenRouterProvider.Gemini3FlashPreviewMultiAgent());
            builders.add(new OpenRouterProvider.Gemini3ProPreview());
            builders.add(new OpenRouterProvider.Gemini3ProPreviewMultiAgent());
        }

        return builders.build();
    }

    /**
     * Gets all enabled providers for multimodal functionality testing.
     *
     * @return Stream of enabled providers that support multiple modalities
     */
    public static Stream<ModelProvider> getEnabledMultimodalProviders() {
        Stream.Builder<ModelProvider> builders = Stream.builder();

        if (hasDashScopeKey()) {
            builders.add(new DashScopeCompatibleProvider.Qwen3OmniFlashOpenAI());
            builders.add(new DashScopeCompatibleProvider.Qwen3OmniFlashMultiAgentOpenAI());
            builders.add(new DashScopeCompatibleProvider.QwenOmniTurboOpenAI());
            builders.add(new DashScopeCompatibleProvider.QwenOmniTurboMultiAgentOpenAI());
            builders.add(new DashScopeProvider.Qwen3VlPlusDashScope());
            builders.add(new DashScopeProvider.Qwen3VlPlusMultiAgentDashScope());
        }

        if (hasGoogleKey()) {
            builders.add(new GeminiProvider.Gemini25FlashGemini());
            builders.add(new GeminiProvider.Gemini25FlashMultiAgentGemini());
        }

        if (hasGLMKey()) {
            builders.add(new GLMProvider.GLM4VPlus());
            builders.add(new GLMProvider.GLM4VPlusMultiAgent());
        }

        if (hasOpenRouterKey()) {
            builders.add(new OpenRouterProvider.GPT4oMini());
            builders.add(new OpenRouterProvider.GPT4oMiniMultiAgent());
            builders.add(new OpenRouterProvider.QwenVL72B());
            builders.add(new OpenRouterProvider.QwenVL72BMultiAgent());
            builders.add(new OpenRouterProvider.Gemini3FlashPreview());
            builders.add(new OpenRouterProvider.Gemini3FlashPreviewMultiAgent());
            builders.add(new OpenRouterProvider.Gemini3ProPreview());
            builders.add(new OpenRouterProvider.Gemini3ProPreviewMultiAgent());
            builders.add(new OpenRouterProvider.GLM46());
            builders.add(new OpenRouterProvider.GLM46MultiAgent());
        }

        return builders.build();
    }

    /**
     * Gets all enabled providers for thinking functionality testing.
     *
     * @return Stream of enabled providers that support thinking
     */
    public static Stream<ModelProvider> getEnabledThinkingProviders() {
        Stream.Builder<ModelProvider> builders = Stream.builder();

        if (hasDashScopeKey()) {
            builders.add(new DashScopeProvider.QwenPlusThinkingDashScope());
            builders.add(new DashScopeProvider.QwenPlusThinkingMultiAgentDashScope());
        }

        if (hasGoogleKey()) {
            builders.add(new GeminiProvider.Gemini25FlashGemini());
            builders.add(new GeminiProvider.Gemini25FlashMultiAgentGemini());
        }

        if (hasAnthropicKey()) {
            builders.add(new AnthropicProvider.ClaudeHaiku45Anthropic());
            builders.add(new AnthropicProvider.ClaudeHaiku45MultiAgentAnthropic());
        }

        if (hasDeepSeekKey()) {
            builders.add(new DeepSeekProvider.DeepSeekR1());
            builders.add(new DeepSeekProvider.DeepSeekR1MultiAgent());
        }

        if (hasGLMKey()) {
            builders.add(new GLMProvider.GLMZ1Air());
            builders.add(new GLMProvider.GLM45());
        }

        if (hasOpenRouterKey()) {
            builders.add(new OpenRouterProvider.Gemini3FlashPreview());
            builders.add(new OpenRouterProvider.Gemini3FlashPreviewMultiAgent());
            builders.add(new OpenRouterProvider.Gemini3ProPreview());
            builders.add(new OpenRouterProvider.Gemini3ProPreviewMultiAgent());
            builders.add(new OpenRouterProvider.DeepSeekChat());
            builders.add(new OpenRouterProvider.DeepSeekChatMultiAgent());
            builders.add(new OpenRouterProvider.DeepSeekR1());
            builders.add(new OpenRouterProvider.DeepSeekR1MultiAgent());
        }

        return builders.build();
    }

    public static Stream<ModelProvider> getSmallThinkingBudgetProviders() {
        Stream.Builder<ModelProvider> builders = Stream.builder();

        if (hasDashScopeKey()) {
            builders.add(new DashScopeProvider.QwenPlusThinkingDashScope(1000));
            builders.add(new DashScopeProvider.QwenPlusThinkingMultiAgentDashScope(1000));
        }

        if (hasGLMKey()) {
            // GLM-Z1-Air and GLM-4.5 have built-in thinking management
            builders.add(new GLMProvider.GLMZ1Air());
            builders.add(new GLMProvider.GLM45());
        }

        if (hasOpenRouterKey()) {
            // DeepSeek R1 is a thinking model (budget is internal/managed by model)
            builders.add(new OpenRouterProvider.DeepSeekR1());
            builders.add(new OpenRouterProvider.DeepSeekR1MultiAgent());

            // Claude 3.5 Sonnet with explicit thinking budget
            builders.add(new OpenRouterProvider.Claude35SonnetThinking(1024));
        }

        return builders.build();
    }

    /**
     * Gets all enabled providers for video functionality testing.
     *
     * @return Stream of enabled providers that support video
     */
    public static Stream<ModelProvider> getEnabledVideoProviders() {
        Stream.Builder<ModelProvider> builders = Stream.builder();

        if (hasDashScopeKey()) {
            builders.add(new DashScopeProvider.Qwen3VlPlusDashScope());
            //            builders.add(new DashScopeProvider.Qwen3VlPlusMultiAgentDashScope());
        }

        if (hasGoogleKey()) {
            builders.add(new GeminiProvider.Gemini25FlashGemini());
            builders.add(new GeminiProvider.Gemini25FlashMultiAgentGemini());
        }

        if (hasGLMKey()) {
            builders.add(new GLMProvider.GLM4VPlus());
            builders.add(new GLMProvider.GLM4VPlusMultiAgent());
        }

        if (hasOpenRouterKey()) {
            builders.add(new OpenRouterProvider.Gemini3FlashPreview());
            builders.add(new OpenRouterProvider.Gemini3FlashPreviewMultiAgent());
            builders.add(new OpenRouterProvider.Gemini3ProPreview());
            builders.add(new OpenRouterProvider.Gemini3ProPreviewMultiAgent());
            builders.add(new OpenRouterProvider.GLM46());
            builders.add(new OpenRouterProvider.GLM46MultiAgent());
        }

        return builders.build();
    }

    /**
     * Gets all enabled providers for multimodal tool functionality testing.
     *
     * @return Stream of enabled providers that support multimodal tools
     */
    public static Stream<ModelProvider> getEnabledMultimodalToolProviders() {
        Stream.Builder<ModelProvider> builders = Stream.builder();

        if (hasDashScopeKey()) {
            builders.add(new DashScopeCompatibleProvider.Qwen3VlPlusOpenAI());
            builders.add(new DashScopeCompatibleProvider.Qwen3VlPlusMultiAgentOpenAI());
            // Dash Scope do not support Image well
            //            builders.add(new DashScopeProvider.Qwen3VlPlusDashScope());
            //            builders.add(new DashScopeProvider.Qwen3VlPlusMultiAgentDashScope());
        }

        if (hasGoogleKey()) {
            builders.add(new GeminiProvider.Gemini25FlashGemini());
            builders.add(new GeminiProvider.Gemini25FlashMultiAgentGemini());
        }

        if (hasGLMKey()) {
            builders.add(new GLMProvider.GLM4VPlus());
            builders.add(new GLMProvider.GLM4VPlusMultiAgent());
        }

        if (hasOpenRouterKey()) {
            builders.add(new OpenRouterProvider.QwenVL72B());
            builders.add(new OpenRouterProvider.QwenVL72BMultiAgent());
            builders.add(new OpenRouterProvider.Gemini3FlashPreview());
            builders.add(new OpenRouterProvider.Gemini3FlashPreviewMultiAgent());
            // Gemini 3 Pro Preview fails with 400 Bad Request for tool calls via OpenRouter
            builders.add(new OpenRouterProvider.GLM46());
            builders.add(new OpenRouterProvider.GLM46MultiAgent());
        }

        return builders.build();
    }

    /**
     * Checks if any E2E tests can be run (has at least one API key).
     *
     * @return true if at least one API key is available
     */
    public static boolean hasAnyApiKey() {
        return ALL_KEYS.stream().anyMatch(ProviderFactory::hasApiKey);
    }

    /**
     * Gets a comma-separated list of available API keys for debugging.
     *
     * @return String describing available API keys
     */
    public static String getApiKeyStatus() {
        String status =
                ALL_KEYS.stream()
                        .filter(ProviderFactory::hasApiKey)
                        .collect(Collectors.joining(", "));
        return status.isEmpty() ? "None" : status;
    }
}
