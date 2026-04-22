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
import io.agentscope.core.e2e.providers.DeepSeekReasonerProvider;
import io.agentscope.core.e2e.providers.GLMProvider;
import io.agentscope.core.e2e.providers.GeminiProvider;
import io.agentscope.core.e2e.providers.ModelCapability;
import io.agentscope.core.e2e.providers.ModelProvider;
import io.agentscope.core.e2e.providers.OpenRouterProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Factory for creating ModelProvider instances based on available API keys.
 *
 * <p>Dynamically provides enabled providers based on environment variables:
 *
 * <ul>
 *   <li>OPENAI_API_KEY: Enables OpenAI Native providers
 *   <li>DASHSCOPE_API_KEY: Enables DashScope Native, DashScope Compatible, and Bailian providers
 *   <li>DEEPSEEK_API_KEY: Enables DeepSeek Native providers
 *   <li>GLM_API_KEY: Enables GLM (Zhipu AI) Native providers
 *   <li>GOOGLE_API_KEY: Enables Google Gemini Native providers
 *   <li>ANTHROPIC_API_KEY: Enables Anthropic Claude Native providers
 *   <li>OPENROUTER_API_KEY: Enables OpenRouter providers (access to various models)
 * </ul>
 *
 * <p>Usage:
 *
 * <pre>{@code
 * // Get all basic providers
 * Stream<ModelProvider> basics = ProviderFactory.getBasicProviders();
 *
 * // Get providers with specific capabilities
 * Stream<ModelProvider> imageProviders = ProviderFactory.getProviders(
 *     ModelCapability.BASIC, ModelCapability.IMAGE);
 *
 * // Check provider status
 * String status = ProviderFactory.getApiKeyStatus();
 * }</pre>
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

    /**
     * Gets all registered providers (including disabled ones).
     *
     * @return List of all provider instances
     */
    private static List<ModelProvider> getAllProviders() {
        List<ModelProvider> providers = new ArrayList<>();

        // DashScope Compatible providers
        providers.add(new DashScopeCompatibleProvider.QwenPlusOpenAI());
        providers.add(new DashScopeCompatibleProvider.QwenPlusMultiAgentOpenAI());
        providers.add(new DashScopeCompatibleProvider.Qwen3OmniFlashOpenAI());
        providers.add(new DashScopeCompatibleProvider.Qwen3OmniFlashMultiAgentOpenAI());
        providers.add(new DashScopeCompatibleProvider.Qwen3VlPlusOpenAI());
        providers.add(new DashScopeCompatibleProvider.Qwen3VlPlusMultiAgentOpenAI());

        // DashScope Native providers
        providers.add(new DashScopeProvider.QwenPlusDashScope());
        providers.add(new DashScopeProvider.QwenPlusMultiAgentDashScope());
        providers.add(new DashScopeProvider.QwenPlusThinkingDashScope());
        providers.add(new DashScopeProvider.QwenPlusThinkingMultiAgentDashScope());
        providers.add(new DashScopeProvider.Qwen3VlPlusDashScope());
        providers.add(new DashScopeProvider.Qwen3VlPlusMultiAgentDashScope());

        // Gemini providers
        providers.add(new GeminiProvider.Gemini25FlashGemini());
        providers.add(new GeminiProvider.Gemini25FlashMultiAgentGemini());

        // Anthropic providers
        providers.add(new AnthropicProvider.ClaudeHaiku45Anthropic());
        providers.add(new AnthropicProvider.ClaudeHaiku45MultiAgentAnthropic());

        // DeepSeek providers
        providers.add(new DeepSeekProvider.DeepSeekChat());
        providers.add(new DeepSeekProvider.DeepSeekChatMultiAgent());
        providers.add(new DeepSeekReasonerProvider.DeepSeekR1());
        providers.add(new DeepSeekReasonerProvider.DeepSeekR1MultiAgent());

        // GLM providers
        providers.add(new GLMProvider.GLM46());
        providers.add(new GLMProvider.GLM46MultiAgent());
        providers.add(new GLMProvider.GLM46VPlus());
        providers.add(new GLMProvider.GLM46VMultiAgent());
        providers.add(new GLMProvider.GLM45());
        providers.add(new GLMProvider.GLM47());

        // OpenRouter providers
        providers.add(new OpenRouterProvider.GPT52());
        providers.add(new OpenRouterProvider.GPT52MultiAgent());
        providers.add(new OpenRouterProvider.Claude45Haiku());
        providers.add(new OpenRouterProvider.Claude45HaikuMultiAgent());
        providers.add(new OpenRouterProvider.Qwen3VL());
        providers.add(new OpenRouterProvider.Qwen3VLMultiAgent());
        providers.add(new OpenRouterProvider.Gemini3FlashPreview());
        providers.add(new OpenRouterProvider.Gemini3FlashPreviewMultiAgent());
        providers.add(new OpenRouterProvider.Gemini3ProPreview());
        providers.add(new OpenRouterProvider.Gemini3ProPreviewMultiAgent());
        providers.add(new OpenRouterProvider.DeepSeekV32());
        providers.add(new OpenRouterProvider.DeepSeekV32MultiAgent());
        providers.add(new OpenRouterProvider.DeepSeekR1());
        providers.add(new OpenRouterProvider.DeepSeekR1MultiAgent());
        providers.add(new OpenRouterProvider.GLM46());
        providers.add(new OpenRouterProvider.GLM46MultiAgent());

        return providers;
    }

    // ==========================================================================
    // API Key Helpers
    // ==========================================================================

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

    // ==========================================================================
    // Capability-based Provider Selection
    // ==========================================================================

    /**
     * Gets providers with all specified capabilities.
     *
     * @param required The required capabilities
     * @return Stream of enabled providers with all required capabilities
     */
    public static Stream<ModelProvider> getProviders(ModelCapability... required) {
        Set<ModelCapability> requiredSet = Set.of(required);
        return getAllProviders().stream()
                .filter(ModelProvider::isEnabled)
                .filter(p -> p.getCapabilities().containsAll(requiredSet));
    }

    // ==========================================================================
    // Convenience Methods (renamed from getEnabledXxxProviders to getXxxProviders)
    // ==========================================================================

    /**
     * Gets all enabled basic providers for core functionality testing.
     *
     * @return Stream of enabled providers with basic capability
     */
    public static Stream<ModelProvider> getBasicProviders() {
        return getProviders(ModelCapability.BASIC);
    }

    /**
     * Gets all enabled providers for tool functionality testing.
     *
     * @return Stream of enabled providers that support tools
     */
    public static Stream<ModelProvider> getToolProviders() {
        return getProviders(ModelCapability.BASIC, ModelCapability.TOOL_CALLING);
    }

    /**
     * Gets all enabled providers for image functionality testing.
     *
     * @return Stream of enabled providers that support images
     */
    public static Stream<ModelProvider> getImageProviders() {
        return getProviders(ModelCapability.BASIC, ModelCapability.IMAGE);
    }

    /**
     * Gets all enabled providers for audio functionality testing.
     *
     * @return Stream of enabled providers that support audio
     */
    public static Stream<ModelProvider> getAudioProviders() {
        return getProviders(ModelCapability.BASIC, ModelCapability.AUDIO);
    }

    /**
     * Gets all enabled providers for multimodal functionality testing.
     *
     * @return Stream of enabled providers that support multiple modalities
     */
    public static Stream<ModelProvider> getMultimodalProviders() {
        return getProviders(ModelCapability.BASIC, ModelCapability.IMAGE, ModelCapability.AUDIO);
    }

    /**
     * Gets all enabled providers for thinking functionality testing.
     *
     * @return Stream of enabled providers that support thinking
     */
    public static Stream<ModelProvider> getThinkingProviders() {
        return getProviders(ModelCapability.BASIC, ModelCapability.THINKING);
    }

    /**
     * Gets all enabled providers for thinking with budget control.
     *
     * @return Stream of enabled providers that support thinking budget
     */
    public static Stream<ModelProvider> getThinkingBudgetProviders() {
        Stream.Builder<ModelProvider> builders = Stream.builder();

        if (hasDashScopeKey()) {
            builders.add(new DashScopeProvider.QwenPlusThinkingDashScope(1000));
            builders.add(new DashScopeProvider.QwenPlusThinkingMultiAgentDashScope(1000));
        }

        if (hasGLMKey()) {
            builders.add(new GLMProvider.GLM45());
            builders.add(new GLMProvider.GLM47());
        }

        if (hasOpenRouterKey()) {
            builders.add(new OpenRouterProvider.DeepSeekR1());
            builders.add(new OpenRouterProvider.DeepSeekR1MultiAgent());
            builders.add(new OpenRouterProvider.Claude45HaikuThinking(1024));
        }

        return builders.build();
    }

    /**
     * Gets all enabled providers for video functionality testing.
     *
     * @return Stream of enabled providers that support video
     */
    public static Stream<ModelProvider> getVideoProviders() {
        return getProviders(ModelCapability.BASIC, ModelCapability.VIDEO);
    }

    /**
     * Gets all enabled providers for multimodal tool functionality testing.
     *
     * @return Stream of enabled providers that support multimodal tools
     */
    public static Stream<ModelProvider> getMultimodalToolProviders() {
        return getProviders(
                ModelCapability.BASIC, ModelCapability.IMAGE, ModelCapability.TOOL_CALLING);
    }

    /**
     * Gets all enabled providers that support multi-agent formatter for MsgHub testing.
     *
     * @return Stream of enabled providers with multi-agent formatter capability
     */
    public static Stream<ModelProvider> getMultiAgentProviders() {
        return getProviders(ModelCapability.BASIC, ModelCapability.MULTI_AGENT_FORMATTER);
    }

    // ==========================================================================
    // Utility Methods
    // ==========================================================================

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
