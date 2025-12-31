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
package io.agentscope.core.formatter.openai;

/**
 * Capabilities of different OpenAI-compatible API providers.
 *
 * <p>This enum defines the tool_choice support levels for different providers,
 * allowing graceful degradation when a provider doesn't support certain features.
 *
 * <p>Reference:
 * <ul>
 *   <li>OpenAI: Full support for auto, none, required, and specific tool choice</li>
 *   <li>Anthropic: Supports auto, none, any, and specific (with different format)</li>
 *   <li>Gemini: Supports auto, none, required (no specific)</li>
 *   <li>GLM (Zhipu): Only supports auto</li>
 * </ul>
 *
 * @see <a href="https://docs.litellm.ai/docs/providers/anthropic">LiteLLM Anthropic</a>
 * @see <a href="https://python.langchain.ac.cn/docs/how_to/tool_choice/">LangChain Tool Choice</a>
 */
public enum ProviderCapability {
    /**
     * OpenAI - Full tool_choice support.
     * <p>Supports: auto, none, required, {"type": "function", "function": {"name": "..."}}
     * <p>Also supports: strict parameter in tool definitions
     */
    OPENAI(true, true, true, true, "openai.com", "api.openai.com"),

    /**
     * Anthropic Claude - Full tool_choice support with different format.
     * <p>Supports: auto, none, any, {"type": "tool", "name": "..."}
     * <p>Does not support: strict parameter
     */
    ANTHROPIC(true, true, true, false, "anthropic.com", "api.anthropic.com"),

    /**
     * Google Gemini - Partial tool_choice support.
     * <p>Supports: auto, none, required (no specific tool forcing)
     * <p>Does not support: strict parameter
     */
    GEMINI(true, true, false, false, "googleapis.com", "generativelanguage.googleapis.com"),

    /**
     * Zhipu GLM - Partial tool_choice support.
     * <p>Supports: auto, required, specific
     * <p>Does not support: none (ignored by API), strict parameter
     * <p>Note: Official docs state "默认且仅支持 auto" but actual API behavior shows
     * required and specific tool choice work correctly.
     */
    GLM(false, true, true, false, "bigmodel.cn", "open.bigmodel.cn"),

    /**
     * DashScope (Alibaba) - Full tool_choice support.
     * <p>Supports: auto, none, required, specific
     * <p>Does not support: strict parameter
     */
    DASHSCOPE(true, true, true, false, "dashscope.aliyuncs.com"),

    /**
     * DeepSeek - Full tool_choice support.
     * <p>Supports: auto, none, required, specific
     * <p>Does not support: strict parameter
     */
    DEEPSEEK(true, true, true, false, "deepseek.com", "api.deepseek.com"),

    /**
     * Unknown provider - Assume full OpenAI compatibility.
     */
    UNKNOWN(true, true, true, true, "");

    private final boolean supportsNone;
    private final boolean supportsRequired;
    private final boolean supportsSpecific;
    private final boolean supportsStrictParameter;
    private final String[] hostPatterns;

    ProviderCapability(
            boolean supportsNone,
            boolean supportsRequired,
            boolean supportsSpecific,
            boolean supportsStrictParameter,
            String... hostPatterns) {
        this.supportsNone = supportsNone;
        this.supportsRequired = supportsRequired;
        this.supportsSpecific = supportsSpecific;
        this.supportsStrictParameter = supportsStrictParameter;
        this.hostPatterns = hostPatterns;
    }

    /**
     * Checks if this provider supports the "none" tool_choice option.
     *
     * @return true if "none" is supported
     */
    public boolean supportsNone() {
        return supportsNone;
    }

    /**
     * Checks if this provider supports the "required" tool_choice option.
     *
     * @return true if "required" is supported
     */
    public boolean supportsRequired() {
        return supportsRequired;
    }

    /**
     * Checks if this provider supports forcing a specific tool.
     *
     * @return true if specific tool choice is supported
     */
    public boolean supportsSpecific() {
        return supportsSpecific;
    }

    /**
     * Checks if this provider supports the "strict" parameter in tool definitions.
     *
     * <p>The "strict" parameter is used for structured output to ensure the model
     * adheres to the JSON schema. Not all providers support this parameter.
     *
     * @return true if "strict" parameter is supported
     */
    public boolean supportsStrictParameter() {
        return supportsStrictParameter;
    }

    /**
     * Detect provider capability from base URL.
     *
     * @param baseUrl the base URL to check
     * @return the detected provider capability
     */
    public static ProviderCapability fromUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            return OPENAI; // Default to OpenAI
        }

        String lowerUrl = baseUrl.toLowerCase();

        // Check each provider's host patterns
        for (ProviderCapability capability : values()) {
            if (capability == UNKNOWN) continue;
            for (String pattern : capability.hostPatterns) {
                if (lowerUrl.contains(pattern)) {
                    return capability;
                }
            }
        }

        // Default: assume full OpenAI compatibility for unknown providers
        return UNKNOWN;
    }

    /**
     * Detect provider capability from model name.
     * <p>Useful when model name contains provider prefix (e.g., "anthropic/claude-3").
     *
     * @param modelName the model name to check
     * @return the detected provider capability
     */
    public static ProviderCapability fromModelName(String modelName) {
        if (modelName == null || modelName.isEmpty()) {
            return UNKNOWN;
        }

        String lowerName = modelName.toLowerCase();

        // Check for provider prefixes
        if (lowerName.startsWith("anthropic/") || lowerName.startsWith("claude-")) {
            return ANTHROPIC;
        }
        if (lowerName.startsWith("gemini") || lowerName.startsWith("models/gemini")) {
            return GEMINI;
        }
        if (lowerName.startsWith("glm-") || lowerName.startsWith("zhipu/")) {
            return GLM;
        }
        if (lowerName.startsWith("qwen-") || lowerName.startsWith("dashscope/")) {
            return DASHSCOPE;
        }
        if (lowerName.startsWith("deepseek-") || lowerName.startsWith("deepseek/")) {
            return DEEPSEEK;
        }
        if (lowerName.startsWith("gpt-") || lowerName.startsWith("openai/")) {
            return OPENAI;
        }

        return UNKNOWN;
    }

    /**
     * Check if the given model is a reasoning model.
     * <p>Reasoning models (like DeepSeek R1, OpenAI o1) have fixed sampling parameters
     * and don't accept temperature, top_p, penalties.
     *
     * @param modelName the model name to check
     * @param baseUrl the base URL for additional context
     * @return true if the model is a reasoning model
     */
    public static boolean isReasoningModel(String modelName, String baseUrl) {
        if (modelName == null || modelName.isEmpty()) {
            return false;
        }

        String lowerName = modelName.toLowerCase();

        // DeepSeek reasoning models
        if (lowerName.contains("deepseek-reasoner") || lowerName.contains("deepseek-r1")) {
            return true;
        }

        // OpenAI o1 series (reasoning models)
        if (lowerName.startsWith("o1-") || lowerName.contains("/o1-")) {
            return true;
        }

        // Check baseUrl for additional context
        if (baseUrl != null) {
            String lowerUrl = baseUrl.toLowerCase();
            // Can add more provider-specific checks here
        }

        return false;
    }
}
