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
package io.agentscope.core.e2e.providers;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.openai.OpenAIChatFormatter;
import io.agentscope.core.formatter.openai.OpenAIMultiAgentFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;

/**
 * Provider for OpenRouter API - 100% compatible with OpenAI API format.
 *
 * <p>OpenRouter provides access to various LLMs through an OpenAI-compatible interface,
 * allowing our OpenAI HTTP implementation to work seamlessly with multiple model providers.
 */
public class OpenRouterProvider implements ModelProvider {

    private static final String DEFAULT_OPENROUTER_BASE_URL = "https://openrouter.ai/api";
    private final String modelName;
    private final boolean multiAgentFormatter;

    public OpenRouterProvider(String modelName, boolean multiAgentFormatter) {
        this.modelName = modelName;
        this.multiAgentFormatter = multiAgentFormatter;
    }

    @Override
    public ReActAgent createAgent(String name, Toolkit toolkit) {
        String apiKey = System.getenv("OPENROUTER_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("OPENROUTER_API_KEY environment variable is required");
        }

        // Get base URL from environment variable, fallback to default
        String baseUrl = System.getenv("OPENROUTER_BASE_URL");
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = DEFAULT_OPENROUTER_BASE_URL;
        }

        OpenAIChatModel model =
                OpenAIChatModel.builder()
                        .baseUrl(baseUrl)
                        .apiKey(apiKey)
                        .modelName(modelName)
                        .stream(true)
                        .formatter(
                                multiAgentFormatter
                                        ? new OpenAIMultiAgentFormatter()
                                        : new OpenAIChatFormatter())
                        .build();

        return ReActAgent.builder()
                .name(name)
                .model(model)
                .toolkit(toolkit)
                .memory(new InMemoryMemory())
                .build();
    }

    @Override
    public String getProviderName() {
        return "OpenRouter";
    }

    @Override
    public boolean supportsThinking() {
        // OpenRouter supports various models, some with thinking
        return false; // Can be overridden in subclasses
    }

    @Override
    public boolean isEnabled() {
        String apiKey = System.getenv("OPENROUTER_API_KEY");
        return apiKey != null && !apiKey.isEmpty();
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    /**
     * GPT-4o mini - OpenAI's fast, cost-effective model for various tasks.
     */
    public static class GPT4oMini extends OpenRouterProvider {
        public GPT4oMini() {
            super("openai/gpt-4o-mini", false);
        }

        @Override
        public String getProviderName() {
            return "OpenRouter - GPT-4o mini";
        }
    }

    /**
     * GPT-4o mini with Multi-Agent Formatter.
     */
    public static class GPT4oMiniMultiAgent extends OpenRouterProvider {
        public GPT4oMiniMultiAgent() {
            super("openai/gpt-4o-mini", true);
        }

        @Override
        public String getProviderName() {
            return "OpenRouter - GPT-4o mini (MultiAgent)";
        }
    }

    /**
     * Claude 3.5 Sonnet - Anthropic's powerful model for complex reasoning.
     */
    public static class Claude35Sonnet extends OpenRouterProvider {
        public Claude35Sonnet() {
            super("anthropic/claude-3.5-haiku", false);
        }

        @Override
        public String getProviderName() {
            return "OpenRouter - Claude 3.5 Sonnet";
        }
    }

    /**
     * Claude 3.5 Sonnet with Multi-Agent Formatter.
     */
    public static class Claude35SonnetMultiAgent extends OpenRouterProvider {
        public Claude35SonnetMultiAgent() {
            super("anthropic/claude-3.5-haiku", true);
        }

        @Override
        public String getProviderName() {
            return "OpenRouter - Claude 3.5 Sonnet (MultiAgent)";
        }
    }

    /**
     * Qwen VL Plus - Alibaba's vision model for multimodal tasks.
     */
    public static class QwenVL72B extends OpenRouterProvider {
        public QwenVL72B() {
            super("qwen/qwen3-vl-32b-instruct", false);
        }

        @Override
        public String getProviderName() {
            return "OpenRouter - Qwen VL Plus";
        }
    }

    /**
     * Qwen2 VL 72B with Multi-Agent Formatter.
     */
    public static class QwenVL72BMultiAgent extends OpenRouterProvider {
        public QwenVL72BMultiAgent() {
            super("qwen/qwen3-vl-32b-instruct", true);
        }

        @Override
        public String getProviderName() {
            return "OpenRouter - Qwen2 VL 72B (MultiAgent)";
        }
    }
}
