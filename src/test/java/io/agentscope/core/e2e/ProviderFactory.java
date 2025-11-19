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

import io.agentscope.core.e2e.providers.DashScopeCompatibleProvider;
import io.agentscope.core.e2e.providers.DashScopeProvider;
import io.agentscope.core.e2e.providers.GeminiProvider;
import io.agentscope.core.e2e.providers.ModelProvider;
import io.agentscope.core.e2e.providers.OpenAINativeProvider;
import java.util.stream.Stream;

/**
 * Factory for creating ModelProvider instances based on available API keys.
 *
 * <p>Dynamically provides enabled providers based on environment variables:
 * - OPENAI_API_KEY: Enables OpenAI Native providers
 * - DASHSCOPE_API_KEY: Enables DashScope Native, DashScope Compatible, and Bailian providers
 */
public class ProviderFactory {

    protected static boolean hasOpenAIKey() {
        String key = System.getenv("OPENAI_API_KEY");
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

    /**
     * Gets all enabled basic providers for core functionality testing.
     *
     * @return Stream of enabled providers
     */
    public static Stream<ModelProvider> getEnabledBasicProviders() {
        Stream.Builder<ModelProvider> builders = Stream.builder();

        if (hasOpenAIKey()) {
            builders.add(new OpenAINativeProvider.Gpt5MiniOpenAI());
            builders.add(new OpenAINativeProvider.Gpt5MiniMultiAgentOpenAI());
        }

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

        return builders.build();
    }

    /**
     * Gets all enabled providers for tool functionality testing.
     *
     * @return Stream of enabled providers that support tools
     */
    public static Stream<ModelProvider> getEnabledToolProviders() {
        Stream.Builder<ModelProvider> builders = Stream.builder();

        if (hasOpenAIKey()) {
            builders.add(new OpenAINativeProvider.Gpt5MiniOpenAI());
            builders.add(new OpenAINativeProvider.Gpt5MiniMultiAgentOpenAI());
        }

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

        return builders.build();
    }

    /**
     * Gets all enabled providers for image functionality testing.
     *
     * @return Stream of enabled providers that support images
     */
    public static Stream<ModelProvider> getEnabledImageProviders() {
        Stream.Builder<ModelProvider> builders = Stream.builder();

        if (hasOpenAIKey()) {
            //            builders.add(new OpenAINativeProvider.Gpt5ImageMiniOpenAI());
            //            builders.add(new OpenAINativeProvider.Gpt5ImageMiniMultiAgentOpenAI());
        }

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

        return builders.build();
    }

    /**
     * Gets all enabled providers for audio functionality testing.
     *
     * @return Stream of enabled providers that support audio
     */
    public static Stream<ModelProvider> getEnabledAudioProviders() {
        Stream.Builder<ModelProvider> builders = Stream.builder();

        if (hasOpenAIKey()) {
            builders.add(new OpenAINativeProvider.Gpt4oAudioPreviewOpenAI());
            builders.add(new OpenAINativeProvider.Gpt4oAudioPreviewMultiAgentOpenAI());
        }

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

        return builders.build();
    }

    /**
     * Gets all enabled providers for multimodal functionality testing.
     *
     * @return Stream of enabled providers that support multiple modalities
     */
    public static Stream<ModelProvider> getEnabledMultimodalProviders() {
        Stream.Builder<ModelProvider> builders = Stream.builder();

        if (hasOpenAIKey()) {
            builders.add(new OpenAINativeProvider.Gpt5MiniOpenAI());
            builders.add(new OpenAINativeProvider.Gpt5MiniMultiAgentOpenAI());
        }

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

        return builders.build();
    }

    public static Stream<ModelProvider> getSmallThinkingBudgetProviders() {
        Stream.Builder<ModelProvider> builders = Stream.builder();

        if (hasDashScopeKey()) {
            builders.add(new DashScopeProvider.QwenPlusThinkingDashScope(1000));
            builders.add(new DashScopeProvider.QwenPlusThinkingMultiAgentDashScope(1000));
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

        return builders.build();
    }

    /**
     * Gets all enabled providers for multimodal tool functionality testing.
     *
     * @return Stream of enabled providers that support multimodal tools
     */
    public static Stream<ModelProvider> getEnabledMultimodalToolProviders() {
        Stream.Builder<ModelProvider> builders = Stream.builder();

        if (hasOpenAIKey()) {
            builders.add(new OpenAINativeProvider.Gpt5MiniOpenAI());
            builders.add(new OpenAINativeProvider.Gpt5MiniMultiAgentOpenAI());
        }

        if (hasDashScopeKey()) {
            builders.add(new DashScopeCompatibleProvider.Qwen3VlPlusOpenAI());
            builders.add(new DashScopeCompatibleProvider.Qwen3VlPlusMultiAgentOpenAI());
            builders.add(new DashScopeProvider.Qwen3VlPlusDashScope());
            builders.add(new DashScopeProvider.Qwen3VlPlusMultiAgentDashScope());
        }

        if (hasGoogleKey()) {
            builders.add(new GeminiProvider.Gemini25FlashGemini());
            builders.add(new GeminiProvider.Gemini25FlashMultiAgentGemini());
        }

        return builders.build();
    }

    /**
     * Checks if any E2E tests can be run (has at least one API key).
     *
     * @return true if at least one API key is available
     */
    public static boolean hasAnyApiKey() {
        return hasOpenAIKey() || hasDashScopeKey();
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
        return status.length() > 0 ? status.toString() : "None";
    }
}
