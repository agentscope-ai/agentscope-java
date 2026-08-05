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
package io.agentscope.spring.boot.gemini;

import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.gemini.GeminiChatModel;
import io.agentscope.spring.boot.AgentscopeAutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot auto-configuration for the Gemini model extension.
 */
@AutoConfiguration(before = AgentscopeAutoConfiguration.class)
@EnableConfigurationProperties(GeminiProperties.class)
@ConditionalOnClass(GeminiChatModel.class)
public class GeminiAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "agentscope.model", name = "provider", havingValue = "gemini")
    @ConditionalOnProperty(
            prefix = "agentscope.gemini",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    @ConditionalOnMissingBean(Model.class)
    public GeminiChatModel geminiChatModel(
            GeminiProperties properties,
            ObjectProvider<GeminiChatModelBuilderCustomizer> customizerObjectProvider) {
        String modelName = trimToNull(properties.getModelName());
        if (modelName == null) {
            throw new IllegalStateException(
                    "agentscope.gemini.model-name must be configured when Gemini provider is"
                            + " selected");
        }

        GeminiChatModel.Builder builder =
                GeminiChatModel.builder().modelName(modelName).streamEnabled(properties.isStream());

        // true to use Vertex AI, false/null for Gemini API
        if (Boolean.TRUE.equals(properties.getVertexAI())) {
            String project = trimToNull(properties.getProject());
            if (project == null) {
                throw new IllegalStateException(
                        "agentscope.gemini.project must be configured when Vertex AI mode"
                                + " is enabled");
            }
            builder.project(project).location(trimToNull(properties.getLocation()));
            builder.vertexAI(true);
        } else {
            String apiKey = trimToNull(properties.getApiKey());
            if (apiKey == null) {
                throw new IllegalStateException(
                        "agentscope.gemini.api-key must be configured when Gemini API mode is"
                                + " selected");
            }
            builder.apiKey(apiKey);
            if (Boolean.FALSE.equals(properties.getVertexAI())) {
                builder.vertexAI(false);
            }
        }

        String baseUrl = trimToNull(properties.getBaseUrl());
        if (baseUrl != null) {
            builder.baseUrl(baseUrl);
        }

        customizerObjectProvider
                .orderedStream()
                .forEach(customizer -> customizer.customize(builder));

        return builder.build();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
