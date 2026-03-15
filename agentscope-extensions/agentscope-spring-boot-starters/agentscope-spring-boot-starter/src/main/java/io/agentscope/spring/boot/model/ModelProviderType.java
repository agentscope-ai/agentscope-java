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
package io.agentscope.spring.boot.model;

import io.agentscope.core.model.AnthropicChatModel;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GeminiChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.WebSocketTransport;
import io.agentscope.spring.boot.properties.AgentscopeProperties;
import io.agentscope.spring.boot.properties.AnthropicProperties;
import io.agentscope.spring.boot.properties.DashscopeProperties;
import io.agentscope.spring.boot.properties.GeminiProperties;
import io.agentscope.spring.boot.properties.OpenAIProperties;

/**
 * Enum-based strategy for creating concrete {@link Model} instances from configuration.
 */
public enum ModelProviderType {
    DASHSCOPE {
        @Override
        public Model createModel(
                AgentscopeProperties properties,
                HttpTransport httpTransport,
                WebSocketTransport webSocketTransport) {
            DashscopeProperties dashscope = properties.getDashscope();
            if (!dashscope.isEnabled()) {
                throw new IllegalStateException(
                        "DashScope model auto-configuration is disabled but selected as provider");
            }
            if (dashscope.getApiKey() == null || dashscope.getApiKey().isEmpty()) {
                throw new IllegalStateException(
                        "agentscope.dashscope.api-key must be configured when Dashscope"
                                + " auto-configuration is enabled");
            }

            DashScopeChatModel.Builder builder =
                    DashScopeChatModel.builder()
                            .apiKey(dashscope.getApiKey())
                            .modelName(dashscope.getModelName())
                            .stream(dashscope.isStream())
                            .httpTransport(httpTransport);

            if (dashscope.getEnableThinking() != null) {
                builder.enableThinking(dashscope.getEnableThinking());
            }

            return builder.build();
        }
    },
    OPENAI {
        @Override
        public Model createModel(
                AgentscopeProperties properties,
                HttpTransport httpTransport,
                WebSocketTransport webSocketTransport) {
            OpenAIProperties openai = properties.getOpenai();
            if (!openai.isEnabled()) {
                throw new IllegalStateException(
                        "OpenAI model auto-configuration is disabled but selected as provider");
            }
            if (openai.getApiKey() == null || openai.getApiKey().isEmpty()) {
                throw new IllegalStateException(
                        "agentscope.openai.api-key must be configured when OpenAI provider is"
                                + " selected");
            }

            OpenAIChatModel.Builder builder =
                    OpenAIChatModel.builder()
                            .apiKey(openai.getApiKey())
                            .modelName(openai.getModelName())
                            .stream(openai.isStream())
                            .httpTransport(httpTransport);

            if (openai.getBaseUrl() != null && !openai.getBaseUrl().isEmpty()) {
                builder.baseUrl(openai.getBaseUrl());
            }

            if (openai.getEndpointPath() != null && !openai.getEndpointPath().isEmpty()) {
                builder.endpointPath(openai.getEndpointPath());
            }

            return builder.build();
        }
    },
    GEMINI {
        @Override
        public Model createModel(
                AgentscopeProperties properties,
                HttpTransport httpTransport,
                WebSocketTransport webSocketTransport) {
            GeminiProperties gemini = properties.getGemini();
            if (!gemini.isEnabled()) {
                throw new IllegalStateException(
                        "Gemini model auto-configuration is disabled but selected as provider");
            }
            if ((gemini.getApiKey() == null || gemini.getApiKey().isEmpty())
                    && (gemini.getProject() == null || gemini.getProject().isEmpty())) {
                throw new IllegalStateException(
                        "Either agentscope.gemini.api-key or agentscope.gemini.project must be"
                                + " configured when Gemini provider is selected");
            }

            GeminiChatModel.Builder builder =
                    GeminiChatModel.builder()
                            .apiKey(gemini.getApiKey())
                            .modelName(gemini.getModelName())
                            .streamEnabled(gemini.isStream())
                            .project(gemini.getProject())
                            .location(gemini.getLocation());

            if (gemini.getVertexAI() != null) {
                builder.vertexAI(gemini.getVertexAI());
            }

            return builder.build();
        }
    },
    ANTHROPIC {
        @Override
        public Model createModel(
                AgentscopeProperties properties,
                HttpTransport httpTransport,
                WebSocketTransport webSocketTransport) {
            AnthropicProperties anthropic = properties.getAnthropic();
            if (!anthropic.isEnabled()) {
                throw new IllegalStateException(
                        "Anthropic model auto-configuration is disabled but selected as provider");
            }
            if (anthropic.getApiKey() == null || anthropic.getApiKey().isEmpty()) {
                throw new IllegalStateException(
                        "agentscope.anthropic.api-key must be configured when Anthropic provider is"
                                + " selected");
            }

            AnthropicChatModel.Builder builder =
                    AnthropicChatModel.builder()
                            .apiKey(anthropic.getApiKey())
                            .modelName(anthropic.getModelName())
                            .stream(anthropic.isStream());

            if (anthropic.getBaseUrl() != null && !anthropic.getBaseUrl().isEmpty()) {
                builder.baseUrl(anthropic.getBaseUrl());
            }

            return builder.build();
        }
    };

    /**
     * Create a concrete {@link Model} instance using the given properties.
     *
     * @param properties The Agentscope properties
     * @param httpTransport The HTTP transport
     * @param webSocketTransport The WebSocket transport
     * @return A new model instance
     */
    public abstract Model createModel(
            AgentscopeProperties properties,
            HttpTransport httpTransport,
            WebSocketTransport webSocketTransport);

    /**
     * Create a concrete {@link Model} instance from the given properties.
     *
     * @param properties The Agentscope properties
     * @param httpTransport The HTTP transport
     * @param webSocketTransport The WebSocket transport
     * @return A new model instance
     */
    public static Model createModelFromProperties(
            AgentscopeProperties properties,
            HttpTransport httpTransport,
            WebSocketTransport webSocketTransport) {
        ModelProviderType provider = properties.getModel().getProvider();
        return provider.createModel(properties, httpTransport, webSocketTransport);
    }
}
