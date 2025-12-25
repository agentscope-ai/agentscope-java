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
package io.agentscope.core.e2e;

import io.agentscope.core.e2e.providers.AnthropicProvider;
import io.agentscope.core.e2e.providers.DashScopeCompatibleProvider;
import io.agentscope.core.e2e.providers.DashScopeProvider;
import io.agentscope.core.e2e.providers.DeepSeekProvider;
import io.agentscope.core.e2e.providers.GLMProvider;
import io.agentscope.core.e2e.providers.GeminiProvider;
import io.agentscope.core.e2e.providers.ModelProvider;
import io.agentscope.core.e2e.providers.OpenRouterProvider;
import java.util.stream.Stream;

/**
 * Factory for creating ModelProvider instances based on available API keys.
 *
 * <p>Dynamically provides enabled providers based on environment variables:
 * - OPENAI_API_KEY: Enables OpenAI Native providers
 * - DASHSCOPE_API_KEY: Enables DashScope Native, DashScope Compatible, and Bailian providers
 * - DEEPSEEK_API_KEY: Enables DeepSeek Native providers
 * - GLM_API_KEY: Enables GLM (Zhipu AI) Native providers
 */
public class ProviderFactory {

    protected static boolean hasOpenAIKey() {
        String key = System.getenv("OPENAI_API_KEY");
        return key != null && !key.isEmpty();
    }

    protected static boolean hasDeepSeekKey() {
        String key = System.getenv("DEEPSEEK_API_KEY");
        if (key == null || key.isEmpty()) {
            key = System.getProperty("DEEPSEEK_API_KEY");
        }
        return key != null && !key.isEmpty();
    }

    protected static boolean hasGLMKey() {
        String key = System.getenv("GLM_API_KEY");
        if (key == null || key.isEmpty()) {
            key = System.getProperty("GLM_API_KEY");
        }
        return key != null && !key.isEmpty();
    }

    protected static boolean hasDashScopeKey() {
        String key = System.getenv("DASHSCOPE_API_KEY");
        return key != null && !key.isEmpty();
    }

    protected static boolean hasGoogleKey() {
        String key = System.getenv("GOOGLE_API_KEY");
        return key != null && !key.isEmpty();
    }

    protected static boolean hasAnthropicKey() {
        String key = System.getenv("ANTHROPIC_API_KEY");
        return key != null && !key.isEmpty();
    }

    protected static boolean hasOpenRouterKey() {
        String key = System.getenv("OPENROUTER_API_KEY");
        if (key == null || key.isEmpty()) {
            key = System.getProperty("OPENROUTER_API_KEY");
        }
        return key != null && !key.isEmpty();
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
        return hasOpenAIKey()
                || hasDashScopeKey()
                || hasGoogleKey()
                || hasAnthropicKey()
                || hasOpenRouterKey()
                || hasDeepSeekKey()
                || hasGLMKey();
    }

    /**
     * Gets a comma-separated list of available API keys for debugging.
     *
     * @return String describing available API keys
     */
    public static String getApiKeyStatus() {
        StringBuilder status = new StringBuilder();
        if (hasOpenAIKey()) {
            status.append("OPENAI_API_KEY");
        }
        if (hasDashScopeKey()) {
            if (status.length() > 0) {
                status.append(", ");
            }
            status.append("DASHSCOPE_API_KEY");
        }
        if (hasGoogleKey()) {
            if (status.length() > 0) {
                status.append(", ");
            }
            status.append("GOOGLE_API_KEY");
        }
        if (hasAnthropicKey()) {
            if (status.length() > 0) {
                status.append(", ");
            }
            status.append("ANTHROPIC_API_KEY");
        }
        if (hasDeepSeekKey()) {
            if (status.length() > 0) {
                status.append(", ");
            }
            status.append("DEEPSEEK_API_KEY");
        }
        if (hasGLMKey()) {
            if (status.length() > 0) {
                status.append(", ");
            }
            status.append("GLM_API_KEY");
        }
        if (hasOpenRouterKey()) {
            if (status.length() > 0) {
                status.append(", ");
            }
            status.append("OPENROUTER_API_KEY");
        }
        return status.length() > 0 ? status.toString() : "None";
    }
}
