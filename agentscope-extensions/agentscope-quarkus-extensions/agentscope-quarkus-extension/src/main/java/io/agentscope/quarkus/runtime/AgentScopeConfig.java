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
package io.agentscope.quarkus.runtime;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Optional;

/**
 * AgentScope configuration for Quarkus.
 *
 * <p>Example configuration in application.properties:
 * <pre>
 * agentscope.model.provider=dashscope
 * agentscope.dashscope.api-key=${DASHSCOPE_API_KEY}
 * agentscope.dashscope.model-name=qwen-plus
 * agentscope.dashscope.stream=true
 * agentscope.agent.name=MyAssistant
 * agentscope.agent.sys-prompt=You are a helpful AI assistant.
 * </pre>
 */
@ConfigMapping(prefix = "agentscope")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface AgentScopeConfig {

    /**
     * Model configuration.
     */
    ModelConfig model();

    /**
     * DashScope configuration.
     */
    DashscopeConfig dashscope();

    /**
     * OpenAI configuration.
     */
    OpenAIConfig openai();

    /**
     * Gemini configuration.
     */
    GeminiConfig gemini();

    /**
     * Anthropic configuration.
     */
    AnthropicConfig anthropic();

    /**
     * Agent configuration.
     */
    AgentConfig agent();

    /**
     * Model provider configuration.
     */
    interface ModelConfig {
        /**
         * Model provider type: dashscope, openai, gemini, anthropic.
         */
        @WithDefault("dashscope")
        String provider();
    }

    /**
     * DashScope configuration.
     */
    interface DashscopeConfig {
        /**
         * API key for DashScope.
         */
        Optional<String> apiKey();

        /**
         * Model name (e.g., qwen-plus, qwen-max).
         */
        @WithDefault("qwen-plus")
        String modelName();

        /**
         * Enable streaming.
         */
        @WithDefault("false")
        boolean stream();

        /**
         * Enable thinking mode.
         */
        @WithDefault("false")
        boolean enableThinking();

        /**
         * Base URL (optional).
         */
        Optional<String> baseUrl();
    }

    /**
     * OpenAI configuration.
     */
    interface OpenAIConfig {
        /**
         * API key for OpenAI.
         */
        Optional<String> apiKey();

        /**
         * Model name (e.g., gpt-4, gpt-4-turbo).
         */
        @WithDefault("gpt-4")
        String modelName();

        /**
         * Enable streaming.
         */
        @WithDefault("false")
        boolean stream();

        /**
         * Base URL (optional).
         */
        Optional<String> baseUrl();
    }

    /**
     * Gemini configuration.
     */
    interface GeminiConfig {
        /**
         * API key for Gemini.
         */
        Optional<String> apiKey();

        /**
         * Model name (e.g., gemini-2.0-flash-exp).
         */
        @WithDefault("gemini-2.0-flash-exp")
        String modelName();

        /**
         * Enable streaming.
         */
        @WithDefault("false")
        boolean stream();

        /**
         * Use Vertex AI (optional).
         */
        @WithDefault("false")
        boolean useVertexAi();

        /**
         * GCP project (for Vertex AI).
         */
        Optional<String> project();

        /**
         * GCP location (for Vertex AI).
         */
        Optional<String> location();
    }

    /**
     * Anthropic configuration.
     */
    interface AnthropicConfig {
        /**
         * API key for Anthropic.
         */
        Optional<String> apiKey();

        /**
         * Model name (e.g., claude-3-5-sonnet-20241022).
         */
        @WithDefault("claude-3-5-sonnet-20241022")
        String modelName();

        /**
         * Enable streaming.
         */
        @WithDefault("false")
        boolean stream();

        /**
         * Base URL (optional).
         */
        Optional<String> baseUrl();
    }

    /**
     * Agent configuration.
     */
    interface AgentConfig {
        /**
         * Agent name.
         */
        @WithDefault("Assistant")
        String name();

        /**
         * System prompt.
         */
        @WithDefault("You are a helpful AI assistant.")
        String sysPrompt();

        /**
         * Maximum iterations.
         */
        @WithDefault("10")
        int maxIters();
    }
}
